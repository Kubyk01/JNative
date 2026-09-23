#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <time.h>
#include <dlfcn.h>
#include <stdio.h>
#include "jnative_runtime.h"

/* ---- Private helpers for Object.c ---- */

// Cache for getClass(): maps vtable address -> ReflectionClass*
static pthread_mutex_t class_cache_lock = PTHREAD_MUTEX_INITIALIZER;
static struct ClassCacheEntry {
    void *vtable;
    struct ReflectionClass *cls;
    struct ClassCacheEntry *next;
} *class_cache = NULL;

/*
 * Resolves the vtable of the class described by cls. Delegates to the
 * runtime's canonical __jnative_own_class_vtable(), which relies on the
 * generator-side invariant that __type_info_X[0] is X's own vtable. See
 * the header comment of that function for the full rationale.
 */
static void *get_class_vtable(struct ReflectionClass *cls) {
    if (cls == NULL || cls->cname == NULL) return NULL;
    return __jnative_own_class_vtable(cls->cname);
}

// Find ReflectionClass for a given vtable (using cache and fallback scan)
static struct ReflectionClass *find_class_by_vtable(void *vtable) {
    if (!vtable) return NULL;

    // Check cache
    pthread_mutex_lock(&class_cache_lock);
    for (struct ClassCacheEntry *e = class_cache; e; e = e->next) {
        if (e->vtable == vtable) {
            pthread_mutex_unlock(&class_cache_lock);
            return e->cls;
        }
    }
    pthread_mutex_unlock(&class_cache_lock);

    // Scan reflect_all_classes and build cache
    struct ReflectionClass **pp = reflect_all_classes;
    while (*pp) {
        struct ReflectionClass *cls = *pp;
        void *cls_vtable = get_class_vtable(cls);
        if (cls_vtable) {
            // Add to cache
            struct ClassCacheEntry *entry = malloc(sizeof(struct ClassCacheEntry));
            if (entry) {
                entry->vtable = cls_vtable;
                entry->cls = cls;
                pthread_mutex_lock(&class_cache_lock);
                entry->next = class_cache;
                class_cache = entry;
                pthread_mutex_unlock(&class_cache_lock);
            }
            if (cls_vtable == vtable) {
                return cls;
            }
        }
        pp++;
    }
    return NULL;
}

/*
 * Resolves the @refclass_* mirror that an array stores in its first word
 * (JAVA_ARR_KLASS_OFFSET). Returns NULL when the word is not a registered
 * class mirror, i.e. when it is an ordinary object's vtable.
 *
 * This is the array counterpart of find_class_by_vtable: the two are told
 * apart by the same test, because a first word that resolves through
 * reflect_all_classes[] can only be an array's class mirror.
 */
static struct ReflectionClass *find_class_object_in_registry(void *ptr) {
    if (!ptr || !reflect_all_classes) return NULL;
    struct ReflectionClass **pp = reflect_all_classes;
    while (*pp) {
        if ((void *)(*pp) == ptr) return *pp;
        pp++;
    }
    return NULL;
}

// Check if a class implements Cloneable
static int is_cloneable(struct ReflectionClass *cls) {
    if (!cls) return 0;
    // Check if any interface is "java/lang/Cloneable"
    struct ReflectionClass **iface = cls->interfaces;
    while (iface && *iface) {
        const char *name = (*iface)->cname;
        if (name && strcmp(name, "java/lang/Cloneable") == 0)
            return 1;
        iface++;
    }
    // Also check superclass recursively
    if (cls->superclass)
        return is_cloneable(cls->superclass);
    return 0;
}

/* ---- Object methods ---- */

// public final native Class<?> getClass();
void *__jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(void *obj) {
    if (!obj) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }
    void *first_word = *(void **)obj;

    /* Array: the first word is the array's own @refclass_* mirror, which
     * resolves directly through the registry. */
    struct ReflectionClass *arr_cls = find_class_object_in_registry(first_word);
    if (arr_cls != NULL) return (void *)arr_cls;

    /* Ordinary object: the first word is the vtable. */
    return (void *)find_class_by_vtable(first_word);
}

// public native int hashCode();
int __jnative_fn_java_lang_Object_hashCode___I(void *obj) {
    if (!obj) return 0;
    // Use object address as hash (similar to OpenJDK's default)
    return (int)((uintptr_t)obj);
}

// protected native Object clone() throws CloneNotSupportedException;
void *__jnative_fn_java_lang_Object_clone___Ljava_lang_Object_(void *obj) {
    if (!obj) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }

    void *first_word = *(void **)obj;

    /*
     * Array: the first word is a @refclass_* mirror, so no vtable lookup
     * applies and the clone is a plain header + payload copy.
     */
    if (find_class_object_in_registry(first_word) != NULL) {
        int32_t length    = *(int32_t *)((char *)obj + JAVA_ARR_LENGTH_OFFSET);
        int32_t elem_size = *(int32_t *)((char *)obj + JAVA_ARR_ELEM_SIZE_OFFSET);

        if (length < 0 || elem_size <= 0 || elem_size > 16) {
            __jnative_throw_clone_not_supported_exception();
            return NULL;
        }

        uint64_t payload = (uint64_t)(uint32_t)length * (uint64_t)(uint32_t)elem_size;
        uint64_t total   = (uint64_t)JAVA_ARR_HDR + payload;

        /*
         * Note: this allocation is never released. JNative has no GC and no
         * general-purpose destructor for objects that escape Object.clone();
         * only the object graph reachable from static fields is walked at
         * exit by the generated __jnative_shutdown. Every clone leaks its
         * backing allocation. This matches the existing behaviour of every
         * other allocation performed inside a C native in this runtime
         * (String.valueOf, Class.getName, etc.); a proper fix would route
         * the allocation through the same lifetime machinery that the
         * optimizer uses for NEW instructions, which is out of scope here.
         */
        void *copy = malloc((size_t)total);
        if (!copy) {
            __jnative_throw_out_of_memory_error_ctx("Object.clone");
            return NULL;
        }
        memcpy(copy, obj, (size_t)total);
        return copy;
    }

    struct ReflectionClass *cls = find_class_by_vtable(first_word);

    if (cls == NULL) {
        __jnative_throw_clone_not_supported_exception();
        return NULL;
    }

    if (!is_cloneable(cls)) {
        __jnative_throw_clone_not_supported_exception();
        return NULL;
    }

    int size = cls->object_size;
    if (size <= 0) {
        __jnative_throw_clone_not_supported_exception();
        return NULL;
    }

    /*
     * Note: this allocation is never released. JNative has no GC and no
     * general-purpose destructor for objects that escape Object.clone();
     * only the object graph reachable from static fields is walked at
     * exit by the generated __jnative_shutdown. Every clone leaks its
     * backing allocation. This matches the existing behaviour of every
     * other allocation performed inside a C native in this runtime
     * (String.valueOf, Class.getName, etc.); a proper fix would route
     * the allocation through the same lifetime machinery that the
     * optimizer uses for NEW instructions, which is out of scope here.
     */
    void *clone = malloc((size_t)size);
    if (!clone) {
        __jnative_throw_out_of_memory_error_ctx("Object.clone");
        return NULL;
    }

    memcpy(clone, obj, (size_t)size);
    return clone;
}

/* ---- Monitor wait/notify support using pthread_cond_t ---- */

// Structure for each object's monitor (mutex + condition variable)
// We store the object pointer to allow lookup.
typedef struct MonitorEx {
    void *obj;
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    int wait_count;
    struct MonitorEx *next;
} MonitorEx;

#define MONITOR_HASH_SIZE 1024
static MonitorEx *monitor_ex_table[MONITOR_HASH_SIZE] = {0};
static pthread_mutex_t monitor_table_lock = PTHREAD_MUTEX_INITIALIZER;

static uint32_t hash_ptr(void *p) {
    return (uint32_t)((uintptr_t)p) % MONITOR_HASH_SIZE;
}

// Get (or create) the monitor structure for an object.
static MonitorEx *get_monitor_ex(void *obj) {
    if (!obj) return NULL;
    uint32_t idx = hash_ptr(obj);
    pthread_mutex_lock(&monitor_table_lock);
    MonitorEx *m = monitor_ex_table[idx];
    while (m) {
        if (m->obj == obj) {
            pthread_mutex_unlock(&monitor_table_lock);
            return m;
        }
        m = m->next;
    }
    // Create new
    MonitorEx *newm = calloc(1, sizeof(MonitorEx));
    if (!newm) {
        pthread_mutex_unlock(&monitor_table_lock);
        return NULL;
    }
    newm->obj = obj;
    pthread_mutex_init(&newm->mutex, NULL);
    pthread_cond_init(&newm->cond, NULL);
    newm->wait_count = 0;
    newm->next = monitor_ex_table[idx];
    monitor_ex_table[idx] = newm;
    pthread_mutex_unlock(&monitor_table_lock);
    return newm;
}

static MonitorEx *find_monitor_ex(void *obj) {
    if (!obj) return NULL;
    uint32_t idx = hash_ptr(obj);
    pthread_mutex_lock(&monitor_table_lock);
    MonitorEx *m = monitor_ex_table[idx];
    while (m) {
        if (m->obj == obj) {
            pthread_mutex_unlock(&monitor_table_lock);
            return m;
        }
        m = m->next;
    }
    pthread_mutex_unlock(&monitor_table_lock);
    return NULL;
}

// public final native void notify();
void __jnative_fn_java_lang_Object_notify___V(void *obj) {
    if (!obj) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    MonitorEx *m = find_monitor_ex(obj);
    if (!m) return; // no waiters
    pthread_mutex_lock(&m->mutex);
    if (m->wait_count > 0) {
        pthread_cond_signal(&m->cond);
    }
    pthread_mutex_unlock(&m->mutex);
}

// public final native void notifyAll();
void __jnative_fn_java_lang_Object_notifyAll___V(void *obj) {
    if (!obj) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    MonitorEx *m = find_monitor_ex(obj);
    if (!m) return;
    pthread_mutex_lock(&m->mutex);
    if (m->wait_count > 0) {
        pthread_cond_broadcast(&m->cond);
    }
    pthread_mutex_unlock(&m->mutex);
}

// private final native void wait0(long timeoutMillis) throws InterruptedException;
// This is the actual implementation of Object.wait(long)
void __jnative_fn_java_lang_Object_wait0__J_V(void *obj, int64_t timeoutMillis) {
    if (!obj) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    // Get the monitor for this object
    MonitorEx *m = get_monitor_ex(obj);
    if (!m) {
        __jnative_throw_exception(NULL);
        return;
    }

    // The calling thread must own the monitor lock.
    // We assume it does (Java semantics checked by bytecode).
    // We'll use the monitor's mutex for waiting.

    pthread_mutex_lock(&m->mutex);
    m->wait_count++;

    struct timespec ts;
    if (timeoutMillis == 0) {
        // Wait indefinitely
        pthread_cond_wait(&m->cond, &m->mutex);
    } else {
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += timeoutMillis / 1000;
        ts.tv_nsec += (timeoutMillis % 1000) * 1000000L;
        if (ts.tv_nsec >= 1000000000) {
            ts.tv_sec++;
            ts.tv_nsec -= 1000000000;
        }
        pthread_cond_timedwait(&m->cond, &m->mutex, &ts);
    }

    m->wait_count--;
    pthread_mutex_unlock(&m->mutex);

    // InterruptedException is not thrown here for simplicity;
    // in a full implementation we would check Thread.interrupted().
}

/* ---- System.nanoTime() ---- */

// public static native long nanoTime();
int64_t __jnative_fn_java_lang_System_nanoTime___J(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000L + ts.tv_nsec;
}

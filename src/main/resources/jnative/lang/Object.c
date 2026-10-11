#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <time.h>
#include <dlfcn.h>
#include <stdio.h>
#include "jnative_runtime.h"

/* ==========================================================================
 *  Registry hash maps
 *
 *  These replace the two linear O(N) walks that dominated every
 *  Object.getClass() call (~80% of the benchmark's instructions):
 *
 *    find_class_object_in_registry()  -- linear scan over reflect_all_classes[]
 *    find_class_by_vtable()           -- linear walk of a linked "cache" under
 *                                        a mutex
 *
 *  Both tables are built once, on first use, and are read-only for the rest
 *  of the process lifetime: reflect_all_classes[] is a constant global
 *  emitted by the LLVM backend; neither the set of classes nor their vtables
 *  ever changes after program start.
 *
 *  A read-only hash table fits this access pattern perfectly: O(1) lookup,
 *  no locking on the hot path, and the exact size is known up front (a
 *  NULL-terminated array). Open addressing with linear probing is used
 *  because the table is never modified after construction -- tombstones are
 *  unnecessary and probing terminates at the first empty slot.
 *
 *  The pointer hash is a 64-bit finalizer (a MurmurHash3 mix step). Addresses
 *  from the malloc/mmap arena are not uniformly distributed, and indexing the
 *  table by masking the low bits would produce long probe runs for the common
 *  case of objects allocated back-to-back on the heap. Mixing the high bits
 *  into the low bits evens out the distribution.
 * ========================================================================== */

typedef struct {
    const void*             key;
    struct ReflectionClass* cls;
} PtrToClassEntry;

typedef struct {
    PtrToClassEntry* entries;
    size_t           capacity;   /* power of two; 0 => not built */
    size_t           size;
} PtrToClassMap;

static PtrToClassMap    class_mirror_map;      /* ReflectionClass* -> itself */
static PtrToClassMap    vtable_to_class_map;   /* vtable pointer   -> class  */
static pthread_once_t   registry_maps_once = PTHREAD_ONCE_INIT;

static inline uintptr_t jnative_hash_pointer(const void* p) {
    uintptr_t h = (uintptr_t)p;
    h ^= h >> 33;
    h *= 0xff51afd7ed558ccdULL;
    h ^= h >> 33;
    h *= 0xc4ceb9fe1a85ec53ULL;
    h ^= h >> 33;
    return h;
}

static struct ReflectionClass* ptr_map_lookup(const PtrToClassMap* m,
                                              const void* key) {
    if (m->capacity == 0 || key == NULL) return NULL;
    size_t mask = m->capacity - 1;
    size_t idx = (size_t)(jnative_hash_pointer(key) & mask);
    for (;;) {
        const PtrToClassEntry* e = &m->entries[idx];
        if (e->key == NULL) return NULL;
        if (e->key == key) return e->cls;
        idx = (idx + 1) & mask;
    }
}

static void ptr_map_insert(PtrToClassMap* m, const void* key,
                           struct ReflectionClass* cls) {
    if (m->capacity == 0 || key == NULL) return;
    size_t mask = m->capacity - 1;
    size_t idx = (size_t)(jnative_hash_pointer(key) & mask);
    while (m->entries[idx].key != NULL) {
        if (m->entries[idx].key == key) {
            /* Already present. Keep the first entry -- in case two classes
             * ever share one vtable in the future (they do not in the image
             * emitted today, but guarding against it is cheaper than
             * debugging table corruption). */
            return;
        }
        idx = (idx + 1) & mask;
    }
    m->entries[idx].key = key;
    m->entries[idx].cls = cls;
    m->size++;
}

/*
 * Resolves the vtable of the class described by cls. Delegates to the
 * runtime's canonical __jnative_own_class_vtable(), which relies on the
 * generator-side invariant that __type_info_X[0] is X's own vtable.
 */
static void *get_class_vtable(struct ReflectionClass *cls) {
    if (cls == NULL || cls->cname == NULL) return NULL;
    return __jnative_own_class_vtable(cls->cname);
}

/*
 * One-time construction of both hash tables.
 *
 * class_mirror_map receives the pointer of every class in reflect_all_classes[]
 * so that the "is this a registered class mirror?" test becomes a single hash
 * probe instead of the linear walk find_class_object_in_registry() used to do.
 *
 * vtable_to_class_map receives the vtable of every class (obtained through
 * __jnative_own_class_vtable, which resolves the class's @__type_info_* global
 * with a single dlsym call), so the reverse lookup "which class owns this
 * vtable?" becomes O(1) instead of O(N). The dlsym cost is paid exactly once --
 * the previous implementation resolved each class's vtable on the first
 * getClass() for a not-yet-cached vtable and then searched a linked list,
 * degrading to the same O(N).
 *
 * Both tables are sized to the next power of two not less than twice the
 * number of classes -- a load factor < 0.5, so the expected probe length is
 * below 2. reflect_all_classes[] is NULL-terminated, so the class count is
 * exact.
 *
 * If allocation of either table fails, capacity stays 0 and the corresponding
 * lookup falls back to the linear path -- correctness is preserved under
 * memory exhaustion at the cost of the old slow path.
 */
static void build_registry_maps(void) {
    if (reflect_all_classes == NULL) return;

    size_t class_count = 0;
    while (reflect_all_classes[class_count] != NULL) class_count++;
    if (class_count == 0) return;

    size_t capacity = 16;
    while (capacity < class_count * 2) capacity <<= 1;

    class_mirror_map.entries = (PtrToClassEntry*)calloc(
        capacity, sizeof(PtrToClassEntry));
    if (class_mirror_map.entries != NULL) {
        class_mirror_map.capacity = capacity;
    }

    vtable_to_class_map.entries = (PtrToClassEntry*)calloc(
        capacity, sizeof(PtrToClassEntry));
    if (vtable_to_class_map.entries != NULL) {
        vtable_to_class_map.capacity = capacity;
    }

    for (size_t i = 0; i < class_count; i++) {
        struct ReflectionClass* cls = reflect_all_classes[i];
        if (cls == NULL) continue;

        if (class_mirror_map.capacity > 0) {
            ptr_map_insert(&class_mirror_map, cls, cls);
        }

        if (vtable_to_class_map.capacity > 0) {
            void* vtable = get_class_vtable(cls);
            if (vtable != NULL) {
                ptr_map_insert(&vtable_to_class_map, vtable, cls);
            }
        }
    }
}

static inline void ensure_registry_maps(void) {
    pthread_once(&registry_maps_once, build_registry_maps);
}

/*
 * Finds the ReflectionClass whose @refclass_* mirror equals ptr. Used by
 * Object.getClass() and __jnative_instanceof() to recognize an array (its
 * first word is the class mirror) and return the array's Class object.
 *
 * O(1) after the tables are built. The linear fallback runs only if calloc
 * could not allocate memory during construction -- a terminal situation for
 * the process.
 */
static struct ReflectionClass *find_class_object_in_registry(void *ptr) {
    if (ptr == NULL || reflect_all_classes == NULL) return NULL;

    ensure_registry_maps();

    if (class_mirror_map.capacity > 0) {
        return ptr_map_lookup(&class_mirror_map, ptr);
    }

    /* Fallback under memory exhaustion: the table could not be built. */
    struct ReflectionClass **pp = reflect_all_classes;
    while (*pp != NULL) {
        if ((void *)(*pp) == ptr) return *pp;
        pp++;
    }
    return NULL;
}

/*
 * Finds the ReflectionClass whose vtable equals vtable. Used by
 * Object.getClass() for ordinary (non-array) objects.
 *
 * O(1) after the tables are built. The linear fallback runs only if calloc
 * could not allocate memory.
 */
static struct ReflectionClass *find_class_by_vtable(void *vtable) {
    if (vtable == NULL) return NULL;

    ensure_registry_maps();

    if (vtable_to_class_map.capacity > 0) {
        return ptr_map_lookup(&vtable_to_class_map, vtable);
    }

    /* Fallback under memory exhaustion: walk the registry, resolve each
     * class's vtable, and look for a match. Slow but correct. */
    struct ReflectionClass **pp = reflect_all_classes;
    while (pp != NULL && *pp != NULL) {
        struct ReflectionClass *cls = *pp++;
        void *cls_vtable = get_class_vtable(cls);
        if (cls_vtable == vtable) return cls;
    }
    return NULL;
}

/* Checks whether a class implements Cloneable. */
static int is_cloneable(struct ReflectionClass *cls) {
    if (!cls) return 0;
    struct ReflectionClass **iface = cls->interfaces;
    while (iface && *iface) {
        const char *name = (*iface)->cname;
        if (name && strcmp(name, "java/lang/Cloneable") == 0)
            return 1;
        iface++;
    }
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

    /*
     * Array: the first word is the array's own @refclass_* mirror, which
     * resolves directly through the registry. Ordinary object: the first
     * word is the vtable.
     *
     * Both checks are O(1) after the registry hash tables are built.
     */
    struct ReflectionClass *arr_cls = find_class_object_in_registry(first_word);
    if (arr_cls != NULL) return (void *)arr_cls;

    return (void *)find_class_by_vtable(first_word);
}

// public native int hashCode();
int __jnative_fn_java_lang_Object_hashCode___I(void *obj) {
    if (!obj) return 0;
    return (int)((uintptr_t)obj);
}

// protected native Object clone() throws CloneNotSupportedException;
void *__jnative_fn_java_lang_Object_clone___Ljava_lang_Object_(void *obj) {
    if (!obj) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }

    void *first_word = *(void **)obj;

    if (find_class_object_in_registry(first_word) != NULL) {
        int32_t length    = *(int32_t *)((char *)obj + JAVA_ARR_LENGTH_OFFSET);
        int32_t elem_size = *(int32_t *)((char *)obj + JAVA_ARR_ELEM_SIZE_OFFSET);

        if (length < 0 || elem_size <= 0 || elem_size > 16) {
            __jnative_throw_clone_not_supported_exception();
            return NULL;
        }

        uint64_t payload = (uint64_t)(uint32_t)length * (uint64_t)(uint32_t)elem_size;
        uint64_t total   = (uint64_t)JAVA_ARR_HDR + payload;

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

    void *clone = malloc((size_t)size);
    if (!clone) {
        __jnative_throw_out_of_memory_error_ctx("Object.clone");
        return NULL;
    }

    memcpy(clone, obj, (size_t)size);
    return clone;
}

/* ---- Monitor wait/notify support using pthread_cond_t ---- */

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

void __jnative_fn_java_lang_Object_notify___V(void *obj) {
    if (!obj) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    MonitorEx *m = find_monitor_ex(obj);
    if (!m) return;
    pthread_mutex_lock(&m->mutex);
    if (m->wait_count > 0) {
        pthread_cond_signal(&m->cond);
    }
    pthread_mutex_unlock(&m->mutex);
}

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

void __jnative_fn_java_lang_Object_wait0__J_V(void *obj, int64_t timeoutMillis) {
    if (!obj) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    MonitorEx *m = get_monitor_ex(obj);
    if (!m) {
        __jnative_throw_exception(NULL);
        return;
    }

    pthread_mutex_lock(&m->mutex);
    m->wait_count++;

    struct timespec ts;
    if (timeoutMillis == 0) {
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
}

/* ---- System.nanoTime() ---- */

int64_t __jnative_fn_java_lang_System_nanoTime___J(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000L + ts.tv_nsec;
}

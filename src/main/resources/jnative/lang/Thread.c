#define _GNU_SOURCE
#include <pthread.h>
#include <time.h>
#include <sched.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <signal.h>
#include <sys/time.h>

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);
void* __jnative_get_exception_object(void);
void __jnative_monitor_enter(void* obj);
void __jnative_monitor_exit(void* obj);

extern const void* vtable_java_lang_Thread[];

#define JLTHREAD_OBJECT_SIZE 200
#define JLTHREAD_NAME_OFFSET 24
#define JLTHREAD_TID_OFFSET  32

typedef struct ThreadState {
    void* java_thread;
    void* carrier_thread;
    pthread_t pthread;
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    int running;
    int started;
    int finished;
    int interrupted;
    int is_daemon;
    int priority;
    int64_t tid;
    void* context_class_loader;
    char* name;
    int64_t stack_size;
    void* runnable;
    void* target;
    void* scoped_value_cache;
    struct ThreadState* next;
} ThreadState;

static pthread_mutex_t thread_table_lock = PTHREAD_MUTEX_INITIALIZER;
static ThreadState* thread_table = NULL;
static _Thread_local ThreadState* tls_state = NULL;
static ThreadState* main_thread_state = NULL;
static int64_t next_tid = 1;

static void* create_thread_object(void) {
    void* t = calloc(1, JLTHREAD_OBJECT_SIZE);
    if (t == NULL) {
        return NULL;
    }
    *(const void**)t = (const void*)vtable_java_lang_Thread;
    *(const char**)((char*)t + JLTHREAD_NAME_OFFSET) = "main";
    *(int64_t*)((char*)t + JLTHREAD_TID_OFFSET) = next_tid++;
    return t;
}

static ThreadState* find_thread_state(void* java_thread) {
    if (!java_thread) return NULL;
    pthread_mutex_lock(&thread_table_lock);
    for (ThreadState* s = thread_table; s; s = s->next) {
        if (s->java_thread == java_thread) {
            pthread_mutex_unlock(&thread_table_lock);
            return s;
        }
    }
    pthread_mutex_unlock(&thread_table_lock);
    return NULL;
}

static ThreadState* get_or_create_thread_state(void* java_thread) {
    if (!java_thread) return NULL;
    pthread_mutex_lock(&thread_table_lock);
    for (ThreadState* s = thread_table; s; s = s->next) {
        if (s->java_thread == java_thread) {
            pthread_mutex_unlock(&thread_table_lock);
            return s;
        }
    }
    ThreadState* s = calloc(1, sizeof(ThreadState));
    if (!s) {
        pthread_mutex_unlock(&thread_table_lock);
        return NULL;
    }
    s->java_thread = java_thread;
    s->priority = 5;
    s->tid = *(int64_t*)((char*)java_thread + JLTHREAD_TID_OFFSET);
    if (s->tid == 0) {
        s->tid = next_tid++;
        *(int64_t*)((char*)java_thread + JLTHREAD_TID_OFFSET) = s->tid;
    }
    pthread_mutex_init(&s->mutex, NULL);
    pthread_cond_init(&s->cond, NULL);
    s->next = thread_table;
    thread_table = s;
    pthread_mutex_unlock(&thread_table_lock);
    return s;
}

static void remove_thread_state(ThreadState* s) {
    if (!s) return;
    pthread_mutex_lock(&thread_table_lock);
    ThreadState** pp = &thread_table;
    while (*pp) {
        if (*pp == s) {
            *pp = s->next;
            break;
        }
        pp = &(*pp)->next;
    }
    pthread_mutex_unlock(&thread_table_lock);
}

static ThreadState* ensure_main_thread(void) {
    if (main_thread_state) return main_thread_state;
    pthread_mutex_lock(&thread_table_lock);
    if (main_thread_state) {
        pthread_mutex_unlock(&thread_table_lock);
        return main_thread_state;
    }
    ThreadState* s = calloc(1, sizeof(ThreadState));
    if (!s) {
        pthread_mutex_unlock(&thread_table_lock);
        return NULL;
    }
    s->java_thread = create_thread_object();
    s->carrier_thread = s->java_thread;
    s->pthread = pthread_self();
    s->running = 1;
    s->started = 1;
    s->priority = 5;
    s->tid = *(int64_t*)((char*)s->java_thread + JLTHREAD_TID_OFFSET);
    pthread_mutex_init(&s->mutex, NULL);
    pthread_cond_init(&s->cond, NULL);
    s->next = thread_table;
    thread_table = s;
    main_thread_state = s;
    tls_state = s;
    pthread_mutex_unlock(&thread_table_lock);
    return s;
}

typedef struct StartArgs {
    ThreadState* state;
    void* runnable;
} StartArgs;

static void* thread_entry(void* arg) {
    StartArgs* sa = (StartArgs*)arg;
    ThreadState* s = sa->state;
    void* runnable = sa->runnable;
    free(sa);

    tls_state = s;

    pthread_mutex_lock(&s->mutex);
    s->running = 1;
    pthread_cond_broadcast(&s->cond);
    pthread_mutex_unlock(&s->mutex);

    if (runnable) {
        extern void __jnative_invoke_runnable(void* runnable);
        __jnative_invoke_runnable(runnable);
    }

    pthread_mutex_lock(&s->mutex);
    s->running = 0;
    s->finished = 1;
    pthread_cond_broadcast(&s->cond);
    pthread_mutex_unlock(&s->mutex);
    return NULL;
}

void* __jnative_fn_java_lang_Thread_currentThread___Ljava_lang_Thread_(void) {
    ThreadState* s = tls_state;
    if (!s) s = ensure_main_thread();
    return s ? s->java_thread : NULL;
}

void __jnative_fn_java_lang_Thread_setCurrentThread__Ljava_lang_Thread__V(void* t) {
    ThreadState* s = get_or_create_thread_state(t);
    if (s) {
        tls_state = s;
        if (!s->carrier_thread) s->carrier_thread = t;
    }
}

void* __jnative_fn_java_lang_Thread_currentCarrierThread___Ljava_lang_Thread_(void) {
    ThreadState* s = tls_state;
    if (!s) s = ensure_main_thread();
    if (s && s->carrier_thread) return s->carrier_thread;
    return s ? s->java_thread : NULL;
}

void __jnative_fn_java_lang_Thread_sleep__J(long millis) {
    if (millis < 0) millis = 0;
    struct timespec req, rem;
    req.tv_sec = millis / 1000;
    req.tv_nsec = (millis % 1000) * 1000000L;
    while (nanosleep(&req, &rem) == -1 && errno == EINTR) {
        if (tls_state && tls_state->interrupted) {
            tls_state->interrupted = 0;
            __jnative_throw_exception(NULL);
        }
        req = rem;
    }
}

void __jnative_fn_java_lang_Thread_yield__V(void) {
    sched_yield();
}

void __jnative_fn_java_lang_Thread_start__V(void* this_thread) {
    if (!this_thread) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    ThreadState* s = get_or_create_thread_state(this_thread);
    if (!s) {
        __jnative_throw_exception(NULL);
        return;
    }
    pthread_mutex_lock(&s->mutex);
    if (s->started) {
        pthread_mutex_unlock(&s->mutex);
        __jnative_throw_exception(NULL);
        return;
    }
    s->started = 1;
    pthread_mutex_unlock(&s->mutex);

    StartArgs* sa = malloc(sizeof(StartArgs));
    if (!sa) {
        __jnative_throw_exception(NULL);
        return;
    }
    sa->state = s;
    sa->runnable = this_thread;

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    if (s->stack_size > 0) {
        pthread_attr_setstacksize(&attr, (size_t)s->stack_size);
    }
    int rc = pthread_create(&s->pthread, &attr, thread_entry, sa);
    pthread_attr_destroy(&attr);
    if (rc != 0) {
        free(sa);
        __jnative_throw_exception(NULL);
    }
}

/*
 * void interrupt0();
 *
 * The actual native hook that java.lang.Thread.interrupt() dispatches to.
 * Sets the per-thread interrupt flag and wakes any thread blocked in
 * Object.wait()/Thread.join() so it can re-check the flag on the next
 * iteration of its wait loop. This is the same body as the legacy
 * interrupt() native — kept as a separate symbol because the JDK's Java
 * source calls interrupt0(), not interrupt().
 */
void __jnative_fn_java_lang_Thread_interrupt0___V(void* this_thread) {
    if (!this_thread) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    ThreadState* s = find_thread_state(this_thread);
    if (!s) return;
    pthread_mutex_lock(&s->mutex);
    s->interrupted = 1;
    pthread_cond_broadcast(&s->cond);
    pthread_mutex_unlock(&s->mutex);
}

/* Legacy alias for callers that still resolve interrupt() directly. */
void __jnative_fn_java_lang_Thread_interrupt__V(void* this_thread) {
    __jnative_fn_java_lang_Thread_interrupt0___V(this_thread);
}

int __jnative_fn_java_lang_Thread_interrupted__Z(void) {
    ThreadState* s = tls_state;
    if (!s) s = ensure_main_thread();
    if (!s) return 0;
    int old = s->interrupted;
    s->interrupted = 0;
    return old;
}

int __jnative_fn_java_lang_Thread_isInterrupted__Z(void* this_thread, int clearInterrupted) {
    if (!this_thread) return 0;
    ThreadState* s = find_thread_state(this_thread);
    if (!s) return 0;
    pthread_mutex_lock(&s->mutex);
    int old = s->interrupted;
    if (clearInterrupted) s->interrupted = 0;
    pthread_mutex_unlock(&s->mutex);
    return old;
}

void __jnative_fn_java_lang_Thread_setPriority__I(void* this_thread, int newPriority) {
    if (!this_thread) return;
    ThreadState* s = find_thread_state(this_thread);
    if (s) s->priority = newPriority;
}

int __jnative_fn_java_lang_Thread_getPriority__I(void* this_thread) {
    if (!this_thread) return 5;
    ThreadState* s = find_thread_state(this_thread);
    return s ? s->priority : 5;
}

void __jnative_fn_java_lang_Thread_setDaemon__Z(void* this_thread, int on) {
    if (!this_thread) return;
    ThreadState* s = find_thread_state(this_thread);
    if (s) s->is_daemon = on ? 1 : 0;
}

int __jnative_fn_java_lang_Thread_isDaemon__Z(void* this_thread) {
    if (!this_thread) return 0;
    ThreadState* s = find_thread_state(this_thread);
    return s ? s->is_daemon : 0;
}

void __jnative_fn_java_lang_Thread_setContextClassLoader__Ljava_lang_ClassLoader_(void* this_thread, void* cl) {
    if (!this_thread) return;
    ThreadState* s = find_thread_state(this_thread);
    if (s) s->context_class_loader = cl;
}

void* __jnative_fn_java_lang_Thread_getContextClassLoader__Ljava_lang_ClassLoader_(void* this_thread) {
    if (!this_thread) return NULL;
    ThreadState* s = find_thread_state(this_thread);
    return s ? s->context_class_loader : NULL;
}

void __jnative_fn_java_lang_Thread_ensureMaterializedForStackWalk__Ljava_lang_Object__V(void* o) {
    (void)o;
}

int64_t __jnative_fn_java_lang_Thread_getId___J(void* this_thread) {
    if (!this_thread) return 0;
    ThreadState* s = find_thread_state(this_thread);
    return s ? s->tid : 0;
}

void* __jnative_fn_java_lang_Thread_getName___Ljava_lang_String_(void* this_thread) {
    if (!this_thread) return NULL;
    ThreadState* s = find_thread_state(this_thread);
    if (!s) return NULL;
    if (!s->name) {
        char buf[32];
        snprintf(buf, sizeof(buf), "Thread-%lld", (long long)s->tid);
        s->name = strdup(buf);
    }
    return s->name;
}

void __jnative_fn_java_lang_Thread_setName__Ljava_lang_String__V(void* this_thread, void* name) {
    if (!this_thread) return;
    ThreadState* s = find_thread_state(this_thread);
    if (!s) return;
    if (s->name) free(s->name);
    s->name = name ? strdup((const char*)name) : NULL;
}

void __jnative_fn_java_lang_Thread_setNativeName__Ljava_lang_String__V(void* this_thread, void* name) {
    __jnative_fn_java_lang_Thread_setName__Ljava_lang_String__V(this_thread, name);
}

int __jnative_fn_java_lang_Thread_isAlive___Z(void* this_thread) {
    if (!this_thread) return 0;
    ThreadState* s = find_thread_state(this_thread);
    return s ? s->running : 0;
}

int __jnative_fn_java_lang_Thread_isVirtual___Z(void* this_thread) {
    (void)this_thread;
    return 0;
}

void __jnative_fn_java_lang_Thread_join__J_V(void* this_thread, int64_t millis) {
    if (!this_thread) return;
    ThreadState* s = find_thread_state(this_thread);
    if (!s || !s->started) return;
    pthread_mutex_lock(&s->mutex);
    if (millis <= 0) {
        while (!s->finished) pthread_cond_wait(&s->cond, &s->mutex);
    } else {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += millis / 1000;
        ts.tv_nsec += (millis % 1000) * 1000000L;
        if (ts.tv_nsec >= 1000000000) { ts.tv_sec++; ts.tv_nsec -= 1000000000; }
        while (!s->finished) {
            if (pthread_cond_timedwait(&s->cond, &s->mutex, &ts) == ETIMEDOUT) break;
        }
    }
    pthread_mutex_unlock(&s->mutex);
}

int __jnative_fn_java_lang_Thread_holdsLock__Ljava_lang_Object__Z(void* obj) {
    (void)obj;
    return 0;
}

void __jnative_fn_java_lang_Thread_onSpinWait___V(void) {
    __asm__ __volatile__("pause" ::: "memory");
}

void __jnative_fn_java_lang_Thread_clearInterrupt__V(void* this_thread) {
    if (!this_thread) return;
    ThreadState* s = find_thread_state(this_thread);
    if (!s) return;
    pthread_mutex_lock(&s->mutex);
    s->interrupted = 0;
    pthread_mutex_unlock(&s->mutex);
}

void __jnative_fn_java_lang_Thread_exit__V(void* this_thread) {
    if (!this_thread) return;
    ThreadState* s = find_thread_state(this_thread);
    if (s) {
        pthread_mutex_lock(&s->mutex);
        s->running = 0;
        s->finished = 1;
        pthread_cond_broadcast(&s->cond);
        pthread_mutex_unlock(&s->mutex);
    }
}

void* __jnative_fn_java_lang_Thread_scopedValueCache____Ljava_lang_Object_(void* this_thread) {
    if (!this_thread) return NULL;
    ThreadState* s = find_thread_state(this_thread);
    return s ? s->scoped_value_cache : NULL;
}

void __jnative_fn_java_lang_Thread_setScopedValueCache___Ljava_lang_Object__V(void* this_thread, void* cache) {
    if (!this_thread) return;
    ThreadState* s = find_thread_state(this_thread);
    if (s) s->scoped_value_cache = cache;
}

int64_t __jnative_fn_java_lang_Thread_getNextThreadIdOffset___J(void) {
    return (int64_t)JLTHREAD_TID_OFFSET;
}

void __jnative_fn_java_lang_Thread_registerNatives___V(void* arg) {
    (void)arg;
}
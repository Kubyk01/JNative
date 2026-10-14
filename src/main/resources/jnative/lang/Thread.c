#define _GNU_SOURCE
#include <pthread.h>
#include <time.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>

#include "jnative_runtime.h"

/* ============================================================================
 * Thread object layout handoff
 * ============================================================================
 *
 * The C runtime synthesises the "main" java.lang.Thread object lazily, the
 * first time the Java layer reaches Thread.currentThread() or
 * Thread.currentCarrierThread(). That happens before any Java-level Thread
 * constructor has run -- inside jdk.internal.misc.CarrierThread.<clinit> --
 * so the object has to be fully valid from C.
 *
 * The offsets below are NOT hard-coded. They are pushed in from @main by
 * LlvmGenerator.generateMain() through __jnative_thread_set_layout(). The
 * values come from LlvmGlobalEmitter.getFieldOffset() and
 * computeObjectSize(), which are the same functions that produced the
 * %struct.java_lang_Thread* LLVM types in the compiled module. Any JDK
 * field reordering changes both sides in lockstep.
 *
 * Until __jnative_thread_set_layout() runs, every offset is -1 and the
 * synthesiser refuses to build an object rather than building one with
 * guessed offsets. This is a hard fail-safe: the alternative is producing
 * an object whose layout silently disagrees with the emitted bytecode.
 * ========================================================================== */

static int32_t THREAD_OBJECT_SIZE         = -1;
static int32_t THREAD_HOLDER_OFFSET       = -1;
static int32_t THREAD_TID_OFFSET          = -1;
static int32_t THREAD_NAME_OFFSET         = -1;
static int32_t THREAD_INTERRUPTLOCK_OFFSET = -1;

static int32_t FH_OBJECT_SIZE        = -1;
static int32_t FH_GROUP_OFFSET       = -1;
static int32_t FH_PRIORITY_OFFSET    = -1;
static int32_t FH_DAEMON_OFFSET      = -1;
static int32_t FH_STATUS_OFFSET      = -1;

static int32_t TG_OBJECT_SIZE        = -1;
static int32_t TG_NAME_OFFSET        = -1;
static int32_t TG_MAXPRIORITY_OFFSET = -1;
static int32_t TG_VMALLOW_OFFSET     = -1;

/*
 * JVMTI thread-status bits, as consumed by jdk.internal.misc.VM.toThreadState.
 * A RUNNABLE|ALIVE combination is what Thread.State.RUNNABLE maps to; it
 * makes isTerminated() == false, so getThreadGroup() does not short-circuit
 * to null on a still-initialising thread.
 */
#define JVMTI_THREAD_STATE_ALIVE      0x0001
#define JVMTI_THREAD_STATE_TERMINATED 0x0002
#define JVMTI_THREAD_STATE_RUNNABLE   0x0004

#define JNATIVE_THREAD_NORM_PRIORITY  5
#define JNATIVE_THREAD_MAX_PRIORITY   10

/**
 * Hand-off from LlvmGenerator.generateMain. Idempotent: a second call with
 * the same values is a no-op, a second call with different values is a
 * programming error and is reported to stderr.
 */
void __jnative_thread_set_layout(
    int32_t thread_object_size,
    int32_t thread_holder_offset,
    int32_t thread_tid_offset,
    int32_t thread_name_offset,
    int32_t thread_interruptlock_offset,
    int32_t fh_object_size,
    int32_t fh_group_offset,
    int32_t fh_priority_offset,
    int32_t fh_daemon_offset,
    int32_t fh_status_offset,
    int32_t tg_object_size,
    int32_t tg_name_offset,
    int32_t tg_maxpriority_offset,
    int32_t tg_vmallow_offset)
{
    if (THREAD_OBJECT_SIZE > 0) {
        if (THREAD_OBJECT_SIZE          != thread_object_size
            || THREAD_HOLDER_OFFSET     != thread_holder_offset
            || THREAD_TID_OFFSET        != thread_tid_offset
            || THREAD_NAME_OFFSET       != thread_name_offset
            || THREAD_INTERRUPTLOCK_OFFSET != thread_interruptlock_offset
            || FH_OBJECT_SIZE           != fh_object_size
            || FH_GROUP_OFFSET          != fh_group_offset
            || FH_PRIORITY_OFFSET       != fh_priority_offset
            || FH_DAEMON_OFFSET         != fh_daemon_offset
            || FH_STATUS_OFFSET         != fh_status_offset
            || TG_OBJECT_SIZE           != tg_object_size
            || TG_NAME_OFFSET           != tg_name_offset
            || TG_MAXPRIORITY_OFFSET    != tg_maxpriority_offset
            || TG_VMALLOW_OFFSET        != tg_vmallow_offset) {
            fprintf(stderr,
                "jnative: warning: __jnative_thread_set_layout called twice "
                "with different values; ignoring the second call\n");
            return;
        }
        return;
    }

    THREAD_OBJECT_SIZE          = thread_object_size;
    THREAD_HOLDER_OFFSET        = thread_holder_offset;
    THREAD_TID_OFFSET           = thread_tid_offset;
    THREAD_NAME_OFFSET          = thread_name_offset;
    THREAD_INTERRUPTLOCK_OFFSET = thread_interruptlock_offset;
    FH_OBJECT_SIZE              = fh_object_size;
    FH_GROUP_OFFSET             = fh_group_offset;
    FH_PRIORITY_OFFSET          = fh_priority_offset;
    FH_DAEMON_OFFSET            = fh_daemon_offset;
    FH_STATUS_OFFSET            = fh_status_offset;
    TG_OBJECT_SIZE              = tg_object_size;
    TG_NAME_OFFSET              = tg_name_offset;
    TG_MAXPRIORITY_OFFSET       = tg_maxpriority_offset;
    TG_VMALLOW_OFFSET           = tg_vmallow_offset;
}

extern const void* vtable_java_lang_Thread[];

/* ============================================================================
 * Global monotonic TID counter.
 * ============================================================================
 *
 * This is the counter that backs Thread.getNextThreadIdOffset() and, through
 * it, java.lang.Thread$ThreadIdentifiers.next(). The Java-side caller does:
 *
 *     U.getAndAddLong(null, NEXT_TID_OFFSET, 1)
 *
 * and Unsafe.getAndAddLong, when its obj argument is the compile-time null
 * literal, treats the offset argument as an absolute address (see
 * effective_address() in Unsafe.c and the contract documented there and in
 * Unsafe.staticFieldOffset / staticFieldBase).
 *
 * The value returned by Thread.getNextThreadIdOffset() must therefore be the
 * ADDRESS of this counter, not the byte offset of Thread.tid inside a Thread
 * object. Returning the field offset (16) produces NULL + 16 = 0x10 and a
 * SIGSEGV on the first Thread construction; returning the address produces
 * NULL + &counter, i.e. &counter, which is exactly what the counter's own
 * increment path needs.
 *
 * Alignment is 8 bytes so the compiler can emit a single `lock xadd` on
 * x86_64 or a single `ldaxr/stlxr` pair on aarch64 without any extra
 * alignment path. Access is exclusively through JNATIVE_NEXT_TID(); no other
 * code path may read or write this variable directly.
 *
 * Starting value is 1: HotSpot's own TID counter also starts at 1, and every
 * observable tid in the runtime (Thread.threadId(), ThreadState.tid, the id
 * returned by Unsafe.getAndAddLong) must be non-zero. A value of 0 is
 * reserved as the "uninitialised slot" sentinel that get_or_create_thread_state
 * uses below.
 * ========================================================================== */
static _Alignas(8) int64_t jnative_next_tid = 1;

/* Atomically reserve and return the next TID.
 *
 * The C side (create_thread_object, get_or_create_thread_state) and the Java
 * side (ThreadIdentifiers.next, via Unsafe.getAndAddLong) both increment this
 * counter. The atomic RMW in this macro and the atomic RMW in
 * Unsafe.getAndAddLong (__atomic_fetch_add on int64_t) are mutually
 * consistent: both use sequential consistency and both operate on the same
 * 8-byte aligned word, so concurrent Java-created and C-synthesised threads
 * receive distinct, strictly monotonic IDs.
 *
 * The value returned is the pre-increment value, matching the semantics of
 * Unsafe.getAndAddLong and of the previous (non-atomic) next_tid++ code path
 * this macro replaces.
 */
#define JNATIVE_NEXT_TID() \
    ((int64_t)__atomic_fetch_add(&jnative_next_tid, 1, __ATOMIC_SEQ_CST))

/*
 * One ThreadState per live java.lang.Thread. The state holds the pthread
 * handle, a per-thread mutex and condition variable used for join/interrupt,
 * and the Java-level bookkeeping (priority, daemon flag, name, context class
 * loader) that does not map onto pthread primitives. Entries are never freed
 * until the process image is torn down; the runtime has no way to know when
 * a Thread object has become unreachable.
 */
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

/* ---------------------------------------------------------------------------
 * Placeholder ThreadGroup for the main thread.
 *
 * The reference JDK creates the root "main" ThreadGroup from the VM bootstrap
 * sequence. This runtime does not run that sequence, so the first caller of
 * Thread.currentThread() -- jdk.internal.misc.CarrierThread from inside its
 * own <clinit> -- would otherwise observe a main Thread whose
 * holder.group is NULL and fail with an NPE inside
 * `new ThreadGroup(parent, "CarrierThreads")`.
 *
 * The placeholder must satisfy exactly the reads the ThreadGroup constructor
 * performs on its parent:
 *
 *     parent.checkAccess()          -> no-op when no SecurityManager
 *     parent.maxPriority            -> copied into the new group
 *     parent.daemon                 -> copied into the new group
 *     parent.vmAllowSuspension      -> copied into the new group
 *     parent.add(child)             -> synchronized, checks destroyed,
 *                                      lazily allocates groups[]
 *
 * Every other field of ThreadGroup (parent, nthreads, threads, ngroups,
 * groups, nUnstartedThreads) is left at zero/NULL from calloc and is not read
 * by that constructor.
 * ------------------------------------------------------------------------- */
static void* create_main_thread_group(void) {
    if (TG_OBJECT_SIZE < 0) return NULL;

    void* vtable = __jnative_own_class_vtable("java/lang/ThreadGroup");
    if (vtable == NULL) return NULL;

    void* tg = calloc(1, (size_t)TG_OBJECT_SIZE);
    if (tg == NULL) return NULL;

    *(void**)tg = vtable;
    *(void**)((char*)tg + TG_NAME_OFFSET) =
        __jnative_make_string_obj("main", 4);
    *(int32_t*)((char*)tg + TG_MAXPRIORITY_OFFSET) =
        JNATIVE_THREAD_MAX_PRIORITY;
    /* vmAllowSuspension only exists on JDKs that predate JDK-8283117; on
     * newer JDKs the emitter reports -1 and the field is simply absent. */
    if (TG_VMALLOW_OFFSET >= 0) {
        *(uint8_t*)((char*)tg + TG_VMALLOW_OFFSET) = 1;
    }

    return tg;
}

/* ---------------------------------------------------------------------------
 * Thread$FieldHolder for the main thread.
 *
 * The JDK's Thread.threadState()/getPriority()/isDaemon()/getThreadGroup()
 * read the corresponding fields from this holder. A NULL holder is the exact
 * condition that produced the NPE reported in fun.txt.
 * ------------------------------------------------------------------------- */
static void* create_main_thread_field_holder(void* group) {
    if (FH_OBJECT_SIZE < 0) return NULL;

    void* vtable = __jnative_own_class_vtable("java/lang/Thread$FieldHolder");
    if (vtable == NULL) return NULL;

    void* fh = calloc(1, (size_t)FH_OBJECT_SIZE);
    if (fh == NULL) return NULL;

    *(void**)fh = vtable;
    *(void**)((char*)fh + FH_GROUP_OFFSET)    = group;
    *(int32_t*)((char*)fh + FH_PRIORITY_OFFSET) =
        JNATIVE_THREAD_NORM_PRIORITY;
    *(uint8_t*)((char*)fh + FH_DAEMON_OFFSET) = 0;
    *(int32_t*)((char*)fh + FH_STATUS_OFFSET) =
        JVMTI_THREAD_STATE_ALIVE | JVMTI_THREAD_STATE_RUNNABLE;

    return fh;
}

/* ---------------------------------------------------------------------------
 * The main Thread object itself.
 *
 * Every field that Java code can observe before the real Thread constructor
 * has had a chance to run must already be populated:
 *
 *   vtable          -> vtable_java_lang_Thread
 *   tid             -> JNATIVE_NEXT_TID()  (ThreadIdentifiers reads this)
 *   name            -> a real java.lang.String (Thread.getName returns it)
 *   holder          -> FieldHolder whose group is a valid ThreadGroup
 * ------------------------------------------------------------------------- */
/*
 * The interruptLock field is a plain java.lang.Object that Thread.<init>
 * allocates with `new Object()` and that every entry into a blocking I/O
 * operation reaches through Thread.blockedOn:
 *
 *     static void blockedOn(Interruptible b) {
 *         Thread me = Thread.currentThread();
 *         synchronized (me.interruptLock) {
 *             me.nioBlocker = b;
 *         }
 *     }
 *
 * The synchronised block compiles to __jnative_monitor_enter on the
 * object reference. If the reference is NULL, the monitor helper throws
 * NullPointerException with the context string "monitor", which
 * propagates out of AbstractInterruptibleChannel.blockedOn, through
 * AbstractInterruptibleChannel.begin, into the try/finally of
 * FileChannelImpl.readInternal. That finally block unconditionally calls
 * threads.remove(ti) with ti still at its initialiser value (-1), so the
 * AIOOBE from the out-of-range index replaces the NPE, and the caller
 * sees only the secondary failure:
 *
 *     java.lang.ArrayIndexOutOfBoundsException: Array index out of bounds
 *     in sun.nio.ch.NativeThreadSet.remove(IV)
 *         at jdk.internal.module.SystemModuleFinders$SystemImage.<clinit>
 *         ...
 *
 * The object must therefore be a real java.lang.Object with a valid
 * vtable, allocated through the same runtime path that any other
 * C-constructed Java object uses. Object.<init> is a no-op in the JDK,
 * so calloc-plus-vtable is exactly what the Java constructor would have
 * produced.
 *
 * The allocation is guarded by THREAD_INTERRUPTLOCK_OFFSET >= 0 so that
 * a JDK release which removes or renames the field (the name has been
 * stable across JDK 17-22, but a rename is not impossible) does not
 * produce a write at a nonsensical offset. In that case the field is
 * left at its calloc'ed zero and the same NPE would reappear — which is
 * still strictly better than a corrupted write, and the handoff
 * mechanism makes the discrepancy visible in the build log the moment
 * the field disappears.
 */
static void* create_thread_object(void) {
    if (THREAD_OBJECT_SIZE < 0) return NULL;

    void* vtable = __jnative_own_class_vtable("java/lang/Thread");
    if (vtable == NULL) return NULL;

    void* t = calloc(1, (size_t)THREAD_OBJECT_SIZE);
    if (t == NULL) return NULL;

    *(void**)t = vtable;

    if (THREAD_NAME_OFFSET >= 0) {
        *(void**)((char*)t + THREAD_NAME_OFFSET) =
            __jnative_make_string_obj("main", 4);
    }

    if (THREAD_TID_OFFSET >= 0) {
        int64_t tid = JNATIVE_NEXT_TID();
        *(int64_t*)((char*)t + THREAD_TID_OFFSET) = tid;
    }

    if (THREAD_HOLDER_OFFSET >= 0) {
        void* group  = create_main_thread_group();
        void* holder = create_main_thread_field_holder(group);
        *(void**)((char*)t + THREAD_HOLDER_OFFSET) = holder;
    }

    if (THREAD_INTERRUPTLOCK_OFFSET >= 0) {
        /*
         * jnative_alloc_object() looks the class up in reflect_all_classes,
         * allocates object_size bytes, and writes the class's vtable into
         * word 0 — the exact state that `new java.lang.Object()` would have
         * produced, because java.lang.Object declares no instance fields
         * and its <init> is empty.
         *
         * The reflect registry is guaranteed to contain java/lang/Object:
         * LlvmGenerator.ensureExternalDeclarations() force-loads it before
         * any function is emitted, and generateReflectionData() emits a
         * @refclass_java_lang_Object constant for it unconditionally.
         */
        ReflectionClass* obj_cls = jnative_class_by_name("java/lang/Object");
        void* interrupt_lock = (obj_cls != NULL)
            ? jnative_alloc_object(obj_cls)
            : NULL;
        *(void**)((char*)t + THREAD_INTERRUPTLOCK_OFFSET) = interrupt_lock;
    }

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
    /* Guard: if the runtime has not received its layout yet, do not
     * fabricate a tid. The Java caller would read garbage from a
     * non-existent field. Returning 0 keeps the ThreadState consistent
     * and the layout is guaranteed to arrive before any user-visible
     * thread operation. */
    if (THREAD_TID_OFFSET < 0) {
        pthread_mutex_unlock(&thread_table_lock);
        return s;
    }
    s->tid = *(int64_t*)((char*)java_thread + THREAD_TID_OFFSET);
    if (s->tid == 0) {
        /*
         * Zero means the Java-level Thread constructor has not yet stored
         * a tid into the object — that is the normal state for a Thread
         * whose <init> has not run (the C-synthesised main thread) or for
         * a Thread created before the counter was reachable from Java.
         * Reserve a fresh id atomically so a concurrent Java-level
         * ThreadIdentifiers.next() cannot hand out the same value.
         */
        s->tid = JNATIVE_NEXT_TID();
        *(int64_t*)((char*)java_thread + THREAD_TID_OFFSET) = s->tid;
    }
    pthread_mutex_init(&s->mutex, NULL);
    pthread_cond_init(&s->cond, NULL);
    s->next = thread_table;
    thread_table = s;
    pthread_mutex_unlock(&thread_table_lock);
    return s;
}

/*
 * Synthesise the state for the process's real main thread. Called lazily the
 * first time Thread.currentThread() is reached, which can happen before any
 * Java-level Thread constructor has run.
 */
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
    if (THREAD_TID_OFFSET >= 0) {
        s->tid = *(int64_t*)((char*)s->java_thread + THREAD_TID_OFFSET);
    }
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

/*
 * pthread entry point. Publishes the ThreadState into the thread-local slot,
 * marks the thread running, invokes the Java-level Runnable, and on return
 * marks the thread finished and wakes any joiner.
 */
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

/*
 * =========================================================================
 * Current-thread identification
 * =========================================================================
 */

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

/*
 * =========================================================================
 * sleep / yield
 * =========================================================================
 */

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

/*
 * JDK 17+ rename: yield0() forwards to the legacy yield().
 */
void __jnative_fn_java_lang_Thread_yield0___V(void) {
    __jnative_fn_java_lang_Thread_yield__V();
}

void __jnative_fn_java_lang_Thread_sleep0__J_V(long millis) {
    __jnative_fn_java_lang_Thread_sleep__J(millis);
}

/*
 * =========================================================================
 * start
 * =========================================================================
 */

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
 * JDK 17+ rename: start0() forwards to the legacy start().
 */
void __jnative_fn_java_lang_Thread_start0___V(void* this_thread) {
    __jnative_fn_java_lang_Thread_start__V(this_thread);
}

/*
 * =========================================================================
 * interrupt
 * =========================================================================
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

void __jnative_fn_java_lang_Thread_clearInterrupt__V(void* this_thread) {
    if (!this_thread) return;
    ThreadState* s = find_thread_state(this_thread);
    if (!s) return;
    pthread_mutex_lock(&s->mutex);
    s->interrupted = 0;
    pthread_mutex_unlock(&s->mutex);
}

/*
 * JDK 17+ rename for Thread.clearInterrupt(). The reference implementation
 * additionally clears a per-thread interrupt event object on Windows; this
 * runtime has no such object, and the blocking primitives (Object.wait,
 * Thread.sleep, Thread.join) re-check the flag on every wake-up, so clearing
 * the flag is all that is required.
 */
void __jnative_fn_java_lang_Thread_clearInterruptEvent___V(void* this_thread) {
    __jnative_fn_java_lang_Thread_clearInterrupt__V(this_thread);
}

/*
 * =========================================================================
 * priority / daemon / context class loader
 * =========================================================================
 */

void __jnative_fn_java_lang_Thread_setPriority__I(void* this_thread, int newPriority) {
    if (!this_thread) return;
    ThreadState* s = find_thread_state(this_thread);
    if (s) s->priority = newPriority;
}

void __jnative_fn_java_lang_Thread_setPriority0__I_V(void* this_thread, int newPriority) {
    __jnative_fn_java_lang_Thread_setPriority__I(this_thread, newPriority);
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

/*
 * =========================================================================
 * tid / name
 * =========================================================================
 */

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
        int n = snprintf(buf, sizeof(buf), "Thread-%lld", (long long)s->tid);
        s->name = strdup(buf);
        (void)n;
    }
    return __jnative_make_string_obj(s->name, (int32_t)strlen(s->name));
}

void __jnative_fn_java_lang_Thread_setName__Ljava_lang_String__V(void* this_thread, void* name) {
    if (!this_thread) return;
    ThreadState* s = find_thread_state(this_thread);
    if (!s) return;
    if (s->name) { free(s->name); s->name = NULL; }
    if (name != NULL) {
        int32_t len = 0;
        const char* bytes = __jnative_read_string_bytes(name, &len);
        s->name = malloc((size_t)len + 1);
        if (s->name) {
            memcpy(s->name, bytes, (size_t)len);
            s->name[len] = '\0';
        }
    }
}

void __jnative_fn_java_lang_Thread_setNativeName__Ljava_lang_String__V(void* this_thread, void* name) {
    __jnative_fn_java_lang_Thread_setName__Ljava_lang_String__V(this_thread, name);
}

/*
 * =========================================================================
 * state queries
 * =========================================================================
 */

int __jnative_fn_java_lang_Thread_isAlive___Z(void* this_thread) {
    if (!this_thread) return 0;
    ThreadState* s = find_thread_state(this_thread);
    return s ? s->running : 0;
}

int __jnative_fn_java_lang_Thread_isVirtual___Z(void* this_thread) {
    (void)this_thread;
    return 0;
}

/*
 * =========================================================================
 * join
 * =========================================================================
 */

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

/*
 * =========================================================================
 * misc
 * =========================================================================
 */

int __jnative_fn_java_lang_Thread_holdsLock__Ljava_lang_Object__Z(void* obj) {
    (void)obj;
    return 0;
}

void __jnative_fn_java_lang_Thread_onSpinWait___V(void) {
    __asm__ __volatile__("pause" ::: "memory");
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

/*
 * =========================================================================
 * scoped-value cache (ScopedValue / StructuredTaskScope)
 * =========================================================================
 */

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

/* ---------------------------------------------------------------------------
 * java.lang.Thread.getNextThreadIdOffset() -> long
 *
 * This method exists to answer a single question for
 * java.lang.Thread$ThreadIdentifiers:
 *
 *     "What address do I pass to Unsafe.getAndAddLong with a NULL base
 *      in order to increment the VM's global thread-ID counter?"
 *
 * The Java-side consumer is (JDK 21, java.base/java/lang/Thread.java):
 *
 *     private static class ThreadIdentifiers {
 *         private static final Unsafe U;
 *         private static final long NEXT_TID_OFFSET;
 *         static {
 *             U = Unsafe.getUnsafe();
 *             NEXT_TID_OFFSET = Thread.getNextThreadIdOffset();
 *         }
 *         static long next() {
 *             return U.getAndAddLong(null, NEXT_TID_OFFSET, 1);
 *         }
 *     }
 *
 * The base argument is a compile-time `null` literal in the bytecode. The
 * runtime's Unsafe.getAndAddLong (see Unsafe.c) and HotSpot's
 * Unsafe_NativeGetAndAddLong both compute:
 *
 *     effective_address(NULL, offset) = (void*)(uintptr_t)offset
 *
 * i.e. with a NULL base, the offset value IS the address. The contract is
 * documented in Unsafe.c:effective_address and is the same one
 * Unsafe.staticFieldOffset / Unsafe.staticFieldBase rely on.
 *
 * HotSpot satisfies this contract by returning the address of the
 * VM-internal TID counter. This runtime must do the same: return
 * &jnative_next_tid, which is the exact counter that C-synthesised threads
 * (main thread, JDK-internal helper threads) draw their IDs from via
 * JNATIVE_NEXT_TID(). Returning the byte offset of Thread.tid inside the
 * Thread object instead produces NULL + 16 = 0x10 and a SIGSEGV on the very
 * first Thread construction — precisely the crash this method was fixed to
 * eliminate.
 *
 * ---------------------------------------------------------------------------
 * Why the previous THREAD_TID_OFFSET >= 0 gate has been removed
 * ---------------------------------------------------------------------------
 *
 * The previous revision returned -1 when the layout handoff had not run, on
 * the theory that ThreadIdentifiers.<clinit> would "fall back to a
 * conservative path". That theory is false. The actual bytecode of
 * ThreadIdentifiers.next() reads:
 *
 *     return U.getAndAddLong(null, NEXT_TID_OFFSET, 1);
 *
 * There is no check of NEXT_TID_OFFSET anywhere in the JDK. -1 would have
 * produced effective_address(NULL, -1) = (void*)-1, i.e. a SIGSEGV at
 * 0xFFFFFFFFFFFFFFFF, which is no better than 0x10 and strictly harder to
 * diagnose.
 *
 * The gate is not just unhelpful — it is wrong in principle, because the
 * value this method returns is a link-time constant. The address of
 * jnative_next_tid is valid before __jnative_thread_set_layout has ever been
 * called, during it, and after it. There is no state the layout handoff can
 * be in that would make the answer different, and therefore no reason to
 * gate on it.
 *
 * ---------------------------------------------------------------------------
 * Why the previous `(int64_t)THREAD_TID_OFFSET` was wrong
 * ---------------------------------------------------------------------------
 *
 * THREAD_TID_OFFSET is the byte offset of the Thread.tid field inside a
 * Thread object, computed by LlvmGlobalEmitter.getFieldOffset(). That offset
 * is consumed by every direct GET_FIELD / PUT_FIELD that reads or writes tid
 * from generated bytecode, and by the C accessors in this file:
 *
 *     *(int64_t*)((char*)t + THREAD_TID_OFFSET) = tid;
 *     s->tid = *(int64_t*)((char*)java_thread + THREAD_TID_OFFSET);
 *
 * It is not an address and cannot be interpreted as one. Passing it to
 * Unsafe.getAndAddLong with a NULL base dereferences address 16. The two
 * meanings ("field offset inside an object" vs. "absolute address of a
 * VM-owned counter") are incompatible and must be kept in separate
 * variables; this method's job is to bridge from the latter meaning to the
 * Java side.
 * ------------------------------------------------------------------------- */
int64_t __jnative_fn_java_lang_Thread_getNextThreadIdOffset___J(void) {
    return (int64_t)(uintptr_t)&jnative_next_tid;
}

/*
 * =========================================================================
 * registerNatives
 * =========================================================================
 */

void __jnative_fn_java_lang_Thread_registerNatives___V(void* arg) {
    (void)arg;
}

/*
 * private static native Thread[] getThreads();
 *
 * Snapshot of every java.lang.Thread object known to the runtime's
 * ThreadState table. The table is process-wide and is populated by
 * get_or_create_thread_state() the first time a Thread object is seen
 * by any of the natives in this file; the synthetic main thread is
 * inserted at process start-up by ensure_main_thread().
 *
 * The Java-side callers are Thread.getAllStackTraces() and
 * System$2.getAllThreads() (the jdk.internal.misc.JavaLangAccess
 * implementation). Both iterate the result and query per-thread state
 * through the other natives in this file, so the objects returned here
 * need only be the same Thread instances the other natives will
 * recognise.
 *
 * A thread whose ThreadState has already been reaped (finished == 1
 * and running == 0) is included in the snapshot: Thread.getAllStackTraces
 * is specified to return every live thread, and a thread whose Java-level
 * run() has returned is still "live" in the language sense until it has
 * been joined or GCed. Filtering it out here would make the result of
 * getAllStackTraces disagree with Thread.isAlive().
 *
 * The result is always non-null. When the ThreadState table is empty —
 * which can only happen if neither currentThread() nor any of the
 * constructors have run yet — the function returns an empty Thread[]
 * rather than null, matching the Java-level contract of
 * Thread.getAllStackTraces being defined for every program state.
 */
void* __jnative_fn_java_lang_Thread_getThreads____Ljava_lang_Thread_(void) {
    pthread_mutex_lock(&thread_table_lock);

    int32_t count = 0;
    for (ThreadState* s = thread_table; s; s = s->next) {
        if (s->java_thread != NULL) count++;
    }

    void* array = jnative_ref_array_of_class(
        NULL, count, "[Ljava/lang/Thread;");

    if (array != NULL) {
        void** slots = (void**)((char*)array + JAVA_ARR_HDR);
        int32_t i = 0;
        for (ThreadState* s = thread_table; s && i < count; s = s->next) {
            if (s->java_thread != NULL) {
                slots[i++] = s->java_thread;
            }
        }
    }

    pthread_mutex_unlock(&thread_table_lock);

    if (array == NULL) {
        __jnative_throw_out_of_memory_error_ctx("Thread.getThreads");
    }
    return array;
}
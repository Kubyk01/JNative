#include <pthread.h>
#include <time.h>
#include <sched.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

/* runtime */
__attribute__((noreturn)) void __jnative_throw_exception(void* exc);
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);
void* __jnative_get_exception_object(void);

/* ---------------------------------------------------------------------------
 * Per-thread state.
 * ------------------------------------------------------------------------- */
static _Thread_local void* tls_current_thread        = NULL;
static _Thread_local void* tls_current_carrier_thread = NULL;

/* Fallback object that is returned by currentThread() if setCurrentThread
 * was never called (e.g. from the very first native call at program start). */
static void* main_thread_object = NULL;

static void* create_thread_object(void) {
    /* Minimal placeholder object: [vtable(8)] [field(8)] */
    void* obj = calloc(1, 16);
    return obj;
}

static void* ensure_main_thread(void) {
    if (!main_thread_object) {
        main_thread_object = create_thread_object();
    }
    return main_thread_object;
}

/* public static native Thread currentThread(); */
void* __jnative_fn_java_lang_Thread_currentThread___Ljava_lang_Thread_(void) {
    if (tls_current_thread != NULL) return tls_current_thread;
    return ensure_main_thread();
}

/* public static native void setCurrentThread(Thread t); */
void __jnative_fn_java_lang_Thread_setCurrentThread__Ljava_lang_Thread__V(void* t) {
    tls_current_thread = t;
    if (tls_current_carrier_thread == NULL) {
        /* The first thread set on a carrier becomes the carrier itself. */
        tls_current_carrier_thread = t;
    }
}

/* public static native Thread currentCarrierThread(); */
void* __jnative_fn_java_lang_Thread_currentCarrierThread___Ljava_lang_Thread_(void) {
    if (tls_current_carrier_thread != NULL) return tls_current_carrier_thread;
    return __jnative_fn_java_lang_Thread_currentThread___Ljava_lang_Thread_();
}

/* public static native void sleep(long millis) throws InterruptedException; */
void __jnative_fn_java_lang_Thread_sleep__J(long millis) {
    if (millis <= 0) {
        if (millis == 0) sched_yield();
        return;
    }
    struct timespec req, rem;
    req.tv_sec = millis / 1000;
    req.tv_nsec = (millis % 1000) * 1000000L;
    while (nanosleep(&req, &rem) == -1) {
        req = rem;
    }
}

/* public static native void yield(); */
void __jnative_fn_java_lang_Thread_yield__V(void) {
    sched_yield();
}

/* public native void start(); */
void __jnative_fn_java_lang_Thread_start__V(void* this_thread) {
    /* Virtual / platform thread start is not implemented for this runtime. */
    (void)this_thread;
}

/* public native void interrupt(); */
void __jnative_fn_java_lang_Thread_interrupt__V(void* this_thread) {
    (void)this_thread;
}

/* public static native boolean interrupted(); */
int __jnative_fn_java_lang_Thread_interrupted__Z(void) {
    return 0;
}

/* public native boolean isInterrupted(boolean ClearInterrupted); */
int __jnative_fn_java_lang_Thread_isInterrupted__Z(int clearInterrupted) {
    (void)clearInterrupted;
    return 0;
}

/* public native void setPriority(int newPriority); */
void __jnative_fn_java_lang_Thread_setPriority__I(void* this_thread, int newPriority) {
    (void)this_thread; (void)newPriority;
}

/* public native int getPriority(); */
int __jnative_fn_java_lang_Thread_getPriority__I(void* this_thread) {
    (void)this_thread;
    return 5;
}

/* public native void setDaemon(boolean on); */
void __jnative_fn_java_lang_Thread_setDaemon__Z(void* this_thread, int on) {
    (void)this_thread; (void)on;
}

/* public native boolean isDaemon(); */
int __jnative_fn_java_lang_Thread_isDaemon__Z(void* this_thread) {
    (void)this_thread;
    return 0;
}

/* public native void setContextClassLoader(ClassLoader cl); */
void __jnative_fn_java_lang_Thread_setContextClassLoader__Ljava_lang_ClassLoader_(void* this_thread, void* cl) {
    (void)this_thread; (void)cl;
}

/* public native ClassLoader getContextClassLoader(); */
void* __jnative_fn_java_lang_Thread_getContextClassLoader__Ljava_lang_ClassLoader_(void* this_thread) {
    (void)this_thread;
    return NULL;
}
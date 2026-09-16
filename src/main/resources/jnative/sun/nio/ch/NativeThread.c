#define _GNU_SOURCE
#include <stdint.h>
#include <pthread.h>
#include <signal.h>
#include <unistd.h>
#include <errno.h>

/*
 * sun.nio.ch.NativeThread
 *
 * On HotSpot this class hands out an opaque 64-bit identifier for the
 * calling thread and delivers a POSIX signal to that thread on request.
 * The identifier is used as the thread handle stored by the async
 * I/O machinery (sun.nio.ch.NativeThreadSet) while a blocking
 * operation is in progress, and the signal is what wakes the thread out
 * of a blocking syscall so it can re-check the interrupt state.
 *
 * In this runtime the blocking I/O primitives (read0/pread0/... in
 * UnixFileDispatcherImpl.c) never block indefinitely on an
 * uninterruptible kernel path: they retry only on EINTR and surface the
 * errno to Java in every other case, so the caller's normal
 * interrupt-check path is reached without any external wake-up. The
 * signal-based preemption that HotSpot performs from
 * NativeThread.signal() is therefore unnecessary; NativeThread.signal()
 * is a no-op, and the identifier the class hands out only needs to be
 * stable and unique per thread for the duration of a NativeThreadSet
 * entry.
 *
 * pthread_self() gives exactly that: a process-wide unique value that
 * is valid for the lifetime of the thread and is not reused until the
 * thread is joined. On Linux/glibc it is already a 64-bit value.
 */

/* ---------------------------------------------------------------------------
 * static native void init();
 *
 * Called from NativeThread.<clinit>. On HotSpot this hook installs the
 * per-thread signal handler used by NativeThread.signal. Because this
 * runtime never delivers such signals, there is nothing to install.
 * ------------------------------------------------------------------------- */
void __jnative_fn_sun_nio_ch_NativeThread_init___V(void) {
}

/* ---------------------------------------------------------------------------
 * static native long current0();
 *
 * Returns the opaque identifier of the calling thread. Used by the Java
 * layer as the handle it stores in NativeThreadSet.
 * ------------------------------------------------------------------------- */
int64_t __jnative_fn_sun_nio_ch_NativeThread_current0___J(void) {
    pthread_t self = pthread_self();
    return (int64_t)(uintptr_t)self;
}

/* ---------------------------------------------------------------------------
 * static native void signal(long nativeThread);
 *
 * On HotSpot this writes a specific byte to a self-pipe that the target
 * thread monitors, so that a blocking syscall aborts with EINTR and the
 * NIO layer can re-check the interrupt state. As explained above, this
 * runtime's blocking primitives retry only on EINTR and surface every
 * other errno immediately, so the Java interrupt path is always reached
 * without external wake-up. Delivering SIGUSR1 to an arbitrary thread
 * from within a signal-based runtime that also installs SIGSEGV/SIGBUS
 * handlers would risk unrelated interference, so the correct behaviour
 * is to leave the target thread alone.
 * ------------------------------------------------------------------------- */
void __jnative_fn_sun_nio_ch_NativeThread_signal__J_V(int64_t native_thread) {
    (void)native_thread;
}
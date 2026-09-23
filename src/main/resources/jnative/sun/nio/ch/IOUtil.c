#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <limits.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/uio.h>

#include "jnative_runtime.h"

/*
 * sun.nio.ch.IOUtil — the small helpers that the rest of the NIO
 * channel stack uses when it needs to talk to a raw file descriptor.
 *
 * The class exposes four natives:
 *
 *   initIDs()                            — one-shot, <clinit>
 *   iovMax()                             — maximum iovec count
 *   writevMax()                          — maximum single-writev byte count
 *   fdVal(FileDescriptor)                — raw kernel fd extraction
 *   configureBlocking(FileDescriptor, boolean)
 *                                        — set/clear O_NONBLOCK
 *
 * The FileDescriptor layout is the same one used by every other native
 * in this runtime: vtable at offset 0, raw kernel fd at offset
 * FD_OFFSET (see jnative_runtime.h), handle at the following slot. The
 * raw fd is the only field any function here needs.
 */

static inline int32_t fd_of(void* fd_obj) {
    return *(int32_t*)((char*)fd_obj + FD_OFFSET);
}

/*
 * static native void initIDs();
 *
 * Called from the static initializer of sun.nio.ch.IOUtil. The
 * reference JDK uses this hook to bind the class's native methods to
 * their JVM-side implementations; this runtime resolves every native
 * method through its statically-linked
 * __jnative_fn_<class>_<method>_<desc> symbol emitted by the LLVM
 * backend, so there is nothing to register. The symbol must exist
 * because IOUtil.<clinit> emits a native call to it.
 */
void __jnative_fn_sun_nio_ch_IOUtil_initIDs___V(void) {
}

/*
 * static native int iovMax();
 *
 * The maximum number of iovec entries that a single readv(2) /
 * writev(2) call may carry.
 *
 * Resolution order:
 *
 *   1. IOV_MAX from <sys/uio.h> / <limits.h>, if the toolchain defines
 *      it. This is the authoritative POSIX value and the one the
 *      running kernel will actually accept.
 *
 *   2. sysconf(_SC_IOV_MAX), if IOV_MAX is not a compile-time
 *      constant. This is the runtime query path and returns the same
 *      number.
 *
 *   3. A conservative fallback of 16. The POSIX minimum is 16, so any
 *      value up to and including 16 is guaranteed to be accepted by
 *      every conforming kernel. If both compile-time and runtime
 *      queries fail, 16 is the largest count that can be used without
 *      risking EINVAL.
 *
 * The returned value is clamped to INT32_MAX so that the Java int type
 * is always sufficient.
 */
int32_t __jnative_fn_sun_nio_ch_IOUtil_iovMax___I(void) {
#if defined(IOV_MAX)
    long v = (long)IOV_MAX;
#elif defined(_SC_IOV_MAX)
    long v = sysconf(_SC_IOV_MAX);
#else
    long v = 0;
#endif

    if (v <= 0) {
        /* POSIX mandates a minimum of 16 iovec entries. */
        v = 16;
    }
    if (v > INT32_MAX) {
        v = INT32_MAX;
    }
    return (int32_t)v;
}

/*
 * static native long writevMax();
 *
 * The maximum number of bytes that the kernel will accept in a single
 * writev(2) call.
 *
 * The reference implementation reports Integer.MAX_VALUE (2147483647)
 * on every platform it supports, and that value is exactly what the
 * Java-side chunking logic expects: it is the largest total that fits
 * in a 32-bit sum of iov_len fields without overflow. On Linux this is
 * also <= the kernel's MAX_RW_COUNT, so every chunk the caller
 * produces is accepted by the syscall.
 *
 * The value is returned as an int64_t because the native is declared
 * `long` on the Java side. It is always positive and always fits in an
 * int32, which keeps the Java caller's arithmetic straightforward.
 */
int64_t __jnative_fn_sun_nio_ch_IOUtil_writevMax___J(void) {
    return (int64_t)INT32_MAX;
}

/*
 * static native int fdVal(FileDescriptor fd);
 *
 * Extracts the raw kernel descriptor from a FileDescriptor object. The
 * Java-side IOUtil.fdVal is used throughout the NIO code when a raw
 * int descriptor is needed — for example when building the jlong
 * address argument that a native `read0` / `write0` will receive, or
 * when comparing two FileDescriptors for identity at the descriptor
 * level.
 *
 * The layout is the same one every other native in this runtime uses:
 * the kernel descriptor is the first instance field, at offset
 * FD_OFFSET, just after the vtable. No JNI field-ID lookup is
 * performed; the offset is baked in by
 * LlvmGlobalEmitter.getFieldOffset and matches every C file that
 * reaches into a FileDescriptor.
 *
 * A negative return means the descriptor has already been closed. The
 * Java callers treat that as "not open" and raise the appropriate
 * ClosedChannelException at their level; the native does not throw, so
 * a query of a stale FileDescriptor does not force an exception where
 * the caller wanted to check the state.
 */
int32_t __jnative_fn_sun_nio_ch_IOUtil_fdVal__Ljava_io_FileDescriptor__I(
        void* fd_obj)
{
    if (fd_obj == NULL) {
        return -1;
    }
    return fd_of(fd_obj);
}

/*
 * static native void configureBlocking(FileDescriptor fd,
 *                                      boolean blocking);
 *
 * Switches the descriptor between blocking and non-blocking mode via
 * fcntl(F_SETFL, O_NONBLOCK). The Java-side caller
 * (SocketChannelImpl's lockedConfigureBlocking /
 * tryLockedConfigureBlocking) invokes this immediately after a socket
 * is created and again whenever a channel switches between blocking
 * and non-blocking mode under the channel's own lock.
 *
 * A false `blocking` argument sets O_NONBLOCK; a true `blocking`
 * argument clears it. The flag is read-modify-written in a single
 * fcntl call so that any other flags already set on the descriptor
 * (O_APPEND, O_SYNC, O_ASYNC, …) are preserved. A naïve
 * `fcntl(F_SETFL, O_NONBLOCK)` overwrites the whole flags word and
 * would silently strip every other bit, which is why the two-step
 * read-modify-write below matters.
 *
 * Any error — including EBADF from a closed descriptor, EINVAL from a
 * kernel that does not support the flag on this descriptor type — is
 * routed through the generic throw helper so the Java caller's
 * `catch (IOException)` block produces the appropriate exception.
 */
void __jnative_fn_sun_nio_ch_IOUtil_configureBlocking__Ljava_io_FileDescriptor_Z_V(
        void* fd_obj, int32_t blocking)
{
    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    int32_t fd = fd_of(fd_obj);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    int flags = fcntl(fd, F_GETFL);
    if (flags < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    if (blocking) {
        flags &= ~O_NONBLOCK;
    } else {
        flags |= O_NONBLOCK;
    }

    if (fcntl(fd, F_SETFL, flags) < 0) {
        __jnative_throw_exception(NULL);
        return;
    }
}
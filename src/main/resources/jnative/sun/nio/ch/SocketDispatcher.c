#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <unistd.h>
#include <errno.h>

#include "jnative_runtime.h"

/*
 * sun.nio.ch.SocketDispatcher — the read/write entry points that the
 * NIO socket channel uses for blocking I/O.
 *
 * The class contributes two natives on top of the shared UnixDispatcher
 * base:
 *
 *   read0(FileDescriptor, long address, int len)   — read into a native buffer
 *   write0(FileDescriptor, long address, int len)  — write from a native buffer
 *
 * Both receive the FileDescriptor object (not the raw kernel fd); the
 * raw fd is extracted with jnative_raw_fd from jnative_runtime.h,
 * which enforces the runtime's standard "NPE on null, throw on
 * closed" convention.
 *
 * The address argument is the base pointer of a native buffer that the
 * Java-side channel allocated as a direct ByteBuffer; it is passed as
 * a jlong because the JVM and the C runtime do not share an address
 * space representation. The buffer is always at least `len` bytes
 * long, and the caller is responsible for its lifetime.
 */

/*
 * private native int read0(FileDescriptor fd, long address, int len)
 *     throws IOException;
 *
 * Reads up to `len` bytes from the socket into the native buffer at
 * `address`. Returns the number of bytes actually read (0 on orderly
 * shutdown of the peer, never more than `len`).
 *
 * The JDK's own SocketDispatcher delegates to the platform's
 * restartableRead helper, which:
 *
 *   - retries on EINTR with no progress, so a signal does not surface
 *     as a spurious short read to the Java caller;
 *   - converts a hard error into an IOException at the Java layer;
 *   - for blocking sockets (the default for every stream
 *     FileDescriptor created by the Java-side
 *     ServerSocketChannel/SocketChannel machinery before the NIO
 *     selector path engages), a single read(2) either fills the
 *     buffer, reaches end of stream, or blocks until one of those
 *     happens.
 *
 * This implementation matches that behaviour for blocking sockets. The
 * non-blocking NIO path never reaches this function through the
 * ordinary code path — the selector layer parks the thread and only
 * retries the read once the channel is reported ready — so no EAGAIN
 * loop is required here.
 */
int32_t __jnative_fn_sun_nio_ch_SocketDispatcher_read0__Ljava_io_FileDescriptor_JI_I(
        void* fd_obj, int64_t address, int32_t len)
{
    int32_t fd = jnative_raw_fd(fd_obj);
    if (len < 0) {
        __jnative_throw_exception(NULL);
    }
    if (len == 0) {
        return 0;
    }

    void* buf = (void*)(intptr_t)address;
    ssize_t n;
    do {
        n = read(fd, buf, (size_t)len);
    } while (n < 0 && errno == EINTR);

    if (n < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int32_t)n;
}

/*
 * private native int write0(FileDescriptor fd, long address, int len)
 *     throws IOException;
 *
 * Writes up to `len` bytes from the native buffer at `address` to the
 * socket. Returns the number of bytes actually written.
 *
 * The same reasoning as for read0 applies: EINTR with no progress is
 * retried, every other error surfaces as IOException, and a blocking
 * socket's write(2) either consumes some prefix of the buffer or
 * blocks until it can. POSIX permits a short write on a stream socket
 * even when there is room in the send buffer, so the return value is
 * the partial count; the Java-level SocketDispatcher.write loop is
 * what drives the remainder.
 */
int32_t __jnative_fn_sun_nio_ch_SocketDispatcher_write0__Ljava_io_FileDescriptor_JI_I(
        void* fd_obj, int64_t address, int32_t len)
{
    int32_t fd = jnative_raw_fd(fd_obj);
    if (len < 0) {
        __jnative_throw_exception(NULL);
    }
    if (len == 0) {
        return 0;
    }

    const void* buf = (const void*)(intptr_t)address;
    ssize_t n;
    do {
        n = write(fd, buf, (size_t)len);
    } while (n < 0 && errno == EINTR);

    if (n < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int32_t)n;
}
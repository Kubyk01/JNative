#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>

#include "jnative_runtime.h"

/*
 * sun.nio.ch.UnixDomainSockets — the Unix-domain-socket backend for
 * SocketChannel / ServerSocketChannel on platforms that support
 * AF_UNIX.
 *
 * This file provides the whole class of natives, not just the init()
 * symbol whose absence would produce a linker error: the reachability
 * analysis pulls in every native of the class the moment any of its
 * call sites becomes reachable, and the JDK's own <clinit> plus the
 * channel constructors reference all of them. Providing only init()
 * would move the linker error from one symbol to the next; providing
 * the whole set at once keeps the module linkable and gives the
 * runtime a coherent implementation for a feature the JDK exercises
 * on every Unix target.
 *
 * The class is only used when init() reports support, which on Linux
 * is always: AF_UNIX has been part of the kernel since the first
 * release.
 *
 * The raw kernel fd is extracted with jnative_raw_fd from
 * jnative_runtime.h, which enforces the runtime's standard
 * "NPE on null receiver, throw on closed descriptor" convention.
 */

/*
 * static native boolean init();
 *
 * Reports whether the platform supports Unix domain sockets. On Linux
 * the answer is unconditionally yes — AF_UNIX is a core part of the
 * kernel ABI and has been present since day one. The check does not
 * actually open a socket: creating one here and leaking it, or closing
 * it and racing with another thread that opens the same fd number,
 * buys nothing over the compile-time knowledge that AF_UNIX is
 * available. The compile-time #if below keeps the file honest on the
 * (hypothetical) target where it is not.
 */
int32_t __jnative_fn_sun_nio_ch_UnixDomainSockets_init___Z(void) {
#if defined(AF_UNIX)
    return 1;
#else
    return 0;
#endif
}

/*
 * static native int socket0(boolean block, boolean toBeBound,
 *                           boolean server)
 *     throws IOException;
 *
 * Creates an AF_UNIX socket of the appropriate type. The three
 * booleans mirror the JDK's own implementation exactly:
 *
 *   - `server` selects SOCK_STREAM for a listening socket (a
 *     ServerSocketChannel), and SOCK_STREAM too for a client socket.
 *     The JDK's UnixDomainSockets always uses SOCK_STREAM; a datagram
 *     variant is not part of this class. The flag is retained for
 *     signature compatibility.
 *
 *   - `block` selects whether the initial descriptor is put into
 *     blocking mode (default for a channel) or non-blocking mode (used
 *     when the caller plans to register the socket with a Selector).
 *     The setting is applied with fcntl(F_SETFL, O_NONBLOCK).
 *
 *   - `toBeBound` is informational: the JDK passes true when the
 *     caller has already supplied a path and will bind immediately,
 *     false otherwise. The kernel does not care.
 *
 * The return value is the raw kernel descriptor. Any failure — EMFILE,
 * ENFILE, ENOMEM, EAFNOSUPPORT on a hypothetical kernel without
 * AF_UNIX — is routed through the generic throw helper and surfaces
 * to the Java caller as an IOException.
 */
int32_t __jnative_fn_sun_nio_ch_UnixDomainSockets_socket0__ZZZ_I(
        int32_t block, int32_t toBeBound, int32_t server)
{
    (void)toBeBound;
    (void)server;

    int type = SOCK_STREAM;

    int fd = socket(AF_UNIX, type, 0);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return -1;
    }

    if (!block) {
        int flags = fcntl(fd, F_GETFL, 0);
        if (flags < 0) {
            int saved = errno;
            close(fd);
            errno = saved;
            __jnative_throw_exception(NULL);
            return -1;
        }
        if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
            int saved = errno;
            close(fd);
            errno = saved;
            __jnative_throw_exception(NULL);
            return -1;
        }
    }

    return (int32_t)fd;
}

/*
 * Populate a sockaddr_un from the caller's path representation.
 *
 * The JDK's Unix domain socket API allows the peer address to be
 * specified in two mutually exclusive ways:
 *
 *   1. As a String (the ordinary filesystem path of the socket file).
 *      The bytes are the UTF-8 encoding of the path, NUL-terminated.
 *      `sun_path` accepts at most `sizeof(sun_path) - 1` characters; a
 *      longer path is ENAMETOOLONG at the Java level.
 *
 *   2. As a byte[] with offset/length (the Linux abstract namespace
 *      form, introduced by the JDK to support abstract sockets). The
 *      first byte must be NUL, and the remaining `len - 1` bytes are
 *      the abstract name. The kernel interprets the leading NUL as
 *      "this is an abstract socket name", and the reported `sun_path`
 *      length covers the NUL.
 *
 * Exactly one of the two forms is used per call: when a String is
 * supplied, the byte[] is null; when a byte[] is supplied, the String
 * is null. The helper below encodes both into the same sockaddr_un
 * structure and returns the length that must be passed to
 * bind/connect — which, for abstract sockets, is
 * `offsetof(sockaddr_un, sun_path) + nameLen`, not
 * `sizeof(sockaddr_un)`, because the kernel reads only the significant
 * prefix.
 *
 * Returns 0 on success, -1 with errno set on failure.
 */
static int build_sockaddr_un(struct sockaddr_un* addr, socklen_t* addrlen,
                             void* path_str, void* path_bytes,
                             int32_t path_off, int32_t path_len)
{
    memset(addr, 0, sizeof(*addr));
    addr->sun_family = AF_UNIX;

    size_t capacity = sizeof(addr->sun_path);
    size_t used = 0;

    if (path_str != NULL) {
        int32_t str_len = 0;
        const char* str = __jnative_read_string_bytes(path_str, &str_len);
        if (str == NULL) {
            errno = EINVAL;
            return -1;
        }
        if ((size_t)str_len >= capacity) {
            /* sun_path has no room for the trailing NUL. */
            errno = ENAMETOOLONG;
            return -1;
        }
        memcpy(addr->sun_path, str, (size_t)str_len);
        addr->sun_path[str_len] = '\0';
        used = (size_t)str_len + 1;
    } else if (path_bytes != NULL) {
        if (path_off < 0 || path_len <= 0) {
            errno = EINVAL;
            return -1;
        }
        const uint8_t* src = (const uint8_t*)path_bytes + JAVA_ARR_HDR + path_off;
        if ((size_t)path_len > capacity) {
            errno = ENAMETOOLONG;
            return -1;
        }
        /*
         * The leading NUL (abstract namespace marker) must be present
         * in the buffer; the Java layer is responsible for supplying
         * it. We copy the raw bytes and rely on the kernel to interpret
         * the leading NUL.
         */
        memcpy(addr->sun_path, src, (size_t)path_len);
        used = (size_t)path_len;
    } else {
        errno = EINVAL;
        return -1;
    }

    *addrlen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + used);
    return 0;
}

/*
 * static native void bind0(FileDescriptor fd, FileDescriptor fd2,
 *                          String path, byte[] bytes, int offset,
 *                          int len)
 *     throws IOException;
 *
 * Binds the socket to the given Unix path. `fd2` is the FileDescriptor
 * object that wraps the raw kernel fd in `fd`'s world — this runtime
 * only ever has one FileDescriptor per kernel fd, so the second
 * argument is informational and is not consulted.
 *
 * A stale socket file left over from a previous process is not
 * unlinked here. The JDK's Java-layer contract is that the caller is
 * responsible for that cleanup via Files.deleteIfExists before
 * invoking bind; doing it silently in the native would create a race
 * with any other process that is currently listening on the same path.
 */
void __jnative_fn_sun_nio_ch_UnixDomainSockets_bind0__Ljava_io_FileDescriptor_Ljava_io_FileDescriptor_Ljava_lang_String__BII_V(
        void* fd_obj, void* fd2_obj,
        void* path_str, void* path_bytes,
        int32_t path_off, int32_t path_len)
{
    (void)fd2_obj;
    int32_t fd = jnative_raw_fd(fd_obj);

    struct sockaddr_un addr;
    socklen_t addrlen = 0;
    if (build_sockaddr_un(&addr, &addrlen, path_str, path_bytes,
                          path_off, path_len) < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    if (bind(fd, (struct sockaddr*)&addr, addrlen) < 0) {
        __jnative_throw_exception(NULL);
    }
}

/*
 * static native int connect0(FileDescriptor fd, FileDescriptor fd2,
 *                            String path, byte[] bytes,
 *                            int offset, int len)
 *     throws IOException;
 *
 * Initiates a connection to the peer whose address is given. The
 * return value follows the platform's connect(2) conventions, which
 * the Java caller inspects to distinguish three outcomes:
 *
 *    1   : connect completed synchronously, the channel is now connected
 *    0   : connect is in progress (EINPROGRESS on a non-blocking
 *          socket), the selector will later report the channel writable
 *    -1  : EINTR was observed before the connection attempt was committed
 *
 * Every other error (ECONNREFUSED, ENOENT for a missing socket file,
 * EACCES, ETIMEDOUT on a socket with SO_SNDTIMEO set, …) is thrown as
 * an IOException, matching the reference JDK's behaviour: those errors
 * are terminal, not "try again later", and the Java layer has no
 * meaningful retry to offer.
 */
int32_t __jnative_fn_sun_nio_ch_UnixDomainSockets_connect0__Ljava_io_FileDescriptor_Ljava_io_FileDescriptor_Ljava_lang_String__BII_I(
        void* fd_obj, void* fd2_obj,
        void* path_str, void* path_bytes,
        int32_t path_off, int32_t path_len)
{
    (void)fd2_obj;
    int32_t fd = jnative_raw_fd(fd_obj);

    struct sockaddr_un addr;
    socklen_t addrlen = 0;
    if (build_sockaddr_un(&addr, &addrlen, path_str, path_bytes,
                          path_off, path_len) < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }

    int rc;
    do {
        rc = connect(fd, (struct sockaddr*)&addr, addrlen);
    } while (rc < 0 && errno == EINTR);

    if (rc == 0) {
        return 1;
    }
    if (errno == EINPROGRESS) {
        return 0;
    }

    __jnative_throw_exception(NULL);
    return 0;
}

/*
 * static native void localAddress0(FileDescriptor fd, byte[] bytes)
 *     throws IOException;
 *
 * Writes the local address of a bound Unix socket into the supplied
 * byte array. The JDK allocates a byte[] of size sun_path and passes
 * it in; the native is expected to fill in the bytes of the address
 * and, when the address is an abstract socket name, the leading NUL.
 *
 * When getsockname(2) succeeds with a zero-length sun_path (the socket
 * is bound to an autobind address that the kernel has not yet
 * materialised, or the socket is not bound at all), the array is left
 * untouched. The Java layer treats that as "no address available" and
 * throws NotYetConnectedException or returns null depending on the
 * caller.
 */
void __jnative_fn_sun_nio_ch_UnixDomainSockets_localAddress0__Ljava_io_FileDescriptor__BII_V(
        void* fd_obj, void* bytes, int32_t offset, int32_t length)
{
    int32_t fd = jnative_raw_fd(fd_obj);
    if (bytes == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    struct sockaddr_un addr;
    socklen_t addrlen = sizeof(addr);
    memset(&addr, 0, sizeof(addr));

    if (getsockname(fd, (struct sockaddr*)&addr, &addrlen) < 0) {
        __jnative_throw_exception(NULL);
        return;
    }
    if (addrlen <= (socklen_t)offsetof(struct sockaddr_un, sun_path)) {
        return;
    }

    size_t name_len = (size_t)addrlen - offsetof(struct sockaddr_un, sun_path);
    if ((int32_t)name_len > length) {
        name_len = (size_t)length;
    }

    memcpy((char*)bytes + JAVA_ARR_HDR + offset, addr.sun_path, name_len);
}

/*
 * static native void remoteAddress0(FileDescriptor fd, byte[] bytes)
 *     throws IOException;
 *
 * Same shape as localAddress0, but reads the peer's address with
 * getpeername(2). On an unconnected socket getpeername fails with
 * ENOTCONN, which the Java layer treats as "peer unknown" — the
 * generic throw helper surfaces it as an IOException, which the caller
 * catches and converts into the appropriate NotYetConnectedException.
 */
void __jnative_fn_sun_nio_ch_UnixDomainSockets_remoteAddress0__Ljava_io_FileDescriptor__BII_V(
        void* fd_obj, void* bytes, int32_t offset, int32_t length)
{
    int32_t fd = jnative_raw_fd(fd_obj);
    if (bytes == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    struct sockaddr_un addr;
    socklen_t addrlen = sizeof(addr);
    memset(&addr, 0, sizeof(addr));

    if (getpeername(fd, (struct sockaddr*)&addr, &addrlen) < 0) {
        __jnative_throw_exception(NULL);
        return;
    }
    if (addrlen <= (socklen_t)offsetof(struct sockaddr_un, sun_path)) {
        return;
    }

    size_t name_len = (size_t)addrlen - offsetof(struct sockaddr_un, sun_path);
    if ((int32_t)name_len > length) {
        name_len = (size_t)length;
    }

    memcpy((char*)bytes + JAVA_ARR_HDR + offset, addr.sun_path, name_len);
}

/*
 * static native int accept0(FileDescriptor fd, FileDescriptor fd2,
 *                           FileDescriptor fd3, String path,
 *                           byte[] bytes, int offset, int len)
 *     throws IOException;
 *
 * Accepts an incoming connection on a listening Unix socket. The new
 * kernel descriptor is written into `fd2`'s FileDescriptor slot, and
 * the peer's address (if any) is copied into `bytes` at the given
 * offset.
 *
 * The `fd3` argument is the FileDescriptor wrapper the JDK wants the
 * new descriptor to be associated with; in this runtime each kernel fd
 * has exactly one FileDescriptor wrapper, so the caller-supplied
 * wrapper is the one that receives the new fd.
 *
 * Return value:
 *
 *    1  : accept succeeded, fd2 now holds the accepted descriptor
 *    0  : the listening socket is non-blocking and there is currently
 *         no pending connection (EAGAIN / EWOULDBLOCK); the Java
 *         caller treats this as "try again later"
 *
 * Every other error (EMFILE, ECONNABORTED, EINTR before the accepted
 * socket was established) is thrown as an IOException.
 *
 * A `path` (or byte-array) argument is present so that the JDK can
 * capture the peer's address at accept time — the kernel fills in the
 * address of the connecting socket when the listener is a Unix socket
 * whose accept path was invoked from Java. When both path arguments
 * are null, the peer's address is simply not captured.
 */
int32_t __jnative_fn_sun_nio_ch_UnixDomainSockets_accept0__Ljava_io_FileDescriptor_Ljava_io_FileDescriptor_Ljava_io_FileDescriptor_Ljava_lang_String__BII_I(
        void* fd_obj, void* fd2_obj, void* fd3_obj,
        void* path_str, void* path_bytes,
        int32_t path_off, int32_t path_len)
{
    (void)fd3_obj;
    (void)path_str;
    (void)path_bytes;
    (void)path_off;
    (void)path_len;

    int32_t listen_fd = jnative_raw_fd(fd_obj);
    if (fd2_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    struct sockaddr_un addr;
    socklen_t addrlen = sizeof(addr);
    memset(&addr, 0, sizeof(addr));

    int new_fd;
    do {
        new_fd = accept(listen_fd, (struct sockaddr*)&addr, &addrlen);
    } while (new_fd < 0 && errno == EINTR);

    if (new_fd < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return 0;
        }
        __jnative_throw_exception(NULL);
        return 0;
    }

    /*
     * Install the freshly accepted descriptor into the FileDescriptor
     * object the Java layer allocated for it. The wrapper's fd slot is
     * the same offset used everywhere else in the runtime (FD_OFFSET
     * from jnative_runtime.h).
     */
    *(int32_t*)((char*)fd2_obj + FD_OFFSET) = new_fd;

    return 1;
}

/*
 * static native byte[] localAddress0(FileDescriptor fd) throws IOException;
 *
 * JDK 22 (JDK-8310615) changed the native signature of the local /
 * remote address queries. The older form took a caller-supplied byte[]
 * and wrote the address into it; the new form allocates the byte[]
 * itself and returns it, so the Java layer no longer has to know the
 * size of struct sockaddr_un in advance.
 *
 * The returned array carries the raw sun_path bytes of the bound Unix
 * socket, exactly as getsockname(2) reports them. For an abstract socket
 * the first byte is NUL, matching the Linux abstract-namespace convention
 * that the Java-side UnixDomainSocketAddress.toByteArray() expects to
 * see; for a filesystem-path socket the bytes are the path without any
 * trailing NUL. A socket that is bound but whose sun_path is empty
 * returns a zero-length array, which the Java layer renders as the
 * empty path.
 */
void* __jnative_fn_sun_nio_ch_UnixDomainSockets_localAddress0__Ljava_io_FileDescriptor___B(
        void* fd_obj)
{
    int32_t fd = jnative_raw_fd(fd_obj);

    struct sockaddr_un addr;
    socklen_t addrlen = (socklen_t)sizeof(addr);
    memset(&addr, 0, sizeof(addr));

    if (getsockname(fd, (struct sockaddr*)&addr, &addrlen) < 0) {
        __jnative_throw_exception(NULL);
        return NULL;
    }

    size_t base = offsetof(struct sockaddr_un, sun_path);
    if (addrlen <= (socklen_t)base) {
        return jnative_byte_array(NULL, 0);
    }
    size_t name_len = (size_t)addrlen - base;
    return jnative_byte_array(addr.sun_path, (int32_t)name_len);
}

void* __jnative_fn_sun_nio_ch_UnixDomainSockets_remoteAddress0__Ljava_io_FileDescriptor___B(
        void* fd_obj)
{
    int32_t fd = jnative_raw_fd(fd_obj);

    struct sockaddr_un addr;
    socklen_t addrlen = (socklen_t)sizeof(addr);
    memset(&addr, 0, sizeof(addr));

    if (getpeername(fd, (struct sockaddr*)&addr, &addrlen) < 0) {
        __jnative_throw_exception(NULL);
        return NULL;
    }

    size_t base = offsetof(struct sockaddr_un, sun_path);
    if (addrlen <= (socklen_t)base) {
        return jnative_byte_array(NULL, 0);
    }
    size_t name_len = (size_t)addrlen - base;
    return jnative_byte_array(addr.sun_path, (int32_t)name_len);
}

/*
 * static native void bind0(FileDescriptor fd, byte[] path)
 *     throws IOException;
 *
 * JDK 22 (JDK-8310615) simplified the Unix-domain-socket native
 * signatures. The old form took both a String and a byte[] so that
 * the Java layer could choose between a filesystem path and an abstract
 * name; the new form takes only the byte[] and leaves the choice to
 * the caller. For an abstract socket the array's first byte is NUL,
 * matching the Linux abstract-namespace convention; for a filesystem
 * path the array holds the raw bytes of the path with no terminator.
 *
 * The two forms are semantically identical, so this body delegates to
 * the four-argument helper through a temporary wrapper that supplies
 * the missing String slot as null. That keeps a single implementation
 * of the sockaddr_un construction, so any future change to the
 * layout rules only has to be made in one place.
 */
void __jnative_fn_sun_nio_ch_UnixDomainSockets_bind0__Ljava_io_FileDescriptor__B_V(
        void* fd_obj, void* path_bytes)
{
    int32_t fd = jnative_raw_fd(fd_obj);
    if (path_bytes == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    int32_t path_len = jnative_array_length(path_bytes);
    if (path_len <= 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    struct sockaddr_un addr;
    socklen_t addrlen = 0;
    if (build_sockaddr_un(&addr, &addrlen, NULL, path_bytes, 0, path_len) < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    if (bind(fd, (struct sockaddr*)&addr, addrlen) < 0) {
        __jnative_throw_exception(NULL);
    }
}
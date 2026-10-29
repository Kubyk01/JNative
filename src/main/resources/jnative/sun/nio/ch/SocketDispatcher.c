#define _GNU_SOURCE

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <winsock2.h>
#  include <windows.h>
#endif

#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <errno.h>
#include <string.h>

#if !defined(_WIN32)
#  include <sys/uio.h>
#  include <unistd.h>
#endif

#include "jnative_runtime.h"

/* ============================================================================
 *  IOStatus constants (mirror sun.nio.ch.IOStatus).
 * ========================================================================== */
#define IOS_UNAVAILABLE (-2)
#define IOS_INTERRUPTED (-3)
#define IOS_THROWN      (-5)

/* ============================================================================
 *  POSIX iovec layout for the Windows build.
 *
 *  On POSIX the real definition comes from <sys/uio.h>. On Windows the
 *  header is absent, but the layout the Java side has already written
 *  into the caller's buffer is fixed at `{ void *iov_base; size_t
 *  iov_len; }` — the JDK's shared IOUtil code builds iovec arrays in
 *  exactly that shape on both platforms (the Java-level layout of a
 *  direct ByteBuffer's data and the native struct are both 16 bytes
 *  per entry on LP64 / LLP64: 8-byte pointer + 8-byte size_t). Declaring
 *  the same struct locally on Windows lets the WSABUF-conversion helper
 *  below refer to a single type name and work on both targets.
 * ========================================================================== */
#if defined(_WIN32)
struct iovec {
    void   *iov_base;
    size_t  iov_len;
};
#endif

/* ============================================================================
 *  Error-reporting helpers.
 * ========================================================================== */

#if defined(_WIN32)

/*
 * Throws java.io.IOException with the Winsock error text for the
 * current WSAGetLastError(). The error text comes from FormatMessageA,
 * which is what the reference JVM's own Winsock error handling uses:
 * it reads the same system message catalog that `net helpmsg` uses, so
 * the resulting message is familiar to a user who has debugged a
 * Windows networking problem before.
 *
 * The trailing "\r\n" that FormatMessageA appends is stripped, matching
 * the reference implementation's message-shaping.
 */
__attribute__((noreturn))
static void throw_wsa_error(const char* what) {
    int err = WSAGetLastError();
    char sysbuf[256];
    DWORD n = FormatMessageA(
        FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        NULL, (DWORD)err,
        MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
        sysbuf, (DWORD)sizeof(sysbuf), NULL);

    char msg[512];
    if (n > 0) {
        while (n > 0 && (sysbuf[n-1] == '\r' || sysbuf[n-1] == '\n'
                         || sysbuf[n-1] == ' ' || sysbuf[n-1] == '\t')) {
            sysbuf[--n] = '\0';
        }
        snprintf(msg, sizeof(msg), "%s: %s (WSA error %d)",
                 what ? what : "Socket I/O error", sysbuf, err);
    } else {
        snprintf(msg, sizeof(msg), "%s (WSA error %d)",
                 what ? what : "Socket I/O error", err);
    }

    void* exc = __jnative_construct_exception(
        "vtable_java_io_IOException", msg);
    __jnative_throw_exception(exc);
}

#else  /* POSIX */

/*
 * Throws java.io.IOException with the strerror text for the current
 * errno. The reference implementation uses
 * JNU_ThrowIOExceptionWithLastError, which does the same thing.
 */
__attribute__((noreturn))
static void throw_errno_error(const char* what) {
    int err = errno;
    char msg[512];
    snprintf(msg, sizeof(msg), "%s: %s (errno %d)",
             what ? what : "Socket I/O error", strerror(err), err);
    void* exc = __jnative_construct_exception(
        "vtable_java_io_IOException", msg);
    __jnative_throw_exception(exc);
}

#endif

/* ============================================================================
 *  FileDescriptor fd extractor.
 *
 *  The fd lives at offset FD_OFFSET of the FileDescriptor object, as a
 *  32-bit int. On POSIX this is a plain file descriptor; on Windows it
 *  is a SOCKET handle stored as a 32-bit int (Windows socket handles
 *  are always small enough to fit, and the rest of the JDK's networking
 *  code — SocketChannelImpl, Net, etc. — reads it back the same way).
 *
 *  A null FileDescriptor or an already-closed one (fd == -1) is thrown
 *  as an IOException before any syscall is attempted, matching the
 *  reference implementation's behaviour on a closed socket.
 * ========================================================================== */
static inline int32_t socket_fdval(void* fdo) {
    if (fdo == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int32_t fd = jnative_fd_of(fdo);
    if (fd < 0) {
        void* exc = __jnative_construct_exception(
            "vtable_java_io_IOException", "Socket closed");
        __jnative_throw_exception(exc);
    }
    return fd;
}

/* ============================================================================
 *  read0 — read up to len bytes into the buffer at `address`.
 * ========================================================================== */

int32_t __jnative_fn_sun_nio_ch_SocketDispatcher_read0__Ljava_io_FileDescriptor_JI_I(
        void* fdo, int64_t address, int32_t len)
{
    int32_t fd = socket_fdval(fdo);
    void* buf = (void*)(intptr_t)address;

#if defined(_WIN32)
    int n = recv((SOCKET)(uintptr_t)fd, (char*)buf, len, 0);
    if (n == SOCKET_ERROR) {
        int err = WSAGetLastError();
        if (err == WSAEWOULDBLOCK) return IOS_UNAVAILABLE;
        if (err == WSAEINTR)       return IOS_INTERRUPTED;
        throw_wsa_error("Read failed");
    }
    return (int32_t)n;
#else
    ssize_t n = read(fd, buf, (size_t)len);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return IOS_UNAVAILABLE;
        if (errno == EINTR)                          return IOS_INTERRUPTED;
        throw_errno_error("Read failed");
    }
    return (int32_t)n;
#endif
}

/* ============================================================================
 *  write0 — write up to len bytes from the buffer at `address`.
 * ========================================================================== */

int32_t __jnative_fn_sun_nio_ch_SocketDispatcher_write0__Ljava_io_FileDescriptor_JI_I(
        void* fdo, int64_t address, int32_t len)
{
    int32_t fd = socket_fdval(fdo);
    void* buf = (void*)(intptr_t)address;

#if defined(_WIN32)
    int n = send((SOCKET)(uintptr_t)fd, (const char*)buf, len, 0);
    if (n == SOCKET_ERROR) {
        int err = WSAGetLastError();
        if (err == WSAEWOULDBLOCK) return IOS_UNAVAILABLE;
        if (err == WSAEINTR)       return IOS_INTERRUPTED;
        throw_wsa_error("Write failed");
    }
    return (int32_t)n;
#else
    ssize_t n = write(fd, buf, (size_t)len);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return IOS_UNAVAILABLE;
        if (errno == EINTR)                          return IOS_INTERRUPTED;
        throw_errno_error("Write failed");
    }
    return (int32_t)n;
#endif
}

/* ============================================================================
 *  readv0 — scatter-read up to len iovecs.
 *
 *  On POSIX the array at `address` is a standard `struct iovec[]` and
 *  the primitive is readv(2). On Windows the same array is copied into
 *  a temporary `WSABUF[]` (field order reversed) and the primitive is
 *  WSARecv on the underlying SOCKET.
 *
 *  The temporary WSABUF[] array is sized on the stack for the common
 *  case (up to STACK_BUF_COUNT iovecs) and heap-allocated only when the
 *  caller supplies more. This keeps the single-buffer and small-batch
 *  cases free of allocator calls without imposing a hard limit on the
 *  batch size.
 * ========================================================================== */

int64_t __jnative_fn_sun_nio_ch_SocketDispatcher_readv0__Ljava_io_FileDescriptor_JI_J(
        void* fdo, int64_t address, int32_t len)
{
    int32_t fd = socket_fdval(fdo);
    struct iovec* iov = (struct iovec*)(intptr_t)address;

    if (iov == NULL || len < 0) {
        __jnative_throw_null_pointer_exception();
    }

#if defined(_WIN32)
    enum { STACK_BUF_COUNT = 64 };
    WSABUF stack_bufs[STACK_BUF_COUNT];
    WSABUF* bufs = stack_bufs;
    int heap_allocated = 0;

    if (len > STACK_BUF_COUNT) {
        bufs = (WSABUF*)malloc((size_t)len * sizeof(WSABUF));
        if (bufs == NULL) {
            __jnative_throw_out_of_memory_error_ctx(
                "SocketDispatcher.readv0");
        }
        heap_allocated = 1;
    }

    for (int32_t i = 0; i < len; i++) {
        bufs[i].buf = (CHAR*)iov[i].iov_base;
        bufs[i].len = (ULONG)iov[i].iov_len;
    }

    DWORD nread = 0;
    DWORD flags = 0;
    int rc = WSARecv((SOCKET)(uintptr_t)fd, bufs, (DWORD)len,
                     &nread, &flags, NULL, NULL);

    if (heap_allocated) free(bufs);

    if (rc == SOCKET_ERROR) {
        int err = WSAGetLastError();
        if (err == WSAEWOULDBLOCK) return (int64_t)IOS_UNAVAILABLE;
        if (err == WSAEINTR)       return (int64_t)IOS_INTERRUPTED;
        throw_wsa_error("Read failed");
    }
    return (int64_t)nread;
#else
    ssize_t n = readv(fd, iov, len);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return (int64_t)IOS_UNAVAILABLE;
        }
        if (errno == EINTR) {
            return (int64_t)IOS_INTERRUPTED;
        }
        throw_errno_error("Read failed");
    }
    return (int64_t)n;
#endif
}

/* ============================================================================
 *  writev0 — scatter-write up to len iovecs.
 * ========================================================================== */

int64_t __jnative_fn_sun_nio_ch_SocketDispatcher_writev0__Ljava_io_FileDescriptor_JI_J(
        void* fdo, int64_t address, int32_t len)
{
    int32_t fd = socket_fdval(fdo);
    struct iovec* iov = (struct iovec*)(intptr_t)address;

    if (iov == NULL || len < 0) {
        __jnative_throw_null_pointer_exception();
    }

#if defined(_WIN32)
    enum { STACK_BUF_COUNT = 64 };
    WSABUF stack_bufs[STACK_BUF_COUNT];
    WSABUF* bufs = stack_bufs;
    int heap_allocated = 0;

    if (len > STACK_BUF_COUNT) {
        bufs = (WSABUF*)malloc((size_t)len * sizeof(WSABUF));
        if (bufs == NULL) {
            __jnative_throw_out_of_memory_error_ctx(
                "SocketDispatcher.writev0");
        }
        heap_allocated = 1;
    }

    for (int32_t i = 0; i < len; i++) {
        bufs[i].buf = (CHAR*)iov[i].iov_base;
        bufs[i].len = (ULONG)iov[i].iov_len;
    }

    DWORD nsent = 0;
    int rc = WSASend((SOCKET)(uintptr_t)fd, bufs, (DWORD)len,
                     &nsent, 0, NULL, NULL);

    if (heap_allocated) free(bufs);

    if (rc == SOCKET_ERROR) {
        int err = WSAGetLastError();
        if (err == WSAEWOULDBLOCK) return (int64_t)IOS_UNAVAILABLE;
        if (err == WSAEINTR)       return (int64_t)IOS_INTERRUPTED;
        throw_wsa_error("Write failed");
    }
    return (int64_t)nsent;
#else
    ssize_t n = writev(fd, iov, len);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return (int64_t)IOS_UNAVAILABLE;
        }
        if (errno == EINTR) {
            return (int64_t)IOS_INTERRUPTED;
        }
        throw_errno_error("Write failed");
    }
    return (int64_t)n;
#endif
}
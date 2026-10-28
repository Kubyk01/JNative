#define _GNU_SOURCE

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <winsock2.h>
#  include <windows.h>
#  include <io.h>
#  include <limits.h>
#  include <errno.h>
#else
#  include <unistd.h>
#  include <errno.h>
#  include <fcntl.h>
#  include <string.h>
#  include <limits.h>
#  include <sys/uio.h>
#endif

#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

#define IOS_UNAVAILABLE (-2)
#define IOS_INTERRUPTED (-3)

/* ============================================================================
 *  Error helpers.
 * ========================================================================== */

static inline int jn_io_would_block(int32_t e) {
    return e == EAGAIN || e == EWOULDBLOCK;
}

static inline int jn_io_interrupted(int32_t e) {
    return e == EINTR;
}

__attribute__((noreturn))
static void jn_throw_io_ctx(const char* what, int32_t err) {
    char buf[512];
#if defined(_WIN32)
    snprintf(buf, sizeof(buf), "%s (errno %d)", what ? what : "I/O error", (int)err);
#else
    snprintf(buf, sizeof(buf), "%s: %s (errno %d)",
             what ? what : "I/O error", strerror((int)err), (int)err);
#endif
    void* exc = __jnative_construct_exception("vtable_java_io_IOException", buf);
    __jnative_throw_exception(exc);
}

/* ============================================================================
 *  FileDescriptor access.
 * ========================================================================== */

static inline int32_t io_fdval(void* fdo) {
    if (fdo == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = jnative_fd_of(fdo);
    if (fd < 0) {
        void* exc = __jnative_construct_exception(
            "vtable_java_io_IOException", "Stream Closed");
        __jnative_throw_exception(exc);
    }
    return fd;
}

/*
 * static native int fdVal(FileDescriptor fd);
 */
int32_t __jnative_fn_sun_nio_ch_IOUtil_fdVal__Ljava_io_FileDescriptor__I(void* fdo) {
    if (fdo == NULL) __jnative_throw_null_pointer_exception();
    return jnative_fd_of(fdo);
}

/* ============================================================================
 *  configureBlocking — put the fd into or out of non-blocking mode.
 *
 *  POSIX: flip O_NONBLOCK via fcntl(F_GETFL) / fcntl(F_SETFL).
 *  Windows: ioctlsocket(FIONBIO) on a socket; non-sockets (pipes,
 *  console handles, real files) are inherently blocking on Windows and
 *  cannot be made non-blocking, so the syscall returns WSAENOTSOCK and
 *  we treat the operation as a silent no-op — matching what HotSpot's
 *  own IOUtil.c does for the non-socket case.
 * ========================================================================== */

void __jnative_fn_sun_nio_ch_IOUtil_configureBlocking__Ljava_io_FileDescriptor_Z_V(
        void* fdo, int32_t blocking)
{
    int32_t fd = io_fdval(fdo);

#if defined(_WIN32)
    u_long mode = blocking ? 0u : 1u;
    if (ioctlsocket((SOCKET)(uintptr_t)(intptr_t)fd, FIONBIO, &mode)
            == SOCKET_ERROR) {
        int32_t e = WSAGetLastError();
        if (e == WSAENOTSOCK) {
            /* Non-socket fd (pipe, file, console). Windows cannot toggle
             * its blocking mode; the caller's request is meaningless. */
            return;
        }
        jn_throw_io_ctx("configureBlocking failed", e);
    }
#else
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) {
        jn_throw_io_ctx("configureBlocking: fcntl(F_GETFL) failed", errno);
    }
    int new_flags = blocking
        ? (flags & ~O_NONBLOCK)
        : (flags |  O_NONBLOCK);
    if (fcntl(fd, F_SETFL, new_flags) < 0) {
        jn_throw_io_ctx("configureBlocking: fcntl(F_SETFL) failed", errno);
    }
#endif
}

/* ============================================================================
 *  iovMax — maximum iovec count for readv / writev.
 * ========================================================================== */

int32_t __jnative_fn_sun_nio_ch_IOUtil_iovMax___I(void) {
#if defined(_WIN32)
    return 1024;
#elif defined(IOV_MAX)
    return IOV_MAX;
#elif defined(_SC_IOV_MAX)
    long v = sysconf(_SC_IOV_MAX);
    return v > 0 ? (int32_t)v : 1024;
#else
    return 1024;
#endif
}

/* ============================================================================
 *  writevMax — maximum single writev() byte count.
 * ========================================================================== */

int64_t __jnative_fn_sun_nio_ch_IOUtil_writevMax___J(void) {
#if defined(_WIN32)
    return (int64_t)INT32_MAX;
#elif defined(SSIZE_MAX)
    return (int64_t)SSIZE_MAX;
#else
    return (int64_t)INT32_MAX;
#endif
}

/* ============================================================================
 *  Buffer address extraction.
 * ========================================================================== */

static int64_t direct_buffer_address(void* buf) {
    JNativeVTable* vt = *(JNativeVTable**)buf;
    if (vt == NULL || vt->name == NULL) return 0;

    ReflectionClass* cls = jnative_class_by_name(vt->name);
    while (cls != NULL) {
        if (cls->methods != NULL) {
            for (ReflectionMethod** mp = cls->methods; *mp != NULL; mp++) {
                ReflectionMethod* m = *mp;
                if (m->name != NULL
                    && m->descriptor != NULL
                    && m->adaptor != NULL
                    && strcmp((const char*)m->name, "address") == 0
                    && strcmp((const char*)m->descriptor, "()J") == 0) {
                    typedef int64_t (*addr_fn)(void*);
                    return ((addr_fn)m->adaptor)(buf);
                }
            }
        }
        cls = cls->superclass;
    }
    return 0;
}

static int64_t buffer_base_address(void* buf_obj, int32_t extra_offset) {
    if (buf_obj == NULL) return 0;
    void* first_word = *(void**)buf_obj;
    if (__jnative_is_class_mirror(first_word)) {
        return (int64_t)(uintptr_t)((uint8_t*)buf_obj + JAVA_ARR_HDR + extra_offset);
    }
    int64_t base = direct_buffer_address(buf_obj);
    if (base == 0) {
        jn_throw_io_ctx("DirectByteBuffer.address() unavailable", 0);
    }
    return base + extra_offset;
}

/* ============================================================================
 *  initIDs — no-op.
 * ========================================================================== */

void __jnative_fn_sun_nio_ch_IOUtil_initIDs___V(void) {
}

/* ============================================================================
 *  write1 — write one byte.
 * ========================================================================== */

int32_t __jnative_fn_sun_nio_ch_IOUtil_write1__Ljava_io_FileDescriptor_I_I(
        void* fdo, int32_t b)
{
    int32_t fd = io_fdval(fdo);
    uint8_t byte = (uint8_t)b;

#if defined(_WIN32)
    int n;
    do {
        n = _write(fd, &byte, 1);
    } while (n < 0 && errno == EINTR);
    if (n < 0) jn_throw_io_ctx("write1 failed", errno);
    return n;
#else
    ssize_t n;
    do {
        n = write(fd, &byte, 1);
    } while (n < 0 && errno == EINTR);
    if (n < 0) {
        if (jn_io_would_block(errno)) return IOS_UNAVAILABLE;
        jn_throw_io_ctx("write1 failed", errno);
    }
    return (int32_t)n;
#endif
}

/* ============================================================================
 *  write — write from a byte[] slice.
 * ========================================================================== */

int32_t __jnative_fn_sun_nio_ch_IOUtil_write__Ljava_io_FileDescriptor_BII_I(
        void* fdo, void* buf_array, int32_t off, int32_t len)
{
    int32_t fd = io_fdval(fdo);
    if (buf_array == NULL) __jnative_throw_null_pointer_exception();

    const uint8_t* base = (const uint8_t*)buf_array + JAVA_ARR_HDR + off;

#if defined(_WIN32)
    int n;
    do {
        n = _write(fd, base, (unsigned int)len);
    } while (n < 0 && errno == EINTR);
    if (n < 0) jn_throw_io_ctx("write failed", errno);
    return n;
#else
    ssize_t n;
    do {
        n = write(fd, base, (size_t)len);
    } while (n < 0 && errno == EINTR);
    if (n < 0) {
        if (jn_io_would_block(errno)) return IOS_UNAVAILABLE;
        jn_throw_io_ctx("write failed", errno);
    }
    return (int32_t)n;
#endif
}

/* ============================================================================
 *  writev — scatter write across `bufs[0..count)`.
 * ========================================================================== */

int64_t __jnative_fn_sun_nio_ch_IOUtil_writev__Ljava_io_FileDescriptor_Ljava_lang_Object__I_I_J(
        void* fdo, void* bufs_array, void* offsets_array, void* lengths_array)
{
    int32_t fd = io_fdval(fdo);

    int32_t count = jnative_array_length(bufs_array);
    if (count <= 0) return 0;

    void** bufs = (void**)jnative_array_data(bufs_array);
    int32_t* offsets = (int32_t*)jnative_array_data(offsets_array);
    int32_t* lengths = (int32_t*)jnative_array_data(lengths_array);

#if !defined(_WIN32)
    struct iovec* iov = (struct iovec*)malloc((size_t)count * sizeof(struct iovec));
    if (iov == NULL) {
        __jnative_throw_out_of_memory_error_ctx("IOUtil.writev");
    }
    for (int32_t i = 0; i < count; i++) {
        iov[i].iov_base = (void*)(uintptr_t)buffer_base_address(bufs[i], offsets[i]);
        iov[i].iov_len  = (size_t)lengths[i];
    }
    ssize_t n = writev(fd, iov, count);
    free(iov);
    if (n < 0) {
        if (jn_io_would_block(errno))  return (int64_t)IOS_UNAVAILABLE;
        if (jn_io_interrupted(errno))  return (int64_t)IOS_INTERRUPTED;
        jn_throw_io_ctx("writev failed", errno);
    }
    return (int64_t)n;
#else
    int64_t total = 0;
    for (int32_t i = 0; i < count; i++) {
        uint8_t* base = (uint8_t*)(uintptr_t)buffer_base_address(bufs[i], offsets[i]);
        int32_t remaining = lengths[i];
        while (remaining > 0) {
            int n = _write(fd, base, (unsigned int)remaining);
            if (n < 0) {
                if (errno == EINTR) continue;
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    return total > 0 ? total : (int64_t)IOS_UNAVAILABLE;
                }
                jn_throw_io_ctx("writev failed", errno);
            }
            if (n == 0) return total;
            total += n;
            base += n;
            remaining -= n;
        }
    }
    return total;
#endif
}

/* ============================================================================
 *  read — read into a byte[] slice.
 * ========================================================================== */

int32_t __jnative_fn_sun_nio_ch_IOUtil_read__Ljava_io_FileDescriptor_BII_I(
        void* fdo, void* buf_array, int32_t off, int32_t len)
{
    int32_t fd = io_fdval(fdo);
    if (buf_array == NULL) __jnative_throw_null_pointer_exception();

    uint8_t* base = (uint8_t*)buf_array + JAVA_ARR_HDR + off;

#if defined(_WIN32)
    int n;
    do {
        n = _read(fd, base, (unsigned int)len);
    } while (n < 0 && errno == EINTR);
    if (n < 0) jn_throw_io_ctx("read failed", errno);
    return n;
#else
    ssize_t n;
    do {
        n = read(fd, base, (size_t)len);
    } while (n < 0 && errno == EINTR);
    if (n < 0) {
        if (jn_io_would_block(errno)) return IOS_UNAVAILABLE;
        jn_throw_io_ctx("read failed", errno);
    }
    return (int32_t)n;
#endif
}

/* ============================================================================
 *  readv — scatter read across `bufs[0..count)`.
 * ========================================================================== */

int64_t __jnative_fn_sun_nio_ch_IOUtil_readv__Ljava_io_FileDescriptor_Ljava_lang_Object__I_I_J(
        void* fdo, void* bufs_array, void* offsets_array, void* lengths_array)
{
    int32_t fd = io_fdval(fdo);

    int32_t count = jnative_array_length(bufs_array);
    if (count <= 0) return 0;

    void** bufs = (void**)jnative_array_data(bufs_array);
    int32_t* offsets = (int32_t*)jnative_array_data(offsets_array);
    int32_t* lengths = (int32_t*)jnative_array_data(lengths_array);

#if !defined(_WIN32)
    struct iovec* iov = (struct iovec*)malloc((size_t)count * sizeof(struct iovec));
    if (iov == NULL) {
        __jnative_throw_out_of_memory_error_ctx("IOUtil.readv");
    }
    for (int32_t i = 0; i < count; i++) {
        iov[i].iov_base = (void*)(uintptr_t)buffer_base_address(bufs[i], offsets[i]);
        iov[i].iov_len  = (size_t)lengths[i];
    }
    ssize_t n = readv(fd, iov, count);
    free(iov);
    if (n < 0) {
        if (jn_io_would_block(errno))  return (int64_t)IOS_UNAVAILABLE;
        if (jn_io_interrupted(errno))  return (int64_t)IOS_INTERRUPTED;
        jn_throw_io_ctx("readv failed", errno);
    }
    return (int64_t)n;
#else
    int64_t total = 0;
    for (int32_t i = 0; i < count; i++) {
        uint8_t* base = (uint8_t*)(uintptr_t)buffer_base_address(bufs[i], offsets[i]);
        int32_t remaining = lengths[i];
        while (remaining > 0) {
            int n = _read(fd, base, (unsigned int)remaining);
            if (n < 0) {
                if (errno == EINTR) continue;
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    return total > 0 ? total : (int64_t)IOS_UNAVAILABLE;
                }
                jn_throw_io_ctx("readv failed", errno);
            }
            if (n == 0) return total;
            total += n;
            base += n;
            remaining -= n;
        }
    }
    return total;
#endif
}
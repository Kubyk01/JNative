#define _GNU_SOURCE

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <winsock2.h>
#  include <windows.h>
#  include <io.h>
#else
#  include <unistd.h>
#  include <fcntl.h>
#  include <sys/types.h>
#  include <sys/stat.h>
#  include <sys/sendfile.h>
#  include <sys/uio.h>
#  include <errno.h>
#  include <string.h>
#endif

#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

#define IOS_UNAVAILABLE (-2)
#define IOS_INTERRUPTED (-3)

/* =========== portable FD and pread/pwrite/read/write wrappers =========== */

static inline int32_t fdi_fdval(void* fdo) {
    if (fdo == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = jnative_fd_of(fdo);
    if (fd < 0) {
        void* exc = __jnative_construct_exception(
            "vtable_java_io_IOException", "Stream Closed");
        __jnative_throw_exception(exc);
    }
    return fd;
}

static inline int fdi_is_would_block(int e) {
    return e == EAGAIN || e == EWOULDBLOCK;
}

static inline int fdi_is_interrupted(int e) {
    return e == EINTR;
}

/*
 * Unified error thrower.
 *
 * `code` is a platform-native error value:
 *   - errno        on POSIX
 *   - errno        on Windows for CRT functions (_read/_write/_open/...)
 *   - GetLastError on Windows for Win32 APIs (_get_osfhandle/GetFileSizeEx/...)
 *
 * The Windows branch passes `code` to FormatMessageA, which expects a
 * Win32 error code. For the CRT paths, callers use fdi_throw_io, which
 * forwards errno. For the Win32-API paths, callers use fdi_throw_win32,
 * which forwards GetLastError.
 *
 * The previous revision called the MSVC-only `_dosmaperr` helper, which
 * does not exist in MinGW-w64 and produced five
 * -Wimplicit-function-declaration errors on the Windows build.
 */
__attribute__((noreturn))
static void fdi_throw_io_code(const char* what, int32_t code) {
    char buf[512];
#if defined(_WIN32)
    char sysbuf[256];
    DWORD n = FormatMessageA(
        FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        NULL, (DWORD)code, MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
        sysbuf, (DWORD)sizeof(sysbuf), NULL);
    if (n > 0) {
        while (n > 0 && (sysbuf[n-1] == '\r' || sysbuf[n-1] == '\n'
                      || sysbuf[n-1] == ' '  || sysbuf[n-1] == '\t')) {
            sysbuf[--n] = '\0';
        }
        snprintf(buf, sizeof(buf), "%s: %s (error %d)",
                 what ? what : "I/O error", sysbuf, (int)code);
    } else {
        snprintf(buf, sizeof(buf), "%s (error %d)",
                 what ? what : "I/O error", (int)code);
    }
#else
    snprintf(buf, sizeof(buf), "%s: %s (errno %d)",
             what ? what : "I/O error", strerror((int)code), (int)code);
#endif
    void* exc = __jnative_construct_exception("vtable_java_io_IOException", buf);
    __jnative_throw_exception(exc);
}

static inline int32_t fdi_errno(void) {
    return (int32_t)errno;
}

__attribute__((noreturn))
static void fdi_throw_io(const char* what) {
    fdi_throw_io_code(what, fdi_errno());
}

#if defined(_WIN32)
__attribute__((noreturn))
static void fdi_throw_win32(const char* what) {
    fdi_throw_io_code(what, (int32_t)GetLastError());
}
#endif

#if defined(_WIN32)
static int64_t fdi_pread(int fd, void* buf, size_t n, int64_t off) {
    if (_lseeki64(fd, off, SEEK_SET) < 0) return -1;
    return _read(fd, buf, (unsigned int)n);
}
static int64_t fdi_pwrite(int fd, const void* buf, size_t n, int64_t off) {
    if (_lseeki64(fd, off, SEEK_SET) < 0) return -1;
    return _write(fd, buf, (unsigned int)n);
}
static int64_t fdi_read(int fd, void* buf, size_t n) {
    return _read(fd, buf, (unsigned int)n);
}
static int64_t fdi_write(int fd, const void* buf, size_t n) {
    return _write(fd, buf, (unsigned int)n);
}
static int64_t fdi_lseek(int fd, int64_t off, int whence) {
    return _lseeki64(fd, off, whence);
}
static int fdi_close(int fd) { return _close(fd); }
static int fdi_commit(int fd) { return _commit(fd); }
#else
static int64_t fdi_pread(int fd, void* buf, size_t n, int64_t off) {
    return (int64_t)pread(fd, buf, n, (off_t)off);
}
static int64_t fdi_pwrite(int fd, const void* buf, size_t n, int64_t off) {
    return (int64_t)pwrite(fd, buf, n, (off_t)off);
}
static int64_t fdi_read(int fd, void* buf, size_t n) {
    return (int64_t)read(fd, buf, n);
}
static int64_t fdi_write(int fd, const void* buf, size_t n) {
    return (int64_t)write(fd, buf, n);
}
static int64_t fdi_lseek(int fd, int64_t off, int whence) {
    return (int64_t)lseek(fd, (off_t)off, whence);
}
static int fdi_close(int fd) { return close(fd); }
static int fdi_commit(int fd) {
#  if defined(__APPLE__)
    return fcntl(fd, F_FULLFSYNC, 0);
#  else
    return fsync(fd);
#  endif
}
#endif

/* =========== init0 =========== */

void __jnative_fn_sun_nio_ch_FileDispatcherImpl_init0___V(void) {
}

/* =========== read0 / write0 =========== */

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_read0__Ljava_io_FileDescriptor_JI_I(
        void* fdo, int64_t address, int32_t len)
{
    int fd = fdi_fdval(fdo);
    void* buf = (void*)(intptr_t)address;

    int64_t n;
    do {
        n = fdi_read(fd, buf, (size_t)len);
    } while (n < 0 && fdi_is_interrupted(fdi_errno()));

    if (n < 0) {
        if (fdi_is_would_block(fdi_errno())) return IOS_UNAVAILABLE;
        fdi_throw_io("read0 failed");
    }
    return (int32_t)n;
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_write0__Ljava_io_FileDescriptor_JI_I(
        void* fdo, int64_t address, int32_t len)
{
    int fd = fdi_fdval(fdo);
    void* buf = (void*)(intptr_t)address;

    int64_t n;
    do {
        n = fdi_write(fd, buf, (size_t)len);
    } while (n < 0 && fdi_is_interrupted(fdi_errno()));

    if (n < 0) {
        if (fdi_is_would_block(fdi_errno())) return IOS_UNAVAILABLE;
        fdi_throw_io("write0 failed");
    }
    return (int32_t)n;
}

/* =========== pread0 / pwrite0 =========== */

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_pread0__Ljava_io_FileDescriptor_JIJ_I(
        void* fdo, int64_t address, int32_t len, int64_t position)
{
    int fd = fdi_fdval(fdo);
    void* buf = (void*)(intptr_t)address;

    int64_t n;
    do {
        n = fdi_pread(fd, buf, (size_t)len, position);
    } while (n < 0 && fdi_is_interrupted(fdi_errno()));

    if (n < 0) {
        if (fdi_is_would_block(fdi_errno())) return IOS_UNAVAILABLE;
        fdi_throw_io("pread0 failed");
    }
    return (int32_t)n;
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_pwrite0__Ljava_io_FileDescriptor_JIJ_I(
        void* fdo, int64_t address, int32_t len, int64_t position)
{
    int fd = fdi_fdval(fdo);
    void* buf = (void*)(intptr_t)address;

    int64_t n;
    do {
        n = fdi_pwrite(fd, buf, (size_t)len, position);
    } while (n < 0 && fdi_is_interrupted(fdi_errno()));

    if (n < 0) {
        if (fdi_is_would_block(fdi_errno())) return IOS_UNAVAILABLE;
        fdi_throw_io("pwrite0 failed");
    }
    return (int32_t)n;
}

/* =========== readv0 / writev0 =========== */

#if defined(_WIN32)
int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_readv0__Ljava_io_FileDescriptor_JI_J(
        void* fdo, int64_t address, int32_t len)
{
    (void)fdo; (void)address; (void)len;
    fdi_throw_io("readv0 not supported on this platform");
}
int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_writev0__Ljava_io_FileDescriptor_JI_J(
        void* fdo, int64_t address, int32_t len)
{
    (void)fdo; (void)address; (void)len;
    fdi_throw_io("writev0 not supported on this platform");
}
#else
int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_readv0__Ljava_io_FileDescriptor_JI_J(
        void* fdo, int64_t address, int32_t len)
{
    int fd = fdi_fdval(fdo);
    struct iovec* iov = (struct iovec*)(intptr_t)address;
    ssize_t n = readv(fd, iov, len);
    if (n < 0) {
        if (fdi_is_would_block(errno)) return (int64_t)IOS_UNAVAILABLE;
        if (fdi_is_interrupted(errno)) return (int64_t)IOS_INTERRUPTED;
        fdi_throw_io("readv failed");
    }
    return (int64_t)n;
}
int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_writev0__Ljava_io_FileDescriptor_JI_J(
        void* fdo, int64_t address, int32_t len)
{
    int fd = fdi_fdval(fdo);
    struct iovec* iov = (struct iovec*)(intptr_t)address;
    ssize_t n = writev(fd, iov, len);
    if (n < 0) {
        if (fdi_is_would_block(errno)) return (int64_t)IOS_UNAVAILABLE;
        if (fdi_is_interrupted(errno)) return (int64_t)IOS_INTERRUPTED;
        fdi_throw_io("writev failed");
    }
    return (int64_t)n;
}
#endif

/* =========== seek0 =========== */

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_seek0__Ljava_io_FileDescriptor_J_J(
        void* fdo, int64_t offset)
{
    int fd = fdi_fdval(fdo);
    int64_t p = fdi_lseek(fd, offset, SEEK_SET);
    if (p < 0) fdi_throw_io("lseek failed");
    return p;
}

/* =========== size0 =========== */

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_size0__Ljava_io_FileDescriptor_J(
        void* fdo)
{
    int fd = fdi_fdval(fdo);
#if defined(_WIN32)
    intptr_t h = _get_osfhandle(fd);
    if (h == -1) fdi_throw_win32("_get_osfhandle failed");
    LARGE_INTEGER sz;
    if (!GetFileSizeEx((HANDLE)h, &sz))
        fdi_throw_win32("GetFileSizeEx failed");
    return (int64_t)sz.QuadPart;
#else
    struct stat st;
    if (fstat(fd, &st) < 0) fdi_throw_io("fstat failed");
    return (int64_t)st.st_size;
#endif
}

/* =========== force0 =========== */

void __jnative_fn_sun_nio_ch_FileDispatcherImpl_force0__Ljava_io_FileDescriptor_Z_V(
        void* fdo, int32_t metadata)
{
    int fd = fdi_fdval(fdo);
    (void)metadata;
    if (fdi_commit(fd) != 0) fdi_throw_io("fsync failed");
}

/* =========== truncate0 =========== */

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_truncate0__Ljava_io_FileDescriptor_J_J(
        void* fdo, int64_t size)
{
    int fd = fdi_fdval(fdo);
#if defined(_WIN32)
    intptr_t h = _get_osfhandle(fd);
    if (h == -1) fdi_throw_win32("_get_osfhandle failed");
    LARGE_INTEGER li;
    li.QuadPart = size;
    if (SetFilePointerEx((HANDLE)h, li, NULL, FILE_BEGIN) == 0)
        fdi_throw_win32("SetFilePointerEx failed");
    if (!SetEndOfFile((HANDLE)h))
        fdi_throw_win32("SetEndOfFile failed");
    return size;
#else
    if (ftruncate(fd, (off_t)size) < 0) fdi_throw_io("ftruncate failed");
    return size;
#endif
}

/* =========== close0 =========== */

void __jnative_fn_sun_nio_ch_FileDispatcherImpl_close0__Ljava_io_FileDescriptor_V(
        void* fdo)
{
    int fd = fdi_fdval(fdo);
    fdi_close(fd);
    *(int32_t*)((char*)fdo + FD_OFFSET) = -1;
}

/* =========== dup0 =========== */

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_dup0__Ljava_io_FileDescriptor_I(
        void* fdo)
{
    int fd = fdi_fdval(fdo);
#if defined(_WIN32)
    int nd = _dup(fd);
    if (nd < 0) fdi_throw_io("_dup failed");
    return nd;
#else
    int nd = dup(fd);
    if (nd < 0) fdi_throw_io("dup failed");
    return nd;
#endif
}

/* =========== transferTo0 =========== */

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_transferTo0__Ljava_io_FileDescriptor_JJLjava_io_FileDescriptor_Z_J(
        void* src_fdo, int64_t position, int64_t count,
        void* dst_fdo, int32_t append)
{
    int src = fdi_fdval(src_fdo);
    int dst = fdi_fdval(dst_fdo);

    if (append) {
        if (fdi_lseek(dst, 0, SEEK_END) < 0) {
            fdi_throw_io("lseek dst to end failed");
        }
    }

#if !defined(_WIN32)
    off_t off = (off_t)position;
    ssize_t sn = sendfile(dst, src, &off, (size_t)count);
    if (sn >= 0) return (int64_t)sn;
    if (errno != EINVAL && errno != ENOSYS && errno != EOPNOTSUPP) {
        fdi_throw_io("sendfile failed");
    }
#endif

    char buf[65536];
    int64_t total = 0;
    while (total < count) {
        size_t chunk = (size_t)((count - total < (int64_t)sizeof(buf))
                                ? (count - total) : (int64_t)sizeof(buf));

        int64_t nr;
        do {
            nr = fdi_pread(src, buf, chunk, position + total);
        } while (nr < 0 && fdi_is_interrupted(fdi_errno()));
        if (nr < 0) fdi_throw_io("pread src failed");
        if (nr == 0) break;

        int64_t written = 0;
        while (written < nr) {
            int64_t nw;
            do {
                nw = fdi_write(dst, buf + written, (size_t)(nr - written));
            } while (nw < 0 && fdi_is_interrupted(fdi_errno()));
            if (nw < 0) fdi_throw_io("write dst failed");
            if (nw == 0) return total;
            written += nw;
        }
        total += nr;
    }
    return total;
}

/* =========== transferFrom0 =========== */

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_transferFrom0__Ljava_io_FileDescriptor_Ljava_io_FileDescriptor_JJZ_J(
        void* src_fdo, void* dst_fdo, int64_t position, int64_t count,
        int32_t append)
{
    int src = fdi_fdval(src_fdo);
    int dst = fdi_fdval(dst_fdo);

    if (append) {
        if (fdi_lseek(dst, 0, SEEK_END) < 0) {
            fdi_throw_io("lseek dst to end failed");
        }
    } else {
        if (fdi_lseek(dst, position, SEEK_SET) < 0) {
            fdi_throw_io("lseek dst failed");
        }
    }

    char buf[65536];
    int64_t total = 0;
    while (total < count) {
        size_t chunk = (size_t)((count - total < (int64_t)sizeof(buf))
                                ? (count - total) : (int64_t)sizeof(buf));

        int64_t nr;
        do {
            nr = fdi_read(src, buf, chunk);
        } while (nr < 0 && fdi_is_interrupted(fdi_errno()));
        if (nr < 0) fdi_throw_io("read src failed");
        if (nr == 0) break;

        int64_t written = 0;
        while (written < nr) {
            int64_t nw;
            do {
                nw = fdi_write(dst, buf + written, (size_t)(nr - written));
            } while (nw < 0 && fdi_is_interrupted(fdi_errno()));
            if (nw < 0) fdi_throw_io("write dst failed");
            if (nw == 0) return total;
            written += nw;
        }
        total += nr;
    }
    return total;
}
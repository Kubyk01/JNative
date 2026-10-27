/*
 * sun.nio.ch.UnixFileDispatcherImpl -- the native surface of the
 * sun.nio.ch.FileDispatcherImpl hierarchy.
 *
 * ==========================================================================
 * Portability
 * ==========================================================================
 *
 * This file is the *cross-platform* implementation. On POSIX it uses the
 * native readv(2) / writev(2) / pread(2) / pwrite(2) / ftruncate(2) /
 * fsync(2) / fstat(2) / dup(2) family. On Windows the same observable
 * semantics are provided through the Microsoft CRT (the _read / _write
 * / _lseeki64 / _chsize_s / _commit / _fstat64 family), because:
 *
 *   * <sys/uio.h> does not exist on Windows and the file must not fail
 *     to compile there;
 *   * <sys/mman.h>, <sys/ioctl.h>, <sys/socket.h> are likewise absent;
 *   * a cross-compiled image built with `--target=x86_64-w64-windows-gnu`
 *     still has to link the callers that `sun.nio.ch.FileChannelImpl`
 *     emits from the *build host's* java.base module, so the symbols
 *     declared here must have bodies on every target.
 *
 * The iovec ABI is fixed by sun.nio.ch.IOVecWrapper:
 *
 *     64-bit: { void* base; size_t len; }  = 16 bytes
 *     32-bit: { void* base; size_t len; }  =  8 bytes
 *
 * On POSIX this matches `struct iovec` on every platform we support
 * (Linux x86_64 / aarch64, macOS x86_64 / arm64). On Windows the same
 * layout is reproduced with a locally-declared struct. Because both use
 * native pointer/size_t widths, the Java-side IOVecWrapper's `SIZE_IOVEC`
 * (computed from `Unsafe.addressSize()`) always matches.
 *
 * ==========================================================================
 * fd layout
 * ==========================================================================
 *
 * Every method in this file takes a `java.io.FileDescriptor` object as
 * its first parameter and reads the raw descriptor via
 *
 *     jnative_fd_of(fd_obj)  ==  *(int32_t*)((char*)fd_obj + FD_OFFSET)
 *
 * where FD_OFFSET is 8 (see jnative_runtime.h). On POSIX this is the
 * kernel fd from open(2); on Windows it is the CRT fd from _open(2),
 * which is what _read / _write / _lseeki64 consume. The `handle` field
 * at offset 16 (used by Unsafe-style paths) is never touched here.
 */

#define _GNU_SOURCE
#include "jnative_platform.h"

#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/stat.h>

#include "jnative_runtime.h"

#if !defined(_WIN32)
#  include <unistd.h>
#  include <sys/uio.h>
#else
#  include <io.h>
#endif

/* ------------------------------------------------------------------------
 * iovec ABI (see file header)
 * ------------------------------------------------------------------------ */
#if defined(_WIN32)
typedef struct {
    void*  iov_base;
    size_t iov_len;
} jn_iovec_t;
#else
typedef struct iovec jn_iovec_t;
#endif

/* ------------------------------------------------------------------------
 * Small helpers
 * ------------------------------------------------------------------------ */

__attribute__((noreturn))
static void fd_throw_io(const char* msg) {
    void* exc = __jnative_construct_exception(
        "vtable_java_io_IOException", msg ? msg : "I/O error");
    __jnative_throw_exception(exc);
}

static inline int32_t fd_checked(void* fd_obj) {
    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int32_t fd = jnative_fd_of(fd_obj);
    if (fd < 0) {
        fd_throw_io("file descriptor is closed");
    }
    return fd;
}

/* ------------------------------------------------------------------------
 * Portable single read / write into a raw buffer.
 *
 * The size_t vs unsigned int difference between POSIX read/write and
 * Windows _read/_write is hidden here. Each call moves at most
 * INT32_MAX bytes, which is the maximum a single Java int-sized return
 * value can report anyway.
 * ------------------------------------------------------------------------ */
static int64_t do_read(int32_t fd, void* buf, size_t count) {
#if defined(_WIN32)
    if (count > 0x7FFFFFFFu) count = 0x7FFFFFFFu;
    int n;
    do {
        n = _read(fd, buf, (unsigned int)count);
    } while (n < 0 && errno == EINTR);
    return (int64_t)n;
#else
    ssize_t n;
    do {
        n = read(fd, buf, count);
    } while (n < 0 && errno == EINTR);
    return (int64_t)n;
#endif
}

static int64_t do_write(int32_t fd, const void* buf, size_t count) {
#if defined(_WIN32)
    if (count > 0x7FFFFFFFu) count = 0x7FFFFFFFu;
    int n;
    do {
        n = _write(fd, buf, (unsigned int)count);
    } while (n < 0 && errno == EINTR);
    return (int64_t)n;
#else
    ssize_t n;
    do {
        n = write(fd, buf, count);
    } while (n < 0 && errno == EINTR);
    return (int64_t)n;
#endif
}

/* ------------------------------------------------------------------------
 * Portable seek helpers.
 * ------------------------------------------------------------------------ */
static int64_t do_lseek_cur(int32_t fd) {
#if defined(_WIN32)
    return _lseeki64(fd, 0, SEEK_CUR);
#else
    return (int64_t)lseek(fd, 0, SEEK_CUR);
#endif
}

static int64_t do_lseek_set(int32_t fd, int64_t pos) {
#if defined(_WIN32)
    return _lseeki64(fd, pos, SEEK_SET);
#else
    return (int64_t)lseek(fd, (off_t)pos, SEEK_SET);
#endif
}

/* ------------------------------------------------------------------------
 * Portable iovec-aware read/write.
 *
 * POSIX uses the platform readv(2)/writev(2). Windows lacks them, so
 * the sequence of iovecs is walked manually. readv may stop on a short
 * read (POSIX semantics); writev loops over each iovec until its bytes
 * are fully written (or fails on the first iovec with an empty write).
 * ------------------------------------------------------------------------ */
static int64_t do_readv(int32_t fd, const jn_iovec_t* iov, int iovcnt) {
#if defined(_WIN32)
    int64_t total = 0;
    for (int i = 0; i < iovcnt; i++) {
        size_t remaining = iov[i].iov_len;
        char*  buf       = (char*)iov[i].iov_base;
        while (remaining > 0) {
            unsigned int chunk = (remaining > 0x7FFFFFFFu)
                ? 0x7FFFFFFFu : (unsigned int)remaining;
            int64_t n = do_read(fd, buf, chunk);
            if (n < 0) {
                if (total > 0) return total;
                return -1;
            }
            if (n == 0) return total;
            total += n;
            buf   += n;
            remaining -= (size_t)n;
            if ((unsigned int)n < chunk) return total;
        }
    }
    return total;
#else
    ssize_t n;
    do {
        n = readv(fd, (const struct iovec*)iov, iovcnt);
    } while (n < 0 && errno == EINTR);
    return (int64_t)n;
#endif
}

static int64_t do_writev(int32_t fd, const jn_iovec_t* iov, int iovcnt) {
#if defined(_WIN32)
    int64_t total = 0;
    for (int i = 0; i < iovcnt; i++) {
        size_t remaining = iov[i].iov_len;
        const char* buf  = (const char*)iov[i].iov_base;
        while (remaining > 0) {
            unsigned int chunk = (remaining > 0x7FFFFFFFu)
                ? 0x7FFFFFFFu : (unsigned int)remaining;
            int64_t n = do_write(fd, buf, chunk);
            if (n < 0) {
                if (total > 0) return total;
                return -1;
            }
            if (n == 0) return total;
            total += n;
            buf += n;
            remaining -= (size_t)n;
        }
    }
    return total;
#else
    ssize_t n;
    do {
        n = writev(fd, (const struct iovec*)iov, iovcnt);
    } while (n < 0 && errno == EINTR);
    return (int64_t)n;
#endif
}

/* ------------------------------------------------------------------------
 * init / initIDs / preClose0
 * ------------------------------------------------------------------------ */

void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_init___V(void) {
}

void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_initIDs___V(void) {
}

void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_preClose0__Ljava_io_FileDescriptor__V(
        void* fd_obj) {
    /*
     * HotSpot uses this hook to release a dup'ed fd used by
     * RandomAccessFile.close. The runtime never dup's on preClose, so
     * the hook is a no-op on both platforms.
     */
    (void)fd_obj;
}

/* ------------------------------------------------------------------------
 * read0 / write0 -- direct buffer I/O at the current file position
 * ------------------------------------------------------------------------ */

int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_read0__Ljava_io_FileDescriptor_JI_I(
        void* fd_obj, int64_t address, int32_t len)
{
    if (address == 0 || len < 0) {
        __jnative_throw_array_index_out_of_bounds();
    }
    int32_t fd = fd_checked(fd_obj);
    int64_t n  = do_read(fd, (void*)(intptr_t)address, (size_t)len);
    if (n < 0) fd_throw_io("read failed");
    return (int32_t)n;
}

int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_write0__Ljava_io_FileDescriptor_JI_I(
        void* fd_obj, int64_t address, int32_t len)
{
    if (address == 0 || len < 0) {
        __jnative_throw_array_index_out_of_bounds();
    }
    int32_t fd = fd_checked(fd_obj);
    int64_t n  = do_write(fd, (const void*)(intptr_t)address, (size_t)len);
    if (n < 0) fd_throw_io("write failed");
    return (int32_t)n;
}

/* ------------------------------------------------------------------------
 * readv0 / writev0 -- scatter/gather I/O
 *
 * `address` is the base of an iovec array; `len` is the iovec count,
 * NOT a byte length. The return value is the total bytes transferred.
 * ------------------------------------------------------------------------ */

int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_readv0__Ljava_io_FileDescriptor_JI_J(
        void* fd_obj, int64_t address, int32_t len)
{
    if (address == 0 || len < 0) {
        __jnative_throw_array_index_out_of_bounds();
    }
    int32_t fd = fd_checked(fd_obj);
    int64_t n  = do_readv(fd, (const jn_iovec_t*)(intptr_t)address, len);
    if (n < 0) fd_throw_io("readv failed");
    return n;
}

int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_writev0__Ljava_io_FileDescriptor_JI_J(
        void* fd_obj, int64_t address, int32_t len)
{
    if (address == 0 || len < 0) {
        __jnative_throw_array_index_out_of_bounds();
    }
    int32_t fd = fd_checked(fd_obj);
    int64_t n  = do_writev(fd, (const jn_iovec_t*)(intptr_t)address, len);
    if (n < 0) fd_throw_io("writev failed");
    return n;
}

/* ------------------------------------------------------------------------
 * close0
 * ------------------------------------------------------------------------ */

void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_close0__Ljava_io_FileDescriptor__V(
        void* fd_obj)
{
    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int32_t fd = jnative_fd_of(fd_obj);
    if (fd >= 0) {
#if defined(_WIN32)
        (void)_close(fd);
#else
        (void)close(fd);
#endif
    }
}

/* ------------------------------------------------------------------------
 * force0 -- fsync / _commit
 * ------------------------------------------------------------------------ */

int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_force0__Ljava_io_FileDescriptor_Z_I(
        void* fd_obj, int32_t metaData)
{
    int32_t fd = fd_checked(fd_obj);

#if defined(_WIN32)
    (void)metaData;
    if (_commit(fd) != 0) fd_throw_io("_commit failed");
#else
    int rc;
    if (metaData) {
        rc = fsync(fd);
    } else {
#  if defined(__APPLE__)
        rc = fsync(fd);
#  else
        rc = fdatasync(fd);
#  endif
    }
    if (rc != 0) fd_throw_io("fsync failed");
#endif
    return 0;
}

/* ------------------------------------------------------------------------
 * dup0 -- duplicate a descriptor into a fresh FileDescriptor object
 * ------------------------------------------------------------------------ */

void* __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_dup0__Ljava_io_FileDescriptor_Ljava_io_FileDescriptor_(
        void* fd_obj)
{
    int32_t fd = fd_checked(fd_obj);

#if defined(_WIN32)
    int32_t new_fd = _dup(fd);
#else
    int32_t new_fd = dup(fd);
#endif
    if (new_fd < 0) fd_throw_io("dup failed");

    ReflectionClass* fdc = jnative_class_by_name("java/io/FileDescriptor");
    if (fdc == NULL) {
        fd_throw_io("java/io/FileDescriptor not registered");
    }
    void* new_obj = jnative_alloc_object(fdc);
    if (new_obj == NULL) {
        __jnative_throw_out_of_memory_error_ctx("dup0");
    }
    *(int32_t*)((char*)new_obj + FD_OFFSET) = new_fd;
    return new_obj;
}

/* ------------------------------------------------------------------------
 * truncate0 -- set the file length
 * ------------------------------------------------------------------------ */

int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_truncate0__Ljava_io_FileDescriptor_J_I(
        void* fd_obj, int64_t size)
{
    if (size < 0) fd_throw_io("negative size");
    int32_t fd = fd_checked(fd_obj);

#if defined(_WIN32)
    if (_chsize_s(fd, size) != 0) fd_throw_io("_chsize_s failed");
#else
    if (ftruncate(fd, (off_t)size) != 0) fd_throw_io("ftruncate failed");
#endif
    return 0;
}

/* ------------------------------------------------------------------------
 * size0 -- file size
 * ------------------------------------------------------------------------ */

int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_size0__Ljava_io_FileDescriptor__J(
        void* fd_obj)
{
    int32_t fd = fd_checked(fd_obj);

#if defined(_WIN32)
    struct _stat64 st;
    if (_fstat64(fd, &st) != 0) fd_throw_io("fstat failed");
    return (int64_t)st.st_size;
#else
    struct stat st;
    if (fstat(fd, &st) != 0) fd_throw_io("fstat failed");
    return (int64_t)st.st_size;
#endif
}

/* ------------------------------------------------------------------------
 * pread0 / pwrite0 -- positional I/O
 *
 * POSIX uses pread(2)/pwrite(2) (atomic). Windows emulates the same
 * semantics with save-seek-read-restore; the caller (FileChannelImpl
 * positional I/O) is single-threaded on the descriptor, so the
 * emulation is observationally equivalent.
 * ------------------------------------------------------------------------ */

int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_pread0__Ljava_io_FileDescriptor_JIJ_I(
        void* fd_obj, int64_t address, int32_t len, int64_t position)
{
    if (address == 0 || len < 0 || position < 0) {
        __jnative_throw_array_index_out_of_bounds();
    }
    int32_t fd = fd_checked(fd_obj);

#if defined(_WIN32)
    int64_t saved = do_lseek_cur(fd);
    if (saved < 0) fd_throw_io("lseek failed");
    if (do_lseek_set(fd, position) < 0) fd_throw_io("lseek failed");

    int64_t n = do_read(fd, (void*)(intptr_t)address, (size_t)len);
    (void)do_lseek_set(fd, saved);
    if (n < 0) fd_throw_io("pread failed");
    return (int32_t)n;
#else
    ssize_t n;
    do {
        n = pread(fd, (void*)(intptr_t)address,
                  (size_t)len, (off_t)position);
    } while (n < 0 && errno == EINTR);
    if (n < 0) fd_throw_io("pread failed");
    return (int32_t)n;
#endif
}

int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_pwrite0__Ljava_io_FileDescriptor_JIJ_I(
        void* fd_obj, int64_t address, int32_t len, int64_t position)
{
    if (address == 0 || len < 0 || position < 0) {
        __jnative_throw_array_index_out_of_bounds();
    }
    int32_t fd = fd_checked(fd_obj);

#if defined(_WIN32)
    int64_t saved = do_lseek_cur(fd);
    if (saved < 0) fd_throw_io("lseek failed");
    if (do_lseek_set(fd, position) < 0) fd_throw_io("lseek failed");

    int64_t n = do_write(fd, (const void*)(intptr_t)address, (size_t)len);
    (void)do_lseek_set(fd, saved);
    if (n < 0) fd_throw_io("pwrite failed");
    return (int32_t)n;
#else
    ssize_t n;
    do {
        n = pwrite(fd, (const void*)(intptr_t)address,
                   (size_t)len, (off_t)position);
    } while (n < 0 && errno == EINTR);
    if (n < 0) fd_throw_io("pwrite failed");
    return (int32_t)n;
#endif
}
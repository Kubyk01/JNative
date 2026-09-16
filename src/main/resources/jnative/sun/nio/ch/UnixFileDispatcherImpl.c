#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <unistd.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/uio.h>

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);

/*
 * FileDescriptor layout in this runtime:
 *   [ 8 bytes vtable ][ int32 fd ][ long handle ]
 *
 * Every native method of UnixFileDispatcherImpl receives the FileDescriptor
 * object (not the raw kernel fd); the raw fd is the first instance field at
 * offset 8.
 */
#define FD_OFFSET 8

static inline int32_t fd_of(void* fd_obj) {
    return *(int32_t*)((char*)fd_obj + FD_OFFSET);
}

static int32_t raw_fd(void* fd_obj) {
    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int32_t fd = fd_of(fd_obj);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
    }
    return fd;
}

/* ---------------------------------------------------------------------------
 * static native int read0(FileDescriptor fd, long address, int len)
 * ------------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_read0__Ljava_io_FileDescriptor_JI_I(
        void* fd_obj, int64_t address, int32_t len)
{
    int32_t fd = raw_fd(fd_obj);
    if (len < 0) {
        __jnative_throw_exception(NULL);
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

/* ---------------------------------------------------------------------------
 * static native int pread0(FileDescriptor fd, long address, int len, long position)
 * ------------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_pread0__Ljava_io_FileDescriptor_JIJ_I(
        void* fd_obj, int64_t address, int32_t len, int64_t position)
{
    int32_t fd = raw_fd(fd_obj);
    if (len < 0 || position < 0) {
        __jnative_throw_exception(NULL);
    }
    void* buf = (void*)(intptr_t)address;
    ssize_t n;
    do {
        n = pread(fd, buf, (size_t)len, (off_t)position);
    } while (n < 0 && errno == EINTR);
    if (n < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int32_t)n;
}

/* ---------------------------------------------------------------------------
 * static native int write0(FileDescriptor fd, long address, int len)
 * ------------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_write0__Ljava_io_FileDescriptor_JI_I(
        void* fd_obj, int64_t address, int32_t len)
{
    int32_t fd = raw_fd(fd_obj);
    if (len < 0) {
        __jnative_throw_exception(NULL);
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

/* ---------------------------------------------------------------------------
 * static native int pwrite0(FileDescriptor fd, long address, int len, long position)
 * ------------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_pwrite0__Ljava_io_FileDescriptor_JIJ_I(
        void* fd_obj, int64_t address, int32_t len, int64_t position)
{
    int32_t fd = raw_fd(fd_obj);
    if (len < 0 || position < 0) {
        __jnative_throw_exception(NULL);
    }
    const void* buf = (const void*)(intptr_t)address;
    ssize_t n;
    do {
        n = pwrite(fd, buf, (size_t)len, (off_t)position);
    } while (n < 0 && errno == EINTR);
    if (n < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int32_t)n;
}

/* ---------------------------------------------------------------------------
 * static native long readv0(FileDescriptor fd, long address, int len)
 *
 * The iovec array and count are packed by the Java side into the native
 * buffer that `address` points to: the first 4 bytes hold the iovec count,
 * followed by that many struct iovec records. We read the count from the
 * buffer and pass the rest to readv().
 * ------------------------------------------------------------------------- */
int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_readv0__Ljava_io_FileDescriptor_JI_J(
        void* fd_obj, int64_t address, int32_t len)
{
    (void)len;
    int32_t fd = raw_fd(fd_obj);
    int32_t* hdr = (int32_t*)(intptr_t)address;
    int32_t count = *hdr;
    struct iovec* iov = (struct iovec*)(hdr + 1);
    ssize_t n;
    do {
        n = readv(fd, iov, (int)count);
    } while (n < 0 && errno == EINTR);
    if (n < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int64_t)n;
}

/* ---------------------------------------------------------------------------
 * static native long writev0(FileDescriptor fd, long address, int len)
 * ------------------------------------------------------------------------- */
int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_writev0__Ljava_io_FileDescriptor_JI_J(
        void* fd_obj, int64_t address, int32_t len)
{
    (void)len;
    int32_t fd = raw_fd(fd_obj);
    int32_t* hdr = (int32_t*)(intptr_t)address;
    int32_t count = *hdr;
    struct iovec* iov = (struct iovec*)(hdr + 1);
    ssize_t n;
    do {
        n = writev(fd, iov, (int)count);
    } while (n < 0 && errno == EINTR);
    if (n < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int64_t)n;
}

/* ---------------------------------------------------------------------------
 * static native void close0(FileDescriptor fd)
 * ------------------------------------------------------------------------- */
void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_close0__Ljava_io_FileDescriptor__V(
        void* fd_obj)
{
    if (fd_obj == NULL) {
        return;
    }
    int32_t fd = fd_of(fd_obj);
    if (fd >= 0) {
        (void)close(fd);
    }
}

/* ---------------------------------------------------------------------------
 * static native void force0(FileDescriptor fd, boolean metaData)
 * ------------------------------------------------------------------------- */
void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_force0__Ljava_io_FileDescriptor_Z_V(
        void* fd_obj, int32_t meta_data)
{
    int32_t fd = raw_fd(fd_obj);
    int rc;
    if (meta_data) {
        rc = fsync(fd);
    } else {
        rc = fdatasync(fd);
    }
    if (rc < 0) {
        __jnative_throw_exception(NULL);
    }
}

/* ---------------------------------------------------------------------------
 * static native void truncate0(FileDescriptor fd, long size)
 * ------------------------------------------------------------------------- */
void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_truncate0__Ljava_io_FileDescriptor_J_V(
        void* fd_obj, int64_t size)
{
    int32_t fd = raw_fd(fd_obj);
    if (size < 0) {
        __jnative_throw_exception(NULL);
    }
    if (ftruncate(fd, (off_t)size) < 0) {
        __jnative_throw_exception(NULL);
    }
}

/* ---------------------------------------------------------------------------
 * static native long size0(FileDescriptor fd)
 * ------------------------------------------------------------------------- */
int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_size0__Ljava_io_FileDescriptor__J(
        void* fd_obj)
{
    int32_t fd = raw_fd(fd_obj);
    struct stat st;
    if (fstat(fd, &st) < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int64_t)st.st_size;
}

/* ---------------------------------------------------------------------------
 * static native long seek0(FileDescriptor fd, long offset)
 * ------------------------------------------------------------------------- */
int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_seek0__Ljava_io_FileDescriptor_J_J(
        void* fd_obj, int64_t offset)
{
    int32_t fd = raw_fd(fd_obj);
    off_t pos = lseek(fd, (off_t)offset, SEEK_SET);
    if (pos < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int64_t)pos;
}
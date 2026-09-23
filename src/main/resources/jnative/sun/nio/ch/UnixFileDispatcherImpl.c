#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/mman.h>

#include "jnative_runtime.h"

/*
 * sun.nio.ch.UnixFileDispatcherImpl — the file-channel dispatcher for
 * Unix platforms.
 *
 * The class implements the whole read/write/seek/size/force/truncate
 * family that FileChannel delegates to, plus the Linux-specific
 * memory-mapped-file and O_DIRECT paths. Every native receives the
 * FileDescriptor object (not the raw kernel fd); the raw fd is
 * extracted with jnative_raw_fd from jnative_runtime.h, which
 * enforces the runtime's standard "NPE on null receiver, throw on
 * closed descriptor" convention.
 *
 * The entry points for the two zero-copy sendfile paths
 * (transferTo0 / transferFrom0) live in the sibling file
 * jnative/sun/nio/ch/FileDispatcherImpl.c, which shares the same
 * descriptor layout and error convention.
 */

/*
 * static native int read0(FileDescriptor fd, long address, int len);
 *
 * Reads up to `len` bytes from the file into the native buffer at
 * `address`. Returns the number of bytes actually read (0 on end of
 * file, never more than `len`).
 *
 * EINTR with no progress is retried so a signal does not surface as a
 * spurious short read to the Java caller. Any hard error (EBADF, EIO)
 * is routed through the generic throw helper.
 */
int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_read0__Ljava_io_FileDescriptor_JI_I(
        void* fd_obj, int64_t address, int32_t len)
{
    int32_t fd = jnative_raw_fd(fd_obj);
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

/*
 * static native int pread0(FileDescriptor fd, long address, int len,
 *                          long position);
 *
 * Reads up to `len` bytes from the file starting at the absolute
 * offset `position`, leaving the file's own position untouched. Same
 * EINTR retry and error convention as read0 above.
 */
int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_pread0__Ljava_io_FileDescriptor_JIJ_I(
        void* fd_obj, int64_t address, int32_t len, int64_t position)
{
    int32_t fd = jnative_raw_fd(fd_obj);
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

/*
 * static native int write0(FileDescriptor fd, long address, int len);
 *
 * Writes up to `len` bytes from the native buffer at `address` to the
 * file. Returns the number of bytes actually written, which may be
 * less than `len` if the kernel performs a partial write; the
 * Java-side loop drives the remainder.
 */
int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_write0__Ljava_io_FileDescriptor_JI_I(
        void* fd_obj, int64_t address, int32_t len)
{
    int32_t fd = jnative_raw_fd(fd_obj);
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

/*
 * static native int pwrite0(FileDescriptor fd, long address, int len,
 *                           long position);
 *
 * Writes up to `len` bytes from the native buffer at `address` to the
 * file starting at the absolute offset `position`, leaving the file's
 * own position untouched. Same EINTR retry and error convention as
 * write0 above.
 */
int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_pwrite0__Ljava_io_FileDescriptor_JIJ_I(
        void* fd_obj, int64_t address, int32_t len, int64_t position)
{
    int32_t fd = jnative_raw_fd(fd_obj);
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

/*
 * static native long readv0(FileDescriptor fd, long address, int len);
 *
 * The iovec array and count are packed by the Java side into the
 * native buffer that `address` points to: the first 4 bytes hold the
 * iovec count, followed by that many struct iovec records. We read
 * the count from the buffer and pass the rest to readv().
 *
 * The `len` argument (the total byte count the caller believes the
 * iovec set describes) is informational and unused: the kernel reads
 * exactly what the iovec array specifies, so a mismatch between the
 * caller's expectation and the iovec contents would be a bug in the
 * Java layer, not something this native could correct.
 */
int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_readv0__Ljava_io_FileDescriptor_JI_J(
        void* fd_obj, int64_t address, int32_t len)
{
    (void)len;
    int32_t fd = jnative_raw_fd(fd_obj);
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

/*
 * static native long writev0(FileDescriptor fd, long address, int len);
 *
 * Same packing convention as readv0: iovec count followed by the
 * iovec array. Same EINTR retry and error convention.
 */
int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_writev0__Ljava_io_FileDescriptor_JI_J(
        void* fd_obj, int64_t address, int32_t len)
{
    (void)len;
    int32_t fd = jnative_raw_fd(fd_obj);
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

/*
 * static native void close0(FileDescriptor fd);
 *
 * Closes the underlying descriptor. A NULL receiver returns without
 * doing anything, matching the reference implementation's tolerance
 * for a FileDescriptor that has already been cleared. A non-negative
 * fd is closed and the return value of close(2) is deliberately
 * ignored: the Java layer has no meaningful recovery path for a failed
 * close, and swallowing errno here matches the reference behaviour.
 */
void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_close0__Ljava_io_FileDescriptor__V(
        void* fd_obj)
{
    if (fd_obj == NULL) {
        return;
    }
    int32_t fd = jnative_fd_of(fd_obj);
    if (fd >= 0) {
        (void)close(fd);
    }
}

/*
 * static native void force0(FileDescriptor fd, boolean metaData);
 *
 * Flushes the file's dirty pages to disk. `metaData` selects between
 * fsync(2) (metadata included) and fdatasync(2) (data only), matching
 * the semantics of FileChannel.force. Any failure surfaces as the
 * generic throw helper.
 */
void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_force0__Ljava_io_FileDescriptor_Z_V(
        void* fd_obj, int32_t meta_data)
{
    int32_t fd = jnative_raw_fd(fd_obj);
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

/*
 * static native void truncate0(FileDescriptor fd, long size);
 *
 * Pre-JDK-18 signature: reports failure by throwing. Kept for backward
 * compatibility with class files compiled against older JDKs. The
 * JDK 18+ signature below is the one that newer builds actually
 * reference.
 */
void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_truncate0__Ljava_io_FileDescriptor_J_V(
        void* fd_obj, int64_t size)
{
    int32_t fd = jnative_raw_fd(fd_obj);
    if (size < 0) {
        __jnative_throw_exception(NULL);
    }
    if (ftruncate(fd, (off_t)size) < 0) {
        __jnative_throw_exception(NULL);
    }
}

/*
 * static native int truncate0(FileDescriptor fd, long size);
 *
 * JDK 18+ signature: returns 0 on success. Errors are still surfaced
 * through the generic throw helper so the Java caller's existing
 * `catch (IOException)` block continues to work; the return value is
 * the "success" sentinel the newer Java-level signature expects.
 */
int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_truncate0__Ljava_io_FileDescriptor_J_I(
        void* fd_obj, int64_t size)
{
    int32_t fd = jnative_raw_fd(fd_obj);
    if (size < 0) {
        __jnative_throw_exception(NULL);
    }
    if (ftruncate(fd, (off_t)size) < 0) {
        __jnative_throw_exception(NULL);
    }
    return 0;
}

/*
 * static native long size0(FileDescriptor fd);
 *
 * Total length of the file in bytes, obtained with fstat(2) on the
 * descriptor. Using the descriptor rather than the path makes the
 * answer correct even when the file is being accessed through a
 * FileDescriptor that has been passed in from elsewhere (for example
 * one obtained from an inherited descriptor).
 */
int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_size0__Ljava_io_FileDescriptor__J(
        void* fd_obj)
{
    int32_t fd = jnative_raw_fd(fd_obj);
    struct stat st;
    if (fstat(fd, &st) < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int64_t)st.st_size;
}

/*
 * static native long seek0(FileDescriptor fd, long offset);
 *
 * Sets the file's current position to `offset` (an absolute offset
 * from the start of the file) and returns the resulting position as
 * reported by lseek(2). Any failure surfaces as the generic throw
 * helper.
 */
int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_seek0__Ljava_io_FileDescriptor_J_J(
        void* fd_obj, int64_t offset)
{
    int32_t fd = jnative_raw_fd(fd_obj);
    off_t pos = lseek(fd, (off_t)offset, SEEK_SET);
    if (pos < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int64_t)pos;
}

/*
 * =========================================================================
 * Memory-mapped file support.
 *
 * FileChannel.map(FileChannel.MapMode mode, long position, long size)
 * delegates to the platform's mmap(2), and FileChannel's default
 * implementation of an explicit unmapping cycle delegates to
 * munmap(2). The `prot` argument carries the small integer encoding
 * that sun.nio.ch.FileChannelImpl declares:
 *
 *     MAP_RO (0) : read-only mapping
 *     MAP_RW (1) : read-write mapping
 *     MAP_PV (2) : private mapping (copy-on-write)
 *
 * Note the encoding is bitwise, not ordinal: MAP_PV implies both read
 * and write access, and MAP_RW implies read access as well. The
 * translation below sets the matching PROT_* bits accordingly.
 * =========================================================================
 */

/* sun.nio.ch.FileChannelImpl constants */
#define JDK_MAP_RO 0
#define JDK_MAP_RW 1
#define JDK_MAP_PV 2

/*
 * static native long map0(FileDescriptor fd, int prot, long position,
 *                         long size, boolean isSync);
 *
 * Maps the region [position, position + size) of the underlying file
 * into the process address space and returns its start address. The
 * `isSync` flag requests a mapping whose modifications are
 * synchronously written back to the backing store; on Linux this is
 * expressed with MAP_SYNC, which is only honoured on DAX-capable
 * filesystems. When the running kernel or filesystem does not support
 * MAP_SYNC, the flag is silently dropped and an ordinary MAP_SHARED
 * mapping is produced — the same degradation the reference JDK
 * exhibits.
 *
 * A NULL return address from mmap is impossible on Linux (mmap returns
 * MAP_FAILED, which is (void*)-1, on failure); the runtime signals
 * failure through the generic throw helper so the Java caller's
 * IOException handler sees a uniform error channel.
 */
int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_map0__Ljava_io_FileDescriptor_IJJZ_J(
        void* fd_obj, int32_t prot, int64_t position, int64_t size, int32_t isSync)
{
    int32_t fd = jnative_raw_fd(fd_obj);
    if (position < 0 || size < 0) {
        __jnative_throw_exception(NULL);
    }

    int mmap_prot = 0;
    if (prot & (1 << JDK_MAP_RO)) mmap_prot |= PROT_READ;
    if (prot & (1 << JDK_MAP_RW)) mmap_prot |= PROT_READ | PROT_WRITE;
    if (prot & (1 << JDK_MAP_PV)) mmap_prot |= PROT_READ | PROT_WRITE;

    int mmap_flags = MAP_SHARED;

    /*
     * MAP_SYNC was introduced in Linux 4.15 and is only effective on
     * filesystems that support the DAX direct-access model (ext4 with
     * -O dax, XFS with DAX, and a few others). On any other filesystem
     * the kernel rejects the call with EINVAL, so we only attempt it
     * when the build environment's headers declare the constant and
     * fall back to plain MAP_SHARED when the kernel refuses.
     */
    if (isSync) {
#ifdef MAP_SYNC
        mmap_flags |= MAP_SYNC;
#endif
    }

    void* result = mmap(NULL, (size_t)size, mmap_prot, mmap_flags,
                        fd, (off_t)position);
    if (result == MAP_FAILED && isSync) {
        /* Retry without MAP_SYNC: the filesystem may not support it. */
        mmap_flags &= ~MAP_SYNC;
        result = mmap(NULL, (size_t)size, mmap_prot, mmap_flags,
                      fd, (off_t)position);
    }
    if (result == MAP_FAILED) {
        __jnative_throw_exception(NULL);
        return 0;
    }

    return (int64_t)(intptr_t)result;
}

/*
 * static native int unmap0(long address, long size);
 *
 * Releases a mapping previously established by map0. Returns 0 on
 * success and the errno on failure; the Java caller inspects the
 * numeric result rather than an exception because unmapping happens on
 * the cleaner thread, where throwing would be dangerous (the
 * underlying mapping is already being dismantled and the JVM may be
 * mid-shutdown).
 *
 * A zero address is treated as "nothing to unmap" and succeeds
 * silently: the JDK's Cleaner machinery can call this after a
 * successful close that already released the mapping.
 */
int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_unmap0__JJ_I(
        int64_t address, int64_t size)
{
    if (address == 0) {
        return 0;
    }
    if (munmap((void*)(intptr_t)address, (size_t)size) < 0) {
        return (int32_t)errno;
    }
    return 0;
}

/*
 * static native long allocationGranularity0();
 *
 * The smallest unit in which the operating system allocates address
 * space. On Linux this is the page size, obtained from sysconf(3);
 * every page-size kernel this runtime targets (4 KiB, 16 KiB on some
 * ARM, 64 KiB on some PPC) is returned exactly as the kernel reports
 * it.
 *
 * FileChannel uses this value to align the position at which a mapping
 * starts when the caller has requested a non-page-aligned offset. If
 * sysconf fails — which has no realistic failure mode on Linux but is
 * defensive against a malformed environment — the 4 KiB default is the
 * smallest value that will satisfy every kernel.
 */
int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_allocationGranularity0___J(void)
{
    long pg = sysconf(_SC_PAGESIZE);
    if (pg <= 0) {
        pg = 4096;
    }
    return (int64_t)pg;
}

int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_setDirect0__Ljava_io_FileDescriptor__I(
        void* fd_obj)
{
    int32_t fd = jnative_raw_fd(fd_obj);

    int flags = fcntl(fd, F_GETFL);
    if (flags < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }

#if defined(O_DIRECT)
    if ((flags & O_DIRECT) == 0) {
        if (fcntl(fd, F_SETFL, flags | O_DIRECT) < 0) {
            __jnative_throw_exception(NULL);
            return 0;
        }
        return 1;
    }
#endif
    /*
     * Either the flag was already set, or the platform does not define
     * O_DIRECT. In both cases there is nothing more to do and the
     * descriptor is in the state the Java caller asked for.
     */
    return 0;
}

void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_release0__Ljava_io_FileDescriptor_JJ_V(
        void* fd_obj, int64_t pos, int64_t size)
{
    (void)pos;
    (void)size;

    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    /* Intentionally empty on Unix: the kernel releases an fd's file
     * locks automatically on close, so there is no side table to walk
     * and no unlock operation to issue. */
    (void)jnative_fd_of(fd_obj);
}
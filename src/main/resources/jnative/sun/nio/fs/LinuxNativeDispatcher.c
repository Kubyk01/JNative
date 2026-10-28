#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>

#if defined(_WIN32)
#  include <io.h>
#  include <sys/stat.h>
#else
#  include <unistd.h>
#  include <sys/types.h>
#endif

#include "jnative_runtime.h"

/* ============================================================================
 *  init() — one-shot setup, called from the class's <clinit>.
 *
 *  On Linux the reference implementation checks for the availability of
 *  the copy_file_range syscall and, if present, flips a static boolean
 *  that routes subsequent copies through the syscall rather than the
 *  read/write fallback. This runtime does not cache that decision: the
 *  copy_file_range native below attempts the syscall directly and falls
 *  back on ENOSYS, which is functionally equivalent and removes the
 *  need for the flag.
 *
 *  On Windows there is nothing to initialize: the class is a Linux-only
 *  dispatcher and its methods either throw or use portable calls.
 * ========================================================================== */

void __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_init___V(void) {
}

/* ============================================================================
 *  openat(int dfd, byte[] path, int flags, int mode) -> int
 * ========================================================================== */

int32_t __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_openat__I_BII_I(
        int32_t dfd, void* path_bytes, int32_t flags, int32_t mode)
{
    if (path_bytes == NULL) {
        __jnative_throw_null_pointer_exception();
        return -1;
    }

#if defined(_WIN32)
    /*
     * Windows has no openat(2). The closest equivalent — GetFileInformation-
     * ByHandleFileEx plus CreateFile relative to a directory handle — is
     * not exposed by the C runtime and would require a full Win32 rewrite
     * of the UnixPath resolution model. Since LinuxFileSystemProvider is
     * the only caller and it never runs on Windows, the honest behaviour
     * is to throw rather than invent a fake success.
     *
     * The null-pointer check above fires first, matching the reference
     * implementation's behaviour on a null path argument regardless of
     * platform.
     */
    (void)dfd;
    (void)flags;
    (void)mode;
    __jnative_throw_exception(NULL);
    return -1;
#else
    const char* path = (const char*)jnative_array_data(path_bytes);
    int fd = openat(dfd, path, flags, (mode_t)mode);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int32_t)fd;
#endif
}

/* ============================================================================
 *  close(int fd)
 * ========================================================================== */

void __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_close__I_V(int32_t fd) {
#if defined(_WIN32)
    if (_close(fd) < 0) {
        __jnative_throw_exception(NULL);
    }
#else
    if (close(fd) < 0) {
        __jnative_throw_exception(NULL);
    }
#endif
}

/* ============================================================================
 *  dup2(int fd, int newfd)
 * ========================================================================== */

void __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_dup2__II_V(
        int32_t fd, int32_t newfd)
{
#if defined(_WIN32)
    if (_dup2(fd, newfd) < 0) {
        __jnative_throw_exception(NULL);
    }
#else
    if (dup2(fd, newfd) < 0) {
        __jnative_throw_exception(NULL);
    }
#endif
}

/* ============================================================================
 *  posix_fadvise(int fd, long offset, long len, int advice) -> int
 *
 *  Advisory file-cache hint. POSIX specifies that implementations may
 *  ignore the advice entirely and return 0; Linux implements it by
 *  calling posix_fadvise(2). Windows' file cache is managed by the
 *  kernel and does not expose a hint API — there is no semantic
 *  difference between "the cache was advised" and "the cache was not
 *  advised" from any caller's perspective, because the advice is only
 *  ever a performance hint. Returning 0 on Windows is therefore the
 *  correct behaviour, not a stub.
 * ========================================================================== */

int32_t __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_posix_fadvise__IJJI_I(
        int32_t fd, int64_t offset, int64_t len, int32_t advice)
{
#if defined(_WIN32)
    (void)fd; (void)offset; (void)len; (void)advice;
    return 0;
#else
    int rc = posix_fadvise(fd, (off_t)offset, (off_t)len, advice);
    return (int32_t)rc;
#endif
}

/* ============================================================================
 *  fdatasync(int fd)
 *
 *  POSIX: fdatasync(2) flushes data (but not necessarily inode metadata)
 *  to disk. Windows: _commit(fd) is the C runtime's equivalent — it
 *  calls FlushFileBuffers on the underlying handle, which flushes both
 *  data and metadata to stable storage (a strictly stronger guarantee
 *  than fdatasync, still valid because the caller only ever needs "the
 *  data is on disk").
 * ========================================================================== */

void __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_fdatasync__I_V(int32_t fd) {
#if defined(_WIN32)
    if (_commit(fd) < 0) {
        __jnative_throw_exception(NULL);
    }
#else
    if (fdatasync(fd) < 0) {
        __jnative_throw_exception(NULL);
    }
#endif
}

/* ============================================================================
 *  copy_file_range(int src, long srcPos, int dst, long dstPos, long nbytes)
 *
 *  POSIX: copy_file_range(2) copies a range of bytes between two file
 *  descriptors without bouncing through a userspace buffer. Fallback
 *  when the syscall is unavailable (ENOSYS): a pread/pwrite loop.
 *
 *  Windows: no direct equivalent. Implemented as a pread/pwrite loop
 *  using _lseeki64 to position both descriptors before each chunk.
 *  This is the same fallback the JDK uses on Linux kernels older than
 *  4.5 and has exactly the same observable behaviour: byte-identical
 *  copy, both positions advanced by the number of bytes copied.
 * ========================================================================== */

void __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_copy_file_range__IJIJJ_V(
        int32_t src, int64_t srcPos, int32_t dst, int64_t dstPos, int64_t nbytes)
{
    if (nbytes <= 0) {
        return;
    }

#if defined(_WIN32)
    /*
     * Windows: manual pread/pwrite loop. The buffer size is a
     * performance choice, not a semantic one — any chunk size produces
     * the same result. 64 KiB matches the size the JDK's own fallback
     * uses in sun.nio.ch.FileChannelImpl.transferFromArbitraryChannel.
     */
    char buf[65536];
    int64_t remaining = nbytes;
    int64_t srcoff    = srcPos;
    int64_t dstoff    = dstPos;

    while (remaining > 0) {
        size_t chunk = (remaining < (int64_t)sizeof(buf))
            ? (size_t)remaining : sizeof(buf);

        if (_lseeki64(src, srcoff, SEEK_SET) < 0) {
            __jnative_throw_exception(NULL);
        }
        int nread = _read(src, buf, (unsigned int)chunk);
        if (nread < 0) {
            __jnative_throw_exception(NULL);
        }
        if (nread == 0) {
            /* Short source — copy as much as was available. */
            break;
        }

        if (_lseeki64(dst, dstoff, SEEK_SET) < 0) {
            __jnative_throw_exception(NULL);
        }
        int nwrote = _write(dst, buf, (unsigned int)nread);
        if (nwrote != nread) {
            __jnative_throw_exception(NULL);
        }

        remaining -= nread;
        srcoff    += nread;
        dstoff    += nread;
    }
#else
    /*
     * POSIX: real syscall with ENOSYS fallback for kernels that predate
     * copy_file_range (older than 4.5). The fallback is invoked only
     * when the syscall is genuinely missing, never when it returns a
     * partial copy or an EINTR.
     */
    off_t srcoff = (off_t)srcPos;
    off_t dstoff = (off_t)dstPos;
    int64_t remaining = nbytes;

    while (remaining > 0) {
        ssize_t n = copy_file_range(src, &srcoff, dst, &dstoff,
                                    (size_t)remaining, 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == ENOSYS) {
                /* Kernel is too old for copy_file_range; fall through to
                 * the pread/pwrite loop below. */
                goto fallback_loop;
            }
            __jnative_throw_exception(NULL);
        }
        if (n == 0) break;
        remaining -= n;
    }
    return;

fallback_loop: {
        char buf[65536];
        while (remaining > 0) {
            size_t chunk = (remaining < (int64_t)sizeof(buf))
                ? (size_t)remaining : sizeof(buf);

            ssize_t nr = pread(src, buf, chunk, srcoff);
            if (nr < 0) {
                if (errno == EINTR) continue;
                __jnative_throw_exception(NULL);
            }
            if (nr == 0) break;

            ssize_t total_written = 0;
            while (total_written < nr) {
                ssize_t nw = pwrite(dst, buf + total_written,
                                    (size_t)(nr - total_written),
                                    dstoff + total_written);
                if (nw < 0) {
                    if (errno == EINTR) continue;
                    __jnative_throw_exception(NULL);
                }
                total_written += nw;
            }

            remaining -= nr;
            srcoff    += nr;
            dstoff    += nr;
        }
    }
#endif
}
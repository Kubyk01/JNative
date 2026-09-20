#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>

#if defined(__linux__)
#  include <linux/version.h>
#  if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 5, 0)
#    define JNATIVE_HAVE_COPY_FILE_RANGE 1
#  endif
#endif

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);

/*
 * sun.nio.fs.LinuxNativeDispatcher
 *
 * The class contributes two natives on top of what UnixNativeDispatcher
 * already provides:
 *
 *   init()                    -- called once from <clinit>
 *   posix_fadvise(int fd, long offset, long len, int advice)
 *   directCopy0(int src, int dst, long count)
 *
 * Both of the latter are Linux-specific optimisations that the JDK uses
 * to make FileChannel operations faster on Linux without changing their
 * observable semantics.
 */

/* -------------------------------------------------------------------------
 * static native void init();
 *
 * Called from LinuxNativeDispatcher.<clinit>. In HotSpot this hook is
 * used to probe for the availability of various Linux-specific system
 * calls (copy_file_range, sync_file_range, …) and cache the results in
 * static Java fields. This runtime performs the same probes lazily inside
 * the functions that need them, so <clinit> has nothing to install. The
 * symbol exists because the class's static initializer emits a native
 * call to it.
 * ----------------------------------------------------------------------- */
void __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_init___V(void) {
}

/* -------------------------------------------------------------------------
 * static native int posix_fadvise(int fd, long offset, long len, int advice);
 *
 * Wrapper around the POSIX posix_fadvise(2) system call, which lets the
 * caller tell the kernel how a range of a file is about to be accessed
 * so the page cache and readahead heuristics can be tuned accordingly.
 * The JDK uses it in three places:
 *
 *   - FileChannel.transferFrom/transferTo, to mark the source region as
 *     "will be read sequentially" (POSIX_FADV_SEQUENTIAL) or "will be
 *     read once, do not cache" (POSIX_FADV_NOREUSE) after a sendfile
 *     transfer;
 *
 *   - the copy paths in LinuxFileSystem, to mark the source region as
 *     "already consumed, free the pages" (POSIX_FADV_DONTNEED) after a
 *     direct or buffered copy completes.
 *
 * The return value is the errno on failure and 0 on success — POSIX
 * specifies that posix_fadvise returns an error code directly rather
 * than setting errno, so the JDK's own implementation reads the value
 * and does not consult errno at all. Returning the raw result matches
 * that convention. Java callers generally ignore the return value:
 * fadvise is an advisory hint, and failing to give the kernel a hint is
 * never a reason to abort an I/O operation.
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_posix_fadvise__IJJI_I(
        int32_t fd, int64_t offset, int64_t len, int32_t advice)
{
    if (fd < 0) {
        return EINVAL;
    }
    return (int32_t)posix_fadvise((int)fd, (off_t)offset, (off_t)len,
                                  (int)advice);
}

/* -------------------------------------------------------------------------
 * static native int directCopy0(int src, int dst, long count);
 *
 * Copies `count` bytes from file descriptor `src` to file descriptor
 * `dst` inside the kernel, without ever bringing the data into user
 * space. On Linux this is done with copy_file_range(2), which since
 * kernel 5.3 can even perform the copy across filesystems via the
 * filesystem's own clone/remap hooks, and which is at minimum a
 * zero-copy in-kernel memcpy on any kernel that supports it.
 *
 * Return value:
 *
 *    > 0 : number of bytes actually copied; the Java caller loops to
 *          consume the remainder of `count`
 *    = 0 : the source is at EOF, no bytes were copied
 *    < 0 : failure, negated errno so the Java caller can distinguish
 *          ENOSYS (kernel too old, or filesystem refuses the call) and
 *          EXDEV (cross-filesystem copy on a kernel that does not
 *          support the cross-fs clone path) from genuine I/O errors.
 *
 * The negative-errno convention is what the JDK's own
 * LinuxNativeDispatcher.directCopy0 uses: the caller inspects the sign
 * of the result and, on certain negative values, falls back to a
 * buffered copy through user space. Returning the raw errno (positive)
 * would collide with the "bytes copied" interpretation, which is why the
 * sign convention matters here and not in posix_fadvise above.
 * ----------------------------------------------------------------------- */
#define JNATIVE_DIRECT_COPY_SRC_OFFSET 0
#define JNATIVE_DIRECT_COPY_DST_OFFSET 0

int32_t __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_directCopy0__IIJ_I(
        int32_t src, int32_t dst, int64_t count)
{
    if (src < 0 || dst < 0) {
        return -EBADF;
    }
    if (count <= 0) {
        return 0;
    }

#if defined(JNATIVE_HAVE_COPY_FILE_RANGE)
    /*
     * copy_file_range(2) takes in/out position pointers. Passing NULL for
     * both means "use and advance the file's current offset", which is
     * exactly the semantics the Java caller expects: FileChannel's
     * direct-copy path has already done whatever seeking it needs, and
     * the kernel-side copy advances both offsets in lockstep.
     *
     * EINTR is retried transparently. Any other error is reported as a
     * negated errno so the Java side can pick a strategy: ENOSYS / EXDEV
     * / EINVAL trigger a fallback to the buffered path, everything else
     * is rethrown as an IOException.
     */
    ssize_t n;
    do {
        n = copy_file_range((int)src, NULL, (int)dst, NULL,
                            (size_t)count, 0);
    } while (n < 0 && errno == EINTR);

    if (n < 0) {
        return -(int32_t)errno;
    }
    return (int32_t)n;
#else
    /*
     * The build environment's headers do not declare copy_file_range(2).
     * That can happen when the toolchain is targeted at an older libc
     * than the one the binary will eventually run against. The truthful
     * answer for such a build is the same thing the kernel would say on
     * a kernel that does not implement the syscall: ENOSYS. The Java
     * caller's fallback logic handles it identically to the runtime
     * case, so there is no behavioural difference from a build that
     * declares the constant and probes the kernel.
     */
    (void)src; (void)dst; (void)count;
    return -ENOSYS;
#endif
}
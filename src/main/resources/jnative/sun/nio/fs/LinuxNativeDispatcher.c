#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <errno.h>

#if defined(_WIN32)
#  include <windows.h>
#else
#  include <unistd.h>
#  include <fcntl.h>
#  include <sys/sendfile.h>
#endif

#include "jnative_runtime.h"

#define LINUX_ENOTSUP 95

#if defined(_WIN32)

extern HANDLE jn_win_fd_to_handle(int fd);

int32_t __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_posix_fadvise__IJJI_I(
        int32_t fd, int64_t offset, int64_t len, int32_t advice)
{
    (void)fd; (void)offset; (void)len; (void)advice;
    /* POSIX_FADV_* are pure read-ahead hints and have no Windows
     * analogue. Returning 0 (success) matches the contract "the caller
     * may proceed whether or not the hint was applied". */
    return 0;
}

int32_t __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_directCopy0__IIJ_I(
        int32_t src_fd, int32_t dst_fd, int64_t address)
{
    HANDLE hs = jn_win_fd_to_handle(src_fd);
    HANDLE hd = jn_win_fd_to_handle(dst_fd);
    if (hs == INVALID_HANDLE_VALUE || hd == INVALID_HANDLE_VALUE)
        return -9; /* -EBADF */

    char   stackbuf[65536];
    char*  buf     = (address != 0) ? (char*)(intptr_t)address : stackbuf;
    size_t bufsize = sizeof(stackbuf);
    int64_t total  = 0;

    for (;;) {
        DWORD got = 0;
        if (!ReadFile(hs, buf, (DWORD)bufsize, &got, NULL)) {
            DWORD err = GetLastError();
            if (err == ERROR_HANDLE_EOF) break;
            return -5;
        }
        if (got == 0) break;
        DWORD written = 0;
        if (!WriteFile(hd, buf, got, &written, NULL)) return -5;
        total += written;
    }
    return (int32_t)total;
}

#else  /* POSIX */

int32_t __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_posix_fadvise__IJJI_I(
        int32_t fd, int64_t offset, int64_t len, int32_t advice)
{
    int r = posix_fadvise(fd, (off_t)offset, (off_t)len, advice);
    return r; /* posix_fadvise returns the errno value directly */
}

int32_t __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_directCopy0__IIJ_I(
        int32_t src_fd, int32_t dst_fd, int64_t address)
{
#if defined(SYS_copy_file_range)
    int64_t total = 0;
    for (;;) {
        ssize_t r = syscall(SYS_copy_file_range,
                            src_fd, NULL, dst_fd, NULL,
                            (size_t)(64 * 1024 * 1024), 0);
        if (r < 0) {
            if (errno == EINTR) continue;
            if (total > 0 && errno == EXDEV) break;
            return -errno;
        }
        if (r == 0) break;
        total += r;
    }
    return (int32_t)total;
#else
    char   stackbuf[65536];
    char*  buf     = (address != 0) ? (char*)(intptr_t)address : stackbuf;
    size_t bufsize = sizeof(stackbuf);
    int64_t total  = 0;

    for (;;) {
        ssize_t n;
        do { n = read(src_fd, buf, bufsize); }
        while (n < 0 && errno == EINTR);
        if (n < 0) return -errno;
        if (n == 0) break;
        ssize_t written = 0;
        while (written < n) {
            ssize_t w;
            do { w = write(dst_fd, buf + written, (size_t)(n - written)); }
            while (w < 0 && errno == EINTR);
            if (w < 0) return -errno;
            written += w;
        }
        total += n;
    }
    return (int32_t)total;
#endif
}

#endif /* _WIN32 */
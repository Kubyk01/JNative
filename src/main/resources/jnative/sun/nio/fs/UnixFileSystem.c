#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>

#if defined(_WIN32)
#  include <io.h>
#else
#  include <unistd.h>
#  include <sys/types.h>
#endif

#include "jnative_runtime.h"

#define JN_COPY_BUFSZ 8192

/*
 * Portable pread / pwrite.
 *
 * POSIX: native pread(2) / pwrite(2), which perform the seek and the
 * read/write atomically in one syscall.
 *
 * Windows: the CRT has no pread / pwrite. The closest equivalent is
 * _lseeki64 followed by _read / _write. The two-call form is not atomic,
 * but this file's only caller (copyFile) holds the source and
 * destination descriptors privately, so no other thread can race with
 * the seek. Any future caller that needs atomicity must use ReadFile /
 * WriteFile with an OVERLAPPED offset.
 */
#if defined(_WIN32)
static int64_t jn_pread(int fd, void* buf, size_t n, int64_t off) {
    if (_lseeki64(fd, off, SEEK_SET) < 0) return -1;
    return _read(fd, buf, (unsigned int)n);
}
static int64_t jn_pwrite(int fd, const void* buf, size_t n, int64_t off) {
    if (_lseeki64(fd, off, SEEK_SET) < 0) return -1;
    return _write(fd, buf, (unsigned int)n);
}
#else
static int64_t jn_pread(int fd, void* buf, size_t n, int64_t off) {
    return (int64_t)pread(fd, buf, n, (off_t)off);
}
static int64_t jn_pwrite(int fd, const void* buf, size_t n, int64_t off) {
    return (int64_t)pwrite(fd, buf, n, (off_t)off);
}
#endif

__attribute__((noreturn))
static void jn_throw_io(const char* msg) {
    void* exc = __jnative_construct_exception(
        "vtable_java_io_IOException", msg ? msg : "I/O error");
    __jnative_throw_exception(exc);
}

/*
 * copyFile(int src, int dst, long size)
 *
 * Copies exactly `size` bytes from the source file descriptor to the
 * destination file descriptor, using positional reads/writes so neither
 * descriptor's file pointer is disturbed. Both descriptors must already
 * be open and (on Windows) must be binary-mode.
 */
void __jnative_fn_sun_nio_fs_UnixFileSystem_copyFile__IIJ_V(
    int32_t src, int32_t dst, int64_t size)
{
    char buf[JN_COPY_BUFSZ];
    int64_t pos = 0;
    int64_t remaining = size;

    while (remaining > 0) {
        size_t to_read = (remaining < (int64_t)JN_COPY_BUFSZ)
            ? (size_t)remaining
            : (size_t)JN_COPY_BUFSZ;

        int64_t n = jn_pread(src, buf, to_read, pos);
        if (n < 0) jn_throw_io("pread failed");
        if (n == 0) break;

        int64_t written = 0;
        while (written < n) {
            int64_t w = jn_pwrite(dst, buf + written,
                                  (size_t)(n - written), pos + written);
            if (w < 0) jn_throw_io("pwrite failed");
            written += w;
        }

        remaining -= n;
        pos += n;
    }
}
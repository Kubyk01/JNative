#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <errno.h>
#include <unistd.h>
#include <sys/types.h>

#include "jnative_runtime.h"

/*
 * sun.nio.fs.UnixFileSystem — the last-resort file-copy native.
 *
 * The class inherits almost everything it needs from UnixFileSystem's
 * Java-side implementation, but the buffered-copy fallback that
 * LinuxFileSystem.copyFile reaches when the kernel's copy_file_range(2)
 * path is unavailable is implemented natively here. It is a plain
 * pread(2)/write(2) loop that reads the source in fixed-size chunks and
 * writes each chunk to the destination.
 *
 * The method is declared on UnixFileSystem itself rather than on a
 * per-platform subclass because the buffered fallback is identical on
 * every Unix the JDK supports: the platform-specific parts of copy are
 * the kernel-assisted fast paths, and this one is deliberately dumb.
 */

/*
 * static native void bufferedCopy0(int src, int dst, long position,
 *                                  int size, long address)
 *     throws IOException;
 *
 * Copies the source file descriptor `src` to the destination file
 * descriptor `dst`, beginning at byte offset `position` in the source,
 * and continuing until the source reaches end-of-file. The intermediate
 * buffer is a native allocation whose address is `address` and whose
 * capacity is `size` bytes; both are provided by the Java caller (which
 * typically allocates a direct ByteBuffer for the purpose and passes
 * its base address and capacity).
 *
 * Why a pread/write loop rather than read/write on a seeked fd:
 *
 *   - pread(2) reads from an explicit offset without touching the
 *     file's stored position. That means the source descriptor's
 *     position is unchanged by this call, which is important because
 *     the Java-side caller may be sharing the descriptor with other
 *     operations that track position through lseek;
 *
 *   - the destination descriptor's own position is not touched either,
 *     because plain write(2) is being used and the caller is expected
 *     to have lseek'd dst to wherever the new data should land. This is
 *     the same contract the JDK's own buffered copy honours: the caller
 *     positions the destination before invoking the native, and the
 *     native is free to write chunks without re-seeking.
 *
 * EINTR is retried transparently at both the read and the write stage,
 * so a signal delivered mid-copy does not surface as a spurious
 * IOException. Any other failure — EBADF, EIO, ENOSPC, a bad user
 * buffer — is routed through the generic throw helper and reaches the
 * Java caller as an IOException.
 *
 * A partial write that returns 0 for a non-zero count is treated as
 * failure: for a regular file, write(2) returning 0 is not a legitimate
 * outcome, and looping on it would hang the copy forever.
 */
void __jnative_fn_sun_nio_fs_UnixFileSystem_bufferedCopy0__IIJIJ_V(
        int32_t src, int32_t dst, int64_t position, int32_t size, int64_t address)
{
    if (src < 0 || dst < 0 || address == 0 || size <= 0 || position < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    char* buf = (char*)(intptr_t)address;
    off_t pos = (off_t)position;

    for (;;) {
        ssize_t n;
        do {
            n = pread(src, buf, (size_t)size, pos);
        } while (n < 0 && errno == EINTR);

        if (n < 0) {
            __jnative_throw_exception(NULL);
            return;
        }
        if (n == 0) {
            /* End of source file. Nothing more to copy. */
            return;
        }

        ssize_t written = 0;
        while (written < n) {
            ssize_t w;
            do {
                w = write(dst, buf + written, (size_t)(n - written));
            } while (w < 0 && errno == EINTR);

            if (w < 0) {
                __jnative_throw_exception(NULL);
                return;
            }
            if (w == 0) {
                /*
                 * write(2) returning 0 for a non-zero count on a
                 * regular file is not a legitimate outcome; treat it
                 * as failure rather than looping forever.
                 */
                __jnative_throw_exception(NULL);
                return;
            }
            written += w;
        }

        pos += (off_t)n;
    }
}
#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <fcntl.h>
#include <errno.h>
#include <limits.h>
#include <sys/types.h>
#include <sys/stat.h>

#if defined(_WIN32)
#  include <io.h>
#else
#  include <unistd.h>
#endif

#include "jnative_runtime.h"

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

/*
 * ============================================================================
 *  Platform-specific C-runtime aliases.
 * ============================================================================
 *
 * The MinGW C runtime provides the CRT file primitives under names that
 * are prefixed with an underscore: _open, _write, _lseeki64, _close.
 * The POSIX names open, write, lseek, close exist in <io.h> as
 * deprecated aliases on most configurations, but:
 *
 *   - not on every UCRT configuration this runtime targets, and
 *   - lseek is 32-bit on Windows regardless of the name, so using it to
 *     answer "where is the write pointer relative to SEEK_END" truncates
 *     at 2 GiB and produces a nonsensical position for any file larger
 *     than that.
 *
 * The correct fix is to route every CRT file operation through the
 * platform-correct primitive. The macros below are the single source
 * of truth for that routing; every call site in this file uses them.
 *
 *   POSIX   : write    = write,    lseek = lseek   (64-bit off_t on LP64)
 *   Windows : write    = _write,   lseek = _lseeki64
 *
 * The open() call has to be a macro too, because the flags differ
 * between the two platforms (O_CLOEXEC vs _O_NOINHERIT) and the mode
 * argument is `mode_t` on POSIX but a small set of _S_I* constants on
 * Windows.
 * ========================================================================== */
#if defined(_WIN32)
#  define fos_write  _write
#  define fos_lseek  _lseeki64
#  define fos_close  _close
#else
#  define fos_write  write
#  define fos_lseek  lseek
#  define fos_close  close
#endif

/*
 * Object layout used by this runtime for java.io.FileOutputStream:
 *
 *   [ 8-byte vtable ][ FileDescriptor fd ][ String path ][ boolean append ]
 *
 * FileDescriptor layout (see jnative/io/FileDescriptor.c):
 *
 *   [ 8-byte vtable ][ int32 fd ][ long handle ]
 *
 * Consequently:
 *   this     + 8 -> FileDescriptor object pointer
 *   fd_obj   + 8 -> raw kernel file descriptor (int32_t)
 */
#define FOS_FD_OFFSET     8
#define FOS_PATH_OFFSET   16
#define FOS_APPEND_OFFSET 24

static inline void* fos_fd_object(void* this_fos) {
    return *(void**)((char*)this_fos + FOS_FD_OFFSET);
}

static inline int32_t fos_raw_fd(void* this_fos) {
    void* fd_obj = fos_fd_object(this_fos);
    if (fd_obj == NULL) return -1;
    return *(int32_t*)((char*)fd_obj + FD_OFFSET);
}

__attribute__((noreturn))
static void fos_throw_fnf(const char* path) {
    void* exc = __jnative_construct_exception(
        "vtable_java_io_FileNotFoundException", path ? path : "");
    __jnative_throw_exception(exc);
}

__attribute__((noreturn))
static void fos_throw_io(const char* msg) {
    void* exc = __jnative_construct_exception(
        "vtable_java_io_IOException", msg ? msg : "I/O error");
    __jnative_throw_exception(exc);
}

/*
 * private static native void initIDs();
 *
 * Called from the static initializer of java.io.FileOutputStream to cache
 * the JNI field IDs of the class's instance fields. This runtime accesses
 * every field through its LLVM-computed byte offset and never consults JNI
 * field IDs, so there is nothing to cache. The symbol must exist because
 * FileOutputStream.<clinit> emits a native call to it.
 */
void __jnative_fn_java_io_FileOutputStream_initIDs___V(void) {
}

void __jnative_fn_java_io_FileOutputStream_open0__Ljava_lang_String_Z_V(
        void* this_fos, void* name_str, int32_t append)
{
    if (this_fos == NULL || name_str == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    void* fd_obj = fos_fd_object(this_fos);
    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    char path_buf[PATH_MAX];
    int32_t path_len = __jnative_read_string_into(name_str, path_buf,
                                                  (int32_t)sizeof(path_buf));
    if (path_len < 0) fos_throw_fnf(NULL);

    int fd;

#if defined(_WIN32)
    /*
     * Windows: _open with the CRT equivalents of the POSIX flags.
     *
     *   _O_WRONLY   -> write-only access
     *   _O_CREAT    -> create if missing
     *   _O_TRUNC    -> truncate to zero (when !append)
     *   _O_APPEND   -> every write is appended to the end of the file
     *                  (when append)
     *   _O_NOINHERIT-> the fd is not inherited by child processes,
     *                  matching POSIX O_CLOEXEC
     *   _O_BINARY   -> no LF <-> CRLF translation; matches POSIX open()
     *
     * The mode argument is a bitmask of _S_IREAD / _S_IWRITE.
     *
     * _O_NOINHERIT is the exact behavioural equivalent of O_CLOEXEC:
     * both ensure that the descriptor is closed in the child across
     * exec / CreateProcess. The previous revision called `open` with
     * `O_CLOEXEC` and produced:
     *
     *     error: use of undeclared identifier 'O_CLOEXEC'
     *
     * because neither the flag nor the <fcntl.h> path that defines it
     * exists on MinGW.
     */
    int flags = _O_WRONLY | _O_CREAT | _O_NOINHERIT | _O_BINARY;
    if (append) flags |= _O_APPEND;
    else        flags |= _O_TRUNC;

    fd = _open(path_buf, flags, _S_IREAD | _S_IWRITE);
#else
    /*
     * POSIX: unchanged from the previous revision. O_CLOEXEC keeps
     * the descriptor private across fork+exec, which is exactly what
     * the JDK's own FileOutputStream_md.c guarantees.
     */
    int flags = O_WRONLY | O_CREAT | O_CLOEXEC;
    if (append) flags |= O_APPEND;
    else        flags |= O_TRUNC;

    fd = open(path_buf, flags, (mode_t)0666);
#endif

    if (fd < 0) fos_throw_fnf(path_buf);

    *(int32_t*)((char*)fd_obj + FD_OFFSET) = fd;
}

/*
 * public native void write(int b, boolean append) throws IOException;
 *
 * Writes a single byte. When `append` is true the write is positioned
 * at the end of the file before the byte is committed, matching the
 * reference implementation's per-write seek. On Windows fos_lseek is
 * _lseeki64, which handles files larger than 2 GiB correctly; the
 * POSIX fos_lseek is the native lseek, which is already 64-bit on
 * every LP64 target this runtime supports.
 */
void __jnative_fn_java_io_FileOutputStream_write__IZ_V(
        void* this_fos, int32_t b, int32_t append)
{
    if (this_fos == NULL) __jnative_throw_null_pointer_exception();

    int32_t fd = fos_raw_fd(this_fos);
    if (fd < 0) fos_throw_io("stream closed");

    if (append) {
        if (fos_lseek(fd, 0, SEEK_END) < 0) fos_throw_io("lseek failed");
    }

    uint8_t byte = (uint8_t)b;
    int64_t n;
    do {
        n = fos_write(fd, &byte, 1);
    } while (n < 0 && errno == EINTR);

    if (n < 0) fos_throw_io("write failed");
}

/*
 * public native void writeBytes(byte[] b, int off, int len,
 *                                boolean append) throws IOException;
 *
 * Bulk write. The loop has to tolerate short writes from the kernel
 * (a partial write of a multi-megabyte buffer is normal for a slow
 * disk, a full pipe, or a signal), so the byte count is decremented
 * on each successful iteration until the entire requested range has
 * been committed.
 *
 * The semantics of the short-write loop are identical on both
 * platforms:
 *
 *   POSIX   : write(2) returns the number of bytes actually written,
 *             which may be less than the requested count.
 *   Windows : _write returns the same way.
 */
void __jnative_fn_java_io_FileOutputStream_writeBytes___BIIZ_V(
        void* this_fos, void* b, int32_t off, int32_t len, int32_t append)
{
    if (this_fos == NULL || b == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    if (off < 0 || len < 0) fos_throw_io("invalid offset/length");
    if (len == 0) return;

    int32_t fd = fos_raw_fd(this_fos);
    if (fd < 0) fos_throw_io("stream closed");

    if (append) {
        if (fos_lseek(fd, 0, SEEK_END) < 0) fos_throw_io("lseek failed");
    }

    const uint8_t* src = (const uint8_t*)b + JAVA_ARR_HDR + off;
    int32_t remaining = len;

    while (remaining > 0) {
        int64_t n = fos_write(fd, src, (size_t)remaining);
        if (n < 0) {
            if (errno == EINTR) continue;
            fos_throw_io("write failed");
        }
        if (n == 0) fos_throw_io("write returned 0");
        src += (size_t)n;
        remaining -= (int32_t)n;
    }
}
#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/stat.h>

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);

extern const char* __jnative_read_string_bytes(void* s, int32_t* out_len);

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
 *
 * FileOutputStream's `path` field is a String used only for diagnostic
 * purposes (toString, close-with-error reporting). It is written by the
 * Java-level constructor before any of the natives below run, and none of
 * the write paths read it.
 */
#define FOS_FD_OFFSET           8
#define FOS_PATH_OFFSET         16
#define FOS_APPEND_OFFSET       24

#define FD_RAW_FD_OFFSET        8

/* Java array layout: [ int32 length ][ payload ... ] */
#define JAVA_ARR_HDR 8

static inline void* fos_fd_object(void* this_fos) {
    return *(void**)((char*)this_fos + FOS_FD_OFFSET);
}

static inline int32_t fos_raw_fd(void* this_fos) {
    void* fd_obj = fos_fd_object(this_fos);
    if (fd_obj == NULL) return -1;
    return *(int32_t*)((char*)fd_obj + FD_RAW_FD_OFFSET);
}

/*
 * private static native void initIDs();
 *
 * Called from the static initializer of java.io.FileOutputStream to cache
 * the JNI field IDs of the class's instance fields (fd, path, append, …).
 * This runtime accesses every field through its LLVM-computed byte offset
 * and never consults JNI field IDs, so there is nothing to cache. The
 * symbol must exist because FileOutputStream.<clinit> emits a native call
 * to it.
 */
void __jnative_fn_java_io_FileOutputStream_initIDs___V(void) {
}

void __jnative_fn_java_io_FileOutputStream_open0__Ljava_lang_String_Z_V(
        void* this_fos, void* name_str, int32_t append)
{
    if (this_fos == NULL || name_str == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    void* fd_obj = fos_fd_object(this_fos);
    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    int32_t name_len = 0;
    const char* path = __jnative_read_string_bytes(name_str, &name_len);
    (void)name_len;
    if (path == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    /*
     * Pick the open flags. O_CLOEXEC is added on every path because the
     * descriptor is owned by this stream and must not leak into a child
     * process spawned between the open and the eventual close. The
     * reference JDK's own implementation sets it for the same reason;
     * the flag is a no-op on kernels that do not support it, since the
     * constant expands to 0 in that case and open(2) ignores it.
     */
    int flags = O_WRONLY | O_CREAT | O_CLOEXEC;
    if (append) {
        flags |= O_APPEND;
    } else {
        flags |= O_TRUNC;
    }

    int fd = open(path, flags, (mode_t)0666);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    *(int32_t*)((char*)fd_obj + FD_RAW_FD_OFFSET) = fd;
}

/*
 * private native void write(int b, boolean append) throws IOException;
 *
 * Writes a single byte to the underlying file descriptor. When `append`
 * is true the file position is first advanced to the end of the file, so
 * the byte lands after every previously-written byte regardless of how
 * the descriptor was originally opened. This matches the reference JDK's
 * behaviour in the same situation: the FileDescriptor itself was created
 * with O_APPEND for append-mode streams, but the flag can be observed
 * independently of the open flags and a defensive lseek is cheap.
 *
 * The write is retried on EINTR so a signal does not surface as a
 * spurious IOException to the Java caller.
 */
void __jnative_fn_java_io_FileOutputStream_write__IZ_V(
        void* this_fos, int32_t b, int32_t append)
{
    if (this_fos == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    int32_t fd = fos_raw_fd(this_fos);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    if (append) {
        if (lseek(fd, 0, SEEK_END) < 0) {
            __jnative_throw_exception(NULL);
            return;
        }
    }

    uint8_t byte = (uint8_t)b;
    ssize_t n;
    do {
        n = write(fd, &byte, 1);
    } while (n < 0 && errno == EINTR);

    if (n < 0) {
        __jnative_throw_exception(NULL);
    }
}

/*
 * private native void writeBytes(byte b[], int off, int len, boolean append)
 *     throws IOException;
 *
 * Bulk write into the file. Same append semantics as write(int, boolean):
 * when `append` is true the file position is first moved to the end of the
 * file, so the batch lands after every previously-written byte.
 *
 * The loop is required because POSIX does not guarantee that write(2)
 * consumes the entire buffer in a single call. For regular files this is
 * rare, but signals delivered between the kernel entry and the copy-in can
 * still yield a short write with a positive return value; looping until
 * the entire buffer is drained makes the native's contract match the
 * Java-level OutputStream.write(byte[], int, int) contract, which also
 * promises to write all `len` bytes.
 *
 * EINTR with no bytes transferred is retried; EINTR after a partial
 * transfer simply continues the loop with the remaining bytes. Zero-length
 * writes are treated as no-ops so callers can pass len == 0 without
 * touching the descriptor.
 */
void __jnative_fn_java_io_FileOutputStream_writeBytes___BIIZ_V(
        void* this_fos, void* b, int32_t off, int32_t len, int32_t append)
{
    if (this_fos == NULL || b == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    if (off < 0 || len < 0) {
        __jnative_throw_exception(NULL);
        return;
    }
    if (len == 0) {
        return;
    }

    int32_t fd = fos_raw_fd(this_fos);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    if (append) {
        if (lseek(fd, 0, SEEK_END) < 0) {
            __jnative_throw_exception(NULL);
            return;
        }
    }

    const uint8_t* src = (const uint8_t*)b + JAVA_ARR_HDR + off;
    int32_t remaining = len;

    while (remaining > 0) {
        ssize_t n = write(fd, src, (size_t)remaining);
        if (n < 0) {
            if (errno == EINTR) continue;
            __jnative_throw_exception(NULL);
            return;
        }
        if (n == 0) {
            /* write(2) returning 0 for a non-zero count is not expected for
             * a regular file; treating it as a failure prevents an infinite
             * loop if the descriptor turns out to be unusable. */
            __jnative_throw_exception(NULL);
            return;
        }
        src += (size_t)n;
        remaining -= (int32_t)n;
    }
}
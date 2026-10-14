#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <limits.h>
#include <sys/types.h>
#include <sys/stat.h>

#include "jnative_runtime.h"

#ifndef PATH_MAX
#define PATH_MAX 4096
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

    int flags = O_WRONLY | O_CREAT | O_CLOEXEC;
    if (append) flags |= O_APPEND;
    else        flags |= O_TRUNC;

    int fd = open(path_buf, flags, (mode_t)0666);
    if (fd < 0) fos_throw_fnf(path_buf);

    *(int32_t*)((char*)fd_obj + FD_OFFSET) = fd;
}

void __jnative_fn_java_io_FileOutputStream_write__IZ_V(
        void* this_fos, int32_t b, int32_t append)
{
    if (this_fos == NULL) __jnative_throw_null_pointer_exception();

    int32_t fd = fos_raw_fd(this_fos);
    if (fd < 0) fos_throw_io("stream closed");

    if (append) {
        if (lseek(fd, 0, SEEK_END) < 0) fos_throw_io("lseek failed");
    }

    uint8_t byte = (uint8_t)b;
    ssize_t n;
    do {
        n = write(fd, &byte, 1);
    } while (n < 0 && errno == EINTR);

    if (n < 0) fos_throw_io("write failed");
}

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
        if (lseek(fd, 0, SEEK_END) < 0) fos_throw_io("lseek failed");
    }

    const uint8_t* src = (const uint8_t*)b + JAVA_ARR_HDR + off;
    int32_t remaining = len;

    while (remaining > 0) {
        ssize_t n = write(fd, src, (size_t)remaining);
        if (n < 0) {
            if (errno == EINTR) continue;
            fos_throw_io("write failed");
        }
        if (n == 0) fos_throw_io("write returned 0");
        src += (size_t)n;
        remaining -= (int32_t)n;
    }
}
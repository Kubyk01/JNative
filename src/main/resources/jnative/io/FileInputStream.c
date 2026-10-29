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

#if defined(_WIN32)
#  define fis_read   _read
#  define fis_lseek  _lseeki64
#else
#  define fis_read   read
#  define fis_lseek  lseek
#endif

/*
 * Layout of java.io.FileInputStream used by this runtime:
 *
 *     [ 8-byte vtable ][ FileDescriptor fd ][ String path ]
 *
 * FileDescriptor:
 *
 *     [ 8-byte vtable ][ int32 fd ][ long handle ]
 */
#define FIS_FD_OFFSET   8
#define FIS_PATH_OFFSET 16

static inline void* fis_fd_object(void* this_fis) {
    return *(void**)((char*)this_fis + FIS_FD_OFFSET);
}

static inline int32_t fis_raw_fd(void* this_fis) {
    void* fd_obj = fis_fd_object(this_fis);
    if (fd_obj == NULL) return -1;
    return *(int32_t*)((char*)fd_obj + FD_OFFSET);
}

__attribute__((noreturn))
static void fis_throw_fnf(const char* path) {
    void* exc = __jnative_construct_exception(
        "vtable_java_io_FileNotFoundException", path ? path : "");
    __jnative_throw_exception(exc);
}

__attribute__((noreturn))
static void fis_throw_io(const char* msg) {
    void* exc = __jnative_construct_exception(
        "vtable_java_io_IOException", msg ? msg : "I/O error");
    __jnative_throw_exception(exc);
}

void __jnative_fn_java_io_FileInputStream_initIDs___V(void) {
}

void __jnative_fn_java_io_FileInputStream_open0__Ljava_lang_String__V(
        void* this_fis, void* name_str)
{
    if (this_fis == NULL || name_str == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    void* fd_obj = fis_fd_object(this_fis);
    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    char path_buf[PATH_MAX];
    int32_t path_len = __jnative_read_string_into(name_str, path_buf,
                                                  (int32_t)sizeof(path_buf));
    if (path_len < 0) fis_throw_fnf(NULL);

    int fd;
#if defined(_WIN32)
    fd = _open(path_buf, _O_RDONLY | _O_BINARY | _O_NOINHERIT);
#else
    fd = open(path_buf, O_RDONLY | O_CLOEXEC);
#endif
    if (fd < 0) fis_throw_fnf(path_buf);

    *(int32_t*)((char*)fd_obj + FD_OFFSET) = fd;
}

int32_t __jnative_fn_java_io_FileInputStream_read0___I(void* this_fis) {
    if (this_fis == NULL) __jnative_throw_null_pointer_exception();

    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) fis_throw_io("stream closed");

    uint8_t byte;
    int64_t n;
    do {
        n = fis_read(fd, &byte, 1);
    } while (n < 0 && errno == EINTR);

    if (n < 0) fis_throw_io("read failed");
    if (n == 0) return -1;
    return (int32_t)byte;
}

int32_t __jnative_fn_java_io_FileInputStream_readBytes___BII_I(
        void* this_fis, void* b, int32_t off, int32_t len)
{
    if (this_fis == NULL || b == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    if (off < 0 || len < 0) fis_throw_io("invalid offset/length");
    if (len == 0) return 0;

    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) fis_throw_io("stream closed");

    uint8_t* dst = (uint8_t*)b + JAVA_ARR_HDR + off;
    int64_t n;
    do {
        n = fis_read(fd, dst, (size_t)len);
    } while (n < 0 && errno == EINTR);

    if (n < 0) fis_throw_io("read failed");
    if (n == 0) return -1;
    return (int32_t)n;
}

int64_t __jnative_fn_java_io_FileInputStream_skip0__J_J(
        void* this_fis, int64_t n)
{
    if (this_fis == NULL) __jnative_throw_null_pointer_exception();
    if (n <= 0) return 0;

    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) fis_throw_io("stream closed");

    int64_t cur = fis_lseek(fd, 0, SEEK_CUR);
    if (cur < 0) fis_throw_io("lseek failed");

    int64_t end = fis_lseek(fd, 0, SEEK_END);
    if (end < 0) fis_throw_io("lseek failed");

    int64_t target = cur + n;
    if (target > end) target = end;

    int64_t actual = fis_lseek(fd, target, SEEK_SET);
    if (actual < 0) fis_throw_io("lseek failed");

    return actual - cur;
}

int32_t __jnative_fn_java_io_FileInputStream_available0___I(void* this_fis) {
    if (this_fis == NULL) __jnative_throw_null_pointer_exception();

    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) fis_throw_io("stream closed");

    int64_t cur = fis_lseek(fd, 0, SEEK_CUR);
    if (cur < 0) fis_throw_io("lseek failed");

    int64_t end = fis_lseek(fd, 0, SEEK_END);
    if (end < 0) fis_throw_io("lseek failed");

    if (fis_lseek(fd, cur, SEEK_SET) < 0) fis_throw_io("lseek failed");

    int64_t avail = end - cur;
    if (avail <= 0) return 0;
    if (avail > INT32_MAX) return INT32_MAX;
    return (int32_t)avail;
}

/* -------------------------------------------------------------------------
 * length0 / position0 — added for JDKs whose FileInputStream exposes
 * the file's size and current read position directly through native
 * methods. Both are pure fd queries and require no state beyond the
 * raw FileDescriptor already stored at offset 8.
 * ----------------------------------------------------------------------- */

int64_t __jnative_fn_java_io_FileInputStream_length0___J(void* this_fis) {
    if (this_fis == NULL) __jnative_throw_null_pointer_exception();

    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) fis_throw_io("stream closed");

    struct stat st;
    if (fstat(fd, &st) < 0) fis_throw_io("fstat failed");
    return (int64_t)st.st_size;
}

int64_t __jnative_fn_java_io_FileInputStream_position0___J(void* this_fis) {
    if (this_fis == NULL) __jnative_throw_null_pointer_exception();

    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) fis_throw_io("stream closed");

    int64_t pos = fis_lseek(fd, 0, SEEK_CUR);
    if (pos < 0) fis_throw_io("lseek failed");
    return pos;
}
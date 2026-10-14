#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
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
 * Object layout used by this runtime for java.io.RandomAccessFile:
 *
 *   [ 8-byte vtable ][ FileDescriptor fd ][ long position ][ boolean rw ]
 *
 * FileDescriptor layout (see jnative/io/FileDescriptor.c):
 *
 *   [ 8-byte vtable ][ int32 fd ][ long handle ]
 *
 * Consequently:
 *   this        + 8  -> FileDescriptor object pointer
 *   FileDesc    + 8  -> raw kernel file descriptor (int32_t)
 *   this        + 16 -> long position  (kept in sync with lseek)
 *   this        + 24 -> boolean rw
 */
#define RAF_FD_OFFSET       8
#define RAF_POSITION_OFFSET 16
#define RAF_RW_OFFSET       24

static inline void* raf_fd_object(void* this_file) {
    return *(void**)((char*)this_file + RAF_FD_OFFSET);
}

static inline int32_t raf_raw_fd(void* this_file) {
    void* fd_obj = raf_fd_object(this_file);
    if (fd_obj == NULL) return -1;
    return *(int32_t*)((char*)fd_obj + FD_OFFSET);
}

__attribute__((noreturn))
static void raf_throw_fnf(const char* path) {
    void* exc = __jnative_construct_exception(
        "vtable_java_io_FileNotFoundException", path ? path : "");
    __jnative_throw_exception(exc);
}

__attribute__((noreturn))
static void raf_throw_io(const char* msg) {
    void* exc = __jnative_construct_exception(
        "vtable_java_io_IOException", msg ? msg : "I/O error");
    __jnative_throw_exception(exc);
}

/*
 * private native void open0(String name, int mode) throws FileNotFoundException;
 *
 * The Java-level mode constants are:
 *
 *     1  = O_RDONLY                    ("r")
 *     2  = O_RDWR                      ("rw")
 *     6  = O_RDWR | O_SYNC             ("rws")
 *     10 = O_RDWR | O_DSYNC            ("rwd")
 *
 * Those numeric values are the fixed Java-side encoding the JDK's own C
 * implementation uses, not the platform's O_* constants.
 */
void __jnative_fn_java_io_RandomAccessFile_open0__Ljava_lang_String_I_V(
        void* this_file, void* name_str, int32_t mode)
{
    if (this_file == NULL || name_str == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    void* fd_obj = raf_fd_object(this_file);
    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    int flags;
    switch (mode) {
        case 1:  flags = O_RDONLY;         break;
        case 2:  flags = O_RDWR;           break;
        case 6:  flags = O_RDWR | O_SYNC;  break;
        case 10: flags = O_RDWR | O_DSYNC; break;
        default: raf_throw_io("invalid mode");
    }

    char path_buf[PATH_MAX];
    int32_t path_len = __jnative_read_string_into(name_str, path_buf,
                                                  (int32_t)sizeof(path_buf));
    if (path_len < 0) raf_throw_fnf(NULL);

    int fd = open(path_buf, flags);
    if (fd < 0) raf_throw_fnf(path_buf);

    *(int32_t*)((char*)fd_obj + FD_OFFSET) = fd;
}

int64_t __jnative_fn_java_io_RandomAccessFile_length0___J(void* this_file) {
    if (this_file == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = raf_raw_fd(this_file);
    if (fd < 0) raf_throw_io("file closed");
    struct stat st;
    if (fstat(fd, &st) < 0) raf_throw_io("fstat failed");
    return (int64_t)st.st_size;
}

int64_t __jnative_fn_java_io_RandomAccessFile_getFilePointer___J(void* this_file) {
    if (this_file == NULL) __jnative_throw_null_pointer_exception();
    return *(int64_t*)((char*)this_file + RAF_POSITION_OFFSET);
}

void __jnative_fn_java_io_RandomAccessFile_seek0__J_V(
        void* this_file, int64_t pos)
{
    if (this_file == NULL) __jnative_throw_null_pointer_exception();
    if (pos < 0) raf_throw_io("negative seek");

    int32_t fd = raf_raw_fd(this_file);
    if (fd >= 0) {
        if (lseek(fd, (off_t)pos, SEEK_SET) < 0) raf_throw_io("lseek failed");
    }
    *(int64_t*)((char*)this_file + RAF_POSITION_OFFSET) = pos;
}

int32_t __jnative_fn_java_io_RandomAccessFile_read0___I(void* this_file) {
    if (this_file == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = raf_raw_fd(this_file);
    if (fd < 0) raf_throw_io("file closed");

    uint8_t byte;
    ssize_t n;
    do {
        n = read(fd, &byte, 1);
    } while (n < 0 && errno == EINTR);

    if (n < 0) raf_throw_io("read failed");
    if (n == 0) return -1;

    *(int64_t*)((char*)this_file + RAF_POSITION_OFFSET) += 1;
    return (int32_t)byte;
}

int32_t __jnative_fn_java_io_RandomAccessFile_readBytes0___BII_I(
        void* this_file, void* b, int32_t off, int32_t len)
{
    if (this_file == NULL || b == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    if (off < 0 || len < 0) raf_throw_io("invalid offset/length");

    int32_t fd = raf_raw_fd(this_file);
    if (fd < 0) raf_throw_io("file closed");

    uint8_t* dst = (uint8_t*)b + JAVA_ARR_HDR + off;
    ssize_t n;
    do {
        n = read(fd, dst, (size_t)len);
    } while (n < 0 && errno == EINTR);

    if (n < 0) raf_throw_io("read failed");
    if (n == 0) return 0;

    *(int64_t*)((char*)this_file + RAF_POSITION_OFFSET) += (int64_t)n;
    return (int32_t)n;
}

void __jnative_fn_java_io_RandomAccessFile_writeBytes0___BII_V(
        void* this_file, void* b, int32_t off, int32_t len)
{
    if (this_file == NULL || b == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    if (off < 0 || len < 0) raf_throw_io("invalid offset/length");
    if (len == 0) return;

    int32_t fd = raf_raw_fd(this_file);
    if (fd < 0) raf_throw_io("file closed");

    const uint8_t* src = (const uint8_t*)b + JAVA_ARR_HDR + off;
    int32_t remaining = len;
    int64_t written_total = 0;

    while (remaining > 0) {
        ssize_t n = write(fd, src, (size_t)remaining);
        if (n < 0) {
            if (errno == EINTR) continue;
            raf_throw_io("write failed");
        }
        if (n == 0) raf_throw_io("write returned 0");
        src += (size_t)n;
        remaining -= (int32_t)n;
        written_total += (int64_t)n;
    }

    *(int64_t*)((char*)this_file + RAF_POSITION_OFFSET) += written_total;
}

void __jnative_fn_java_io_RandomAccessFile_initIDs___V(void) {
}

void __jnative_fn_java_io_RandomAccessFile_write0__I_V(
        void* this_file, int32_t b)
{
    if (this_file == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = raf_raw_fd(this_file);
    if (fd < 0) raf_throw_io("file closed");

    uint8_t byte = (uint8_t)b;
    ssize_t n;
    do {
        n = write(fd, &byte, 1);
    } while (n < 0 && errno == EINTR);

    if (n < 0) raf_throw_io("write failed");

    *(int64_t*)((char*)this_file + RAF_POSITION_OFFSET) += 1;
}
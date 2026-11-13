#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <errno.h>
#include <limits.h>
#include <sys/types.h>
#include <sys/stat.h>

#include "jnative_runtime.h"

#ifndef O_SYNC
#define O_SYNC 0
#endif
#ifndef O_DSYNC
#define O_DSYNC 0
#endif
#ifndef O_CLOEXEC
#define O_CLOEXEC 0
#endif

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#if defined(_WIN32)
#  include <io.h>
#  define raf_open     jn_open_utf8
#  define raf_read     _read
#  define raf_write    _write
#  define raf_lseek    _lseeki64
#else
#  include <unistd.h>
#  define raf_open     open
#  define raf_read     read
#  define raf_write    write
#  define raf_lseek    lseek
#endif

#define RAF_MODE_RDONLY    1
#define RAF_MODE_RDWR      2
#define RAF_MODE_SYNC      4
#define RAF_MODE_DSYNC     8
#define RAF_MODE_TEMPORARY 16

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

static int32_t raf_fd_field = -1;

static void* raf_fd_object(void* this_file) {
    int32_t off = __atomic_load_n(&raf_fd_field, __ATOMIC_ACQUIRE);
    if (off < 0) {
        for (ReflectionClass* c = jnative_class_by_name("java/io/RandomAccessFile");
             c != NULL && off < 0; c = c->superclass) {
            if (c->fields == NULL) continue;
            for (ReflectionField** fp = c->fields; *fp != NULL; fp++) {
                if ((*fp)->name != NULL && strcmp((const char*)(*fp)->name, "fd") == 0) {
                    off = (*fp)->offset;
                    break;
                }
            }
        }
        if (off < 0) raf_throw_io("RandomAccessFile.fd is not present in the image");
        __atomic_store_n(&raf_fd_field, off, __ATOMIC_RELEASE);
    }
    return *(void**)((char*)this_file + off);
}

static int32_t raf_raw_fd(void* this_file) {
    void* fd_obj = raf_fd_object(this_file);
    if (fd_obj == NULL) return -1;
    return *(int32_t*)((char*)fd_obj + FD_OFFSET);
}

static int32_t raf_open_fd(void* this_file) {
    if (this_file == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = raf_raw_fd(this_file);
    if (fd < 0) raf_throw_io("Stream Closed");
    return fd;
}

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
    if (mode & RAF_MODE_RDONLY) {
        flags = O_RDONLY;
    } else if (mode & RAF_MODE_RDWR) {
        flags = O_RDWR | O_CREAT;
        if (mode & RAF_MODE_SYNC) flags |= O_SYNC;
        else if (mode & RAF_MODE_DSYNC) flags |= O_DSYNC;
    } else {
        raf_throw_io("invalid mode");
    }
#if defined(_WIN32)
    flags |= _O_BINARY | _O_NOINHERIT;
    if (mode & RAF_MODE_TEMPORARY) flags |= _O_TEMPORARY;
#else
    flags |= O_CLOEXEC;
#endif

    char path_buf[PATH_MAX];
    int32_t path_len = __jnative_read_string_into(name_str, path_buf,
                                                  (int32_t)sizeof(path_buf));
    if (path_len < 0) raf_throw_fnf(NULL);

    int fd;
    do {
        fd = raf_open(path_buf, flags, 0666);
    } while (fd < 0 && errno == EINTR);
    if (fd < 0) raf_throw_fnf(path_buf);

#if !defined(_WIN32)
    struct stat st;
    if (fstat(fd, &st) == 0 && S_ISDIR(st.st_mode)) {
        close(fd);
        raf_throw_fnf(path_buf);
    }
    if (mode & RAF_MODE_TEMPORARY) unlink(path_buf);
#endif

    *(int32_t*)((char*)fd_obj + FD_OFFSET) = fd;
}

int64_t __jnative_fn_java_io_RandomAccessFile_length0___J(void* this_file) {
    int32_t fd = raf_open_fd(this_file);
    int64_t cur = (int64_t)raf_lseek(fd, 0, SEEK_CUR);
    if (cur < 0) raf_throw_io("Seek failed");
    int64_t end = (int64_t)raf_lseek(fd, 0, SEEK_END);
    if (end < 0) raf_throw_io("Seek failed");
    if ((int64_t)raf_lseek(fd, cur, SEEK_SET) < 0) raf_throw_io("Seek failed");
    return end;
}

void __jnative_fn_java_io_RandomAccessFile_setLength0__J_V(void* this_file, int64_t new_length) {
    int32_t fd = raf_open_fd(this_file);
    if (new_length < 0) raf_throw_io("Negative seek offset");
    int64_t cur = (int64_t)raf_lseek(fd, 0, SEEK_CUR);
    if (cur < 0) raf_throw_io("setLength failed");
#if defined(_WIN32)
    if (_chsize_s(fd, new_length) != 0) raf_throw_io("setLength failed");
#else
    int rc;
    do {
        rc = ftruncate(fd, (off_t)new_length);
    } while (rc < 0 && errno == EINTR);
    if (rc < 0) raf_throw_io("setLength failed");
#endif
    if (cur > new_length) cur = new_length;
    if ((int64_t)raf_lseek(fd, cur, SEEK_SET) < 0) raf_throw_io("setLength failed");
}

int64_t __jnative_fn_java_io_RandomAccessFile_getFilePointer___J(void* this_file) {
    int32_t fd = raf_open_fd(this_file);
    int64_t pos = (int64_t)raf_lseek(fd, 0, SEEK_CUR);
    if (pos < 0) raf_throw_io("Seek failed");
    return pos;
}

void __jnative_fn_java_io_RandomAccessFile_seek0__J_V(
        void* this_file, int64_t pos)
{
    int32_t fd = raf_open_fd(this_file);
    if (pos < 0) raf_throw_io("Negative seek offset");
    if ((int64_t)raf_lseek(fd, pos, SEEK_SET) < 0) raf_throw_io("Seek failed");
}

int32_t __jnative_fn_java_io_RandomAccessFile_read0___I(void* this_file) {
    int32_t fd = raf_open_fd(this_file);

    uint8_t byte;
    long n;
    do {
        n = (long)raf_read(fd, &byte, 1);
    } while (n < 0 && errno == EINTR);

    if (n < 0) raf_throw_io("Read error");
    if (n == 0) return -1;
    return (int32_t)byte;
}

int32_t __jnative_fn_java_io_RandomAccessFile_readBytes0___BII_I(
        void* this_file, void* b, int32_t off, int32_t len)
{
    if (this_file == NULL || b == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    if (off < 0 || len < 0) raf_throw_io("invalid offset/length");
    if (len == 0) return 0;

    int32_t fd = raf_open_fd(this_file);

    uint8_t* dst = (uint8_t*)b + JAVA_ARR_HDR + off;
    long n;
    do {
        n = (long)raf_read(fd, dst, (unsigned)len);
    } while (n < 0 && errno == EINTR);

    if (n < 0) raf_throw_io("Read error");
    if (n == 0) return -1;
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

    int32_t fd = raf_open_fd(this_file);

    const uint8_t* src = (const uint8_t*)b + JAVA_ARR_HDR + off;
    int32_t remaining = len;

    while (remaining > 0) {
        long n = (long)raf_write(fd, src, (unsigned)remaining);
        if (n < 0) {
            if (errno == EINTR) continue;
            raf_throw_io("Write error");
        }
        if (n == 0) raf_throw_io("Write error");
        src += (size_t)n;
        remaining -= (int32_t)n;
    }
}

void __jnative_fn_java_io_RandomAccessFile_initIDs___V(void) {
}

void __jnative_fn_java_io_RandomAccessFile_write0__I_V(
        void* this_file, int32_t b)
{
    int32_t fd = raf_open_fd(this_file);

    uint8_t byte = (uint8_t)b;
    long n;
    do {
        n = (long)raf_write(fd, &byte, 1);
    } while (n < 0 && errno == EINTR);

    if (n < 0) raf_throw_io("Write error");
}

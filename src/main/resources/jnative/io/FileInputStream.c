#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <limits.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/mman.h>

#include "jnative_runtime.h"

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

/*
 * Object layout used by this runtime for java.io.FileInputStream:
 *
 *   [ 8-byte vtable ][ FileDescriptor fd ][ String path ][ Object closeLock ][ boolean closed ]
 *
 * The FileDescriptor's own layout is fixed by jnative_runtime.h:
 *
 *   [ 8-byte vtable ][ int32 fd ][ long handle ]
 *
 * Consequently:
 *   this     + FIS_FD_OFFSET  -> FileDescriptor object pointer
 *   fd_obj   + FD_OFFSET      -> raw kernel file descriptor (int32_t)
 */
#define FIS_FD_OFFSET 8

static inline void* fis_fd_object(void* this_fis) {
    return *(void**)((char*)this_fis + FIS_FD_OFFSET);
}

static inline int32_t fis_raw_fd(void* this_fis) {
    void* fd_obj = fis_fd_object(this_fis);
    if (fd_obj == NULL) return -1;
    return jnative_fd_of(fd_obj);
}

/*
 * Constructs and throws java.io.FileNotFoundException with its detail
 * message set to the path string, as the reference JDK does. If the class
 * is missing from the image, __jnative_throw_exception falls back to a
 * generic Throwable — but that is an emergency path, not the normal one.
 *
 * __attribute__((noreturn)) keeps the compiler from generating code after
 * the call and from treating the function as returning.
 */
__attribute__((noreturn))
static void throw_file_not_found_from_str(void* name_str) {
    char buf[PATH_MAX];
    int32_t n = __jnative_read_string_into(name_str, buf, (int32_t)sizeof(buf));
    if (n < 0) {
        /* The string is too long or unreadable — the message must still
         * be a valid C string. */
        static const char fallback[] = "<unreadable path>";
        memcpy(buf, fallback, sizeof(fallback));
    }
    void* exc = __jnative_construct_exception(
        "vtable_java_io_FileNotFoundException", buf);
    __jnative_throw_exception(exc);
}

__attribute__((noreturn))
static void throw_file_not_found_from_cstr(const char* path) {
    void* exc = __jnative_construct_exception(
        "vtable_java_io_FileNotFoundException", path ? path : "");
    __jnative_throw_exception(exc);
}

/*
 * Throws java.io.IOException with detail message `msg`. Used on every
 * error path where the reference JDK throws IOException (read0, readBytes,
 * available0, length0, position0, skip0). Without a real IOException the
 * catch (Exception) clauses in the JDK code would not match, since a
 * generic Throwable is not a subclass of Exception.
 */
__attribute__((noreturn))
static void throw_io_exception(const char* msg) {
    void* exc = __jnative_construct_exception("vtable_java_io_IOException",
                                              msg ? msg : "I/O error");
    __jnative_throw_exception(exc);
}

/*
 * Opening the embedded TZDB. Works with (path, path_len) instead of a C
 * string so it does not depend on a NUL beyond the array bounds. Same
 * semantics as before, but no strlen.
 *
 * Serves the JDK's compiled TZDB from the embedded resource table.
 * ZoneInfoFile.<clinit> opens <java.home>/lib/tzdb.dat through an ordinary
 * FileInputStream. In an AOT image the executable may be running on a
 * machine with no JDK, or a different JDK release whose tzdb.dat uses a
 * newer binary format than the compiled ZoneInfoFile expects. The fallback
 * is to intercept any path ending in "/lib/tzdb.dat" and hand the caller
 * an anonymous file descriptor backed by the build JDK's copy of that
 * file (memfd on Linux, unlink-immediately mkstemp elsewhere).
 */
static int try_open_embedded_tzdb(const char* path, size_t path_len) {
    if (path == NULL) return -1;

    static const char SUFFIX[] = "/lib/tzdb.dat";
    const size_t slen = sizeof(SUFFIX) - 1;
    if (path_len < slen) return -1;
    if (memcmp(path + path_len - slen, SUFFIX, slen) != 0) return -1;

    static const char KEY[] = "__jdk_internal__/tzdb.dat";
    const JNativeResourceEntry* res =
        jnative_find_resource(KEY, (int32_t)(sizeof(KEY) - 1));
    if (res == NULL || res->data == NULL || res->size <= 0) return -1;

    int fd;
#if defined(__linux__) && defined(MFD_CLOEXEC)
    fd = memfd_create("jnative-tzdb", MFD_CLOEXEC);
    if (fd < 0) return -1;
#else
    char tmpl[] = "/tmp/jnative_tzdb_XXXXXX";
    fd = mkstemp(tmpl);
    if (fd < 0) return -1;
    (void)unlink(tmpl);
#endif

    const uint8_t* p = (const uint8_t*)res->data;
    size_t remaining = (size_t)res->size;
    while (remaining > 0) {
        ssize_t n = write(fd, p, remaining);
        if (n < 0) {
            if (errno == EINTR) continue;
            close(fd);
            return -1;
        }
        if (n == 0) {
            close(fd);
            return -1;
        }
        p += n;
        remaining -= (size_t)n;
    }

    if (lseek(fd, 0, SEEK_SET) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/*
 * private native void open0(String name) throws FileNotFoundException;
 *
 * The path string's bytes are copied into a stack buffer and NUL-terminated.
 * The raw pointer from __jnative_read_string_bytes must not be used as a C
 * string: a string built by Arrays.copyOf has no in-bounds NUL, and reading
 * past the payload escapes into the neighbouring heap chunk's header.
 */
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
    if (path_len < 0) {
        /* Path does not fit in PATH_MAX or is unreadable — exactly the
         * situation in which the reference JDK throws FileNotFoundException. */
        throw_file_not_found_from_str(name_str);
    }

    int fd = try_open_embedded_tzdb(path_buf, (size_t)path_len);
    if (fd < 0) {
        fd = open(path_buf, O_RDONLY);
    }
    if (fd < 0) {
        throw_file_not_found_from_cstr(path_buf);
    }

    *(int32_t*)((char*)fd_obj + FD_OFFSET) = fd;
}

int32_t __jnative_fn_java_io_FileInputStream_read0___I(void* this_fis)
{
    if (this_fis == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) throw_io_exception("stream closed");

    uint8_t byte;
    ssize_t n;
    do {
        n = read(fd, &byte, 1);
    } while (n < 0 && errno == EINTR);

    if (n < 0) throw_io_exception("read failed");
    if (n == 0) return -1;
    return (int32_t)byte;
}

int32_t __jnative_fn_java_io_FileInputStream_readBytes___BII_I(
        void* this_fis, void* b, int32_t off, int32_t len)
{
    if (this_fis == NULL || b == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    if (off < 0 || len < 0) {
        throw_io_exception("invalid offset/length");
    }
    if (len == 0) return 0;

    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) throw_io_exception("stream closed");

    uint8_t* dst = (uint8_t*)b + JAVA_ARR_HDR + off;
    ssize_t n;
    do {
        n = read(fd, dst, (size_t)len);
    } while (n < 0 && errno == EINTR);

    if (n < 0) throw_io_exception("read failed");
    return (int32_t)n;
}

int32_t __jnative_fn_java_io_FileInputStream_available0___I(void* this_fis)
{
    if (this_fis == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) return 0;

    int avail = 0;
    if (ioctl(fd, FIONREAD, &avail) == 0 && avail > 0) return avail;

    struct stat st;
    if (fstat(fd, &st) != 0) return avail > 0 ? avail : 0;

    off_t pos = lseek(fd, 0, SEEK_CUR);
    if (pos < 0) return avail > 0 ? avail : 0;

    off_t remaining = st.st_size - pos;
    if (remaining < 0) remaining = 0;
    if (remaining > INT32_MAX) remaining = INT32_MAX;
    return (int32_t)remaining;
}

int64_t __jnative_fn_java_io_FileInputStream_length0___J(void* this_fis)
{
    if (this_fis == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) throw_io_exception("stream closed");

    struct stat st;
    if (fstat(fd, &st) != 0) throw_io_exception("fstat failed");
    return (int64_t)st.st_size;
}

int64_t __jnative_fn_java_io_FileInputStream_position0___J(void* this_fis)
{
    if (this_fis == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) throw_io_exception("stream closed");

    off_t pos = lseek(fd, 0, SEEK_CUR);
    if (pos < 0) throw_io_exception("lseek failed");
    return (int64_t)pos;
}

void __jnative_fn_java_io_FileInputStream_initIDs___V(void) {
}

int64_t __jnative_fn_java_io_FileInputStream_skip0__J_J(
        void* this_fis, int64_t n)
{
    if (this_fis == NULL) __jnative_throw_null_pointer_exception();
    if (n <= 0) return 0;

    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) throw_io_exception("stream closed");

    off_t current = lseek(fd, 0, SEEK_CUR);
    if (current == (off_t)-1) throw_io_exception("lseek failed");

    off_t offset = (off_t)n;
    if ((int64_t)offset != n) {
        if (n > 0) {
            offset = (off_t)(((uint64_t)1 << (sizeof(off_t) * 8 - 1)) - 1);
        }
    }

    off_t target = lseek(fd, offset, SEEK_CUR);
    if (target == (off_t)-1) throw_io_exception("lseek failed");
    return (int64_t)(target - current);
}
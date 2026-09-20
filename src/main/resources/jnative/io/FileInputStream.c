#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/ioctl.h>

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);

extern const char* __jnative_read_string_bytes(void* s, int32_t* out_len);

/*
 * Object layout used by this runtime for java.io.FileInputStream:
 *
 *   [ 8-byte vtable ][ FileDescriptor fd ][ String path ][ Object closeLock ][ boolean closed ]
 *
 * FileDescriptor layout (see jnative/io/FileDescriptor.c):
 *
 *   [ 8-byte vtable ][ int32 fd ][ long handle ]
 *
 * Consequently:
 *   this     + 8 -> FileDescriptor object pointer
 *   fd_obj   + 8 -> raw kernel file descriptor (int32_t)
 *
 * The FileInputStream constructor allocates the FileDescriptor itself
 * (`fd = new FileDescriptor()`) before calling open0(name), so open0 only
 * needs to write the kernel descriptor into the already-existing object.
 */
#define FIS_FD_OFFSET           8
#define FD_RAW_FD_OFFSET        8

/* Java array layout: [ int32 length ][ payload ... ] */
#define JAVA_ARR_HDR 8

static inline void* fis_fd_object(void* this_fis) {
    return *(void**)((char*)this_fis + FIS_FD_OFFSET);
}

static inline int32_t fis_raw_fd(void* this_fis) {
    void* fd_obj = fis_fd_object(this_fis);
    if (fd_obj == NULL) return -1;
    return *(int32_t*)((char*)fd_obj + FD_RAW_FD_OFFSET);
}

/*
 * private native void open0(String name) throws FileNotFoundException;
 *
 * Opens the named file read-only and stores the resulting kernel fd in the
 * already-allocated FileDescriptor. On failure the original Java layer's
 * contract is to throw FileNotFoundException; since this runtime's throw
 * helpers do not yet materialise the full exception object graph, the
 * generic throw is used, which propagates cleanly through the generated
 * setjmp-based catch machinery.
 */
void __jnative_fn_java_io_FileInputStream_open0__Ljava_lang_String__V(
        void* this_fis, void* name_str)
{
    if (this_fis == NULL || name_str == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    void* fd_obj = fis_fd_object(this_fis);
    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    int32_t nameLen = 0;
    const char* path = __jnative_read_string_bytes(name_str, &nameLen);
    (void)nameLen;

    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    *(int32_t*)((char*)fd_obj + FD_RAW_FD_OFFSET) = fd;
}

/*
 * private native int read0() throws IOException;
 *
 * Reads a single byte from the file's current offset. Returns the byte
 * value in the range 0..255, or -1 on end of file. Every EINTR is retried
 * internally so the Java caller never observes a spurious short read
 * caused by a signal.
 */
int32_t __jnative_fn_java_io_FileInputStream_read0___I(void* this_fis)
{
    if (this_fis == NULL) {
        __jnative_throw_null_pointer_exception();
        return -1;
    }
    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return -1;
    }

    uint8_t byte;
    ssize_t n;
    do {
        n = read(fd, &byte, 1);
    } while (n < 0 && errno == EINTR);

    if (n < 0) {
        __jnative_throw_exception(NULL);
        return -1;
    }
    if (n == 0) {
        return -1;
    }
    return (int32_t)byte;
}

/*
 * private native int readBytes(byte[] b, int off, int len) throws IOException;
 *
 * Bulk read into a byte array. Returns the number of bytes actually read
 * (0 on end of file, never more than len).
 */
int32_t __jnative_fn_java_io_FileInputStream_readBytes___BII_I(
        void* this_fis, void* b, int32_t off, int32_t len)
{
    if (this_fis == NULL || b == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }
    if (off < 0 || len < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    if (len == 0) {
        return 0;
    }

    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }

    uint8_t* dst = (uint8_t*)b + JAVA_ARR_HDR + off;
    ssize_t n;
    do {
        n = read(fd, dst, (size_t)len);
    } while (n < 0 && errno == EINTR);

    if (n < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    return (int32_t)n;
}

/*
 * private native int available0() throws IOException;
 *
 * Returns a non-negative estimate of the number of bytes that can be read
 * without blocking. For regular files this is the exact remaining length
 * (or FIONREAD, whichever the platform reports first); for other kinds of
 * descriptor (pipes, character devices) FIONREAD is authoritative. A
 * failure of both queries is treated as "unknown", reported as 0, matching
 * the contract of InputStream.available for a stream whose size cannot be
 * determined.
 */
int32_t __jnative_fn_java_io_FileInputStream_available0___I(void* this_fis)
{
    if (this_fis == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }
    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) {
        return 0;
    }

    int avail = 0;
    if (ioctl(fd, FIONREAD, &avail) == 0 && avail > 0) {
        return avail;
    }

    struct stat st;
    if (fstat(fd, &st) != 0) {
        return avail > 0 ? avail : 0;
    }

    off_t pos = lseek(fd, 0, SEEK_CUR);
    if (pos < 0) {
        return avail > 0 ? avail : 0;
    }

    off_t remaining = st.st_size - pos;
    if (remaining < 0) remaining = 0;
    if (remaining > INT32_MAX) remaining = INT32_MAX;
    return (int32_t)remaining;
}

/*
 * private native long length0() throws IOException;
 *
 * Total length in bytes of the underlying file. Uses fstat(2) so it works
 * for any file descriptor, not just those opened on a named path.
 */
int64_t __jnative_fn_java_io_FileInputStream_length0___J(void* this_fis)
{
    if (this_fis == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }
    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }

    struct stat st;
    if (fstat(fd, &st) != 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    return (int64_t)st.st_size;
}

/*
 * private native long position0() throws IOException;
 *
 * The current read offset in the underlying file, queried from the kernel
 * with lseek(SEEK_CUR). Because the runtime never maintains a shadow copy
 * of the offset for FileInputStream (unlike RandomAccessFile), the kernel
 * value is authoritative.
 */
int64_t __jnative_fn_java_io_FileInputStream_position0___J(void* this_fis)
{
    if (this_fis == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }
    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }

    off_t pos = lseek(fd, 0, SEEK_CUR);
    if (pos < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    return (int64_t)pos;
}

/*
 * private static native void initIDs();
 *
 * Called from the static initializer of java.io.FileInputStream to cache
 * the JNI field IDs of the class's instance fields. This runtime accesses
 * every field through its LLVM-computed byte offset and never consults JNI
 * field IDs, so there is nothing to cache. The symbol must exist because
 * the class's <clinit> emits a native call to it.
 */
void __jnative_fn_java_io_FileInputStream_initIDs___V(void) {
}

int64_t __jnative_fn_java_io_FileInputStream_skip0__J_J(
        void* this_fis, int64_t n)
{
    if (this_fis == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    if (n <= 0) {
        return 0;
    }

    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }

    off_t current = lseek(fd, 0, SEEK_CUR);
    if (current == (off_t)-1) {
        __jnative_throw_exception(NULL);
        return 0;
    }

    /*
     * FileInputStream.skip() should move forward from the current
     * position. lseek() may fail if the descriptor is not seekable.
     */
    off_t offset = (off_t)n;

    if ((int64_t)offset != n) {
        /*
         * The requested Java long cannot be represented by off_t.
         * Clamp it to the largest positive off_t value.
         */
        if (n > 0) {
            offset = (off_t)(((uint64_t)1 << (sizeof(off_t) * 8 - 1)) - 1);
        }
    }

    off_t target = lseek(fd, offset, SEEK_CUR);
    if (target == (off_t)-1) {
        __jnative_throw_exception(NULL);
        return 0;
    }

    return (int64_t)(target - current);
}
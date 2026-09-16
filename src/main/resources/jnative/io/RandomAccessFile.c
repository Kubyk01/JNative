#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/types.h>

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);

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
 *
 * RandomAccessFile in the JDK maintains its own `position` field and
 * explicitly seeks before every read/write, so the runtime does not need
 * to synchronise it with the kernel's file offset. The field is written
 * only by seek0 and read by getFilePointer.
 */
#define RAF_FD_OFFSET       8
#define RAF_POSITION_OFFSET 16
#define RAF_RW_OFFSET       24

#define JAVA_ARR_HDR 4

static inline void* fd_object_of(void* this_file) {
    return *(void**)((char*)this_file + RAF_FD_OFFSET);
}

static inline int32_t fd_of_fd_object(void* fd_obj) {
    return *(int32_t*)((char*)fd_obj + 8);
}

/* --------------------------------------------------------------------------
 * long getFilePointer() throws IOException;
 *
 * Returns the current byte offset of the file pointer. The runtime keeps
 * the authoritative offset in the RandomAccessFile's own `position` field,
 * which is updated by every read/seek. Reading the kernel's lseek value
 * would be equivalent after a seek0, but the Java layer is free to call
 * getFilePointer before any I/O has happened, in which case the field is
 * still the initialised value (0).
 * ------------------------------------------------------------------------ */
int64_t __jnative_fn_java_io_RandomAccessFile_getFilePointer___J(void* this_file) {
    if (this_file == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }
    return *(int64_t*)((char*)this_file + RAF_POSITION_OFFSET);
}

/* --------------------------------------------------------------------------
 * private native void seek0(long pos) throws IOException;
 *
 * Sets the file pointer. The runtime updates both the cached `position`
 * field and (for good measure, so subsequent native read/write calls on
 * the same file descriptor see a consistent kernel state) the kernel's
 * own file offset through lseek.
 * ------------------------------------------------------------------------ */
void __jnative_fn_java_io_RandomAccessFile_seek0__J_V(void* this_file, int64_t pos) {
    if (this_file == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    if (pos < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    void* fd_obj = fd_object_of(this_file);
    if (fd_obj != NULL) {
        int32_t fd = fd_of_fd_object(fd_obj);
        if (fd >= 0) {
            if (lseek(fd, (off_t)pos, SEEK_SET) < 0) {
                __jnative_throw_exception(NULL);
                return;
            }
        }
    }

    *(int64_t*)((char*)this_file + RAF_POSITION_OFFSET) = pos;
}

/* --------------------------------------------------------------------------
 * private native int read0() throws IOException;
 *
 * Reads a single byte from the current file position and advances the
 * position by one. Returns the byte value (0..255) on success or -1 on
 * end of file.
 * ------------------------------------------------------------------------ */
int32_t __jnative_fn_java_io_RandomAccessFile_read0___I(void* this_file) {
    if (this_file == NULL) {
        __jnative_throw_null_pointer_exception();
        return -1;
    }

    void* fd_obj = fd_object_of(this_file);
    if (fd_obj == NULL) {
        __jnative_throw_exception(NULL);
        return -1;
    }
    int32_t fd = fd_of_fd_object(fd_obj);
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

    *(int64_t*)((char*)this_file + RAF_POSITION_OFFSET) += 1;
    return (int32_t)byte;
}

/* --------------------------------------------------------------------------
 * private native int readBytes0(byte[] b, int off, int len) throws IOException;
 *
 * Bulk read into a byte array. Returns the number of bytes actually read
 * (0 on EOF) and advances the cached position by that amount.
 * ------------------------------------------------------------------------ */
int32_t __jnative_fn_java_io_RandomAccessFile_readBytes0___BII_I(
        void* this_file, void* b, int32_t off, int32_t len) {
    if (this_file == NULL || b == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }
    if (off < 0 || len < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }

    void* fd_obj = fd_object_of(this_file);
    if (fd_obj == NULL) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    int32_t fd = fd_of_fd_object(fd_obj);
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
    if (n == 0) {
        return 0;
    }

    *(int64_t*)((char*)this_file + RAF_POSITION_OFFSET) += (int64_t)n;
    return (int32_t)n;
}

/* --------------------------------------------------------------------------
 * private static native void initIDs();
 *
 * This runtime does not use JNI field IDs: instance fields are accessed
 * directly through their LLVM-computed byte offsets. The symbol exists
 * because RandomAccessFile.<clinit> emits a native call to it.
 * ------------------------------------------------------------------------ */
void __jnative_fn_java_io_RandomAccessFile_initIDs___V(void) {
}
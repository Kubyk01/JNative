#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/stat.h>

#include "jnative_runtime.h"

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

static inline void* fd_object_of(void* this_file) {
    return *(void**)((char*)this_file + RAF_FD_OFFSET);
}

static inline int32_t fd_of_fd_object(void* fd_obj) {
    return *(int32_t*)((char*)fd_obj + FD_OFFSET);
}

/* --------------------------------------------------------------------------
 * private native void open0(String name, int mode) throws FileNotFoundException;
 *
 * Opens the named file in the requested mode and stores the resulting
 * kernel descriptor into the already-allocated FileDescriptor that the
 * RandomAccessFile constructor created. The mode integer is one of the
 * constants the Java layer declares:
 *
 *     1  = O_RDONLY                    ("r")
 *     2  = O_RDWR                      ("rw")
 *     6  = O_RDWR | O_SYNC             ("rws")
 *     10 = O_RDWR | O_DSYNC            ("rwd")
 *
 * Those numeric values are not the platform's O_* constants; they are
 * the fixed Java-side encoding that the JDK's own C implementation also
 * uses, which is why the mapping below is spelled out explicitly rather
 * than relying on the numeric coincidence with Linux's <fcntl.h>.
 *
 * Any failure to open the file — ENOENT, EACCES, EISDIR when opening a
 * directory for writing, EMFILE — surfaces as the generic throw helper.
 * The Java caller catches and re-raises it as FileNotFoundException with
 * the file's name attached, matching the reference JDK's behaviour.
 * ------------------------------------------------------------------------ */
void __jnative_fn_java_io_RandomAccessFile_open0__Ljava_lang_String_I_V(
        void* this_file, void* name_str, int32_t mode)
{
    if (this_file == NULL || name_str == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    void* fd_obj = fd_object_of(this_file);
    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    /* Translate the Java-level mode constants to the platform's open(2)
     * flags. Any unrecognised value is a programming error at the Java
     * level; report it as an I/O failure rather than silently opening
     * with the wrong access mode. */
    int flags = -1;
    switch (mode) {
        case 1:  flags = O_RDONLY;                break;
        case 2:  flags = O_RDWR;                  break;
        case 6:  flags = O_RDWR | O_SYNC;         break;
        case 10: flags = O_RDWR | O_DSYNC;        break;
        default: flags = -1;                      break;
    }
    if (flags < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    int32_t nameLen = 0;
    const char* path = __jnative_read_string_bytes(name_str, &nameLen);
    (void)nameLen;
    if (path == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    int fd = open(path, flags);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    *(int32_t*)((char*)fd_obj + FD_OFFSET) = fd;
}

/* --------------------------------------------------------------------------
 * private native long length() throws IOException;
 *
 * Total length of the underlying file in bytes, obtained with fstat(2)
 * on the FileDescriptor. Uses the descriptor rather than the path so
 * the answer is correct even when the file is being accessed through a
 * FileDescriptor that has been passed in from elsewhere.
 * ------------------------------------------------------------------------ */
int64_t __jnative_fn_java_io_RandomAccessFile_length0___J(void* this_file) {
    if (this_file == NULL) {
        __jnative_throw_null_pointer_exception();
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

    struct stat st;
    if (fstat(fd, &st) < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    return (int64_t)st.st_size;
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

void __jnative_fn_java_io_RandomAccessFile_writeBytes0___BII_V(
        void* this_file, void* b, int32_t off, int32_t len)
{
    if (this_file == NULL || b == NULL) {
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

    void* fd_obj = fd_object_of(this_file);
    if (fd_obj == NULL) {
        __jnative_throw_exception(NULL);
        return;
    }
    int32_t fd = fd_of_fd_object(fd_obj);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    const uint8_t* src = (const uint8_t*)b + JAVA_ARR_HDR + off;
    int32_t remaining = len;
    int64_t written_total = 0;

    while (remaining > 0) {
        ssize_t n = write(fd, src, (size_t)remaining);
        if (n < 0) {
            if (errno == EINTR) {
                /* Signal delivered before any byte was committed.
                 * Retry the same write with the same arguments. */
                continue;
            }
            __jnative_throw_exception(NULL);
            return;
        }
        if (n == 0) {
            /* write(2) returning 0 for a non-zero count is not a
             * legitimate outcome on a regular file; looping on it
             * would hang the write forever. */
            __jnative_throw_exception(NULL);
            return;
        }

        src += (size_t)n;
        remaining -= (int32_t)n;
        written_total += (int64_t)n;
    }

    *(int64_t*)((char*)this_file + RAF_POSITION_OFFSET) += written_total;
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

/*
 * private native void write0(int b) throws IOException;
 *
 * Single-byte write at the file's current position. The Java-level
 * RandomAccessFile.write(int) calls this directly; RandomAccessFile
 * does not maintain a shadow of the file position (unlike its own
 * `position` field on the FileChannel side), so the kernel's own
 * offset is authoritative and advances by one byte on each call.
 *
 * After the byte has been committed, the cached `position` field is
 * incremented so that a subsequent getFilePointer() reflects the
 * write. The order is significant: the kernel write must succeed
 * before the cache is updated, otherwise a failed write would leave
 * the cache ahead of the true offset.
 */
void __jnative_fn_java_io_RandomAccessFile_write0__I_V(
        void* this_file, int32_t b)
{
    if (this_file == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    void* fd_obj = fd_object_of(this_file);
    if (fd_obj == NULL) {
        __jnative_throw_exception(NULL);
        return;
    }
    int32_t fd = fd_of_fd_object(fd_obj);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    uint8_t byte = (uint8_t)b;
    ssize_t n;
    do {
        n = write(fd, &byte, 1);
    } while (n < 0 && errno == EINTR);

    if (n < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    *(int64_t*)((char*)this_file + RAF_POSITION_OFFSET) += 1;
}
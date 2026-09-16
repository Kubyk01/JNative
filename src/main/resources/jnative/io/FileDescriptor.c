#include <unistd.h>
#include <stdint.h>
#include <errno.h>
#include <fcntl.h>

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);

/*
 * FileDescriptor layout in this runtime:
 *   [ 8 bytes vtable ][ int32 fd ][ long handle ][ ... ]
 *
 * FileDescriptor.fd is the first instance field, located at offset 8.
 */
void __jnative_fn_java_io_FileDescriptor_close0___V(void* this_fd) {
    if (this_fd == NULL) {
        return;
    }

    int32_t fd = *(int32_t*)((char*)this_fd + 8);

    if (fd >= 0) {
        (void)close(fd);
    }
}

void __jnative_fn_java_io_FileDescriptor_sync___V(void* this_fd) {
    if (this_fd == NULL) {
        __jnative_throw_exception(NULL);
        return;
    }
    int32_t fd = *(int32_t*)((char*)this_fd + 8);
    if (fd >= 0) {
        if (fsync(fd) < 0) {
            __jnative_throw_exception(NULL);
        }
    }
}

/*
 * private static native long getHandle(int d);
 *
 * Returns an opaque 64-bit identifier for the given raw file descriptor.
 * On Unix this identifier is the descriptor itself: it uniquely names an
 * open file within the process and is stable for the lifetime of the
 * descriptor. An invalid descriptor (-1) yields -1.
 */
int64_t __jnative_fn_java_io_FileDescriptor_getHandle__I_J(int32_t fd) {
    return (int64_t)fd;
}

/*
 * private static native boolean getAppend(int d);
 *
 * Returns true iff the file descriptor was opened with O_APPEND. The flag
 * is queried through fcntl(F_GETFL); a failed query or an invalid
 * descriptor yields false.
 */
int32_t __jnative_fn_java_io_FileDescriptor_getAppend__I_Z(int32_t fd) {
    if (fd < 0) {
        return 0;
    }
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0) {
        return 0;
    }
    return (flags & O_APPEND) ? 1 : 0;
}


/*
 * private static native void initIDs();
 *
 * Caches the JNI field IDs of FileDescriptor's instance fields. This
 * runtime accesses instance fields through their LLVM-computed byte
 * offsets and never consults JNI field IDs, so there is nothing to
 * cache. The symbol exists so the class's <clinit> links.
 */
void __jnative_fn_java_io_FileDescriptor_initIDs___V(void) {
}

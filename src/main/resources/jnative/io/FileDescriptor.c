#include <unistd.h>
#include <stdint.h>
#include <errno.h>
#include <fcntl.h>

#include "jnative_runtime.h"

/*
 * FileDescriptor layout in this runtime:
 *   [ 8 bytes vtable ][ int32 fd ][ long handle ][ ... ]
 *
 */

__attribute__((noreturn))
static void fd_throw_io(const char* msg) {
    void* exc = __jnative_construct_exception(
        "vtable_java_io_IOException", msg ? msg : "I/O error");
    __jnative_throw_exception(exc);
}

void __jnative_fn_java_io_FileDescriptor_close0___V(void* this_fd) {
    if (this_fd == NULL) {
        return;
    }

    int32_t fd = jnative_fd_of(this_fd);
    if (fd >= 0) {
        (void)close(fd);
    }
}

void __jnative_fn_java_io_FileDescriptor_sync___V(void* this_fd) {
    if (this_fd == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    int32_t fd = jnative_fd_of(this_fd);
    if (fd < 0) {
        return;
    }

    if (fsync(fd) < 0) {
        fd_throw_io("fsync failed");
    }
}

/* --------------------------------------------------------------------------
 * private static native long getHandle(int d);
 *
 * Returns an opaque 64-bit identifier for the given raw file descriptor.
 * On Unix this identifier is the descriptor itself: it uniquely names an
 * open file within the process and is stable for the lifetime of the
 * descriptor. An invalid descriptor (-1) yields -1.
 * ------------------------------------------------------------------------ */
int64_t __jnative_fn_java_io_FileDescriptor_getHandle__I_J(int32_t fd) {
    return (int64_t)fd;
}

/* --------------------------------------------------------------------------
 * private static native boolean getAppend(int d);
 *
 * Returns true iff the file descriptor was opened with O_APPEND. The
 * flag is queried through fcntl(F_GETFL); a failed query or an invalid
 * descriptor yields false.
 * ------------------------------------------------------------------------ */
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

/* --------------------------------------------------------------------------
 * private static native void initIDs();
 *
 * Caches the JNI field IDs of FileDescriptor's instance fields. This
 * runtime accesses instance fields through their LLVM-computed byte
 * offsets and never consults JNI field IDs, so there is nothing to
 * cache. The symbol exists so the class's <clinit> links.
 * ------------------------------------------------------------------------ */
void __jnative_fn_java_io_FileDescriptor_initIDs___V(void) {
}
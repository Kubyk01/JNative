#include <unistd.h>
#include <stdint.h>
#include <errno.h>

/*
 * FileDescriptor layout in this runtime:
 *   [ 8 bytes vtable ][ int32 fd ][ long handle ][ ... ]
 *
 * FileDescriptor.fd is the first instance field, located at offset 8.
 * close0() is an instance native method, so the receiver is passed
 * as the first argument (i8* in LLVM IR / void* in C).
 */
void __jnative_fn_java_io_FileDescriptor_close0___V(void* this_fd) {
    if (this_fd == NULL) {
        return;
    }

    int32_t fd = *(int32_t*)((char*)this_fd + 8);

    if (fd >= 0) {
        /* close() releases the kernel file descriptor. EINTR must not be
         * retried on Linux because after EINTR the fd state is unspecified;
         * matching JDK behaviour we simply ignore the outcome. */
        (void)close(fd);
    }
}
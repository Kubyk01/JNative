#define _GNU_SOURCE
#include <stdint.h>
#include <unistd.h>
#include <errno.h>

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);

void __jnative_fn_java_io_FileCleanable_initIDs___V(void) {
}

void __jnative_fn_java_io_FileCleanable_cleanupClose0__IJ_V(
        int32_t fd, int64_t handle)
{
    (void)handle;

    if (fd < 0) {
        return;
    }

    if (close(fd) < 0) {
        if (errno == EBADF) {
            return;
        }
        __jnative_throw_exception(NULL);
    }
}
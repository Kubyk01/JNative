#define _GNU_SOURCE

#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>

#if defined(__linux__)
#  include <unistd.h>
#  include <errno.h>
#  include <string.h>
#  include <sys/eventfd.h>
#endif

#include "jnative_runtime.h"

#if defined(__linux__)

__attribute__((noreturn))
static void jn_eventfd_throw(const char* what, int err) {
    char buf[256];
    snprintf(buf, sizeof(buf), "%s: %s (errno %d)", what, strerror(err), err);
    __jnative_throw_exception(__jnative_construct_exception("vtable_java_io_IOException", buf));
}

int32_t __jnative_fn_sun_nio_ch_EventFD_eventfd0___I(void)
{
    int efd = eventfd(0, EFD_CLOEXEC);
    if (efd < 0) jn_eventfd_throw("eventfd failed", errno);
    return (int32_t)efd;
}

int32_t __jnative_fn_sun_nio_ch_EventFD_set0__I_I(int32_t efd)
{
    uint64_t one = 1;
    ssize_t res;
    do {
        res = write(efd, &one, sizeof(one));
    } while (res < 0 && errno == EINTR);
    if (res < 0 && errno != EAGAIN) jn_eventfd_throw("eventfd write failed", errno);
    return (int32_t)res;
}

#else

int32_t __jnative_fn_sun_nio_ch_EventFD_eventfd0___I(void)
{
    __jnative_throw_exception(__jnative_construct_exception("vtable_java_io_IOException", "eventfd unsupported"));
    return -1;
}

int32_t __jnative_fn_sun_nio_ch_EventFD_set0__I_I(int32_t efd)
{
    (void)efd;
    __jnative_throw_exception(__jnative_construct_exception("vtable_java_io_IOException", "eventfd unsupported"));
    return -1;
}

#endif

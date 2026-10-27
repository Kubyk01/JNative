/*
 * sun.nio.ch.EPoll — Linux-only epoll bridge.
 *
 * The class is part of the JDK's Linux NIO selector implementation
 * (EPollSelectorImpl). On Windows the equivalent selector is
 * WindowsSelectorImpl, which uses WSAEventSelect + select(2) and has
 * no native EPoll dependency. Therefore this translation unit is
 * compiled only when the target is Linux.
 *
 * The guard is the correct fix for the build failure: the symbols
 * epoll_create1, epoll_ctl, epoll_wait and the struct epoll_event are
 * glibc/Linux kernel interfaces that do not exist on Windows. Compiling
 * this file on a Windows target is not a portability problem — the
 * code is simply not part of the Windows image.
 */

#if defined(__linux__)

#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <unistd.h>
#include <sys/epoll.h>
#include <errno.h>

#include "jnative_runtime.h"

/*
 * EPoll.java constants:
 *
 *     EPOLL_CTL_ADD = 1
 *     EPOLL_CTL_DEL = 2
 *     EPOLL_CTL_MOD = 3
 *
 *     EPOLLIN  = 0x001
 *     EPOLLOUT = 0x004
 *     EPOLLERR = 0x008
 *     EPOLLHUP = 0x010
 *
 * EPOLLONESHOT is a JDK-internal flag (1 << 30) that the selector uses
 * for one-shot registrations; it is passed through verbatim to
 * epoll_ctl.
 */
#define JNATIVE_EPOLLONESHOT (1u << 30)

/* -------------------------------------------------------------------------
 * static native int epollCreate();
 *
 * Creates a new epoll instance with close-on-exec set.
 * Returns the epoll file descriptor, or throws IOException on failure.
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_epollCreate___I(void)
{
    int fd = epoll_create1(EPOLL_CLOEXEC);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int32_t)fd;
}

/* -------------------------------------------------------------------------
 * static native int epollCtl(int epfd, int opcode, int fd, int events);
 *
 * Adds, modifies or removes a file descriptor from the epoll set.
 * Returns 0 on success, or throws IOException on failure.
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_epollCtl__IIII_I(
        int32_t epfd, int32_t opcode, int32_t fd, int32_t events)
{
    struct epoll_event ev;
    /* The kernel ignores the event payload on EPOLL_CTL_DEL; the field
     * is initialised anyway so that the struct is fully defined. */
    ev.events   = (uint32_t)events;
    ev.data.fd  = fd;

    int rc = epoll_ctl(epfd, opcode, fd, &ev);
    if (rc < 0) {
        __jnative_throw_exception(NULL);
    }
    return 0;
}

/* -------------------------------------------------------------------------
 * static native int epollWait(int epfd, long pollArray, int timeout);
 *
 * Waits for events on the epoll instance.
 *
 * `pollArray` is the address of a native-allocated array of
 * epoll_event structs (allocated by EPollArrayWrapper in the JDK).
 * Each entry holds a 32-bit `events` field followed by a 32-bit
 * `data.fd` field; that is exactly the layout of struct epoll_event
 * on Linux for the `EPOLL_CTL_ADD`/`EPOLL_CTL_MOD` path.
 *
 * Returns the number of ready descriptors, 0 on timeout, or throws
 * IOException on failure.
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_epollWait__IJI_I(
        int32_t epfd, int64_t pollArray, int32_t timeout)
{
    struct epoll_event* events = (struct epoll_event*)(intptr_t)pollArray;
    if (events == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    /*
     * The JDK's EPollArrayWrapper allocates a buffer large enough to
     * hold `NUM_EPOLL_EVENTS` entries (currently 256). The kernel
     * writes at most that many ready events; the exact constant is
     * defined in EPollArrayWrapper.java and must match here.
     */
    const int MAX_EVENTS = 256;

    int n;
    do {
        n = epoll_wait(epfd, events, MAX_EVENTS, timeout);
    } while (n < 0 && errno == EINTR);

    if (n < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int32_t)n;
}

/* -------------------------------------------------------------------------
 * static native int epollSize();
 *
 * Returns sizeof(struct epoll_event), which the JDK uses to size the
 * native poll buffer. The value is platform-specific and is exposed
 * through the class's static final field.
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_epollSize___I(void)
{
    return (int32_t)sizeof(struct epoll_event);
}

/* -------------------------------------------------------------------------
 * static native int epollDataOffset();
 *
 * Returns the byte offset of the `data` field inside struct
 * epoll_event. The JDK stores the file descriptor in that field and
 * reads it back through Unsafe, so the offset must be the kernel's
 * actual offset, not a compile-time guess.
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_epollDataOffset___I(void)
{
    return (int32_t)offsetof(struct epoll_event, data);
}

/* -------------------------------------------------------------------------
 * static native int epollEventsOffset();
 *
 * Returns the byte offset of the `events` field inside struct
 * epoll_event. Used by the JDK to read the ready-event mask.
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_epollEventsOffset___I(void)
{
    return (int32_t)offsetof(struct epoll_event, events);
}

#endif /* __linux__ */
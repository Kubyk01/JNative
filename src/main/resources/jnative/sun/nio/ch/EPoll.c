/*
 * sun.nio.ch.EPoll — Linux epoll bridge with non-Linux fallbacks.
 *
 * The real implementation is Linux-only: it calls epoll_create1,
 * epoll_ctl, epoll_wait and uses struct epoll_event, none of which
 * exist on Windows. But the Linux analysis that produced the .ll
 * files in this build pulled sun/nio/ch/EPoll into the reachability
 * closure, so the emitted IR references the mangled natives
 * unconditionally. On a Windows target those symbols must therefore
 * still exist — otherwise the link fails with "undefined symbol:
 * __jnative_fn_sun_nio_ch_EPoll_*".
 *
 * The fix keeps the real implementation behind #if defined(__linux__)
 * and provides stubs in the #else branch. The stubs are never reached
 * at runtime on Windows because EPollSelectorImpl / EPollPoller are
 * not loaded there; they exist purely so the linker can resolve the
 * call sites that came from the Linux-host analysis.
 *
 * The eventSize / eventsOffset / dataOffset stubs return the Linux
 * x86_64 values. Those three natives ARE called at run time, from
 * EPoll.<clinit>, which is scheduled eagerly by the JNative runtime
 * regardless of target platform. Returning a valid number there lets
 * the class initializer complete; the value itself is never consumed
 * on Windows.
 */

#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>

#if defined(__linux__)
#  include <unistd.h>
#  include <sys/epoll.h>
#  include <errno.h>
#endif

#include "jnative_runtime.h"

#if defined(__linux__)

/* -------------------------------------------------------------------------
 * static native int create();
 *
 * Creates a new epoll instance with close-on-exec set.
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_create___I(void)
{
    int fd = epoll_create1(EPOLL_CLOEXEC);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int32_t)fd;
}

/* -------------------------------------------------------------------------
 * static native int ctl(int epfd, int opcode, int fd, int events);
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_ctl__IIII_I(
        int32_t epfd, int32_t opcode, int32_t fd, int32_t events)
{
    struct epoll_event ev;
    ev.events  = (uint32_t)events;
    ev.data.fd = fd;

    int rc = epoll_ctl(epfd, opcode, fd, &ev);
    if (rc < 0) {
        __jnative_throw_exception(NULL);
    }
    return 0;
}

/* -------------------------------------------------------------------------
 * static native int wait(int epfd, long pollAddress,
 *                        int numfds, int timeout) throws IOException;
 *
 * `pollAddress` points at a native buffer of `numfds` struct epoll_event
 * entries. The kernel writes at most `numfds` ready events into it. The
 * caller (EPollArrayWrapper) allocates that buffer once and passes the
 * count through unchanged; we do not invent a MAX_EVENTS constant.
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_wait__IJII_I(
        int32_t epfd, int64_t pollAddress, int32_t numfds, int32_t timeout)
{
    struct epoll_event* events = (struct epoll_event*)(intptr_t)pollAddress;
    if (events == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    if (numfds <= 0) {
        return 0;
    }

    int n;
    do {
        n = epoll_wait(epfd, events, numfds, timeout);
    } while (n < 0 && errno == EINTR);

    if (n < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int32_t)n;
}

/* -------------------------------------------------------------------------
 * static native int eventSize();
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_eventSize___I(void)
{
    return (int32_t)sizeof(struct epoll_event);
}

/* -------------------------------------------------------------------------
 * static native int eventsOffset();
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_eventsOffset___I(void)
{
    return (int32_t)offsetof(struct epoll_event, events);
}

/* -------------------------------------------------------------------------
 * static native int dataOffset();
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_dataOffset___I(void)
{
    return (int32_t)offsetof(struct epoll_event, data);
}

#else /* !__linux__ */

/*
 * Non-Linux fallbacks.
 *
 * These symbols exist only because the Linux analysis that produced the
 * .ll files in this build emitted references to them unconditionally.
 * On Windows the JDK uses WindowsSelectorImpl (WSAEventSelect + select)
 * and EPollSelectorImpl / EPollPoller are never loaded, so create / ctl
 * / wait are never reached at run time.
 *
 * The three offset accessors ARE reached, from EPoll.<clinit>, which
 * the JNative runtime schedules eagerly on every platform. They return
 * the Linux x86_64 layout (sizeof = 12, events @ 0, data @ 4). The
 * values are never consulted on Windows — EPoll itself is unused —
 * but returning a real number lets the class initializer complete
 * instead of aborting the whole program with an unhandled exception.
 */

int32_t __jnative_fn_sun_nio_ch_EPoll_create___I(void)
{
    __jnative_throw_exception(NULL);
    return -1;
}

int32_t __jnative_fn_sun_nio_ch_EPoll_ctl__IIII_I(
        int32_t epfd, int32_t opcode, int32_t fd, int32_t events)
{
    (void)epfd; (void)opcode; (void)fd; (void)events;
    __jnative_throw_exception(NULL);
    return -1;
}

int32_t __jnative_fn_sun_nio_ch_EPoll_wait__IJII_I(
        int32_t epfd, int64_t pollAddress, int32_t numfds, int32_t timeout)
{
    (void)epfd; (void)pollAddress; (void)numfds; (void)timeout;
    __jnative_throw_exception(NULL);
    return -1;
}

int32_t __jnative_fn_sun_nio_ch_EPoll_eventSize___I(void)
{
    return 12;
}

int32_t __jnative_fn_sun_nio_ch_EPoll_eventsOffset___I(void)
{
    return 0;
}

int32_t __jnative_fn_sun_nio_ch_EPoll_dataOffset___I(void)
{
    return 4;
}

#endif /* __linux__ */
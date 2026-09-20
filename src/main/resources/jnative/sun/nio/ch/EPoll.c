#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <errno.h>
#include <unistd.h>
#include <sys/epoll.h>

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);

/*
 * sun.nio.ch.EPoll — the Linux epoll(7) event-polling dispatcher.
 *
 * The class is a thin JNI binding around four epoll(2) entry points plus
 * three layout-probe helpers. Every method is called from
 * sun.nio.ch.EPollPoller or, once per process, from EPoll.<clinit>.
 *
 * The runtime's exception convention applies to create/wait: failures
 * that the Java caller has no way to handle fall through the generic
 * throw helper. The ctl method is different — its Java caller inspects
 * the return value and maps it to a pollster-specific error code, so ctl
 * reports errno numerically rather than throwing.
 */

/* sun.nio.ch.IOStatus.INTERRUPTED */
#define IO_STATUS_INTERRUPTED (-3)

/* -------------------------------------------------------------------------
 * static native int eventSize();
 *
 * Returns sizeof(struct epoll_event), which the JDK uses to size the
 * native poll-array buffer that EPollPoller allocates per selector.
 * Computed here rather than hard-coded so the value matches whatever the
 * kernel's headers actually define on the build platform.
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_eventSize___I(void) {
    return (int32_t)sizeof(struct epoll_event);
}

/* -------------------------------------------------------------------------
 * static native int eventsOffset();
 *
 * Byte offset of the `events` member within struct epoll_event. The
 * JDK's Java-side unpacking loop reads the event mask from this offset
 * in each entry of the poll array that epoll_wait filled in.
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_eventsOffset___I(void) {
    return (int32_t)offsetof(struct epoll_event, events);
}

/* -------------------------------------------------------------------------
 * static native int dataOffset();
 *
 * Byte offset of the `data` union within struct epoll_event. The JDK
 * reads the opaque user token (the fd it registered, or a channel
 * identifier) from this offset when iterating over the entries that
 * epoll_wait returned.
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_dataOffset___I(void) {
    return (int32_t)offsetof(struct epoll_event, data);
}

/* -------------------------------------------------------------------------
 * static native int create();
 *
 * Creates a new epoll file descriptor via epoll_create1(0). The flags
 * argument is fixed at 0 because the JDK does not require EPOLL_CLOEXEC
 * (it manages the CLOEXEC bit through the ordinary FileDescriptor
 * machinery) and does not use any other epoll_create1 flag.
 *
 * On failure — EMFILE when the process has exhausted its descriptor
 * table, ENFILE when the system-wide table is full, ENOMEM — the generic
 * throw helper is invoked and the Java caller's `catch (IOException)`
 * block converts the failure into the appropriate exception.
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_create___I(void) {
    int fd = epoll_create1(0);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int32_t)fd;
}

/* -------------------------------------------------------------------------
 * static native int ctl(int epfd, int opcode, int fd, int events);
 *
 * Registers, modifies, or removes an fd in the epoll set. `opcode` is
 * one of EPOLL_CTL_ADD / EPOLL_CTL_MOD / EPOLL_CTL_DEL, mirroring the
 * constants that EPollPoller declares at the Java level.
 *
 * The `data.fd` field of the epoll_event record is set to the fd itself.
 * The JDK's Java-side event-unpacking code reads that field back from the
 * poll array after epoll_wait, so the value stored here is the opaque
 * token the caller will observe.
 *
 * Unlike create and wait, ctl reports errors by *returning* the errno
 * rather than throwing. The Java-side caller uses the numeric code to
 * distinguish a transient failure (EINTR, which it retries) from a
 * permanent one (EBADF / EEXIST / ENOENT, which it converts to a
 * ClosedChannelException or a generic IOException as appropriate).
 *
 * EINTR is retried transparently: on Linux epoll_ctl can be interrupted
 * by a signal delivered between the kernel entry and the point where the
 * operation is committed, and the JDK's own `restartable` macro does
 * exactly the same retry.
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_ctl__IIII_I(
        int32_t epfd, int32_t opcode, int32_t fd, int32_t events)
{
    struct epoll_event event;
    event.events = (uint32_t)events;
    event.data.fd = (int)fd;

    int res;
    do {
        res = epoll_ctl((int)epfd, (int)opcode, (int)fd, &event);
    } while (res < 0 && errno == EINTR);

    return (res == 0) ? 0 : errno;
}

/* -------------------------------------------------------------------------
 * static native int wait(int epfd, long pollAddress, int numfds,
 *                        int timeout);
 *
 * Blocks until one or more of the registered fds becomes ready, or until
 * `timeout` milliseconds have elapsed (0 = return immediately, -1 =
 * block indefinitely). `pollAddress` is the raw address of the native
 * buffer into which the kernel writes the array of ready epoll_event
 * records; that buffer is allocated and sized by the Java-side poller
 * using the values returned by eventSize / eventsOffset / dataOffset.
 *
 * Return value:
 *   >= 0 : number of ready fds written into the buffer
 *   -3   : the wait was interrupted by a signal (IOStatus.INTERRUPTED);
 *          the Java caller maps this to zero ready events and loops
 *   throws on any other failure
 *
 * The INTERRUPTED return is not an error: the JDK's selector loop uses
 * it as the hook that drives its periodic "check for pending
 * modifications to the registered set" pass, so a signal arriving during
 * the wait must not surface as an exception to the caller.
 * ----------------------------------------------------------------------- */
int32_t __jnative_fn_sun_nio_ch_EPoll_wait__IJII_I(
        int32_t epfd, int64_t address, int32_t numfds, int32_t timeout)
{
    struct epoll_event* events = (struct epoll_event*)(intptr_t)address;

    int res = epoll_wait((int)epfd, events, (int)numfds, (int)timeout);
    if (res < 0) {
        if (errno == EINTR) {
            return IO_STATUS_INTERRUPTED;
        }
        __jnative_throw_exception(NULL);
    }
    return (int32_t)res;
}
#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>

__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);

/*
 * FileDescriptor layout in this runtime:
 *   [ 8 bytes vtable ][ int32 fd ][ long handle ]
 *
 * The raw kernel fd is the first instance field, at offset 8.
 */
#define FD_OFFSET 8

static inline int32_t fd_of(void* fd_obj) {
    return *(int32_t*)((char*)fd_obj + FD_OFFSET);
}

/*
 * sun.nio.ch.UnixDispatcher — shared base class for the platform's
 * channel dispatchers (SocketDispatcher, and on Linux also
 * UnixDispatcher itself when the file dispatcher is not selected).
 *
 * The class contributes three natives:
 *
 *   init()                       -- called once from <clinit>
 *   preClose0(FileDescriptor fd) -- called from preClose before close
 *   close0(FileDescriptor fd)    -- the terminal close operation
 *
 * init and preClose0 do no HotSpot-specific work in this runtime.
 * close0 is the one that actually releases the descriptor; it is
 * inherited unchanged by SocketDispatcher, whose own close() delegates
 * to the same body.
 */

/* -------------------------------------------------------------------------
 * static native void init();
 *
 * Called from UnixDispatcher.<clinit>. On HotSpot this hook installs the
 * platform-specific pre-close actions that preClose0 dispatches to.
 * This runtime does not use any of those actions — see preClose0 below —
 * so the hook is a strict no-op. The symbol must exist because
 * UnixDispatcher.<clinit> emits a native call to it.
 * ----------------------------------------------------------------------- */
void __jnative_fn_sun_nio_ch_UnixDispatcher_init___V(void) {
}

/* -------------------------------------------------------------------------
 * static native void preClose0(FileDescriptor fd);
 *
 * Called from UnixDispatcher.preClose and inherited unchanged by
 * SocketDispatcher.preClose. The call site is
 *
 *     void preClose(FileDescriptor fd) throws IOException {
 *         preClose0(fd);
 *     }
 *
 * The purpose of preClose is to give the platform a chance to perform
 * any socket-specific teardown that must happen *before* the descriptor
 * itself is closed — releasing an associate with a completion port on
 * Windows, waking a thread blocked in a synchronous accept/read on the
 * associated selector, and so on.
 *
 * On Linux with epoll there is no such pre-close step in the reference
 * VM either: the JDK's own sun.nio.ch.UnixDispatcher declares
 *
 *     static native void preClose0(FileDescriptor fd) throws IOException;
 *
 * and the native body is empty. Closing the underlying fd via the
 * ordinary FileDescriptor.close path is sufficient — the kernel
 * automatically unregisters the fd from any epoll set it was added to,
 * which is exactly the teardown the selector needs.
 *
 * The function still performs a null check, matching the reference
 * implementation and every other native in this runtime that receives
 * a FileDescriptor. A null receiver is a genuine programming error at
 * the Java level and must surface as an NPE, not as a silent no-op.
 * ----------------------------------------------------------------------- */
void __jnative_fn_sun_nio_ch_UnixDispatcher_preClose0__Ljava_io_FileDescriptor__V(
        void* fd_obj)
{
    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    /* Intentionally empty: on Linux no pre-close work is required. */
    (void)fd_of(fd_obj);
}

/* -------------------------------------------------------------------------
 * static native void close0(FileDescriptor fd);
 *
 * The terminal close operation for a channel's underlying descriptor.
 * Called from UnixDispatcher.close and inherited unchanged by
 * SocketDispatcher.close, both of which are themselves invoked from
 * FileDescriptor.close() and from the channel-close paths in
 * SocketChannelImpl / ServerSocketChannelImpl / DatagramChannelImpl.
 *
 * The reference JDK's C body is:
 *
 *     jint fd = fdval(env, fdo);
 *     if (fd != -1) {
 *         close(fd);
 *     }
 *
 * That is exactly the behaviour implemented below, with two adjustments
 * for this runtime's conventions:
 *
 *   1. The raw fd is read from the FileDescriptor object's first
 *      instance field (offset 8), the same layout used by every other
 *      native in this runtime that takes a FileDescriptor.
 *
 *   2. A negative fd value means "already closed". FileDescriptor is
 *      specified to be idempotent under close: calling close() twice is
 *      a no-op on the second call, and the channel's own Java-side
 *      bookkeeping relies on that. The guard below preserves this
 *      contract without needing to inspect any per-instance state.
 *
 * A NULL FileDescriptor is a genuine programming error at the Java
 * level — no caller in the JDK or in user code reaches this native
 * with a null receiver. It surfaces as an NPE rather than a silent
 * no-op, matching the treatment of null receivers in every other
 * native of this file and of the surrounding dispatcher family.
 *
 * The return value is void: the reference implementation does not
 * throw on a failed close() call, because there is nothing a caller
 * can usefully do about it. A descriptor that the kernel refuses to
 * release has already been unlinked from the process's descriptor
 * table, and the value is best-effort from the Java layer's point of
 * view. Swallowing errno here matches that contract exactly.
 * ----------------------------------------------------------------------- */
void __jnative_fn_sun_nio_ch_UnixDispatcher_close0__Ljava_io_FileDescriptor__V(
        void* fd_obj)
{
    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    int32_t fd = fd_of(fd_obj);
    if (fd >= 0) {
        (void)close(fd);
    }
}
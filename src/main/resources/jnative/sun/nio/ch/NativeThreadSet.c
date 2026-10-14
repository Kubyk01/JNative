/*
 * sun.nio.ch.NativeThreadSet — native overrides.
 *
 * The Java-level implementation of NativeThreadSet is a set of
 * (thread-id) entries describing threads currently blocked in I/O on a
 * FileChannel. A thread that is about to enter a potentially blocking
 * I/O operation adds itself with add(); when the operation returns, it
 * removes itself with remove(). When the channel is being closed,
 * FileChannelImpl.implCloseChannel calls signalAndWait(), which walks
 * the set, sends a wake-up to each entry, and then waits until every
 * entry has removed itself:
 *
 *     void signalAndWait() {
 *         synchronized (this) {
 *             if (used > 0) {
 *                 ...
 *                 this.waitingToEmpty = true;
 *                 while (used > 0) {
 *                     try { this.wait(); }
 *                     catch (InterruptedException x) { ... }
 *                 }
 *             }
 *         }
 *     }
 *
 * That design assumes two properties that do not hold in this runtime:
 *
 *   1. That there may be other threads currently blocked on the
 *      channel being closed. The AOT-compiled image runs @main as the
 *      sole Java-level entry point; every thread that the program
 *      might ever have started would appear as an additional native
 *      stack frame. In particular, when the close() call that
 *      triggers signalAndWait is reached from a <clinit> — the
 *      observed case is
 *
 *          JceSecurityManager.<clinit>
 *            -> lazy_clinit_run(JceSecurity)
 *            -> JceSecurity.<clinit>
 *            -> AccessController.doPrivileged
 *            -> JceSecurity$1.run
 *            -> JceSecurity.setupJurisdictionPolicies
 *            -> AbstractInterruptibleChannel.close
 *            -> FileChannelImpl.implCloseChannel
 *            -> NativeThreadSet.signalAndWait
 *
 *      — there is exactly one Java thread alive. No other thread can
 *      be blocked in I/O on the channel being closed, and therefore
 *      no other thread can ever invoke remove() to decrement `used`.
 *
 *   2. That NativeThread.signal() can deliver an asynchronous wake-up
 *      to a thread blocked in a syscall. In this runtime signal() is
 *      a no-op (see jnative/sun/nio/ch/NativeThread.c), because the
 *      blocking primitives implemented in
 *      jnative/sun/nio/ch/UnixFileDispatcherImpl.c and
 *      jnative/sun/nio/ch/SocketDispatcher.c return IOStatus.INTERRUPTED
 *      on EINTR rather than relying on being woken from the outside.
 *      Even if a thread were parked in a blocking read, signaling it
 *      would not cause that read to return early.
 *
 * Together these two facts make the loop condition `used > 0`
 * permanent: the only code that decrements `used` (remove) is reached
 * exclusively from the return path of a blocked I/O operation, and no
 * such operation is in flight. The result is a hard hang inside the
 * static initializer of javax.crypto.JceSecurity, before any user
 * code has run.
 *
 * The override below replaces the entire signal-and-wait dance with an
 * immediate return. That is the correct behaviour for a runtime in
 * which no thread can be blocked on the channel being closed: the
 * method's only job is to wait for such threads, and there are none,
 * so there is nothing to wait for.
 *
 * add() and remove() are deliberately NOT overridden. They are reached
 * from the ordinary read/write/force paths, each add is balanced by a
 * remove in a finally block, and they do not block. Replacing them
 * would change the runtime's bookkeeping for no benefit. Only
 * signalAndWait, whose entire body is the problematic wait loop, needs
 * to be neutralised.
 *
 * The C symbol name follows NativeOverrideScanner's convention:
 *
 *     __jnative_override_<mangled-class>_<method>
 *
 * where the mangled class uses '_' in place of '/'. For
 * sun.nio.ch.NativeThreadSet the mangled class is
 * sun_nio_ch_NativeThreadSet, and the full symbol is
 * __jnative_override_sun_nio_ch_NativeThreadSet_signalAndWait.
 *
 * The single parameter `self` receives the NativeThreadSet instance as
 * its receiver. It is not consulted: the override ignores the set's
 * contents entirely, because there is never anything in it that needs
 * to be waited for.
 */

#define _GNU_SOURCE
#include <stdint.h>

#include "jnative_runtime.h"

void __jnative_override_sun_nio_ch_NativeThreadSet_signalAndWait(void* self) {
    (void)self;
}
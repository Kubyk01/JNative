#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>

#include "jnative_runtime.h"

/*
 * java.lang.Shutdown native methods.
 *
 * The JDK's Java layer invokes these two natives as the very last steps
 * of JVM termination:
 *
 *   Shutdown.exit(status) -> runHooks() -> Shutdown.halt(status)
 *       -> beforeHalt(); halt0(status);
 *
 * halt0 is the point of no return: after it is entered, the process
 * must terminate with the given status without running any more Java
 * code. beforeHalt is a pre-halt notification hook that HotSpot uses
 * to let the VM install its own shutdown state on top of whatever the
 * Java layer already did.
 *
 * Both names are declared `native` on java.lang.Shutdown, so both
 * symbols must exist in the final executable. Neither participates in
 * this runtime's stack-trace machinery; the generated code has no
 * debug prologues registered with an external unwinder, and the
 * runtime itself uses its own signal handlers for crash reporting.
 * Consequently:
 *
 *   - halt0 simply forwards to the C library's exit(3). exit() runs
 *     atexit handlers, which is exactly what is needed to invoke the
 *     __jnative_shutdown function that LlvmGenerator registers at
 *     program start-up (see generateMain()). It then flushes stdio
 *     and terminates the process with the requested status.
 *
 *   - beforeHalt is a no-op. The reference implementation's work
 *     (releasing JVMTI resources, unparking threads, etc.) is
 *     specific to HotSpot's internal state and has no analogue in a
 *     native image where every allocation is a libc malloc and every
 *     thread is a plain pthread.
 *
 * Note on exit vs. _exit: this runtime deliberately uses exit() rather
 * than _exit() so that the atexit-registered __jnative_shutdown runs
 * and releases objects held in static fields. _exit() would skip that
 * step and leak every still-reachable object, which is harmless for
 * the process image but makes leak-checking harder during
 * development.
 */

/*
 * static native void halt0(int status);
 *
 * Terminates the running JVM image with the supplied exit status. Runs
 * atexit handlers first (notably __jnative_shutdown) so that any
 * objects still rooted in static fields are released through the
 * generated destructor chains.
 *
 * The explicit abort() after exit() is defensive: exit(3) is declared
 * noreturn, and every compiler this runtime targets honours that
 * attribute, but a hostile build configuration could in principle
 * miss it. The abort() makes the noreturn contract unconditional,
 * which matters because Java callers expect halt0 never to return.
 */
void __jnative_fn_java_lang_Shutdown_halt0__I_V(int32_t status) {
    exit((int)status);
    abort();
}

/*
 * static native void beforeHalt();
 *
 * Pre-halt notification. In HotSpot this hook lets the VM flush its
 * own internal state (JVMTI events, thread stacks) before the process
 * image is torn down. This runtime has no such state: all bookkeeping
 * lives in the compiled module itself, which is destroyed along with
 * the process. The call is a strict no-op.
 */
void __jnative_fn_java_lang_Shutdown_beforeHalt___V(void) {
}
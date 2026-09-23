#include <stddef.h>
#include <stdint.h>

#include "jnative_runtime.h"

/*
 * java.lang.Throwable native methods.
 *
 * In this runtime there is no JVM-level stack-trace machinery: unwinding
 * is performed by the C runtime and reported through the
 * __jnative_throw_*_ctx helpers (see jnative_runtime.c). A Throwable
 * instance is a plain object whose `detailMessage` field is the only
 * interesting piece of state — there is nowhere to store a
 * StackTraceElement[] array, and no walker to fill one.
 *
 * The contract of Throwable.fillInStackTrace is therefore satisfied by
 * returning the receiver unchanged. This is exactly what HotSpot itself
 * does when stack-trace capture is disabled
 * (-XX:-StackTraceInThrowable or a Throwable subclass that overrides
 * fillInStackTrace), and it makes every `new SomeException(...)` in
 * generated code complete without touching any uninitialised memory.
 *
 * getStackTraceDepth / getStackTraceElement are declared private native
 * in Throwable and are called by Throwable.getOurStackTrace() during
 * printStackTrace(). They are provided here so that any code path
 * reaching them (for example a user-supplied exception handler that
 * calls printStackTrace) links and returns a well-defined empty stack
 * instead of dereferencing a NULL vtable slot.
 */

/*
 * private native Throwable fillInStackTrace(int dummy);
 *
 * No-op that returns the receiver. The `dummy` argument is a JDK
 * implementation detail (it selects between "capture the full trace"
 * and "capture nothing", a distinction that has no meaning in a
 * runtime with no capture machinery at all); it is deliberately
 * unused here.
 */
void* __jnative_fn_java_lang_Throwable_fillInStackTrace__I_Ljava_lang_Throwable_(
        void* this_throwable, int32_t dummy) {
    (void)dummy;
    return this_throwable;
}

/*
 * private native int getStackTraceDepth();
 *
 * Reports the number of stack frames captured at construction time.
 * This runtime never captures any, so the answer is always 0. The
 * Java-side caller (Throwable.getOurStackTrace) short-circuits on a
 * zero depth and does not touch the companion getStackTraceElement
 * below.
 */
int32_t __jnative_fn_java_lang_Throwable_getStackTraceDepth___I(
        void* this_throwable) {
    (void)this_throwable;
    return 0;
}

/*
 * private native StackTraceElement getStackTraceElement(int index);
 *
 * Would return the stack frame at the given depth. Since the depth is
 * always reported as 0 by getStackTraceDepth above, this entry point
 * is unreachable in practice — but it must still exist because
 * Throwable's compiled bytecode emits a direct call to it. Returning
 * NULL is the documented "no frame at this index" answer, which is
 * the only truthful response for a runtime without a captured stack.
 */
void* __jnative_fn_java_lang_Throwable_getStackTraceElement__I_Ljava_lang_StackTraceElement_(
        void* this_throwable, int32_t index) {
    (void)this_throwable;
    (void)index;
    return NULL;
}

/*
 * private static native void registerNatives();
 *
 * Called from Throwable.<clinit>. This runtime resolves every native
 * method through its statically-linked
 * __jnative_fn_<class>_<method>_<desc> symbol emitted by the LLVM
 * backend, so there is nothing to register. The symbol must exist
 * because Throwable.<clinit> emits a native call to it.
 */
void __jnative_fn_java_lang_Throwable_registerNatives___V(void) {
}
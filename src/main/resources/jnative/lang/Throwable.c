#define _GNU_SOURCE
#include <stddef.h>
#include <stdint.h>

/*
 * Throwable native methods.
 *
 * In this runtime there is no JVM-level stack-trace machinery: unwinding is
 * performed by the C runtime and reported through the __jnative_throw_*_ctx
 * helpers (see jnative_runtime.c). A Throwable instance is a plain object
 * whose `detailMessage` field is the only interesting piece of state — there
 * is nowhere to store a StackTraceElement[] array, and no walker to fill one.
 *
 * The contract of Throwable.fillInStackTrace is therefore satisfied by
 * returning the receiver unchanged. This is exactly what HotSpot itself does
 * when stack-trace capture is disabled (-XX:-StackTraceInThrowable or a
 * Throwable subclass that overrides fillInStackTrace), and it makes every
 * `new SomeException(...)` in generated code complete without touching any
 * uninitialised memory.
 *
 * getStackTraceDepth / getStackTraceElement are declared private native in
 * Throwable and are called by Throwable.getOurStackTrace() during
 * printStackTrace(). They are provided here so that any code path reaching
 * them (e.g. a user-supplied exception handler that calls printStackTrace)
 * links and returns a well-defined empty stack instead of dereferencing a
 * NULL vtable slot.
 */

/* ---- private native Throwable fillInStackTrace(int dummy); ---------------- */
void* __jnative_fn_java_lang_Throwable_fillInStackTrace__I_Ljava_lang_Throwable_(
        void* this_throwable, int32_t dummy) {
    (void)dummy;
    return this_throwable;
}

/* ---- private native int getStackTraceDepth(); ----------------------------- */
int32_t __jnative_fn_java_lang_Throwable_getStackTraceDepth___I(
        void* this_throwable) {
    (void)this_throwable;
    return 0;
}

/* ---- private native StackTraceElement getStackTraceElement(int index); --- */
void* __jnative_fn_java_lang_Throwable_getStackTraceElement__I_Ljava_lang_StackTraceElement_(
        void* this_throwable, int32_t index) {
    (void)this_throwable;
    (void)index;
    return NULL;
}

/*
 * private static native void registerNatives();
 *
 * Called from Throwable.<clinit>. This runtime resolves every native method
 * through its statically-linked __jnative_fn_<class>_<method>_<desc> symbol
 * emitted by the LLVM backend, so there is nothing to register. The symbol
 * must exist because Throwable.<clinit> emits a native call to it.
 */
void __jnative_fn_java_lang_Throwable_registerNatives___V(void) {
}
#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <string.h>

__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);

/*
 * java.lang.StackTraceElement — native support for StackWalker frame
 * materialisation.
 *
 * Two entry points exist:
 *
 *   initStackTraceElements(StackTraceElement[] stes,
 *                          Object backtrace,
 *                          int depth)
 *
 *     Bulk-fills an array of StackTraceElement objects from a JVM-side
 *     backtrace record. Called by Throwable.getOurStackTrace and by the
 *     StackWalker traversal when it collects frames in batches.
 *
 *   initStackTraceElement(StackTraceElement ste,
 *                         StackFrameInfo frame)
 *
 *     Copies the field values of a single StackFrameInfo into a single
 *     StackTraceElement. Called from StackFrameInfo.toStackTraceElement()
 *     and from StackTraceElement.of(StackFrameInfo), which are the two
 *     public entry points for turning a live stack frame into a public
 *     element.
 *
 * Both natives are dead in this runtime, for the same reason: the
 * generated code has no per-frame metadata registered with any stack
 * walker. The JVM-side backtrace record that the bulk variant consumes
 * does not exist, and the StackFrameInfo instances that the single
 * variant consumes are only ever produced by a successful walk of the
 * native stack — a walk that StackStreamFactory.AbstractStackWalker's
 * native hooks refuse to perform (see the corresponding stubs in
 * StackStreamFactory.c, which unconditionally return "no frames").
 *
 * The single-variant native is nevertheless emitted as a direct call
 * from StackFrameInfo and StackTraceElement because both are reachable
 * at the class level (their <clinit> and constructors are pulled into
 * the module by the reachability analysis). The linker therefore needs
 * the symbol to exist even though no call site will ever execute it at
 * runtime.
 *
 * Leaving the target StackTraceElement at its Java-level zero value —
 * null declaringClass, null methodName, null fileName, 0 lineNumber — is
 * the truthful answer for a runtime that cannot see the native stack.
 * It matches the empty-frame walk that every other StackWalker entry
 * point in this runtime already produces, and it avoids the version
 * brittleness of hand-decoding the StackFrameInfo field layout (whose
 * field order and set have changed between JDK releases, and which is
 * computed by the LLVM backend from each individual build's class file).
 */

/* --------------------------------------------------------------------------
 * private static native void initStackTraceElement(StackTraceElement ste,
 *                                                  StackFrameInfo frame);
 *
 * No-op after the null check. A null receiver is a genuine programming
 * error at the Java level — the call sites both allocate a fresh
 * StackTraceElement immediately before invoking the native — so it must
 * still surface as an NPE rather than a silent success.
 * ------------------------------------------------------------------------ */
void __jnative_fn_java_lang_StackTraceElement_initStackTraceElement__Ljava_lang_StackTraceElement_Ljava_lang_StackFrameInfo__V(
        void* ste, void* frame)
{
    if (ste == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    (void)frame;
    /* Intentionally empty: the runtime cannot reconstruct a StackFrameInfo
     * from native stack metadata, so there is nothing to copy. */
}

/* --------------------------------------------------------------------------
 * private static native void initStackTraceElements(StackTraceElement[] stes,
 *                                                   Object backtrace,
 *                                                   int depth);
 *
 * No-op. Called from Throwable.getOurStackTrace and the batch-collection
 * path of StackWalker; both are reached only after a successful
 * VM-side backtrace capture, which this runtime never performs.
 * ------------------------------------------------------------------------ */
void __jnative_fn_java_lang_StackTraceElement_initStackTraceElements___Ljava_lang_StackTraceElement_Ljava_lang_Object_I_V(
        void* stes,
        void* backtrace,
        int32_t depth)
{
    (void)stes;
    (void)backtrace;
    (void)depth;
}
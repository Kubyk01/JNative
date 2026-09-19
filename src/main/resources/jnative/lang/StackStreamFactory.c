#include <stdint.h>

/*
 * java.lang.StackStreamFactory.checkStackWalkModes() -> boolean
 *
 * HotSpot-specific check that tells StackWalker whether the VM annotates
 * individual frames with a walk-mode (visible vs. hidden). This runtime
 * does not maintain per-frame walk-mode metadata: the stack is unwound
 * through backtrace()/frame-pointers with no VM-side frame tagging, so
 * only WALK_ALL_FRAMES is meaningful. Returning false makes the caller
 * fall back to that mode.
 */
int32_t __jnative_fn_java_lang_StackStreamFactory_checkStackWalkModes___Z(void) {
    return 0;
}

/* =========================================================================
 * java.lang.StackStreamFactory$AbstractStackWalker
 *
 * The three natives below are the VM hooks that StackWalker uses to pull
 * frames off the running thread. The reference implementation walks the
 * native stack inside `callStackWalk`, invoking `fetchStackFrames` in
 * batches and calling back into Java through the enclosing
 * AbstractStackWalker.
 *
 * This runtime does not maintain VM-level frame metadata (the generated
 * code has no debug-info prologues registered with a stack walker), so
 * the walk cannot be performed faithfully. The chosen contract is the
 * one every JDK caller is already prepared to handle: return "no
 * frames". In the Java source that means:
 *
 *   - fetchStackFrames returns 0  -> "batch is empty, walk is done"
 *   - setContinuation does nothing -> there is no continuation to store
 *   - callStackWalk returns NULL  -> the walker's callback result is null,
 *                                    which StackFrameTraverser.tryAdvance
 *                                    interprets as "no more frames"
 *
 * This produces an empty StackWalker walk, which is the only truthful
 * answer for a runtime without VM stack-frame bookkeeping.
 * ========================================================================= */

/*
 * static native Object callStackWalk(long anchor,
 *                                    int mode,
 *                                    ContinuationScope contScope,
 *                                    Continuation continuation,
 *                                    int batchSize,
 *                                    int startIndex,
 *                                    Object[] frames);
 */
void* __jnative_fn_java_lang_StackStreamFactory_AbstractStackWalker_callStackWalk__JILjdk_internal_vm_ContinuationScope_Ljdk_internal_vm_Continuation_II_Ljava_lang_Object__Ljava_lang_Object_(
        int64_t anchor,
        int32_t mode,
        void*   contScope,
        void*   continuation,
        int32_t batchSize,
        int32_t startIndex,
        void*   frames)
{
    (void)anchor;
    (void)mode;
    (void)contScope;
    (void)continuation;
    (void)batchSize;
    (void)startIndex;
    (void)frames;
    return (void*)0;
}

/*
 * static native int fetchStackFrames(long anchor,
 *                                    long mode,
 *                                    int startIndex,
 *                                    int batchSize,
 *                                    Object[] frames);
 */
int32_t __jnative_fn_java_lang_StackStreamFactory_AbstractStackWalker_fetchStackFrames__JJII_Ljava_lang_Object__I(
        int64_t anchor,
        int64_t mode,
        int32_t startIndex,
        int32_t batchSize,
        void*   frames)
{
    (void)anchor;
    (void)mode;
    (void)startIndex;
    (void)batchSize;
    (void)frames;
    return 0;
}

/*
 * static native void setContinuation(long anchor,
 *                                    Object[] frames,
 *                                    Continuation continuation);
 */
void __jnative_fn_java_lang_StackStreamFactory_AbstractStackWalker_setContinuation__J_Ljava_lang_Object_Ljdk_internal_vm_Continuation__V(
        int64_t anchor,
        void*   frames,
        void*   continuation)
{
    (void)anchor;
    (void)frames;
    (void)continuation;
}
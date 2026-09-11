#include <stdint.h>
#include <stddef.h>

/*
 * jnative runtime has no Java-level call stack. Java methods are lowered to
 * native functions whose frames carry no Java metadata, so there is nothing
 * to report to StackWalker. Returning 0 (zero frames fetched) is the correct
 * behaviour: StackWalker.walk() will deliver an empty stream.
 *
 * If a future version of the runtime records Java-level frame information
 * (e.g. via libunwind + debug info), this function should be replaced by a
 * walk that consults that metadata.
 */
int32_t __jnative_fn_java_lang_StackStreamFactory_AbstractStackWalker_fetchStackFrames__JJII_Ljava_lang_Object__I(
        void* self,
        int64_t mode,
        int64_t anchor,
        int32_t startIndex,
        int32_t maxFrames,
        void* array)
{
    (void)self; (void)mode; (void)anchor;
    (void)startIndex; (void)maxFrames; (void)array;
    return 0;
}

/*
 * Fills the walker's internal state so that a subsequent
 * callStackWalk()/fetchStackFrames() pair can proceed. With no Java frames
 * present there is nothing to prepare; the walker's state is already the
 * empty state.
 */
void* __jnative_fn_java_lang_StackStreamFactory_AbstractStackWalker_callStackWalk__JILjdk_internal_vm_ContinuationScope_Ljdk_internal_vm_Continuation_II_Ljava_lang_Object__Ljava_lang_Object_(
        void* self,
        int64_t mode,
        int32_t skipFrames,
        void* contScope,
        void* continuation,
        int32_t batchSize,
        int32_t startIndex,
        void* frameBuffer)
{
    (void)self; (void)mode; (void)skipFrames; (void)contScope;
    (void)continuation; (void)batchSize; (void)startIndex; (void)frameBuffer;
    /*
     * There are no Java frames; the walk is complete on the first call.
     * Returning NULL signals "end of stack" to the caller, which is exactly
     * what a JVM returns when the walk has produced zero frames.
     */
    return NULL;
}

/*
 * Continuations are not implemented in this runtime (there are no virtual
 * threads). Setting a continuation on the walker is a no-op — the walker
 * simply never walks any continuation.
 */
void __jnative_fn_java_lang_StackStreamFactory_AbstractStackWalker_setContinuation__J_Ljava_lang_Object_Ljdk_internal_vm_Continuation__V(
        void* self,
        int64_t mode,
        void* anchor,
        void* continuation)
{
    (void)self; (void)mode; (void)anchor; (void)continuation;
}
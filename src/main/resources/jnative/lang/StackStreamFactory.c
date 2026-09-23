#include <stdint.h>

#include "jnative_runtime.h"

int32_t __jnative_fn_java_lang_StackStreamFactory_checkStackWalkModes___Z(void) {
    return 1;
}

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

void __jnative_fn_java_lang_StackStreamFactory_AbstractStackWalker_setContinuation__J_Ljava_lang_Object_Ljdk_internal_vm_Continuation__V(
        int64_t anchor,
        void*   frames,
        void*   continuation)
{
    (void)anchor;
    (void)frames;
    (void)continuation;
}
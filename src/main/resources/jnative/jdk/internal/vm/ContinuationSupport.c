#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

int32_t __jnative_fn_jdk_internal_vm_ContinuationSupport_isSupported0___Z(void) {
    return 1;
}

void __jnative_fn_jdk_internal_vm_Continuation_registerNatives___V(void) {
}

int32_t __jnative_fn_jdk_internal_vm_Continuation_doYield___I(int32_t mode) {
    const char* op;
    switch (mode) {
        case 0:  op = "mount"; break;
        case 1:  op = "yield"; break;
        case 2:  op = "run";   break;
        case 3:  op = "done";  break;
        default: op = "unknown"; break;
    }

    char msg[384];
    snprintf(msg, sizeof(msg),
        "jdk.internal.vm.Continuation.%s (mode=%d) is not implemented in "
        "this runtime: JNative has no StackChunk freeze/thaw mechanism. "
        "Virtual threads and structured-concurrency scopes require it and "
        "cannot be used on this build.",
        op, (int)mode);

    void* exc = __jnative_construct_exception(
        "vtable_java_lang_UnsupportedOperationException", msg);

    __jnative_throw_exception(exc);

    return 0;
}
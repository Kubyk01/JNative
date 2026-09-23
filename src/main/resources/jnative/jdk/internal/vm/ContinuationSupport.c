#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

int32_t __jnative_fn_jdk_internal_vm_ContinuationSupport_isSupported0___Z(void) {
    return 0;
}

void __jnative_fn_jdk_internal_vm_Continuation_registerNatives___V(void) {
}

int32_t __jnative_fn_jdk_internal_vm_Continuation_doYield___I(int32_t mode) {
    (void)mode;
    __jnative_throw_exception(NULL);
    /* __jnative_throw_exception is declared noreturn, but the explicit
     * return keeps the compiler happy on toolchains that do not honour
     * the attribute in every optimisation mode. */
    return 0;
}
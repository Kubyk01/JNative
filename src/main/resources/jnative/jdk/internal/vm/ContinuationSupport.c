#include <stdint.h>

/* static native boolean isSupported0();
 * This runtime does not implement continuations / virtual threads, so the
 * only correct answer is false. Callers fall back to the platform-thread
 * implementation. */
int32_t __jnative_fn_jdk_internal_vm_ContinuationSupport_isSupported0___Z(void) {
    return 0;
}
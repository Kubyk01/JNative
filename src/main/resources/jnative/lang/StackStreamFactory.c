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
#include "jnative_platform.h"
#include "jnative_runtime.h"
#include "jnative_portability.h"

extern int jnative_stack_frame_expand(void* sfi) JNATIVE_WEAK;

void __jnative_fn_java_lang_StackFrameInfo_expandStackFrameInfo___V(void* this_obj) {
    if (this_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    if (jnative_stack_frame_expand == NULL || !jnative_stack_frame_expand(this_obj)) {
        __jnative_throw_exception(__jnative_construct_exception(
            "vtable_java_lang_InternalError",
            "StackFrameInfo: frame was not filled by a stack walk"));
    }
}

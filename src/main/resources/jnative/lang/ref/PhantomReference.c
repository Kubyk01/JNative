#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stddef.h>
#include <stdint.h>

#include "jnative_runtime.h"

int32_t __jnative_fn_java_lang_ref_PhantomReference_refersTo0__Ljava_lang_Object__Z(
        void* this_ref, void* o)
{
    if (this_ref == NULL) return 0;
    return __atomic_load_n(__jnative_referent_slot(this_ref), __ATOMIC_ACQUIRE) == o;
}
void __jnative_fn_java_lang_ref_PhantomReference_clear0___V(void* this_ref) {
    if (this_ref == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __jnative_weak_store(__jnative_referent_slot(this_ref), NULL);
}

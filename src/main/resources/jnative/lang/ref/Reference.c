#include "jnative_platform.h"
#include <stdint.h>

#include "jnative_runtime.h"

static jn_mutex_t reference_wait_mutex = JN_MUTEX_INITIALIZER;
static jn_cond_t  reference_wait_cond  = JN_COND_INITIALIZER;

int32_t __jnative_fn_java_lang_ref_Reference_refersTo0__Ljava_lang_Object__Z(
        void* this_ref, void* o)
{
    if (this_ref == NULL) return 0;
    return __atomic_load_n(__jnative_referent_slot(this_ref), __ATOMIC_ACQUIRE) == o;
}

void* __jnative_fn_java_lang_ref_Reference_get0___Ljava_lang_Object_(void* this_ref) {
    if (this_ref == NULL) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }
    return __jnative_weak_load(__jnative_referent_slot(this_ref), __jnative_rc_ret_slot());
}

void __jnative_fn_java_lang_ref_Reference_clear0___V(void* this_ref) {
    if (this_ref == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    __jnative_weak_store(__jnative_referent_slot(this_ref), NULL);
}

int32_t __jnative_fn_java_lang_ref_Reference_hasReferencePendingList___Z(void) {
    return 0;
}

void __jnative_fn_java_lang_ref_Reference_waitForReferencePendingList___V(void) {
    jn_mutex_lock(&reference_wait_mutex);
    jn_cond_wait(&reference_wait_cond, &reference_wait_mutex);
    jn_mutex_unlock(&reference_wait_mutex);
}

void* __jnative_fn_java_lang_ref_Reference_getAndClearReferencePendingList___Ljava_lang_ref_Reference_(void) {
    return NULL;
}
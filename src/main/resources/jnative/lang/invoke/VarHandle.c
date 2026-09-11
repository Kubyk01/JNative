#include <stdint.h>
#include <string.h>
#include <stdlib.h>

// Forward declarations for runtime exception helpers
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);
__attribute__((noreturn)) void __jnative_throw_array_index_out_of_bounds(void);
// todo implement
void __jnative_fn_java_lang_invoke_VarHandle_get___V_V(void **args) {
    // stub
}

int __jnative_fn_java_lang_invoke_VarHandle_get___V_I(void **args) {
    // stub
    return 0;
}

int __jnative_fn_java_lang_invoke_VarHandle_get___BI_I(uint8_t* array, int index) {
    // stub
    return 0;
}

int32_t
fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_atomic_AtomicReference_Ljava_lang_Object_Ljava_lang_Object__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;               /* the VarHandle instance is not needed */
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    /* AtomicReference.value lives immediately after the vtable slot. */
    void** value_slot = (void**)((char*)obj + 8);

    /* __atomic_compare_exchange_n updates its expected pointer in place on
     * failure, so use a mutable local copy. */
    void* exp = expected;
    int ok = __atomic_compare_exchange_n(value_slot, &exp, newValue, 0,
                                         __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return ok ? 1 : 0;
}

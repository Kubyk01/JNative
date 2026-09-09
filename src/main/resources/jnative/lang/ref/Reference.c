#include <stdint.h>
#include <stdlib.h>
#include <string.h>

__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);

/* OBJECT_HEADER_SIZE = 8 (vtable), then first field = referent */
#define OBJECT_HEADER_SIZE 8
#define REFERENT_OFFSET    OBJECT_HEADER_SIZE

static inline void** referent_slot(void* ref) {
    return (void**)((char*)ref + REFERENT_OFFSET);
}

/* ---------- boolean refersTo0(Object o) ---------- */
int32_t __jnative_fn_java_lang_ref_Reference_refersTo0__Ljava_lang_Object__Z(
        void* this_ref, void* o)
{
    if (this_ref == NULL) return 0;
    return (*referent_slot(this_ref) == o) ? 1 : 0;
}

/* ---------- void clear0() ---------- */
void __jnative_fn_java_lang_ref_Reference_clear0___V(void* this_ref) {
    if (this_ref == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    *referent_slot(this_ref) = NULL;
}
#define _GNU_SOURCE
#include <stddef.h>
#include <stdint.h>

__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);

/*
 * java.lang.ref.PhantomReference — native `refersTo0`.
 *
 * PhantomReference inherits `refersTo0(Object)` from java.lang.ref.Reference
 * but, unlike the soft/weak variants, the JDK declares it as a native method
 * on PhantomReference itself so that the VM can route the query through the
 * same reference-processing machinery it uses for reachability. This runtime
 * has no reference processor: every Reference is a plain object whose
 * `referent` field lives at the same offset as in java.lang.ref.Reference
 * (vtable at 0, first instance field at OBJECT_HEADER_SIZE).
 *
 * The semantics we implement are identical to
 * java.lang.ref.Reference.refersTo0: return true iff the referent currently
 * stored in the receiver equals the argument. Because no GC ever clears the
 * referent in this runtime, a PhantomReference that was constructed with a
 * non-null referent continues to refer to it forever — which is the correct
 * behaviour for a runtime without a garbage collector.
 */
#define OBJECT_HEADER_SIZE 8
#define REFERENT_OFFSET    OBJECT_HEADER_SIZE

static inline void** referent_slot(void* ref) {
    return (void**)((char*)ref + REFERENT_OFFSET);
}

int32_t __jnative_fn_java_lang_ref_PhantomReference_refersTo0__Ljava_lang_Object__Z(
        void* this_ref, void* o)
{
    if (this_ref == NULL) return 0;
    return (*referent_slot(this_ref) == o) ? 1 : 0;
}
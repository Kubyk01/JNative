#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <stdatomic.h>

/* ===========================================================================
 * Forward declarations for runtime exception helpers (jnative_runtime.c)
 * ========================================================================= */
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);
__attribute__((noreturn)) void __jnative_throw_array_index_out_of_bounds(void);

/* ===========================================================================
 * Java array layout used by this runtime:
 *     [ int32 length ][ elements... ]
 * (see NEW_ARRAY in LlvmFunctionEmitter and __jnative_create_string_array)
 * ========================================================================= */
#define JAVA_ARR_HDR 4

static inline uint8_t* barray_data(void* arr) {
    return (uint8_t*)arr + JAVA_ARR_HDR;
}

static inline int32_t barray_length(void* arr) {
    return *(int32_t*)arr;
}

static inline void barray_check(void* arr, int32_t index, int32_t elem_size) {
    if (arr == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int32_t len = barray_length(arr);
    if (index < 0 || index + elem_size > len) {
        __jnative_throw_array_index_out_of_bounds();
    }
}

/* Little-endian native byte order on x86_64 - memcpy gives the right result. */

/* ===========================================================================
 *  get: byte[] @ int  ->  { byte, short, char, int, long, float, double }
 * ========================================================================= */

int8_t __jnative_fn_java_lang_invoke_VarHandle_get___BI_B(void* arr, int32_t index) {
    barray_check(arr, index, 1);
    return *(int8_t*)(barray_data(arr) + index);
}

int16_t __jnative_fn_java_lang_invoke_VarHandle_get___BI_S(void* arr, int32_t index) {
    barray_check(arr, index, 2);
    int16_t v;
    memcpy(&v, barray_data(arr) + index, 2);
    return v;
}

uint16_t __jnative_fn_java_lang_invoke_VarHandle_get___BI_C(void* arr, int32_t index) {
    barray_check(arr, index, 2);
    uint16_t v;
    memcpy(&v, barray_data(arr) + index, 2);
    return v;
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_get___BI_I(void* arr, int32_t index) {
    barray_check(arr, index, 4);
    int32_t v;
    memcpy(&v, barray_data(arr) + index, 4);
    return v;
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_get___BI_J(void* arr, int32_t index) {
    barray_check(arr, index, 8);
    int64_t v;
    memcpy(&v, barray_data(arr) + index, 8);
    return v;
}

float __jnative_fn_java_lang_invoke_VarHandle_get___BI_F(void* arr, int32_t index) {
    barray_check(arr, index, 4);
    float v;
    memcpy(&v, barray_data(arr) + index, 4);
    return v;
}

double __jnative_fn_java_lang_invoke_VarHandle_get___BI_D(void* arr, int32_t index) {
    barray_check(arr, index, 8);
    double v;
    memcpy(&v, barray_data(arr) + index, 8);
    return v;
}

/* ===========================================================================
 *  set: byte[] @ int <- value
 * ========================================================================= */

void __jnative_fn_java_lang_invoke_VarHandle_set___BIB_V(void* arr, int32_t index, int8_t v) {
    barray_check(arr, index, 1);
    *(int8_t*)(barray_data(arr) + index) = v;
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BIS_V(void* arr, int32_t index, int16_t v) {
    barray_check(arr, index, 2);
    memcpy(barray_data(arr) + index, &v, 2);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BIC_V(void* arr, int32_t index, uint16_t v) {
    barray_check(arr, index, 2);
    memcpy(barray_data(arr) + index, &v, 2);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BII_V(void* arr, int32_t index, int32_t v) {
    barray_check(arr, index, 4);
    memcpy(barray_data(arr) + index, &v, 4);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BIJ_V(void* arr, int32_t index, int64_t v) {
    barray_check(arr, index, 8);
    memcpy(barray_data(arr) + index, &v, 8);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BIF_V(void* arr, int32_t index, float v) {
    barray_check(arr, index, 4);
    memcpy(barray_data(arr) + index, &v, 4);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BID_V(void* arr, int32_t index, double v) {
    barray_check(arr, index, 8);
    memcpy(barray_data(arr) + index, &v, 8);
}

/* ===========================================================================
 *  Volatile access modes (sequential consistency)
 * ========================================================================= */

int32_t __jnative_fn_java_lang_invoke_VarHandle_getVolatile___BI_I(void* arr, int32_t index) {
    barray_check(arr, index, 4);
    int32_t v;
    __atomic_load((int32_t*)(barray_data(arr) + index), &v, __ATOMIC_SEQ_CST);
    return v;
}

void __jnative_fn_java_lang_invoke_VarHandle_setVolatile___BII_V(void* arr, int32_t index, int32_t v) {
    barray_check(arr, index, 4);
    __atomic_store((int32_t*)(barray_data(arr) + index), &v, __ATOMIC_SEQ_CST);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getVolatile___BI_J(void* arr, int32_t index) {
    barray_check(arr, index, 8);
    int64_t v;
    __atomic_load((int64_t*)(barray_data(arr) + index), &v, __ATOMIC_SEQ_CST);
    return v;
}

void __jnative_fn_java_lang_invoke_VarHandle_setVolatile___BIJ_V(void* arr, int32_t index, int64_t v) {
    barray_check(arr, index, 8);
    __atomic_store((int64_t*)(barray_data(arr) + index), &v, __ATOMIC_SEQ_CST);
}

/* ===========================================================================
 *  Acquire / Release access modes
 * ========================================================================= */

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAcquire___BI_I(void* arr, int32_t index) {
    barray_check(arr, index, 4);
    int32_t v;
    __atomic_load((int32_t*)(barray_data(arr) + index), &v, __ATOMIC_ACQUIRE);
    return v;
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease___BII_V(void* arr, int32_t index, int32_t v) {
    barray_check(arr, index, 4);
    __atomic_store((int32_t*)(barray_data(arr) + index), &v, __ATOMIC_RELEASE);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getAcquire___BI_J(void* arr, int32_t index) {
    barray_check(arr, index, 8);
    int64_t v;
    __atomic_load((int64_t*)(barray_data(arr) + index), &v, __ATOMIC_ACQUIRE);
    return v;
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease___BIJ_V(void* arr, int32_t index, int64_t v) {
    barray_check(arr, index, 8);
    __atomic_store((int64_t*)(barray_data(arr) + index), &v, __ATOMIC_RELEASE);
}

/* ===========================================================================
 *  Opaque access modes (relaxed)
 * ========================================================================= */

int32_t __jnative_fn_java_lang_invoke_VarHandle_getOpaque___BI_I(void* arr, int32_t index) {
    barray_check(arr, index, 4);
    int32_t v;
    __atomic_load((int32_t*)(barray_data(arr) + index), &v, __ATOMIC_RELAXED);
    return v;
}

void __jnative_fn_java_lang_invoke_VarHandle_setOpaque___BII_V(void* arr, int32_t index, int32_t v) {
    barray_check(arr, index, 4);
    __atomic_store((int32_t*)(barray_data(arr) + index), &v, __ATOMIC_RELAXED);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getOpaque___BI_J(void* arr, int32_t index) {
    barray_check(arr, index, 8);
    int64_t v;
    __atomic_load((int64_t*)(barray_data(arr) + index), &v, __ATOMIC_RELAXED);
    return v;
}

void __jnative_fn_java_lang_invoke_VarHandle_setOpaque___BIJ_V(void* arr, int32_t index, int64_t v) {
    barray_check(arr, index, 8);
    __atomic_store((int64_t*)(barray_data(arr) + index), &v, __ATOMIC_RELAXED);
}

/* ===========================================================================
 *  compareAndSet / weakCompareAndSet: int and long on byte[]
 * ========================================================================= */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet___BIII_Z(
        void* arr, int32_t index, int32_t expected, int32_t newValue) {
    barray_check(arr, index, 4);
    int32_t exp = expected;
    return __atomic_compare_exchange_n((int32_t*)(barray_data(arr) + index),
                                       &exp, newValue, 0,
                                       __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet___BIJJ_Z(
        void* arr, int32_t index, int64_t expected, int64_t newValue) {
    barray_check(arr, index, 8);
    int64_t exp = expected;
    return __atomic_compare_exchange_n((int64_t*)(barray_data(arr) + index),
                                       &exp, newValue, 0,
                                       __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet___BIII_Z(
        void* arr, int32_t index, int32_t expected, int32_t newValue) {
    return __jnative_fn_java_lang_invoke_VarHandle_compareAndSet___BIII_Z(
        arr, index, expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet___BIJJ_Z(
        void* arr, int32_t index, int64_t expected, int64_t newValue) {
    return __jnative_fn_java_lang_invoke_VarHandle_compareAndSet___BIJJ_Z(
        arr, index, expected, newValue);
}

/* ===========================================================================
 *  compareAndExchange: returns the witness value
 * ========================================================================= */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange___BIII_I(
        void* arr, int32_t index, int32_t expected, int32_t newValue) {
    barray_check(arr, index, 4);
    int32_t witness = expected;
    __atomic_compare_exchange_n((int32_t*)(barray_data(arr) + index),
                                &witness, newValue, 0,
                                __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange___BIJJ_J(
        void* arr, int32_t index, int64_t expected, int64_t newValue) {
    barray_check(arr, index, 8);
    int64_t witness = expected;
    __atomic_compare_exchange_n((int64_t*)(barray_data(arr) + index),
                                &witness, newValue, 0,
                                __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}

/* ===========================================================================
 *  getAndSet / getAndAdd
 * ========================================================================= */

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAndSet___BII_I(
        void* arr, int32_t index, int32_t newValue) {
    barray_check(arr, index, 4);
    int32_t old;
    __atomic_exchange((int32_t*)(barray_data(arr) + index), &newValue, &old,
                      __ATOMIC_SEQ_CST);
    return old;
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getAndSet___BIJ_J(
        void* arr, int32_t index, int64_t newValue) {
    barray_check(arr, index, 8);
    int64_t old;
    __atomic_exchange((int64_t*)(barray_data(arr) + index), &newValue, &old,
                      __ATOMIC_SEQ_CST);
    return old;
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAndAdd___BII_I(
        void* arr, int32_t index, int32_t delta) {
    barray_check(arr, index, 4);
    return __atomic_fetch_add((int32_t*)(barray_data(arr) + index), delta,
                              __ATOMIC_SEQ_CST);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getAndAdd___BIJ_J(
        void* arr, int32_t index, int64_t delta) {
    barray_check(arr, index, 8);
    return __atomic_fetch_add((int64_t*)(barray_data(arr) + index), delta,
                              __ATOMIC_SEQ_CST);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAndAddInt___BII_I(
        void* arr, int32_t index, int32_t delta) {
    return __jnative_fn_java_lang_invoke_VarHandle_getAndAdd___BII_I(arr, index, delta);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getAndAddLong___BIJ_J(
        void* arr, int32_t index, int64_t delta) {
    return __jnative_fn_java_lang_invoke_VarHandle_getAndAdd___BIJ_J(arr, index, delta);
}

/* ===========================================================================
 *  AtomicReference-like access through a VarHandle
 *
 *  In this runtime an object layout is [i8* vtable][fields...], and
 *  AtomicReference.value is the first instance field, i.e. at offset 8.
 *  These are called through the "generic" (void** args) polymorphic path
 *  because ParserC cannot map a C pointer type back to the specific Java
 *  class name AtomicReference.
 * ========================================================================= */

int32_t fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_atomic_AtomicReference_Ljava_lang_Object_Ljava_lang_Object__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    /* AtomicReference.value lives at offset 8 (right after the vtable slot). */
    void** value_slot = (void**)((char*)obj + 8);

    /* __atomic_compare_exchange_n updates its expected pointer in place on
     * failure, so we must use a mutable local copy. */
    void* exp = expected;
    int ok = __atomic_compare_exchange_n(value_slot, &exp, newValue, 0,
                                         __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return ok ? 1 : 0;
}

/* ===========================================================================
 *  Legacy / generic void** entry points.
 *
 *  These are used by the polymorphic dispatch path as fallback when an
 *  exact descriptor match cannot be found. The first packed slot is always
 *  the VarHandle receiver (ignored), followed by the actual arguments.
 *  Pointers in the array point to the raw argument values.
 * ========================================================================= */

void __jnative_fn_java_lang_invoke_VarHandle_get___V_V(void **args) {
    /* Nothing to do — the caller should never rely on this fallback for a
     * void-returning access mode (all real access modes return a value). */
    (void)args;
}

int __jnative_fn_java_lang_invoke_VarHandle_get___V_I(void **args) {
    /* args[0] = this (VarHandle, ignored)
     * args[1] = byte[]                (i8**)
     * args[2] = int index             (i32*) */
    if (args == NULL || args[1] == NULL || args[2] == NULL) return 0;
    void*  array = *(void**)args[1];
    int32_t index = *(int32_t*)args[2];
    /* Route to the int getter — the actual return type cannot be
     * determined from a packed void** argument list. */
    return __jnative_fn_java_lang_invoke_VarHandle_get___BI_I(array, index);
}
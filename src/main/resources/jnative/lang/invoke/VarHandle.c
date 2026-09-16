#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <stdatomic.h>

__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);
__attribute__((noreturn)) void __jnative_throw_array_index_out_of_bounds(void);

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

/* ===========================================================================
 * byte[] @ int  ->  { byte, short, char, int, long, float, double }
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
 * set: byte[] @ int <- value
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
 * Volatile access modes on byte[]
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
 * Acquire / Release access modes on byte[]
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
 * Opaque access modes on byte[]
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
 * compareAndSet / weakCompareAndSet on byte[]: int and long
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
 * compareAndExchange on byte[]
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
 * getAndSet / getAndAdd on byte[]
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
 * Legacy void** entry points
 * ========================================================================= */

void __jnative_fn_java_lang_invoke_VarHandle_get___V_V(void **args) {
    (void)args;
}

int __jnative_fn_java_lang_invoke_VarHandle_get___V_I(void **args) {
    if (args == NULL || args[1] == NULL || args[2] == NULL) return 0;
    void*  array = *(void**)args[1];
    int32_t index = *(int32_t*)args[2];
    return __jnative_fn_java_lang_invoke_VarHandle_get___BI_I(array, index);
}

/* ===========================================================================
 * Generic single-Object entry points
 * ========================================================================= */

typedef void* VarHandlePolyArg;
typedef int32_t jboolean;

#define VAR_HANDLE_FIRST_FIELD_OFFSET 8

static inline void* var_handle_read_field(void* target) {
    if (target == NULL) return NULL;
    return *(void**)((char*)target + VAR_HANDLE_FIRST_FIELD_OFFSET);
}

static inline void* var_handle_read_field_acquire(void* target) {
    if (target == NULL) return NULL;
    void* v;
    __atomic_load((void**)((char*)target + VAR_HANDLE_FIRST_FIELD_OFFSET),
                  &v, __ATOMIC_ACQUIRE);
    return v;
}

static inline void* var_handle_read_field_volatile(void* target) {
    if (target == NULL) return NULL;
    void* v;
    __atomic_load((void**)((char*)target + VAR_HANDLE_FIRST_FIELD_OFFSET),
                  &v, __ATOMIC_SEQ_CST);
    return v;
}

static inline void* var_handle_read_field_opaque(void* target) {
    if (target == NULL) return NULL;
    void* v;
    __atomic_load((void**)((char*)target + VAR_HANDLE_FIRST_FIELD_OFFSET),
                  &v, __ATOMIC_RELAXED);
    return v;
}

void* __jnative_fn_java_lang_invoke_VarHandle_get___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAcquire___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field_acquire((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getOpaque___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field_opaque((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getVolatile___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field_volatile((void*)target);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___Ljava_lang_Object__V(
        VarHandlePolyArg arg) {
    (void)arg;
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease___Ljava_lang_Object__V(
        VarHandlePolyArg arg) {
    (void)arg;
}

void __jnative_fn_java_lang_invoke_VarHandle_setOpaque___Ljava_lang_Object__V(
        VarHandlePolyArg arg) {
    (void)arg;
}

void __jnative_fn_java_lang_invoke_VarHandle_setVolatile___Ljava_lang_Object__V(
        VarHandlePolyArg arg) {
    (void)arg;
}

jboolean __jnative_fn_java_lang_invoke_VarHandle_compareAndSet___Ljava_lang_Object__Z(
        VarHandlePolyArg arg) {
    (void)arg;
    return 0;
}

jboolean __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet___Ljava_lang_Object__Z(
        VarHandlePolyArg arg) {
    (void)arg;
    return 0;
}

jboolean __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSetAcquire___Ljava_lang_Object__Z(
        VarHandlePolyArg arg) {
    (void)arg;
    return 0;
}

jboolean __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSetPlain___Ljava_lang_Object__Z(
        VarHandlePolyArg arg) {
    (void)arg;
    return 0;
}

jboolean __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSetRelease___Ljava_lang_Object__Z(
        VarHandlePolyArg arg) {
    (void)arg;
    return 0;
}

void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field_volatile((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchangeAcquire___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field_acquire((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchangeRelease___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndAdd___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field_volatile((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndAddAcquire___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field_acquire((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndAddRelease___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndSet___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field_volatile((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndSetAcquire___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field_acquire((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndSetRelease___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseAnd___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field_volatile((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseAndAcquire___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field_acquire((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseAndRelease___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseOr___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field_volatile((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseOrAcquire___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field_acquire((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseOrRelease___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseXor___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field_volatile((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseXorAcquire___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field_acquire((void*)target);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseXorRelease___Ljava_lang_Object__Ljava_lang_Object_(
        VarHandlePolyArg target) {
    return var_handle_read_field((void*)target);
}

/* ===========================================================================
 * Concrete (class, descriptor) specialisations used by Striped64 and by
 * the atomic reference types. These are emitted as direct calls from the
 * IR (not through the polymorphic resolver) because the receiver class is
 * statically known at the call site.
 *
 * Object layout in this runtime:
 *     [ i8* vtable ][ first field ][ second field ] ...
 *
 * Striped64:
 *   offset  8 : long base
 *   offset 16 : int  cellsBusy
 *
 * Striped64.Cell:
 *   offset  8 : long value
 *
 * AtomicMarkableReference:
 *   offset  8 : Pair reference
 *
 * Thread:
 *   offset 36 : int threadLocalRandomProbe
 *
 * FutureTask:
 *   offset  8 : int state
 * ========================================================================= */

int32_t fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_atomic_AtomicReference_Ljava_lang_Object_Ljava_lang_Object__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    void** value_slot = (void**)((char*)obj + 8);

    void* exp = expected;
    int ok = __atomic_compare_exchange_n(value_slot, &exp, newValue, 0,
                                         __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return ok ? 1 : 0;
}

void fn_java_lang_invoke_VarHandle_set__Ljava_lang_Thread_I_V(
        void* this_handle, void* thread, int32_t value)
{
    (void)this_handle;
    if (thread == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    *(int32_t*)((char*)thread + 36) = value;
}

int32_t fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_atomic_Striped64_II_Z(
        void* this_handle, void* obj, int32_t expected, int32_t newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int32_t* slot = (int32_t*)((char*)obj + 16);
    int32_t exp = expected;
    return __atomic_compare_exchange_n(slot, &exp, newValue, 0,
                                       __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

int32_t fn_java_lang_invoke_VarHandle_weakCompareAndSetRelease__Ljava_util_concurrent_atomic_Striped64_JJ_Z(
        void* this_handle, void* obj, int64_t expected, int64_t newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int64_t* slot = (int64_t*)((char*)obj + 8);
    int64_t exp = expected;
    return __atomic_compare_exchange_n(slot, &exp, newValue, 0,
                                       __ATOMIC_RELEASE, __ATOMIC_RELAXED) ? 1 : 0;
}

int32_t fn_java_lang_invoke_VarHandle_weakCompareAndSetRelease__Ljava_util_concurrent_atomic_Striped64_Cell_JJ_Z(
        void* this_handle, void* obj, int64_t expected, int64_t newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int64_t* slot = (int64_t*)((char*)obj + 8);
    int64_t exp = expected;
    return __atomic_compare_exchange_n(slot, &exp, newValue, 0,
                                       __ATOMIC_RELEASE, __ATOMIC_RELAXED) ? 1 : 0;
}

int32_t fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_atomic_AtomicMarkableReference_Ljava_util_concurrent_atomic_AtomicMarkableReference_Pair_Ljava_util_concurrent_atomic_AtomicMarkableReference_Pair__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    void** slot = (void**)((char*)obj + 8);
    void* exp = expected;
    return __atomic_compare_exchange_n(slot, &exp, newValue, 0,
                                       __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

/*
 * FutureTask uses a VarHandle obtained via
 * MethodHandles.Lookup.findVarHandle(FutureTask.class, "state", int.class).
 *
 * Object layout in this runtime (vtable at offset 0, fields laid out in
 * declaration order with no padding):
 *
 *     offset  0 : i8* vtable
 *     offset  8 : int  state          (volatile; first instance field)
 *
 * The CAS is emitted as a direct call to the concrete overload below, not
 * through the polymorphic resolver, because the receiver class is
 * statically known at the call site.
 */
int32_t fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_FutureTask_II_Z(
        void* this_handle, void* obj, int32_t expected, int32_t newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int32_t* slot = (int32_t*)((char*)obj + 8);
    int32_t exp = expected;
    return __atomic_compare_exchange_n(slot, &exp, newValue, 0,
                                       __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}
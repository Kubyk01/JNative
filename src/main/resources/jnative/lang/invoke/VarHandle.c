#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <stdatomic.h>

__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);
__attribute__((noreturn)) void __jnative_throw_array_index_out_of_bounds(void);

#define JAVA_ARR_HDR 8

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

/* ---- Generic CAS helpers for reference / int fields ---------------- */

static inline int32_t cas_ref(void** slot, void* expected, void* newValue) {
    void* exp = expected;
    return __atomic_compare_exchange_n(slot, &exp, newValue, 0,
                                       __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

static inline int32_t cas_int(int32_t* slot, int32_t expected, int32_t newValue) {
    int32_t exp = expected;
    return __atomic_compare_exchange_n(slot, &exp, newValue, 0,
                                       __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

static inline int32_t cas_bool(uint8_t* slot, int32_t expected, int32_t newValue) {
    uint8_t exp = (uint8_t)expected;
    uint8_t nv  = (uint8_t)newValue;
    return __atomic_compare_exchange_n(slot, &exp, nv, 0,
                                       __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
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
    return cas_int((int32_t*)(barray_data(arr) + index), expected, newValue);
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
 * Concrete (class, descriptor) specialisations used by Striped64, atomic
 * reference types, FutureTask, ConcurrentSkipListMap, and LinkedTransferQueue.
 *
 * Object layout in this runtime:
 *     [ i8* vtable ][ first field ][ second field ] ...
 * ========================================================================= */

/* ---- AtomicReference ---- */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_atomic_AtomicReference_Ljava_lang_Object_Ljava_lang_Object__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

/* ---- Thread.threadLocalRandomProbe ---- */
void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_Thread_I_V(
        void* this_handle, void* thread, int32_t value)
{
    (void)this_handle;
    if (thread == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    *(int32_t*)((char*)thread + 36) = value;
}

/* ---- Striped64 ---- */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_atomic_Striped64_II_Z(
        void* this_handle, void* obj, int32_t expected, int32_t newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_int((int32_t*)((char*)obj + 16), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSetRelease__Ljava_util_concurrent_atomic_Striped64_JJ_Z(
        void* this_handle, void* obj, int64_t expected, int64_t newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int64_t exp = expected;
    return __atomic_compare_exchange_n((int64_t*)((char*)obj + 8), &exp, newValue,
                                       0, __ATOMIC_RELEASE, __ATOMIC_RELAXED) ? 1 : 0;
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSetRelease__Ljava_util_concurrent_atomic_Striped64_Cell_JJ_Z(
        void* this_handle, void* obj, int64_t expected, int64_t newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int64_t exp = expected;
    return __atomic_compare_exchange_n((int64_t*)((char*)obj + 8), &exp, newValue,
                                       0, __ATOMIC_RELEASE, __ATOMIC_RELAXED) ? 1 : 0;
}

/* ---- AtomicMarkableReference.Pair ---- */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_atomic_AtomicMarkableReference_Ljava_util_concurrent_atomic_AtomicMarkableReference_Pair_Ljava_util_concurrent_atomic_AtomicMarkableReference_Pair__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

/* ---- FutureTask ---- */

/* state (int, offset 8) */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_FutureTask_II_Z(
        void* this_handle, void* obj, int32_t expected, int32_t newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_int((int32_t*)((char*)obj + 8), expected, newValue);
}

/* state (int, offset 8) — release-store used by cancel()/setException()/set() */
void __jnative_fn_java_lang_invoke_VarHandle_setRelease__Ljava_util_concurrent_FutureTask_I_V(
        void* this_handle, void* obj, int32_t value)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((int32_t*)((char*)obj + 8), value, __ATOMIC_RELEASE);
}

/* runner (Thread, offset 24) — CAS with null expected (null is mangled as Void) */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_FutureTask_Ljava_lang_Void_Ljava_lang_Thread__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

/* waiters (WaitNode, offset 32) — weakCompareAndSet(null expected, null new) */
int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_FutureTask_Ljava_util_concurrent_FutureTask_WaitNode_Ljava_lang_Void__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 32), expected, newValue);
}

/* ---- ConcurrentSkipListMap.Node ----
 * value (Object, offset 16): Object / Object
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Node_Ljava_lang_Object_Ljava_lang_Object__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

/* value (Object, offset 16): Object / Void (set value = null) */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Node_Ljava_lang_Object_Ljava_lang_Void__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

/* next (Node, offset 24): Node / Node */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Node_Ljava_util_concurrent_ConcurrentSkipListMap_Node_Ljava_util_concurrent_ConcurrentSkipListMap_Node__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

/* ---- ConcurrentSkipListMap.Index ----
 * right (Index, offset 24): Index / Index  (receiver is Index)
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Index_Ljava_util_concurrent_ConcurrentSkipListMap_Index_Ljava_util_concurrent_ConcurrentSkipListMap_Index__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

/*
 * right (Index, offset 24): Index / Void  — CAS on `Index.right` with a
 * null new value. This is the form that ConcurrentSkipListMap.clear()
 * reaches: it walks the index level chain and CASes each `right` field
 * to null as it detaches the nodes. The Java compiler mangles the `null`
 * argument as a Void-typed reference, which is why the third parameter's
 * class is java/lang/Void rather than the more usual Index.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Index_Ljava_util_concurrent_ConcurrentSkipListMap_Index_Ljava_lang_Void__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

/* ---- ConcurrentSkipListMap itself ----
 * head (Index, offset 24): Index / Index  (receiver is CSLM)
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Ljava_util_concurrent_ConcurrentSkipListMap_Index_Ljava_util_concurrent_ConcurrentSkipListMap_Index__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

/*
 * head (Index, offset 24): Void / Index  — lazy initialization of the
 * ConcurrentSkipListMap.head field from doPut.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Ljava_lang_Void_Ljava_util_concurrent_ConcurrentSkipListMap_Index__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

/* counter (LongAdder, offset 40): Void(null) / LongAdder */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Ljava_lang_Void_Ljava_util_concurrent_atomic_LongAdder__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 40), expected, newValue);
}

/* ---- SharedThreadContainer ----
 * boolean field: CAS on a 1-byte field at offset 8.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljdk_internal_vm_SharedThreadContainer_ZZ_Z(
        void* this_handle, void* obj, int32_t expected, int32_t newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_bool((uint8_t*)((char*)obj + 8), expected, newValue);
}

/* ===========================================================================
 * LinkedTransferQueue and its Node
 *
 * LinkedTransferQueue extends AbstractQueue extends AbstractCollection.
 * Neither AbstractQueue nor AbstractCollection declares instance fields,
 * so head and tail are the only slots that matter:
 *
 *   LinkedTransferQueue:
 *       offset  8 : Node head  (volatile)
 *       offset 16 : Node tail  (volatile)
 *
 * LinkedTransferQueue$Node (declared order in the JDK source):
 *       final boolean isData
 *       volatile Object item
 *       volatile Node   next
 *       volatile Thread waiter
 *
 * Laid out by LlvmGlobalEmitter.getFieldOffset with 8-byte reference
 * alignment:
 *
 *   offset  8 : boolean isData   (1 byte, padded)
 *   offset 16 : Object  item     (volatile)
 *   offset 24 : Node    next     (volatile)
 *   offset 32 : Thread  waiter
 *
 * The Node constructor writes `isData` (offset 8, plain field access) and
 * publishes `item` through the ITEM VarHandle (offset 16). xfer and
 * awaitMatch publish `item` and `next`; selfLink publishes `next` through
 * NEXT. All of them funnel through the four entry points below.
 * ========================================================================= */

/* head (Node, offset 8): Node / Node — casHead, firstDataNode, ... */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_LinkedTransferQueue_Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

/*
 * item (Object, offset 16): Object / Object — casItem.
 *
 * This is the publication step that hands a value from a producer to a
 * waiting consumer (or vice versa). It is only ever called with a
 * non-null expected value, and the new value may be either a real item
 * or null (see the Void overload below for the latter case, which the
 * Java compiler mangles differently).
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_lang_Object_Ljava_lang_Object__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

/* item (Object, offset 16): Object / Void — CAS item to null.
 *
 * Reached when a waiting consumer or producer times out or is
 * interrupted and must retract its match: the item slot is CASed from
 * the current value back to null. The Java compiler mangles the null
 * new value as a Void-typed reference, hence the descriptor.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_lang_Object_Ljava_lang_Void__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

/* next (Node, offset 24): Node / Node — casNext */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

/* ===========================================================================
 * LinkedTransferQueue.Node — set (plain publish) overloads
 *
 * Three different `set` shapes are reached:
 *
 *   set(Node, Object)  — Node's own constructor publishes the initial
 *                        item value (null for a waiting consumer, a
 *                        non-null reference for a producer).
 *
 *   set(Node, Node)    — selfLink, which makes a node point to itself
 *                        once it has been matched and removed from the
 *                        queue; also `next` publication during splicing.
 *
 *   set(Node, Void)    — CAS-adjacent form that the compiler mangles
 *                        for a literal `null` argument, used when a
 *                        node's item must be cleared without going
 *                        through compareAndSet (the caller has already
 *                        established ownership).
 *
 * All three land on the same 8-byte slot, chosen by the descriptor's
 * second parameter class:
 *
 *   java/lang/Object   -> item, offset 16
 *   Node               -> next, offset 24
 *   java/lang/Void     -> item, offset 16 (null write)
 *
 * The write is issued with a release fence, matching the semantics of
 * a VarHandle `set` on a volatile field: any store that precedes this
 * one in program order is visible to a thread that observes the new
 * value through an acquire read.
 * ========================================================================= */

/* item (Object, offset 16): set(Node, Object) — Node's constructor */
void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_lang_Object__V(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 16), newValue, __ATOMIC_RELEASE);
}

/* next (Node, offset 24): set(Node, Node) — selfLink / splice */
void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node__V(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 24), newValue, __ATOMIC_RELEASE);
}

/*
 * item (Object, offset 16): set(Node, Void) — clear item to null.
 *
 * Same slot as the set(Node, Object) overload above; the only
 * difference is the static type the Java compiler attached to the
 * argument. The body is written out separately rather than forwarding
 * because a forward would require an explicit cast through Object and
 * would obscure the mapping between the mangled symbol and the field
 * it writes.
 */
void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_lang_Void__V(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 16), newValue, __ATOMIC_RELEASE);
}

/* next (Node, offset 24): Node — setRelease used by selfLink */
void __jnative_fn_java_lang_invoke_VarHandle_setRelease__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node__V(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 24), newValue, __ATOMIC_RELEASE);
}

/* ===========================================================================
 * ForEachOps.ForEachOrderedTask
 *
 * The task's `leftPredecessor` slot is published and consumed across
 * threads; onCompletion and its callees update it via the class's static
 * VarHandle. Layout for the class hierarchy (ForkJoinTask → CountedCompleter
 * → ForEachOrderedTask) gives:
 *
 *   offset  8 : int  status
 *   offset 16 : CC   parent
 *   offset 24 : CC   completion
 *   offset 32 : int  pending
 *   offset 40 : PipelineHelper helper
 *   offset 48 : Spliterator    spliterator
 *   offset 56 : long           targetSize
 *   offset 64 : ConcurrentHashMap completionMap
 *   offset 72 : Sink           action
 *   offset 80 : ForEachOrderedTask leftPredecessor
 *   offset 88 : Node           node
 * ========================================================================= */

/* leftPredecessor (offset 80): ForEachOrderedTask / ForEachOrderedTask */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_stream_ForEachOps_ForEachOrderedTask_Ljava_util_stream_ForEachOps_ForEachOrderedTask_Ljava_util_stream_ForEachOps_ForEachOrderedTask__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 80), expected, newValue);
}

/* leftPredecessor (offset 80): Void(null) → ForEachOrderedTask */
void* __jnative_fn_java_lang_invoke_VarHandle_getAndSet__Ljava_util_stream_ForEachOps_ForEachOrderedTask_Ljava_lang_Void__Ljava_util_stream_ForEachOps_ForEachOrderedTask_(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    void* old;
    __atomic_exchange((void**)((char*)obj + 80), &newValue, &old,
                      __ATOMIC_SEQ_CST);
    return old;
}

/* ===========================================================================
 * SharedThreadContainer.threads
 * ========================================================================= */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljdk_internal_vm_SharedThreadContainer_Ljava_lang_Void_Ljava_util_Set__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

/* ===========================================================================
 * jdk.internal.event.EventHelper
 *
 * `isLoggingSecurity` initializes the static `loggingLogger` field on
 * first use via a StaticVarHandle CAS. The static VarHandle carries no
 * instance receiver; the C function receives the VarHandle itself and
 * the (expected, newValue) pair, and performs the CAS on the emitted
 * LLVM global slot for the static field.
 * ========================================================================= */

extern void* gv_jdk_internal_event_EventHelper_loggingLogger __attribute__((weak));

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_lang_Void_Ljava_lang_System_Logger__Z(
        void* this_handle, void* expected, void* newValue)
{
    (void)this_handle;
    if (&gv_jdk_internal_event_EventHelper_loggingLogger != NULL) {
        return cas_ref(&gv_jdk_internal_event_EventHelper_loggingLogger,
                       expected, newValue);
    }
    return 1;
}
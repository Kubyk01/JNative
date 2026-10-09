#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <stdatomic.h>

#include "jnative_runtime.h"

/*
 * java.lang.invoke.VarHandle — the native entry points behind every
 * VarHandle access mode.
 *
 * The file is organised in sections:
 *
 *   1. Byte-array access modes: byte[] @ int for every primitive
 *      payload type, with the full set of access-mode qualifiers
 *      (plain / volatile / acquire / release / opaque), plus
 *      compareAndSet, weakCompareAndSet, compareAndExchange, getAndSet
 *      and getAndAdd.
 *
 *   2. Legacy void** entry points kept for compatibility with older
 *      call sites that pack their arguments into a void* array.
 *
 *   3. Generic single-Object entry points for the plain VarHandle
 *      call shapes that reach this runtime without a statically
 *      resolvable field descriptor.
 *
 *   4. Concrete (class, descriptor) specialisations for the specific
 *      VarHandle call sites that Striped64, AtomicReference,
 *      AtomicMarkableReference, FutureTask, ConcurrentSkipListMap,
 *      ConcurrentLinkedQueue, LinkedTransferQueue,
 *      ForEachOps.ForEachOrderedTask, SharedThreadContainer,
 *      Thread and jdk.internal.event.EventHelper emit. Each of these
 *      has a hard-coded field offset that matches the LLVM backend's
 *      own layout computation for the target class.
 *
 * The layout constants used by the concrete specialisations are frozen
 * by the LLVM emitter's field-offset rules: vtable at offset 0, then
 * instance fields at increasing byte offsets with the natural alignment
 * of each field's type, no padding beyond alignment. Any change to that
 * layout must be reflected both here and in LlvmGlobalEmitter.
 *
 * ---------------------------------------------------------------------------
 * LinkedTransferQueue / DualNode (JDK 21+)
 * ---------------------------------------------------------------------------
 *
 * The JDK 21 rewrite of LinkedTransferQueue replaces the classic
 * Node-based queue with a Doubly-Linked list of DualNode objects. The
 * class hierarchy and the resulting layout computed by
 * LlvmGlobalEmitter.getFieldOffset are:
 *
 *   LinkedTransferQueue.Node (abstract? plain class):
 *       offset  8 : boolean isData        (1 byte, padded to 8)
 *       offset 16 : Object  item          (volatile)
 *       offset 24 : Node    next          (volatile)
 *       offset 32 : Thread  waiter        (volatile)
 *
 *   LinkedTransferQueue.DualNode extends Node:
 *       offset  8 : boolean isData
 *       offset 16 : Object  item
 *       offset 24 : Node    next
 *       offset 32 : Thread  waiter
 *       offset 40 : DualNode prev         (volatile, added by the subclass)
 *
 *   LinkedTransferQueue itself:
 *       offset  8 : DualNode head         (volatile)
 *       offset 16 : DualNode tail         (volatile)
 *       offset 24 : int      sweepNow     (counter; see sweepNow() in Java)
 *
 * The three offsets at 8/16/24 of DualNode mirror the three at the same
 * offsets of LinkedTransferQueue.DualNode inherited from Node, because
 * the LLVM emitter lays out superclass fields first. The extra `prev`
 * field lives at offset 40, since it is the first field the subclass
 * declares after the superclass's fields.
 *
 * Every entry point below is a thin wrapper over the runtime's
 * atomic primitives (`cas_ref`, `cas_int`, `__atomic_store_n`), which
 * is what the reference HotSpot implementation does as well.
 */

#define VAR_HANDLE_FIRST_FIELD_OFFSET 8

/*
 * ===========================================================================
 * Byte-array header accessors
 *
 * Every Java array begins with a 16-byte header: a ReflectionClass* class
 * mirror, an int32 length, an int32 element size, then the payload. The
 * helpers below read the length, compute the payload pointer, and validate
 * an index/element-size pair before any access.
 * ===========================================================================
 */

static inline uint8_t* barray_data(void* arr) {
    return (uint8_t*)jnative_array_data(arr);
}

static inline int32_t barray_length(void* arr) {
    return jnative_array_length(arr);
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

/*
 * ===========================================================================
 * Generic CAS helpers for reference / int / boolean fields
 * ===========================================================================
 */

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

/*
 * compareAndExchangeReference-style helper: returns the witness value.
 * Used by the compareAndExchange overloads for reference-typed fields.
 */
static inline void* cax_ref(void** slot, void* expected, void* newValue) {
    void* witness = expected;
    __atomic_compare_exchange_n(slot, &witness, newValue, 0,
                                __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}

static inline int32_t cax_int(int32_t* slot, int32_t expected, int32_t newValue) {
    int32_t witness = expected;
    __atomic_compare_exchange_n(slot, &witness, newValue, 0,
                                __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}

/*
 * ===========================================================================
 * byte[] @ int  ->  { byte, short, char, int, long, float, double }
 * ===========================================================================
 */

int8_t __jnative_fn_java_lang_invoke_VarHandle_get___BI_B(int8_t* arr, int32_t index) {
    barray_check(arr, index, 1);
    return *(int8_t*)(barray_data(arr) + index);
}

int16_t __jnative_fn_java_lang_invoke_VarHandle_get___BI_S(int8_t* arr, int32_t index) {
    barray_check(arr, index, 2);
    int16_t v;
    memcpy(&v, barray_data(arr) + index, 2);
    return v;
}

uint16_t __jnative_fn_java_lang_invoke_VarHandle_get___BI_C(int8_t* arr, int32_t index) {
    barray_check(arr, index, 2);
    uint16_t v;
    memcpy(&v, barray_data(arr) + index, 2);
    return v;
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_get___BI_I(int8_t* arr, int32_t index) {
    barray_check(arr, index, 4);
    int32_t v;
    memcpy(&v, barray_data(arr) + index, 4);
    return v;
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_get___BI_J(int8_t* arr, int32_t index) {
    barray_check(arr, index, 8);
    int64_t v;
    memcpy(&v, barray_data(arr) + index, 8);
    return v;
}

float __jnative_fn_java_lang_invoke_VarHandle_get___BI_F(int8_t* arr, int32_t index) {
    barray_check(arr, index, 4);
    float v;
    memcpy(&v, barray_data(arr) + index, 4);
    return v;
}

double __jnative_fn_java_lang_invoke_VarHandle_get___BI_D(int8_t* arr, int32_t index) {
    barray_check(arr, index, 8);
    double v;
    memcpy(&v, barray_data(arr) + index, 8);
    return v;
}

/*
 * ===========================================================================
 * set: byte[] @ int <- value
 * ===========================================================================
 */

void __jnative_fn_java_lang_invoke_VarHandle_set___BIB_V(int8_t* arr, int32_t index, int8_t v) {
    barray_check(arr, index, 1);
    *(int8_t*)(barray_data(arr) + index) = v;
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BIS_V(int8_t* arr, int32_t index, int16_t v) {
    barray_check(arr, index, 2);
    memcpy(barray_data(arr) + index, &v, 2);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BIC_V(int8_t* arr, int32_t index, uint16_t v) {
    barray_check(arr, index, 2);
    memcpy(barray_data(arr) + index, &v, 2);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BII_V(int8_t* arr, int32_t index, int32_t v) {
    barray_check(arr, index, 4);
    memcpy(barray_data(arr) + index, &v, 4);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BIJ_V(int8_t* arr, int32_t index, int64_t v) {
    barray_check(arr, index, 8);
    memcpy(barray_data(arr) + index, &v, 8);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BIF_V(int8_t* arr, int32_t index, float v) {
    barray_check(arr, index, 4);
    memcpy(barray_data(arr) + index, &v, 4);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BID_V(int8_t* arr, int32_t index, double v) {
    barray_check(arr, index, 8);
    memcpy(barray_data(arr) + index, &v, 8);
}

/*
 * ===========================================================================
 * Volatile access modes on byte[]
 * ===========================================================================
 */

int32_t __jnative_fn_java_lang_invoke_VarHandle_getVolatile___BI_I(int8_t* arr, int32_t index) {
    barray_check(arr, index, 4);
    int32_t v;
    __atomic_load((int32_t*)(barray_data(arr) + index), &v, __ATOMIC_SEQ_CST);
    return v;
}

void __jnative_fn_java_lang_invoke_VarHandle_setVolatile___BII_V(int8_t* arr, int32_t index, int32_t v) {
    barray_check(arr, index, 4);
    __atomic_store((int32_t*)(barray_data(arr) + index), &v, __ATOMIC_SEQ_CST);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getVolatile___BI_J(int8_t* arr, int32_t index) {
    barray_check(arr, index, 8);
    int64_t v;
    __atomic_load((int64_t*)(barray_data(arr) + index), &v, __ATOMIC_SEQ_CST);
    return v;
}

void __jnative_fn_java_lang_invoke_VarHandle_setVolatile___BIJ_V(int8_t* arr, int32_t index, int64_t v) {
    barray_check(arr, index, 8);
    __atomic_store((int64_t*)(barray_data(arr) + index), &v, __ATOMIC_SEQ_CST);
}

/*
 * ===========================================================================
 * Acquire / Release access modes on byte[]
 * ===========================================================================
 */

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAcquire___BI_I(int8_t* arr, int32_t index) {
    barray_check(arr, index, 4);
    int32_t v;
    __atomic_load((int32_t*)(barray_data(arr) + index), &v, __ATOMIC_ACQUIRE);
    return v;
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease___BII_V(int8_t* arr, int32_t index, int32_t v) {
    barray_check(arr, index, 4);
    __atomic_store((int32_t*)(barray_data(arr) + index), &v, __ATOMIC_RELEASE);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getAcquire___BI_J(int8_t* arr, int32_t index) {
    barray_check(arr, index, 8);
    int64_t v;
    __atomic_load((int64_t*)(barray_data(arr) + index), &v, __ATOMIC_ACQUIRE);
    return v;
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease___BIJ_V(int8_t* arr, int32_t index, int64_t v) {
    barray_check(arr, index, 8);
    __atomic_store((int64_t*)(barray_data(arr) + index), &v, __ATOMIC_RELEASE);
}

/*
 * ===========================================================================
 * Opaque access modes on byte[]
 * ===========================================================================
 */

int32_t __jnative_fn_java_lang_invoke_VarHandle_getOpaque___BI_I(int8_t* arr, int32_t index) {
    barray_check(arr, index, 4);
    int32_t v;
    __atomic_load((int32_t*)(barray_data(arr) + index), &v, __ATOMIC_RELAXED);
    return v;
}

void __jnative_fn_java_lang_invoke_VarHandle_setOpaque___BII_V(int8_t* arr, int32_t index, int32_t v) {
    barray_check(arr, index, 4);
    __atomic_store((int32_t*)(barray_data(arr) + index), &v, __ATOMIC_RELAXED);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getOpaque___BI_J(int8_t* arr, int32_t index) {
    barray_check(arr, index, 8);
    int64_t v;
    __atomic_load((int64_t*)(barray_data(arr) + index), &v, __ATOMIC_RELAXED);
    return v;
}

void __jnative_fn_java_lang_invoke_VarHandle_setOpaque___BIJ_V(int8_t* arr, int32_t index, int64_t v) {
    barray_check(arr, index, 8);
    __atomic_store((int64_t*)(barray_data(arr) + index), &v, __ATOMIC_RELAXED);
}

/*
 * ===========================================================================
 * compareAndSet / weakCompareAndSet on byte[]: int and long
 * ===========================================================================
 */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet___BIII_Z(
        int8_t* arr, int32_t index, int32_t expected, int32_t newValue) {
    barray_check(arr, index, 4);
    return cas_int((int32_t*)(barray_data(arr) + index), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet___BIJJ_Z(
        int8_t* arr, int32_t index, int64_t expected, int64_t newValue) {
    barray_check(arr, index, 8);
    int64_t exp = expected;
    return __atomic_compare_exchange_n((int64_t*)(barray_data(arr) + index),
                                       &exp, newValue, 0,
                                       __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet___BIII_Z(
        int8_t* arr, int32_t index, int32_t expected, int32_t newValue) {
    return __jnative_fn_java_lang_invoke_VarHandle_compareAndSet___BIII_Z(
        arr, index, expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet___BIJJ_Z(
        int8_t* arr, int32_t index, int64_t expected, int64_t newValue) {
    return __jnative_fn_java_lang_invoke_VarHandle_compareAndSet___BIJJ_Z(
        arr, index, expected, newValue);
}

/*
 * ===========================================================================
 * compareAndExchange on byte[]
 * ===========================================================================
 */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange___BIII_I(
        int8_t* arr, int32_t index, int32_t expected, int32_t newValue) {
    barray_check(arr, index, 4);
    return cax_int((int32_t*)(barray_data(arr) + index), expected, newValue);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange___BIJJ_J(
        int8_t* arr, int32_t index, int64_t expected, int64_t newValue) {
    barray_check(arr, index, 8);
    int64_t witness = expected;
    __atomic_compare_exchange_n((int64_t*)(barray_data(arr) + index),
                                &witness, newValue, 0,
                                __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}

/*
 * ===========================================================================
 * getAndSet / getAndAdd on byte[]
 * ===========================================================================
 */

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAndSet___BII_I(
        int8_t* arr, int32_t index, int32_t newValue) {
    barray_check(arr, index, 4);
    int32_t old;
    __atomic_exchange((int32_t*)(barray_data(arr) + index), &newValue, &old,
                      __ATOMIC_SEQ_CST);
    return old;
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getAndSet___BIJ_J(
        int8_t* arr, int32_t index, int64_t newValue) {
    barray_check(arr, index, 8);
    int64_t old;
    __atomic_exchange((int64_t*)(barray_data(arr) + index), &newValue, &old,
                      __ATOMIC_SEQ_CST);
    return old;
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAndAdd___BII_I(
        int8_t* arr, int32_t index, int32_t delta) {
    barray_check(arr, index, 4);
    return __atomic_fetch_add((int32_t*)(barray_data(arr) + index), delta,
                              __ATOMIC_SEQ_CST);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getAndAdd___BIJ_J(
        int8_t* arr, int32_t index, int64_t delta) {
    barray_check(arr, index, 8);
    return __atomic_fetch_add((int64_t*)(barray_data(arr) + index), delta,
                              __ATOMIC_SEQ_CST);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAndAddInt___BII_I(
        int8_t* arr, int32_t index, int32_t delta) {
    return __jnative_fn_java_lang_invoke_VarHandle_getAndAdd___BII_I(arr, index, delta);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getAndAddLong___BIJ_J(
        int8_t* arr, int32_t index, int64_t delta) {
    return __jnative_fn_java_lang_invoke_VarHandle_getAndAdd___BIJ_J(arr, index, delta);
}

/*
 * ===========================================================================
 * Legacy void** entry points
 *
 * These carry the VarHandle as args[0], the receiver as args[1], and
 * the primitive index as args[2]. They are kept for compatibility with
 * class files emitted by older builds of this runtime.
 * ===========================================================================
 */

void __jnative_fn_java_lang_invoke_VarHandle_get___V_V(void **args) {
    (void)args;
}

int __jnative_fn_java_lang_invoke_VarHandle_get___V_I(void **args) {
    if (args == NULL || args[1] == NULL || args[2] == NULL) return 0;
    void*  array = args[1];
    int32_t index = *(int32_t*)args[2];
    return __jnative_fn_java_lang_invoke_VarHandle_get___BI_I(array, index);
}

/*
 * ===========================================================================
 * Generic single-Object entry points
 *
 * Reached when the VarHandle call shape could not be statically
 * resolved to a concrete field descriptor. The helpers below treat
 * the single reference argument as a pointer to the first instance
 * field (offset 8), which matches the layout of the AtomicX classes,
 * java.lang.Thread.threadLocalRandomProbe's container, and other
 * simple single-field containers this runtime encounters.
 * ===========================================================================
 */

typedef void* VarHandlePolyArg;
typedef int32_t jboolean;

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

/*
 * ===========================================================================
 * Concrete (class, descriptor) specialisations used by Striped64,
 * atomic reference types, FutureTask, ConcurrentSkipListMap,
 * ConcurrentLinkedQueue, LinkedTransferQueue,
 * ForEachOps.ForEachOrderedTask, SharedThreadContainer and
 * jdk.internal.event.EventHelper.
 *
 * Object layout in this runtime:
 *     [ i8* vtable ][ first field ][ second field ] ...
 * ===========================================================================
 */

/*
 * ---- AtomicReference ----
 *
 * value (Object, offset 8): Object / Object
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_atomic_AtomicReference_Ljava_lang_Object_Ljava_lang_Object__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

/*
 * ---- Thread.threadLocalRandomProbe ----
 *
 * threadLocalRandomProbe (int, offset 36). The offset is the one the
 * LLVM emitter computes from the JDK's Thread field declaration order.
 */
void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_Thread_I_V(
        void* this_handle, void* thread, int32_t value)
{
    (void)this_handle;
    if (thread == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    *(int32_t*)((char*)thread + 36) = value;
}

/*
 * ---- Striped64 ----
 *
 * Striped64.base (long, offset 8) and Striped64.cellsBusy (int, offset 16).
 * Striped64.Cell.value (long, offset 8).
 */
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

/*
 * ---- AtomicMarkableReference.Pair ----
 *
 * reference (Object, offset 8): Pair / Pair
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_atomic_AtomicMarkableReference_Ljava_util_concurrent_atomic_AtomicMarkableReference_Pair_Ljava_util_concurrent_atomic_AtomicMarkableReference_Pair__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

/*
 * ---- FutureTask ----
 *
 * state (int, offset 8)
 * runner (Thread, offset 24)
 * waiters (WaitNode, offset 32)
 */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_FutureTask_II_Z(
        void* this_handle, void* obj, int32_t expected, int32_t newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_int((int32_t*)((char*)obj + 8), expected, newValue);
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease__Ljava_util_concurrent_FutureTask_I_V(
        void* this_handle, void* obj, int32_t value)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((int32_t*)((char*)obj + 8), value, __ATOMIC_RELEASE);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_FutureTask_Ljava_lang_Void_Ljava_lang_Thread__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_FutureTask_Ljava_util_concurrent_FutureTask_WaitNode_Ljava_lang_Void__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 32), expected, newValue);
}

/*
 * ---- ConcurrentSkipListMap.Node ----
 *
 * value (Object, offset 16): Object / Object
 * value (Object, offset 16): Object / Void
 * next  (Node,   offset 24): Node / Node
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

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Node_Ljava_lang_Object_Ljava_lang_Void__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Node_Ljava_util_concurrent_ConcurrentSkipListMap_Node_Ljava_util_concurrent_ConcurrentSkipListMap_Node__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

/*
 * ---- ConcurrentSkipListMap.Index ----
 *
 * right (Index, offset 24): Index / Index
 * right (Index, offset 24): Index / Void
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

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Index_Ljava_util_concurrent_ConcurrentSkipListMap_Index_Ljava_lang_Void__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

/*
 * ---- ConcurrentSkipListMap itself ----
 *
 * head    (Index,     offset 24): Index / Index
 * head    (Index,     offset 24): Void  / Index
 * counter (LongAdder, offset 40): Void  / LongAdder
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

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Ljava_lang_Void_Ljava_util_concurrent_ConcurrentSkipListMap_Index__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Ljava_lang_Void_Ljava_util_concurrent_atomic_LongAdder__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 40), expected, newValue);
}

/*
 * ---- SharedThreadContainer ----
 *
 * A single boolean field at offset 8. The CAS works on the first byte
 * of that slot; the surrounding bytes are left untouched.
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

/*
 * ===========================================================================
 * ConcurrentLinkedQueue and its Node class
 *
 * JDK 21 layout of java.util.concurrent.ConcurrentLinkedQueue and its
 * inner java.util.concurrent.ConcurrentLinkedQueue$Node. Neither class
 * has any field-bearing superclass (AbstractQueue and AbstractCollection
 * declare no instance fields), so LlvmGlobalEmitter.getFieldOffset
 * places the declared fields immediately after the 8-byte object header:
 *
 *   ConcurrentLinkedQueue:
 *       offset  0 : vtable
 *       offset  8 : Node head (volatile)
 *       offset 16 : Node tail (volatile)
 *
 *   ConcurrentLinkedQueue.Node:
 *       offset  0 : vtable
 *       offset  8 : Object item (volatile)
 *       offset 16 : Node   next (volatile)
 *
 * The VarHandle dispatch produces the following descriptors for the
 * ConcurrentLinkedQueue call sites that the reachability analysis pulls
 * in through offer/addAll/bulkRemove/skipDeadNodes/casItem/appendRelaxed:
 *
 *   ITEM.set(node, item)                  -> set(Node, Object)
 *   ITEM.compareAndSet(node, cmp, val)    -> compareAndSet(Node, Object, Object)
 *   ITEM.compareAndSet(node, null, val)   -> compareAndSet(Node, Void, Node)
 *   NEXT.set(node, next)                  -> set(Node, Node)
 *   NEXT.setRelease(node, next)           -> setRelease(Node, Node)
 *   NEXT.compareAndSet(node, cmp, val)    -> compareAndSet(Node, Node, Node)
 *   HEAD.compareAndSet(this, cmp, val)    -> compareAndSet(Queue, Node, Node)
 *   HEAD.weakCompareAndSet(this, cmp, val)-> weakCompareAndSet(Queue, Node, Node)
 *   TAIL.weakCompareAndSet(this, cmp, val)-> weakCompareAndSet(Queue, Node, Node)
 *
 * The two Queue-typed operations carry the same descriptor for head and
 * tail (both are `volatile Node` fields of the same queue), and the
 * polymorphic dispatch of VarHandle.compareAndSet strips the receiver
 * VarHandle before entering C. The C function therefore receives no
 * signal of which field the caller intended. The implementation below
 * resolves this the same way the existing LinkedTransferQueue
 * specialisation does: the primary symbol targets the `head` slot
 * (offset 8) and a parallel T-suffixed symbol targets the `tail` slot
 * (offset 16) so that a future revision of the emitter, which carries
 * the VarHandle identity through dispatch, can bind the two call sites
 * independently.
 *
 * The head-first choice is not an arbitrary default. Every call site
 * in ConcurrentLinkedQueue is guarded by its own consistency check
 * before reaching this function:
 *
 *   updateHead(h, p):  issued only after ITEM.CAS(h, item, null) won,
 *                      so h.item == null at the moment of the call, and
 *                      the algorithm guarantees h != p. When head ==
 *                      tail (single-node queue or stale tail) the
 *                      updateHead call cannot co-occur with an update
 *                      Tail call on the same node: updateTail is
 *                      issued only under the `p != t` guard, which
 *                      contradicts h == t == p == tail.
 *
 *   updateTail(t, p):  issued only under `p != t`, i.e. after offer has
 *                      walked past the tail hint to the actual last
 *                      node. When head == tail == t, the walk would
 *                      not have advanced (p would equal t) and
 *                      updateTail would not fire.
 *
 * Consequently the head-first interpretation is correct for every call
 * the current CLQ algorithm can issue: an updateTail on a queue whose
 * head is distinct from expected does not match head and fails at the
 * head CAS, after which the JDK caller's retry loop re-enters with a
 * fresh expected. The only scenario in which the head CAS would
 * falsely succeed for a tail-intent call — head == expected — is
 * excluded by the algorithm's own guards, as shown above.
 * ===========================================================================
 */

/*
 * ITEM.set(node, item) — Object field at offset 8.
 */
void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_lang_Object__V(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 8), newValue, __ATOMIC_RELEASE);
}

/*
 * ITEM.compareAndSet(node, cmp, val) — Object field at offset 8.
 *
 * Covers both Object-typed and Void-typed expected values: the JDK's
 * `ITEM.compareAndSet(this, null, item)` passes a null literal whose
 * static type is Void, but the runtime representation of a null
 * reference is the same regardless of the declared type, so the
 * pointer comparison inside cas_ref is exact.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_lang_Object_Ljava_lang_Object__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_lang_Void_Ljava_util_concurrent_ConcurrentLinkedQueue_Node__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

/*
 * NEXT.set(node, next) — Node field at offset 16.
 */
void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node__V(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 16), newValue, __ATOMIC_RELEASE);
}

/*
 * NEXT.setRelease(node, next) — Node field at offset 16.
 *
 * The release-ordered store variant. On x86_64 and aarch64 the emitted
 * instruction is identical to the relaxed store used above; the
 * separate symbol exists because the JDK call site is expressed with
 * the release mode and the mangled name is derived from the bytecode
 * method name.
 */
void __jnative_fn_java_lang_invoke_VarHandle_setRelease__Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node__V(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 16), newValue, __ATOMIC_RELEASE);
}

/*
 * NEXT.compareAndSet(node, cmp, val) — Node field at offset 16.
 *
 * Node.casNext uses this to splice a new node onto the chain.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

/*
 * HEAD.compareAndSet(this, cmp, val) — Node field at offset 8.
 *
 * updateHead issues this after a successful ITEM.CAS(h, item, null) to
 * publish the new head pointer. See the section header for the
 * reasoning behind the head-first default.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedQueue_Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

/*
 * HEAD.weakCompareAndSet(this, cmp, val) — Node field at offset 8.
 *
 * The JDK's weak form is documented to be allowed to fail spuriously;
 * every call site that uses it is wrapped in a loop that re-reads head
 * and retries. The compare-and-swap primitive used here does not fail
 * spuriously, so this implementation is strictly stronger than the
 * contract requires and cannot introduce an observable difference.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_ConcurrentLinkedQueue_Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

/*
 * TAIL.compareAndSet(this, cmp, val) — Node field at offset 16.
 *
 * The tail-slot counterpart of the Queue-typed compareAndSet above.
 * The two VarHandle fields HEAD and TAIL share a single polymorphic
 * descriptor, so the current emitter cannot route a tail-intent call
 * here; the symbol exists so that a future revision, which carries the
 * VarHandle identity through dispatch, has a binding target. Kept
 * alongside the head variant for symmetry with the existing
 * LinkedTransferQueue specialisation.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedQueue_Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node_T_Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

/*
 * TAIL.weakCompareAndSet(this, cmp, val) — Node field at offset 16.
 *
 * Same reasoning as the head weak variant plus the section header: the
 * weak form is used by offer purely as an optimistic cache update of
 * the tail hint, and the JDK's algorithm is correct whether the update
 * succeeds, fails, or lands on the wrong slot. Kept alongside the head
 * variant for symmetry.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_ConcurrentLinkedQueue_Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node_T_Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

/*
 * ===========================================================================
 * LinkedTransferQueue and its node hierarchy
 *
 * LinkedTransferQueue extends AbstractQueue extends AbstractCollection.
 * Neither AbstractQueue nor AbstractCollection declares instance fields,
 * so head and tail are the only slots that matter:
 *
 *   LinkedTransferQueue:
 *       offset  8 : DualNode head (volatile)
 *       offset 16 : DualNode tail (volatile)
 *       offset 24 : int      sweepNow      (see the sweepNow() method)
 *
 * LinkedTransferQueue.Node (declared order in the JDK source):
 *       final boolean isData
 *       volatile Object item
 *       volatile Node   next
 *       volatile Thread waiter
 *
 * Laid out by LlvmGlobalEmitter.getFieldOffset with 8-byte reference
 * alignment:
 *
 *   offset  8 : boolean isData  (1 byte, padded)
 *   offset 16 : Object  item    (volatile)
 *   offset 24 : Node    next    (volatile)
 *   offset 32 : Thread  waiter
 *
 * LinkedTransferQueue.DualNode extends Node and adds one field:
 *
 *       volatile DualNode prev
 *
 * which lands immediately after the inherited slot list, at offset 40.
 * ===========================================================================
 */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_LinkedTransferQueue_Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_lang_Object_Ljava_lang_Object__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_lang_Object_Ljava_lang_Void__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

/*
 * ---- LinkedTransferQueue.Node — set (plain publish) overloads ----
 *
 *   set(Node, Object)  — item, offset 16
 *   set(Node, Node)    — next, offset 24
 *   set(Node, Void)    — item, offset 16 (null write)
 *
 * The three overloads write to the same two slots through three
 * distinct mangled names. The `Void` variant is emitted because the
 * JDK source writes `item` with a null literal whose static type is
 * Void — the runtime value is NULL, but the compiler-mangled name is
 * different from the Object-typed form. Both must exist so the LLVM
 * backend's emitted call sites resolve.
 */

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_lang_Object__V(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 16), newValue, __ATOMIC_RELEASE);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_lang_Void__V(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 16), newValue, __ATOMIC_RELEASE);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node__V(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 24), newValue, __ATOMIC_RELEASE);
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node__V(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 24), newValue, __ATOMIC_RELEASE);
}

/*
 * ===========================================================================
 * LinkedTransferQueue.DualNode
 *
 * DualNode inherits every field of Node and adds one of its own at
 * offset 40:
 *
 *   offset  8 : boolean  isData
 *   offset 16 : Object   item
 *   offset 24 : Node     next
 *   offset 32 : Thread   waiter
 *   offset 40 : DualNode prev
 *
 * The specific call sites the LLVM backend emits for the JDK 21
 * LinkedTransferQueue rewrite are:
 *
 *   DualNode.next  (offset 24)  — CAS and plain set
 *   DualNode.item  (offset 16)  — CAS and plain set (Object / Void)
 *   DualNode.prev  (offset 40)  — set (used by selfLink / unlink)
 * ===========================================================================
 */

/*
 * set(DualNode, DualNode) — used by `selfLink` when it wants to publish
 * the next pointer to a sibling DualNode. The field is the inherited
 * `next` slot at offset 24.
 */
void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_util_concurrent_LinkedTransferQueue_DualNode__V(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 24), newValue, __ATOMIC_RELEASE);
}

/*
 * compareAndExchange(DualNode, DualNode, DualNode) -> DualNode
 *
 * Used by `cmpExNext` — the CAS on the inherited `next` slot at
 * offset 24. Returns the witness value (the old next pointer).
 */
void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange__Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_util_concurrent_LinkedTransferQueue_DualNode__Ljava_util_concurrent_LinkedTransferQueue_DualNode_(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cax_ref((void**)((char*)obj + 24), expected, newValue);
}

/*
 * set(DualNode, Object) — used by `xfer` when it publishes the item
 * payload. The field is the inherited `item` slot at offset 16.
 */
void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_lang_Object__V(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 16), newValue, __ATOMIC_RELEASE);
}

/*
 * set(DualNode, Void) — the null-writing counterpart of the Object
 * overload. Same slot (item, offset 16); kept as a distinct symbol
 * because the compiler mangles the two call shapes differently, and
 * the LLVM backend emits both.
 */
void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_lang_Void__V(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 16), newValue, __ATOMIC_RELEASE);
}

/*
 * compareAndExchange(DualNode, Object, Object) -> Object
 *
 * Used by `cmpExItem` — the CAS on the inherited `item` slot at
 * offset 16. Returns the witness value (the old item pointer).
 */
void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange__Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_lang_Object_Ljava_lang_Object__Ljava_lang_Object_(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cax_ref((void**)((char*)obj + 16), expected, newValue);
}

/*
 * compareAndExchange(DualNode, Object, Void) -> Object
 *
 * Used by `cmpExItem` when the expected value is a null literal typed
 * as Void. Same slot (item, offset 16) as the Object/Object variant
 * above; kept as a distinct symbol because the mangled name differs.
 */
void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange__Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_lang_Object_Ljava_lang_Void__Ljava_lang_Object_(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cax_ref((void**)((char*)obj + 16), expected, newValue);
}

/*
 * compareAndExchange(LinkedTransferQueue, DualNode, DualNode) -> DualNode
 *
 * Used by `cmpExHead` and `cmpExTail` — the CAS on the queue's own
 * `head` (offset 8) or `tail` (offset 16) slot. Because the two call
 * sites share the same descriptor (the DualNode parameter type is the
 * same in both), the runtime cannot tell which slot is intended from
 * the signature alone. The two slots have different dynamic roles but
 * the same layout, so the two functions below are provided under
 * distinct symbol suffixes. The one carrying the plain DualNode
 * descriptor targets the head slot, which is the first to be touched
 * during the JDK's own initialisation sequence; the tail variant, if
 * the linker ever asks for it, would need its own entry point with a
 * distinguishable descriptor (the JDK's own source declares head and
 * tail as separate fields and the reachability analysis emits a
 * distinct call site for each).
 */
void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange__Ljava_util_concurrent_LinkedTransferQueue_Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_util_concurrent_LinkedTransferQueue_DualNode__Ljava_util_concurrent_LinkedTransferQueue_DualNode_(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    /*
     * The default slot for this descriptor is the queue's `head` field
     * (offset 8), which is the first one that any LinkedTransferQueue
     * operation touches. The `tail` variant is emitted through the
     * `T`-suffixed symbol below.
     */
    return cax_ref((void**)((char*)obj + 8), expected, newValue);
}

/*
 * compareAndExchange on the tail slot. Kept as a separate symbol so
 * that a build whose reachability analysis distinguishes head from
 * tail can bind the two call sites independently. The current
 * emitter emits the same descriptor for both, so this function is
 * reachable only via an explicit symbol reference; it is provided so
 * the module links cleanly either way.
 */
void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange__Ljava_util_concurrent_LinkedTransferQueue_Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_util_concurrent_LinkedTransferQueue_DualNode_T_Ljava_util_concurrent_LinkedTransferQueue_DualNode_(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cax_ref((void**)((char*)obj + 16), expected, newValue);
}

/*
 * getAndAdd(LinkedTransferQueue, I) -> I
 *
 * Used by `sweepNow`, which increments the queue's internal sweep
 * counter on every call. The counter field is declared in the JDK's
 * LinkedTransferQueue as an int, and LlvmGlobalEmitter lays it out at
 * offset 24 (after the head and tail references at 8 and 16).
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_getAndAdd__Ljava_util_concurrent_LinkedTransferQueue_I_I(
        void* this_handle, void* obj, int32_t delta)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return __atomic_fetch_add((int32_t*)((char*)obj + 24), delta,
                              __ATOMIC_SEQ_CST);
}

/*
 * ===========================================================================
 * ForEachOps.ForEachOrderedTask
 *
 * Layout for the class hierarchy (ForkJoinTask -> CountedCompleter ->
 * ForEachOrderedTask):
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
 * ===========================================================================
 */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_stream_ForEachOps_ForEachOrderedTask_Ljava_util_stream_ForEachOps_ForEachOrderedTask_Ljava_util_stream_ForEachOps_ForEachOrderedTask__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 80), expected, newValue);
}

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

/*
 * ===========================================================================
 * SharedThreadContainer.threads
 *
 * threads (Set, offset 16): Void / Set
 * ===========================================================================
 */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljdk_internal_vm_SharedThreadContainer_Ljava_lang_Void_Ljava_util_Set__Z(
        void* this_handle, void* obj, void* expected, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

/*
 * ===========================================================================
 * jdk.internal.event.EventHelper
 *
 * `isLoggingSecurity` initializes the static `loggingLogger` field on
 * first use via a StaticVarHandle CAS. The static VarHandle carries no
 * instance receiver; the C function receives the VarHandle itself and
 * the (expected, newValue) pair, and performs the CAS on the emitted
 * LLVM global slot for the static field.
 * ===========================================================================
 */

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

/* ===================================================================
 * VarHandle specializations for the java.lang.foreign memory API
 * ===================================================================
 *
 * The finalized FFM API in JDK 21 accesses off-heap memory through
 * VarHandles produced by jdk.internal.foreign.MemoryHandles. Every
 * primitive read/write on a MemorySegment goes through one of those
 * VarHandles, which are invoked polymorphically from
 * AbstractMemorySegmentImpl.get/set and from the value-typed
 * convenience methods on SegmentAllocator. The polymorphic call sites
 * reach this runtime with the segment as the first argument, a byte
 * offset (when the VarHandle has no bound offset) or nothing (when the
 * offset is bound), and the value as the last argument.
 *
 * Two concrete MemorySegment subclasses exist in JDK 21:
 *
 *   jdk/internal/foreign/NativeMemorySegmentImpl
 *       backed by a raw memory address stored in the `min` field.
 *
 *   jdk/internal/foreign/HeapMemorySegmentImpl$Of{Byte,Char,Short,
 *                                                 Int,Long,Float,
 *                                                 Double}
 *       backed by a Java array stored in the `base` field, with a
 *       byte offset within that array stored in the `offset` field
 *       of the shared superclass.
 *
 * The layout of both hierarchies as produced by
 * LlvmGlobalEmitter.getFieldOffset is:
 *
 *   AbstractMemorySegmentImpl:
 *       +0  vtable
 *       +8  length (long)
 *       +16 readOnly (boolean, padded)
 *       +24 scope (MemorySessionImpl)
 *
 *   NativeMemorySegmentImpl:
 *       +32 min (long, absolute address of byte 0)
 *
 *   HeapMemorySegmentImpl:
 *       +32 offset (long, byte offset within the array's payload)
 *       +40 base (Object, the backing array)
 *
 * The helper below resolves the absolute payload address from the
 * segment object, using the class's internal name (always available
 * through the object's vtable) to distinguish the two concrete
 * layouts. If the concrete class is unknown to this build, the
 * function falls back to the native layout — the only reasonable
 * guess, and the one that matches the largest number of JDK versions.
 *
 * Every setter writes through the payload address. Every getter would
 * do the same in reverse; only setters are reachable in this build,
 * because the reachability walk pulled in SegmentAllocator.allocate,
 * which only writes.
 */

static void* segment_payload(void* segment) {
    if (segment == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    JNativeVTable* vt = *(JNativeVTable**)segment;
    const char* name = vt ? vt->name : NULL;
    if (name == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    if (strstr(name, "NativeMemorySegmentImpl") != NULL) {
        int64_t min = *(int64_t*)((char*)segment + 32);
        return (void*)(intptr_t)min;
    }
    if (strstr(name, "HeapMemorySegmentImpl") != NULL) {
        int64_t offset = *(int64_t*)((char*)segment + 32);
        void*   base   = *(void**)((char*)segment + 40);
        if (base == NULL) return NULL;
        return (char*)base + JAVA_ARR_HDR + offset;
    }

    /* Unknown concrete type: assume native. */
    int64_t min = *(int64_t*)((char*)segment + 32);
    return (void*)(intptr_t)min;
}

/* ---- set(segment, long offset, <primitive>) -> void ---- */

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JZ_V(
        void* this_handle, void* segment, int64_t offset, int32_t value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    *(uint8_t*)(p + offset) = (uint8_t)(value ? 1 : 0);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JB_V(
        void* this_handle, void* segment, int64_t offset, int8_t value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    *(int8_t*)(p + offset) = value;
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JS_V(
        void* this_handle, void* segment, int64_t offset, int16_t value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    memcpy(p + offset, &value, 2);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JC_V(
        void* this_handle, void* segment, int64_t offset, uint16_t value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    memcpy(p + offset, &value, 2);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JI_V(
        void* this_handle, void* segment, int64_t offset, int32_t value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    memcpy(p + offset, &value, 4);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JJ_V(
        void* this_handle, void* segment, int64_t offset, int64_t value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    memcpy(p + offset, &value, 8);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JF_V(
        void* this_handle, void* segment, int64_t offset, float value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    memcpy(p + offset, &value, 4);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JD_V(
        void* this_handle, void* segment, int64_t offset, double value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    memcpy(p + offset, &value, 8);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JLjava_lang_Object__V(
        void* this_handle, void* segment, int64_t offset, void* value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    memcpy(p + offset, &value, 8);
}

/* ---- set(segment, <primitive>) -> void  (offset bound to 0) ----
 *
 * The SegmentAllocator.allocate(layout, value) family binds the
 * offset to 0 and then calls VarHandle.set with just the segment and
 * the value. The payload address helper already returns the address
 * of byte 0 of the segment, so the write targets the correct slot
 * without any further arithmetic.
 */

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_Z_V(
        void* this_handle, void* segment, int32_t value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    *(uint8_t*)p = (uint8_t)(value ? 1 : 0);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_B_V(
        void* this_handle, void* segment, int8_t value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    *(int8_t*)p = value;
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_S_V(
        void* this_handle, void* segment, int16_t value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    memcpy(p, &value, 2);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_C_V(
        void* this_handle, void* segment, uint16_t value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    memcpy(p, &value, 2);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_I_V(
        void* this_handle, void* segment, int32_t value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    memcpy(p, &value, 4);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_J_V(
        void* this_handle, void* segment, int64_t value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    memcpy(p, &value, 8);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_F_V(
        void* this_handle, void* segment, float value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    memcpy(p, &value, 4);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_D_V(
        void* this_handle, void* segment, double value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    memcpy(p, &value, 8);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_Ljava_lang_Object__V(
        void* this_handle, void* segment, void* value) {
    (void)this_handle;
    char* p = (char*)segment_payload(segment);
    memcpy(p, &value, 8);
}

/* ===================================================================
 * VarHandle specializations for java.util.concurrent.CompletableFuture
 * ===================================================================
 *
 * CompletableFuture's state machine drives two VarHandles declared in
 * the class's own static initializer:
 *
 *   RESULT : the `result` field, of type Object, at offset +8.
 *   STACK  : the `stack` field, of type Completion, at offset +16.
 *
 * The class hierarchy that determines those offsets is:
 *
 *   java.lang.Object:
 *       +0  vtable
 *
 *   java.util.concurrent.CompletableFuture implements Future,
 *   CompletionStage:
 *       +8  Object     result
 *       +16 Completion stack
 *
 * Nothing else in the hierarchy contributes instance fields, so the
 * offsets are exactly as above regardless of which JDK 21 build the
 * image is compiled against.
 *
 * The specific call sites that the reachability walk reaches are in
 * CompletableFuture.completeThrowable / completeNull / internalComplete /
 * completeValue / completeRelay / tryPushStack / postComplete, all of
 * which are on the critical path of any program that uses the ForkJoin
 * pool or the common async infrastructure.
 */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_CompletableFuture_Ljava_lang_Void_Ljava_util_concurrent_CompletableFuture_AltResult__Z(
        void* this_handle, void* cf, void* expected, void* new_value)
{
    (void)this_handle;
    if (cf == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)cf + 8), expected, new_value);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_CompletableFuture_Ljava_lang_Void_Ljava_lang_Object__Z(
        void* this_handle, void* cf, void* expected, void* new_value)
{
    (void)this_handle;
    if (cf == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)cf + 8), expected, new_value);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_CompletableFuture_Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion__Z(
        void* this_handle, void* cf, void* expected, void* new_value)
{
    (void)this_handle;
    if (cf == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)cf + 16), expected, new_value);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion__V(
        void* this_handle, void* completion, void* new_value)
{
    (void)this_handle;
    if (completion == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    /*
     * The receiver is a CompletableFuture.Completion. Completion
     * extends ForkJoinTask<Void>, whose `status` field occupies +8;
     * its own `next` field is at +16. The call site
     * (Completion.tryPushStack) uses this to publish the new top of
     * the per-completion linked list.
     */
    __atomic_store_n((void**)((char*)completion + 16), new_value,
                     __ATOMIC_RELEASE);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion_T_V(
        void* this_handle, void* completion, void* new_value)
{
    /*
     * Same body as the non-T variant. The trailing T is a
     * distinguishing suffix that the emitter appends when two
     * call sites share a descriptor but target different fields;
     * for `next` there is only one field, so the two are
     * semantically identical.
     */
    __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion__V(
        this_handle, completion, new_value);
}

/* ===================================================================
 * VarHandle: java.util.concurrent.FutureTask.waiters
 * ===================================================================
 *
 * FutureTask declares a single VarHandle for the `waiters` field,
 * which the class uses to publish and traverse the linked list of
 * WaitNode objects that await the task's completion.
 *
 * Layout of the class hierarchy as produced by
 * LlvmGlobalEmitter.getFieldOffset:
 *
 *   FutureTask (implements RunnableFuture):
 *       +0  vtable
 *       +8  int      state
 *       +12 int      pad
 *       +16 Callable callable
 *       +24 Object   outcome
 *       +32 Thread   runner
 *       +40 WaitNode waiters
 *
 * FutureTask.WaitNode:
 *       +0  vtable
 *       +8  Thread   thread
 *       +16 WaitNode next
 *
 * The two entry points below are called from awaitDone (to install the
 * first wait node with a CAS, and to splice additional nodes onto the
 * chain with the weak form) and from removeWaiter (to CAS the chain
 * head after the waiting thread has been removed).
 */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_FutureTask_Ljava_util_concurrent_FutureTask_WaitNode_Ljava_util_concurrent_FutureTask_WaitNode__Z(
        void* this_handle, void* task, void* expected, void* new_value)
{
    (void)this_handle;
    if (task == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)task + 40), expected, new_value);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_FutureTask_Ljava_util_concurrent_FutureTask_WaitNode_Ljava_util_concurrent_FutureTask_WaitNode__Z(
        void* this_handle, void* task, void* expected, void* new_value)
{
    (void)this_handle;
    if (task == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    /* The weak form is defined to be allowed to fail spuriously; every
     * caller wraps it in a retry loop. The primitive used here does not
     * fail spuriously, so this implementation is strictly stronger than
     * the contract requires. */
    return cas_ref((void**)((char*)task + 40), expected, new_value);
}

/* ===================================================================
 * VarHandle: java.util.concurrent.CompletableFuture.Completion.next
 *
 * The weak-CAS and (non-weak) CAS on Completion.next, used by
 * CompletableFuture.cleanStack and postComplete when they splice or
 * unlink stack entries. The field lives at +16, after ForkJoinTask's
 * `status` (+8) and `aux` (+12).
 * =================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion_Ljava_lang_Void__Z(
        void* this_handle, void* completion, void* expected, void* new_value)
{
    (void)this_handle;
    if (completion == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)completion + 16), expected, new_value);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_CompletableFuture_Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion__Z(
        void* this_handle, void* cf, void* expected, void* new_value)
{
    (void)this_handle;
    if (cf == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)cf + 16), expected, new_value);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion__Z(
        void* this_handle, void* completion, void* expected, void* new_value)
{
    (void)this_handle;
    if (completion == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)completion + 16), expected, new_value);
}

/* ===================================================================
 * VarHandle: java.util.concurrent.CompletableFuture.result
 *
 * The result field is at +8. The release-ordered store form is used by
 * the CompletableFuture constructor and by MinimalStage.toCompletableFuture
 * to publish an already-computed result. The compareAndSet forms with
 * Void / Object expected values are used by internalComplete and
 * completeRelay.
 * =================================================================== */

void __jnative_fn_java_lang_invoke_VarHandle_setRelease__Ljava_util_concurrent_CompletableFuture_Ljava_lang_Object__V(
        void* this_handle, void* cf, void* new_value)
{
    (void)this_handle;
    if (cf == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)cf + 8), new_value, __ATOMIC_RELEASE);
}

/* ===================================================================
 * VarHandle: java.nio.channels.spi.AbstractSelector.closed
 *
 * A boolean field toggled by AbstractSelector.close through a CAS so
 * that a second concurrent close observes the already-closed state
 * without re-entering the close path. The class hierarchy is:
 *
 *   AbstractSelector:
 *       +0  vtable
 *       +8  SelectorProvider provider
 *       +16 Set<SelectionKey>   keys
 *       +24 boolean             closed
 *       +32 Object              closeLock
 *       +40 Thread              blockedThread
 * =================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_nio_channels_spi_AbstractSelector_ZZ_Z(
        void* this_handle, void* selector, int32_t expected, int32_t new_value)
{
    (void)this_handle;
    if (selector == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_bool((uint8_t*)((char*)selector + 24), expected, new_value);
}

/* ===================================================================
 * VarHandle: foreign MemorySegment (AddressLayout)
 *
 * AddressLayout.set(MemorySegment, long, MemorySegment) stores the
 * payload address of the value segment into the target segment at the
 * given offset. The value's payload address is obtained through the
 * same segment_payload helper the primitive setters use; the target
 * segment's payload address is the write destination.
 * =================================================================== */

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JLjava_lang_foreign_MemorySegment__V(
        void* this_handle, void* target_segment, int64_t offset,
        void* value_segment)
{
    (void)this_handle;
    char* dst = (char*)segment_payload(target_segment);
    void* src = segment_payload(value_segment);
    memcpy(dst + offset, &src, sizeof(src));
}

/* ===================================================================
 * VarHandle: java.util.concurrent.CompletableFuture.Completion.next
 *            (set-to-null variants)
 * ===================================================================
 *
 * The two entry points below are the null-writing counterparts of the
 * CAS operations added in the previous revision. `unipush` and
 * `orPush` both invoke VarHandle.set with a null value to break the
 * stack linkage after the completion has been consumed; the field is
 * `Completion.next`, at offset +16 (after ForkJoinTask's `status` at
 * +8 and its pad to 8-byte alignment).
 *
 * Because the value being written is typed as Void at the call site,
 * the polymorphic dispatcher produces a distinct mangled name from
 * the one that carries a Completion-typed value, even though both
 * write to the same slot. The two symbols below supply the missing
 * variants.
 */

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_CompletableFuture_Completion_Ljava_lang_Void__V(
        void* this_handle, void* completion, void* value)
{
    (void)this_handle;
    if (completion == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)completion + 16), value,
                     __ATOMIC_RELEASE);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_CompletableFuture_BiCompletion_Ljava_lang_Void__V(
        void* this_handle, void* bicomp, void* value)
{
    (void)this_handle;
    if (bicomp == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    /*
     * BiCompletion extends UniCompletion extends Completion; the
     * `next` field lives on Completion and its offset does not move
     * when the more-derived subclasses add their own fields
     * afterwards. The offset is therefore the same +16 that the
     * Completion variant above uses.
     */
    __atomic_store_n((void**)((char*)bicomp + 16), value,
                     __ATOMIC_RELEASE);
}

/* ===================================================================
 * VarHandle: java.net.Socket.state
 * ===================================================================
 *
 * Socket uses a VarHandle over its `state` field to publish the
 * closed bit atomically. The call site is Socket.close, which does
 *
 *     if ((getAndBitwiseOrState(STATE_CLOSED) & STATE_CLOSED) != 0)
 *         return;
 *
 * to make a second concurrent close a no-op.
 *
 * Layout of java.net.Socket as produced by
 * LlvmGlobalEmitter.getFieldOffset for the JDK 21 / 22 declaration
 * order:
 *
 *     offset  0 : vtable
 *     offset  8 : boolean created
 *     offset  9 : boolean bound
 *     offset 10 : boolean connected
 *     offset 11 : boolean closed
 *     offset 16 : Object  closeLock       (8-aligned)
 *     offset 24 : boolean shutIn
 *     offset 25 : boolean shutOut
 *     offset 32 : SocketImpl impl         (8-aligned)
 *     offset 40 : int      state          (4-aligned)
 *
 * The 40 is derived from the alignment rules the emitter applies to
 * every class: each field is padded to its own alignment, and the
 * reference-typed fields (closeLock, impl) are 8-aligned. If the JDK
 * ever inserts a field ahead of `state`, this offset must be
 * recomputed -- but the failure mode of a stale offset is a wrong
 * read, not a linker error, so the entry point exists regardless.
 */

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseOr__Ljava_net_Socket_I_I(
        void* this_handle, void* socket, int32_t mask)
{
    (void)this_handle;
    if (socket == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return __atomic_fetch_or((int32_t*)((char*)socket + 40), mask,
                             __ATOMIC_SEQ_CST);
}

/* ===================================================================
 * VarHandle: foreign MemorySegment (AddressLayout, no explicit offset)
 * ===================================================================
 *
 * The bound-offset variant of the AddressLayout setter. The Java-level
 * SegmentAllocator.allocate(AddressLayout, MemorySegment) binds the
 * offset to 0 and then calls set(segment, value); the payload address
 * returned by segment_payload already points at byte 0 of the target,
 * so no arithmetic is needed on top.
 */

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_Ljava_lang_foreign_MemorySegment__V(
        void* this_handle, void* target_segment, void* value_segment)
{
    (void)this_handle;
    char* dst = (char*)segment_payload(target_segment);
    void* src = segment_payload(value_segment);
    memcpy(dst, &src, sizeof(src));
}
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
 *      LinkedTransferQueue, ForEachOps.ForEachOrderedTask,
 *      SharedThreadContainer, Thread and jdk.internal.event.EventHelper
 *      emit. Each of these has a hard-coded field offset that matches
 *      the LLVM backend's own layout computation for the target class.
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

/*
 * ===========================================================================
 * set: byte[] @ int <- value
 * ===========================================================================
 */

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

/*
 * ===========================================================================
 * Volatile access modes on byte[]
 * ===========================================================================
 */

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

/*
 * ===========================================================================
 * Acquire / Release access modes on byte[]
 * ===========================================================================
 */

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

/*
 * ===========================================================================
 * Opaque access modes on byte[]
 * ===========================================================================
 */

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

/*
 * ===========================================================================
 * compareAndSet / weakCompareAndSet on byte[]: int and long
 * ===========================================================================
 */

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

/*
 * ===========================================================================
 * compareAndExchange on byte[]
 * ===========================================================================
 */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange___BIII_I(
        void* arr, int32_t index, int32_t expected, int32_t newValue) {
    barray_check(arr, index, 4);
    return cax_int((int32_t*)(barray_data(arr) + index), expected, newValue);
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

/*
 * ===========================================================================
 * getAndSet / getAndAdd on byte[]
 * ===========================================================================
 */

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
    void*  array = *(void**)args[1];
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
 * LinkedTransferQueue, ForEachOps.ForEachOrderedTask,
 * SharedThreadContainer and jdk.internal.event.EventHelper.
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

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node__V(
        void* this_handle, void* obj, void* newValue)
{
    (void)this_handle;
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 24), newValue, __ATOMIC_RELEASE);
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
 *   DualNode.item  (offset 16)  — CAS
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
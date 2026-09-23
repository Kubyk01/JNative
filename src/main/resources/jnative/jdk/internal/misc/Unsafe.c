#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <errno.h>
#include <stdatomic.h>
#include <time.h>
#include <unistd.h>
#include <ctype.h>
#include <dlfcn.h>
#include <inttypes.h>
#include "jnative_runtime.h"

/*
 * ABI convention for every instance native in this file:
 *
 *     return_type __jnative_fn_<class>_<method>_<descriptor>(
 *             void* this_unsafe,   <-- receiver, always first
 *             <java params...>);
 *
 * The LLVM code generator emits every instance call as
 *     call ret @__jnative_fn_...(<receiver>, <args...>)
 * so the C definition MUST have a slot for the receiver. Omitting it
 * shifts every argument by one word and produces garbage effective
 * addresses at runtime (this was the cause of the ConcurrentHashMap
 * initTable crash: the CAS received (Unsafe, CHM, SIZECTL, sc, -1) and
 * computed effective_address(Unsafe, CHM) = Unsafe + CHM).
 *
 * The static entry point `registerNatives()` is the only method in this
 * file without a receiver.
 */

static inline void* effective_address(void* obj, int64_t offset) {
    return (obj == NULL) ? (void*)(uintptr_t)offset : (char*)obj + offset;
}

/* ---- Plain loads ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getInt__Ljava_lang_Object_J_I(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    return *(int32_t*)effective_address(obj, offset);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getLong__Ljava_lang_Object_J_J(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    return *(int64_t*)effective_address(obj, offset);
}
void* __jnative_fn_jdk_internal_misc_Unsafe_getReference__Ljava_lang_Object_J_Ljava_lang_Object_(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    return *(void**)effective_address(obj, offset);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getBoolean__Ljava_lang_Object_J_Z(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    return *(uint8_t*)effective_address(obj, offset);
}
int8_t __jnative_fn_jdk_internal_misc_Unsafe_getByte__Ljava_lang_Object_J_B(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    return *(int8_t*)effective_address(obj, offset);
}
int16_t __jnative_fn_jdk_internal_misc_Unsafe_getShort__Ljava_lang_Object_J_S(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    return *(int16_t*)effective_address(obj, offset);
}
uint16_t __jnative_fn_jdk_internal_misc_Unsafe_getChar__Ljava_lang_Object_J_C(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    return *(uint16_t*)effective_address(obj, offset);
}
float __jnative_fn_jdk_internal_misc_Unsafe_getFloat__Ljava_lang_Object_J_F(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    return *(float*)effective_address(obj, offset);
}
double __jnative_fn_jdk_internal_misc_Unsafe_getDouble__Ljava_lang_Object_J_D(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    return *(double*)effective_address(obj, offset);
}

/* ---- Plain stores ---- */
void __jnative_fn_jdk_internal_misc_Unsafe_putInt__Ljava_lang_Object_JI_V(void* this_unsafe, void* obj, int64_t offset, int32_t x) {
    (void)this_unsafe;
    *(int32_t*)effective_address(obj, offset) = x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putLong__Ljava_lang_Object_JJ_V(void* this_unsafe, void* obj, int64_t offset, int64_t x) {
    (void)this_unsafe;
    *(int64_t*)effective_address(obj, offset) = x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putReference__Ljava_lang_Object_JLjava_lang_Object__V(void* this_unsafe, void* obj, int64_t offset, void* x) {
    (void)this_unsafe;
    *(void**)effective_address(obj, offset) = x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putBoolean__Ljava_lang_Object_JZ_V(void* this_unsafe, void* obj, int64_t offset, int32_t x) {
    (void)this_unsafe;
    *(uint8_t*)effective_address(obj, offset) = (uint8_t)x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putByte__Ljava_lang_Object_JB_V(void* this_unsafe, void* obj, int64_t offset, int8_t x) {
    (void)this_unsafe;
    *(int8_t*)effective_address(obj, offset) = x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putShort__Ljava_lang_Object_JS_V(void* this_unsafe, void* obj, int64_t offset, int16_t x) {
    (void)this_unsafe;
    *(int16_t*)effective_address(obj, offset) = x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putChar__Ljava_lang_Object_JC_V(void* this_unsafe, void* obj, int64_t offset, uint16_t x) {
    (void)this_unsafe;
    *(uint16_t*)effective_address(obj, offset) = x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putFloat__Ljava_lang_Object_JF_V(void* this_unsafe, void* obj, int64_t offset, float x) {
    (void)this_unsafe;
    *(float*)effective_address(obj, offset) = x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putDouble__Ljava_lang_Object_JD_V(void* this_unsafe, void* obj, int64_t offset, double x) {
    (void)this_unsafe;
    *(double*)effective_address(obj, offset) = x;
}

/* ---- Volatile loads ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getIntVolatile__Ljava_lang_Object_J_I(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    int32_t v; __atomic_load((int32_t*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getLongVolatile__Ljava_lang_Object_J_J(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    int64_t v; __atomic_load((int64_t*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
void* __jnative_fn_jdk_internal_misc_Unsafe_getReferenceVolatile__Ljava_lang_Object_J_Ljava_lang_Object_(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    void* v; __atomic_load((void**)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getBooleanVolatile__Ljava_lang_Object_J_Z(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    uint8_t v; __atomic_load((uint8_t*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
int8_t __jnative_fn_jdk_internal_misc_Unsafe_getByteVolatile__Ljava_lang_Object_J_B(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    int8_t v; __atomic_load((int8_t*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
int16_t __jnative_fn_jdk_internal_misc_Unsafe_getShortVolatile__Ljava_lang_Object_J_S(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    int16_t v; __atomic_load((int16_t*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
uint16_t __jnative_fn_jdk_internal_misc_Unsafe_getCharVolatile__Ljava_lang_Object_J_C(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    uint16_t v; __atomic_load((uint16_t*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
float __jnative_fn_jdk_internal_misc_Unsafe_getFloatVolatile__Ljava_lang_Object_J_F(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    float v; __atomic_load((float*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
double __jnative_fn_jdk_internal_misc_Unsafe_getDoubleVolatile__Ljava_lang_Object_J_D(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    double v; __atomic_load((double*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}

/* ---- Volatile stores ---- */
void __jnative_fn_jdk_internal_misc_Unsafe_putIntVolatile__Ljava_lang_Object_JI_V(void* this_unsafe, void* obj, int64_t offset, int32_t x) {
    (void)this_unsafe;
    __atomic_store((int32_t*)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putLongVolatile__Ljava_lang_Object_JJ_V(void* this_unsafe, void* obj, int64_t offset, int64_t x) {
    (void)this_unsafe;
    __atomic_store((int64_t*)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putReferenceVolatile__Ljava_lang_Object_JLjava_lang_Object__V(void* this_unsafe, void* obj, int64_t offset, void* x) {
    (void)this_unsafe;
    __atomic_store((void**)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putBooleanVolatile__Ljava_lang_Object_JZ_V(void* this_unsafe, void* obj, int64_t offset, int32_t x) {
    (void)this_unsafe;
    uint8_t v = (uint8_t)x;
    __atomic_store((uint8_t*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putByteVolatile__Ljava_lang_Object_JB_V(void* this_unsafe, void* obj, int64_t offset, int8_t x) {
    (void)this_unsafe;
    __atomic_store((int8_t*)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putShortVolatile__Ljava_lang_Object_JS_V(void* this_unsafe, void* obj, int64_t offset, int16_t x) {
    (void)this_unsafe;
    __atomic_store((int16_t*)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putCharVolatile__Ljava_lang_Object_JC_V(void* this_unsafe, void* obj, int64_t offset, uint16_t x) {
    (void)this_unsafe;
    __atomic_store((uint16_t*)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putFloatVolatile__Ljava_lang_Object_JF_V(void* this_unsafe, void* obj, int64_t offset, float x) {
    (void)this_unsafe;
    __atomic_store((float*)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putDoubleVolatile__Ljava_lang_Object_JD_V(void* this_unsafe, void* obj, int64_t offset, double x) {
    (void)this_unsafe;
    __atomic_store((double*)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}

/* ---- Ordered (release) stores ---- */
void __jnative_fn_jdk_internal_misc_Unsafe_putOrderedInt__Ljava_lang_Object_JI_V(void* this_unsafe, void* obj, int64_t offset, int32_t x) {
    (void)this_unsafe;
    __atomic_store((int32_t*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putOrderedLong__Ljava_lang_Object_JJ_V(void* this_unsafe, void* obj, int64_t offset, int64_t x) {
    (void)this_unsafe;
    __atomic_store((int64_t*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putOrderedObject__Ljava_lang_Object_JLjava_lang_Object__V(void* this_unsafe, void* obj, int64_t offset, void* x) {
    (void)this_unsafe;
    __atomic_store((void**)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}

/* ---- CAS ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetInt__Ljava_lang_Object_JII_Z(void* this_unsafe, void* obj, int64_t offset, int32_t expected, int32_t x) {
    (void)this_unsafe;
    int32_t exp = expected;
    return __atomic_compare_exchange_n((int32_t*)effective_address(obj, offset), &exp, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetLong__Ljava_lang_Object_JJJ_Z(void* this_unsafe, void* obj, int64_t offset, int64_t expected, int64_t x) {
    (void)this_unsafe;
    int64_t exp = expected;
    return __atomic_compare_exchange_n((int64_t*)effective_address(obj, offset), &exp, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetReference__Ljava_lang_Object_JLjava_lang_Object_Ljava_lang_Object__Z(void* this_unsafe, void* obj, int64_t offset, void* expected, void* x) {
    (void)this_unsafe;
    void* exp = expected;
    return __atomic_compare_exchange_n((void**)effective_address(obj, offset), &exp, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetByte__Ljava_lang_Object_JBB_Z(void* this_unsafe, void* obj, int64_t offset, int8_t expected, int8_t x) {
    (void)this_unsafe;
    int8_t exp = expected;
    return __atomic_compare_exchange_n((int8_t*)effective_address(obj, offset), &exp, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetShort__Ljava_lang_Object_JSS_Z(void* this_unsafe, void* obj, int64_t offset, int16_t expected, int16_t x) {
    (void)this_unsafe;
    int16_t exp = expected;
    return __atomic_compare_exchange_n((int16_t*)effective_address(obj, offset), &exp, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetChar__Ljava_lang_Object_JCC_Z(void* this_unsafe, void* obj, int64_t offset, uint16_t expected, uint16_t x) {
    (void)this_unsafe;
    uint16_t exp = expected;
    return __atomic_compare_exchange_n((uint16_t*)effective_address(obj, offset), &exp, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetFloat__Ljava_lang_Object_JFF_Z(void* this_unsafe, void* obj, int64_t offset, float expected, float x) {
    (void)this_unsafe;
    uint32_t exp; memcpy(&exp, &expected, 4);
    uint32_t val; memcpy(&val, &x, 4);
    return __atomic_compare_exchange_n((uint32_t*)effective_address(obj, offset), &exp, val, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetDouble__Ljava_lang_Object_JDD_Z(void* this_unsafe, void* obj, int64_t offset, double expected, double x) {
    (void)this_unsafe;
    uint64_t exp; memcpy(&exp, &expected, 8);
    uint64_t val; memcpy(&val, &x, 8);
    return __atomic_compare_exchange_n((uint64_t*)effective_address(obj, offset), &exp, val, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

/* ---- compareAndExchange ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndExchangeInt__Ljava_lang_Object_JII_I(void* this_unsafe, void* obj, int64_t offset, int32_t expected, int32_t x) {
    (void)this_unsafe;
    int32_t witness = expected;
    __atomic_compare_exchange_n((int32_t*)effective_address(obj, offset), &witness, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndExchangeLong__Ljava_lang_Object_JJJ_J(void* this_unsafe, void* obj, int64_t offset, int64_t expected, int64_t x) {
    (void)this_unsafe;
    int64_t witness = expected;
    __atomic_compare_exchange_n((int64_t*)effective_address(obj, offset), &witness, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}
void* __jnative_fn_jdk_internal_misc_Unsafe_compareAndExchangeReference__Ljava_lang_Object_JLjava_lang_Object_Ljava_lang_Object__Ljava_lang_Object_(void* this_unsafe, void* obj, int64_t offset, void* expected, void* x) {
    (void)this_unsafe;
    void* witness = expected;
    __atomic_compare_exchange_n((void**)effective_address(obj, offset), &witness, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}
int8_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndExchangeByte__Ljava_lang_Object_JBB_B(void* this_unsafe, void* obj, int64_t offset, int8_t expected, int8_t x) {
    (void)this_unsafe;
    int8_t witness = expected;
    __atomic_compare_exchange_n((int8_t*)effective_address(obj, offset), &witness, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}
int16_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndExchangeShort__Ljava_lang_Object_JSS_S(void* this_unsafe, void* obj, int64_t offset, int16_t expected, int16_t x) {
    (void)this_unsafe;
    int16_t witness = expected;
    __atomic_compare_exchange_n((int16_t*)effective_address(obj, offset), &witness, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}
uint16_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndExchangeChar__Ljava_lang_Object_JCC_C(void* this_unsafe, void* obj, int64_t offset, uint16_t expected, uint16_t x) {
    (void)this_unsafe;
    uint16_t witness = expected;
    __atomic_compare_exchange_n((uint16_t*)effective_address(obj, offset), &witness, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}

/* ---- Weak CAS ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_weakCompareAndSetInt__Ljava_lang_Object_JII_Z(void* this_unsafe, void* obj, int64_t offset, int32_t expected, int32_t x) {
    return __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetInt__Ljava_lang_Object_JII_Z(this_unsafe, obj, offset, expected, x);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_weakCompareAndSetLong__Ljava_lang_Object_JJJ_Z(void* this_unsafe, void* obj, int64_t offset, int64_t expected, int64_t x) {
    return __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetLong__Ljava_lang_Object_JJJ_Z(this_unsafe, obj, offset, expected, x);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_weakCompareAndSetReference__Ljava_lang_Object_JLjava_lang_Object_Ljava_lang_Object__Z(void* this_unsafe, void* obj, int64_t offset, void* expected, void* x) {
    return __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetReference__Ljava_lang_Object_JLjava_lang_Object_Ljava_lang_Object__Z(this_unsafe, obj, offset, expected, x);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_weakCompareAndSetByte__Ljava_lang_Object_JBB_Z(void* this_unsafe, void* obj, int64_t offset, int8_t expected, int8_t x) {
    return __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetByte__Ljava_lang_Object_JBB_Z(this_unsafe, obj, offset, expected, x);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_weakCompareAndSetShort__Ljava_lang_Object_JSS_Z(void* this_unsafe, void* obj, int64_t offset, int16_t expected, int16_t x) {
    return __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetShort__Ljava_lang_Object_JSS_Z(this_unsafe, obj, offset, expected, x);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_weakCompareAndSetChar__Ljava_lang_Object_JCC_Z(void* this_unsafe, void* obj, int64_t offset, uint16_t expected, uint16_t x) {
    return __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetChar__Ljava_lang_Object_JCC_Z(this_unsafe, obj, offset, expected, x);
}

/* ---- getAndAdd ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getAndAddInt__Ljava_lang_Object_JI_I(void* this_unsafe, void* obj, int64_t offset, int32_t delta) {
    (void)this_unsafe;
    return __atomic_fetch_add((int32_t*)effective_address(obj, offset), delta, __ATOMIC_SEQ_CST);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getAndAddLong__Ljava_lang_Object_JJ_J(void* this_unsafe, void* obj, int64_t offset, int64_t delta) {
    (void)this_unsafe;
    return __atomic_fetch_add((int64_t*)effective_address(obj, offset), delta, __ATOMIC_SEQ_CST);
}
int8_t __jnative_fn_jdk_internal_misc_Unsafe_getAndAddByte__Ljava_lang_Object_JB_B(void* this_unsafe, void* obj, int64_t offset, int8_t delta) {
    (void)this_unsafe;
    return __atomic_fetch_add((int8_t*)effective_address(obj, offset), delta, __ATOMIC_SEQ_CST);
}
int16_t __jnative_fn_jdk_internal_misc_Unsafe_getAndAddShort__Ljava_lang_Object_JS_S(void* this_unsafe, void* obj, int64_t offset, int16_t delta) {
    (void)this_unsafe;
    return __atomic_fetch_add((int16_t*)effective_address(obj, offset), delta, __ATOMIC_SEQ_CST);
}

/* ---- getAndSet ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getAndSetInt__Ljava_lang_Object_JI_I(void* this_unsafe, void* obj, int64_t offset, int32_t newValue) {
    (void)this_unsafe;
    int32_t old;
    __atomic_exchange((int32_t*)effective_address(obj, offset), &newValue, &old, __ATOMIC_SEQ_CST);
    return old;
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getAndSetLong__Ljava_lang_Object_JJ_J(void* this_unsafe, void* obj, int64_t offset, int64_t newValue) {
    (void)this_unsafe;
    int64_t old;
    __atomic_exchange((int64_t*)effective_address(obj, offset), &newValue, &old, __ATOMIC_SEQ_CST);
    return old;
}
void* __jnative_fn_jdk_internal_misc_Unsafe_getAndSetReference__Ljava_lang_Object_JLjava_lang_Object_Ljava_lang_Object_(void* this_unsafe, void* obj, int64_t offset, void* newValue) {
    (void)this_unsafe;
    void* old;
    __atomic_exchange((void**)effective_address(obj, offset), &newValue, &old, __ATOMIC_SEQ_CST);
    return old;
}
int8_t __jnative_fn_jdk_internal_misc_Unsafe_getAndSetByte__Ljava_lang_Object_JB_B(void* this_unsafe, void* obj, int64_t offset, int8_t newValue) {
    (void)this_unsafe;
    int8_t old;
    __atomic_exchange((int8_t*)effective_address(obj, offset), &newValue, &old, __ATOMIC_SEQ_CST);
    return old;
}
int16_t __jnative_fn_jdk_internal_misc_Unsafe_getAndSetShort__Ljava_lang_Object_JS_S(void* this_unsafe, void* obj, int64_t offset, int16_t newValue) {
    (void)this_unsafe;
    int16_t old;
    __atomic_exchange((int16_t*)effective_address(obj, offset), &newValue, &old, __ATOMIC_SEQ_CST);
    return old;
}

/* ---- Bitwise atomic ops (Java 9+) ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getAndBitwiseAndInt__Ljava_lang_Object_JI_I(void* this_unsafe, void* obj, int64_t offset, int32_t mask) {
    (void)this_unsafe;
    return __atomic_fetch_and((int32_t*)effective_address(obj, offset), mask, __ATOMIC_SEQ_CST);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getAndBitwiseOrInt__Ljava_lang_Object_JI_I(void* this_unsafe, void* obj, int64_t offset, int32_t mask) {
    (void)this_unsafe;
    return __atomic_fetch_or((int32_t*)effective_address(obj, offset), mask, __ATOMIC_SEQ_CST);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getAndBitwiseXorInt__Ljava_lang_Object_JI_I(void* this_unsafe, void* obj, int64_t offset, int32_t mask) {
    (void)this_unsafe;
    return __atomic_fetch_xor((int32_t*)effective_address(obj, offset), mask, __ATOMIC_SEQ_CST);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getAndBitwiseAndLong__Ljava_lang_Object_JJ_J(void* this_unsafe, void* obj, int64_t offset, int64_t mask) {
    (void)this_unsafe;
    return __atomic_fetch_and((int64_t*)effective_address(obj, offset), mask, __ATOMIC_SEQ_CST);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getAndBitwiseOrLong__Ljava_lang_Object_JJ_J(void* this_unsafe, void* obj, int64_t offset, int64_t mask) {
    (void)this_unsafe;
    return __atomic_fetch_or((int64_t*)effective_address(obj, offset), mask, __ATOMIC_SEQ_CST);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getAndBitwiseXorLong__Ljava_lang_Object_JJ_J(void* this_unsafe, void* obj, int64_t offset, int64_t mask) {
    (void)this_unsafe;
    return __atomic_fetch_xor((int64_t*)effective_address(obj, offset), mask, __ATOMIC_SEQ_CST);
}

/* ---- Acquire/Release variants ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getIntAcquire__Ljava_lang_Object_J_I(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    int32_t v; __atomic_load((int32_t*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putIntRelease__Ljava_lang_Object_JI_V(void* this_unsafe, void* obj, int64_t offset, int32_t x) {
    (void)this_unsafe;
    __atomic_store((int32_t*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getLongAcquire__Ljava_lang_Object_J_J(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    int64_t v; __atomic_load((int64_t*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putLongRelease__Ljava_lang_Object_JJ_V(void* this_unsafe, void* obj, int64_t offset, int64_t x) {
    (void)this_unsafe;
    __atomic_store((int64_t*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
void* __jnative_fn_jdk_internal_misc_Unsafe_getReferenceAcquire__Ljava_lang_Object_J_Ljava_lang_Object_(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    void* v; __atomic_load((void**)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putReferenceRelease__Ljava_lang_Object_JLjava_lang_Object__V(void* this_unsafe, void* obj, int64_t offset, void* x) {
    (void)this_unsafe;
    __atomic_store((void**)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getBooleanAcquire__Ljava_lang_Object_J_Z(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    uint8_t v; __atomic_load((uint8_t*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putBooleanRelease__Ljava_lang_Object_JZ_V(void* this_unsafe, void* obj, int64_t offset, int32_t x) {
    (void)this_unsafe;
    uint8_t v = (uint8_t)x; __atomic_store((uint8_t*)effective_address(obj, offset), &v, __ATOMIC_RELEASE);
}
int8_t __jnative_fn_jdk_internal_misc_Unsafe_getByteAcquire__Ljava_lang_Object_J_B(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    int8_t v; __atomic_load((int8_t*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putByteRelease__Ljava_lang_Object_JB_V(void* this_unsafe, void* obj, int64_t offset, int8_t x) {
    (void)this_unsafe;
    __atomic_store((int8_t*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
int16_t __jnative_fn_jdk_internal_misc_Unsafe_getShortAcquire__Ljava_lang_Object_J_S(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    int16_t v; __atomic_load((int16_t*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putShortRelease__Ljava_lang_Object_JS_V(void* this_unsafe, void* obj, int64_t offset, int16_t x) {
    (void)this_unsafe;
    __atomic_store((int16_t*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
uint16_t __jnative_fn_jdk_internal_misc_Unsafe_getCharAcquire__Ljava_lang_Object_J_C(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    uint16_t v; __atomic_load((uint16_t*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putCharRelease__Ljava_lang_Object_JC_V(void* this_unsafe, void* obj, int64_t offset, uint16_t x) {
    (void)this_unsafe;
    __atomic_store((uint16_t*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
float __jnative_fn_jdk_internal_misc_Unsafe_getFloatAcquire__Ljava_lang_Object_J_F(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    float v; __atomic_load((float*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putFloatRelease__Ljava_lang_Object_JF_V(void* this_unsafe, void* obj, int64_t offset, float x) {
    (void)this_unsafe;
    __atomic_store((float*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
double __jnative_fn_jdk_internal_misc_Unsafe_getDoubleAcquire__Ljava_lang_Object_J_D(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    double v; __atomic_load((double*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putDoubleRelease__Ljava_lang_Object_JD_V(void* this_unsafe, void* obj, int64_t offset, double x) {
    (void)this_unsafe;
    __atomic_store((double*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}

/* ---- Opaque variants ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getIntOpaque__Ljava_lang_Object_J_I(void* this_unsafe, void* obj, int64_t offset) {
    (void)this_unsafe;
    int32_t v;
    __atomic_load((int32_t*)effective_address(obj, offset), &v, __ATOMIC_RELAXED);
    return v;
}

/* ---- Direct absolute-address access ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getInt__J_I(void* this_unsafe, void* addr) { (void)this_unsafe; return *(int32_t*)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putInt__JI_V(void* this_unsafe, void* addr, int32_t x) { (void)this_unsafe; *(int32_t*)addr = x; }
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getLong__J_J(void* this_unsafe, void* addr) { (void)this_unsafe; return *(int64_t*)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putLong__JJ_V(void* this_unsafe, void* addr, int64_t x) { (void)this_unsafe; *(int64_t*)addr = x; }
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getAddress__J_J(void* this_unsafe, void* addr) { (void)this_unsafe; return (int64_t)(uintptr_t)*(void**)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putAddress__JJ_V(void* this_unsafe, void* addr, int64_t x) { (void)this_unsafe; *(void**)addr = (void*)(uintptr_t)x; }
int8_t __jnative_fn_jdk_internal_misc_Unsafe_getByte__J_B(void* this_unsafe, void* addr) { (void)this_unsafe; return *(int8_t*)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putByte__JB_V(void* this_unsafe, void* addr, int8_t x) { (void)this_unsafe; *(int8_t*)addr = x; }
int16_t __jnative_fn_jdk_internal_misc_Unsafe_getShort__J_S(void* this_unsafe, void* addr) { (void)this_unsafe; return *(int16_t*)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putShort__JS_V(void* this_unsafe, void* addr, int16_t x) { (void)this_unsafe; *(int16_t*)addr = x; }
uint16_t __jnative_fn_jdk_internal_misc_Unsafe_getChar__J_C(void* this_unsafe, void* addr) { (void)this_unsafe; return *(uint16_t*)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putChar__JC_V(void* this_unsafe, void* addr, uint16_t x) { (void)this_unsafe; *(uint16_t*)addr = x; }
float __jnative_fn_jdk_internal_misc_Unsafe_getFloat__J_F(void* this_unsafe, void* addr) { (void)this_unsafe; return *(float*)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putFloat__JF_V(void* this_unsafe, void* addr, float x) { (void)this_unsafe; *(float*)addr = x; }
double __jnative_fn_jdk_internal_misc_Unsafe_getDouble__J_D(void* this_unsafe, void* addr) { (void)this_unsafe; return *(double*)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putDouble__JD_V(void* this_unsafe, void* addr, double x) { (void)this_unsafe; *(double*)addr = x; }

/* ---- Memory management ---- */
void* __jnative_fn_jdk_internal_misc_Unsafe_allocateMemory__J_J(void* this_unsafe, int64_t bytes) {
    (void)this_unsafe;
    if (bytes < 0) return NULL;
    void* p = malloc((size_t)bytes);
    if (!p) __jnative_throw_exception(NULL);
    return p;
}
void* __jnative_fn_jdk_internal_misc_Unsafe_reallocateMemory__JJ_J(void* this_unsafe, void* addr, int64_t bytes) {
    (void)this_unsafe;
    if (bytes < 0) return NULL;
    void* p = realloc(addr, (size_t)bytes);
    if (!p) __jnative_throw_exception(NULL);
    return p;
}
void __jnative_fn_jdk_internal_misc_Unsafe_freeMemory__J_V(void* this_unsafe, void* addr) {
    (void)this_unsafe;
    free(addr);
}
void __jnative_fn_jdk_internal_misc_Unsafe_setMemory__JJB_V(void* this_unsafe, void* addr, int64_t bytes, int8_t value) {
    (void)this_unsafe;
    memset(addr, value, (size_t)bytes);
}
void __jnative_fn_jdk_internal_misc_Unsafe_setMemory__Ljava_lang_Object_JJBB_V(void* this_unsafe, void* obj, int64_t offset, int64_t bytes, int8_t value) {
    (void)this_unsafe;
    memset(effective_address(obj, offset), value, (size_t)bytes);
}
void __jnative_fn_jdk_internal_misc_Unsafe_copyMemory__JJJ_V(void* this_unsafe, void* src, void* dst, int64_t bytes) {
    (void)this_unsafe;
    memmove(dst, src, (size_t)bytes);
}
void __jnative_fn_jdk_internal_misc_Unsafe_copyMemory__Ljava_lang_Object_JLjava_lang_Object_JJ_V(
        void* this_unsafe,
        void* srcBase, int64_t srcOffset,
        void* dstBase, int64_t dstOffset,
        int64_t bytes) {
    (void)this_unsafe;
    memmove(effective_address(dstBase, dstOffset),
            effective_address(srcBase, srcOffset),
            (size_t)bytes);
}
void __jnative_fn_jdk_internal_misc_Unsafe_copySwapMemory0__Ljava_lang_Object_JLjava_lang_Object_JJJ_V(void* this_unsafe, void* srcBase, int64_t srcOffset, void* dstBase, int64_t dstOffset, int64_t bytes, int64_t elemSize) {
    (void)this_unsafe;
    char* s = (char*)effective_address(srcBase, srcOffset);
    char* d = (char*)effective_address(dstBase, dstOffset);
    if (elemSize <= 1) { memmove(d, s, (size_t)bytes); return; }
    int64_t n = bytes / elemSize;
    for (int64_t i = 0; i < n; i++) {
        for (int64_t j = 0; j < elemSize; j++) {
            d[i * elemSize + j] = s[i * elemSize + (elemSize - 1 - j)];
        }
    }
}

/* ---- Fences ---- */
void __jnative_fn_jdk_internal_misc_Unsafe_loadFence___V(void* this_unsafe) { (void)this_unsafe; __atomic_thread_fence(__ATOMIC_ACQUIRE); }
void __jnative_fn_jdk_internal_misc_Unsafe_storeFence___V(void* this_unsafe) { (void)this_unsafe; __atomic_thread_fence(__ATOMIC_RELEASE); }
void __jnative_fn_jdk_internal_misc_Unsafe_fullFence___V(void* this_unsafe) { (void)this_unsafe; __atomic_thread_fence(__ATOMIC_SEQ_CST); }

/* ---- Park / Unpark (per-thread) ---- */
typedef struct ParkEntry {
    pthread_t thread;
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    int permit;
    int parked;
    struct ParkEntry* next;
} ParkEntry;

static pthread_mutex_t park_table_lock = PTHREAD_MUTEX_INITIALIZER;
static ParkEntry* park_table = NULL;

static ParkEntry* get_park_entry(pthread_t t) {
    pthread_mutex_lock(&park_table_lock);
    for (ParkEntry* e = park_table; e; e = e->next) {
        if (pthread_equal(e->thread, t)) {
            pthread_mutex_unlock(&park_table_lock);
            return e;
        }
    }
    ParkEntry* e = calloc(1, sizeof(ParkEntry));
    if (e) {
        e->thread = t;
        pthread_mutex_init(&e->mutex, NULL);
        pthread_cond_init(&e->cond, NULL);
        e->next = park_table;
        park_table = e;
    }
    pthread_mutex_unlock(&park_table_lock);
    return e;
}

void __jnative_fn_jdk_internal_misc_Unsafe_park__ZJ_V(void* this_unsafe, int32_t isAbsolute, int64_t time) {
    (void)this_unsafe;
    int abs = isAbsolute ? 1 : 0;
    ParkEntry* e = get_park_entry(pthread_self());
    if (!e) return;
    pthread_mutex_lock(&e->mutex);
    if (e->permit) {
        e->permit = 0;
        pthread_mutex_unlock(&e->mutex);
        return;
    }
    e->parked = 1;
    if (time == 0) {
        while (e->parked) pthread_cond_wait(&e->cond, &e->mutex);
    } else {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        if (abs) {
            ts.tv_sec = time / 1000;
            ts.tv_nsec = (time % 1000) * 1000000L;
        } else {
            ts.tv_sec += time / 1000;
            ts.tv_nsec += (time % 1000) * 1000000L;
            if (ts.tv_nsec >= 1000000000) { ts.tv_sec++; ts.tv_nsec -= 1000000000; }
        }
        while (e->parked) {
            if (pthread_cond_timedwait(&e->cond, &e->mutex, &ts) == ETIMEDOUT) break;
        }
    }
    e->parked = 0;
    pthread_mutex_unlock(&e->mutex);
}

void __jnative_fn_jdk_internal_misc_Unsafe_unpark__Ljava_lang_Object__V(
        void* this_unsafe, void* thread) {
    (void)this_unsafe;
    if (!thread) return;
    pthread_t t = pthread_self();
    ParkEntry* e = get_park_entry(t);
    if (!e) return;
    pthread_mutex_lock(&e->mutex);
    if (e->parked) {
        e->parked = 0;
        pthread_cond_signal(&e->cond);
    } else {
        e->permit = 1;
    }
    pthread_mutex_unlock(&e->mutex);
}

struct ReflectionFieldLayout {
    void* name;         /* const char*  — C string constant */
    void* descriptor;   /* const char*  — C string constant */
    int   offset;
    int   modifiers;
};

/* ========================================================================
 *  Field offset resolution
 *
 *  Instance fields: the java.lang.reflect.Field mirror's `slot` field
 *  holds the byte offset computed by LlvmGlobalEmitter.getFieldOffset
 *  and emitted into @reffield_<class>_<name>. The mirror is created by
 *  __jnative_fn_java_lang_Class_getDeclaredFields0, which copies that
 *  offset into the slot during construction.
 *
 *  The mirror is a real java.lang.reflect.Field object, not a
 *  ReflectionField descriptor, so the previous `((ReflectionField*)field)
 *  ->offset` cast was reading bytes 16..19 of an unrelated object. The
 *  offsets below are the ones in the java.lang.reflect.{Field,Method,
 *  Constructor} layout table in jnative_runtime.h, and they must stay
 *  equal to what LlvmGlobalEmitter.getFieldOffset computes for
 *  java/lang/reflect/Field.
 *
 *  Static fields: this runtime emits each static field as an LLVM
 *  global @gv_<sanitized(owner.name)>. The address of that global is
 *  what the (base, offset) pair must yield, so staticFieldOffset
 *  resolves it through dlsym(RTLD_DEFAULT) and staticFieldBase returns
 *  NULL. The runtime's effective_address(NULL, addr) then evaluates to
 *  (void*)addr, which is exactly the global's address.
 * ======================================================================== */

/*
 * Build the LLVM global symbol name for a static field, matching
 * LlvmTypeMapper.sanitizeIdentifier byte-for-byte:
 *
 *     "gv_" + sanitize(owner + "." + name)
 *
 * where sanitize replaces every character outside [a-zA-Z0-9_] with a
 * single underscore. '/' and '.' are both outside that set, so
 * "java/util/concurrent/ForkJoinPool.poolIds" becomes
 * "gv_java_util_concurrent_ForkJoinPool_poolIds".
 */
static int build_static_field_symbol(const char* owner,
                                     const char* name,
                                     int32_t name_len,
                                     char* out, size_t out_size)
{
    if (owner == NULL || name == NULL || out_size < 8) return 0;

    size_t pos = 0;
    out[pos++] = 'g';
    out[pos++] = 'v';
    out[pos++] = '_';

    for (const char* p = owner; *p; p++) {
        if (pos + 2 >= out_size) return 0;
        unsigned char c = (unsigned char)*p;
        out[pos++] = (isalnum(c) || c == '_') ? (char)c : '_';
    }
    if (pos + 2 >= out_size) return 0;
    out[pos++] = '_';

    for (int32_t i = 0; i < name_len; i++) {
        if (pos + 2 >= out_size) return 0;
        unsigned char c = (unsigned char)name[i];
        out[pos++] = (isalnum(c) || c == '_') ? (char)c : '_';
    }

    out[pos] = '\0';
    return 1;
}

int64_t __jnative_fn_jdk_internal_misc_Unsafe_objectFieldOffset__Ljava_lang_reflect_Field_J(void* this_unsafe, void* field) {
    (void)this_unsafe;
    if (!field) return 0;
    return (int64_t)*(int32_t*)((char*)field + JNATIVE_FIELD_SLOT_OFFSET);
}

int64_t __jnative_fn_jdk_internal_misc_Unsafe_staticFieldOffset__Ljava_lang_reflect_Field_J(void* this_unsafe, void* field) {
    (void)this_unsafe;
    if (field == NULL) return 0;

    void* clazz     = *(void**)((char*)field + JNATIVE_FIELD_CLAZZ_OFFSET);
    void* name_str  = *(void**)((char*)field + JNATIVE_FIELD_NAME_OFFSET);
    if (clazz == NULL || name_str == NULL) return 0;

    int32_t name_len = 0;
    const char* name = __jnative_read_string_bytes(name_str, &name_len);
    if (name == NULL || name_len <= 0) return 0;

    void* handle = dlopen(NULL, RTLD_LAZY);
    if (handle == NULL) return 0;

    /*
     * Walk the superclass chain. A field queried through a subclass
     * mirror still has its global emitted under the *declaring* class's
     * name, so the symbol lookup must try each ancestor until one
     * resolves. The walk stops at the first hit; a miss on the last
     * ancestor returns 0.
     */
    ReflectionClass* cur = (ReflectionClass*)clazz;
    int64_t result = 0;
    char symbol[1024];

    while (cur != NULL) {
        const char* cls_name = cur->cname;
        if (cls_name != NULL
            && build_static_field_symbol(cls_name, name, name_len,
                                         symbol, sizeof(symbol))) {
            void* addr = dlsym(handle, symbol);
            if (addr != NULL) {
                result = (int64_t)(intptr_t)addr;
                break;
            }
        }
        cur = cur->superclass;
    }

    dlclose(handle);
    return result;
}

void* __jnative_fn_jdk_internal_misc_Unsafe_staticFieldBase__Ljava_lang_reflect_Field_Ljava_lang_Object_(void* this_unsafe, void* field) {
    (void)this_unsafe;
    (void)field;
    /*
     * staticFieldOffset already returns the absolute address of the
     * emitted LLVM global. The runtime's effective_address(NULL, addr)
     * evaluates to (void*)addr, so returning NULL here makes the
     * (base, offset) pair resolve to the global's address directly.
     */
    return NULL;
}

int64_t __jnative_fn_jdk_internal_misc_Unsafe_objectFieldOffset1__Ljava_lang_Class_Ljava_lang_String__J(
        void* this_unsafe, void* cls, void* name_str)
{
    (void)this_unsafe;
    if (cls == NULL || name_str == NULL) {
        return 0;
    }

    int32_t req_len = 0;
    const char* requested = __jnative_read_string_bytes(name_str, &req_len);
    if (requested == NULL || req_len <= 0) {
        return 0;
    }

    ReflectionClass* rc = (ReflectionClass*)cls;
    ReflectionField** fp = rc->fields;
    if (fp == NULL) {
        return 0;
    }

    while (*fp != NULL) {
        ReflectionField* f = *fp;
        if (f->name != NULL) {
            const char* fname = (const char*)f->name;
            size_t flen = strlen(fname);
            if ((int32_t)flen == req_len && memcmp(fname, requested, flen) == 0) {
                return (int64_t)f->offset;
            }
        }
        fp++;
    }
    return 0;
}

/*
 * static native int arrayBaseOffset(Class<?> arrayClass);
 *
 * Returns the byte offset of the first element of a Java array. This is
 * the value the JDK's concurrent collections (ConcurrentHashMap,
 * ConcurrentSkipListMap, ThreadLocalRandom, Striped64, …) fold into
 * every Unsafe read/write against an array:
 *
 *     tabAt(tab, i) == U.getReferenceAcquire(tab, (i << ASHIFT) + ABASE)
 *     casTabAt(tab, i, c, v)
 *         == U.compareAndSetReference(tab, (i << ASHIFT) + ABASE, c, v)
 *
 * The offset is a property of the runtime's array layout, not of the
 * array type, so the function ignores its `arrayClass` argument and
 * returns the single canonical value.
 *
 * The value MUST be JAVA_ARR_HDR from jnative_runtime.h — the payload
 * offset of the runtime's Java-array layout:
 *
 *     offset  0 : ReflectionClass* klass
 *     offset  8 : int32_t         length
 *     offset 12 : int32_t         elem_size
 *     offset 16 : payload
 *
 * Historically this function returned the literal 8, which was correct
 * under the earlier 8-byte header (length@0, elem_size@4, payload@8).
 * After the array layout was corrected to the 16-byte header, this
 * literal was left behind, and every array access through Unsafe read
 * the wrong slot: for i == 0 it read length|elem_size as a pointer, for
 * i == 1 it read the element at index 0, and so on. On
 * ConcurrentHashMap.get this presented as a null result for keys that
 * were demonstrably present in the table (the "java.class.version"
 * lookup inside VM.saveProperties was the first one to dereference the
 * null).
 *
 * Using JAVA_ARR_HDR directly rather than a literal makes the drift
 * impossible to reintroduce: the compiler reads the same constant the
 * rest of the runtime uses.
 */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_arrayBaseOffset__Ljava_lang_Class_I(
        void* this_unsafe, void* arrayClass) {
    (void)this_unsafe;
    (void)arrayClass;
    return (int32_t)JAVA_ARR_HDR;
}

/*
 * static native int arrayIndexScale(Class<?> arrayClass);
 *
 * Returns the stride, in bytes, between consecutive elements of a Java
 * array, so that the JDK can compute an element's byte offset as
 * (index << ASHIFT) + ABASE. The value is a property of the array's
 * element type:
 *
 *     boolean[]  byte[]   -> 1
 *     short[]    char[]   -> 2
 *     int[]      float[]  -> 4
 *     long[]     double[] -> 8
 *     T[]        T[][]    -> 8   (reference / sub-array pointer)
 *
 * The class argument is the ARRAY class, and its internal name — the
 * ReflectionClass->cname field — is the array's JVM descriptor, not the
 * element type's internal name:
 *
 *     int[].class          -> "[I"
 *     byte[].class         -> "[B"
 *     String[].class       -> "[Ljava/lang/String;"
 *     int[][].class        -> "[[I"
 *
 * The earlier revision compared cname against the *primitive* class
 * names ("int", "float", …). Those names are never produced for an
 * array class, so every primitive array fell through to the reference
 * case and reported a stride of 8 — correct by accident for long[] and
 * double[], wrong for everything else. This was latent because the only
 * caller in the bootstrap path is ConcurrentHashMap.<clinit>, whose
 * Node[] has an 8-byte element anyway.
 *
 * The implementation below switches on the descriptor's first two
 * characters. The first character must be '['; for a name that is not
 * an array descriptor the function returns 8 as a conservative default,
 * matching the reference implementation's behaviour for a
 * reference-typed array.
 */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_arrayIndexScale__Ljava_lang_Class_I(
        void* this_unsafe, void* arrayClass) {
    (void)this_unsafe;
    if (arrayClass == NULL) return 1;

    const char* n = ((ReflectionClass*)arrayClass)->cname;
    if (n == NULL || n[0] != '[' || n[1] == '\0') return 8;

    switch (n[1]) {
        case 'Z': return 1;   /* boolean[] */
        case 'B': return 1;   /* byte[]    */
        case 'C': return 2;   /* char[]    */
        case 'S': return 2;   /* short[]   */
        case 'I': return 4;   /* int[]     */
        case 'F': return 4;   /* float[]   */
        case 'J': return 8;   /* long[]    */
        case 'D': return 8;   /* double[]  */
        case 'L': return 8;   /* T[]       */
        case '[': return 8;   /* T[][]     */
        default:  return 8;
    }
}

/* ---- Misc ---- */
void* __jnative_fn_jdk_internal_misc_Unsafe_allocateInstance__Ljava_lang_Class__Ljava_lang_Object_(
        void* this_unsafe, void* cls) {
    (void)this_unsafe;
    if (!cls) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }
    int size = ((ReflectionClass*)cls)->object_size;
    if (size <= 0) size = 8;
    return calloc(1, (size_t)size);
}

void* __jnative_fn_jdk_internal_misc_Unsafe_defineClass0__Ljava_lang_String__BIILjava_lang_ClassLoader_Ljava_security_ProtectionDomain__Ljava_lang_Class_(
        void* this_unsafe,
        void* name,
        void* b,
        int32_t off,
        int32_t len,
        void* loader,
        void* protectionDomain) {
    (void)this_unsafe;
    (void)name;
    (void)b;
    (void)off;
    (void)len;
    (void)loader;
    (void)protectionDomain;
    __jnative_throw_exception(NULL);
    return NULL;
}

void* __jnative_fn_jdk_internal_misc_Unsafe_getUncompressedObject__J_Ljava_lang_Object_(
        void* this_unsafe, int64_t address) {
    (void)this_unsafe;
    return (void*)(uintptr_t)address;
}

void __jnative_fn_jdk_internal_misc_Unsafe_throwException__Ljava_lang_Throwable__V(
        void* this_unsafe, void* throwable) {
    (void)this_unsafe;
    __jnative_throw_exception(throwable);
}

int32_t __jnative_fn_jdk_internal_misc_Unsafe_shouldBeInitialized__Ljava_lang_Class_Z(void* this_unsafe, void* cls) {
    (void)this_unsafe;
    (void)cls;
    return 0;
}
void __jnative_fn_jdk_internal_misc_Unsafe_ensureClassInitialized__Ljava_lang_Class_V(void* this_unsafe, void* cls) {
    (void)this_unsafe;
    (void)cls;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getLoadAverage___D_I(void* this_unsafe, double* loadavg, int32_t nelems) {
    (void)this_unsafe;
    if (!loadavg || nelems <= 0) return -1;
    double v = 0.0;
    if (getloadavg(&v, 1) != 1) return -1;
    loadavg[0] = v;
    return 1;
}
void __jnative_fn_jdk_internal_misc_Unsafe_invokeCleaner__Ljava_nio_ByteBuffer_V(void* this_unsafe, void* directBuffer) {
    (void)this_unsafe;
    (void)directBuffer;
}

/*
 * JDK 17+ renamed several of the Unsafe natives by appending a `0` to
 * the Java-visible name. The bodies are identical, so we forward to the
 * un-suffixed implementations. Keeping both symbol families present
 * makes the same C file usable across JDK 8 — 22 build targets.
 */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_shouldBeInitialized0__Ljava_lang_Class__Z(void* this_unsafe, void* cls) {
    return __jnative_fn_jdk_internal_misc_Unsafe_shouldBeInitialized__Ljava_lang_Class_Z(this_unsafe, cls);
}
void __jnative_fn_jdk_internal_misc_Unsafe_ensureClassInitialized0__Ljava_lang_Class__V(void* this_unsafe, void* cls) {
    __jnative_fn_jdk_internal_misc_Unsafe_ensureClassInitialized__Ljava_lang_Class_V(this_unsafe, cls);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_objectFieldOffset0__Ljava_lang_reflect_Field__J(void* this_unsafe, void* field) {
    return __jnative_fn_jdk_internal_misc_Unsafe_objectFieldOffset__Ljava_lang_reflect_Field_J(this_unsafe, field);
}

int64_t __jnative_fn_jdk_internal_misc_Unsafe_staticFieldOffset0__Ljava_lang_reflect_Field__J(void* this_unsafe, void* field) {
    return __jnative_fn_jdk_internal_misc_Unsafe_staticFieldOffset__Ljava_lang_reflect_Field_J(this_unsafe, field);
}

void* __jnative_fn_jdk_internal_misc_Unsafe_staticFieldBase0__Ljava_lang_reflect_Field__Ljava_lang_Object_(void* this_unsafe, void* field) {
    return __jnative_fn_jdk_internal_misc_Unsafe_staticFieldBase__Ljava_lang_reflect_Field_Ljava_lang_Object_(this_unsafe, field);
}

/*
 * static native int arrayBaseOffset0(Class<?> arrayClass);
 *
 * JDK 17+ spelling of arrayBaseOffset. Same body, same rationale — see
 * the comment on __jnative_fn_jdk_internal_misc_Unsafe_arrayBaseOffset__
 * Ljava_lang_Class_I above.
 */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_arrayBaseOffset0__Ljava_lang_Class__I(
        void* arrayClass) {
    (void)arrayClass;
    return (int32_t)JAVA_ARR_HDR;
}

int32_t __jnative_fn_jdk_internal_misc_Unsafe_arrayIndexScale0__Ljava_lang_Class__I(void* this_unsafe, void* arrayClass) {
    return __jnative_fn_jdk_internal_misc_Unsafe_arrayIndexScale__Ljava_lang_Class_I(this_unsafe, arrayClass);
}

/*
 * void copyMemory0(Object srcBase, long srcOffset, Object dstBase, long dstOffset, long bytes)
 *
 * The JDK 17+ name of the two-object copy. Same layout as copyMemory: the
 * two (object, offset) pairs address the source and destination ranges in
 * that order.
 */
void __jnative_fn_jdk_internal_misc_Unsafe_copyMemory0__Ljava_lang_Object_JLjava_lang_Object_JJ_V(
        void* this_unsafe,
        void* srcBase, int64_t srcOffset,
        void* dstBase, int64_t dstOffset,
        int64_t bytes) {
    __jnative_fn_jdk_internal_misc_Unsafe_copyMemory__Ljava_lang_Object_JLjava_lang_Object_JJ_V(
        this_unsafe, srcBase, srcOffset, dstBase, dstOffset, bytes);
}

/*
 * long allocateMemory0(long bytes)
 *
 * JDK 17+ name of allocateMemory. Same contract: returns a raw malloc'd
 * buffer of the requested size, or throws on failure. Negative sizes are
 * rejected before the allocator is touched so no size_t wrap-around can
 * produce a huge under-allocation.
 */
void* __jnative_fn_jdk_internal_misc_Unsafe_allocateMemory0__J_J(void* this_unsafe, int64_t bytes) {
    (void)this_unsafe;
    if (bytes < 0) return NULL;
    void* p = malloc((size_t)bytes);
    if (!p) __jnative_throw_exception(NULL);
    return p;
}

/*
 * void setMemory0(Object o, long offset, long bytes, byte value)
 *
 * JDK 17+ name of the two-object setMemory. `effective_address` folds
 * the (object, offset) pair into a single pointer: for a NULL object the
 * offset is treated as an absolute address, for a non-NULL object it is
 * a byte offset from the object's base. This matches the reference
 * implementation and the semantics used by every other Unsafe accessor
 * in this file.
 */
void __jnative_fn_jdk_internal_misc_Unsafe_setMemory0__Ljava_lang_Object_JJB_V(
        void* this_unsafe, void* obj, int64_t offset, int64_t bytes, int8_t value) {
    (void)this_unsafe;
    memset(effective_address(obj, offset), value, (size_t)bytes);
}

/*
 * void freeMemory0(long address)
 *
 * JDK 17+ name of freeMemory. Same body; forwarded rather than
 * re-implemented so that a future change to the cleanup path (for
 * example, invoking a registered cleaner on the freed block) only has to
 * happen in one place.
 */
void __jnative_fn_jdk_internal_misc_Unsafe_freeMemory0__J_V(void* this_unsafe, void* addr) {
    (void)this_unsafe;
    free(addr);
}

/*
 * private static native void registerNatives();
 *
 * Called from Unsafe.<clinit>. This runtime resolves every native method
 * through its statically-linked __jnative_fn_<class>_<method>_<desc> symbol
 * emitted by the LLVM backend, so there is nothing to register.
 *
 * This is the only STATIC native in Unsafe — no receiver slot.
 */
void __jnative_fn_jdk_internal_misc_Unsafe_registerNatives___V(void) {
}
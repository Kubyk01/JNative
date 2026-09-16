#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <errno.h>
#include <stdatomic.h>
#include <time.h>
#include <unistd.h>

__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);
__attribute__((noreturn)) void __jnative_throw_exception(void* exc);

static inline void* effective_address(void* obj, int64_t offset) {
    return (obj == NULL) ? (void*)(uintptr_t)offset : (char*)obj + offset;
}

/* ---- Plain loads ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getInt__Ljava_lang_Object_J_I(void* obj, int64_t offset) {
    return *(int32_t*)effective_address(obj, offset);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getLong__Ljava_lang_Object_J_J(void* obj, int64_t offset) {
    return *(int64_t*)effective_address(obj, offset);
}
void* __jnative_fn_jdk_internal_misc_Unsafe_getReference__Ljava_lang_Object_J_Ljava_lang_Object_(void* obj, int64_t offset) {
    return *(void**)effective_address(obj, offset);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getBoolean__Ljava_lang_Object_J_Z(void* obj, int64_t offset) {
    return *(uint8_t*)effective_address(obj, offset);
}
int8_t __jnative_fn_jdk_internal_misc_Unsafe_getByte__Ljava_lang_Object_J_B(void* obj, int64_t offset) {
    return *(int8_t*)effective_address(obj, offset);
}
int16_t __jnative_fn_jdk_internal_misc_Unsafe_getShort__Ljava_lang_Object_J_S(void* obj, int64_t offset) {
    return *(int16_t*)effective_address(obj, offset);
}
uint16_t __jnative_fn_jdk_internal_misc_Unsafe_getChar__Ljava_lang_Object_J_C(void* obj, int64_t offset) {
    return *(uint16_t*)effective_address(obj, offset);
}
float __jnative_fn_jdk_internal_misc_Unsafe_getFloat__Ljava_lang_Object_J_F(void* obj, int64_t offset) {
    return *(float*)effective_address(obj, offset);
}
double __jnative_fn_jdk_internal_misc_Unsafe_getDouble__Ljava_lang_Object_J_D(void* obj, int64_t offset) {
    return *(double*)effective_address(obj, offset);
}

/* ---- Plain stores ---- */
void __jnative_fn_jdk_internal_misc_Unsafe_putInt__Ljava_lang_Object_JI_V(void* obj, int64_t offset, int32_t x) {
    *(int32_t*)effective_address(obj, offset) = x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putLong__Ljava_lang_Object_JJ_V(void* obj, int64_t offset, int64_t x) {
    *(int64_t*)effective_address(obj, offset) = x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putReference__Ljava_lang_Object_JLjava_lang_Object__V(void* obj, int64_t offset, void* x) {
    *(void**)effective_address(obj, offset) = x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putBoolean__Ljava_lang_Object_JZ_V(void* obj, int64_t offset, int32_t x) {
    *(uint8_t*)effective_address(obj, offset) = (uint8_t)x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putByte__Ljava_lang_Object_JB_V(void* obj, int64_t offset, int8_t x) {
    *(int8_t*)effective_address(obj, offset) = x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putShort__Ljava_lang_Object_JS_V(void* obj, int64_t offset, int16_t x) {
    *(int16_t*)effective_address(obj, offset) = x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putChar__Ljava_lang_Object_JC_V(void* obj, int64_t offset, uint16_t x) {
    *(uint16_t*)effective_address(obj, offset) = x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putFloat__Ljava_lang_Object_JF_V(void* obj, int64_t offset, float x) {
    *(float*)effective_address(obj, offset) = x;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putDouble__Ljava_lang_Object_JD_V(void* obj, int64_t offset, double x) {
    *(double*)effective_address(obj, offset) = x;
}

/* ---- Volatile loads ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getIntVolatile__Ljava_lang_Object_J_I(void* obj, int64_t offset) {
    int32_t v; __atomic_load((int32_t*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getLongVolatile__Ljava_lang_Object_J_J(void* obj, int64_t offset) {
    int64_t v; __atomic_load((int64_t*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
void* __jnative_fn_jdk_internal_misc_Unsafe_getReferenceVolatile__Ljava_lang_Object_J_Ljava_lang_Object_(void* obj, int64_t offset) {
    void* v; __atomic_load((void**)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getBooleanVolatile__Ljava_lang_Object_J_Z(void* obj, int64_t offset) {
    uint8_t v; __atomic_load((uint8_t*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
int8_t __jnative_fn_jdk_internal_misc_Unsafe_getByteVolatile__Ljava_lang_Object_J_B(void* obj, int64_t offset) {
    int8_t v; __atomic_load((int8_t*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
int16_t __jnative_fn_jdk_internal_misc_Unsafe_getShortVolatile__Ljava_lang_Object_J_S(void* obj, int64_t offset) {
    int16_t v; __atomic_load((int16_t*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
uint16_t __jnative_fn_jdk_internal_misc_Unsafe_getCharVolatile__Ljava_lang_Object_J_C(void* obj, int64_t offset) {
    uint16_t v; __atomic_load((uint16_t*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
float __jnative_fn_jdk_internal_misc_Unsafe_getFloatVolatile__Ljava_lang_Object_J_F(void* obj, int64_t offset) {
    float v; __atomic_load((float*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}
double __jnative_fn_jdk_internal_misc_Unsafe_getDoubleVolatile__Ljava_lang_Object_J_D(void* obj, int64_t offset) {
    double v; __atomic_load((double*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST); return v;
}

/* ---- Volatile stores ---- */
void __jnative_fn_jdk_internal_misc_Unsafe_putIntVolatile__Ljava_lang_Object_JI_V(void* obj, int64_t offset, int32_t x) {
    __atomic_store((int32_t*)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putLongVolatile__Ljava_lang_Object_JJ_V(void* obj, int64_t offset, int64_t x) {
    __atomic_store((int64_t*)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putReferenceVolatile__Ljava_lang_Object_JLjava_lang_Object__V(void* obj, int64_t offset, void* x) {
    __atomic_store((void**)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putBooleanVolatile__Ljava_lang_Object_JZ_V(void* obj, int64_t offset, int32_t x) {
    uint8_t v = (uint8_t)x;
    __atomic_store((uint8_t*)effective_address(obj, offset), &v, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putByteVolatile__Ljava_lang_Object_JB_V(void* obj, int64_t offset, int8_t x) {
    __atomic_store((int8_t*)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putShortVolatile__Ljava_lang_Object_JS_V(void* obj, int64_t offset, int16_t x) {
    __atomic_store((int16_t*)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putCharVolatile__Ljava_lang_Object_JC_V(void* obj, int64_t offset, uint16_t x) {
    __atomic_store((uint16_t*)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putFloatVolatile__Ljava_lang_Object_JF_V(void* obj, int64_t offset, float x) {
    __atomic_store((float*)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putDoubleVolatile__Ljava_lang_Object_JD_V(void* obj, int64_t offset, double x) {
    __atomic_store((double*)effective_address(obj, offset), &x, __ATOMIC_SEQ_CST);
}

/* ---- Ordered (release) stores ---- */
void __jnative_fn_jdk_internal_misc_Unsafe_putOrderedInt__Ljava_lang_Object_JI_V(void* obj, int64_t offset, int32_t x) {
    __atomic_store((int32_t*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putOrderedLong__Ljava_lang_Object_JJ_V(void* obj, int64_t offset, int64_t x) {
    __atomic_store((int64_t*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putOrderedObject__Ljava_lang_Object_JLjava_lang_Object__V(void* obj, int64_t offset, void* x) {
    __atomic_store((void**)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}

/* ---- CAS ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetInt__Ljava_lang_Object_JII_Z(void* obj, int64_t offset, int32_t expected, int32_t x) {
    int32_t exp = expected;
    return __atomic_compare_exchange_n((int32_t*)effective_address(obj, offset), &exp, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetLong__Ljava_lang_Object_JJJ_Z(void* obj, int64_t offset, int64_t expected, int64_t x) {
    int64_t exp = expected;
    return __atomic_compare_exchange_n((int64_t*)effective_address(obj, offset), &exp, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetReference__Ljava_lang_Object_JLjava_lang_Object_Ljava_lang_Object__Z(void* obj, int64_t offset, void* expected, void* x) {
    void* exp = expected;
    return __atomic_compare_exchange_n((void**)effective_address(obj, offset), &exp, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetByte__Ljava_lang_Object_JBB_Z(void* obj, int64_t offset, int8_t expected, int8_t x) {
    int8_t exp = expected;
    return __atomic_compare_exchange_n((int8_t*)effective_address(obj, offset), &exp, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetShort__Ljava_lang_Object_JSS_Z(void* obj, int64_t offset, int16_t expected, int16_t x) {
    int16_t exp = expected;
    return __atomic_compare_exchange_n((int16_t*)effective_address(obj, offset), &exp, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetChar__Ljava_lang_Object_JCC_Z(void* obj, int64_t offset, uint16_t expected, uint16_t x) {
    uint16_t exp = expected;
    return __atomic_compare_exchange_n((uint16_t*)effective_address(obj, offset), &exp, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetFloat__Ljava_lang_Object_JFF_Z(void* obj, int64_t offset, float expected, float x) {
    uint32_t exp; memcpy(&exp, &expected, 4);
    uint32_t val; memcpy(&val, &x, 4);
    return __atomic_compare_exchange_n((uint32_t*)effective_address(obj, offset), &exp, val, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetDouble__Ljava_lang_Object_JDD_Z(void* obj, int64_t offset, double expected, double x) {
    uint64_t exp; memcpy(&exp, &expected, 8);
    uint64_t val; memcpy(&val, &x, 8);
    return __atomic_compare_exchange_n((uint64_t*)effective_address(obj, offset), &exp, val, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

/* ---- compareAndExchange ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndExchangeInt__Ljava_lang_Object_JII_I(void* obj, int64_t offset, int32_t expected, int32_t x) {
    int32_t witness = expected;
    __atomic_compare_exchange_n((int32_t*)effective_address(obj, offset), &witness, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndExchangeLong__Ljava_lang_Object_JJJ_J(void* obj, int64_t offset, int64_t expected, int64_t x) {
    int64_t witness = expected;
    __atomic_compare_exchange_n((int64_t*)effective_address(obj, offset), &witness, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}
void* __jnative_fn_jdk_internal_misc_Unsafe_compareAndExchangeReference__Ljava_lang_Object_JLjava_lang_Object_Ljava_lang_Object__Ljava_lang_Object_(void* obj, int64_t offset, void* expected, void* x) {
    void* witness = expected;
    __atomic_compare_exchange_n((void**)effective_address(obj, offset), &witness, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}
int8_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndExchangeByte__Ljava_lang_Object_JBB_B(void* obj, int64_t offset, int8_t expected, int8_t x) {
    int8_t witness = expected;
    __atomic_compare_exchange_n((int8_t*)effective_address(obj, offset), &witness, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}
int16_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndExchangeShort__Ljava_lang_Object_JSS_S(void* obj, int64_t offset, int16_t expected, int16_t x) {
    int16_t witness = expected;
    __atomic_compare_exchange_n((int16_t*)effective_address(obj, offset), &witness, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}
uint16_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndExchangeChar__Ljava_lang_Object_JCC_C(void* obj, int64_t offset, uint16_t expected, uint16_t x) {
    uint16_t witness = expected;
    __atomic_compare_exchange_n((uint16_t*)effective_address(obj, offset), &witness, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return witness;
}

/* ---- Weak CAS ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_weakCompareAndSetInt__Ljava_lang_Object_JII_Z(void* obj, int64_t offset, int32_t expected, int32_t x) {
    return __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetInt__Ljava_lang_Object_JII_Z(obj, offset, expected, x);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_weakCompareAndSetLong__Ljava_lang_Object_JJJ_Z(void* obj, int64_t offset, int64_t expected, int64_t x) {
    return __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetLong__Ljava_lang_Object_JJJ_Z(obj, offset, expected, x);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_weakCompareAndSetReference__Ljava_lang_Object_JLjava_lang_Object_Ljava_lang_Object__Z(void* obj, int64_t offset, void* expected, void* x) {
    return __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetReference__Ljava_lang_Object_JLjava_lang_Object_Ljava_lang_Object__Z(obj, offset, expected, x);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_weakCompareAndSetByte__Ljava_lang_Object_JBB_Z(void* obj, int64_t offset, int8_t expected, int8_t x) {
    return __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetByte__Ljava_lang_Object_JBB_Z(obj, offset, expected, x);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_weakCompareAndSetShort__Ljava_lang_Object_JSS_Z(void* obj, int64_t offset, int16_t expected, int16_t x) {
    return __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetShort__Ljava_lang_Object_JSS_Z(obj, offset, expected, x);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_weakCompareAndSetChar__Ljava_lang_Object_JCC_Z(void* obj, int64_t offset, uint16_t expected, uint16_t x) {
    return __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetChar__Ljava_lang_Object_JCC_Z(obj, offset, expected, x);
}

/* ---- getAndAdd ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getAndAddInt__Ljava_lang_Object_JI_I(void* obj, int64_t offset, int32_t delta) {
    return __atomic_fetch_add((int32_t*)effective_address(obj, offset), delta, __ATOMIC_SEQ_CST);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getAndAddLong__Ljava_lang_Object_JJ_J(void* obj, int64_t offset, int64_t delta) {
    return __atomic_fetch_add((int64_t*)effective_address(obj, offset), delta, __ATOMIC_SEQ_CST);
}
int8_t __jnative_fn_jdk_internal_misc_Unsafe_getAndAddByte__Ljava_lang_Object_JB_B(void* obj, int64_t offset, int8_t delta) {
    return __atomic_fetch_add((int8_t*)effective_address(obj, offset), delta, __ATOMIC_SEQ_CST);
}
int16_t __jnative_fn_jdk_internal_misc_Unsafe_getAndAddShort__Ljava_lang_Object_JS_S(void* obj, int64_t offset, int16_t delta) {
    return __atomic_fetch_add((int16_t*)effective_address(obj, offset), delta, __ATOMIC_SEQ_CST);
}

/* ---- getAndSet ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getAndSetInt__Ljava_lang_Object_JI_I(void* obj, int64_t offset, int32_t newValue) {
    int32_t old;
    __atomic_exchange((int32_t*)effective_address(obj, offset), &newValue, &old, __ATOMIC_SEQ_CST);
    return old;
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getAndSetLong__Ljava_lang_Object_JJ_J(void* obj, int64_t offset, int64_t newValue) {
    int64_t old;
    __atomic_exchange((int64_t*)effective_address(obj, offset), &newValue, &old, __ATOMIC_SEQ_CST);
    return old;
}
void* __jnative_fn_jdk_internal_misc_Unsafe_getAndSetReference__Ljava_lang_Object_JLjava_lang_Object_Ljava_lang_Object_(void* obj, int64_t offset, void* newValue) {
    void* old;
    __atomic_exchange((void**)effective_address(obj, offset), &newValue, &old, __ATOMIC_SEQ_CST);
    return old;
}
int8_t __jnative_fn_jdk_internal_misc_Unsafe_getAndSetByte__Ljava_lang_Object_JB_B(void* obj, int64_t offset, int8_t newValue) {
    int8_t old;
    __atomic_exchange((int8_t*)effective_address(obj, offset), &newValue, &old, __ATOMIC_SEQ_CST);
    return old;
}
int16_t __jnative_fn_jdk_internal_misc_Unsafe_getAndSetShort__Ljava_lang_Object_JS_S(void* obj, int64_t offset, int16_t newValue) {
    int16_t old;
    __atomic_exchange((int16_t*)effective_address(obj, offset), &newValue, &old, __ATOMIC_SEQ_CST);
    return old;
}

/* ---- Bitwise atomic ops (Java 9+) ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getAndBitwiseAndInt__Ljava_lang_Object_JI_I(void* obj, int64_t offset, int32_t mask) {
    return __atomic_fetch_and((int32_t*)effective_address(obj, offset), mask, __ATOMIC_SEQ_CST);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getAndBitwiseOrInt__Ljava_lang_Object_JI_I(void* obj, int64_t offset, int32_t mask) {
    return __atomic_fetch_or((int32_t*)effective_address(obj, offset), mask, __ATOMIC_SEQ_CST);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getAndBitwiseXorInt__Ljava_lang_Object_JI_I(void* obj, int64_t offset, int32_t mask) {
    return __atomic_fetch_xor((int32_t*)effective_address(obj, offset), mask, __ATOMIC_SEQ_CST);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getAndBitwiseAndLong__Ljava_lang_Object_JJ_J(void* obj, int64_t offset, int64_t mask) {
    return __atomic_fetch_and((int64_t*)effective_address(obj, offset), mask, __ATOMIC_SEQ_CST);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getAndBitwiseOrLong__Ljava_lang_Object_JJ_J(void* obj, int64_t offset, int64_t mask) {
    return __atomic_fetch_or((int64_t*)effective_address(obj, offset), mask, __ATOMIC_SEQ_CST);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getAndBitwiseXorLong__Ljava_lang_Object_JJ_J(void* obj, int64_t offset, int64_t mask) {
    return __atomic_fetch_xor((int64_t*)effective_address(obj, offset), mask, __ATOMIC_SEQ_CST);
}

/* ---- Acquire/Release variants ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getIntAcquire__Ljava_lang_Object_J_I(void* obj, int64_t offset) {
    int32_t v; __atomic_load((int32_t*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putIntRelease__Ljava_lang_Object_JI_V(void* obj, int64_t offset, int32_t x) {
    __atomic_store((int32_t*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getLongAcquire__Ljava_lang_Object_J_J(void* obj, int64_t offset) {
    int64_t v; __atomic_load((int64_t*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putLongRelease__Ljava_lang_Object_JJ_V(void* obj, int64_t offset, int64_t x) {
    __atomic_store((int64_t*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
void* __jnative_fn_jdk_internal_misc_Unsafe_getReferenceAcquire__Ljava_lang_Object_J_Ljava_lang_Object_(void* obj, int64_t offset) {
    void* v; __atomic_load((void**)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putReferenceRelease__Ljava_lang_Object_JLjava_lang_Object__V(void* obj, int64_t offset, void* x) {
    __atomic_store((void**)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getBooleanAcquire__Ljava_lang_Object_J_Z(void* obj, int64_t offset) {
    uint8_t v; __atomic_load((uint8_t*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putBooleanRelease__Ljava_lang_Object_JZ_V(void* obj, int64_t offset, int32_t x) {
    uint8_t v = (uint8_t)x; __atomic_store((uint8_t*)effective_address(obj, offset), &v, __ATOMIC_RELEASE);
}
int8_t __jnative_fn_jdk_internal_misc_Unsafe_getByteAcquire__Ljava_lang_Object_J_B(void* obj, int64_t offset) {
    int8_t v; __atomic_load((int8_t*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putByteRelease__Ljava_lang_Object_JB_V(void* obj, int64_t offset, int8_t x) {
    __atomic_store((int8_t*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
int16_t __jnative_fn_jdk_internal_misc_Unsafe_getShortAcquire__Ljava_lang_Object_J_S(void* obj, int64_t offset) {
    int16_t v; __atomic_load((int16_t*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putShortRelease__Ljava_lang_Object_JS_V(void* obj, int64_t offset, int16_t x) {
    __atomic_store((int16_t*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
uint16_t __jnative_fn_jdk_internal_misc_Unsafe_getCharAcquire__Ljava_lang_Object_J_C(void* obj, int64_t offset) {
    uint16_t v; __atomic_load((uint16_t*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putCharRelease__Ljava_lang_Object_JC_V(void* obj, int64_t offset, uint16_t x) {
    __atomic_store((uint16_t*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
float __jnative_fn_jdk_internal_misc_Unsafe_getFloatAcquire__Ljava_lang_Object_J_F(void* obj, int64_t offset) {
    float v; __atomic_load((float*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putFloatRelease__Ljava_lang_Object_JF_V(void* obj, int64_t offset, float x) {
    __atomic_store((float*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}
double __jnative_fn_jdk_internal_misc_Unsafe_getDoubleAcquire__Ljava_lang_Object_J_D(void* obj, int64_t offset) {
    double v; __atomic_load((double*)effective_address(obj, offset), &v, __ATOMIC_ACQUIRE); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putDoubleRelease__Ljava_lang_Object_JD_V(void* obj, int64_t offset, double x) {
    __atomic_store((double*)effective_address(obj, offset), &x, __ATOMIC_RELEASE);
}

/* ---- Opaque variants ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getIntOpaque__Ljava_lang_Object_J_I(void* obj, int64_t offset) {
    int32_t v; __atomic_load((int32_t*)effective_address(obj, offset), &v, __ATOMIC_RELAXED); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putIntOpaque__Ljava_lang_Object_JI_V(void* obj, int64_t offset, int32_t x) {
    __atomic_store((int32_t*)effective_address(obj, offset), &x, __ATOMIC_RELAXED);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getLongOpaque__Ljava_lang_Object_J_J(void* obj, int64_t offset) {
    int64_t v; __atomic_load((int64_t*)effective_address(obj, offset), &v, __ATOMIC_RELAXED); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putLongOpaque__Ljava_lang_Object_JJ_V(void* obj, int64_t offset, int64_t x) {
    __atomic_store((int64_t*)effective_address(obj, offset), &x, __ATOMIC_RELAXED);
}
void* __jnative_fn_jdk_internal_misc_Unsafe_getReferenceOpaque__Ljava_lang_Object_J_Ljava_lang_Object_(void* obj, int64_t offset) {
    void* v; __atomic_load((void**)effective_address(obj, offset), &v, __ATOMIC_RELAXED); return v;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putReferenceOpaque__Ljava_lang_Object_JLjava_lang_Object__V(void* obj, int64_t offset, void* x) {
    __atomic_store((void**)effective_address(obj, offset), &x, __ATOMIC_RELAXED);
}

/* ---- Direct absolute-address access ---- */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getInt__J_I(void* addr) { return *(int32_t*)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putInt__JI_V(void* addr, int32_t x) { *(int32_t*)addr = x; }
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getLong__J_J(void* addr) { return *(int64_t*)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putLong__JJ_V(void* addr, int64_t x) { *(int64_t*)addr = x; }
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getAddress__J_J(void* addr) { return (int64_t)(uintptr_t)*(void**)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putAddress__JJ_V(void* addr, int64_t x) { *(void**)addr = (void*)(uintptr_t)x; }
int8_t __jnative_fn_jdk_internal_misc_Unsafe_getByte__J_B(void* addr) { return *(int8_t*)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putByte__JB_V(void* addr, int8_t x) { *(int8_t*)addr = x; }
int16_t __jnative_fn_jdk_internal_misc_Unsafe_getShort__J_S(void* addr) { return *(int16_t*)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putShort__JS_V(void* addr, int16_t x) { *(int16_t*)addr = x; }
uint16_t __jnative_fn_jdk_internal_misc_Unsafe_getChar__J_C(void* addr) { return *(uint16_t*)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putChar__JC_V(void* addr, uint16_t x) { *(uint16_t*)addr = x; }
float __jnative_fn_jdk_internal_misc_Unsafe_getFloat__J_F(void* addr) { return *(float*)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putFloat__JF_V(void* addr, float x) { *(float*)addr = x; }
double __jnative_fn_jdk_internal_misc_Unsafe_getDouble__J_D(void* addr) { return *(double*)addr; }
void __jnative_fn_jdk_internal_misc_Unsafe_putDouble__JD_V(void* addr, double x) { *(double*)addr = x; }

/* ---- Memory management ---- */
void* __jnative_fn_jdk_internal_misc_Unsafe_allocateMemory__J_J(int64_t bytes) {
    if (bytes < 0) return NULL;
    void* p = malloc((size_t)bytes);
    if (!p) __jnative_throw_exception(NULL);
    return p;
}
void* __jnative_fn_jdk_internal_misc_Unsafe_reallocateMemory__JJ_J(void* addr, int64_t bytes) {
    if (bytes < 0) return NULL;
    void* p = realloc(addr, (size_t)bytes);
    if (!p) __jnative_throw_exception(NULL);
    return p;
}
void __jnative_fn_jdk_internal_misc_Unsafe_freeMemory__J_V(void* addr) {
    free(addr);
}
void __jnative_fn_jdk_internal_misc_Unsafe_setMemory__JJB_V(void* addr, int64_t bytes, int8_t value) {
    memset(addr, value, (size_t)bytes);
}
void __jnative_fn_jdk_internal_misc_Unsafe_setMemory__Ljava_lang_Object_JJBB_V(void* obj, int64_t offset, int64_t bytes, int8_t value) {
    memset(effective_address(obj, offset), value, (size_t)bytes);
}
void __jnative_fn_jdk_internal_misc_Unsafe_copyMemory__JJJ_V(void* src, void* dst, int64_t bytes) {
    memmove(dst, src, (size_t)bytes);
}
void __jnative_fn_jdk_internal_misc_Unsafe_copyMemory__Ljava_lang_Object_JLjava_lang_Object_JJ_V(
        void* srcBase, int64_t srcOffset,
        void* dstBase, int64_t dstOffset,
        int64_t bytes) {
    memmove(effective_address(dstBase, dstOffset),
            effective_address(srcBase, srcOffset),
            (size_t)bytes);
}
void __jnative_fn_jdk_internal_misc_Unsafe_copySwapMemory0__Ljava_lang_Object_JLjava_lang_Object_JJJ_V(void* srcBase, int64_t srcOffset, void* dstBase, int64_t dstOffset, int64_t bytes, int64_t elemSize) {
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
void __jnative_fn_jdk_internal_misc_Unsafe_loadFence___V(void) { __atomic_thread_fence(__ATOMIC_ACQUIRE); }
void __jnative_fn_jdk_internal_misc_Unsafe_storeFence___V(void) { __atomic_thread_fence(__ATOMIC_RELEASE); }
void __jnative_fn_jdk_internal_misc_Unsafe_fullFence___V(void) { __atomic_thread_fence(__ATOMIC_SEQ_CST); }

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

void __jnative_fn_jdk_internal_misc_Unsafe_park__ZJ_V(void* isAbsolute, int64_t time) {
    int abs = isAbsolute && *(int32_t*)isAbsolute;
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

/* ---- Field-offset helpers ---- */
int64_t __jnative_fn_jdk_internal_misc_Unsafe_objectFieldOffset__Ljava_lang_reflect_Field_J(void* field) {
    if (!field) return 0;
    struct ReflectionField {
        void* name;
        void* descriptor;
        int offset;
        int modifiers;
    };
    return (int64_t)((struct ReflectionField*)field)->offset;
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_staticFieldOffset__Ljava_lang_reflect_Field_J(void* field) {
    return __jnative_fn_jdk_internal_misc_Unsafe_objectFieldOffset__Ljava_lang_reflect_Field_J(field);
}
void* __jnative_fn_jdk_internal_misc_Unsafe_staticFieldBase__Ljava_lang_reflect_Field_Ljava_lang_Object_(void* field) {
    (void)field;
    return NULL;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_arrayBaseOffset__Ljava_lang_Class_I(void* arrayClass) {
    (void)arrayClass;
    return 4;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_arrayIndexScale__Ljava_lang_Class_I(void* arrayClass) {
    if (!arrayClass) return 1;
    struct ReflectionClass {
        void* name;
        struct ReflectionClass* superclass;
        struct ReflectionClass** interfaces;
        void** methods;
        void** fields;
        void** constructors;
        int modifiers;
        int object_size;
    };
    const char* n = (const char*)((struct ReflectionClass*)arrayClass)->name;
    if (!n) return 1;
    if (strcmp(n, "boolean") == 0 || strcmp(n, "byte") == 0) return 1;
    if (strcmp(n, "short") == 0 || strcmp(n, "char") == 0) return 2;
    if (strcmp(n, "int") == 0 || strcmp(n, "float") == 0) return 4;
    if (strcmp(n, "long") == 0 || strcmp(n, "double") == 0) return 8;
    return 8;
}

/* ---- Misc ---- */
void* __jnative_fn_jdk_internal_misc_Unsafe_allocateInstance__Ljava_lang_Class__Ljava_lang_Object_(
        void* this_unsafe, void* cls) {
    (void)this_unsafe;
    if (!cls) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }
    struct ReflectionClass {
        void* name;
        struct ReflectionClass* superclass;
        struct ReflectionClass** interfaces;
        void** methods;
        void** fields;
        void** constructors;
        int modifiers;
        int object_size;
    };
    int size = ((struct ReflectionClass*)cls)->object_size;
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

int32_t __jnative_fn_jdk_internal_misc_Unsafe_shouldBeInitialized__Ljava_lang_Class_Z(void* cls) {
    (void)cls;
    return 0;
}
void __jnative_fn_jdk_internal_misc_Unsafe_ensureClassInitialized__Ljava_lang_Class_V(void* cls) {
    (void)cls;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getLoadAverage___D_I(double* loadavg, int32_t nelems) {
    if (!loadavg || nelems <= 0) return -1;
    double v = 0.0;
    if (getloadavg(&v, 1) != 1) return -1;
    loadavg[0] = v;
    return 1;
}
void __jnative_fn_jdk_internal_misc_Unsafe_invokeCleaner__Ljava_nio_ByteBuffer_V(void* directBuffer) {
    (void)directBuffer;
}

/*
 * JDK 17+ renamed several of the Unsafe natives by appending a `0` to
 * the Java-visible name. The bodies are identical, so we forward to the
 * un-suffixed implementations. Keeping both symbol families present
 * makes the same C file usable across JDK 8 — 22 build targets.
 */
int32_t __jnative_fn_jdk_internal_misc_Unsafe_shouldBeInitialized0__Ljava_lang_Class__Z(void* cls) {
    return __jnative_fn_jdk_internal_misc_Unsafe_shouldBeInitialized__Ljava_lang_Class_Z(cls);
}
void __jnative_fn_jdk_internal_misc_Unsafe_ensureClassInitialized0__Ljava_lang_Class__V(void* cls) {
    __jnative_fn_jdk_internal_misc_Unsafe_ensureClassInitialized__Ljava_lang_Class_V(cls);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_objectFieldOffset0__Ljava_lang_reflect_Field__J(void* field) {
    return __jnative_fn_jdk_internal_misc_Unsafe_objectFieldOffset__Ljava_lang_reflect_Field_J(field);
}

/*
 * void copyMemory0(Object srcBase, long srcOffset, Object dstBase, long dstOffset, long bytes)
 *
 * The JDK 17+ name of the two-object copy. Same layout as copyMemory: the
 * two (object, offset) pairs address the source and destination ranges in
 * that order.
 */
void __jnative_fn_jdk_internal_misc_Unsafe_copyMemory0__Ljava_lang_Object_JLjava_lang_Object_JJ_V(
        void* srcBase, int64_t srcOffset,
        void* dstBase, int64_t dstOffset,
        int64_t bytes) {
    __jnative_fn_jdk_internal_misc_Unsafe_copyMemory__Ljava_lang_Object_JLjava_lang_Object_JJ_V(
        srcBase, srcOffset, dstBase, dstOffset, bytes);
}

/*
 * private static native void registerNatives();
 *
 * Called from Unsafe.<clinit>. This runtime resolves every native method
 * through its statically-linked __jnative_fn_<class>_<method>_<desc> symbol
 * emitted by the LLVM backend, so there is nothing to register.
 */
void __jnative_fn_jdk_internal_misc_Unsafe_registerNatives___V(void) {
}
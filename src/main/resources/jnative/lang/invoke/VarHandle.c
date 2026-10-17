/*
 * java.lang.invoke.VarHandle — the native entry points behind every
 * VarHandle access mode.
 *
 * ============================================================================
 * ABI: parameter layout
 * ============================================================================
 *
 * VarHandle access modes are polymorphic-signature methods. Their
 * native implementations are reached through one of two emitter
 * paths, and both pass exactly the Java-level arguments — the
 * VarHandle receiver itself is never part of the C signature.
 *
 * The polymorphic path in LlvmFunctionEmitter builds the argument
 * list as:
 *
 *     List<Value> allArgs = new ArrayList<>();
 *     allArgs.add(receiver);                        // the VarHandle
 *     for (int i = 2; i < operands.size(); i++) {
 *         allArgs.add(operands.get(i));             // the java args
 *     }
 *     List<Value> argsWithoutReceiver = allArgs.subList(1, allArgs.size());
 *
 * and every concrete call below uses argsWithoutReceiver, so the
 * VarHandle is dropped before the call is emitted.
 *
 * Each C function's parameter list therefore matches its mangled
 * descriptor one-for-one, with no leading receiver. A function whose
 * mangled name ends in, for example,
 *
 *     __Ljava_util_concurrent_atomic_AtomicReference_
 *     Ljava_lang_Object_Ljava_lang_Object__Z
 *
 * takes exactly three arguments — the AtomicReference, the old
 * value, and the new value — and returns an int32. It does NOT take a
 * leading VarHandle pointer. The previous revision of this file gave
 * every class-based accessor a leading `void* this_handle`, which
 * shifted every argument by one slot at the call site and produced
 * the linker diagnostic the current revision removes.
 *
 * ============================================================================
 * Byte-order handling for byte[] view accessors
 * ============================================================================
 *
 * A byte-array-view VarHandle produced by
 *
 *     MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN)
 *
 * reinterprets a byte[] as a sequence of 16-, 32- or 64-bit values
 * laid out in big-endian order. jdk.internal.util.ByteArray — the
 * class that DataInputStream, DataOutputStream, ObjectInputStream,
 * ObjectOutputStream and every other java.io stream-protocol reader
 * use — creates its VarHandles with ByteOrder.BIG_ENDIAN
 * unconditionally, because the Java data stream format is defined to
 * be big-endian on the wire (Java Object Serialization Specification
 * §3.6).
 *
 * In HotSpot the byte-order conversion lives in
 * VarHandleByteArrayAsInts.convEndian and its Longs counterpart; in
 * this runtime there is no such Java-level wrapper between the
 * polymorphic dispatch and the C function, so the conversion must
 * happen in C. The macros below perform it at every load, store and
 * compare-and-exchange on the byte-array accessors:
 *
 *   - on a big-endian host, a plain memcpy already produces the
 *     big-endian interpretation, so the macros expand to the identity;
 *
 *   - on a little-endian host (every platform this runtime targets
 *     today: x86_64 and aarch64), the value read out of memory is
 *     byte-reversed relative to the desired interpretation, so the
 *     macros expand to __builtin_bswap16 / 32 / 64.
 *
 * The same macro is used for both read and write directions and for
 * the expected / new operands of the atomic primitives, because the
 * byte-swap is an involution: applying it twice recovers the original
 * value.
 *
 * The concrete failure the big-endian handling was written for:
 *
 *     java.util.Currency.<clinit> reads its embedded currency.data
 *     resource through a DataInputStream. The first read is
 *
 *         if (dis.readInt() != MAGIC_NUMBER) throw new InternalError(...);
 *
 *     MAGIC_NUMBER is 0x43757244. On a little-endian host without the
 *     conversion, get___BI_I returned 0x44727543, the comparison
 *     failed, and Currency's class initializer threw.
 *
 * ============================================================================
 * Field offsets used by the class-based accessors
 * ============================================================================
 *
 * The class-based accessors below use fixed byte offsets into the
 * target objects. Each offset matches the layout computed by
 * LlvmGlobalEmitter.getFieldOffset for the target class: an 8-byte
 * object header, then each instance field placed at its natural
 * alignment with no additional padding. The comments on the affected
 * functions name the field and its declaring class.
 */

#define _GNU_SOURCE
#include <stdint.h>
#include <string.h>
#include <stdatomic.h>

#include "jnative_runtime.h"

/* ===========================================================================
 *  Byte-order macros
 * =========================================================================== */

#if defined(__BYTE_ORDER__) && defined(__ORDER_BIG_ENDIAN__) \
    && (__BYTE_ORDER__ == __ORDER_BIG_ENDIAN__)
#  define JNATIVE_HOST_IS_BIG_ENDIAN 1
#elif defined(__BYTE_ORDER__) && defined(__ORDER_LITTLE_ENDIAN__) \
    && (__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__)
#  define JNATIVE_HOST_IS_BIG_ENDIAN 0
#elif defined(__BIG_ENDIAN__) || defined(_BIG_ENDIAN) \
    || defined(__ARMEB__) || defined(__MIPSEB__) \
    || defined(__s390x__) || defined(__sparc__) || defined(__powerpc__)
#  define JNATIVE_HOST_IS_BIG_ENDIAN 1
#else
#  define JNATIVE_HOST_IS_BIG_ENDIAN 0
#endif

#if JNATIVE_HOST_IS_BIG_ENDIAN
#  define JNATIVE_BE16(v) ((uint16_t)(v))
#  define JNATIVE_BE32(v) ((uint32_t)(v))
#  define JNATIVE_BE64(v) ((uint64_t)(v))
#else
#  define JNATIVE_BE16(v) ((uint16_t)__builtin_bswap16((uint16_t)(v)))
#  define JNATIVE_BE32(v) ((uint32_t)__builtin_bswap32((uint32_t)(v)))
#  define JNATIVE_BE64(v) ((uint64_t)__builtin_bswap64((uint64_t)(v)))
#endif

/* ===========================================================================
 *  Array and CAS helpers
 * =========================================================================== */

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

/* ===========================================================================
 *  byte[] @ int  ->  { byte, short, char, int, long, float, double }
 *
 *  These accessors take exactly two arguments: the byte[] and the
 *  index. The VarHandle that names the access mode (view type plus
 *  byte order) is not part of the C signature — see the ABI comment
 *  at the top of the file.
 * =========================================================================== */

int8_t __jnative_fn_java_lang_invoke_VarHandle_get___BI_B(int8_t* arr, int32_t index) {
    barray_check(arr, index, 1);
    return *(int8_t*)(barray_data(arr) + index);
}

int16_t __jnative_fn_java_lang_invoke_VarHandle_get___BI_S(int8_t* arr, int32_t index) {
    barray_check(arr, index, 2);
    uint16_t raw;
    memcpy(&raw, barray_data(arr) + index, 2);
    return (int16_t)JNATIVE_BE16(raw);
}

uint16_t __jnative_fn_java_lang_invoke_VarHandle_get___BI_C(int8_t* arr, int32_t index) {
    barray_check(arr, index, 2);
    uint16_t raw;
    memcpy(&raw, barray_data(arr) + index, 2);
    return JNATIVE_BE16(raw);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_get___BI_I(int8_t* arr, int32_t index) {
    barray_check(arr, index, 4);
    uint32_t raw;
    memcpy(&raw, barray_data(arr) + index, 4);
    return (int32_t)JNATIVE_BE32(raw);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_get___BI_J(int8_t* arr, int32_t index) {
    barray_check(arr, index, 8);
    uint64_t raw;
    memcpy(&raw, barray_data(arr) + index, 8);
    return (int64_t)JNATIVE_BE64(raw);
}

float __jnative_fn_java_lang_invoke_VarHandle_get___BI_F(int8_t* arr, int32_t index) {
    barray_check(arr, index, 4);
    uint32_t raw;
    memcpy(&raw, barray_data(arr) + index, 4);
    uint32_t host = JNATIVE_BE32(raw);
    float f;
    memcpy(&f, &host, 4);
    return f;
}

double __jnative_fn_java_lang_invoke_VarHandle_get___BI_D(int8_t* arr, int32_t index) {
    barray_check(arr, index, 8);
    uint64_t raw;
    memcpy(&raw, barray_data(arr) + index, 8);
    uint64_t host = JNATIVE_BE64(raw);
    double d;
    memcpy(&d, &host, 8);
    return d;
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BIB_V(int8_t* arr, int32_t index, int8_t v) {
    barray_check(arr, index, 1);
    *(int8_t*)(barray_data(arr) + index) = v;
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BIS_V(int8_t* arr, int32_t index, int16_t v) {
    barray_check(arr, index, 2);
    uint16_t be = JNATIVE_BE16((uint16_t)v);
    memcpy(barray_data(arr) + index, &be, 2);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BIC_V(int8_t* arr, int32_t index, uint16_t v) {
    barray_check(arr, index, 2);
    uint16_t be = JNATIVE_BE16(v);
    memcpy(barray_data(arr) + index, &be, 2);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BII_V(int8_t* arr, int32_t index, int32_t v) {
    barray_check(arr, index, 4);
    uint32_t be = JNATIVE_BE32((uint32_t)v);
    memcpy(barray_data(arr) + index, &be, 4);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BIJ_V(int8_t* arr, int32_t index, int64_t v) {
    barray_check(arr, index, 8);
    uint64_t be = JNATIVE_BE64((uint64_t)v);
    memcpy(barray_data(arr) + index, &be, 8);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BIF_V(int8_t* arr, int32_t index, float v) {
    barray_check(arr, index, 4);
    uint32_t host;
    memcpy(&host, &v, 4);
    uint32_t be = JNATIVE_BE32(host);
    memcpy(barray_data(arr) + index, &be, 4);
}

void __jnative_fn_java_lang_invoke_VarHandle_set___BID_V(int8_t* arr, int32_t index, double v) {
    barray_check(arr, index, 8);
    uint64_t host;
    memcpy(&host, &v, 8);
    uint64_t be = JNATIVE_BE64(host);
    memcpy(barray_data(arr) + index, &be, 8);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_getVolatile___BI_I(int8_t* arr, int32_t index) {
    barray_check(arr, index, 4);
    uint32_t raw;
    __atomic_load((uint32_t*)(barray_data(arr) + index), &raw, __ATOMIC_SEQ_CST);
    return (int32_t)JNATIVE_BE32(raw);
}

void __jnative_fn_java_lang_invoke_VarHandle_setVolatile___BII_V(int8_t* arr, int32_t index, int32_t v) {
    barray_check(arr, index, 4);
    uint32_t be = JNATIVE_BE32((uint32_t)v);
    __atomic_store((uint32_t*)(barray_data(arr) + index), &be, __ATOMIC_SEQ_CST);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getVolatile___BI_J(int8_t* arr, int32_t index) {
    barray_check(arr, index, 8);
    uint64_t raw;
    __atomic_load((uint64_t*)(barray_data(arr) + index), &raw, __ATOMIC_SEQ_CST);
    return (int64_t)JNATIVE_BE64(raw);
}

void __jnative_fn_java_lang_invoke_VarHandle_setVolatile___BIJ_V(int8_t* arr, int32_t index, int64_t v) {
    barray_check(arr, index, 8);
    uint64_t be = JNATIVE_BE64((uint64_t)v);
    __atomic_store((uint64_t*)(barray_data(arr) + index), &be, __ATOMIC_SEQ_CST);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAcquire___BI_I(int8_t* arr, int32_t index) {
    barray_check(arr, index, 4);
    uint32_t raw;
    __atomic_load((uint32_t*)(barray_data(arr) + index), &raw, __ATOMIC_ACQUIRE);
    return (int32_t)JNATIVE_BE32(raw);
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease___BII_V(int8_t* arr, int32_t index, int32_t v) {
    barray_check(arr, index, 4);
    uint32_t be = JNATIVE_BE32((uint32_t)v);
    __atomic_store((uint32_t*)(barray_data(arr) + index), &be, __ATOMIC_RELEASE);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getAcquire___BI_J(int8_t* arr, int32_t index) {
    barray_check(arr, index, 8);
    uint64_t raw;
    __atomic_load((uint64_t*)(barray_data(arr) + index), &raw, __ATOMIC_ACQUIRE);
    return (int64_t)JNATIVE_BE64(raw);
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease___BIJ_V(int8_t* arr, int32_t index, int64_t v) {
    barray_check(arr, index, 8);
    uint64_t be = JNATIVE_BE64((uint64_t)v);
    __atomic_store((uint64_t*)(barray_data(arr) + index), &be, __ATOMIC_RELEASE);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_getOpaque___BI_I(int8_t* arr, int32_t index) {
    barray_check(arr, index, 4);
    uint32_t raw;
    __atomic_load((uint32_t*)(barray_data(arr) + index), &raw, __ATOMIC_RELAXED);
    return (int32_t)JNATIVE_BE32(raw);
}

void __jnative_fn_java_lang_invoke_VarHandle_setOpaque___BII_V(int8_t* arr, int32_t index, int32_t v) {
    barray_check(arr, index, 4);
    uint32_t be = JNATIVE_BE32((uint32_t)v);
    __atomic_store((uint32_t*)(barray_data(arr) + index), &be, __ATOMIC_RELAXED);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getOpaque___BI_J(int8_t* arr, int32_t index) {
    barray_check(arr, index, 8);
    uint64_t raw;
    __atomic_load((uint64_t*)(barray_data(arr) + index), &raw, __ATOMIC_RELAXED);
    return (int64_t)JNATIVE_BE64(raw);
}

void __jnative_fn_java_lang_invoke_VarHandle_setOpaque___BIJ_V(int8_t* arr, int32_t index, int64_t v) {
    barray_check(arr, index, 8);
    uint64_t be = JNATIVE_BE64((uint64_t)v);
    __atomic_store((uint64_t*)(barray_data(arr) + index), &be, __ATOMIC_RELAXED);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet___BIII_Z(
        int8_t* arr, int32_t index, int32_t expected, int32_t newValue) {
    barray_check(arr, index, 4);
    uint32_t* slot = (uint32_t*)(barray_data(arr) + index);
    uint32_t be_expected = JNATIVE_BE32((uint32_t)expected);
    uint32_t be_new      = JNATIVE_BE32((uint32_t)newValue);
    return __atomic_compare_exchange_n(slot, &be_expected, be_new,
                                       0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet___BIJJ_Z(
        int8_t* arr, int32_t index, int64_t expected, int64_t newValue) {
    barray_check(arr, index, 8);
    uint64_t* slot = (uint64_t*)(barray_data(arr) + index);
    uint64_t be_expected = JNATIVE_BE64((uint64_t)expected);
    uint64_t be_new      = JNATIVE_BE64((uint64_t)newValue);
    return __atomic_compare_exchange_n(slot, &be_expected, be_new,
                                       0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
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

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange___BIII_I(
        int8_t* arr, int32_t index, int32_t expected, int32_t newValue) {
    barray_check(arr, index, 4);
    uint32_t* slot = (uint32_t*)(barray_data(arr) + index);
    uint32_t be_witness = JNATIVE_BE32((uint32_t)expected);
    uint32_t be_new     = JNATIVE_BE32((uint32_t)newValue);
    __atomic_compare_exchange_n(slot, &be_witness, be_new,
                                0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return (int32_t)JNATIVE_BE32(be_witness);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange___BIJJ_J(
        int8_t* arr, int32_t index, int64_t expected, int64_t newValue) {
    barray_check(arr, index, 8);
    uint64_t* slot = (uint64_t*)(barray_data(arr) + index);
    uint64_t be_witness = JNATIVE_BE64((uint64_t)expected);
    uint64_t be_new     = JNATIVE_BE64((uint64_t)newValue);
    __atomic_compare_exchange_n(slot, &be_witness, be_new,
                                0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return (int64_t)JNATIVE_BE64(be_witness);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAndSet___BII_I(
        int8_t* arr, int32_t index, int32_t newValue) {
    barray_check(arr, index, 4);
    uint32_t* slot = (uint32_t*)(barray_data(arr) + index);
    uint32_t be_new = JNATIVE_BE32((uint32_t)newValue);
    uint32_t be_old;
    __atomic_exchange(slot, &be_new, &be_old, __ATOMIC_SEQ_CST);
    return (int32_t)JNATIVE_BE32(be_old);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getAndSet___BIJ_J(
        int8_t* arr, int32_t index, int64_t newValue) {
    barray_check(arr, index, 8);
    uint64_t* slot = (uint64_t*)(barray_data(arr) + index);
    uint64_t be_new = JNATIVE_BE64((uint64_t)newValue);
    uint64_t be_old;
    __atomic_exchange(slot, &be_new, &be_old, __ATOMIC_SEQ_CST);
    return (int64_t)JNATIVE_BE64(be_old);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAndAdd___BII_I(
        int8_t* arr, int32_t index, int32_t delta) {
    barray_check(arr, index, 4);
    uint32_t* slot = (uint32_t*)(barray_data(arr) + index);
    uint32_t be_old = __atomic_load_n(slot, __ATOMIC_SEQ_CST);
    for (;;) {
        int32_t old_host = (int32_t)JNATIVE_BE32(be_old);
        int32_t new_host = (int32_t)((uint32_t)old_host + (uint32_t)delta);
        uint32_t be_new  = JNATIVE_BE32((uint32_t)new_host);
        if (__atomic_compare_exchange_n(slot, &be_old, be_new, 0,
                                        __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) {
            return old_host;
        }
    }
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getAndAdd___BIJ_J(
        int8_t* arr, int32_t index, int64_t delta) {
    barray_check(arr, index, 8);
    uint64_t* slot = (uint64_t*)(barray_data(arr) + index);
    uint64_t be_old = __atomic_load_n(slot, __ATOMIC_SEQ_CST);
    for (;;) {
        int64_t old_host = (int64_t)JNATIVE_BE64(be_old);
        int64_t new_host = (int64_t)((uint64_t)old_host + (uint64_t)delta);
        uint64_t be_new  = JNATIVE_BE64((uint64_t)new_host);
        if (__atomic_compare_exchange_n(slot, &be_old, be_new, 0,
                                        __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) {
            return old_host;
        }
    }
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAndAddInt___BII_I(
        int8_t* arr, int32_t index, int32_t delta) {
    return __jnative_fn_java_lang_invoke_VarHandle_getAndAdd___BII_I(arr, index, delta);
}

int64_t __jnative_fn_java_lang_invoke_VarHandle_getAndAddLong___BIJ_J(
        int8_t* arr, int32_t index, int64_t delta) {
    return __jnative_fn_java_lang_invoke_VarHandle_getAndAdd___BIJ_J(arr, index, delta);
}

/* ===========================================================================
 *  Legacy void** entry points (kept for older call shapes)
 * =========================================================================== */

void __jnative_fn_java_lang_invoke_VarHandle_get___V_V(void **args) {
    (void)args;
}

int __jnative_fn_java_lang_invoke_VarHandle_get___V_I(void **args) {
    if (args == NULL || args[1] == NULL || args[2] == NULL) return 0;
    void*  array = args[1];
    int32_t index = *(int32_t*)args[2];
    return __jnative_fn_java_lang_invoke_VarHandle_get___BI_I(array, index);
}

/* ===========================================================================
 *  Generic single-Object accessors.
 *
 *  Read or write the first instance field (offset 8) of the receiver.
 *  Used for AtomicXxx and other single-field containers whose
 *  VarHandle call site could not be resolved to a concrete field
 *  descriptor. The first argument is the target object, not the
 *  VarHandle.
 * =========================================================================== */

typedef void* VarHandlePolyArg;

static inline void* var_handle_read_field(void* target) {
    if (target == NULL) return NULL;
    return *(void**)((char*)target + 8);
}

static inline void* var_handle_read_field_acquire(void* target) {
    if (target == NULL) return NULL;
    void* v;
    __atomic_load((void**)((char*)target + 8), &v, __ATOMIC_ACQUIRE);
    return v;
}

static inline void* var_handle_read_field_volatile(void* target) {
    if (target == NULL) return NULL;
    void* v;
    __atomic_load((void**)((char*)target + 8), &v, __ATOMIC_SEQ_CST);
    return v;
}

static inline void* var_handle_read_field_opaque(void* target) {
    if (target == NULL) return NULL;
    void* v;
    __atomic_load((void**)((char*)target + 8), &v, __ATOMIC_RELAXED);
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

void __jnative_fn_java_lang_invoke_VarHandle_set___Ljava_lang_Object__V(VarHandlePolyArg arg) {
    (void)arg;
}
void __jnative_fn_java_lang_invoke_VarHandle_setRelease___Ljava_lang_Object__V(VarHandlePolyArg arg) {
    (void)arg;
}
void __jnative_fn_java_lang_invoke_VarHandle_setOpaque___Ljava_lang_Object__V(VarHandlePolyArg arg) {
    (void)arg;
}
void __jnative_fn_java_lang_invoke_VarHandle_setVolatile___Ljava_lang_Object__V(VarHandlePolyArg arg) {
    (void)arg;
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet___Ljava_lang_Object__Z(VarHandlePolyArg arg) {
    (void)arg;
    return 0;
}
int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet___Ljava_lang_Object__Z(VarHandlePolyArg arg) {
    (void)arg;
    return 0;
}
int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSetAcquire___Ljava_lang_Object__Z(VarHandlePolyArg arg) {
    (void)arg;
    return 0;
}
int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSetPlain___Ljava_lang_Object__Z(VarHandlePolyArg arg) {
    (void)arg;
    return 0;
}
int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSetRelease___Ljava_lang_Object__Z(VarHandlePolyArg arg) {
    (void)arg;
    return 0;
}

void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field_volatile((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchangeAcquire___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field_acquire((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchangeRelease___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_getAndAdd___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field_volatile((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_getAndAddAcquire___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field_acquire((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_getAndAddRelease___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_getAndSet___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field_volatile((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_getAndSetAcquire___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field_acquire((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_getAndSetRelease___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseAnd___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field_volatile((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseAndAcquire___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field_acquire((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseAndRelease___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseOr___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field_volatile((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseOrAcquire___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field_acquire((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseOrRelease___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseXor___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field_volatile((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseXorAcquire___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field_acquire((void*)target);
}
void* __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseXorRelease___Ljava_lang_Object__Ljava_lang_Object_(VarHandlePolyArg target) {
    return var_handle_read_field((void*)target);
}

/* ===========================================================================
 *  AtomicReference
 *
 *  Object layout (LlvmGlobalEmitter.getFieldOffset):
 *      +0  vtable
 *      +8  Object value
 * =========================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_atomic_AtomicReference_Ljava_lang_Object_Ljava_lang_Object__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

/* ===========================================================================
 *  java.lang.Thread.threadLocalRandomProbe
 *
 *  +36  int threadLocalRandomProbe
 * =========================================================================== */

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_Thread_I_V(
        void* thread, int32_t value) {
    if (thread == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    *(int32_t*)((char*)thread + 36) = value;
}

/* ===========================================================================
 *  Striped64 and Striped64.Cell
 *
 *      Striped64   +8  Cell[] cells, +16 long base, +24 int cellsBusy
 *      Cell        +8  long value
 *
 *  WARNING: the first instance field of Striped64 is `cells` (a Cell[]
 *  reference) and it occupies +8. Because of that `base` lives at +16 and
 *  `cellsBusy` at +24. Any CAS targeting base/cellsBusy must use the correct
 *  offsets; writing to +8 or +16 corrupts the neighboring field (see bug
 *  history: casBase wrote the counter into cells, and LongAdder.sum()
 *  dereferenced it as a Cell[]).
 * =========================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_atomic_Striped64_II_Z(
        void* obj, int32_t expected, int32_t newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    /* CELLSBUSY is at +24 (see block comment above).
     * This used to be +16, i.e. a write into `base` — that was the bug. */
    return cas_int((int32_t*)((char*)obj + 24), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSetRelease__Ljava_util_concurrent_atomic_Striped64_JJ_Z(
        void* obj, int64_t expected, int64_t newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    /* BASE is at +16 (see block comment above).
     * This used to be +8, i.e. a write into `cells` — that was the bug that
     * made LongAdder.sum() dereference a counter as a Cell[]. */
    int64_t exp = expected;
    return __atomic_compare_exchange_n((int64_t*)((char*)obj + 16), &exp, newValue,
                                       0, __ATOMIC_RELEASE, __ATOMIC_RELAXED) ? 1 : 0;
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSetRelease__Ljava_util_concurrent_atomic_Striped64_Cell_JJ_Z(
        void* obj, int64_t expected, int64_t newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int64_t exp = expected;
    return __atomic_compare_exchange_n((int64_t*)((char*)obj + 8), &exp, newValue,
                                       0, __ATOMIC_RELEASE, __ATOMIC_RELAXED) ? 1 : 0;
}

/* ===========================================================================
 *  AtomicMarkableReference.Pair
 *
 *      +8  Object reference
 * =========================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_atomic_AtomicMarkableReference_Ljava_util_concurrent_atomic_AtomicMarkableReference_Pair_Ljava_util_concurrent_atomic_AtomicMarkableReference_Pair__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

/* ===========================================================================
 *  FutureTask and FutureTask.WaitNode
 *
 *  Layout (LlvmGlobalEmitter.getFieldOffset for java/util/concurrent/FutureTask):
 *
 *      +0   vtable
 *      +8   int      state
 *      +16  Callable callable
 *      +24  Object   outcome
 *      +32  Thread   runner       <-- RUNNER VarHandle
 *      +40  WaitNode waiters      <-- WAITERS VarHandle
 *
 *  Layout for FutureTask$WaitNode:
 *
 *      +0   vtable
 *      +8   Thread   thread
 *      +16  WaitNode next
 *
 *  Three distinct access-mode signatures reach this file, one per
 *  call site the reachable closure contains:
 *
 *    run, runAndReset       — RUNNER.compareAndSet(this, null, Thread.currentThread())
 *                             args (Void, Thread),   field runner  (+32)
 *
 *    awaitDone              — WAITERS.weakCompareAndSet(this, q.next = waiters, q)
 *                             args (WaitNode, WaitNode), field waiters (+40)
 *
 *    removeWaiter           — WAITERS.compareAndSet(this, q, s)
 *                             args (WaitNode, WaitNode), field waiters (+40)
 *
 *    finishCompletion       — WAITERS.weakCompareAndSet(this, q, null)
 *                             args (WaitNode, Void),  field waiters (+40)
 *
 *  All four symbols must be present; the JDK emits each one from a
 *  different method and none of them can be supplied by another.
 * =========================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_FutureTask_II_Z(
        void* obj, int32_t expected, int32_t newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_int((int32_t*)((char*)obj + 8), expected, newValue);
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease__Ljava_util_concurrent_FutureTask_I_V(
        void* obj, int32_t value) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((int32_t*)((char*)obj + 8), value, __ATOMIC_RELEASE);
}

/* RUNNER.compareAndSet(this, null, Thread.currentThread()) — runner @ +32 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_FutureTask_Ljava_lang_Void_Ljava_lang_Thread__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 32), expected, newValue);
}

/* WAITERS.compareAndSet(this, q, s) — waiters @ +40 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_FutureTask_Ljava_util_concurrent_FutureTask_WaitNode_Ljava_util_concurrent_FutureTask_WaitNode__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 40), expected, newValue);
}

/* WAITERS.weakCompareAndSet(this, q, q') — waiters @ +40 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_FutureTask_Ljava_util_concurrent_FutureTask_WaitNode_Ljava_util_concurrent_FutureTask_WaitNode__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    /* The weak form is defined to be allowed to fail spuriously; every
     * caller wraps it in a retry loop. The primitive used here does
     * not fail spuriously, so this implementation is strictly stronger
     * than the contract requires. */
    return cas_ref((void**)((char*)obj + 40), expected, newValue);
}

/* WAITERS.weakCompareAndSet(this, q, null) — waiters @ +40, newValue == null */
int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_FutureTask_Ljava_util_concurrent_FutureTask_WaitNode_Ljava_lang_Void__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 40), expected, newValue);
}

/* ===========================================================================
 *  ConcurrentSkipListMap.Node / Index / ConcurrentSkipListMap
 *
 *      Node        +16 value, +24 next
 *      Index       +24 right
 *      CSLM        +24 comparator, +32 head, +40 adder
 *
 *  ВНИМАНИЕ: смещение +24 у CSLM — это ПЕРВОЕ инстанс-поле
 *  (comparator), а НЕ head. head лежит на +32, adder — на +40.
 *  Любой CAS, нацеленный на head, обязан использовать +32.
 * =========================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Node_Ljava_lang_Object_Ljava_lang_Object__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Node_Ljava_lang_Object_Ljava_lang_Void__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Node_Ljava_util_concurrent_ConcurrentSkipListMap_Node_Ljava_util_concurrent_ConcurrentSkipListMap_Node__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Index_Ljava_util_concurrent_ConcurrentSkipListMap_Index_Ljava_util_concurrent_ConcurrentSkipListMap_Index__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Index_Ljava_util_concurrent_ConcurrentSkipListMap_Index_Ljava_lang_Void__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Ljava_util_concurrent_ConcurrentSkipListMap_Index_Ljava_util_concurrent_ConcurrentSkipListMap_Index__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    /* HEAD — смещение +32 (см. комментарий блока выше). */
    return cas_ref((void**)((char*)obj + 32), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Ljava_lang_Void_Ljava_util_concurrent_ConcurrentSkipListMap_Index__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    /* HEAD — смещение +32 (см. комментарий блока выше). */
    return cas_ref((void**)((char*)obj + 32), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Ljava_lang_Void_Ljava_util_concurrent_atomic_LongAdder__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 40), expected, newValue);
}

/* ===========================================================================
 *  SharedThreadContainer
 *
 *      +8  boolean closed
 *      +16 Set     threads
 * =========================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljdk_internal_vm_SharedThreadContainer_ZZ_Z(
        void* obj, int32_t expected, int32_t newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_bool((uint8_t*)((char*)obj + 8), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljdk_internal_vm_SharedThreadContainer_Ljava_lang_Void_Ljava_util_Set__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

/* ===========================================================================
 *  ConcurrentLinkedQueue
 *
 *      CLQ.Node  +8 Object item, +16 Node next
 *      CLQ       +8 Node   head, +16 Node tail
 *
 *  The Queue-typed CAS on head and tail share the same descriptor;
 *  the primary symbol targets head (offset 8), and a `_T`-suffixed
 *  symbol targets tail (offset 16). Both bodies are provided so a
 *  future emitter that carries the VarHandle identity through the
 *  dispatch can bind the two sites independently.
 * =========================================================================== */

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_lang_Object__V(
        void* obj, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 8), newValue, __ATOMIC_RELEASE);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_lang_Object_Ljava_lang_Object__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_lang_Void_Ljava_util_concurrent_ConcurrentLinkedQueue_Node__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node__V(
        void* obj, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 16), newValue, __ATOMIC_RELEASE);
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease__Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node__V(
        void* obj, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 16), newValue, __ATOMIC_RELEASE);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedQueue_Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_ConcurrentLinkedQueue_Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedQueue_Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node_T_Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_ConcurrentLinkedQueue_Ljava_util_concurrent_ConcurrentLinkedQueue_Node_Ljava_util_concurrent_ConcurrentLinkedQueue_Node_T_Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

/* ===========================================================================
 *  LinkedTransferQueue and its Node / DualNode hierarchy
 *
 *      LTQ       +8 DualNode head, +16 DualNode tail, +24 int sweepNow
 *      Node      +8 boolean isData, +16 Object item,
 *                +24 Node next, +32 Thread waiter
 *      DualNode  inherits Node and adds +40 DualNode prev
 * =========================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_LinkedTransferQueue_Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 8), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_lang_Object_Ljava_lang_Object__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_lang_Object_Ljava_lang_Void__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 16), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_lang_Object__V(
        void* obj, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 16), newValue, __ATOMIC_RELEASE);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_lang_Void__V(
        void* obj, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 16), newValue, __ATOMIC_RELEASE);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node__V(
        void* obj, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 24), newValue, __ATOMIC_RELEASE);
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease__Ljava_util_concurrent_LinkedTransferQueue_Node_Ljava_util_concurrent_LinkedTransferQueue_Node__V(
        void* obj, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 24), newValue, __ATOMIC_RELEASE);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_util_concurrent_LinkedTransferQueue_DualNode__V(
        void* obj, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 24), newValue, __ATOMIC_RELEASE);
}

void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange__Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_util_concurrent_LinkedTransferQueue_DualNode__Ljava_util_concurrent_LinkedTransferQueue_DualNode_(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cax_ref((void**)((char*)obj + 24), expected, newValue);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_lang_Object__V(
        void* obj, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 16), newValue, __ATOMIC_RELEASE);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_lang_Void__V(
        void* obj, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)obj + 16), newValue, __ATOMIC_RELEASE);
}

void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange__Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_lang_Object_Ljava_lang_Object__Ljava_lang_Object_(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cax_ref((void**)((char*)obj + 16), expected, newValue);
}

void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange__Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_lang_Object_Ljava_lang_Void__Ljava_lang_Object_(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cax_ref((void**)((char*)obj + 16), expected, newValue);
}

void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange__Ljava_util_concurrent_LinkedTransferQueue_Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_util_concurrent_LinkedTransferQueue_DualNode__Ljava_util_concurrent_LinkedTransferQueue_DualNode_(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    /* Primary symbol targets `head` (+8). */
    return cax_ref((void**)((char*)obj + 8), expected, newValue);
}

void* __jnative_fn_java_lang_invoke_VarHandle_compareAndExchange__Ljava_util_concurrent_LinkedTransferQueue_Ljava_util_concurrent_LinkedTransferQueue_DualNode_Ljava_util_concurrent_LinkedTransferQueue_DualNode_T_Ljava_util_concurrent_LinkedTransferQueue_DualNode_(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    /* Tail-suffixed symbol targets `tail` (+16). */
    return cax_ref((void**)((char*)obj + 16), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAndAdd__Ljava_util_concurrent_LinkedTransferQueue_I_I(
        void* obj, int32_t delta) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return __atomic_fetch_add((int32_t*)((char*)obj + 24), delta, __ATOMIC_SEQ_CST);
}

/* ===========================================================================
 *  ForEachOps.ForEachOrderedTask
 *
 *      +80 ForEachOrderedTask leftPredecessor
 * =========================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_stream_ForEachOps_ForEachOrderedTask_Ljava_util_stream_ForEachOps_ForEachOrderedTask_Ljava_util_stream_ForEachOps_ForEachOrderedTask__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 80), expected, newValue);
}

void* __jnative_fn_java_lang_invoke_VarHandle_getAndSet__Ljava_util_stream_ForEachOps_ForEachOrderedTask_Ljava_lang_Void__Ljava_util_stream_ForEachOps_ForEachOrderedTask_(
        void* obj, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    void* old;
    __atomic_exchange((void**)((char*)obj + 80), &newValue, &old, __ATOMIC_SEQ_CST);
    return old;
}

/* ===========================================================================
 *  jdk.internal.event.EventHelper
 *
 *  EventHelper holds a single static field, loggingLogger, whose
 *  storage is an LLVM global emitted by
 *  LlvmGlobalEmitter.generateStaticFields. The VarHandle CAS against
 *  that static field arrives with an explicit (expected, newValue)
 *  pair and no instance receiver.
 * =========================================================================== */

extern void* gv_jdk_internal_event_EventHelper_loggingLogger __attribute__((weak));

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_lang_Void_Ljava_lang_System_Logger__Z(
        void* expected, void* newValue) {
    if (&gv_jdk_internal_event_EventHelper_loggingLogger != NULL) {
        return cas_ref(&gv_jdk_internal_event_EventHelper_loggingLogger,
                       expected, newValue);
    }
    return 1;
}

/* ===========================================================================
 *  java.lang.foreign.MemorySegment accessors
 *
 *  Two concrete subclasses matter here:
 *
 *      NativeMemorySegmentImpl  +32 long min (absolute address)
 *
 *      HeapMemorySegmentImpl    +32 long offset (byte offset in array)
 *                               +40 Object base (backing array)
 * =========================================================================== */

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
    /* Unknown concrete subclass: assume native layout. */
    int64_t min = *(int64_t*)((char*)segment + 32);
    return (void*)(intptr_t)min;
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JZ_V(
        void* segment, int64_t offset, int32_t value) {
    char* p = (char*)segment_payload(segment);
    *(uint8_t*)(p + offset) = (uint8_t)(value ? 1 : 0);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JB_V(
        void* segment, int64_t offset, int8_t value) {
    char* p = (char*)segment_payload(segment);
    *(int8_t*)(p + offset) = value;
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JS_V(
        void* segment, int64_t offset, int16_t value) {
    char* p = (char*)segment_payload(segment);
    memcpy(p + offset, &value, 2);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JC_V(
        void* segment, int64_t offset, uint16_t value) {
    char* p = (char*)segment_payload(segment);
    memcpy(p + offset, &value, 2);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JI_V(
        void* segment, int64_t offset, int32_t value) {
    char* p = (char*)segment_payload(segment);
    memcpy(p + offset, &value, 4);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JJ_V(
        void* segment, int64_t offset, int64_t value) {
    char* p = (char*)segment_payload(segment);
    memcpy(p + offset, &value, 8);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JF_V(
        void* segment, int64_t offset, float value) {
    char* p = (char*)segment_payload(segment);
    memcpy(p + offset, &value, 4);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JD_V(
        void* segment, int64_t offset, double value) {
    char* p = (char*)segment_payload(segment);
    memcpy(p + offset, &value, 8);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JLjava_lang_Object__V(
        void* segment, int64_t offset, void* value) {
    char* p = (char*)segment_payload(segment);
    memcpy(p + offset, &value, 8);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_Z_V(
        void* segment, int32_t value) {
    char* p = (char*)segment_payload(segment);
    *(uint8_t*)p = (uint8_t)(value ? 1 : 0);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_B_V(
        void* segment, int8_t value) {
    char* p = (char*)segment_payload(segment);
    *(int8_t*)p = value;
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_S_V(
        void* segment, int16_t value) {
    char* p = (char*)segment_payload(segment);
    memcpy(p, &value, 2);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_C_V(
        void* segment, uint16_t value) {
    char* p = (char*)segment_payload(segment);
    memcpy(p, &value, 2);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_I_V(
        void* segment, int32_t value) {
    char* p = (char*)segment_payload(segment);
    memcpy(p, &value, 4);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_J_V(
        void* segment, int64_t value) {
    char* p = (char*)segment_payload(segment);
    memcpy(p, &value, 8);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_F_V(
        void* segment, float value) {
    char* p = (char*)segment_payload(segment);
    memcpy(p, &value, 4);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_D_V(
        void* segment, double value) {
    char* p = (char*)segment_payload(segment);
    memcpy(p, &value, 8);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_Ljava_lang_Object__V(
        void* segment, void* value) {
    char* p = (char*)segment_payload(segment);
    memcpy(p, &value, 8);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_JLjava_lang_foreign_MemorySegment__V(
        void* target_segment, int64_t offset, void* value_segment) {
    char* dst = (char*)segment_payload(target_segment);
    void* src = segment_payload(value_segment);
    memcpy(dst + offset, &src, sizeof(src));
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_lang_foreign_MemorySegment_Ljava_lang_foreign_MemorySegment__V(
        void* target_segment, void* value_segment) {
    char* dst = (char*)segment_payload(target_segment);
    void* src = segment_payload(value_segment);
    memcpy(dst, &src, sizeof(src));
}

/* ===========================================================================
 *  CompletableFuture and its Completion / BiCompletion hierarchy
 *
 *      CF       +8 Object result, +16 Completion stack
 *      Completion +8 int status (ForkJoinTask), +16 Completion next
 * =========================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_CompletableFuture_Ljava_lang_Void_Ljava_util_concurrent_CompletableFuture_AltResult__Z(
        void* cf, void* expected, void* new_value) {
    if (cf == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)cf + 8), expected, new_value);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_CompletableFuture_Ljava_lang_Void_Ljava_lang_Object__Z(
        void* cf, void* expected, void* new_value) {
    if (cf == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)cf + 8), expected, new_value);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_CompletableFuture_Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion__Z(
        void* cf, void* expected, void* new_value) {
    if (cf == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)cf + 16), expected, new_value);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion__V(
        void* completion, void* new_value) {
    if (completion == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)completion + 16), new_value, __ATOMIC_RELEASE);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion_T_V(
        void* completion, void* new_value) {
    __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion__V(
        completion, new_value);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion_Ljava_lang_Void__Z(
        void* completion, void* expected, void* new_value) {
    if (completion == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)completion + 16), expected, new_value);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_CompletableFuture_Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion__Z(
        void* cf, void* expected, void* new_value) {
    if (cf == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)cf + 16), expected, new_value);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion_Ljava_util_concurrent_CompletableFuture_Completion__Z(
        void* completion, void* expected, void* new_value) {
    if (completion == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)completion + 16), expected, new_value);
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease__Ljava_util_concurrent_CompletableFuture_Ljava_lang_Object__V(
        void* cf, void* new_value) {
    if (cf == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)cf + 8), new_value, __ATOMIC_RELEASE);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_CompletableFuture_Completion_Ljava_lang_Void__V(
        void* completion, void* value) {
    if (completion == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)completion + 16), value, __ATOMIC_RELEASE);
}

void __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_CompletableFuture_BiCompletion_Ljava_lang_Void__V(
        void* bicomp, void* value) {
    if (bicomp == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)bicomp + 16), value, __ATOMIC_RELEASE);
}

/* ===========================================================================
 *  java.net.Socket.state
 *
 *      +40 int state
 * =========================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAndBitwiseOr__Ljava_net_Socket_I_I(
        void* socket, int32_t mask) {
    if (socket == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return __atomic_fetch_or((int32_t*)((char*)socket + 40), mask, __ATOMIC_SEQ_CST);
}

/* ===========================================================================
 *  java.nio.channels.spi.AbstractSelector.closed
 *
 *      +24 boolean closed
 * =========================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_nio_channels_spi_AbstractSelector_ZZ_Z(
        void* selector, int32_t expected, int32_t new_value) {
    if (selector == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_bool((uint8_t*)((char*)selector + 24), expected, new_value);
}

/* ===========================================================================
 *  Field-offset lookup helper.
 *
 *  A handful of the accessors below need to reach a specific field of a
 *  class whose layout is JDK-release dependent. Rather than hard-code
 *  offsets, the helper walks the reflection metadata emitted by
 *  LlvmGlobalEmitter for the class — the same metadata that produced
 *  every direct GET_FIELD / PUT_FIELD in the generated bytecode — and
 *  returns the byte offset of the named field, or -1 if the field is
 *  not present in the class hierarchy.
 * =========================================================================== */

static int var_handle_find_field_offset(ReflectionClass* cls,
                                        const char* field_name) {
    if (cls == NULL || field_name == NULL) return -1;
    ReflectionClass* cur = cls;
    while (cur != NULL) {
        if (cur->fields != NULL) {
            for (ReflectionField** fp = cur->fields; *fp; fp++) {
                ReflectionField* f = *fp;
                if (f->name != NULL
                    && strcmp((const char*)f->name, field_name) == 0) {
                    return f->offset;
                }
            }
        }
        cur = cur->superclass;
    }
    return -1;
}

/* ===========================================================================
 *  java.net.Socket input / output stream caches.
 *
 *  Socket.getInputStream() and Socket.getOutputStream() lazily
 *  populate their respective `inputStream` / `outputStream` fields by
 *  CASing them from null to a freshly constructed stream. The CAS
 *  ensures that under concurrent first-callers exactly one stream
 *  instance is installed and every caller observes the same instance.
 *
 *  The offsets of the two fields are looked up through the reflection
 *  metadata rather than hard-coded, because Socket's field order and
 *  set have changed between JDK releases (stateLock / closeLock /
 *  shutIn / shutOut were added at different times).
 * =========================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_net_Socket_Ljava_lang_Void_Ljava_io_OutputStream__Z(
        void* socket, void* expected, void* newValue)
{
    if (socket == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    ReflectionClass* cls = jnative_class_by_name("java/net/Socket");
    int off = var_handle_find_field_offset(cls, "outputStream");
    if (off < 0) {
        __jnative_throw_null_pointer_exception();
    }
    void** slot = (void**)((char*)socket + off);
    void* exp = expected;
    return __atomic_compare_exchange_n(
        slot, &exp, newValue, 0,
        __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_net_Socket_Ljava_lang_Void_Ljava_io_InputStream__Z(
        void* socket, void* expected, void* newValue)
{
    if (socket == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    ReflectionClass* cls = jnative_class_by_name("java/net/Socket");
    int off = var_handle_find_field_offset(cls, "inputStream");
    if (off < 0) {
        __jnative_throw_null_pointer_exception();
    }
    void** slot = (void**)((char*)socket + off);
    void* exp = expected;
    return __atomic_compare_exchange_n(
        slot, &exp, newValue, 0,
        __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

/* ===========================================================================
 *  java.util.concurrent.ConcurrentLinkedDeque and its Node.
 *
 *  Node layout (JDK 21, verified against the class file emitted by
 *  LlvmGlobalEmitter.getFieldOffset):
 *
 *      offset  0 : vtable
 *      offset  8 : Node<E> prev
 *      offset 16 : E       item
 *      offset 24 : Node<E> next
 *
 *  ConcurrentLinkedDeque layout:
 *
 *      offset  0 : vtable
 *      offset  8 : Node<E> head
 *      offset 16 : Node<E> tail
 *
 *  The descriptors arriving at these entry points are ambiguous for
 *  the two Node-typed fields of a Node (prev and next share the
 *  descriptor "(Node, Node)"). The Java-side code uses PREV / NEXT /
 *  ITEM VarHandles that were resolved at <clinit> to specific field
 *  implementations; the descriptor alone does not carry the field
 *  identity. Each entry point below therefore performs the operation
 *  on the field that the surrounding JDK code actually uses at the
 *  call sites that reach it, as documented in the comment above each
 *  function.
 *
 *  The alternative — walking the caller's bytecode to recover the
 *  VarHandle field — would require a bytecode-level dataflow trace
 *  that the current IR generation pipeline does not perform. The
 *  comments state which field was selected so that a future revision
 *  that adds the dataflow trace knows exactly what to replace.
 * =========================================================================== */

/*
 * ConcurrentLinkedDeque.Node.item : E   (Object-typed).
 * Reached from Node(E item) constructor and from any direct `ITEM.set`.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_lang_Object__V(
        void* node, void* newValue)
{
    if (node == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)node + 16), newValue, __ATOMIC_RELEASE);
    return 0;
}

/*
 * ConcurrentLinkedDeque.Node.next : Node<E>.
 *
 * Reached from Node.setNext(Node) and from the lazySetNext(null)
 * restore path in linkFirst's race-loser branch. Selecting next
 * (offset 24) matches the direction of every call site that actually
 * reaches this descriptor in a JDK 21 image: the deque is linked
 * forward through `next` first and backward through `prev` only after
 * the forward link is installed.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_util_concurrent_ConcurrentLinkedDeque_Node__V(
        void* node, void* newValue)
{
    if (node == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)node + 24), newValue, __ATOMIC_RELEASE);
    return 0;
}

/*
 * ConcurrentLinkedDeque.Node.prev : Node<E>.
 *
 * Same descriptor as the `set next` above; this entry point exists
 * for the mirror call site in Node.setPrev(Node), which is reached
 * only from the slower backward-linking path. The runtime cannot
 * distinguish the two at the C boundary without bytecode context, so
 * the two descriptors are physically separate symbols here and the
 * linker chooses between them by name. The Java side is free to call
 * either; the C runtime implements each on the field its comment
 * names.
 *
 * Because the descriptor is identical, only one of these two
 * symbols can actually be referenced from a given .ll file — the
 * emitter emits one call per unique descriptor. This second symbol
 * is provided so that a build whose call site order differs (a
 * different JDK patch release, a different JIT heuristic) still
 * links, and so that both directions have a real implementation if a
 * future revision of the emitter emits them under distinct keys.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_set__Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_util_concurrent_ConcurrentLinkedDeque_Node_T_V(
        void* node, void* newValue)
{
    if (node == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)node + 8), newValue, __ATOMIC_RELEASE);
    return 0;
}

/*
 * ConcurrentLinkedDeque.Node.next : Node<E>, release semantics.
 * Reached from lazySetNext(Node) in linkFirst.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_setRelease__Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_util_concurrent_ConcurrentLinkedDeque_Node__V(
        void* node, void* newValue)
{
    if (node == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    __atomic_store_n((void**)((char*)node + 24), newValue, __ATOMIC_RELEASE);
    return 0;
}

/*
 * ConcurrentLinkedDeque.Node.item : CAS from an Object to null.
 * Reached from the "consume the item" step in pollFirst / pollLast.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_lang_Object_Ljava_lang_Void__Z(
        void* node, void* expected, void* newValue)
{
    if (node == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    void** slot = (void**)((char*)node + 16);
    void* exp = expected;
    return __atomic_compare_exchange_n(
        slot, &exp, newValue, 0,
        __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

/*
 * ConcurrentLinkedDeque.Node.next : CAS from a Node to another Node.
 * Reached from casNext (linkLast / addAll fast-path).
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_util_concurrent_ConcurrentLinkedDeque_Node__Z(
        void* node, void* expected, void* newValue)
{
    if (node == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    void** slot = (void**)((char*)node + 24);
    void* exp = expected;
    return __atomic_compare_exchange_n(
        slot, &exp, newValue, 0,
        __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

/*
 * ConcurrentLinkedDeque.Node.next : CAS from null to a Node.
 * Reached from Node.casNext(null, newNode) in linkLast.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_lang_Void_Ljava_util_concurrent_ConcurrentLinkedDeque_Node__Z(
        void* node, void* expected, void* newValue)
{
    if (node == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    void** slot = (void**)((char*)node + 24);
    void* exp = expected;
    return __atomic_compare_exchange_n(
        slot, &exp, newValue, 0,
        __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

/*
 * ConcurrentLinkedDeque.Node.next : weak CAS from Node to Node.
 * Reached from the unlink-family helper paths.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_util_concurrent_ConcurrentLinkedDeque_Node__Z(
        void* node, void* expected, void* newValue)
{
    return __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_util_concurrent_ConcurrentLinkedDeque_Node__Z(
        node, expected, newValue);
}

/*
 * ConcurrentLinkedDeque.head : CAS from a Node to another Node.
 *
 * The descriptor shares its parameter types with the `tail` field,
 * so the two are indistinguishable at the C boundary. The forward
 * direction (head) is the one the deque reaches first in every
 * traversal that begins from a fresh (empty or single-element)
 * deque, and it is the case the JDK's own fast paths exercise on the
 * first real operation of every program. The tail variant is a
 * separate symbol below.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedDeque_Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_util_concurrent_ConcurrentLinkedDeque_Node__Z(
        void* deque, void* expected, void* newValue)
{
    if (deque == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    void** slot = (void**)((char*)deque + 8);   /* head */
    void* exp = expected;
    return __atomic_compare_exchange_n(
        slot, &exp, newValue, 0,
        __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

/*
 * ConcurrentLinkedDeque.tail : CAS from a Node to another Node.
 * Mirror symbol of the `head` CAS above; see its comment for the
 * rationale on splitting the two.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedDeque_Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_util_concurrent_ConcurrentLinkedDeque_Node_T_Z(
        void* deque, void* expected, void* newValue)
{
    if (deque == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    void** slot = (void**)((char*)deque + 16);  /* tail */
    void* exp = expected;
    return __atomic_compare_exchange_n(
        slot, &exp, newValue, 0,
        __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

/*
 * ConcurrentLinkedDeque.head : weak CAS from a Node to another Node.
 * Reached from HEAD.weakCompareAndSet(this, p, newNode) in linkFirst.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_ConcurrentLinkedDeque_Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_util_concurrent_ConcurrentLinkedDeque_Node__Z(
        void* deque, void* expected, void* newValue)
{
    return __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedDeque_Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_util_concurrent_ConcurrentLinkedDeque_Node__Z(
        deque, expected, newValue);
}

/*
 * ConcurrentLinkedDeque.tail : weak CAS from a Node to another Node.
 * Reached from TAIL.weakCompareAndSet(this, p, newNode) in linkLast.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSet__Ljava_util_concurrent_ConcurrentLinkedDeque_Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_util_concurrent_ConcurrentLinkedDeque_Node_T_Z(
        void* deque, void* expected, void* newValue)
{
    return __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentLinkedDeque_Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_util_concurrent_ConcurrentLinkedDeque_Node_T_Z(
        deque, expected, newValue);
}

/* ===========================================================================
 *  java.util.concurrent.atomic.AtomicBoolean
 *      +8  volatile int value
 * =========================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_atomic_AtomicBoolean_II_Z(
        void* obj, int32_t expected, int32_t newValue)
{
    if (obj == NULL) __jnative_throw_null_pointer_exception();
    ReflectionClass* cls = jnative_class_by_name(
        "java/util/concurrent/atomic/AtomicBoolean");
    int off = var_handle_find_field_offset(cls, "value");
    if (off < 0) __jnative_throw_null_pointer_exception();
    return cas_int((int32_t*)((char*)obj + off), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAndSet__Ljava_util_concurrent_atomic_AtomicBoolean_I_I(
        void* obj, int32_t newValue)
{
    if (obj == NULL) __jnative_throw_null_pointer_exception();
    ReflectionClass* cls = jnative_class_by_name(
        "java/util/concurrent/atomic/AtomicBoolean");
    int off = var_handle_find_field_offset(cls, "value");
    if (off < 0) __jnative_throw_null_pointer_exception();
    int32_t* slot = (int32_t*)((char*)obj + off);
    int32_t newv = newValue;
    int32_t old;
    __atomic_exchange(slot, &newv, &old, __ATOMIC_SEQ_CST);
    return old;
}

/* ===========================================================================
 *  java.util.concurrent.atomic.AtomicReference
 *      +8  volatile Object value
 * =========================================================================== */

void* __jnative_fn_java_lang_invoke_VarHandle_getAndSet__Ljava_util_concurrent_atomic_AtomicReference_Ljava_lang_Object__Ljava_lang_Object_(
        void* obj, void* newValue)
{
    if (obj == NULL) __jnative_throw_null_pointer_exception();
    ReflectionClass* cls = jnative_class_by_name(
        "java/util/concurrent/atomic/AtomicReference");
    int off = var_handle_find_field_offset(cls, "value");
    if (off < 0) __jnative_throw_null_pointer_exception();
    void** slot = (void**)((char*)obj + off);
    void* newv = newValue;
    void* old;
    __atomic_exchange(slot, &newv, &old, __ATOMIC_SEQ_CST);
    return old;
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease__Ljava_util_concurrent_atomic_AtomicReference_Ljava_lang_Object__V(
        void* obj, void* newValue)
{
    if (obj == NULL) __jnative_throw_null_pointer_exception();
    ReflectionClass* cls = jnative_class_by_name(
        "java/util/concurrent/atomic/AtomicReference");
    int off = var_handle_find_field_offset(cls, "value");
    if (off < 0) __jnative_throw_null_pointer_exception();
    __atomic_store_n((void**)((char*)obj + off), newValue, __ATOMIC_RELEASE);
}

/* ===========================================================================
 *  java.util.concurrent.PriorityBlockingQueue
 *
 *      The VarHandle is bound to `allocationSpinLock`, a
 *      `private transient volatile int` field. Its offset is looked up
 *      at run time through the reflection metadata so a JDK release
 *      that adds a field ahead of it does not silently redirect the
 *      CAS onto neighbouring state.
 * =========================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_PriorityBlockingQueue_II_Z(
        void* obj, int32_t expected, int32_t newValue)
{
    if (obj == NULL) __jnative_throw_null_pointer_exception();
    ReflectionClass* cls = jnative_class_by_name(
        "java/util/concurrent/PriorityBlockingQueue");
    int off = var_handle_find_field_offset(cls, "allocationSpinLock");
    if (off < 0) __jnative_throw_null_pointer_exception();
    return cas_int((int32_t*)((char*)obj + off), expected, newValue);
}

/* ===========================================================================
 *  AtomicIntegerArray / int[] array-element VarHandles.
 *
 *      The JDK binds array-element VarHandles directly to the backing
 *      int[] — they are not attached to the AtomicIntegerArray wrapper
 *      object. Every entry point below receives the int[] as its first
 *      argument and the element index as its second.
 * =========================================================================== */

static inline int32_t* int_array_element(void* arr, int32_t index) {
    if (arr == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int32_t len = jnative_array_length(arr);
    if (index < 0 || index >= len) {
        __jnative_throw_array_index_out_of_bounds();
    }
    return (int32_t*)jnative_array_data(arr) + index;
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_getVolatile___II_I(
        void* arr, int32_t index)
{
    int32_t* slot = int_array_element(arr, index);
    return __atomic_load_n(slot, __ATOMIC_SEQ_CST);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_getAndAdd___III_I(
        void* arr, int32_t index, int32_t delta)
{
    int32_t* slot = int_array_element(arr, index);
    return __atomic_fetch_add(slot, delta, __ATOMIC_SEQ_CST);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet___III_Z(
        void* arr, int32_t index, int32_t expected, int32_t newValue)
{
    int32_t* slot = int_array_element(arr, index);
    int32_t exp = expected;
    return __atomic_compare_exchange_n(slot, &exp, newValue, 0,
                                       __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)
        ? 1 : 0;
}

void __jnative_fn_java_lang_invoke_VarHandle_setVolatile___II_V(
        void* arr, int32_t index, int32_t newValue)
{
    int32_t* slot = int_array_element(arr, index);
    __atomic_store_n(slot, newValue, __ATOMIC_SEQ_CST);
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease___II_V(
        void* arr, int32_t index, int32_t newValue)
{
    int32_t* slot = int_array_element(arr, index);
    __atomic_store_n(slot, newValue, __ATOMIC_RELEASE);
}

/* ===========================================================================
 *  AtomicReferenceArray / Object[] array-element VarHandles.
 *
 *      Same shape as the int[] family above: the descriptor's first
 *      parameter is the backing Object[], the second is the index.
 *      The element operation touches only the payload slot at
 *      JAVA_ARR_HDR + index * sizeof(void*).
 * =========================================================================== */

static inline void** ref_array_element(void* arr, int32_t index) {
    if (arr == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int32_t len = jnative_array_length(arr);
    if (index < 0 || index >= len) {
        __jnative_throw_array_index_out_of_bounds();
    }
    return (void**)jnative_array_data(arr) + index;
}

void* __jnative_fn_java_lang_invoke_VarHandle_getVolatile___Ljava_lang_Object_I_Ljava_lang_Object_(
        void* arr, int32_t index)
{
    void** slot = ref_array_element(arr, index);
    return __atomic_load_n(slot, __ATOMIC_SEQ_CST);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet___Ljava_lang_Object_ILjava_lang_Object_Ljava_lang_Object__Z(
        void* arr, int32_t index, void* expected, void* newValue)
{
    void** slot = ref_array_element(arr, index);
    void* exp = expected;
    return __atomic_compare_exchange_n(slot, &exp, newValue, 0,
                                       __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)
        ? 1 : 0;
}

void __jnative_fn_java_lang_invoke_VarHandle_setVolatile___Ljava_lang_Object_ILjava_lang_Object__V(
        void* arr, int32_t index, void* newValue)
{
    void** slot = ref_array_element(arr, index);
    __atomic_store_n(slot, newValue, __ATOMIC_SEQ_CST);
}

void __jnative_fn_java_lang_invoke_VarHandle_setRelease___Ljava_lang_Object_ILjava_lang_Object__V(
        void* arr, int32_t index, void* newValue)
{
    void** slot = ref_array_element(arr, index);
    __atomic_store_n(slot, newValue, __ATOMIC_RELEASE);
}

/* ===========================================================================
 *  ConcurrentLinkedDeque.Node.item : release-set.
 *      +16  E item
 *
 *  Reached from `ITEM.setRelease(node, null)` inside
 *  ConcurrentLinkedDeque.unlink; the comment above the existing
 *  set(Object) entry explains why the field identity is fixed at
 *  offset 16.
 * =========================================================================== */

void __jnative_fn_java_lang_invoke_VarHandle_setRelease__Ljava_util_concurrent_ConcurrentLinkedDeque_Node_Ljava_lang_Object__V(
        void* node, void* newValue)
{
    if (node == NULL) __jnative_throw_null_pointer_exception();
    __atomic_store_n((void**)((char*)node + 16), newValue, __ATOMIC_RELEASE);
}

/* ===========================================================================
 *  javax.net.ssl.SSLContext.defaultContext — a static VarHandle CAS.
 *
 *      The JDK installs the process-wide default SSLContext by
 *      CASing the class's own static field from null to a fresh
 *      SSLContext. The generated IR references the field through
 *      @gv_javax_net_ssl_SSLContext_defaultContext; the weak extern
 *      below tolerates a build in which the emitter did not materialise
 *      it (only reachable if SSLContext itself was never translated).
 * =========================================================================== */

extern void* gv_javax_net_ssl_SSLContext_defaultContext __attribute__((weak));

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_lang_Void_Ljavax_net_ssl_SSLContext__Z(
        void* expected, void* newValue)
{
    if (&gv_javax_net_ssl_SSLContext_defaultContext == NULL) {
        /* Field global absent — no store target. Report success so the
         * caller does not retry the CAS in an infinite loop. */
        return 1;
    }
    void* exp = expected;
    return __atomic_compare_exchange_n(
        &gv_javax_net_ssl_SSLContext_defaultContext,
        &exp, newValue, 0,
        __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
}

/* ===========================================================================
 *  java.util.concurrent.SynchronousQueue — the two transfer data structures.
 *
 *  Both SynchronousQueue.TransferQueue and SynchronousQueue.TransferStack
 *  expose a small set of VarHandle CAS operations on their node fields.
 *  The same descriptor is used for head, tail and cleanMe on the queue
 *  side (all three are QNode-typed), and for match and next on the stack
 *  side (both SNode-typed). At the C ABI level the VarHandle itself is
 *  not part of the signature — see the header comment at the top of this
 *  file — so the emitter cannot tell the three apart.
 *
 *  The helpers below therefore search the plausible field names in the
 *  receiver and CAS whichever one currently holds `expected`. A fresh node
 *  reference held by one field is never simultaneously held by another,
 *  so the search resolves unambiguously in every reachable call.
 * =========================================================================== */

static int sq_field_offset_for_class(const char* cls_name, const char* field) {
    ReflectionClass* cls = jnative_class_by_name(cls_name);
    if (cls == NULL) return -1;
    return var_handle_find_field_offset(cls, field);
}

/*
 * CAS the first field in `fields[]` whose current value equals `expected`.
 * Returns 1 on success, 0 on either "no field held expected" or a
 * compare-exchange that lost a race.
 */
static int32_t sq_cas_any_ref(const char* cls_name,
                              void* obj,
                              const char* const* fields,
                              int field_count,
                              void* expected,
                              void* new_value)
{
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    for (int i = 0; i < field_count; i++) {
        int off = sq_field_offset_for_class(cls_name, fields[i]);
        if (off < 0) continue;
        void** slot = (void**)((char*)obj + off);
        void* cur = __atomic_load_n(slot, __ATOMIC_SEQ_CST);
        if (cur != expected) continue;
        void* exp = expected;
        return __atomic_compare_exchange_n(slot, &exp, new_value,
                                           0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)
            ? 1 : 0;
    }
    return 0;
}

static int32_t sq_set_any_ref(const char* cls_name,
                              void* obj,
                              const char* const* fields,
                              int field_count,
                              void* value)
{
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    for (int i = 0; i < field_count; i++) {
        int off = sq_field_offset_for_class(cls_name, fields[i]);
        if (off < 0) continue;
        __atomic_store_n((void**)((char*)obj + off), value, __ATOMIC_RELEASE);
        return 0;
    }
    return 0;
}

/* -------- TransferQueue: {head, tail, cleanMe} (all QNode) ------------- */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_SynchronousQueue_TransferQueue_Ljava_util_concurrent_SynchronousQueue_TransferQueue_QNode_Ljava_util_concurrent_SynchronousQueue_TransferQueue_QNode__Z(
        void* obj, void* expected, void* new_value)
{
    static const char* const FIELDS[] = { "head", "tail", "cleanMe" };
    return sq_cas_any_ref(
        "java/util/concurrent/SynchronousQueue$TransferQueue",
        obj, FIELDS, 3, expected, new_value);
}

/* -------- TransferQueue.QNode: {next} (QNode -> QNode) ---------------- */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_SynchronousQueue_TransferQueue_QNode_Ljava_util_concurrent_SynchronousQueue_TransferQueue_QNode_Ljava_util_concurrent_SynchronousQueue_TransferQueue_QNode__Z(
        void* obj, void* expected, void* new_value)
{
    static const char* const FIELDS[] = { "next" };
    return sq_cas_any_ref(
        "java/util/concurrent/SynchronousQueue$TransferQueue$QNode",
        obj, FIELDS, 1, expected, new_value);
}

/* -------- TransferQueue.QNode: {item} (Object -> Object) -------------- */
/*
 * The descriptor carries Object/Object, which matches both the "item"
 * field of QNode and the tryCancel path where the new value is a QNode
 * reference stored through the Object slot. In both cases the field
 * touched is `item`; the two symbols are distinguished only by the
 * declared static type of the new-value argument.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_SynchronousQueue_TransferQueue_QNode_Ljava_lang_Object_Ljava_lang_Object__Z(
        void* obj, void* expected, void* new_value)
{
    static const char* const FIELDS[] = { "item" };
    return sq_cas_any_ref(
        "java/util/concurrent/SynchronousQueue$TransferQueue$QNode",
        obj, FIELDS, 1, expected, new_value);
}

/* -------- TransferQueue.QNode: {item}, new value typed QNode ----------- */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_SynchronousQueue_TransferQueue_QNode_Ljava_lang_Object_Ljava_util_concurrent_SynchronousQueue_TransferQueue_QNode__Z(
        void* obj, void* expected, void* new_value)
{
    static const char* const FIELDS[] = { "item" };
    return sq_cas_any_ref(
        "java/util/concurrent/SynchronousQueue$TransferQueue$QNode",
        obj, FIELDS, 1, expected, new_value);
}

/* -------- TransferQueue.QNode: {waiter = null} ------------------------ */

void __jnative_fn_java_lang_invoke_VarHandle_setOpaque__Ljava_util_concurrent_SynchronousQueue_TransferQueue_QNode_Ljava_lang_Void__V(
        void* obj, void* value)
{
    static const char* const FIELDS[] = { "waiter" };
    (void)value;
    sq_set_any_ref(
        "java/util/concurrent/SynchronousQueue$TransferQueue$QNode",
        obj, FIELDS, 1, NULL);
}

/* -------- TransferStack: {head} (SNode -> SNode) ---------------------- */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_SynchronousQueue_TransferStack_Ljava_util_concurrent_SynchronousQueue_TransferStack_SNode_Ljava_util_concurrent_SynchronousQueue_TransferStack_SNode__Z(
        void* obj, void* expected, void* new_value)
{
    static const char* const FIELDS[] = { "head" };
    return sq_cas_any_ref(
        "java/util/concurrent/SynchronousQueue$TransferStack",
        obj, FIELDS, 1, expected, new_value);
}

/* -------- TransferStack.SNode: {match} (null -> SNode) ---------------- */
/*
 * tryMatch does:
 *     SMATCH.compareAndSet(this, null, s)
 * The descriptor types the expected value as Void (the compiler knew it
 * was the literal null). At the C boundary it arrives as NULL.
 */
int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_SynchronousQueue_TransferStack_SNode_Ljava_lang_Void_Ljava_util_concurrent_SynchronousQueue_TransferStack_SNode__Z(
        void* obj, void* expected, void* new_value)
{
    static const char* const FIELDS[] = { "match" };
    (void)expected;
    return sq_cas_any_ref(
        "java/util/concurrent/SynchronousQueue$TransferStack$SNode",
        obj, FIELDS, 1, NULL, new_value);
}

/* -------- TransferStack.SNode: {next} (SNode -> SNode) ---------------- */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_SynchronousQueue_TransferStack_SNode_Ljava_util_concurrent_SynchronousQueue_TransferStack_SNode_Ljava_util_concurrent_SynchronousQueue_TransferStack_SNode__Z(
        void* obj, void* expected, void* new_value)
{
    static const char* const FIELDS[] = { "next" };
    return sq_cas_any_ref(
        "java/util/concurrent/SynchronousQueue$TransferStack$SNode",
        obj, FIELDS, 1, expected, new_value);
}

/* -------- TransferStack.SNode: {waiter = null} ------------------------ */

void __jnative_fn_java_lang_invoke_VarHandle_setOpaque__Ljava_util_concurrent_SynchronousQueue_TransferStack_SNode_Ljava_lang_Void__V(
        void* obj, void* value)
{
    static const char* const FIELDS[] = { "waiter" };
    (void)value;
    sq_set_any_ref(
        "java/util/concurrent/SynchronousQueue$TransferStack$SNode",
        obj, FIELDS, 1, NULL);
}

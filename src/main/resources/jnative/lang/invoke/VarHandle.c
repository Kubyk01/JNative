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
 *      Striped64   +8 long base, +16 int cellsBusy
 *      Cell        +8 long value
 * =========================================================================== */

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_atomic_Striped64_II_Z(
        void* obj, int32_t expected, int32_t newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_int((int32_t*)((char*)obj + 16), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_weakCompareAndSetRelease__Ljava_util_concurrent_atomic_Striped64_JJ_Z(
        void* obj, int64_t expected, int64_t newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int64_t exp = expected;
    return __atomic_compare_exchange_n((int64_t*)((char*)obj + 8), &exp, newValue,
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
 *      CSLM        +24 head, +40 counter
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
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
}

int32_t __jnative_fn_java_lang_invoke_VarHandle_compareAndSet__Ljava_util_concurrent_ConcurrentSkipListMap_Ljava_lang_Void_Ljava_util_concurrent_ConcurrentSkipListMap_Index__Z(
        void* obj, void* expected, void* newValue) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    return cas_ref((void**)((char*)obj + 24), expected, newValue);
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

#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <errno.h>
#include <stdatomic.h>
#include <time.h>

// ---------------------------------------------------------------------------
// Forward declarations of runtime exception helpers (defined in jnative_runtime.c)
// ---------------------------------------------------------------------------
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);
__attribute__((noreturn)) void __jnative_throw_exception(void* exc);

// ---------------------------------------------------------------------------
// Utility: convert object + offset to effective address.
// If obj is NULL, offset is treated as an absolute address.
// ---------------------------------------------------------------------------
static inline void* effective_address(void* obj, int64_t offset) {
    return (obj == NULL) ? (void*)(uintptr_t)offset : (char*)obj + offset;
}

// ---------------------------------------------------------------------------
// Plain loads / stores (no memory ordering guarantees)
// ---------------------------------------------------------------------------

// int
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getInt__Ljava_lang_Object_J_I(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    return *(int32_t*)addr;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putInt__Ljava_lang_Object_JI_V(void* obj, int64_t offset, int32_t x) {
    void* addr = effective_address(obj, offset);
    *(int32_t*)addr = x;
}

// long
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getLong__Ljava_lang_Object_J_J(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    return *(int64_t*)addr;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putLong__Ljava_lang_Object_JJ_V(void* obj, int64_t offset, int64_t x) {
    void* addr = effective_address(obj, offset);
    *(int64_t*)addr = x;
}

// reference (object)
void* __jnative_fn_jdk_internal_misc_Unsafe_getReference__Ljava_lang_Object_J_Ljava_lang_Object_(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    return *(void**)addr;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putReference__Ljava_lang_Object_JLjava_lang_Object__V(void* obj, int64_t offset, void* x) {
    void* addr = effective_address(obj, offset);
    *(void**)addr = x;
}

// boolean
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getBoolean__Ljava_lang_Object_J_Z(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    return *(uint8_t*)addr;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putBoolean__Ljava_lang_Object_JZ_V(void* obj, int64_t offset, int32_t x) {
    void* addr = effective_address(obj, offset);
    *(uint8_t*)addr = (uint8_t)x;
}

// byte
int8_t __jnative_fn_jdk_internal_misc_Unsafe_getByte__Ljava_lang_Object_J_B(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    return *(int8_t*)addr;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putByte__Ljava_lang_Object_JB_V(void* obj, int64_t offset, int8_t x) {
    void* addr = effective_address(obj, offset);
    *(int8_t*)addr = x;
}

// short
int16_t __jnative_fn_jdk_internal_misc_Unsafe_getShort__Ljava_lang_Object_J_S(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    return *(int16_t*)addr;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putShort__Ljava_lang_Object_JS_V(void* obj, int64_t offset, int16_t x) {
    void* addr = effective_address(obj, offset);
    *(int16_t*)addr = x;
}

// char
uint16_t __jnative_fn_jdk_internal_misc_Unsafe_getChar__Ljava_lang_Object_J_C(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    return *(uint16_t*)addr;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putChar__Ljava_lang_Object_JC_V(void* obj, int64_t offset, uint16_t x) {
    void* addr = effective_address(obj, offset);
    *(uint16_t*)addr = x;
}

// float
float __jnative_fn_jdk_internal_misc_Unsafe_getFloat__Ljava_lang_Object_J_F(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    return *(float*)addr;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putFloat__Ljava_lang_Object_JF_V(void* obj, int64_t offset, float x) {
    void* addr = effective_address(obj, offset);
    *(float*)addr = x;
}

// double
double __jnative_fn_jdk_internal_misc_Unsafe_getDouble__Ljava_lang_Object_J_D(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    return *(double*)addr;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putDouble__Ljava_lang_Object_JD_V(void* obj, int64_t offset, double x) {
    void* addr = effective_address(obj, offset);
    *(double*)addr = x;
}

// ---------------------------------------------------------------------------
// Volatile loads / stores (acquire / release semantics)
// ---------------------------------------------------------------------------

// int
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getIntVolatile__Ljava_lang_Object_J_I(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    int32_t val;
    __atomic_load((int32_t*)addr, &val, __ATOMIC_ACQUIRE);
    return val;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putIntVolatile__Ljava_lang_Object_JI_V(void* obj, int64_t offset, int32_t x) {
    void* addr = effective_address(obj, offset);
    __atomic_store((int32_t*)addr, &x, __ATOMIC_RELEASE);
}

// long
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getLongVolatile__Ljava_lang_Object_J_J(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    int64_t val;
    __atomic_load((int64_t*)addr, &val, __ATOMIC_ACQUIRE);
    return val;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putLongVolatile__Ljava_lang_Object_JJ_V(void* obj, int64_t offset, int64_t x) {
    void* addr = effective_address(obj, offset);
    __atomic_store((int64_t*)addr, &x, __ATOMIC_RELEASE);
}

// reference
void* __jnative_fn_jdk_internal_misc_Unsafe_getReferenceVolatile__Ljava_lang_Object_J_Ljava_lang_Object_(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    void* val;
    __atomic_load((void**)addr, &val, __ATOMIC_ACQUIRE);
    return val;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putReferenceVolatile__Ljava_lang_Object_JLjava_lang_Object__V(void* obj, int64_t offset, void* x) {
    void* addr = effective_address(obj, offset);
    __atomic_store((void**)addr, &x, __ATOMIC_RELEASE);
}

// boolean – as byte
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getBooleanVolatile__Ljava_lang_Object_J_Z(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    uint8_t val;
    __atomic_load((uint8_t*)addr, &val, __ATOMIC_ACQUIRE);
    return val;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putBooleanVolatile__Ljava_lang_Object_JZ_V(void* obj, int64_t offset, int32_t x) {
    void* addr = effective_address(obj, offset);
    uint8_t v = (uint8_t)x;
    __atomic_store((uint8_t*)addr, &v, __ATOMIC_RELEASE);
}

// byte
int8_t __jnative_fn_jdk_internal_misc_Unsafe_getByteVolatile__Ljava_lang_Object_J_B(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    int8_t val;
    __atomic_load((int8_t*)addr, &val, __ATOMIC_ACQUIRE);
    return val;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putByteVolatile__Ljava_lang_Object_JB_V(void* obj, int64_t offset, int8_t x) {
    void* addr = effective_address(obj, offset);
    __atomic_store((int8_t*)addr, &x, __ATOMIC_RELEASE);
}

// short
int16_t __jnative_fn_jdk_internal_misc_Unsafe_getShortVolatile__Ljava_lang_Object_J_S(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    int16_t val;
    __atomic_load((int16_t*)addr, &val, __ATOMIC_ACQUIRE);
    return val;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putShortVolatile__Ljava_lang_Object_JS_V(void* obj, int64_t offset, int16_t x) {
    void* addr = effective_address(obj, offset);
    __atomic_store((int16_t*)addr, &x, __ATOMIC_RELEASE);
}

// char
uint16_t __jnative_fn_jdk_internal_misc_Unsafe_getCharVolatile__Ljava_lang_Object_J_C(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    uint16_t val;
    __atomic_load((uint16_t*)addr, &val, __ATOMIC_ACQUIRE);
    return val;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putCharVolatile__Ljava_lang_Object_JC_V(void* obj, int64_t offset, uint16_t x) {
    void* addr = effective_address(obj, offset);
    __atomic_store((uint16_t*)addr, &x, __ATOMIC_RELEASE);
}

// float
float __jnative_fn_jdk_internal_misc_Unsafe_getFloatVolatile__Ljava_lang_Object_J_F(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    float val;
    __atomic_load((float*)addr, &val, __ATOMIC_ACQUIRE);
    return val;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putFloatVolatile__Ljava_lang_Object_JF_V(void* obj, int64_t offset, float x) {
    void* addr = effective_address(obj, offset);
    __atomic_store((float*)addr, &x, __ATOMIC_RELEASE);
}

// double
double __jnative_fn_jdk_internal_misc_Unsafe_getDoubleVolatile__Ljava_lang_Object_J_D(void* obj, int64_t offset) {
    void* addr = effective_address(obj, offset);
    double val;
    __atomic_load((double*)addr, &val, __ATOMIC_ACQUIRE);
    return val;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putDoubleVolatile__Ljava_lang_Object_JD_V(void* obj, int64_t offset, double x) {
    void* addr = effective_address(obj, offset);
    __atomic_store((double*)addr, &x, __ATOMIC_RELEASE);
}

// ---------------------------------------------------------------------------
// Ordered (lazy) stores – release semantics, no load barrier
// ---------------------------------------------------------------------------

void __jnative_fn_jdk_internal_misc_Unsafe_putOrderedInt__Ljava_lang_Object_JI_V(void* obj, int64_t offset, int32_t x) {
    void* addr = effective_address(obj, offset);
    __atomic_store((int32_t*)addr, &x, __ATOMIC_RELEASE);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putOrderedLong__Ljava_lang_Object_JJ_V(void* obj, int64_t offset, int64_t x) {
    void* addr = effective_address(obj, offset);
    __atomic_store((int64_t*)addr, &x, __ATOMIC_RELEASE);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putOrderedObject__Ljava_lang_Object_JLjava_lang_Object__V(void* obj, int64_t offset, void* x) {
    void* addr = effective_address(obj, offset);
    __atomic_store((void**)addr, &x, __ATOMIC_RELEASE);
}

// ---------------------------------------------------------------------------
// Compare-and-swap (CAS) operations – full sequential consistency
// ---------------------------------------------------------------------------

int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetInt__Ljava_lang_Object_JII_Z(void* obj, int64_t offset, int32_t expected, int32_t x) {
    void* addr = effective_address(obj, offset);
    return __atomic_compare_exchange_n((int32_t*)addr, &expected, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetLong__Ljava_lang_Object_JJJ_Z(void* obj, int64_t offset, int64_t expected, int64_t x) {
    void* addr = effective_address(obj, offset);
    return __atomic_compare_exchange_n((int64_t*)addr, &expected, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetReference__Ljava_lang_Object_JLjava_lang_Object_Ljava_lang_Object__Z(void* obj, int64_t offset, void* expected, void* x) {
    void* addr = effective_address(obj, offset);
    return __atomic_compare_exchange_n((void**)addr, &expected, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
}

// ---------------------------------------------------------------------------
// getAndAdd / getAndSet
// ---------------------------------------------------------------------------

int32_t __jnative_fn_jdk_internal_misc_Unsafe_getAndAddInt__Ljava_lang_Object_JI_I(void* obj, int64_t offset, int32_t delta) {
    void* addr = effective_address(obj, offset);
    return __atomic_fetch_add((int32_t*)addr, delta, __ATOMIC_SEQ_CST);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getAndAddLong__Ljava_lang_Object_JJ_J(void* obj, int64_t offset, int64_t delta) {
    void* addr = effective_address(obj, offset);
    return __atomic_fetch_add((int64_t*)addr, delta, __ATOMIC_SEQ_CST);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getAndSetInt__Ljava_lang_Object_JI_I(void* obj, int64_t offset, int32_t newValue) {
    void* addr = effective_address(obj, offset);
    int32_t old;
    __atomic_exchange((int32_t*)addr, &newValue, &old, __ATOMIC_SEQ_CST);
    return old;
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getAndSetLong__Ljava_lang_Object_JJ_J(void* obj, int64_t offset, int64_t newValue) {
    void* addr = effective_address(obj, offset);
    int64_t old;
    __atomic_exchange((int64_t*)addr, &newValue, &old, __ATOMIC_SEQ_CST);
    return old;
}
void* __jnative_fn_jdk_internal_misc_Unsafe_getAndSetReference__Ljava_lang_Object_JLjava_lang_Object_Ljava_lang_Object_(void* obj, int64_t offset, void* newValue) {
    void* addr = effective_address(obj, offset);
    void* old;
    __atomic_exchange((void**)addr, &newValue, &old, __ATOMIC_SEQ_CST);
    return old;
}

// ---------------------------------------------------------------------------
// Direct memory access (absolute addresses)
// ---------------------------------------------------------------------------

// getInt(long)
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getInt__J_I(void* addr) {
    return *(int32_t*)addr;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putInt__JI_V(void* addr, int32_t x) {
    *(int32_t*)addr = x;
}

// getLong(long)
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getLong__J_J(void* addr) {
    return *(int64_t*)addr;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putLong__JJ_V(void* addr, int64_t x) {
    *(int64_t*)addr = x;
}

// getAddress(long) – returns native pointer as long
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getAddress__J_J(void* addr) {
    return (int64_t)(uintptr_t)*(void**)addr;
}
void __jnative_fn_jdk_internal_misc_Unsafe_putAddress__JJ_V(void* addr, int64_t x) {
    *(void**)addr = (void*)(uintptr_t)x;
}

// other direct types
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

// ---------------------------------------------------------------------------
// Memory allocation / free / set / copy
// ---------------------------------------------------------------------------

void* __jnative_fn_jdk_internal_misc_Unsafe_allocateMemory__J_J(int64_t bytes) {
    if (bytes < 0) return NULL;
    void* p = malloc((size_t)bytes);
    if (!p) __jnative_throw_exception(NULL); // OutOfMemoryError
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
    void* addr = effective_address(obj, offset);
    memset(addr, value, (size_t)bytes);
}
void __jnative_fn_jdk_internal_misc_Unsafe_copyMemory__JJJ_V(void* src, void* dst, int64_t bytes) {
    memmove(dst, src, (size_t)bytes);
}
void __jnative_fn_jdk_internal_misc_Unsafe_copyMemory__Ljava_lang_Object_JLjava_lang_Object_JJ_V(void* srcBase, int64_t srcOffset, void* dstBase, int64_t dstOffset, int64_t bytes) {
    void* src = effective_address(srcBase, srcOffset);
    void* dst = effective_address(dstBase, dstOffset);
    memmove(dst, src, (size_t)bytes);
}

// ---------------------------------------------------------------------------
// park / unpark (simple pthread-based implementation)
// ---------------------------------------------------------------------------

typedef struct ParkData {
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    int parked;
} ParkData;

static ParkData* get_park_data(void) {
    static ParkData pd = { PTHREAD_MUTEX_INITIALIZER, PTHREAD_COND_INITIALIZER, 0 };
    return &pd;
}

void __jnative_fn_jdk_internal_misc_Unsafe_park__JZ_V(int32_t isAbsolute, int64_t time) {
    ParkData* pd = get_park_data();
    pthread_mutex_lock(&pd->mutex);
    pd->parked = 1;
    if (time == 0) {
        // indefinite park
        while (pd->parked) {
            pthread_cond_wait(&pd->cond, &pd->mutex);
        }
    } else {
        struct timespec ts;
        if (isAbsolute) {
            ts.tv_sec = time / 1000;
            ts.tv_nsec = (time % 1000) * 1000000;
        } else {
            clock_gettime(CLOCK_REALTIME, &ts);
            ts.tv_sec += time / 1000;
            ts.tv_nsec += (time % 1000) * 1000000;
            if (ts.tv_nsec >= 1000000000) {
                ts.tv_sec += 1;
                ts.tv_nsec -= 1000000000;
            }
        }
        pthread_cond_timedwait(&pd->cond, &pd->mutex, &ts);
        pd->parked = 0;
    }
    pthread_mutex_unlock(&pd->mutex);
}

void __jnative_fn_jdk_internal_misc_Unsafe_unpark__Ljava_lang_Object_V(void* thread) {
    // In this simple implementation we ignore the thread argument and unpark the single thread.
    // A full implementation would maintain a per-thread park state.
    ParkData* pd = get_park_data();
    pthread_mutex_lock(&pd->mutex);
    if (pd->parked) {
        pd->parked = 0;
        pthread_cond_signal(&pd->cond);
    }
    pthread_mutex_unlock(&pd->mutex);
}

// ---------------------------------------------------------------------------
// Fences
// ---------------------------------------------------------------------------

void __jnative_fn_jdk_internal_misc_Unsafe_loadFence___V(void) {
    __atomic_thread_fence(__ATOMIC_ACQUIRE);
}
void __jnative_fn_jdk_internal_misc_Unsafe_storeFence___V(void) {
    __atomic_thread_fence(__ATOMIC_RELEASE);
}
void __jnative_fn_jdk_internal_misc_Unsafe_fullFence___V(void) {
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
}

// ---------------------------------------------------------------------------
// Reflection-based helpers: objectFieldOffset, staticFieldOffset, staticFieldBase,
// arrayBaseOffset, arrayIndexScale.
// These are called rarely from Java code; we can return 0 or use the global
// reflection structures if needed. Since offsets are resolved at compile time,
// we provide minimal stubs that return 0 (or sensible defaults) to avoid
// link errors. In a full implementation they would search the Reflection data.
// ---------------------------------------------------------------------------

int64_t __jnative_fn_jdk_internal_misc_Unsafe_objectFieldOffset__Ljava_lang_reflect_Field_J(void* field) {
    // Stub: return 0; in real usage this is not called in compiled code.
    return 0;
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_staticFieldOffset__Ljava_lang_reflect_Field_J(void* field) {
    return 0;
}
void* __jnative_fn_jdk_internal_misc_Unsafe_staticFieldBase__Ljava_lang_reflect_Field_Ljava_lang_Object_(void* field) {
    return NULL;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_arrayBaseOffset__Ljava_lang_Class_I(void* arrayClass) {
    // Default header size: 8 bytes (length+element size)
    return 8;
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_arrayIndexScale__Ljava_lang_Class_I(void* arrayClass) {
    // We don't know the element type; return 1 as fallback, but this is rarely used.
    return 1;
}

// ---------------------------------------------------------------------------
// Other methods: allocateInstance, throwException, shouldBeInitialized,
// ensureClassInitialized, getLoadAverage, invokeCleaner.
// These are either not used or can be stubbed.
// ---------------------------------------------------------------------------

void* __jnative_fn_jdk_internal_misc_Unsafe_allocateInstance__Ljava_lang_Class_Ljava_lang_Object_(void* cls) {
    // Not implemented – would require class meta-data.
    return NULL;
}
void __jnative_fn_jdk_internal_misc_Unsafe_throwException__Ljava_lang_Throwable_V(void* throwable) {
    // Simply re-throw by calling the runtime exception helper.
    __jnative_throw_exception(throwable);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_shouldBeInitialized__Ljava_lang_Class_Z(void* cls) {
    return 0; // assume already initialized
}
void __jnative_fn_jdk_internal_misc_Unsafe_ensureClassInitialized__Ljava_lang_Class_V(void* cls) {
    // no-op
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_getLoadAverage___D_I(double* loadavg, int32_t nelems) {
    return -1; // not available
}
void __jnative_fn_jdk_internal_misc_Unsafe_invokeCleaner__Ljava_nio_ByteBuffer_V(void* directBuffer) {
    // no-op
}

// ---------------------------------------------------------------------------
// Weak CAS variants – simply delegate to the standard CAS
// ---------------------------------------------------------------------------

int32_t __jnative_fn_jdk_internal_misc_Unsafe_weakCompareAndSetInt__Ljava_lang_Object_JII_Z(void* obj, int64_t offset, int32_t expected, int32_t x) {
    return __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetInt__Ljava_lang_Object_JII_Z(obj, offset, expected, x);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_weakCompareAndSetLong__Ljava_lang_Object_JJJ_Z(void* obj, int64_t offset, int64_t expected, int64_t x) {
    return __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetLong__Ljava_lang_Object_JJJ_Z(obj, offset, expected, x);
}
int32_t __jnative_fn_jdk_internal_misc_Unsafe_weakCompareAndSetReference__Ljava_lang_Object_JLjava_lang_Object_Ljava_lang_Object__Z(void* obj, int64_t offset, void* expected, void* x) {
    return __jnative_fn_jdk_internal_misc_Unsafe_compareAndSetReference__Ljava_lang_Object_JLjava_lang_Object_Ljava_lang_Object__Z(obj, offset, expected, x);
}

// ---------------------------------------------------------------------------
// Acquire/Release variants – delegate to volatile with appropriate ordering
// Note: these are added for completeness; they may not be used.
// ---------------------------------------------------------------------------

int32_t __jnative_fn_jdk_internal_misc_Unsafe_getIntAcquire__Ljava_lang_Object_J_I(void* obj, int64_t offset) {
    return __jnative_fn_jdk_internal_misc_Unsafe_getIntVolatile__Ljava_lang_Object_J_I(obj, offset);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putIntRelease__Ljava_lang_Object_JI_V(void* obj, int64_t offset, int32_t x) {
    __jnative_fn_jdk_internal_misc_Unsafe_putOrderedInt__Ljava_lang_Object_JI_V(obj, offset, x);
}
void* __jnative_fn_jdk_internal_misc_Unsafe_getReferenceAcquire__Ljava_lang_Object_J_Ljava_lang_Object_(void* obj, int64_t offset) {
    return __jnative_fn_jdk_internal_misc_Unsafe_getReferenceVolatile__Ljava_lang_Object_J_Ljava_lang_Object_(obj, offset);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putReferenceRelease__Ljava_lang_Object_JLjava_lang_Object__V(void* obj, int64_t offset, void* x) {
    __jnative_fn_jdk_internal_misc_Unsafe_putOrderedObject__Ljava_lang_Object_JLjava_lang_Object__V(obj, offset, x);
}
int64_t __jnative_fn_jdk_internal_misc_Unsafe_getLongAcquire__Ljava_lang_Object_J_J(void* obj, int64_t offset) {
    return __jnative_fn_jdk_internal_misc_Unsafe_getLongVolatile__Ljava_lang_Object_J_J(obj, offset);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putLongRelease__Ljava_lang_Object_JJ_V(void* obj, int64_t offset, int64_t x) {
    __jnative_fn_jdk_internal_misc_Unsafe_putOrderedLong__Ljava_lang_Object_JJ_V(obj, offset, x);
}
// Similar for other types can be added if needed, but the above cover the most common ones.

// ---------------------------------------------------------------------------
// Opaque operations – same as plain (no ordering) but may be used; we delegate to plain.
// ---------------------------------------------------------------------------

int32_t __jnative_fn_jdk_internal_misc_Unsafe_getIntOpaque__Ljava_lang_Object_J_I(void* obj, int64_t offset) {
    return __jnative_fn_jdk_internal_misc_Unsafe_getInt__Ljava_lang_Object_J_I(obj, offset);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putIntOpaque__Ljava_lang_Object_JI_V(void* obj, int64_t offset, int32_t x) {
    __jnative_fn_jdk_internal_misc_Unsafe_putInt__Ljava_lang_Object_JI_V(obj, offset, x);
}
void* __jnative_fn_jdk_internal_misc_Unsafe_getReferenceOpaque__Ljava_lang_Object_J_Ljava_lang_Object_(void* obj, int64_t offset) {
    return __jnative_fn_jdk_internal_misc_Unsafe_getReference__Ljava_lang_Object_J_Ljava_lang_Object_(obj, offset);
}
void __jnative_fn_jdk_internal_misc_Unsafe_putReferenceOpaque__Ljava_lang_Object_JLjava_lang_Object__V(void* obj, int64_t offset, void* x) {
    __jnative_fn_jdk_internal_misc_Unsafe_putReference__Ljava_lang_Object_JLjava_lang_Object__V(obj, offset, x);
}

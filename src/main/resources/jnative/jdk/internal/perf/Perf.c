/*
 * jdk.internal.perf.Perf -- the native side of the JDK's internal
 * counter subsystem (JPerf).
 *
 * ==========================================================================
 * Why this file exists
 * ==========================================================================
 *
 * PerfCounter and friends dereference the ByteBuffer returned by
 * create* unconditionally, on hot paths of ClassLoader and ZipFile.
 * A NULL return means NullPointerException inside `bb.order(...)`,
 * not a graceful "no counters available" state.
 *
 * The implementation here therefore returns a real HeapByteBuffer for
 * every create* call, allocated through java.nio.ByteBuffer.allocate
 * by mangled symbol. That symbol must be present in the compiled
 * image; Orchestrator.forcePerfMethods() forces it into the
 * reachability set before the analysis snapshot is taken.
 *
 * ==========================================================================
 * Platform portability
 * ==========================================================================
 *
 * Every primitive in this file is portable:
 *
 *   - jn_now_monotonic / jn_clock_* are provided by jnative_platform.h
 *     (clock_gettime on POSIX, QueryPerformanceCounter on Windows);
 *   - jn_dlsym_default uses dlsym(RTLD_DEFAULT, ...) on POSIX and
 *     GetProcAddress(GetModuleHandleA(NULL), ...) on Windows;
 *   - the Counter frequency is 1e9 Hz on both platforms because the
 *     emitted highResCounter() returns nanoseconds since an epoch and
 *     is therefore always ticking at exactly one tick per nanosecond.
 *
 * The previous revision called clock_getres(CLOCK_MONOTONIC, ...) to
 * derive a "real" frequency from the clock resolution. That call does
 * not exist on Windows, and it is also wrong on POSIX: highResCounter()
 * returns nanoseconds, so the frequency must be 1e9 regardless of the
 * underlying clock's hardware resolution. See highResFrequency() below.
 */

#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "jnative_runtime.h"

/* =========================================================================
 *  Symbols the C side calls through dlsym
 * ========================================================================= */

#define PERF_SYM_BB_ALLOCATE \
    "fn_java_nio_ByteBuffer_allocate__I_Ljava_nio_ByteBuffer_"
#define PERF_SYM_BB_PUT_BYTES \
    "fn_java_nio_ByteBuffer_put___B_Ljava_nio_ByteBuffer_"
#define PERF_SYM_HBB_PUT_LONG \
    "fn_java_nio_HeapByteBuffer_putLong__IJ_Ljava_nio_ByteBuffer_"

static void* perf_dlsym(const char* symbol) {
    return jn_dlsym_default(symbol);
}

/* =========================================================================
 *  Buffer allocation helpers
 * ========================================================================= */

static void* perf_allocate_byte_buffer(int32_t capacity) {
    if (capacity < 0) capacity = 0;

    typedef void* (*allocate_fn_t)(int32_t);
    allocate_fn_t fn = (allocate_fn_t)perf_dlsym(PERF_SYM_BB_ALLOCATE);

    if (fn == NULL) {
        __jnative_throw_out_of_memory_error_ctx(
            "jdk.internal.perf.Perf.create*: "
            "java.nio.ByteBuffer.allocate is not present in the compiled "
            "image. Orchestrator.forcePerfMethods() must force it into the "
            "reachability set before the analysis snapshot.");
    }

    void* bb = fn(capacity);
    if (bb == NULL) {
        __jnative_throw_out_of_memory_error_ctx(
            "jdk.internal.perf.Perf.create*: "
            "java.nio.ByteBuffer.allocate returned null");
    }
    return bb;
}

static void perf_write_initial_long(void* bb, int64_t value) {
    if (bb == NULL) return;
    if (value == 0) return;

    typedef void* (*put_long_fn_t)(void*, int32_t, int64_t);
    put_long_fn_t fn = (put_long_fn_t)perf_dlsym(PERF_SYM_HBB_PUT_LONG);

    if (fn == NULL) {
        static int warned = 0;
        if (!warned) {
            warned = 1;
            fprintf(stderr,
                "jnative: warning: %s is not in the compiled image; "
                "a non-zero initial value of a Perf long counter will be "
                "dropped. Orchestrator.forcePerfMethods() should force it "
                "into the reachability set.\n",
                PERF_SYM_HBB_PUT_LONG);
        }
        return;
    }

    (void)fn(bb, 0, value);
}

static void perf_write_initial_bytes(void* bb, void* bytes, int32_t max_len) {
    if (bb == NULL || bytes == NULL || max_len <= 0) return;

    int32_t len = jnative_array_length(bytes);
    if (len <= 0) return;
    if (len > max_len) return;

    typedef void* (*put_bytes_fn_t)(void*, void*);
    put_bytes_fn_t fn = (put_bytes_fn_t)perf_dlsym(PERF_SYM_BB_PUT_BYTES);

    if (fn == NULL) {
        static int warned = 0;
        if (!warned) {
            warned = 1;
            fprintf(stderr,
                "jnative: warning: %s is not in the compiled image; "
                "Perf.createByteArray will skip the initial-value copy. "
                "Orchestrator.forcePerfMethods() should force it.\n",
                PERF_SYM_BB_PUT_BYTES);
        }
        return;
    }

    (void)fn(bb, bytes);
}

/* =========================================================================
 *  Public natives
 * ========================================================================= */

void* __jnative_fn_jdk_internal_perf_Perf_createLong__Ljava_lang_String_IIJ_Ljava_nio_ByteBuffer_(
        void* name, int32_t variability, int32_t units, int64_t value)
{
    (void)name; (void)variability; (void)units;
    void* bb = perf_allocate_byte_buffer(8);
    perf_write_initial_long(bb, value);
    return bb;
}

void* __jnative_fn_jdk_internal_perf_Perf_createByteArray__Ljava_lang_String_III_Ljava_nio_ByteBuffer_(
        void* name, int32_t variability, int32_t units, int32_t size)
{
    (void)name; (void)variability; (void)units;
    if (size < 0) size = 0;
    return perf_allocate_byte_buffer(size);
}

void* __jnative_fn_jdk_internal_perf_Perf_createByteArray__Ljava_lang_String_II_BI_Ljava_nio_ByteBuffer_(
        void* name, int32_t variability, int32_t units,
        void* value, int32_t maxLength)
{
    (void)name; (void)variability; (void)units;
    if (maxLength < 0) maxLength = 0;
    void* bb = perf_allocate_byte_buffer(maxLength);
    perf_write_initial_bytes(bb, value, maxLength);
    return bb;
}

void* __jnative_fn_jdk_internal_perf_Perf_createString__Ljava_lang_String_III_Ljava_nio_ByteBuffer_(
        void* name, int32_t variability, int32_t units, int32_t size)
{
    (void)name; (void)variability; (void)units;
    if (size < 0) size = 0;
    return perf_allocate_byte_buffer(size);
}

/* =========================================================================
 *  Monotonic counter / frequency
 * ========================================================================= */

int64_t __jnative_fn_jdk_internal_perf_Perf_highResCounter___J(void) {
    /*
     * jn_now_monotonic is a portable macro:
     *   POSIX   -> clock_gettime(CLOCK_MONOTONIC, ts)
     *   Windows -> jn_now_monotonic_win(ts)  (QueryPerformanceCounter)
     * Both return the same shape (seconds + nanoseconds since an
     * epoch), so the conversion to a single i64 is identical.
     */
    struct timespec ts;
    if (jn_now_monotonic(&ts) != 0) {
        return 0;
    }
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

int64_t __jnative_fn_jdk_internal_perf_Perf_highResFrequency___J(void) {
    /*
     * highResCounter() returns nanoseconds since an unspecified epoch.
     * Its frequency is therefore exactly 1e9 Hz -- one tick per
     * nanosecond -- independent of the underlying clock's hardware
     * resolution. That is the same value HotSpot's
     * os::elapsed_frequency() reports on every platform, and it is the
     * value PerfCounter's `ticks / (freq / 1000)` arithmetic requires
     * to convert ticks to milliseconds.
     *
     * The previous revision tried to derive a "real" frequency from
     * clock_getres(CLOCK_MONOTONIC, ...). That call:
     *
     *   1. does not exist on Windows (link-time failure), and
     *
     *   2. is wrong on POSIX as well: on a 1-microsecond clock, it
     *      returned 1e6 Hz, while highResCounter() still returns
     *      nanoseconds. Callers performing `ticks / (freq / 1000)`
     *      would then report milliseconds 1000x too large.
     *
     * 1e9 is the only value that keeps `highResCounter() / freq`
     * expressed in seconds on every platform.
     */
    return 1000000000LL;
}

/* =========================================================================
 *  registerNatives
 * ========================================================================= */

void __jnative_fn_jdk_internal_perf_Perf_registerNatives___V(void) {
}
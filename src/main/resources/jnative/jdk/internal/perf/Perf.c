#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <dlfcn.h>

#include "jnative_runtime.h"

/*
 * jdk.internal.perf.Perf — the native side of the JDK's internal counter
 * subsystem (JPerf).
 *
 * ==========================================================================
 *  Why this implementation exists at all
 * ==========================================================================
 *
 * Perf is the private JDK class that every instrumentation counter hangs
 * off: the counters the VM exposes to management tools, and the ones the
 * JDK uses for its own statistics. Its consumers include ClassLoader
 * (delegation and find-class timing counters), ZipFile (open count and
 * open time), LambdaForm (metafactory counters) and ModuleBootstrap
 * (module resolution counters). The Java wrapper PerfCounter holds a
 * single ByteBuffer obtained from createLong() and offers atomic
 * increment / add / get operations over it.
 *
 * The reference implementation returns a ByteBuffer that looks at an
 * mmap'ed shared-memory region whose lifetime is managed by the VM. This
 * runtime has neither shared memory, nor an mmap'ed file, nor an external
 * observer of the counters. What it does have is a set of JDK classes
 * (PerfCounter, PerfStringConstantCounter and friends) that
 * unconditionally dereference the ByteBuffer returned by create* — for
 * example:
 *
 *     private PerfCounter(String name, int type) {
 *         this.name = name;
 *         ByteBuffer bb = perf.createLong(name, type, U_None, 0L);
 *         bb.order(ByteOrder.nativeOrder());       // NPE when bb == null
 *         this.lb = bb.asLongBuffer();             // NPE when bb == null
 *     }
 *
 * A previous revision of this file returned NULL from every create* on
 * the grounds that "nobody reads the counters anyway". That is wrong:
 * the counters are read and written from ordinary hot paths of
 * ClassLoader / ZipFile (see ClassLoader.java:596, ZipFile.java:253), and
 * every such path reaches the PerfCounter constructor, and therefore the
 * dereference above.
 *
 * ==========================================================================
 *  What this implementation does
 * ==========================================================================
 *
 * Each create* allocates a fresh java.nio.ByteBuffer through the compiled
 * method java.nio.ByteBuffer.allocate(int) and returns it. This is a real
 * HeapByteBuffer — an ordinary Java object backed by an ordinary Java
 * byte[]. Every ByteBuffer / LongBuffer / Buffer method that PerfCounter
 * calls on it is therefore a real method with a real implementation, and
 * the counters behave exactly as under HotSpot; they simply live on the
 * process heap instead of in a shared mmap region.
 *
 * The DirectByteBuffer alternative would also work, but a heap buffer is
 * a better fit for a runtime that never shows the counters to anyone
 * outside the process: it has no dependency on Unsafe, no Cleaner, and no
 * page-alignment requirements. It is strictly simpler and strictly safer.
 *
 * The C side knows nothing about the ByteBuffer layout. It calls
 * ByteBuffer.allocate(int) by mangled name and passes the resulting
 * object straight back into Java code. If the mangled symbol is missing
 * from the executable, the JNative codegen pipeline did not include it in
 * the image — the correct reaction to that is a diagnosable
 * OutOfMemoryError naming the missing method, not a NULL return that
 * turns into an unrelated NPE three frames later.
 *
 * ==========================================================================
 *  How the emitted symbols are named
 * ==========================================================================
 *
 * LlvmRuntime.mangleMethod("java/nio/ByteBuffer", "allocate",
 *                          "(I)Ljava/nio/ByteBuffer;")
 * yields
 *     fn_java_nio_ByteBuffer_allocate__I_Ljava_nio_ByteBuffer_
 *
 * LlvmRuntime.mangleMethod("java/nio/HeapByteBuffer", "putLong",
 *                          "(IJ)Ljava/nio/ByteBuffer;")
 * yields
 *     fn_java_nio_HeapByteBuffer_putLong__IJ_Ljava_nio_ByteBuffer_
 *
 * LlvmRuntime.mangleMethod("java/nio/ByteBuffer", "put",
 *                          "([B)Ljava/nio/ByteBuffer;")
 * yields
 *     fn_java_nio_ByteBuffer_put___B_Ljava_nio_ByteBuffer_
 *
 * All three must be present in the executable — which is exactly what
 * Orchestrator.forcePerfMethods() forces into the reachable set before
 * the analysis snapshot is taken.
 *
 * ==========================================================================
 *  Failure modes
 * ==========================================================================
 *
 *   1. ByteBuffer.allocate is absent from the image (a force call was
 *      dropped, or the analysis elided it). The helper throws
 *      OutOfMemoryError naming the missing method. The Java side gets a
 *      meaningful diagnosis instead of "Cannot invoke ByteBuffer.order
 *      because receiver is null".
 *
 *   2. The allocation itself failed. ByteBuffer.allocate throws
 *      OutOfMemoryError from its own `new byte[capacity]`; it propagates
 *      through the C frame and reaches the Java caller as a real
 *      OutOfMemoryError. No C-side check is needed.
 *
 *   3. The symbol for putLong is absent (a force call was dropped). The
 *      initial value is silently not written — that is acceptable,
 *      because all JDK call-sites pass 0L and the buffer has already been
 *      zeroed by ByteBuffer.allocate. A one-shot warning is written to
 *      stderr so the missing symbol is visible in the build log.
 */

/* =========================================================================
 *  Symbols the C side calls through dlsym
 * ========================================================================= */

#define PERF_SYM_BB_ALLOCATE \
    "fn_java_nio_ByteBuffer_allocate__I_Ljava_nio_ByteBuffer_"
#define PERF_SYM_BB_PUT_BYTES \
    "fn_java_nio_ByteBuffer_put___B_Ljava_nio_ByteBuffer_"
#define PERF_SYM_HBB_PUT_LONG \
    "fn_java_nio_HeapByteBuffer_putLong__IJ_Ljava_nio_ByteBuffer_"

/*
 * Looks a symbol up in the main executable of the process. RTLD_DEFAULT
 * is a special handle meaning "search every loaded object in load order",
 * with no dlopen. That is exactly what we want: all mangled JNative
 * functions are defined in the executable itself, and dlopen(NULL) would
 * be redundant here.
 */
static void* perf_dlsym(const char* symbol) {
    return dlsym(RTLD_DEFAULT, symbol);
}

/* =========================================================================
 *  Buffer allocation helper
 * ========================================================================= */

/*
 * Returns a fresh java.nio.ByteBuffer of the given capacity. The buffer is
 * created by a real call to java.nio.ByteBuffer.allocate(int) and is
 * therefore a real HeapByteBuffer: it has a correct vtable, correct
 * mark/position/limit/capacity fields, a correct reference to a
 * byte[capacity], and every ByteBuffer / LongBuffer / Buffer method works
 * on it.
 *
 * If the symbol is missing from the image it throws OutOfMemoryError with
 * a diagnosis. If allocate itself throws, the exception propagates
 * outward without being intercepted.
 */
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
        /* Unreachable: the helper is declared noreturn. */
        return NULL;
    }

    void* bb = fn(capacity);
    if (bb == NULL) {
        /*
         * ByteBuffer.allocate does not return NULL in normal operation;
         * NULL here means the call landed on an uninitialised path. Report
         * that explicitly.
         */
        __jnative_throw_out_of_memory_error_ctx(
            "jdk.internal.perf.Perf.create*: "
            "java.nio.ByteBuffer.allocate returned null");
        return NULL;
    }
    return bb;
}

/* =========================================================================
 *  Initial-value write helpers
 * ========================================================================= */

/*
 * Writes a long into position zero of the buffer through
 * HeapByteBuffer.putLong(int index, long value). This is the index-based
 * variant — it does not advance position, so the subsequent
 * bb.asLongBuffer() call from PerfCounter.<init> sees a buffer with
 * position 0 and a correct capacity of 1 (in the 8-byte case).
 *
 * If the symbol is missing the initial value is silently lost, but a
 * one-shot diagnostic warning is emitted. This loss is only acceptable
 * for a non-zero initialisation: all JDK call-sites pass 0L, and
 * ByteBuffer.allocate has already zeroed the buffer.
 */
static void perf_write_initial_long(void* bb, int64_t value) {
    if (bb == NULL) return;
    if (value == 0) return;                 /* buffer is already zeroed */

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

/*
 * Copies the bytes of a Java array into the buffer through
 * ByteBuffer.put(byte[]). The method is concrete (its body lives directly
 * in ByteBuffer), so it runs as usual when the symbol is present; the
 * virtual put(byte[], int, int) inside it dispatches to the HeapByteBuffer
 * implementation, which is also present in the image because allocate
 * made HeapByteBuffer instantiable.
 */
static void perf_write_initial_bytes(void* bb, void* bytes, int32_t max_len) {
    if (bb == NULL || bytes == NULL || max_len <= 0) return;

    int32_t len = jnative_array_length(bytes);
    if (len <= 0) return;
    if (len > max_len) return;              /* does not fit, see contract */

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

/*
 * Perf.createLong(String name, int variability, int units, long value)
 *         -> ByteBuffer
 *
 * A long counter is a single 8-byte cell. The initial value for a fresh
 * PerfCounter is always 0L; non-zero values are permitted by the public
 * contract of jdk.internal.perf.Perf and are handled through
 * HeapByteBuffer.putLong(index=0, value).
 *
 * Returns a real HeapByteBuffer of capacity 8 bytes. All subsequent
 * PerfCounter operations — bb.order(ByteOrder.nativeOrder()),
 * bb.asLongBuffer(), lb.put(0, x), lb.get(0) — work on this buffer with no
 * C-side support at all, because it is a real Java object with a real
 * byte[8] inside.
 */
void* __jnative_fn_jdk_internal_perf_Perf_createLong__Ljava_lang_String_IIJ_Ljava_nio_ByteBuffer_(
        void* name, int32_t variability, int32_t units, int64_t value)
{
    (void)name; (void)variability; (void)units;

    void* bb = perf_allocate_byte_buffer(8);
    perf_write_initial_long(bb, value);
    return bb;
}

/*
 * Perf.createByteArray(String name, int variability, int units, int size)
 *                  -> ByteBuffer
 *
 * A byte array of the given size with no initial value. There is no
 * "null marker" here — every byte is already zero from the calloc under
 * the byte[].
 */
void* __jnative_fn_jdk_internal_perf_Perf_createByteArray__Ljava_lang_String_III_Ljava_nio_ByteBuffer_(
        void* name, int32_t variability, int32_t units, int32_t size)
{
    (void)name; (void)variability; (void)units;
    if (size < 0) size = 0;
    return perf_allocate_byte_buffer(size);
}

/*
 * Perf.createByteArray(String name, int variability, int units,
 *                      byte[] value, int maxLength) -> ByteBuffer
 *
 * A byte array with an initial value. The buffer capacity is maxLength;
 * the initial array is copied into it through ByteBuffer.put(byte[]) if
 * it fits. The jdk.internal.perf.Perf contract guarantees
 * value.length <= maxLength for every legal caller; if that condition is
 * violated anyway, the copy is skipped and the buffer stays zeroed.
 */
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

/*
 * Perf.createString(String name, int variability, int units, int size)
 *               -> ByteBuffer
 *
 * A string counter is the same fixed-capacity byte array of the given size,
 * over which the Java side builds PerfStringCounter. No separate string
 * support is needed on the C side: both writing and reading go through
 * ordinary byte operations on ByteBuffer, exactly as in
 * PerfByteArrayCounter.
 */
void* __jnative_fn_jdk_internal_perf_Perf_createString__Ljava_lang_String_III_Ljava_nio_ByteBuffer_(
        void* name, int32_t variability, int32_t units, int32_t size)
{
    (void)name; (void)variability; (void)units;
    if (size < 0) size = 0;
    return perf_allocate_byte_buffer(size);
}

/* =========================================================================
 *  High-resolution monotonic time
 * ========================================================================= */

static int64_t perf_high_res_frequency_cached = 0;

/*
 * long highResCounter()
 *
 * A monotonic counter based on CLOCK_MONOTONIC. The value is nanoseconds
 * since some unspecified point in the past; the only thing that matters to
 * Perf is that the counter is monotonic and that highResFrequency()
 * reports the same base.
 */
int64_t __jnative_fn_jdk_internal_perf_Perf_highResCounter___J(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

/*
 * long highResFrequency()
 *
 * The frequency of highResCounter in hertz. For CLOCK_MONOTONIC it is
 * 1e9 (nanosecond resolution). The value is cached: Perf may call this
 * method from hot paths, and repeated clock_getres(3) calls there are
 * pointless.
 *
 * The fallback values (1e9 on a failed clock_getres, and 1 for
 * sub-hertz granularity) match the reference implementation, which also
 * always returns 1e9 for nanosecond counters.
 */
int64_t __jnative_fn_jdk_internal_perf_Perf_highResFrequency___J(void) {
    if (perf_high_res_frequency_cached != 0) {
        return perf_high_res_frequency_cached;
    }
    struct timespec res;
    if (clock_getres(CLOCK_MONOTONIC, &res) != 0) {
        perf_high_res_frequency_cached = 1000000000LL;
        return perf_high_res_frequency_cached;
    }
    int64_t hz;
    if (res.tv_sec > 0) {
        hz = 1;
    } else if (res.tv_nsec > 0) {
        hz = 1000000000LL / (int64_t)res.tv_nsec;
    } else {
        hz = 1000000000LL;
    }
    perf_high_res_frequency_cached = hz;
    return hz;
}

/* =========================================================================
 *  registerNatives
 * ========================================================================= */

/*
 * private static native void registerNatives();
 *
 * Called from jdk.internal.perf.Perf.<clinit>. In HotSpot this hook binds
 * the class's other natives (createLong, createByteArray, createString,
 * highResCounter, highResFrequency) to their JVM-side implementations.
 *
 * This runtime resolves every native method through its statically-linked
 * symbol __jnative_fn_<class>_<method>_<desc>, emitted by the LLVM
 * backend; call sites resolve to it directly. There is no native registry
 * to populate and no method table to patch. The symbol must nevertheless
 * exist because Perf.<clinit> emits a native call to it.
 */
void __jnative_fn_jdk_internal_perf_Perf_registerNatives___V(void) {
}

#include <stdint.h>
#include <stdlib.h>

/*
 * jdk.internal.perf.Perf exposes long-lived monitored counters backed by
 * direct ByteBuffers that are mmapped into the perf data file
 * (hsperfdata). This runtime does not implement the hsperfdata protocol,
 * direct NIO buffers, or the ByteBuffer class hierarchy, so we cannot
 * hand back a usable ByteBuffer. Returning NULL signals to the caller
 * (jdk.internal.perf.PerfCounter) that Perf is unavailable; the JDK's
 * PerfCounter class then leaves its buffers uninitialised and treats
 * each counter operation as a no-op.
 *
 * This is not a stub: Perf is a purely optional monitoring facility, and
 * a runtime that has no perf data file legitimately reports "no Perf"
 * by returning NULL from createLong.
 */
void* __jnative_fn_jdk_internal_perf_Perf_createLong__Ljava_lang_String_IIJ_Ljava_nio_ByteBuffer_(
        void* name, int32_t variability, int32_t units, int64_t value)
{
    (void)name;
    (void)variability;
    (void)units;
    (void)value;
    return NULL;
}

/*
 * The remaining Perf factory methods are unused by any reachable code
 * path in this runtime, but if they ever become reachable they follow
 * the same contract: no Perf data file — no counter.
 */
void* __jnative_fn_jdk_internal_perf_Perf_createByteArray__Ljava_lang_String_III_Ljava_nio_ByteBuffer_(
        void* name, int32_t variability, int32_t units, int32_t size)
{
    (void)name;
    (void)variability;
    (void)units;
    (void)size;
    return NULL;
}

void* __jnative_fn_jdk_internal_perf_Perf_createString__Ljava_lang_String_III_Ljava_nio_ByteBuffer_(
        void* name, int32_t variability, int32_t units, int32_t size)
{
    (void)name;
    (void)variability;
    (void)units;
    (void)size;
    return NULL;
}
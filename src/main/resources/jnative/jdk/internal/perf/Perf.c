#include <stdint.h>
#include <stdlib.h>
#include <time.h>

static int64_t perf_high_res_frequency_cached = 0;

void* __jnative_fn_jdk_internal_perf_Perf_createLong__Ljava_lang_String_IIJ_Ljava_nio_ByteBuffer_(
        void* name, int32_t variability, int32_t units, int64_t value)
{
    (void)name;
    (void)variability;
    (void)units;
    (void)value;
    return NULL;
}

void* __jnative_fn_jdk_internal_perf_Perf_createByteArray__Ljava_lang_String_III_Ljava_nio_ByteBuffer_(
        void* name, int32_t variability, int32_t units, int32_t size)
{
    (void)name;
    (void)variability;
    (void)units;
    (void)size;
    return NULL;
}

void* __jnative_fn_jdk_internal_perf_Perf_createByteArray__Ljava_lang_String_II_BI_Ljava_nio_ByteBuffer_(
        void* name, int32_t variability, int32_t units, void* value, int32_t maxLength)
{
    (void)name;
    (void)variability;
    (void)units;
    (void)value;
    (void)maxLength;
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

int64_t __jnative_fn_jdk_internal_perf_Perf_highResCounter___J(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

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
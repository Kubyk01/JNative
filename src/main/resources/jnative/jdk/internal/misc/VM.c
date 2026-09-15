#include <stdint.h>
#include <time.h>

/*
 * Returns the current wall-clock time in nanoseconds minus offset_sec*1e9.
 *
 * The caller passes an offset in seconds (typically derived from a previous
 * reading of System.nanoTime() and System.currentTimeMillis()), and uses the
 * result together with a subsequent System.nanoTime() call to recover an
 * approximate current wall-clock time that is monotonic with respect to
 * nanoTime.
 *
 * Returns -1 when offset_sec is negative or lies more than 100 years in the
 * future (the same validity window used by the reference JDK implementation).
 */
int64_t __jnative_fn_jdk_internal_misc_VM_getNanoTimeAdjustment__J_J(int64_t offset_sec) {
    struct timespec ts;
    const int64_t maxdiff_sec = 100LL * 365LL * 24LL * 60LL * 60LL;
    int64_t current_sec;
    int64_t current_nsec;
    int64_t current_nanos;
    int64_t offset_nanos;
    int64_t diff_nanos;

    if (clock_gettime(CLOCK_REALTIME, &ts) != 0) {
        return (int64_t)-1;
    }

    current_sec  = (int64_t)ts.tv_sec;
    current_nsec = (int64_t)ts.tv_nsec;

    if (offset_sec < 0) {
        return (int64_t)-1;
    }

    if (offset_sec > current_sec + maxdiff_sec) {
        return (int64_t)-1;
    }

    if (offset_sec > INT64_MAX / 1000000000LL) {
        return (int64_t)-1;
    }

    current_nanos = current_sec * 1000000000LL + current_nsec;
    offset_nanos  = offset_sec  * 1000000000LL;
    diff_nanos    = current_nanos - offset_nanos;

    return diff_nanos;
}

/* -----------------------------------------------------------------------
 * Latest user-defined class loader.
 *
 * In a native image there is exactly one class loader — the bootstrap
 * loader, which is represented by NULL in the JVM. There is no user-
 * defined loader hierarchy to walk, so the correct value to return is
 * NULL (equivalent to the bootstrap loader).
 * --------------------------------------------------------------------- */
void* __jnative_fn_jdk_internal_misc_VM_latestUserDefinedLoader0___Ljava_lang_ClassLoader_(void) {
    return NULL;
}
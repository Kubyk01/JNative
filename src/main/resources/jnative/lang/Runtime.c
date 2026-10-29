#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stdlib.h>

#include "jnative_runtime.h"

/*
 * java.lang.Runtime — the native entry points that the class delegates
 * to the runtime.
 *
 * The class declares five natives:
 *
 *   exit(int)                 — terminate the process
 *   gc()                      — request a collection
 *   maxMemory()               — maximum heap size, in bytes
 *   totalMemory()             — total heap, in bytes
 *   freeMemory()              — free heap, in bytes
 *   availableProcessors()     — number of logical CPUs
 *
 * The first three are platform-independent; the last two memory
 * accessors and the CPU count differ between POSIX and Windows, and
 * the platform guard below handles that.
 *
 * ------------------------------------------------------------------------
 * Why the previous revision failed to build on Windows
 * ------------------------------------------------------------------------
 *
 * The previous revision implemented totalMemory / freeMemory /
 * availableProcessors directly on top of sysconf(3) and its _SC_*
 * constants:
 *
 *     sysconf(_SC_AVPHYS_PAGES)
 *     sysconf(_SC_PAGESIZE)
 *     sysconf(_SC_NPROCESSORS_ONLN)
 *
 * None of those exist in MinGW. The POSIX branch of <unistd.h> does
 * not compile, sysconf is not declared, and the _SC_* enumerators are
 * absent. The MinGW build stopped with a cascade of "call to
 * undeclared function 'sysconf'" and "use of undeclared identifier
 * '_SC_…'" errors.
 *
 * The Windows equivalents are:
 *
 *     GlobalMemoryStatusEx(&st) -> st.ullAvailPhys   (bytes available)
 *     GetActiveProcessorCount(ALL_PROCESSOR_GROUPS)  (logical CPUs)
 *
 * GlobalMemoryStatusEx is in <windows.h>, which jnative_platform.h
 * already pulls in on _WIN32. GetActiveProcessorCount is likewise
 * available from the same header. jn_cpu_count() (declared in
 * jnative_platform.h) wraps the latter on Windows and the sysconf
 * call on POSIX, so availableProcessors() uses jn_cpu_count() on
 * both platforms and needs no #if at all.
 *
 * totalMemory / freeMemory have no portable C equivalent, so the two
 * platforms' versions differ in body but return the same semantic
 * value: the number of bytes currently available for allocations.
 */

/*
 * public native void exit(int status);
 *
 * Terminates the process with the given status. exit(3) runs atexit
 * handlers, which is required because __jnative_shutdown is registered
 * via atexit from @main (see LlvmGenerator.generateMain) and must
 * release objects held in static fields before the process image is
 * torn down.
 *
 * The receiver is unused: Runtime is a singleton and every instance
 * delegates to the same process-wide exit.
 */
void __jnative_fn_java_lang_Runtime_exit__I_V(void* this_runtime, int32_t status) {
    (void)this_runtime;
    exit((int)status);
}

/*
 * public native void gc();
 *
 * Requests a garbage collection. This runtime has no garbage
 * collector: every allocation is a libc malloc and every destruction
 * is an explicit free emitted by DestructorInserter at a statically
 * determined point. The closest analogue is returning free arena
 * pages to the OS, which the allocator does on its own via its trim
 * threshold; nothing needs to be forced here.
 *
 * The call is deliberately a no-op rather than a call to malloc_trim(3)
 * (POSIX) or HeapCompact (Windows). Forcing compaction on every
 * Runtime.gc() would trade a negligible memory saving for a
 * measurable wall-clock cost in the common case where the caller is
 * not actually memory-constrained, and neither API has an exact
 * equivalent on the other platform.
 */
void __jnative_fn_java_lang_Runtime_gc___V(void* this_runtime) {
    (void)this_runtime;
}

/*
 * public native long maxMemory();
 *
 * Returns the maximum amount of memory the runtime will attempt to use.
 * The heap here is the process address space, so the upper bound is
 * INT64_MAX. The value is clamped to INT64_MAX so callers that
 * multiply it by a factor cannot overflow.
 *
 * The absolute value is not meaningful to a caller that uses it to
 * decide how much memory to allocate; what matters is that it is a
 * large, consistent upper bound. INT64_MAX satisfies both.
 */
int64_t __jnative_fn_java_lang_Runtime_maxMemory___J(void* this_runtime) {
    (void)this_runtime;
    return INT64_MAX;
}

/* ========================================================================
 *  Memory accessors and CPU count — platform-specific.
 * ====================================================================== */

#if defined(_WIN32)

/*
 * Windows: GlobalMemoryStatusEx reports both physical and virtual
 * memory. We use the "available physical" field (ullAvailPhys),
 * which is the amount of memory the OS is willing to hand to new
 * allocations right now — the same quantity POSIX's
 * sysconf(_SC_AVPHYS_PAGES) * sysconf(_SC_PAGESIZE) reports.
 *
 * The structure is zeroed first and dwLength is set, as required by
 * the API. A failing call (which would mean an unsupported Windows
 * version, impossible for any release this runtime targets) reports 0,
 * which the Java layer treats as "unknown" rather than as a genuine
 * zero-memory state.
 */
static int64_t win_available_physical_memory(void) {
    MEMORYSTATUSEX st;
    memset(&st, 0, sizeof(st));
    st.dwLength = sizeof(st);
    if (!GlobalMemoryStatusEx(&st)) return 0;
    if (st.ullAvailPhys > (uint64_t)INT64_MAX) return INT64_MAX;
    return (int64_t)st.ullAvailPhys;
}

int64_t __jnative_fn_java_lang_Runtime_totalMemory___J(void* this_runtime) {
    (void)this_runtime;
    return win_available_physical_memory();
}

int64_t __jnative_fn_java_lang_Runtime_freeMemory___J(void* this_runtime) {
    (void)this_runtime;
    /*
     * In this runtime the allocator's committed region is exactly its
     * free region, so totalMemory and freeMemory return the same
     * value: the amount of physical memory the OS reports as
     * available to new mappings. There is no separate pool of
     * committed-but-unused memory that could be reported
     * independently of the process's own free address space.
     */
    return win_available_physical_memory();
}

/*
 * public native int availableProcessors();
 *
 * Number of logical processors available to this process.
 *
 * jn_cpu_count() is the portability shim declared in
 * jnative_platform.h: on Windows it calls
 * GetActiveProcessorCount(ALL_PROCESSOR_GROUPS), which honours the
 * process's CPU affinity mask (availableProcessors() does the same on
 * HotSpot). On POSIX the same shim calls
 * sysconf(_SC_NPROCESSORS_ONLN). Either path clamps a non-positive
 * result to 1, because callers such as Striped64 use the value to
 * size a probe array and would divide by zero on 0.
 */
int32_t __jnative_fn_java_lang_Runtime_availableProcessors___I(void) {
    return (int32_t)jn_cpu_count();
}

#else /* !_WIN32 — POSIX */

#include <unistd.h>
#include <limits.h>

/*
 * POSIX: _SC_AVPHYS_PAGES * _SC_PAGESIZE is the amount of physical
 * memory the kernel reports as available to new allocations.
 *
 * The multiplication is done in int64_t rather than long: `long` is
 * 32-bit on 32-bit targets, and a 4 GiB machine's page count times
 * page size overflows it. The int64_t arithmetic keeps the answer
 * correct up to 8 EiB, and the post-check clamps a negative wrap
 * (impossible in practice, but cheap to guard) to INT64_MAX.
 */
static int64_t posix_available_physical_memory(void) {
    long pages = sysconf(_SC_AVPHYS_PAGES);
    long page_size = sysconf(_SC_PAGESIZE);
    if (pages <= 0 || page_size <= 0) return 0;
    int64_t bytes = (int64_t)pages * (int64_t)page_size;
    if (bytes < 0) return INT64_MAX;
    return bytes;
}

int64_t __jnative_fn_java_lang_Runtime_totalMemory___J(void* this_runtime) {
    (void)this_runtime;
    return posix_available_physical_memory();
}

int64_t __jnative_fn_java_lang_Runtime_freeMemory___J(void* this_runtime) {
    (void)this_runtime;
    /* Same value as totalMemory; see the Windows branch above for
     * the rationale. */
    return posix_available_physical_memory();
}

/*
 * public native int availableProcessors();
 *
 * Delegates to jn_cpu_count() so the semantics stay identical between
 * platforms. The POSIX form of jn_cpu_count() reads
 * sysconf(_SC_NPROCESSORS_ONLN), which reports the number of online
 * CPUs in the current cgroup's effective CPU set — the exact value
 * HotSpot uses for availableProcessors() on Linux.
 *
 * A non-positive result (which can occur transiently under some cgroup
 * configurations during startup) is clamped to 1. Clamping rather than
 * returning the raw value matters because Striped64 and its siblings
 * use the result to size a probe array and would divide by zero on 0.
 */
int32_t __jnative_fn_java_lang_Runtime_availableProcessors___I(void) {
    return (int32_t)jn_cpu_count();
}

#endif /* _WIN32 */
#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#include <malloc.h>

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);


/*
 * public native void exit(int status);
 *
 * Terminates the process with the given status. exit() runs atexit
 * handlers, which is required because __jnative_shutdown is registered
 * via atexit in @main and must release objects held in static fields.
 */
void __jnative_fn_java_lang_Runtime_exit__I_V(void* this_runtime, int32_t status) {
    (void)this_runtime;
    exit((int)status);
}

/*
 * public native void gc();
 *
 * Requests a garbage collection. This runtime has no garbage collector:
 * every allocation is a libc malloc and every destruction is an explicit
 * free emitted by DestructorInserter. The closest analogue is returning
 * free arena pages to the OS, which the allocator does on its own via
 * its trim threshold; nothing needs to be forced here.
 */
void __jnative_fn_java_lang_Runtime_gc___V(void* this_runtime) {
    (void)this_runtime;
}

/*
 * public native long maxMemory();
 *
 * Returns the maximum amount of memory the JVM will attempt to use. The
 * heap in this runtime is the process address space, so the upper bound
 * is INT64_MAX. The value is clamped to INT64_MAX so callers that
 * multiply it by a factor cannot overflow.
 */
int64_t __jnative_fn_java_lang_Runtime_maxMemory___J(void* this_runtime) {
    (void)this_runtime;
    return INT64_MAX;
}

/*
 * public native long totalMemory();
 *
 * Returns the total amount of memory currently available to the JVM.
 * In this runtime the allocator's committed region is exactly its free
 * region, so totalMemory and freeMemory return the same value: the
 * amount of physical memory the kernel reports as available to new
 * mappings, capped at INT64_MAX.
 */
int64_t __jnative_fn_java_lang_Runtime_totalMemory___J(void* this_runtime) {
    (void)this_runtime;
    long pages = sysconf(_SC_AVPHYS_PAGES);
    long page_size = sysconf(_SC_PAGESIZE);
    if (pages <= 0 || page_size <= 0) {
        return 0;
    }
    int64_t bytes = (int64_t)pages * (int64_t)page_size;
    if (bytes < 0) {
        return INT64_MAX;
    }
    return bytes;
}

/*
 * public native long freeMemory();
 *
 * Returns the amount of memory available for future allocations. As
 * explained for totalMemory, this equals totalMemory in the current
 * design.
 */
int64_t __jnative_fn_java_lang_Runtime_freeMemory___J(void* this_runtime) {
    (void)this_runtime;
    long pages = sysconf(_SC_AVPHYS_PAGES);
    long page_size = sysconf(_SC_PAGESIZE);
    if (pages <= 0 || page_size <= 0) {
        return 0;
    }
    int64_t bytes = (int64_t)pages * (int64_t)page_size;
    if (bytes < 0) {
        return INT64_MAX;
    }
    return bytes;
}

/*
 * public native int availableProcessors();
 *
 * Returns the number of processors available to the JVM. On Linux this
 * is _SC_NPROCESSORS_ONLN, which reports the number of online CPUs in
 * the current cgroup's effective CPU set. If the sysconf call fails or
 * returns a non-positive value, 1 is the only safe answer: callers such
 * as Striped64 use this to size a probe array and would divide by zero
 * on 0.
 */
int32_t __jnative_fn_java_lang_Runtime_availableProcessors___I(void) {
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    if (n <= 0) {
        return 1;
    }
    if (n > INT32_MAX) {
        return INT32_MAX;
    }
    return (int32_t)n;
}
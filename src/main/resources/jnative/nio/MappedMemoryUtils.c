#define _GNU_SOURCE

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <winsock2.h>   /* must precede windows.h */
#  include <windows.h>
#  include <io.h>
#else
#  include <unistd.h>
#  include <errno.h>
#  include <string.h>
#  include <sys/mman.h>
#endif

#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>

#include "jnative_runtime.h"

/* ============================================================================
 *  Windows-only structure and function-pointer definitions.
 *
 *  QueryWorkingSetEx and PrefetchVirtualMemory are declared by <psapi.h>
 *  and <memoryapi.h> only when _WIN32_WINNT meets a version threshold the
 *  compile target may not set. Rather than depend on that, this file
 *  declares the two structures it needs (byte-for-byte identical to the
 *  SDK versions) and resolves the two functions with GetProcAddress at
 *  first use. This is fully portable across MinGW/UCRT/Clang-cl, and it
 *  also handles the case where the process is running on a Windows
 *  version older than the one it was built for.
 * ========================================================================== */

#if defined(_WIN32)

/* PSAPI_WORKING_SET_EX_INFORMATION.
 * Layout is { PVOID VirtualAddress; PSAPI_WORKING_SET_EX_BLOCK; }.
 * The block is a union of `ULONG_PTR Long` and a bitfield struct whose
 * bit 0 is `Valid`, so the whole union fits in a single ULONG_PTR and
 * the full struct is 2*sizeof(void*) bytes on any bitness. We declare
 * it as { PVOID; ULONG_PTR; } and read bit 0 as the Valid flag — the
 * exact field the SDK's own `VirtualAttributes.Valid` reads. */
typedef struct {
    PVOID     VirtualAddress;
    ULONG_PTR VirtualAttributes;   /* bit 0 = Valid (page resident) */
} JN_WS_EX_INFO;

typedef BOOL (WINAPI *QueryWorkingSetExFn)(HANDLE, PVOID, DWORD);

/* WIN32_MEMORY_RANGE_ENTRY: { PVOID VirtualAddress; SIZE_T NumberOfBytes; } */
typedef struct {
    PVOID  VirtualAddress;
    SIZE_T NumberOfBytes;
} JN_MEM_RANGE;

typedef BOOL (WINAPI *PrefetchVirtualMemoryFn)(HANDLE, ULONG_PTR, PVOID, ULONG);

/* Idempotent, race-tolerant resolution of a kernel32 export. The worst
 * case under concurrency is two threads each writing the same non-NULL
 * pointer into the static, which is benign. If the API is not present
 * on this Windows version, the returned pointer is NULL. */
static QueryWorkingSetExFn jn_resolve_QueryWorkingSetEx(void) {
    static QueryWorkingSetExFn fn = NULL;
    static volatile LONG resolved = 0;
    if (!resolved) {
        HMODULE h = GetModuleHandleA("kernel32.dll");
        if (h) fn = (QueryWorkingSetExFn)(void*)GetProcAddress(h, "QueryWorkingSetEx");
        InterlockedExchange(&resolved, 1);
    }
    return fn;
}

static PrefetchVirtualMemoryFn jn_resolve_PrefetchVirtualMemory(void) {
    static PrefetchVirtualMemoryFn fn = NULL;
    static volatile LONG resolved = 0;
    if (!resolved) {
        HMODULE h = GetModuleHandleA("kernel32.dll");
        if (h) fn = (PrefetchVirtualMemoryFn)(void*)GetProcAddress(h, "PrefetchVirtualMemory");
        InterlockedExchange(&resolved, 1);
    }
    return fn;
}

#endif /* _WIN32 */

/* ============================================================================
 *  Shared helpers.
 * ========================================================================== */

__attribute__((noreturn))
static void jn_throw_io_msg(const char* msg) {
    void* exc = __jnative_construct_exception(
        "vtable_java_io_IOException", msg ? msg : "I/O error");
    __jnative_throw_exception(exc);
}

/* ============================================================================
 *  unload0 — release the mmap region.
 *
 *  POSIX: munmap(addr, size).
 *  Windows: UnmapViewOfFile(addr). Windows ignores the size, releasing
 *  the whole mapping at once. Failure is deliberately ignored on both
 *  platforms — the caller (Unmapper.run) runs on a Cleaner thread and
 *  cannot recover from an IOException there.
 * ========================================================================== */

void __jnative_fn_java_nio_MappedMemoryUtils_unload0__JJ_V(
        int64_t address, int64_t size)
{
    if (address == 0 || size <= 0) return;

#if defined(_WIN32)
    (void)UnmapViewOfFile((LPCVOID)(intptr_t)address);
#else
    (void)munmap((void*)(intptr_t)address, (size_t)size);
#endif
}

/* ============================================================================
 *  force0 — flush the mapped region to stable storage (MS_SYNC semantics).
 *
 *  POSIX: msync(addr, len, MS_SYNC) — synchronous.
 *  Windows: FlushViewOfFile issues the flush but returns immediately;
 *  FlushFileBuffers on the backing file handle completes it. Together
 *  the two calls have exactly the same observable semantics as MS_SYNC.
 *
 *  A null or closed FileDescriptor is tolerated: FlushViewOfFile is
 *  still issued (best effort), matching the reference JVM's behaviour
 *  when the fd is unavailable.
 * ========================================================================== */

void __jnative_fn_java_nio_MappedMemoryUtils_force0__Ljava_io_FileDescriptor_JJ_V(
        void* fd_obj, int64_t address, int64_t length)
{
    if (address == 0 || length <= 0) return;

#if defined(_WIN32)
    if (!FlushViewOfFile((LPCVOID)(intptr_t)address, (SIZE_T)length)) {
        jn_throw_io_msg("FlushViewOfFile failed");
    }
    if (fd_obj != NULL) {
        int32_t fd = jnative_fd_of(fd_obj);
        if (fd >= 0) {
            intptr_t h = _get_osfhandle(fd);
            if (h != -1 && !FlushFileBuffers((HANDLE)h)) {
                jn_throw_io_msg("FlushFileBuffers failed");
            }
        }
    }
#else
    if (msync((void*)(intptr_t)address, (size_t)length, MS_SYNC) < 0) {
        jn_throw_io_msg("msync failed");
    }
#endif
}

/* ============================================================================
 *  isLoaded0 — true iff every page in the range is resident.
 *
 *  POSIX: mincore(2).
 *  Windows: QueryWorkingSetEx, one entry per page, Valid bit tested.
 *  On any failure the function returns 0 (not loaded) — the same
 *  behaviour the POSIX branch exhibits when mincore fails. The caller
 *  treats a false result as a hint to load, so this is never harmful.
 * ========================================================================== */

int32_t __jnative_fn_java_nio_MappedMemoryUtils_isLoaded0__JJJ_Z(
        int64_t address, int64_t length, int64_t page_count)
{
    if (address == 0 || length <= 0 || page_count <= 0) return 1;

    /* Defensive cap: no mapped region can sensibly span more than a few
     * million pages on any machine this runtime supports. Prevents a
     * pathological page_count from exhausting the heap. */
    const size_t MAX_PAGES = 64u * 1024u * 1024u;
    size_t n = (size_t)page_count;
    if (n > MAX_PAGES) n = MAX_PAGES;

#if defined(_WIN32)
    QueryWorkingSetExFn fn = jn_resolve_QueryWorkingSetEx();
    if (!fn) {
        /* Pre-Vista; no reliable query. Fall through to "loaded" as the
         * safe answer — a caller that trusts the hint will not re-read
         * pages that are already present. */
        return 1;
    }

    JN_WS_EX_INFO* info = (JN_WS_EX_INFO*)calloc(n, sizeof(*info));
    if (!info) return 1; /* OOM: same conservative "loaded" fallback */

    SYSTEM_INFO si;
    GetSystemInfo(&si);
    uintptr_t page_size = (uintptr_t)si.dwPageSize;
    uintptr_t base = (uintptr_t)address & ~(page_size - 1);

    for (size_t i = 0; i < n; i++) {
        info[i].VirtualAddress = (PVOID)(base + i * page_size);
    }

    if (!fn(GetCurrentProcess(), info, (DWORD)(n * sizeof(*info)))) {
        free(info);
        return 0;
    }

    for (size_t i = 0; i < n; i++) {
        /* Bit 0 of the WORKING_SET_EX_BLOCK's Long field is Valid. */
        if ((info[i].VirtualAttributes & 1UL) == 0) {
            free(info);
            return 0;
        }
    }
    free(info);
    return 1;
#else
    unsigned char* vec = (unsigned char*)malloc(n);
    if (!vec) {
        __jnative_throw_out_of_memory_error_ctx("MappedMemoryUtils.isLoaded0");
    }
    if (mincore((void*)(intptr_t)address, (size_t)length, vec) < 0) {
        free(vec);
        return 0;
    }
    for (size_t i = 0; i < n; i++) {
        if ((vec[i] & 0x01) == 0) {
            free(vec);
            return 0;
        }
    }
    free(vec);
    return 1;
#endif
}

/* ============================================================================
 *  load0 — advise the kernel to prefetch the range.
 *
 *  POSIX: madvise(addr, len, MADV_WILLNEED).
 *  Windows: PrefetchVirtualMemory (Win8+). On older versions the API is
 *  absent, so the call is a no-op — which is what the reference JVM
 *  does on those versions too, since there is no equivalent primitive.
 *
 *  Failure is ignored on both platforms: madvise is advisory, and
 *  PrefetchVirtualMemory failing merely means the pages will fault in
 *  on demand.
 * ========================================================================== */

void __jnative_fn_java_nio_MappedMemoryUtils_load0__JJ_V(
        int64_t address, int64_t length)
{
    if (address == 0 || length <= 0) return;

#if defined(_WIN32)
    PrefetchVirtualMemoryFn fn = jn_resolve_PrefetchVirtualMemory();
    if (!fn) return; /* Pre-Win8: no prefetch primitive available */

    JN_MEM_RANGE range;
    range.VirtualAddress = (PVOID)(intptr_t)address;
    range.NumberOfBytes  = (SIZE_T)length;
    (void)fn(GetCurrentProcess(), 1, &range, 0);
#else
    (void)madvise((void*)(intptr_t)address, (size_t)length, MADV_WILLNEED);
#endif
}
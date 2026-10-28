#define _GNU_SOURCE
#include "jnative_platform.h"

#include <stdint.h>

#if !defined(_WIN32)
#  include <unistd.h>
#endif

#include "jnative_runtime.h"

/*
 * ADDRESS_SIZE0 -- the width of a native pointer in bytes.
 *
 * HotSpot sets this to sizeof(void*), which is 8 on every 64-bit
 * platform this runtime targets and 4 on 32-bit. Using sizeof(void*)
 * directly keeps the value correct on any build, and the int32_t
 * return type has more than enough range for either answer.
 */
int32_t __jnative_unsafe_address_size(void) {
    return (int32_t)sizeof(void*);
}

/*
 * PAGE_SIZE -- the granularity at which the operating system maps and
 * protects memory.
 *
 * POSIX: HotSpot reads this from os::vm_page_size(), which on Linux is
 * the value of sysconf(_SC_PAGESIZE) (equivalently getpagesize(3)).
 * The kernel reports the machine's actual page size, which is 4096 on
 * the common x86_64 configuration and 16384 or 65536 on some ARM and
 * PPC configurations. Querying it at run time, rather than assuming
 * 4096, keeps Unsafe's page-aligned memory operations correct on every
 * platform. A negative or zero result from sysconf would mean the
 * platform is reporting a nonsensical page size; falling back to 4096
 * in that case is at worst wrong by a factor that no code path in
 * practice exercises, and at best a truthful answer on any system that
 * could have started this runtime in the first place.
 *
 * Windows: the corresponding value is SYSTEM_INFO.dwPageSize, obtained
 * from GetSystemInfo. That is the same API the reference JVM's
 * src/hotspot/os/windows/os_windows.cpp uses for
 * os::vm_page_size(), so the value this helper reports matches
 * HotSpot's on every Windows configuration, including the rare ARM64
 * configurations where the page size is 4 KiB, 16 KiB, or 64 KiB.
 *
 * MinGW has neither sysconf nor _SC_PAGESIZE. The previous revision
 * called them unconditionally and produced:
 *
 *     error: call to undeclared function 'sysconf'
 *     error: use of undeclared identifier '_SC_PAGESIZE'
 *
 * The two branches below replace that unconditional call with a
 * platform-specific query, both of which return the same value on
 * their own platform that HotSpot would have injected.
 */
int32_t __jnative_unsafe_page_size(void) {
#if defined(_WIN32)
    SYSTEM_INFO si;
    GetSystemInfo(&si);
    return (int32_t)si.dwPageSize;
#else
    long p = sysconf(_SC_PAGESIZE);
    return (p > 0) ? (int32_t)p : 4096;
#endif
}

/*
 * BIG_ENDIAN -- 1 if the native byte order is big-endian, 0 otherwise.
 *
 * The compiler's __BYTE_ORDER__ macro is the authoritative answer on
 * every toolchain this runtime targets. Clang and GCC both define it
 * whenever they are compiling for a target whose endianness is known
 * at compile time -- which is every target in use today -- so the
 * macro's absence would mean the build is targeting something
 * pathological, and the little-endian default below is only a fallback
 * for that case.
 *
 * MinGW defines __BYTE_ORDER__ and __ORDER_LITTLE_ENDIAN__ for its
 * x86_64 and aarch64 targets, so this function needs no platform
 * branch.
 */
int32_t __jnative_unsafe_big_endian(void) {
#if defined(__BYTE_ORDER__) && defined(__ORDER_BIG_ENDIAN__) \
    && (__BYTE_ORDER__ == __ORDER_BIG_ENDIAN__)
    return 1;
#else
    return 0;
#endif
}

/*
 * UNALIGNED_ACCESS -- 1 if the CPU tolerates unaligned reads and writes
 * of every primitive type, 0 otherwise.
 *
 * HotSpot sets this true on x86 and x86_64 (where unaligned access is
 * part of the base ISA), on aarch64 (where the architecture defines
 * unaligned normal-memory accesses as valid, though possibly slow),
 * and false on the strict-alignment architectures (SPARC, PA-RISC,
 * the MIPS strict mode). It is what tells Unsafe whether a Java-level
 * unaligned read can be lowered to a plain memory load or has to go
 * through the byte-by-byte reconstruction path.
 *
 * The __x86_64__ / __i386__ / __aarch64__ macros are defined by clang
 * and gcc for the same targets on every host, so this function also
 * needs no platform branch.
 */
int32_t __jnative_unsafe_unaligned_access(void) {
#if defined(__x86_64__) || defined(__i386__) || defined(__aarch64__)
    return 1;
#else
    return 0;
#endif
}

/*
 * DATA_CACHE_LINE_FLUSH_SIZE -- the granularity at which the
 * architecture can flush a range of a CPU cache line to persistent
 * memory.
 *
 * HotSpot reports 0 unless the VM was built with
 * -XX:+UseDataCacheLineFlush and the target provides the corresponding
 * instruction (currently only the PowerPC dcbz/dcbf family). The
 * intent of the field is to let Unsafe.allocateMemory and friends emit
 * extra fences for the caller's non-volatile memory, and the default
 * of 0 disables that path entirely.
 *
 * This runtime does not implement the cache-flush primitive at all,
 * which is exactly what 0 means: the caller will not attempt the flush
 * because the VM has told it the platform cannot do one. Any other
 * answer would be a lie that leads to a missing fence or to a crash
 * inside an unimplemented instruction.
 */
int32_t __jnative_unsafe_data_cache_line_flush_size(void) {
    return 0;
}
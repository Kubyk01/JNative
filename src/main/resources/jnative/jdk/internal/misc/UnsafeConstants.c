/*
 * Runtime companion for jdk.internal.misc.UnsafeConstants.
 *
 * HotSpot injects the values of the static fields of UnsafeConstants
 * (ADDRESS_SIZE0, PAGE_SIZE, BIG_ENDIAN, UNALIGNED_ACCESS,
 * DATA_CACHE_LINE_FLUSH_SIZE) at VM startup. The fields' Java-level
 * initializers are placeholders (0, 0, false, false, 0), and the VM
 * overwrites them before any Java code can observe them.
 *
 * A JNative image is compiled statically: the placeholder initializers
 * end up as literal zero stores in the generated IR for
 * UnsafeConstants.<clinit>, and there is no VM to overwrite them
 * afterwards. Unless something replaces those stores, every read of
 * the fields observes the placeholders, which breaks:
 *
 *   - Unsafe.ADDRESS_SIZE  -> 0
 *   - Unsafe.PAGE_SIZE     -> 0
 *   - Unsafe.DATA_CACHE_LINE_FLUSH_SIZE -> 0
 *   - Unsafe.unalignedAccess() -> false
 *
 * The first observable failure is Unsafe.allocateMemory: it aligns the
 * requested size with
 *
 *     (bytes + ADDRESS_SIZE - 1) & ~(ADDRESS_SIZE - 1)
 *
 * which for ADDRESS_SIZE == 0 evaluates to 0 for any input, and then
 * returns 0 without ever calling malloc. NativeBuffers.newNativeBuffer
 * stores that 0 in the NativeBuffer.address field, and the first
 * Unsafe.copyMemory into it dereferences NULL.
 *
 * The fix is to give UnsafeConstants.<clinit> a real body: the
 * LlvmFunctionEmitter special-cases the function by name and emits a
 * sequence of calls to the helpers below, each of which returns the
 * value HotSpot would have injected. The helpers are ordinary C
 * functions, so the values are computed at run time on the machine the
 * image actually executes on -- page size, endianness and alignment
 * tolerance are all queried from the platform rather than baked in.
 *
 * The helper signatures all return int32_t rather than the field's
 * final Java type. The emitter converts the int32 result to i1 for the
 * two boolean fields; keeping the C side uniform means the platform
 * probes never have to guess what LLVM type the caller expects.
 */

#include <stdint.h>
#include <unistd.h>

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
 * HotSpot reads this from os::vm_page_size(), which on Linux is the
 * value of sysconf(_SC_PAGESIZE) (equivalently getpagesize(3)). The
 * kernel reports the machine's actual page size, which is 4096 on the
 * common x86_64 configuration and 16384 or 65536 on some ARM and PPC
 * configurations. Querying it at run time, rather than assuming 4096,
 * keeps Unsafe's page-aligned memory operations correct on every
 * platform.
 *
 * A negative or zero result from sysconf would mean the platform is
 * reporting a nonsensical page size; falling back to 4096 in that case
 * is at worst wrong by a factor that no code path in practice
 * exercises, and at best a truthful answer on any system that could
 * have started this runtime in the first place.
 */
int32_t __jnative_unsafe_page_size(void) {
    long p = sysconf(_SC_PAGESIZE);
    return (p > 0) ? (int32_t)p : 4096;
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

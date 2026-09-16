#include <stdint.h>

/*
 * static native boolean VMSupportsCS8();
 *
 * Called from AtomicLong.<clinit> to decide whether the class can use a
 * plain volatile field for its value or must fall back to a
 * synchronized-path implementation. The check asks whether the VM
 * provides lock-free 8-byte compare-and-swap, which every platform
 * targeted by this runtime supports natively: on x86_64 the CMPXCHG8B
 * instruction (and its 16-byte successor) is part of the base ISA, and
 * __atomic_compare_exchange_n on int64_t compiles to a single lock-free
 * instruction. Returning true selects the lock-free path, which is the
 * path the rest of the runtime is built against (see Striped64's
 * weakCompareAndSetRelease__Striped64_Cell_JJ_Z, which relies on
 * lock-free 64-bit CAS).
 */
int32_t __jnative_fn_java_util_concurrent_atomic_AtomicLong_VMSupportsCS8___Z(void) {
    return 1;
}
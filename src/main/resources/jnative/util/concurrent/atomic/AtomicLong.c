#include <stdint.h>

#include "jnative_runtime.h"
/*
 * private static native boolean VMSupportsCS8();
 *
 * True iff the VM has a lock-free 8-byte compare-and-swap.
 *
 * Every platform this runtime targets supports it natively: on x86_64
 * the CMPXCHG8B instruction (and its 16-byte successor) is part of
 * the base ISA, and __atomic_compare_exchange_n on int64_t compiles
 * to a single lock-free instruction. On aarch64 the corresponding
 * primitive is LSE LDXP/STXP or the CASP extension, both of which are
 * likewise present in every platform revision this runtime supports.
 *
 * Returning true selects the lock-free path, which is the path the
 * rest of the runtime is built against: Striped64's
 * weakCompareAndSetRelease__Striped64_Cell_JJ_Z (see
 * jnative/lang/invoke/VarHandle.c) and LongAdder's own add() both
 * assume that an int64_t CAS is a single-instruction operation and
 * would be incorrect if the underlying primitive required a lock.
 */
int32_t __jnative_fn_java_util_concurrent_atomic_AtomicLong_VMSupportsCS8___Z(void) {
    return 1;
}
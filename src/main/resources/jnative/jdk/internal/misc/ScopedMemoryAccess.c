#include <stdint.h>
#include <stdatomic.h>

__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);

#define MEMORY_SESSION_STATE_OFFSET 8

#define MEMORY_SESSION_OPEN      0
#define MEMORY_SESSION_CLOSING  (-1)
#define MEMORY_SESSION_CLOSED   (-2)

int32_t __jnative_fn_jdk_internal_misc_ScopedMemoryAccess_closeScope0__Ljdk_internal_foreign_MemorySessionImpl__Z(
        void* self, void* session) {
    (void)self;
    if (session == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    int32_t* state = (int32_t*)((char*)session + MEMORY_SESSION_STATE_OFFSET);

    for (;;) {
        int32_t current = __atomic_load_n(state, __ATOMIC_SEQ_CST);

        if (current == MEMORY_SESSION_CLOSED) {
            return 1;
        }
        if (current != MEMORY_SESSION_OPEN) {
            return 0;
        }

        int32_t expected = MEMORY_SESSION_OPEN;
        if (__atomic_compare_exchange_n(state, &expected, MEMORY_SESSION_CLOSING,
                                        0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) {
            break;
        }
    }

    int32_t closing = MEMORY_SESSION_CLOSING;
    __atomic_compare_exchange_n(state, &closing, MEMORY_SESSION_CLOSED,
                                0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);

    return 1;
}

/*
 * private static native void registerNatives();
 *
 * Called from ScopedMemoryAccess.<clinit>. In the reference JDK this
 * hook binds the class's native methods (closeScope0, ...) to their C
 * implementations. This runtime resolves every native method through
 * its statically-linked __jnative_fn_<class>_<method>_<desc> symbol
 * emitted by the LLVM backend, so there is nothing to register. The
 * symbol must exist because the class's <clinit> emits a native call
 * to it.
 */
void __jnative_fn_jdk_internal_misc_ScopedMemoryAccess_registerNatives___V(void) {
}
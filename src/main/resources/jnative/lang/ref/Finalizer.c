#include <stdint.h>

#include "jnative_runtime.h"

/*
 * java.lang.ref.Finalizer native methods.
 *
 * Finalizer is the VM-level class that drives the finalization
 * machinery: a dedicated daemon thread (the finalizer thread) walks
 * the list of registered Finalizer objects and invokes each one's
 * `finalize()` method when the GC has determined that the object
 * behind it is unreachable.
 *
 * This runtime has no garbage collector and no reference processor.
 * The objects that HotSpot would eventually hand to the finalizer
 * thread never become "unreachable" in a way the runtime can observe —
 * every allocation is a libc malloc and every destruction is an
 * explicit free emitted by DestructorInserter at a statically
 * determined point. There is no moment at which the runtime learns
 * that an object has become garbage, and therefore no moment at which
 * it could decide to run a finalizer.
 *
 * Two natives are declared on java.lang.ref.Finalizer and both are
 * emitted as direct calls from the class's compiled bytecode:
 *
 *   - isFinalizationEnabled()  from <clinit>, to decide whether the
 *     finalizer thread should be started at all;
 *
 *   - reportComplete(Object)   from runFinalizer(JavaLangAccess),
 *     which the finalizer thread calls after every successful
 *     finalization to acknowledge that the Finalizer object has been
 *     fully processed.
 *
 * Both symbols must therefore exist at link time. Their bodies are
 * written so that the class initializes cleanly and the finalizer
 * thread, if it is ever started, terminates immediately rather than
 * spinning.
 */

int32_t __jnative_fn_java_lang_ref_Finalizer_isFinalizationEnabled___Z(void) {
    return 0;
}

void __jnative_fn_java_lang_ref_Finalizer_reportComplete__Ljava_lang_Object__V(
        void* finalizer)
{
    (void)finalizer;
}
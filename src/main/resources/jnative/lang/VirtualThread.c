#include <stdint.h>

/* ---------------------------------------------------------------------------
 * JVMTI notification that a virtual thread is being unmounted.
 *
 * The only purpose of this callback is to fire a JVMTI event. Since our
 * runtime never attaches a JVMTI agent, there is no observer to notify,
 * so the correct behaviour is to return immediately. If an agent were
 * present, this function would dispatch the event via the JVMTI env - the
 * "hide" flag tells the JVM whether the unmounted frame should be hidden
 * from stack traces.
 * ------------------------------------------------------------------------- */
void __jnative_fn_java_lang_VirtualThread_notifyJvmtiUnmount__Z_V(int32_t hide) {
    (void)hide;
}
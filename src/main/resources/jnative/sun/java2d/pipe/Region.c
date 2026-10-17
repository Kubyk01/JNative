#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.pipe.Region — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares one native:
 *
 *     private static native void initIDs();
 *
 * called unconditionally from Region.<clinit>. On HotSpot the reference
 * implementation caches the JNI field IDs of Region's instance fields
 * (lox, loy, hix, hiy, bands, children) so the native clipping code can
 * address them without repeated lookups.
 *
 * That caching is meaningless in this runtime: every read of every
 * field goes through the byte offset computed by
 * LlvmGlobalEmitter.getFieldOffset, and the JNI field ID machinery is
 * never consulted. The initIDs body was therefore replaced with an
 * empty stub: the class's static initializer completes, and every
 * subsequent read or write of a Region field uses the emitter-
 * computed offset exactly as every other class in the image does.
 *
 * ============================================================================
 * Why this cannot be left as a JNI native
 * ============================================================================
 *
 * Without this file the class's initIDs becomes a JNI native, which the
 * LLVM emitter turns into a trampoline that resolves the real
 * implementation through dlsym at run time. On a build whose JDK
 * libraries are not embedded that lookup returns NULL and the emitted
 * code calls __jnative_unresolved_slot with a diagnostic.
 *
 * A build that does embed libawt.so sees a different failure: the
 * library's JNI_OnLoad crashes (it calls (*env)->CallStaticBooleanMethod
 * during AWT_OnLoad, and that slot is NULL in this runtime's JNIEnv),
 * the loader marks the library unusable, and the trampoline either
 * resolves through dlsym to a broken implementation or fails outright.
 * Either way, Region.<clinit> cannot complete.
 *
 * A local no-op stub, compiled into the executable and reached directly
 * through the emitter's ordinary STATIC_CALL path, removes both failure
 * modes at once.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * initIDs is a STATIC native: no receiver argument, no parameters.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/pipe/Region") does not
 * strip a leading "java/" — the class is in the sun.* namespace — so
 * the file lives at "jnative/sun/java2d/pipe/Region.c".
 */

void __jnative_fn_sun_java2d_pipe_Region_initIDs___V(void) {
    /*
     * Intentionally empty. The runtime addresses every Region field
     * through its emitter-computed byte offset; no field-ID cache is
     * needed and none would be consulted even if it were populated.
     */
}

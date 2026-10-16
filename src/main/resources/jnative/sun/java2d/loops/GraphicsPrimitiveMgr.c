#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.GraphicsPrimitiveMgr — native entry point.
 *
 * The class declares a single static native, `initIDs`, called from
 * its own static initializer. On HotSpot this hook caches the JNI
 * field IDs of GraphicsPrimitive and its registry tables so the rest
 * of the primitive machinery can reach into those objects without
 * repeated lookups.
 *
 * This runtime accesses every field through its LLVM-computed byte
 * offset and never consults JNI field IDs, so the body is a no-op.
 * The symbol must nevertheless exist because the class's static
 * initializer emits a native call to it. The class itself is
 * reachable in the compiled image because every primitive subclass
 * calls GraphicsPrimitiveMgr.register(...) from its own static
 * initializer, and every one of those register calls references this
 * class.
 */
void __jnative_fn_sun_java2d_loops_GraphicsPrimitiveMgr_initIDs___V(void) {
}

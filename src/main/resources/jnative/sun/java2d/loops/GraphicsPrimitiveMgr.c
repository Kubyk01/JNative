#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.GraphicsPrimitiveMgr — native entry points.
 *
 * The class declares two native-both methods, both called from <clinit>:
 *
 *   initIDs — caches the JNI field IDs of the ten registration tables
 *             and twenty primitive classes. The JDK 21 signature
 *             contains 11 Class<?> parameters.
 *
 *   registerNativeLoops — registers the C functions of the drawing
 *             loops (Blit, MaskBlit, DrawLine, …) in the global
 *             GraphicsPrimitiveMgr table. Without it the Java layer
 *             (SunGraphics2D.validatePipe) cannot find the right
 *             primitive for a (SurfaceType, CompositeType) pair.
 *
 * In a headless runtime both methods are strict no-ops:
 *
 *   - JNI field IDs are not consulted by any native (all fields are
 *     read through emitter-computed offsets); there is nothing to
 *     cache;
 *
 *   - the real drawing primitives in this runtime always resolve on
 *     the Java side through the software tables (SunGraphics2D picks
 *     a sun.java2d.loops.* subclass and calls its Java method), and
 *     native registration is not needed. The accelerated pipelines
 *     (OpenGL, XRender, D3D) are never instantiated in this runtime
 *     because all of their isXXXAvailable() methods return false.
 *
 * Both symbols must exist because <clinit> of the class unconditionally
 * emits calls to them.
 */

/*
 * private static native void initIDs(Class<?> c1, ..., Class<?> c11);
 *
 * The 11 parameters are references to the classes whose JNI field IDs
 * HotSpot caches. The exact list: GraphicsPrimitive, SurfaceType,
 * CompositeType, Blit, MaskBlit, MaskFill, DrawLine, DrawRect,
 * FillRect, FillSpans, TransformHelper.
 *
 * This implementation takes them all as void* and ignores them. A
 * change in the number or composition of the parameters in a future
 * JDK release generates a different symbol and this function simply
 * never gets called; a new version of this file must then be built
 * against the new JDK.
 */
void __jnative_fn_sun_java2d_loops_GraphicsPrimitiveMgr_initIDs__Ljava_lang_Class_Ljava_lang_Class_Ljava_lang_Class_Ljava_lang_Class_Ljava_lang_Class_Ljava_lang_Class_Ljava_lang_Class_Ljava_lang_Class_Ljava_lang_Class_Ljava_lang_Class_Ljava_lang_Class__V(
        void* c1, void* c2, void* c3, void* c4, void* c5,
        void* c6, void* c7, void* c8, void* c9, void* c10, void* c11)
{
    (void)c1;  (void)c2;  (void)c3;  (void)c4;  (void)c5;
    (void)c6;  (void)c7;  (void)c8;  (void)c9;  (void)c10;
    (void)c11;
}

/*
 * private static native void registerNativeLoops();
 *
 * Registers the C functions of the primitives in the global table. In
 * this runtime the Java side never accesses the native table:
 * primitive resolution goes through GraphicsPrimitive.getPrimitiveType()
 * and the Java methods of the concrete subclasses (see LoopPipe and the
 * Draw* classes in jnative/sun/java2d/loops/). Native registration is
 * neither needed nor possible.
 */
void __jnative_fn_sun_java2d_loops_GraphicsPrimitiveMgr_registerNativeLoops___V(void) {
}

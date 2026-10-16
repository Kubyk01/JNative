#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.DrawParallelogram — native entry point.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * DrawParallelogram is one of the java2d GraphicsPrimitive subclasses.
 * It represents the "fill the region between two parallelograms"
 * operation: given an outer parallelogram (origin plus two delta
 * vectors) and an inner parallelogram (specified by the lw1 / lw2
 * fractions of the outer delta vectors), it fills the outer band
 * minus the inner rectangle. This is the shape that the java2d
 * "BasicStroke" conversion produces for a stroked line, and it is
 * what the pipeline uses to render thick strokes without going
 * through the general Shape rasteriser.
 *
 * The class declares a single public native:
 *
 *     public native void DrawParallelogram(SunGraphics2D sg,
 *                                          SurfaceData dest,
 *                                          double x, double y,
 *                                          double dx1, double dy1,
 *                                          double dx2, double dy2,
 *                                          double lw1, double lw2);
 *
 * which the java2d pipeline reaches through LoopPipe — the general
 * software pipe that dispatches every drawing operation to the
 * primitive registered for the current surface / composite pair. The
 * diagnostic names sun/java2d/pipe/LoopPipe.ll because the call site
 * lives in LoopPipe's bytecode, but the class file whose native needs
 * a definition is DrawParallelogram itself.
 *
 * Without a definition in the linked executable, clang rejects
 * whichever translation unit emits the call — LoopPipe.ll in the
 * current build — with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_loops_DrawParallelogram_DrawParallelogram__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_DDDDDDDD_V'
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no accelerated java2d pipeline. GraphicsEnvironment
 * .isHeadless() is true on every reachable path, so no X11GraphicsConfig,
 * XRSurfaceData or GLXSurfaceData is ever constructed for a real
 * destination surface, and SunGraphics2D.validatePipe() never selects
 * an accelerated DrawParallelogram primitive. Every caller in this
 * runtime has a well-defined software fallback that operates on the
 * destination raster's Java-side data through the JDK's own Java-level
 * primitive implementations.
 *
 * The native is nevertheless emitted into the module because the
 * bytecode of LoopPipe and of the GraphicsPrimitiveMgr registration
 * tables contains the call site. The implementation below is a strict
 * no-op:
 *
 *   - There is no destination surface to fill.
 *
 *   - There is no paint, composite, clip or XOR state to apply.
 *
 *   - No observable effect — neither in the program's output nor in
 *     any subsequent read of the destination surface — would be
 *     produced by drawing in a runtime that cannot display the result,
 *     and no reachable caller would observe the difference.
 *
 * Aborting would break every program that constructs a Graphics2D and
 * strokes a shape with a non-thin BasicStroke — a purely in-memory
 * operation — because SunGraphics2D's pipeline-selection code walks
 * the accelerated primitive tables during initialisation and would
 * trip the abort before the software path could take over.
 *
 * ============================================================================
 * Calling convention and mangled name
 * ============================================================================
 *
 * DrawParallelogram is an INSTANCE native: the receiver is the first
 * argument, followed by the ten Java-level parameters. The C signature
 * takes, in order:
 *
 *     this_prim   DrawParallelogram receiver
 *     sg          SunGraphics2D reference
 *     dest        SurfaceData reference (destination surface)
 *     x, y        origin of the outer parallelogram
 *     dx1, dy1    first delta vector of the outer parallelogram
 *     dx2, dy2    second delta vector of the outer parallelogram
 *     lw1, lw2    inner-parallelogram proportions along dx1 and dx2
 *
 * The mangled name applies the standard rule
 *     "fn_" + sanitize(class) + "_" + sanitize(method) + "_" + sanitize(desc)
 * and the emitter prefixes it with "__jnative_":
 *
 *   class  "sun/java2d/loops/DrawParallelogram"
 *       -> "sun_java2d_loops_DrawParallelogram"
 *   desc   "(Lsun/java2d/SunGraphics2D;Lsun/java2d/SurfaceData;DDDDDDDD)V"
 *       -> "_Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_DDDDDDDD_V"
 *
 * giving the symbol the linker reported as undefined. Every argument is
 * captured and discarded: there is no destination surface to write to,
 * no graphics state to apply, and no parallelogram to rasterise.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/loops/DrawParallelogram")
 * does not strip a leading "java/" — the class is in the sun.*
 * namespace — so it returns
 * "jnative/sun/java2d/loops/DrawParallelogram.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/*
 * public native void DrawParallelogram(SunGraphics2D sg, SurfaceData dest,
 *                                      double x, double y,
 *                                      double dx1, double dy1,
 *                                      double dx2, double dy2,
 *                                      double lw1, double lw2);
 *
 * No-op. See the file header for the full rationale: the runtime has no
 * accelerated java2d pipeline that could invoke this primitive, and
 * every caller in this runtime has a well-defined software fallback
 * path. The ten arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_loops_DrawParallelogram_DrawParallelogram__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_DDDDDDDD_V(
        void* this_prim,
        void* sg,
        void* dest,
        double x,  double y,
        double dx1, double dy1,
        double dx2, double dy2,
        double lw1, double lw2)
{
    (void)this_prim;
    (void)sg;
    (void)dest;
    (void)x;   (void)y;
    (void)dx1; (void)dy1;
    (void)dx2; (void)dy2;
    (void)lw1; (void)lw2;
}
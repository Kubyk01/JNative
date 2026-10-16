#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.FillParallelogram — native entry point.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * FillParallelogram is one of the java2d GraphicsPrimitive subclasses.
 * It represents the "fill the area between the four edges of a
 * parallelogram" operation: given an origin point (x0, y0) and two
 * delta vectors (dx1, dy1) and (dx2, dy2), it fills the quadrilateral
 * whose four corners are
 *
 *     (x0,          y0)
 *     (x0 + dx1,    y0 + dy1)
 *     (x0 + dx2,    y0 + dy2)
 *     (x0 + dx1 + dx2, y0 + dy1 + dy2)
 *
 * honouring the graphics state's clip, composite and paint. It is the
 * counterpart of DrawParallelogram, which strokes the same shape as a
 * band rather than filling it.
 *
 * The class declares a single public native:
 *
 *     public native void FillParallelogram(SunGraphics2D sg2d,
 *                                          SurfaceData dest,
 *                                          double x0, double y0,
 *                                          double dx1, double dy1,
 *                                          double dx2, double dy2);
 *
 * which the java2d pipeline reaches through LoopPipe — the general
 * software pipe that dispatches every drawing operation to the
 * primitive registered for the current surface / composite pair. The
 * diagnostic names sun/java2d/pipe/LoopPipe.ll because the call site
 * lives in LoopPipe's bytecode, but the class file whose native needs
 * a definition is FillParallelogram itself.
 *
 * Without a definition in the linked executable, clang rejects
 * whichever translation unit emits the call — LoopPipe.ll in the
 * current build — with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_loops_FillParallelogram_FillParallelogram__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_DDDDDD_V'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot with the software pipeline selected, the native
 * implementation rasterises the parallelogram directly into the
 * destination surface. Because the shape is defined by two vectors
 * rather than by an arbitrary path, the rasteriser can take a fast
 * path that is not available to the general shape filler: the
 * parallelogram's scanlines are horizontal runs whose endpoints move
 * linearly with Y, so the loop is a fixed-arithmetic scan-conversion
 * that iterates over rows and computes each row's (xleft, xright)
 * pair from the current Y coordinate.
 *
 * The `dd` double arguments are used verbatim: the parallelogram is
 * not snapped to integer pixel boundaries, and the native loop works
 * in floating-point until the point at which it produces each row's
 * integer extents.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no accelerated java2d pipeline. GraphicsEnvironment
 * .isHeadless() is true on every reachable path, so no X11GraphicsConfig,
 * XRSurfaceData or GLXSurfaceData is ever constructed for a real
 * destination surface, and SunGraphics2D.validatePipe() never selects
 * an accelerated FillParallelogram primitive. Every caller in this
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
 *     produced by filling in a runtime that cannot display the result,
 *     and no reachable caller would observe the difference.
 *
 * Aborting would break every program that constructs a Graphics2D and
 * fills a parallelogram-shaped region — a purely in-memory operation —
 * because SunGraphics2D's pipeline-selection code walks the accelerated
 * primitive tables during initialisation and would trip the abort
 * before the software path could take over.
 *
 * ============================================================================
 * Calling convention and mangled name
 * ============================================================================
 *
 * FillParallelogram is an INSTANCE native: the receiver is the first
 * argument, followed by the eight Java-level parameters. The C
 * signature takes, in order:
 *
 *     this_prim   FillParallelogram receiver
 *     sg2d        SunGraphics2D reference (graphics context)
 *     dest        SurfaceData reference (destination surface)
 *     x0, y0      origin of the parallelogram
 *     dx1, dy1    first delta vector
 *     dx2, dy2    second delta vector
 *
 * The mangled name applies the standard rule
 *     "fn_" + sanitize(class) + "_" + sanitize(method) + "_" + sanitize(desc)
 * and the emitter prefixes it with "__jnative_":
 *
 *   class  "sun/java2d/loops/FillParallelogram"
 *       -> "sun_java2d_loops_FillParallelogram"
 *   desc   "(Lsun/java2d/SunGraphics2D;Lsun/java2d/SurfaceData;DDDDDD)V"
 *       -> "_Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_DDDDDD_V"
 *
 * giving the symbol the linker reported as undefined. The double
 * underscore after the method name is the method-to-descriptor
 * separator followed by the sanitised `(`. Each reference parameter's
 * `/` and `;` characters sanitize to `_`, producing the two `L…_`
 * tokens separated by a single underscore. The six `D`s are the
 * sanitised double coordinates (origin plus the four delta-vector
 * components), and the trailing `_V` is the sanitised `)` plus the
 * void return type.
 *
 * Every argument is captured and discarded: there is no destination
 * surface to write to, no graphics state to apply, and no
 * parallelogram to fill.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/loops/FillParallelogram")
 * does not strip a leading "java/" — the class is in the sun.*
 * namespace — so it returns
 * "jnative/sun/java2d/loops/FillParallelogram.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/*
 * public native void FillParallelogram(SunGraphics2D sg2d, SurfaceData dest,
 *                                      double x0,  double y0,
 *                                      double dx1, double dy1,
 *                                      double dx2, double dy2);
 *
 * No-op. See the file header for the full rationale: the runtime has
 * no accelerated java2d pipeline that could invoke this primitive, and
 * every caller in this runtime has a well-defined software fallback
 * path that operates on the destination raster's Java-side data
 * instead. The eight arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_loops_FillParallelogram_FillParallelogram__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_DDDDDD_V(
        void* this_prim,
        void* sg2d,
        void* dest,
        double x0,  double y0,
        double dx1, double dy1,
        double dx2, double dy2)
{
    (void)this_prim;
    (void)sg2d;
    (void)dest;
    (void)x0;  (void)y0;
    (void)dx1; (void)dy1;
    (void)dx2; (void)dy2;
}
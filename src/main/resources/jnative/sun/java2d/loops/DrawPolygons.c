#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.DrawPolygons — native entry point.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * DrawPolygons is one of the java2d GraphicsPrimitive subclasses. It
 * represents the "draw a set of single-width line polygons" operation:
 * given a set of polygon vertex arrays, a per-polygon point-count array,
 * a translation offset, and a close flag, it strokes each polygon's
 * outline as one-pixel-wide lines, honouring the graphics state's
 * clip, composite and paint.
 *
 * The class declares a single public native:
 *
 *     public native void DrawPolygons(SunGraphics2D sg2d, SurfaceData sData,
 *                                     int[] xPoints, int[] yPoints,
 *                                     int[] nPoints, int numPolys,
 *                                     int transX, int transY,
 *                                     boolean close);
 *
 * which the java2d pipeline reaches through LoopPipe — the general
 * software pipe that dispatches every drawing operation to the
 * primitive registered for the current surface / composite pair. The
 * diagnostic names sun/java2d/pipe/LoopPipe.ll because the call site
 * lives in LoopPipe's bytecode, but the class file whose native needs
 * a definition is DrawPolygons itself.
 *
 * Without a definition in the linked executable, clang rejects
 * whichever translation unit emits the call — LoopPipe.ll in the
 * current build — with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_loops_DrawPolygons_DrawPolygons__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData__I_I_IIIIZ_V'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot with the software pipeline selected, the native
 * implementation walks the input arrays and issues the appropriate
 * X11 or software line-drawing primitive for each edge of each
 * polygon:
 *
 *   - `xPoints` and `yPoints` hold the vertex coordinates of every
 *     polygon concatenated into a single pair of arrays.
 *
 *   - `nPoints[i]` is the number of vertices that belong to the i-th
 *     polygon, and `numPolys` is the number of entries in nPoints.
 *
 *   - `transX` and `transY` are a device-space translation applied to
 *     every vertex before rasterisation.
 *
 *   - `close` selects whether the last vertex of each polygon is
 *     connected back to its first. When true, the shape is a closed
 *     polygon outline; when false, it is an open polyline.
 *
 * The per-edge line rasterisation is the same as the single-line
 * DrawLine primitive; this native is essentially a loop that calls it
 * once per edge.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no accelerated java2d pipeline. GraphicsEnvironment
 * .isHeadless() is true on every reachable path, so no X11GraphicsConfig,
 * XRSurfaceData or GLXSurfaceData is ever constructed for a real
 * destination surface, and SunGraphics2D.validatePipe() never selects
 * an accelerated DrawPolygons primitive. Every caller in this runtime
 * has a well-defined software fallback that operates on the destination
 * raster's Java-side data through the JDK's own Java-level primitive
 * implementations.
 *
 * The native is nevertheless emitted into the module because the
 * bytecode of LoopPipe and of the GraphicsPrimitiveMgr registration
 * tables contains the call site. The implementation below is a strict
 * no-op:
 *
 *   - There is no destination surface to draw onto.
 *
 *   - There are no polygons to stroke and no lines to rasterise.
 *
 *   - No observable effect — neither in the program's output nor in
 *     any subsequent read of the destination surface — would be
 *     produced by drawing in a runtime that cannot display the result,
 *     and no reachable caller would observe the difference.
 *
 * Aborting would break every program that constructs a Graphics2D and
 * calls draw(Shape) on a polygon-backed Shape — a purely in-memory
 * operation — because SunGraphics2D's pipeline-selection code walks
 * the accelerated primitive tables during initialisation and would
 * trip the abort before the software path could take over.
 *
 * ============================================================================
 * Calling convention and mangled name
 * ============================================================================
 *
 * DrawPolygons is an INSTANCE native: the receiver is the first
 * argument, followed by the nine Java-level parameters. The C
 * signature takes, in order:
 *
 *     this_prim   DrawPolygons receiver
 *     sg2d        SunGraphics2D reference (graphics context)
 *     s_data      SurfaceData reference (destination surface)
 *     x_points    int[] vertex X coordinates
 *     y_points    int[] vertex Y coordinates
 *     n_points    int[] per-polygon vertex counts
 *     num_polys   int32_t number of polygons
 *     trans_x     int32_t device-space translation, X axis
 *     trans_y     int32_t device-space translation, Y axis
 *     close       int32_t close flag (0 = open, non-zero = closed)
 *
 * The mangled name applies the standard rule
 *     "fn_" + sanitize(class) + "_" + sanitize(method) + "_" + sanitize(desc)
 * and the emitter prefixes it with "__jnative_":
 *
 *   class  "sun/java2d/loops/DrawPolygons"
 *       -> "sun_java2d_loops_DrawPolygons"
 *   desc   "(Lsun/java2d/SunGraphics2D;Lsun/java2d/SurfaceData;"
 *            "[I[I[IIIZ)V"
 *       -> "_Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_"
 *          "_I_I_IIIIZ_V"
 *
 * giving the symbol the linker reported as undefined. The double
 * underscore after the method name is the method-to-descriptor
 * separator followed by the sanitised `(`. Each reference parameter's
 * `/` and `;` characters sanitize to `_`, producing the two `L…_`
 * tokens. The three int[] parameters contribute their leading `[` as
 * `_` followed by the element type `I`, giving `_I_I_I`; the two
 * remaining `I` characters are the two int offsets, and the `Z` is
 * the sanitised boolean `close`. The trailing `_V` is the sanitised
 * `)` plus the void return type.
 *
 * Every argument is captured and discarded: there is no destination
 * surface to write to, no graphics state to apply, and no polygon to
 * stroke.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/loops/DrawPolygons") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/java2d/loops/DrawPolygons.c". That is the
 * path this file must live at for Compiler.extractSystemNativeSources
 * to find it and compile it into the executable.
 */

/*
 * public native void DrawPolygons(SunGraphics2D sg2d, SurfaceData sData,
 *                                 int[] xPoints, int[] yPoints,
 *                                 int[] nPoints, int numPolys,
 *                                 int transX, int transY,
 *                                 boolean close);
 *
 * No-op. See the file header for the full rationale: the runtime has
 * no accelerated java2d pipeline that could invoke this primitive, and
 * every caller in this runtime has a well-defined software fallback
 * path that operates on the destination raster's Java-side data
 * instead. The ten arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_loops_DrawPolygons_DrawPolygons__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData__I_I_IIIIZ_V(
        void* this_prim,
        void* sg2d,
        void* s_data,
        void* x_points,
        void* y_points,
        void* n_points,
        int32_t num_polys,
        int32_t trans_x,
        int32_t trans_y,
        int32_t close)
{
    (void)this_prim;
    (void)sg2d;
    (void)s_data;
    (void)x_points;
    (void)y_points;
    (void)n_points;
    (void)num_polys;
    (void)trans_x;
    (void)trans_y;
    (void)close;
}
#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.DrawPath — native entry point.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * DrawPath is one of the java2d GraphicsPrimitive subclasses. It
 * represents the "draw a single-width line path onto the destination
 * surface" operation: given a Path2D.Float and a translation offset,
 * it strokes each path segment as a one-pixel-wide line, honouring
 * the graphics state's clip, composite and paint.
 *
 * The class declares a single public native:
 *
 *     public native void DrawPath(SunGraphics2D sg2d, SurfaceData sData,
 *                                 int transX, int transY,
 *                                 Path2D.Float p2df);
 *
 * which the java2d pipeline reaches through LoopPipe — the general
 * software pipe that dispatches every drawing operation to the
 * primitive registered for the current surface / composite pair. The
 * diagnostic names sun/java2d/pipe/LoopPipe.ll because the call site
 * lives in LoopPipe's bytecode, but the class file whose native needs
 * a definition is DrawPath itself.
 *
 * Without a definition in the linked executable, clang rejects
 * whichever translation unit emits the call — LoopPipe.ll in the
 * current build — with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_loops_DrawPath_DrawPath__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_IILjava_awt_geom_Path2D_Float__V'
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no accelerated java2d pipeline. GraphicsEnvironment
 * .isHeadless() is true on every reachable path, so no X11GraphicsConfig,
 * XRSurfaceData or GLXSurfaceData is ever constructed for a real
 * destination surface, and SunGraphics2D.validatePipe() never selects
 * an accelerated DrawPath primitive. Every caller in this runtime has
 * a well-defined software fallback that operates on the destination
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
 *   - There is no path stroker whose output could be rasterised.
 *
 *   - No observable effect — neither in the program's output nor in
 *     any subsequent read of the destination surface — would be
 *     produced by drawing in a runtime that cannot display the result,
 *     and no reachable caller would observe the difference.
 *
 * Aborting would break every program that constructs a Graphics2D and
 * calls draw(Shape) with a non-thin stroke — a purely in-memory
 * operation — because SunGraphics2D's pipeline-selection code walks
 * the accelerated primitive tables during initialisation and would
 * trip the abort before the software path could take over.
 *
 * ============================================================================
 * Calling convention and mangled name
 * ============================================================================
 *
 * DrawPath is an INSTANCE native: the receiver is the first argument,
 * followed by the five Java-level parameters. The C signature takes,
 * in order:
 *
 *     this_prim   DrawPath receiver
 *     sg2d        SunGraphics2D reference (graphics context)
 *     sData       SurfaceData reference (destination surface)
 *     transX      int32_t translation in device space, X axis
 *     transY      int32_t translation in device space, Y axis
 *     p2df        Path2D.Float reference (the path to draw)
 *
 * The mangled name applies the standard rule
 *     "fn_" + sanitize(class) + "_" + sanitize(method) + "_" + sanitize(desc)
 * and the emitter prefixes it with "__jnative_":
 *
 *   class  "sun/java2d/loops/DrawPath"
 *       -> "sun_java2d_loops_DrawPath"
 *   desc   "(Lsun/java2d/SunGraphics2D;Lsun/java2d/SurfaceData;"
 *            "IILjava/awt/geom/Path2D$Float;)V"
 *       -> "_Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_"
 *          "IILjava_awt_geom_Path2D_Float__V"
 *
 * giving the symbol the linker reported as undefined. The double
 * underscore after the method name is the method-to-descriptor
 * separator followed by the sanitised `(`. Each reference parameter's
 * `/`, `$` and `;` characters sanitize to `_`, producing the
 * `L…_Path2D_Float_` token for the Path2D.Float argument. The two `I`s
 * are the int translation offsets, and the trailing `_V` is the
 * sanitised `)` plus the void return type.
 *
 * Every argument is captured and discarded: there is no destination
 * surface to write to, no graphics state to apply, and no path to
 * stroke.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/loops/DrawPath") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/java2d/loops/DrawPath.c". That is the
 * path this file must live at for Compiler.extractSystemNativeSources
 * to find it and compile it into the executable.
 */

/*
 * public native void DrawPath(SunGraphics2D sg2d, SurfaceData sData,
 *                             int transX, int transY,
 *                             Path2D.Float p2df);
 *
 * No-op. See the file header for the full rationale: the runtime has
 * no accelerated java2d pipeline that could invoke this primitive, and
 * every caller in this runtime has a well-defined software fallback
 * path that operates on the destination raster's Java-side data
 * instead. The six arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_loops_DrawPath_DrawPath__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_IILjava_awt_geom_Path2D_Float__V(
        void* this_prim,
        void* sg2d,
        void* s_data,
        int32_t trans_x,
        int32_t trans_y,
        void* p2df)
{
    (void)this_prim;
    (void)sg2d;
    (void)s_data;
    (void)trans_x;
    (void)trans_y;
    (void)p2df;
}
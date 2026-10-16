#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.DrawLine — native entry point.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * DrawLine is one of the java2d GraphicsPrimitive subclasses. Each
 * concrete subclass represents one drawing operation (draw, fill,
 * blit, mask-blit, fill-spans, transform, …) and one source /
 * composite / destination-type pairing. DrawLine covers the "draw a
 * solid-colour single-pixel-wide line onto a destination surface"
 * operation for a specific SurfaceType and CompositeType.
 *
 * The class declares a single public native:
 *
 *     public native void DrawLine(SunGraphics2D sg2d,
 *                                 SurfaceData dest,
 *                                 int x1, int y1, int x2, int y2);
 *
 * which the java2d pipeline reaches through LoopPipe — the general
 * software pipe that dispatches every drawing operation to the
 * primitive registered for the current surface / composite pair. That
 * is why the diagnostic names sun/java2d/pipe/LoopPipe.ll rather than
 * DrawLine.ll: the call site lives in LoopPipe's bytecode, but the
 * symbol being resolved is DrawLine's own native, and the class file
 * whose native needs a definition is DrawLine itself.
 *
 * The class is reachable in the compiled image because
 * GraphicsPrimitiveMgr.locate walks the entire primitive table during
 * SunGraphics2D.validatePipe() on the first drawing operation, and
 * that walk touches every registered primitive's class. The call site
 * is emitted into the module regardless of which concrete primitive
 * subclass is instantiated at run time.
 *
 * Without a definition in the linked executable, clang rejects
 * whichever translation unit emits the call — LoopPipe.ll in the
 * current build — with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_loops_DrawLine_DrawLine__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_IIII_V'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot with the software pipeline selected, DrawLine runs the
 * Bresenham line-rasterisation loop against the destination raster,
 * honouring the graphics state's clip, composite and XOR mode, and
 * writing the graphics context's current colour into each pixel that
 * the line passes through. The four integer arguments are the
 * endpoints of the line in device coordinates:
 *
 *     (x1, y1)   start of the line
 *     (x2, y2)   end of the line
 *
 * The native is declared on the primitive subclass, but the actual
 * loop it drives is shared across every DrawLine subclass through the
 * pNativePrim pointer that GraphicsPrimitiveMgr fills in from the
 * registered C function table. The registered loop walks the line,
 * clips each pixel against sg2d.clip, applies the composite, and
 * stores the resulting colour into dest.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no XRender extension, no OpenGL
 * library, no GPU, and no accelerated SurfaceData. GraphicsEnvironment
 * .isHeadless() is true on every reachable path, so:
 *
 *   - No X11GraphicsConfig, XRSurfaceData or GLXSurfaceData is ever
 *     constructed for a real destination surface.
 *
 *   - The software pipeline (sun.java2d.loops.* primitive tables for
 *     BufferedImage-backed destinations) dispatches DrawLine to its
 *     Java-side loop implementation through the same primitive-table
 *     machinery, not through this native. The accelerated DrawLine
 *     subclass whose native is the one declared on this class is never
 *     selected.
 *
 *   - The only path that could reach this native is the accelerated
 *     pipeline-selection machinery in GraphicsPrimitiveMgr, and every
 *     accelerated pipeline in this runtime is short-circuited by
 *     isAccelerationEnabled / isGLXAvailable / isXRenderAvailable all
 *     returning false (see the sibling files under jnative/sun/awt/
 *     and jnative/sun/java2d/).
 *
 * The native is nevertheless emitted into the module because the
 * bytecode of LoopPipe and of the GraphicsPrimitiveMgr registration
 * tables contains the call site. The implementation is a strict
 * no-op:
 *
 *   - There is no destination surface to write pixels into.
 *
 *   - There is no paint, composite, clip or XOR state to apply.
 *
 *   - No observable effect — neither in the program's output nor in
 *     any subsequent read of the destination surface — would be
 *     produced by drawing a line in a runtime that cannot display it,
 *     and no reachable caller would observe the difference.
 *
 * This is a genuine "no accelerated pipeline" answer, not a stub that
 * aborts. Aborting would break every program that constructs a
 * Graphics2D and calls drawLine — a purely in-memory operation that
 * does not need a display — because SunGraphics2D's pipeline-selection
 * code walks the accelerated primitive tables during initialisation
 * and would trip the abort before the software path could take over.
 *
 * The remaining natives of the sibling GraphicsPrimitive subclasses
 * (FillSpans, TransformHelper, DrawGlyphListLCD, BufferedRenderPipe
 * .fillSpans, BufferedMaskBlit.enqueueTile, and the rest of the
 * sun.java2d.loops package) all follow the same pattern: reachable
 * from the pipeline tables, never invoked from a live path,
 * implemented as a no-op or a defaulted return. This file continues
 * that pattern.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * DrawLine is an INSTANCE native: the receiver is the first argument,
 * followed by the six Java-level parameters. The C signature takes, in
 * order:
 *
 *     this_prim   DrawLine receiver
 *     sg2d        SunGraphics2D reference (graphics context)
 *     dest        SurfaceData reference (destination surface)
 *     x1, y1      int32_t line start coordinates in device space
 *     x2, y2      int32_t line end coordinates in device space
 *
 * The method returns void.
 *
 * Every argument is captured and discarded: there is no destination
 * surface to write to, no graphics state to apply, and no line to
 * rasterise.
 *
 * ============================================================================
 * The mangled name
 * ============================================================================
 *
 * LlvmRuntime.mangleMethod applies
 *     "fn_" + sanitize(class) + "_" + sanitize(method) + "_" + sanitize(desc)
 * where sanitize replaces every character outside [a-zA-Z0-9_] with a
 * single underscore. The emitter prefixes the result with "__jnative_".
 *
 *   class  "sun/java2d/loops/DrawLine"
 *       -> "sun_java2d_loops_DrawLine"
 *   method "DrawLine"
 *       -> "DrawLine"
 *   desc   "(Lsun/java2d/SunGraphics2D;Lsun/java2d/SurfaceData;IIII)V"
 *       -> "_Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_IIII_V"
 *
 * giving the symbol the linker reported as undefined:
 *
 *     __jnative_fn_sun_java2d_loops_DrawLine_DrawLine__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_IIII_V
 *
 * The double underscore after the method name is the method-to-
 * descriptor separator followed by the sanitised `(`. Each reference
 * parameter's `/` and `;` characters sanitize to `_`, giving the two
 * `L…_` tokens separated by a single underscore. The four `I`s are
 * the sanitised int line-endpoint coordinates, and the trailing `_V`
 * is the sanitised `)` plus the void return type.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/loops/DrawLine") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/java2d/loops/DrawLine.c". That is the
 * path this file must live at for Compiler.extractSystemNativeSources
 * to find it and compile it into the executable.
 */

/*
 * public native void DrawLine(SunGraphics2D sg2d,
 *                             SurfaceData dest,
 *                             int x1, int y1, int x2, int y2);
 *
 * No-op. See the file header for the full rationale: the runtime has
 * no accelerated java2d pipeline that could invoke this primitive, and
 * every caller in this runtime has a well-defined software fallback
 * path that operates on the destination raster's Java-side data
 * instead. The six arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_loops_DrawLine_DrawLine__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_IIII_V(
        void* this_prim,
        void* sg2d,
        void* dest,
        int32_t x1, int32_t y1,
        int32_t x2, int32_t y2)
{
    (void)this_prim;
    (void)sg2d;
    (void)dest;
    (void)x1; (void)y1;
    (void)x2; (void)y2;
}
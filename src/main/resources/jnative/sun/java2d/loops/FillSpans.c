#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.FillSpans — native entry point.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * FillSpans is one of the java2d GraphicsPrimitive subclasses. Each
 * concrete subclass represents one drawing operation (fill, draw,
 * blit, mask-blit, fill-spans, …) and one destination/backend pairing
 * (IntArgb vs. ByteIndexed, X11 vs. OpenGL vs. software, …). The
 * class declares a single private native:
 *
 *     private native void FillSpans(SunGraphics2D sg2d,
 *                                   SurfaceData dest,
 *                                   int pixel,
 *                                   long pIterator,
 *                                   SpanIterator si);
 *
 * which the public invoker method FillSpans(SunGraphics2D, SurfaceData,
 * SpanIterator) calls, passing the graphics context's current colour
 * in `pixel`, the SpanIterator's raw native function-block address in
 * `pIterator`, and the SpanIterator itself in `si`.
 *
 * The call site is emitted into the module unconditionally whenever the
 * class is reachable — the bytecode contains it regardless of which
 * concrete primitive subclass is instantiated at run time. The class
 * is reachable because GraphicsPrimitiveMgr walks the entire primitive
 * table during SunGraphics2D.validatePipe() on the first drawing
 * operation, and that walk touches every registered primitive's class.
 *
 * Without a definition in the linked executable, clang rejects
 * sun/java2d/loops/FillSpans.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_loops_FillSpans_FillSpans__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_IJLsun_java2d_pipe_SpanIterator__V'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot with an accelerated pipeline selected, FillSpans walks the
 * SpanIterator's native function block. Each call to the block's
 * nextSpan(pIterator, spanbox) entry point fills `spanbox` with the
 * four coordinates of one horizontal span (x1, y1, x2, y2), and
 * FillSpans fills the corresponding pixel run in the destination
 * surface with `pixel`, honouring the graphics state's clip, composite
 * and XOR mode:
 *
 *     for (;;) {
 *         if (!nextSpan(pIterator, spanbox)) break;
 *         if (spanbox[1] >= spanbox[3]) continue;
 *         for (int y = spanbox[1]; y < spanbox[3]; y++) {
 *             fill from spanbox[0] to spanbox[2] at row y
 *         }
 *     }
 *
 * The concrete implementation of each FillSpans subclass differs only
 * in how the pixel run is written (a bulk memset for IntArgb, a
 * colour-indexed lookup for ByteIndexed, a 2D blit for X11, …).
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
 *     BufferedImage-backed destinations) uses Draw* / Fill* primitives
 *     that operate directly on the raster's Java-side data through the
 *     per-pixel loops in the JDK's own Java code, not through this
 *     native.
 *
 *   - The only path that could reach FillSpans is the accelerated
 *     pipeline-selection machinery in GraphicsPrimitiveMgr, and every
 *     accelerated pipeline in this runtime is short-circuited by
 *     isAccelerationEnabled / isGLXAvailable / isXRenderAvailable all
 *     returning false (see the sibling files under jnative/sun/awt/
 *     and jnative/sun/java2d/).
 *
 * The native is nevertheless emitted into the module because the
 * bytecode of the public invoker method contains the call site. The
 * implementation is a strict no-op:
 *
 *   - There is no destination surface to fill.
 *
 *   - There is no paint, composite, clip or XOR state to apply.
 *
 *   - No observable effect — neither in the program's output nor in
 *     any subsequent read of the destination surface — would be
 *     produced by filling spans in a runtime that cannot display
 *     them, and no reachable caller would observe the difference.
 *
 * This is a genuine "no accelerated pipeline" answer, not a stub that
 * aborts. Aborting would break every program that constructs a
 * Graphics2D and calls fill(Shape) — a purely in-memory operation that
 * does not need a display — because SunGraphics2D's pipeline-selection
 * code walks the accelerated primitive tables during initialisation
 * and would trip the abort before the software path could take over.
 *
 * The remaining natives of the sibling GraphicsPrimitive subclasses
 * (DrawGlyphListLCD, BufferedRenderPipe.fillSpans, BufferedMaskBlit
 * .enqueueTile) all follow the same pattern: reachable from the
 * pipeline tables, never invoked from a live path, implemented as a
 * no-op or a defaulted return. This file continues that pattern.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * FillSpans is an INSTANCE native: the receiver is the first argument,
 * followed by the five Java-level parameters. The Java declaration is
 *
 *     private native void FillSpans(SunGraphics2D sg2d,
 *                                   SurfaceData dest,
 *                                   int pixel,
 *                                   long pIterator,
 *                                   SpanIterator si);
 *
 * so the C signature takes the FillSpans receiver, then two object
 * references (SunGraphics2D, SurfaceData), then int32_t `pixel`, then
 * int64_t `pIterator`, then the SpanIterator object reference. The
 * method returns void.
 *
 * Every argument is captured and discarded: there is no destination
 * surface to write to, no colour to write, and no iterator whose
 * function block could be called.
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
 *   class  "sun/java2d/loops/FillSpans"
 *       -> "sun_java2d_loops_FillSpans"
 *   method "FillSpans"
 *       -> "FillSpans"
 *   desc   "(Lsun/java2d/SunGraphics2D;Lsun/java2d/SurfaceData;I"
 *            "JLsun/java2d/pipe/SpanIterator;)V"
 *       -> "_Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_IJLsun_java2d_pipe_SpanIterator__V"
 *
 * giving the symbol the linker reported as undefined:
 *
 *     __jnative_fn_sun_java2d_loops_FillSpans_FillSpans__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_IJLsun_java2d_pipe_SpanIterator__V
 *
 * The double underscore after the method name is the method-to-
 * descriptor separator followed by the sanitised `(`. Each reference
 * parameter's `/` and `;` characters sanitize to `_`, giving the
 * `L…_` tokens. The `I` and `J` between them are the sanitised int and
 * long primitive parameters, and the trailing `_V` is the sanitised
 * `)` plus the void return type.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/loops/FillSpans") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/java2d/loops/FillSpans.c". That is the
 * path this file must live at for Compiler.extractSystemNativeSources
 * to find it and compile it into the executable.
 */

/*
 * private native void FillSpans(SunGraphics2D sg2d,
 *                               SurfaceData dest,
 *                               int pixel,
 *                               long pIterator,
 *                               SpanIterator si);
 *
 * No-op. See the file header for the full rationale: the runtime has
 * no accelerated java2d pipeline that could invoke this primitive, and
 * every caller in this runtime has a well-defined software fallback
 * path that operates on the destination raster's Java-side data
 * instead. The five arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_loops_FillSpans_FillSpans__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_IJLsun_java2d_pipe_SpanIterator__V(
        void* this_prim,
        void* sg2d,
        void* dest,
        int32_t pixel,
        int64_t p_iterator,
        void* si)
{
    (void)this_prim;
    (void)sg2d;
    (void)dest;
    (void)pixel;
    (void)p_iterator;
    (void)si;
}
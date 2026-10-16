#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.DrawGlyphListLCD — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares one native method:
 *
 *     public native void DrawGlyphListLCD(SunGraphics2D sg2d,
 *                                         SurfaceData dest,
 *                                         GlyphList srcData,
 *                                         int fromGlyph,
 *                                         int toGlyph);
 *
 * It is called through the Java2D GlyphListLoopPipe whenever the active
 * text renderer needs to draw a run of LCD-anti-aliased glyphs onto the
 * destination surface. The pipe is instantiated by SunGraphics2D's
 * validatePipe() when the caller has selected LCD text anti-aliasing
 * (SunHints.INTVAL_TEXT_ANTIALIAS_LCD_HRGB or _LCD_VRGB). Because the
 * pipe class references this primitive, the reachability walk pulls
 * DrawGlyphListLCD into the compiled image, and the bytecode then emits
 * a call site for the native. Without a definition in the linked
 * executable, clang rejects sun/java2d/pipe/GlyphListLoopPipe.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_loops_DrawGlyphListLCD_DrawGlyphListLCD__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_Lsun_font_GlyphList_II_V'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, DrawGlyphListLCD is a GraphicsPrimitive that drives the
 * native C loop DrawGlyphListLCD in
 * src/java.desktop/share/native/libawt/awt/medialib/… or in the
 * corresponding software-pipeline source, depending on the target
 * surface:
 *
 *   - It reads the glyph run's per-glyph advance and LCD subpixel
 *     layout (three 8-bit coverage values per pixel, one for each of
 *     R, G, B) from the GlyphList's cached glyph images.
 *
 *   - For each glyph in the range [fromGlyph, toGlyph), it blends the
 *     glyph's LCD subpixel mask into the destination surface using the
 *     graphics state's current composite and paint.
 *
 *   - The GlyphList abstraction handles glyph caching: the first time a
 *     (font, size, AA mode, glyph code) tuple is drawn, its bitmap is
 *     rasterised and cached; subsequent draws of the same tuple reuse
 *     the cache.
 *
 * The two integer arguments delimit the slice of the GlyphList's cached
 * glyph array to render. A caller with a multi-glyph run can split it
 * into several calls to amortise setup cost; the loop itself only cares
 * about the [fromGlyph, toGlyph) range.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no display, no X11 connection, no GPU, and no
 * software rasteriser pipeline. GraphicsEnvironment.isHeadless() is
 * true on every reachable path, so:
 *
 *   - No X11GraphicsConfig, XRSurfaceData or GLXSurfaceData is ever
 *     constructed with a real destination surface.
 *
 *   - No SunGraphics2D whose text antialias hint is LCD_HRGB or
 *     LCD_VRGB is ever created by any reachable code, because the AWT
 *     constructs such a Graphics2D only when a real display is present.
 *
 *   - No GlyphList is ever materialised: the entire glyph-caching and
 *     rasterisation machinery is behind the same headless guard.
 *
 * The native is nevertheless emitted into the module because the call
 * site exists in GlyphListLoopPipe's bytecode. Its implementation is a
 * strict no-op:
 *
 *   - There is no destination surface to write pixels to.
 *
 *   - There is no paint, composite, or clip state to apply.
 *
 *   - No observable effect — neither in the program's output nor in
 *     any subsequent read of the destination surface — would be
 *     produced by drawing LCD text on a runtime that cannot display
 *     it.
 *
 * This is a genuine "no display" answer, not a stub that aborts.
 * Aborting would break every program that constructs a
 * BufferedImage-backed Graphics2D with LCD anti-aliasing enabled —
 * a purely in-memory operation that does not need a display — because
 * SunGraphics2D's validatePipe() consults the primitive table on every
 * text draw and would trip the abort before any pixels could be
 * rasterised. The no-op lets the Java-side pipeline fall through to the
 * software renderer that the runtime actually supports.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * DrawGlyphListLCD is an INSTANCE native: the receiver is the first
 * argument, followed by the five Java-level parameters. The Java
 * declaration is
 *
 *     public native void DrawGlyphListLCD(SunGraphics2D sg2d,
 *                                         SurfaceData dest,
 *                                         GlyphList srcData,
 *                                         int fromGlyph,
 *                                         int toGlyph);
 *
 * so the C signature takes the DrawGlyphListLCD receiver, then three
 * object references (SunGraphics2D, SurfaceData, GlyphList), then two
 * int32_t parameters. Every argument is captured and discarded.
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
 *   class  "sun/java2d/loops/DrawGlyphListLCD"
 *       -> "sun_java2d_loops_DrawGlyphListLCD"
 *   method "DrawGlyphListLCD"
 *       -> "DrawGlyphListLCD"
 *   desc   "(Lsun/java2d/SunGraphics2D;Lsun/java2d/SurfaceData;"
 *            "Lsun/font/GlyphList;II)V"
 *       -> "_Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_Lsun_font_GlyphList_II_V"
 *
 * giving the symbol the linker reported as undefined:
 *
 *     __jnative_fn_sun_java2d_loops_DrawGlyphListLCD_DrawGlyphListLCD__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_Lsun_font_GlyphList_II_V
 *
 * The double underscore after the method name is the method-to-
 * descriptor separator followed by the sanitised `(`. Each reference
 * parameter's `/` and `;` characters sanitize to `_`, giving the three
 * `L…_` tokens separated by single underscores. The trailing `_II_V`
 * is the sanitised `)`, the two int parameters, and the void return.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/loops/DrawGlyphListLCD")
 * does not strip a leading "java/" — the class is in the sun.*
 * namespace — so it returns
 * "jnative/sun/java2d/loops/DrawGlyphListLCD.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/*
 * public native void DrawGlyphListLCD(SunGraphics2D sg2d,
 *                                     SurfaceData dest,
 *                                     GlyphList srcData,
 *                                     int fromGlyph,
 *                                     int toGlyph);
 *
 * No-op. See the file header for the full rationale: the runtime has no
 * display and no rasteriser pipeline that could render the glyphs, so
 * the call has no observable effect. The five arguments are captured
 * and discarded.
 */
void __jnative_fn_sun_java2d_loops_DrawGlyphListLCD_DrawGlyphListLCD__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_Lsun_font_GlyphList_II_V(
        void* this_prim,
        void* sg2d,
        void* dest,
        void* src_data,
        int32_t from_glyph,
        int32_t to_glyph)
{
    (void)this_prim;
    (void)sg2d;
    (void)dest;
    (void)src_data;
    (void)from_glyph;
    (void)to_glyph;
}
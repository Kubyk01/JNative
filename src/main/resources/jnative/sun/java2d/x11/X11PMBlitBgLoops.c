#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.x11.X11PMBlitBgLoops — native entry point.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * X11PMBlitBgLoops is the X11-specific subclass of
 * sun.java2d.loops.BlitBg. It accelerates background blits between two
 * Pixmap-backed surfaces of X11 types — the onscreen X11 surface and
 * the offscreen X11 pixmap surfaces that X11OffScreenImage creates.
 *
 * The class declares a single native:
 *
 *     private native void nativeBlitBg(long srcData, long dstData,
 *                                      long xgc, int pixel,
 *                                      int sx, int sy,
 *                                      int dx, int dy,
 *                                      int w, int h);
 *
 * which the inherited BlitBg invoker method calls after acquiring the
 * AWT lock and obtaining a graphics context from the destination
 * X11SurfaceData. The native is emitted into the module because the
 * inherited BlitBg invoker's bytecode contains the call site, and the
 * call site is unconditional with respect to whether the primitive is
 * ever dispatched to at run time.
 *
 * The class is reachable in the compiled image because its own
 * register() method is called from X11SurfaceData's static
 * initializer, which the java2d pipeline-selection machinery touches
 * during SunGraphics2D.validatePipe() on the first drawing operation.
 * That initializer does not require a live X11 connection to run; it
 * simply installs the X11 primitives into the global table used by
 * GraphicsPrimitiveMgr, and the table walk itself is what pulls the
 * class into the emitted module.
 *
 * Without a definition in the linked executable, clang rejects
 * sun/java2d/x11/X11PMBlitBgLoops.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_x11_X11PMBlitBgLoops_nativeBlitBg__JJJIIIIIII_V'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot with an X11 display and the accelerated X11 pipeline
 * selected, nativeBlitBg drives a single X11 copy operation that
 * combines a solid background fill with a blit of the source's
 * overlapping pixels:
 *
 *   1. It sets the destination graphics context's foreground to
 *      `pixel` (the destination's own pixel encoding of the caller's
 *      background colour, obtained by dst.pixelFor(bgColor) in the
 *      Java-level invoker).
 *
 *   2. It issues an XFillRectangle on the destination's drawable
 *      covering the target rectangle [dx, dy, w, h), which fills the
 *      entire destination region with the background colour.
 *
 *   3. It issues an XCopyArea from the source's drawable to the
 *      destination's drawable, using the same graphics context,
 *      transferring the source rectangle [sx, sy, w, h) on top of the
 *      freshly filled destination rectangle.
 *
 * The net effect — a background-fill followed by a copy of the
 * source region's pixels — is what BlitBg's contract requires: the
 * parts of the destination rectangle that the source does not cover
 * (which, in the Pixmap-to-Pixmap case, never happens because the
 * source and destination rectangles are always the same size) are
 * filled with the background colour, and the covered parts are
 * replaced by the source pixels.
 *
 * The X11 C-side implementation lives in the JDK's own libawt_xawt
 * sources; this runtime does not link them.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no display, and no X server to
 * copy pixels between. GraphicsEnvironment.isHeadless() is true on
 * every reachable path, and X11GraphicsEnvironment.
 * isAccelerationEnabled() returns false, so the accelerated X11
 * pipeline is never selected by SunGraphics2D.validatePipe().
 * Consequently no X11SurfaceData is ever constructed with a live
 * GraphicsConfig, no X11PMBlitBgLoops instance is ever created through
 * the normal path, and this native is never invoked from a live path.
 *
 * The native is nevertheless emitted into the module because the
 * bytecode of the inherited BlitBg invoker references it. The
 * implementation below is a strict no-op:
 *
 *   - There is no source drawable to copy from.
 *
 *   - There is no destination drawable to fill or copy into.
 *
 *   - There is no X graphics context whose foreground could be set or
 *     whose drawing primitives could be issued.
 *
 *   - No observable effect — neither in the program's output nor in
 *     any subsequent read of the destination surface — would be
 *     produced by drawing in a runtime that cannot display the result,
 *     and no reachable caller would observe the difference.
 *
 * This is a genuine "no X11" answer, not a stub that aborts. Aborting
 * would break the class initialisation of X11SurfaceData itself,
 * because X11SurfaceData.<clinit> calls X11PMBlitBgLoops.register()
 * unconditionally, and every program that constructs a Graphics2D
 * would trip the abort before reaching any actual drawing call. The
 * no-op keeps every caller on the documented "X11 acceleration
 * unavailable" branch that HotSpot itself exercises on a system
 * without a display.
 *
 * The remaining natives of the sibling X11 primitives
 * (X11PMBlitLoops, X11BlitBgLoops, X11MaskFill, X11MaskBlit,
 * X11DrawLine, X11DrawRect, X11FillRect, X11DrawPolygons,
 * X11FillPolygons, X11DrawGlyphList, X11DrawGlyphListAA, …) all
 * follow the same pattern: reachable from X11SurfaceData.<clinit>,
 * never invoked from a live path in a headless runtime, implemented
 * as a no-op or a defaulted return. This file continues that pattern.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * nativeBlitBg is an INSTANCE native: the receiver is the first
 * argument, followed by the ten Java-level parameters. The C
 * signature takes, in order:
 *
 *     this_prim       X11PMBlitBgLoops receiver
 *     srcData         int64_t native ops pointer of the source surface
 *     dstData         int64_t native ops pointer of the destination surface
 *     xgc             int64_t X11 graphics context handle
 *     pixel           int32_t destination-encoded background colour
 *     sx, sy          int32_t source rectangle top-left corner
 *     dx, dy          int32_t destination rectangle top-left corner
 *     w, h            int32_t rectangle width and height
 *
 * The method returns void.
 *
 * Every argument is captured and discarded. `srcData` and `dstData`
 * would index into per-surface native ops structs, `xgc` would be
 * used as the X11 graphics context for the copy, `pixel` would be the
 * fill colour, and the six integers would bound the two rectangles;
 * none of them can affect the outcome in a runtime with no X server
 * to issue the drawing primitive against.
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
 *   class  "sun/java2d/x11/X11PMBlitBgLoops"
 *       -> "sun_java2d_x11_X11PMBlitBgLoops"
 *   method "nativeBlitBg"
 *       -> "nativeBlitBg"
 *   desc   "(JJJIIIIIII)V"
 *       -> "_JJJIIIIIII_V"
 *
 * giving the symbol the linker reported as undefined:
 *
 *     __jnative_fn_sun_java2d_x11_X11PMBlitBgLoops_nativeBlitBg__JJJIIIIIII_V
 *
 * The double underscore after the method name is the method-to-
 * descriptor separator followed by the sanitised `(`. The three `J`s
 * are the three long parameters (srcData, dstData, xgc); the seven
 * `I`s are the sanitised ints (pixel, sx, sy, dx, dy, w, h); the
 * trailing `_V` is the sanitised `)` plus the void return type.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/x11/X11PMBlitBgLoops")
 * does not strip a leading "java/" — the class is in the sun.*
 * namespace — so it returns
 * "jnative/sun/java2d/x11/X11PMBlitBgLoops.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find
 * it and compile it into the executable.
 */

/*
 * private native void nativeBlitBg(long srcData, long dstData,
 *                                  long xgc, int pixel,
 *                                  int sx, int sy,
 *                                  int dx, int dy,
 *                                  int w, int h);
 *
 * No-op. See the file header for the full rationale: the runtime has
 * no X11 connection, the accelerated X11 pipeline is never selected,
 * and every caller in this runtime has a well-defined software
 * fallback path that operates on the destination raster's Java-side
 * data instead. The ten arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_x11_X11PMBlitBgLoops_nativeBlitBg__JJJIIIIIII_V(
        void* this_prim,
        int64_t src_data,
        int64_t dst_data,
        int64_t xgc,
        int32_t pixel,
        int32_t sx, int32_t sy,
        int32_t dx, int32_t dy,
        int32_t w, int32_t h)
{
    (void)this_prim;
    (void)src_data;
    (void)dst_data;
    (void)xgc;
    (void)pixel;
    (void)sx; (void)sy;
    (void)dx; (void)dy;
    (void)w;  (void)h;
}
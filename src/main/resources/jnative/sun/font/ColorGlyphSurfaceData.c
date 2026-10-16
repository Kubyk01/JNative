#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.font.ColorGlyphSurfaceData — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares two native methods:
 *
 *     private native void initOps();
 *     native void setCurrentGlyph(long imgPtr);
 *
 * initOps is called from the class's no-argument constructor, and
 * setCurrentGlyph is called from the glyph-cache machinery whenever a
 * new color glyph's native pixel data needs to be installed as the
 * surface's current backing store. Both call sites are emitted into
 * the module by the LLVM emitter whenever the class is reachable, so
 * without definitions in the linked executable clang rejects
 * sun/font/ColorGlyphSurfaceData.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_font_ColorGlyphSurfaceData_initOps___V'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, ColorGlyphSurfaceData is a SurfaceData view that wraps a
 * single color glyph bitmap held by the glyph cache. It is used by the
 * native text rendering pipeline to draw color-emoji glyphs (CBDT,
 * sbix, COLR tables) that a monochrome glyph cache cannot represent.
 *
 *   initOps
 *       registers the surface's native ops function block with the
 *       JDK's SurfaceManager, so the drawing pipeline can dispatch
 *       Blit and MaskBlit operations to the color-glyph renderer.
 *
 *   setCurrentGlyph
 *       stores the native pointer to the current glyph's pixel data
 *       into the surface's native ops struct, so subsequent drawing
 *       operations read from the correct location.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no font rasteriser, no glyph cache, and no color
 * glyph rendering pipeline. GraphicsEnvironment.isHeadless() is true on
 * every reachable path, so no Font2D is ever loaded and no color glyph
 * is ever produced by the JDK's font machinery. The class is only
 * reachable in the compiled image because the font-related classes
 * that reference it are part of the AWT bootstrap closure.
 *
 * Both natives are strict no-ops:
 *
 *   initOps           no-op. There is no native ops function block to
 *                     register, because this runtime has no color
 *                     glyph renderer.
 *
 *   setCurrentGlyph   no-op. There is no native glyph pointer to
 *                     install; the pixel data the Java side would
 *                     pass in is never produced by the runtime's font
 *                     machinery, and the surface itself is never used
 *                     for drawing.
 *
 * These are genuine "no color glyphs" answers, not stubs that abort.
 * Aborting would break class initialisation of ColorGlyphSurfaceData
 * — and, transitively, of every font subsystem that references it —
 * even though those subsystems have well-defined paths for a runtime
 * without color glyph support. The Java-side methods of the class
 * (`getReplacement`, `getDeviceConfiguration`, `getRaster`,
 * `getBounds`, `getDestination`) all throw
 * UnsupportedOperationException precisely because a color-glyph
 * surface has no drawable representation; the two natives do not need
 * to add to that.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * Both natives are INSTANCE methods: the receiver is the first
 * argument. The Java declarations are
 *
 *     private native void initOps();
 *     native void setCurrentGlyph(long imgPtr);
 *
 * so the C signatures take the ColorGlyphSurfaceData receiver, followed
 * (for setCurrentGlyph) by the int64_t glyph pointer. The `imgPtr`
 * argument is captured and discarded.
 *
 * ============================================================================
 * The mangled names
 * ============================================================================
 *
 * LlvmRuntime.mangleMethod applies
 *     "fn_" + sanitize(class) + "_" + sanitize(method) + "_" + sanitize(desc)
 * where sanitize replaces every character outside [a-zA-Z0-9_] with a
 * single underscore. The emitter prefixes the result with "__jnative_".
 *
 *   class  "sun/font/ColorGlyphSurfaceData"
 *       -> "sun_font_ColorGlyphSurfaceData"
 *
 *   initOps         desc "()V"
 *       -> "__jnative_fn_sun_font_ColorGlyphSurfaceData_initOps___V"
 *   setCurrentGlyph desc "(J)V"
 *       -> "__jnative_fn_sun_font_ColorGlyphSurfaceData_setCurrentGlyph__J_V"
 *
 * The initOps name is the one the linker reported as undefined;
 * setCurrentGlyph is emitted as well and would produce the same
 * diagnostic on the next rebuild if it were left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/font/ColorGlyphSurfaceData") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns
 * "jnative/sun/font/ColorGlyphSurfaceData.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/*
 * private native void initOps();
 *
 * No-op. See the file header for the full rationale: this runtime has
 * no color glyph renderer, so there is no native ops function block to
 * register. The call site exists in the constructor's bytecode, and
 * the linker requires a definition.
 */
void __jnative_fn_sun_font_ColorGlyphSurfaceData_initOps___V(
        void* this_surface)
{
    (void)this_surface;
}

/*
 * native void setCurrentGlyph(long imgPtr);
 *
 * No-op. On HotSpot this stores the native address of the current color
 * glyph's pixel data into the surface's native ops struct. This runtime
 * has no such struct and no glyph data to store; the argument is
 * captured and discarded.
 */
void __jnative_fn_sun_font_ColorGlyphSurfaceData_setCurrentGlyph__J_V(
        void* this_surface, int64_t img_ptr)
{
    (void)this_surface;
    (void)img_ptr;
}
#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.font.NativeFont — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * NativeFont is the java.awt font implementation that wraps an X11
 * server-side font, addressed by its X Logical Font Description (XLFD).
 * The class declares seven natives, five of them instance methods and
 * two of them static probes of the X font path. Every one of them is
 * referenced from the compiled IR — directly from NativeFont.ll and
 * indirectly, through inherited calls, from NativeStrike.ll and
 * FileFontStrike.ll — so every one of them must resolve at link time.
 *
 * Without this file, clang rejects each of those translation units in
 * turn with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_font_NativeFont_<method>_<desc>'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot with an X11 display, NativeFont drives the X server's own
 * font machinery through libX11:
 *
 *   countGlyphs            opens the font through XLoadQueryFont and
 *                          reads the returned XFontStruct's
 *                          max_char_or_byte2 / min_byte1 range to
 *                          derive the glyph count.
 *
 *   getFontMetrics         calls XQueryFont and translates the XFontStruct's
 *                          ascent / descent / max_bounds into a
 *                          StrikeMetrics record.
 *
 *   getGlyphAdvance        calls XTextWidth or reads the XCharStruct
 *                          for the requested glyph and returns its
 *                          perchar width in font units.
 *
 *   getGlyphImage          renders the glyph's bitmap on the X server
 *                          and returns a pointer to a native GlyphInfo
 *                          struct holding the pixel data.
 *
 *   getGlyphImageNoDefault same, but without the fallback to the
 *                          default glyph when the requested code is
 *                          absent.
 *
 *   haveBitmapFonts        probes the X font path for a set of bitmap
 *                          fonts matching the given XLFD wildcards.
 *
 *   fontExists             probes whether any font matching the given
 *                          XLFD is installed on the X server.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no X server, no X font path, and
 * no X font rasteriser. GraphicsEnvironment.isHeadless() is true on
 * every reachable path, and the NativeFont constructor itself rejects
 * a headless environment before any instance method can be invoked:
 *
 *     public NativeFont(String platName, boolean bitmapDelegate)
 *         throws FontFormatException {
 *         super(platName, null);
 *         isBitmapDelegate = bitmapDelegate;
 *         if (GraphicsEnvironment.isHeadless()) {
 *             throw new FontFormatException("Native font in headless toolkit");
 *         }
 *         ...
 *     }
 *
 * Consequently:
 *
 *   - No successfully constructed NativeFont instance can exist. Every
 *     instance native below is emitted into the module only because the
 *     bytecode of the class contains its call site, and the linker
 *     requires a definition.
 *
 *   - The static probes (haveBitmapFonts, fontExists) return false,
 *     which is the truthful answer for a runtime whose X font path is
 *     empty. The Java-side callers treat a false result as "no such
 *     font" and fall back to the on-disk Font2D path (or, in a headless
 *     runtime, to the default logical font).
 *
 *   - countGlyphs returns 0, which makes the NativeFont constructor
 *     raise FontFormatException("Couldn't locate font" + platName) —
 *     the same diagnostic HotSpot produces when the requested X font
 *     does not exist on the server. That is the correct answer even if
 *     a future revision removed the headless guard from the
 *     constructor.
 *
 *   - getFontMetrics returns NULL. The Java-side caller
 *     (NativeStrike.getFontMetrics) stores the return value in its
 *     strikeMetrics field and, on the paths that later read it,
 *     checks for NULL and falls back to the delegate font's metrics.
 *
 *   - getGlyphAdvance returns 0.0f, getGlyphImage and
 *     getGlyphImageNoDefault return 0 (the "no native glyph image
 *     available" sentinel). The NativeGlyphMapper caller treats a
 *     zero return as "glyph not representable" and reports the
 *     missing-glyph code upward, exactly as it does on a real X
 *     server when the requested code is outside the font's range.
 *
 * These are genuine "no X font" answers, not stubs that abort.
 * Aborting would break class initialisation of the whole font
 * subsystem — every Font2D subclass references NativeFont during its
 * own class load — for programs that never draw a single character,
 * and every caller in this file has a well-defined "no font" branch
 * that HotSpot itself exercises on a headless server.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * haveBitmapFonts and fontExists are STATIC natives: no receiver
 * argument. Both take a single Java byte[] (the UTF-8 encoded XLFD)
 * and return boolean (i1 at the IR level, int32_t at the C ABI level,
 * matching every other boolean native in this runtime).
 *
 * The other five are INSTANCE natives: the receiver is the first
 * argument.
 *
 *   countGlyphs(byte[], int) -> int
 *       receiver, byte[] XLFD, int pt size
 *
 *   getFontMetrics(long) -> StrikeMetrics
 *       receiver, long native scaler context
 *
 *   getGlyphAdvance(long, int) -> float
 *       receiver, long native scaler context, int glyph code
 *
 *   getGlyphImage(long, int) -> long
 *   getGlyphImageNoDefault(long, int) -> long
 *       receiver, long native scaler context, int glyph code
 *
 * Every argument is captured and discarded. None of them can affect
 * the outcome in a runtime with no X server to query.
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
 *   class "sun/font/NativeFont" -> "sun_font_NativeFont"
 *
 *   haveBitmapFonts        desc "([B)Z"
 *       -> "__jnative_fn_sun_font_NativeFont_haveBitmapFonts___B_Z"
 *   fontExists             desc "([B)Z"
 *       -> "__jnative_fn_sun_font_NativeFont_fontExists___B_Z"
 *   countGlyphs            desc "([BI)I"
 *       -> "__jnative_fn_sun_font_NativeFont_countGlyphs___BI_I"
 *   getFontMetrics         desc "(J)Lsun/font/StrikeMetrics;"
 *       -> "__jnative_fn_sun_font_NativeFont_getFontMetrics__J_Lsun_font_StrikeMetrics_"
 *   getGlyphAdvance        desc "(JI)F"
 *       -> "__jnative_fn_sun_font_NativeFont_getGlyphAdvance__JI_F"
 *   getGlyphImage          desc "(JI)J"
 *       -> "__jnative_fn_sun_font_NativeFont_getGlyphImage__JI_J"
 *   getGlyphImageNoDefault desc "(JI)J"
 *       -> "__jnative_fn_sun_font_NativeFont_getGlyphImageNoDefault__JI_J"
 *
 * The countGlyphs and getFontMetrics names are the two the linker
 * reported as undefined; the other five would produce the same
 * diagnostic on the next rebuild if left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/font/NativeFont") does not strip
 * a leading "java/" — the class is in the sun.* namespace — so it
 * returns "jnative/sun/font/NativeFont.c". That is the path this file
 * must live at for Compiler.extractSystemNativeSources to find it and
 * compile it into the executable.
 */

/* ============================================================================
 *  Static probes of the X font path
 * ========================================================================== */

/*
 * private static native boolean haveBitmapFonts(byte[] xlfd);
 *
 * Returns false. On HotSpot this queries the X font path for any
 * installed bitmap font whose XLFD matches the wildcarded pattern.
 * There is no X font path in this runtime, so the truthful answer is
 * that no matching bitmap font exists; the Java-side caller
 * (NativeFont.hasExternalBitmaps) uses the result to decide whether
 * the strike can be sourced from X server-side bitmaps, and a false
 * result steers it to the on-disk Font2D path.
 *
 * STATIC native: no receiver argument.
 */
int32_t __jnative_fn_sun_font_NativeFont_haveBitmapFonts___B_Z(void* xlfd) {
    (void)xlfd;
    return 0;
}

/*
 * public static native boolean fontExists(byte[] xlfd);
 *
 * Returns false. On HotSpot this queries the X server for any font
 * matching the XLFD. With no X connection there is nothing to query;
 * a false result is the same answer HotSpot produces when the X font
 * path does not contain the requested font.
 *
 * STATIC native: no receiver argument.
 */
int32_t __jnative_fn_sun_font_NativeFont_fontExists___B_Z(void* xlfd) {
    (void)xlfd;
    return 0;
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * private native int countGlyphs(byte[] platformNameBytes, int ptSize);
 *
 * Returns 0. On HotSpot this opens the font through XLoadQueryFont
 * and reads the returned XFontStruct's character range. This runtime
 * has no X server; 0 is the "font not found" sentinel, and the
 * NativeFont constructor converts it into
 *
 *     FontFormatException("Couldn't locate font" + platName)
 *
 * which is exactly what the reference implementation raises on a
 * server whose X font path does not carry the requested XLFD.
 *
 * The two arguments are captured and discarded.
 */
int32_t __jnative_fn_sun_font_NativeFont_countGlyphs___BI_I(
        void* this_font, void* platform_name_bytes, int32_t pt_size)
{
    (void)this_font;
    (void)platform_name_bytes;
    (void)pt_size;
    return 0;
}

/*
 * native StrikeMetrics getFontMetrics(long pScalerContext);
 *
 * Returns NULL. On HotSpot this queries the XFontStruct's ascent,
 * descent and max_bounds and builds a StrikeMetrics record. This
 * runtime has no X font to query; the Java-side caller
 * (NativeStrike.getFontMetrics) stores the return value in its
 * strikeMetrics field, and on the paths that read it the field is
 * checked against NULL before use. Returning NULL is the correct
 * "metrics unavailable" answer and matches what the reference
 * implementation produces when XQueryFont fails.
 *
 * The scaler-context argument is captured and discarded.
 */
void* __jnative_fn_sun_font_NativeFont_getFontMetrics__J_Lsun_font_StrikeMetrics_(
        void* this_font, int64_t p_scaler_context)
{
    (void)this_font;
    (void)p_scaler_context;
    return NULL;
}

/*
 * native float getGlyphAdvance(long pContext, int glyphCode);
 *
 * Returns 0.0f. On HotSpot this reads the per-glyph advance width
 * from the X server. With no X font available, the truthful answer
 * is that the glyph has no advance — the Java-side NativeGlyphMapper
 * treats a 0.0f return as "not representable" and reports the
 * missing-glyph code upward, exactly as it does on a real X server
 * for a glyph outside the font's character range.
 *
 * Both arguments are captured and discarded.
 */
float __jnative_fn_sun_font_NativeFont_getGlyphAdvance__JI_F(
        void* this_font, int64_t p_context, int32_t glyph_code)
{
    (void)this_font;
    (void)p_context;
    (void)glyph_code;
    return 0.0f;
}

/*
 * native long getGlyphImage(long pScalerContext, int glyphCode);
 *
 * Returns 0. On HotSpot this renders the glyph on the X server and
 * returns the address of a native GlyphInfo struct holding the pixel
 * data. This runtime has no X font rasteriser; 0 is the "no glyph
 * image available" sentinel that the Java-side caller
 * (NativeStrike.getGlyphImage) checks against and, on the paths that
 * need actual pixels, converts into the missing-glyph code.
 *
 * Both arguments are captured and discarded.
 */
int64_t __jnative_fn_sun_font_NativeFont_getGlyphImage__JI_J(
        void* this_font, int64_t p_scaler_context, int32_t glyph_code)
{
    (void)this_font;
    (void)p_scaler_context;
    (void)glyph_code;
    return 0;
}

/*
 * native long getGlyphImageNoDefault(long pScalerContext, int glyphCode);
 *
 * Returns 0. Same as getGlyphImage above, except that the reference
 * implementation suppresses the fallback to the font's default glyph
 * when the requested code is absent. In a runtime with no X font
 * server both entry points produce the same result — the
 * "no image available" sentinel — because there is no font to source
 * either the requested glyph or its default from.
 *
 * Both arguments are captured and discarded.
 */
int64_t __jnative_fn_sun_font_NativeFont_getGlyphImageNoDefault__JI_J(
        void* this_font, int64_t p_scaler_context, int32_t glyph_code)
{
    (void)this_font;
    (void)p_scaler_context;
    (void)glyph_code;
    return 0;
}
#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.font.FreetypeFontScaler — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares sixteen native methods that wrap the FreeType
 * font-rasteriser library. Every one of them is called from some
 * reachable method of the class, so the LLVM emitter records a call
 * site for each in the generated IR. Without definitions in the linked
 * executable, clang rejects sun/font/FreetypeFontScaler.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_font_FreetypeFontScaler_createScalerContextNative__J_DIIFF_J'
 *
 * and stops. Providing only the symbol in the diagnostic would move the
 * error to the next one; all sixteen are defined here so the module
 * links in one pass:
 *
 *   initIDs                      — static, <clinit> hook
 *   initNativeScaler             — allocate the native FreeType scaler
 *   getFontMetricsNative         — font-wide metrics
 *   getGlyphAdvanceNative        — glyph advance
 *   getGlyphMetricsNative        — glyph advance/left-bearing pair
 *   getGlyphImageNative          — glyph bitmap pointer
 *   getGlyphOutlineBoundsNative  — glyph outline bounding box
 *   getGlyphOutlineNative        — glyph outline path
 *   getGlyphVectorOutlineNative  — multi-glyph outline path
 *   getGlyphPointNative          — one point on a glyph outline
 *   disposeNativeScaler          — release the native FreeType scaler
 *   getGlyphCodeNative           — char -> glyph-id lookup
 *   getNumGlyphsNative           — total glyph count
 *   getMissingGlyphCodeNative    — the "missing glyph" id
 *   getUnitsPerEMNative          — font design-grid units per em
 *   createScalerContextNative    — create a per-strike scaler context
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, FreetypeFontScaler is the FontScaler implementation that
 * drives the FreeType library through a JNI bridge in libfontmanager.
 * It owns a per-font native scaler state (the address stored in the
 * Java-side `nativeScaler` field) and creates a per-strike scaler
 * context for every FontStrikeDesc. Every subsequent glyph operation
 * — advance, metrics, bitmap, outline, vector outline, point on
 * outline — funnels through FreeType's `FT_Load_Glyph` and its
 * friends, using the JNI bridge to translate FreeType structures into
 * the Java-side value classes (StrikeMetrics, Point2D.Float,
 * Rectangle2D.Float, GeneralPath).
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime does not link FreeType. The class's own design, however,
 * anticipates an environment where FreeType is unavailable: every
 * glyph query method is written as
 *
 *     if (nativeScaler != 0L) {
 *         return <native call>;
 *     }
 *     return FontScaler.getNullScaler().<same query>;
 *
 * so a `nativeScaler` field of `0` makes the entire class fall through
 * to the Java-only NullFontScaler for every operation. That fallback
 * returns zero advances, zero metrics, empty outlines and a null glyph
 * bitmap — the same answers that HotSpot itself returns for a font
 * whose glyphs cannot be rasterised at the requested size, and every
 * downstream consumer of a FontStrike is written to handle them.
 *
 * The sixteen natives therefore take the following answers:
 *
 *   initIDs                      no-op. There are no JNI field IDs
 *                                to cache in this runtime.
 *
 *   initNativeScaler             returns 0. The `nativeScaler` field
 *                                is left at 0, which is the sentinel
 *                                that steers every subsequent query to
 *                                the NullFontScaler fallback.
 *
 *   getFontMetricsNative         returns null. The caller never reaches
 *                                this method because `nativeScaler`
 *                                is 0; a null return matches the
 *                                "no scaler" state.
 *
 *   getGlyphAdvanceNative        returns 0.0f.
 *
 *   getGlyphMetricsNative        no-op. The Point2D.Float argument
 *                                retains whatever values the caller
 *                                initialised it with (typically 0).
 *
 *   getGlyphImageNative          returns 0.
 *
 *   getGlyphOutlineBoundsNative  returns null.
 *
 *   getGlyphOutlineNative        returns null.
 *
 *   getGlyphVectorOutlineNative  returns null.
 *
 *   getGlyphPointNative          returns null.
 *
 *   disposeNativeScaler          no-op.
 *
 *   getGlyphCodeNative           returns 0 (the missing-glyph id).
 *
 *   getNumGlyphsNative           returns 0.
 *
 *   getMissingGlyphCodeNative    returns 0.
 *
 *   getUnitsPerEMNative          returns 0.
 *
 *   createScalerContextNative    returns 0, which the Java-side caller
 *                                already treats as "fall through to
 *                                the NullFontScalerContext".
 *
 * These are genuine "no FreeType" answers, not stubs that abort.
 * Aborting would break every program that constructs a Font2D — i.e.
 * every program that uses a Font at all — because FreeTypeFontScaler's
 * constructor is reached as soon as a font is loaded, even on the
 * code paths that would eventually use only the NullFontScaler.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * initIDs is a STATIC native: no receiver argument.
 *
 * The other fifteen are INSTANCE natives: the receiver is the first
 * argument.
 *
 * The `boolean` parameter of initNativeScaler is represented as
 * int32_t at the C ABI level, matching every other boolean parameter
 * of every other native in this runtime. Reference-typed arguments
 * (Font2D, Point2D.Float, double[], int[]) arrive as i8* in LLVM
 * terms; primitives arrive as their natural C types (float, double,
 * int32_t, int64_t, char).
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
 *   class "sun/font/FreetypeFontScaler"
 *       -> "sun_font_FreetypeFontScaler"
 *
 * Symbol names, one per Java declaration:
 *
 *   __jnative_fn_sun_font_FreetypeFontScaler_initIDs__Ljava_lang_Class__V
 *   __jnative_fn_sun_font_FreetypeFontScaler_initNativeScaler__Lsun_font_Font2D_IIZI_J
 *   __jnative_fn_sun_font_FreetypeFontScaler_getFontMetricsNative__Lsun_font_Font2D_JJ_Lsun_font_StrikeMetrics_
 *   __jnative_fn_sun_font_FreetypeFontScaler_getGlyphAdvanceNative__Lsun_font_Font2D_JJI_F
 *   __jnative_fn_sun_font_FreetypeFontScaler_getGlyphMetricsNative__Lsun_font_Font2D_JJILjava_awt_geom_Point2D_Float__V
 *   __jnative_fn_sun_font_FreetypeFontScaler_getGlyphImageNative__Lsun_font_Font2D_JJI_J
 *   __jnative_fn_sun_font_FreetypeFontScaler_getGlyphOutlineBoundsNative__Lsun_font_Font2D_JJI_Ljava_awt_geom_Rectangle2D_Float_
 *   __jnative_fn_sun_font_FreetypeFontScaler_getGlyphOutlineNative__Lsun_font_Font2D_JJIFF_Ljava_awt_geom_GeneralPath_
 *   __jnative_fn_sun_font_FreetypeFontScaler_getGlyphVectorOutlineNative__Lsun_font_Font2D_JJ_I_IFF_Ljava_awt_geom_GeneralPath_
 *   __jnative_fn_sun_font_FreetypeFontScaler_getGlyphPointNative__Lsun_font_Font2D_JJII_Ljava_awt_geom_Point2D_Float_
 *   __jnative_fn_sun_font_FreetypeFontScaler_disposeNativeScaler__Lsun_font_Font2D_J_V
 *   __jnative_fn_sun_font_FreetypeFontScaler_getGlyphCodeNative__Lsun_font_Font2D_JC_I
 *   __jnative_fn_sun_font_FreetypeFontScaler_getNumGlyphsNative__J_I
 *   __jnative_fn_sun_font_FreetypeFontScaler_getMissingGlyphCodeNative__J_I
 *   __jnative_fn_sun_font_FreetypeFontScaler_getUnitsPerEMNative__J_J
 *   __jnative_fn_sun_font_FreetypeFontScaler_createScalerContextNative__J_DIIFF_J
 *
 * The createScalerContextNative name is the one the linker reported as
 * undefined; the others are emitted as well and would produce the same
 * diagnostic on the next rebuild if left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/font/FreetypeFontScaler") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/font/FreetypeFontScaler.c". That is the
 * path this file must live at for Compiler.extractSystemNativeSources
 * to find it and compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native void initIDs(Class<?> FFS);
 *
 * Called from FreetypeFontScaler.<clinit>. On HotSpot this hook caches
 * the JNI field IDs used by the other natives. This runtime accesses
 * every field through its LLVM-computed byte offset and never consults
 * JNI field IDs, so there is nothing to cache.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_font_FreetypeFontScaler_initIDs__Ljava_lang_Class__V(
        void* ffs_class)
{
    (void)ffs_class;
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * private native long initNativeScaler(Font2D font, int type,
 *                                      int indexInCollection,
 *                                      boolean supportsCJK,
 *                                      int filesize);
 *
 * The signature matches JDK 21. The parameter order — Font2D, int,
 * int, boolean, int — is fixed by the descriptor
 * (Lsun/font/Font2D;IIZI)J. This differs from older JDKs where the
 * fourth parameter was int; a build against an older JDK generates a
 * different symbol and this function simply never gets called.
 *
 * Returns 0 because FreeType is not linked: see the file-header
 * comment. The Java-side FreetypeFontScaler constructor checks
 * nativeScaler != 0L and falls back to NullFontScaler on zero.
 */
int64_t __jnative_fn_sun_font_FreetypeFontScaler_initNativeScaler__Lsun_font_Font2D_IIZI_J(
        void* this_scaler,
        void* font,
        int32_t type,
        int32_t index_in_collection,
        int32_t supports_cjk,
        int32_t filesize)
{
    (void)this_scaler;
    (void)font;
    (void)type;
    (void)index_in_collection;
    (void)supports_cjk;
    (void)filesize;
    return 0;
}

/*
 * private native StrikeMetrics getFontMetricsNative(Font2D font,
 *                                                   long pScalerContext,
 *                                                   long pScaler);
 *
 * Returns null. On HotSpot this reads the font's ascender, descender
 * and max advance from FreeType and builds a StrikeMetrics record.
 * The Java-side caller only reaches this method when `nativeScaler`
 * is non-zero; with `nativeScaler == 0` it delegates to the
 * NullFontScaler instead. Null is the correct "no scaler" answer.
 */
void* __jnative_fn_sun_font_FreetypeFontScaler_getFontMetricsNative__Lsun_font_Font2D_JJ_Lsun_font_StrikeMetrics_(
        void* this_scaler,
        void* font,
        int64_t p_scaler_context,
        int64_t p_scaler)
{
    (void)this_scaler;
    (void)font;
    (void)p_scaler_context;
    (void)p_scaler;
    return NULL;
}

/*
 * private native float getGlyphAdvanceNative(Font2D font,
 *                                            long pScalerContext,
 *                                            long pScaler,
 *                                            int glyphCode);
 *
 * Returns 0.0f. See the file header for the full rationale: the
 * Java-side caller only reaches this method when `nativeScaler` is
 * non-zero, which never happens in this runtime.
 */
float __jnative_fn_sun_font_FreetypeFontScaler_getGlyphAdvanceNative__Lsun_font_Font2D_JJI_F(
        void* this_scaler,
        void* font,
        int64_t p_scaler_context,
        int64_t p_scaler,
        int32_t glyph_code)
{
    (void)this_scaler;
    (void)font;
    (void)p_scaler_context;
    (void)p_scaler;
    (void)glyph_code;
    return 0.0f;
}

/*
 * private native void getGlyphMetricsNative(Font2D font,
 *                                           long pScalerContext,
 *                                           long pScaler,
 *                                           int glyphCode,
 *                                           Point2D.Float metrics);
 *
 * No-op. The caller's Point2D.Float retains whatever values it was
 * initialised with (typically 0,0), which is the correct "no metrics
 * available" state for a runtime without FreeType.
 */
void __jnative_fn_sun_font_FreetypeFontScaler_getGlyphMetricsNative__Lsun_font_Font2D_JJILjava_awt_geom_Point2D_Float__V(
        void* this_scaler,
        void* font,
        int64_t p_scaler_context,
        int64_t p_scaler,
        int32_t glyph_code,
        void* metrics)
{
    (void)this_scaler;
    (void)font;
    (void)p_scaler_context;
    (void)p_scaler;
    (void)glyph_code;
    (void)metrics;
}

/*
 * private native long getGlyphImageNative(Font2D font,
 *                                         long pScalerContext,
 *                                         long pScaler,
 *                                         int glyphCode);
 *
 * Returns 0. On HotSpot this renders the glyph through FreeType and
 * returns a pointer to a native GlyphInfo struct. The Java-side caller
 * only reaches this method when `nativeScaler` is non-zero; 0 is the
 * "no image" sentinel the caller's own null-check handles.
 */
int64_t __jnative_fn_sun_font_FreetypeFontScaler_getGlyphImageNative__Lsun_font_Font2D_JJI_J(
        void* this_scaler,
        void* font,
        int64_t p_scaler_context,
        int64_t p_scaler,
        int32_t glyph_code)
{
    (void)this_scaler;
    (void)font;
    (void)p_scaler_context;
    (void)p_scaler;
    (void)glyph_code;
    return 0;
}

/*
 * private native Rectangle2D.Float getGlyphOutlineBoundsNative(
 *         Font2D font, long pScalerContext, long pScaler, int glyphCode);
 *
 * Returns null. On HotSpot this reads the glyph's outline bounding box
 * from FreeType and constructs a Rectangle2D.Float. Null is the
 * correct "no outline available" answer for a runtime without
 * FreeType.
 */
void* __jnative_fn_sun_font_FreetypeFontScaler_getGlyphOutlineBoundsNative__Lsun_font_Font2D_JJI_Ljava_awt_geom_Rectangle2D_Float_(
        void* this_scaler,
        void* font,
        int64_t p_scaler_context,
        int64_t p_scaler,
        int32_t glyph_code)
{
    (void)this_scaler;
    (void)font;
    (void)p_scaler_context;
    (void)p_scaler;
    (void)glyph_code;
    return NULL;
}

/*
 * private native GeneralPath getGlyphOutlineNative(Font2D font,
 *                                                  long pScalerContext,
 *                                                  long pScaler,
 *                                                  int glyphCode,
 *                                                  float x, float y);
 *
 * Returns null. On HotSpot this rasterises the glyph's outline into a
 * sequence of move/line/quad/cubic path elements and constructs a
 * GeneralPath. Null is the correct "no outline available" answer for
 * a runtime without FreeType.
 */
void* __jnative_fn_sun_font_FreetypeFontScaler_getGlyphOutlineNative__Lsun_font_Font2D_JJIFF_Ljava_awt_geom_GeneralPath_(
        void* this_scaler,
        void* font,
        int64_t p_scaler_context,
        int64_t p_scaler,
        int32_t glyph_code,
        float x,
        float y)
{
    (void)this_scaler;
    (void)font;
    (void)p_scaler_context;
    (void)p_scaler;
    (void)glyph_code;
    (void)x;
    (void)y;
    return NULL;
}

/*
 * private native GeneralPath getGlyphVectorOutlineNative(Font2D font,
 *                                                        long pScalerContext,
 *                                                        long pScaler,
 *                                                        int[] glyphs,
 *                                                        int numGlyphs,
 *                                                        float x, float y);
 *
 * Returns null. On HotSpot this builds a single GeneralPath covering
 * the given sequence of glyphs, translated by (x, y). Null is the
 * correct "no outline available" answer for a runtime without
 * FreeType.
 */
void* __jnative_fn_sun_font_FreetypeFontScaler_getGlyphVectorOutlineNative__Lsun_font_Font2D_JJ_I_IFF_Ljava_awt_geom_GeneralPath_(
        void* this_scaler,
        void* font,
        int64_t p_scaler_context,
        int64_t p_scaler,
        void* glyphs,
        int32_t num_glyphs,
        float x,
        float y)
{
    (void)this_scaler;
    (void)font;
    (void)p_scaler_context;
    (void)p_scaler;
    (void)glyphs;
    (void)num_glyphs;
    (void)x;
    (void)y;
    return NULL;
}

/*
 * private native Point2D.Float getGlyphPointNative(Font2D font,
 *                                                  long pScalerContext,
 *                                                  long pScaler,
 *                                                  int glyphCode,
 *                                                  int ptNumber);
 *
 * Returns null. On HotSpot this reads the requested point on the
 * glyph's outline from FreeType. Null is the correct "no point
 * available" answer for a runtime without FreeType.
 */
void* __jnative_fn_sun_font_FreetypeFontScaler_getGlyphPointNative__Lsun_font_Font2D_JJII_Ljava_awt_geom_Point2D_Float_(
        void* this_scaler,
        void* font,
        int64_t p_scaler_context,
        int64_t p_scaler,
        int32_t glyph_code,
        int32_t pt_number)
{
    (void)this_scaler;
    (void)font;
    (void)p_scaler_context;
    (void)p_scaler;
    (void)glyph_code;
    (void)pt_number;
    return NULL;
}

/*
 * private native void disposeNativeScaler(Font2D font2D, long pScaler);
 *
 * No-op. On HotSpot this releases the FreeType scaler whose address
 * the Java side passes in. This runtime never allocated one — see
 * initNativeScaler above — so there is nothing to release.
 */
void __jnative_fn_sun_font_FreetypeFontScaler_disposeNativeScaler__Lsun_font_Font2D_J_V(
        void* this_scaler,
        void* font,
        int64_t p_scaler)
{
    (void)this_scaler;
    (void)font;
    (void)p_scaler;
}

/*
 * private native int getGlyphCodeNative(Font2D font,
 *                                       long pScaler,
 *                                       char charCode);
 *
 * Returns 0, the "missing glyph" id. On HotSpot this maps a Unicode
 * character to its glyph id in the font through FreeType's cmap. The
 * Java-side caller only reaches this method when `nativeScaler` is
 * non-zero; 0 is the sentinel for a character the font does not
 * support, which is exactly the truthful answer for a runtime without
 * FreeType.
 */
int32_t __jnative_fn_sun_font_FreetypeFontScaler_getGlyphCodeNative__Lsun_font_Font2D_JC_I(
        void* this_scaler,
        void* font,
        int64_t p_scaler,
        uint16_t char_code)
{
    (void)this_scaler;
    (void)font;
    (void)p_scaler;
    (void)char_code;
    return 0;
}

/*
 * private native int getNumGlyphsNative(long pScaler);
 *
 * Returns 0. On HotSpot this reads the font's glyph count from
 * FreeType. The Java-side caller only reaches this method when
 * `nativeScaler` is non-zero.
 */
int32_t __jnative_fn_sun_font_FreetypeFontScaler_getNumGlyphsNative__J_I(
        void* this_scaler,
        int64_t p_scaler)
{
    (void)this_scaler;
    (void)p_scaler;
    return 0;
}

/*
 * private native int getMissingGlyphCodeNative(long pScaler);
 *
 * Returns 0. On HotSpot this reads the font's "missing glyph" id from
 * FreeType. The Java-side caller only reaches this method when
 * `nativeScaler` is non-zero.
 */
int32_t __jnative_fn_sun_font_FreetypeFontScaler_getMissingGlyphCodeNative__J_I(
        void* this_scaler,
        int64_t p_scaler)
{
    (void)this_scaler;
    (void)p_scaler;
    return 0;
}

/*
 * private native long getUnitsPerEMNative(long pScaler);
 *
 * Returns 0. On HotSpot this reads the font's design-grid units-per-em
 * value from FreeType, which the caller uses to scale outline
 * coordinates into device space. The Java-side caller only reaches
 * this method when `nativeScaler` is non-zero.
 */
int64_t __jnative_fn_sun_font_FreetypeFontScaler_getUnitsPerEMNative__J_J(
        void* this_scaler,
        int64_t p_scaler)
{
    (void)this_scaler;
    (void)p_scaler;
    return 0;
}

/*
 * private native long createScalerContextNative(long pScaler,
 *                                               double[] matrix,
 *                                               int aa, int fm,
 *                                               float boldness,
 *                                               float italic);
 *
 * Returns 0. On HotSpot this creates a FreeType scaler context for
 * the given glyph transform and rendering hints, and returns its
 * native address. The Java-side caller only reaches this method when
 * `nativeScaler` is non-zero; on a 0 return it falls through to
 * NullFontScaler.getNullScalerContext(), which is the same fallback
 * the class takes when the native scaler itself is unavailable.
 *
 * The double[] matrix argument arrives as an i8* pointer to a runtime
 * Java array whose payload begins at JAVA_ARR_HDR; every other
 * argument is captured and discarded.
 */
int64_t __jnative_fn_sun_font_FreetypeFontScaler_createScalerContextNative__J_DIIFF_J(
        void* this_scaler,
        int64_t p_scaler,
        void* matrix,
        int32_t aa,
        int32_t fm,
        float boldness,
        float italic)
{
    (void)this_scaler;
    (void)p_scaler;
    (void)matrix;
    (void)aa;
    (void)fm;
    (void)boldness;
    (void)italic;
    return 0;
}
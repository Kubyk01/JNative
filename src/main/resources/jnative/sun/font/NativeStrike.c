#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.font.NativeStrike — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * NativeStrike is the PhysicalStrike subclass that bridges a NativeFont
 * — the java.awt font implementation that wraps an X11 server-side
 * font — to the font machinery that Font2D, FontStrike, and the
 * text-rendering code in java2d consume.
 *
 * The class declares three natives:
 *
 *   private native long createScalerContext(byte[] nameBytes,
 *                                           int ptSize,
 *                                           double scale);
 *
 *     Creates the native X11 font context for the strike. Called from
 *     the strike's constructor with the font's XLFD name (as a UTF-8
 *     encoded byte[]), the point size in pixels, and the device-space
 *     scale factor. Returns the address of the native context, or 0
 *     when the context cannot be created.
 *
 *   private native int getMaxGlyph(long pScalerContext);
 *
 *     Returns the largest valid glyph code that the font behind the
 *     given context supports. Used by the strike to size its glyph-
 *     image cache array so that glyph codes within the font's range
 *     can be cached by direct index.
 *
 *   private native long createNullScalerContext();
 *
 *     Returns the sentinel scaler-context value that identifies "this
 *     strike has no native context". Called from the strike's
 *     constructor when createScalerContext returned 0 — i.e. when the
 *     font could not be loaded into the X server — so that the strike
 *     still has a stable, non-zero scaler context to pass to its own
 *     metrics and glyph queries.
 *
 * Every one of these natives is emitted into the module because the
 * bytecode of NativeStrike or of one of its callers contains a call
 * site. Without definitions in the linked executable, clang rejects
 * sun/font/NativeStrike.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_font_NativeStrike_<method>_<desc>'
 *
 * The diagnostic that motivated this file names
 * createNullScalerContext, which is simply the first undefined symbol
 * clang happened to reach; the other two would surface on the next
 * rebuild if left out.
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot with an X11 display and an X font server, the native
 * side of NativeStrike drives the per-strike X11 font state:
 *
 *   createScalerContext
 *       opens the font through XLoadQueryFont using the XLFD name,
 *       allocates a native context struct (the same X11InputMethodData-
 *       like struct that NativeFont uses), stores the X font handle
 *       and the point size in it, and returns its address. On any
 *       failure — the font not present on the X server, the X
 *       connection gone, an allocation failure — it returns 0.
 *
 *   getMaxGlyph
 *       reads the font's max_char_or_byte2 field from the XFontStruct
 *       stored in the context and returns it as an int, bounding the
 *       range of glyph codes that the strike will cache.
 *
 *   createNullScalerContext
 *       returns the address of a fixed, process-wide sentinel that
 *       the strike machinery uses as the "no native context" token.
 *       The value is non-zero, stable across calls, and unique within
 *       the process, so the strike's own `pScalerContext != 0` tests
 *       continue to pass and the queries that follow do not
 *       re-trigger the null-scaler fallback.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no X font server, and no native
 * font context. GraphicsEnvironment.isHeadless() is true on every
 * reachable path, and NativeFont's constructor raises
 * FontFormatException("Native font in headless toolkit") before any
 * NativeStrike can be constructed. So on every reachable path:
 *
 *   - No NativeStrike instance is ever created through the normal
 *     path.
 *
 *   - Even if one were created through a bypass, the Java-side
 *     constructor's call to createScalerContext would receive 0
 *     (see below) and take the "no native strike" branch, which
 *     de-registers the bad font from the SunFontManager, installs
 *     the null scaler context, and sets numGlyphs to 0.
 *
 * Every native is nevertheless emitted into the module because the
 * bytecode of NativeStrike's constructor and of its glyph-query
 * methods references them. The three implementations below provide
 * the answers that keep the class linkable and internally consistent:
 *
 *   createScalerContext returns 0. On HotSpot this is the exact
 *       "font not loadable" sentinel, and the Java-side constructor
 *       is written to take its fallback branch on a zero return:
 *
 *           pScalerContext = createScalerContext(...);
 *           if (pScalerContext == 0L) {
 *               SunFontManager.getInstance().deRegisterBadFont(nativeFont);
 *               pScalerContext = createNullScalerContext();
 *               numGlyphs = 0;
 *               ...
 *               return;
 *           }
 *
 *       So a zero return is not an error path — it is the branch that
 *       steers the strike to the NullFontScaler delegation that this
 *       runtime actually supports.
 *
 *   getMaxGlyph returns 0. The Java-side caller
 *       (usingIntGlyphImages and getLongGlyphImages) clamps the
 *       return value to the strike's numGlyphs before sizing the
 *       glyph-image cache array:
 *
 *           int glyphLenArray = getMaxGlyph(pScalerContext);
 *           if (glyphLenArray < numGlyphs) {
 *               glyphLenArray = numGlyphs;
 *           }
 *
 *       A zero return is safe on every path — including paths that
 *       somehow reach this native with a non-zero context — because
 *       the clamp guarantees that the resulting array is at least
 *       as large as the font's own glyph count.
 *
 *   createNullScalerContext returns the address of a static sentinel
 *       struct, computed once and returned verbatim on every call.
 *       The value is non-zero, stable across calls, and unique within
 *       the process, so it satisfies every property the JDK's own
 *       callers rely on: it is a real, page-aligned address that any
 *       future code that chose to dereference it would fault on
 *       diagnosably rather than at a wild low address, and it does
 *       not collide with the sentinel used by NullFontScaler because
 *       each is a distinct static object in its own translation unit.
 *
 * These are genuine "no native strike" answers, not stubs that abort.
 * Aborting would break the constructor of NativeStrike itself, because
 * the constructor unconditionally calls createScalerContext on every
 * path that reaches it, and every program that loads a NativeFont-
 * backed strike would trip the abort before the fallback could take
 * over. Returning 0 steers the constructor to the documented "font
 * not loadable" branch that HotSpot itself exercises on a system
 * whose X font path does not carry the requested XLFD.
 *
 * ============================================================================
 * The sentinel value
 * ============================================================================
 *
 * The sentinel is a single static struct in this translation unit,
 * distinct from the one used by NullFontScaler.getNullScalerContext.
 * Using a distinct object rather than sharing the NullFontScaler
 * sentinel is deliberate: the two values serve different roles — one
 * identifies "the null font scaler" at the FontScaler level, the
 * other identifies "the null strike context" at the PhysicalStrike
 * level — and a future revision that wants to distinguish the two
 * would otherwise have to unwind the sharing. The cost of the extra
 * sentinel is one additional rodata symbol in the final executable.
 *
 * The struct has no fields because nothing in this runtime ever
 * reads through the pointer; the only property that matters is that
 * the address is a valid, non-zero, page-aligned, process-wide
 * constant.
 */
static struct NativeStrikeNullContextSentinel {
    int unused;
} native_strike_null_context_sentinel;

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * private native long createScalerContext(byte[] nameBytes,
 *                                         int ptSize,
 *                                         double scale);
 *
 * Returns 0. On HotSpot this opens the requested X11 font and returns
 * the address of a native context struct, or 0 when the font cannot
 * be loaded. This runtime has no X font server; 0 is the exact
 * "font not loadable" sentinel the Java-side constructor is written
 * to handle, and it steers the strike to the NullFontScaler
 * delegation that this runtime actually supports.
 *
 * All three arguments are captured and discarded. `nameBytes` is the
 * font's XLFD name encoded as UTF-8, `ptSize` is the pixel size, and
 * `scale` is the device-space scale factor — none of them can affect
 * the outcome in a runtime with no X font server to open the font on.
 */
int64_t __jnative_fn_sun_font_NativeStrike_createScalerContext___BID_J(
        void* this_strike,
        void* name_bytes,
        int32_t pt_size,
        double scale)
{
    (void)this_strike;
    (void)name_bytes;
    (void)pt_size;
    (void)scale;
    return 0;
}

/*
 * private native int getMaxGlyph(long pScalerContext);
 *
 * Returns 0. On HotSpot this reads the font's maximum glyph code from
 * the native context. The Java-side caller clamps the return value to
 * the strike's own numGlyphs before sizing the glyph-image cache
 * array, so 0 is safe on every path — including the paths that reach
 * this native with a non-zero context, which in this runtime only
 * happens with the null-context sentinel installed by the
 * constructor's fallback branch.
 *
 * The scaler-context argument is captured and discarded.
 */
int32_t __jnative_fn_sun_font_NativeStrike_getMaxGlyph__J_I(
        void* this_strike,
        int64_t p_scaler_context)
{
    (void)this_strike;
    (void)p_scaler_context;
    return 0;
}

/*
 * private native long createNullScalerContext();
 *
 * Returns the address of the file-scope sentinel struct above. The
 * value is non-zero, stable across calls, and unique within the
 * process, so it satisfies every property the JDK's own callers rely
 * on: the strike's `pScalerContext != 0` tests continue to pass, and
 * the subsequent glyph queries flow through NativeFont's own natives
 * (which are already no-ops that return 0 or NULL) rather than
 * re-triggering the null-scaler fallback.
 */
int64_t __jnative_fn_sun_font_NativeStrike_createNullScalerContext___J(
        void* this_strike)
{
    (void)this_strike;
    return (int64_t)(intptr_t)&native_strike_null_context_sentinel;
}
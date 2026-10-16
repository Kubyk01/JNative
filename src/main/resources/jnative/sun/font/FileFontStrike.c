#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.font.FileFontStrike — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares two native methods:
 *
 *     private static native boolean initNative();
 *     private native long _getGlyphImageFromWindows(String family,
 *                                                   int style,
 *                                                   int size,
 *                                                   int glyphCode,
 *                                                   boolean fracMetrics,
 *                                                   int fontDataSize);
 *
 * Both are Windows-specific:
 *
 *   - initNative is called from FileFontStrike.<clinit>, guarded by
 *     FontUtilities.isWindows && !FontUtilities.useJDKScaler &&
 *     !GraphicsEnvironment.isHeadless(). On a Linux or macOS build the
 *     guard is false and the call is never executed at run time.
 *
 *   - _getGlyphImageFromWindows is called from
 *     getGlyphImageFromWindows(int), which is itself called from
 *     getGlyphImageFromNative(int) only on the Windows branch of the
 *     platform check.
 *
 * The LLVM emitter nevertheless records a call site for both in the
 * generated IR, because the bytecode contains them unconditionally. So
 * without definitions in the linked executable clang rejects
 * sun/font/FileFontStrike.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_font_FileFontStrike__getGlyphImageFromWindows__Ljava_lang_String_IIIZI_J'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On Windows HotSpot, FileFontStrike uses the platform's GDI font
 * rasteriser in preference to the JDK's own scaler for a narrow but
 * performance-critical case: a scale-only transform (no shear, no
 * rotation) at an integer point size between 3 and 100, requesting LCD
 * subpixel antialiasing, on a font whose glyphs are not bitmap-embedded
 * at that size. When all of those conditions hold:
 *
 *   initNative
 *       probes the Windows GDI rasteriser and returns true if it is
 *       available (XP or later). The answer is stored in the Java-side
 *       isXPorLater field.
 *
 *   _getGlyphImageFromWindows
 *       calls the native GDI rasteriser for the given glyph and returns
 *       a pointer to a native GlyphInfo struct, or 0 on failure. The
 *       caller then stores the JDK-rasteriser's advance into the struct
 *       and caches the pointer in the strike's glyph-image table.
 *
 * The two natives are declared in libawt and are only compiled into the
 * Windows build of the JDK.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime is not built for Windows: the X11/GLX/XRender pipelines
 * that appear elsewhere in the codebase are Linux-specific, and the
 * platform-detection logic in X11GraphicsEnvironment and
 * XToolkit is only ever satisfied on a non-Windows host. Consequently:
 *
 *   - FontUtilities.isWindows is false, so the class initializer never
 *     calls initNative at run time.
 *
 *   - The `useNatives` field in the FileFontStrike constructor is only
 *     set to true on the Windows branch of its platform check, so
 *     getGlyphImageFromNative is never invoked.
 *
 * Both natives are therefore unreachable at run time in this runtime,
 * but both symbols are still required at link time because the bytecode
 * contains the call sites.
 *
 * The implementations below provide the answers that keep the class
 * well-formed if a future revision were to reach them:
 *
 *   initNative                  returns false — there is no Windows GDI
 *                               rasteriser to probe. The Java-side
 *                               isXPorLater field stays false, and every
 *                               subsequent check that gates on it takes
 *                               the JDK-rasteriser path.
 *
 *   _getGlyphImageFromWindows   returns 0 — the "glyph image not
 *                               available" sentinel. The Java-side
 *                               caller (getGlyphImageFromWindows) checks
 *                               against 0 and falls through to
 *                               fileFont.getGlyphImage, which uses the
 *                               JDK's own scaler. That fallback is the
 *                               same one used on a Windows machine
 *                               where GDI happens not to have the glyph;
 *                               it is a fully working code path.
 *
 * These are genuine "no Windows GDI" answers, not stubs that abort.
 * Aborting would break every program that constructs a FontStrike on a
 * non-Windows host, because the class initializer is executed as part
 * of loading a font regardless of which scaler is eventually used.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * initNative is a STATIC native: no receiver argument.
 *
 * _getGlyphImageFromWindows is an INSTANCE native: the receiver is the
 * first argument, followed by the six Java-level parameters. The
 * boolean `fracMetrics` is represented as int32_t at the C ABI level,
 * matching every other boolean parameter of every other native in this
 * runtime. The return type is a Java `long` (i64 at the IR level),
 * which the C ABI represents as int64_t.
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
 *   class "sun/font/FileFontStrike"
 *       -> "sun_font_FileFontStrike"
 *
 *   initNative  desc "()Z"
 *       -> "fn_sun_font_FileFontStrike_initNative___Z"
 *       -> "__jnative_fn_sun_font_FileFontStrike_initNative___Z"
 *
 *   _getGlyphImageFromWindows
 *       desc "(Ljava/lang/String;IIIZI)J"
 *       -> "fn_sun_font_FileFontStrike__getGlyphImageFromWindows__Ljava_lang_String_IIIZI_J"
 *       -> "__jnative_fn_sun_font_FileFontStrike__getGlyphImageFromWindows__Ljava_lang_String_IIIZI_J"
 *
 * The double underscore between "FileFontStrike" and
 * "getGlyphImageFromWindows" comes from the class-to-method separator
 * followed by the leading `_` character that is part of the method's
 * own name in the Java source (`_getGlyphImageFromWindows`). That
 * matches the name the linker reported as undefined.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/font/FileFontStrike") does not
 * strip a leading "java/" — the class is in the sun.* namespace — so it
 * returns "jnative/sun/font/FileFontStrike.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native boolean initNative();
 *
 * Returns false. See the file header for the full rationale: this
 * runtime has no Windows GDI rasteriser, and the Java-side static
 * initializer's platform guard (`FontUtilities.isWindows`) never
 * evaluates to true on any of this runtime's supported build hosts.
 * The symbol is nevertheless emitted into the IR because the call site
 * exists in the bytecode.
 *
 * A `false` return leaves the Java-side isXPorLater field at its
 * default false value, which correctly gates every subsequent
 * Windows-native path.
 *
 * STATIC native: no receiver argument.
 */
int32_t __jnative_fn_sun_font_FileFontStrike_initNative___Z(void) {
    return 0;
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * private native long _getGlyphImageFromWindows(String family,
 *                                               int style,
 *                                               int size,
 *                                               int glyphCode,
 *                                               boolean fracMetrics,
 *                                               int fontDataSize);
 *
 * Returns 0, the "glyph image not available" sentinel. See the file
 * header for the full rationale: this runtime has no Windows GDI
 * rasteriser, and the Java-side caller (getGlyphImageFromWindows) is
 * only reached on the Windows branch of the platform check in
 * getGlyphImageFromNative.
 *
 * A `0` return makes the Java-side caller fall through to
 * `fileFont.getGlyphImage(pScalerContext, glyphCode)`, which uses the
 * JDK's own scaler — the same fallback used on a Windows machine where
 * GDI happens not to have the glyph, and a fully working code path.
 *
 * Every argument is captured and discarded; there is no GDI rasteriser
 * to query with them.
 */
int64_t __jnative_fn_sun_font_FileFontStrike__getGlyphImageFromWindows__Ljava_lang_String_IIIZI_J(
        void* this_strike,
        void* family,
        int32_t style,
        int32_t size,
        int32_t glyph_code,
        int32_t frac_metrics,
        int32_t font_data_size)
{
    (void)this_strike;
    (void)family;
    (void)style;
    (void)size;
    (void)glyph_code;
    (void)frac_metrics;
    (void)font_data_size;
    return 0;
}
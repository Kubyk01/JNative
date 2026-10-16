#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.font.NullFontScaler — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * NullFontScaler is the java.awt font scaler that the JDK installs
 * when no real scaler is available for a font. It is the fallback
 * that every other FontScaler implementation delegates to for the
 * glyph operations it cannot service, and it is the scaler that a
 * FreetypeFontScaler uses on the paths where its own native state has
 * not been initialised (see jnative/sun/font/FreetypeFontScaler.c,
 * where initNativeScaler returns 0, which is exactly the signal that
 * steers the Java side to NullFontScaler).
 *
 * The class declares two natives:
 *
 *   static native long getNullScalerContext();
 *
 *     Returns the sentinel scaler-context value that identifies "this
 *     is the null scaler, do not attempt to render". The Java-side
 *     caller (NullFontScaler.createScalerContext) returns this value
 *     verbatim, and the surrounding JDK code uses it as an identity
 *     token: 0 means "no context", any other value means "a context
 *     exists but produces nothing". The value must therefore be both
 *     non-zero and stable — the same value on every call — so that
 *     the JDK's own caching of the context works.
 *
 *   native long getGlyphImage(long pScalerContext, int glyphCode);
 *
 *     Returns the native address of a GlyphInfo struct for the given
 *     glyph, or 0 when no image is available. NullFontScaler by
 *     definition never has a glyph image to return, so this method
 *     always returns 0. The Java-side callers (FileFontStrike and
 *     the glyph-cache machinery in FontStrike / StrikeCache) check
 *     the return value against 0 and take the "no glyph image"
 *     branch, which is exactly what the reference implementation
 *     produces for a glyph the font cannot render.
 *
 * The two natives are emitted into the module because the bytecode of
 * NullFontScaler and of its callers contains the call sites.
 * Without definitions in the linked executable, clang rejects
 * sun/font/NullFontScaler.ll and sun/font/FileFontStrike.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_font_NullFontScaler_<method>_<desc>'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot the two natives are essentially bookkeeping stubs whose
 * bodies are trivial, but whose return values are load-bearing for
 * the rest of the JDK's font machinery.
 *
 *   getNullScalerContext
 *       returns a fixed, process-wide non-zero constant that the JDK
 *       uses as the identity of the null scaler context. The value is
 *       the address of a static sentinel struct in libfontmanager.
 *       It is never dereferenced: every consumer treats it as an
 *       opaque token, not a real context.
 *
 *   getGlyphImage
 *       returns 0 unconditionally, since NullFontScaler has no
 *       glyph-image table and no per-glyph data to hand back. The
 *       GlyphInfo* slot the caller is expecting stays NULL, and the
 *       glyph-cache code recognises that as "this glyph has no
 *       cached image" rather than as an error.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no font rasteriser and no real FontScaler of any
 * kind. The class is reachable in the compiled image because every
 * other FontScaler implementation (FreetypeFontScaler in particular)
 * references NullFontScaler during its own class initialisation, and
 * because the glyph-cache and FileFontStrike code paths call
 * NullFontScaler.getNullScalerContext() as their fallback whenever
 * the real scaler's own context is 0.
 *
 * Both natives are implemented with the answers that keep those
 * callers on their documented "no real scaler" branches:
 *
 *   getNullScalerContext returns the address of a static sentinel
 *       struct, computed once on first use. The value is non-zero,
 *       stable across every call, and unique within the process, so
 *       it satisfies every property the JDK's own callers rely on.
 *       It is never dereferenced by anything in this runtime — the
 *       token flows into the glyph cache and into FileFontStrike's
 *       native context slot, both of which only ever compare it
 *       against 0 and pass it back to the natives here and in
 *       FreetypeFontScaler.c.
 *
 *   getGlyphImage returns 0 unconditionally. This is what the
 *       reference implementation produces for every glyph, and it is
 *       what the Java-side callers expect: the glyph-cache code
 *       treats a 0 return as "no cached image" and either falls
 *       through to the next scaler in the chain or accepts that the
 *       glyph cannot be rendered.
 *
 * These are genuine "no real scaler" answers, not stubs that abort.
 * Aborting would break every program that loads a Font2D — i.e.
 * every program that constructs a Font at all — because the
 * FreetypeFontScaler constructor reaches the NullFontScaler
 * delegation path during its own field initialisation, even on the
 * code paths that never actually draw a glyph. Returning the same
 * values the reference implementation returns for a font whose
 * scaler could not be initialised keeps every caller on a path it
 * already handles.
 *
 * ============================================================================
 * The sentinel value
 * ============================================================================
 *
 * The sentinel is a single static struct in this translation unit.
 * Its address is taken once, the first time getNullScalerContext is
 * called, and returned verbatim on every subsequent call. The struct
 * has no fields because nothing ever reads through the pointer; the
 * only property that matters is that the address is a valid,
 * non-zero, page-aligned, process-wide constant.
 *
 * Using a static address rather than a literal constant like 1L is
 * deliberate, even though no current caller dereferences the token.
 * A future revision of the JDK, or a future change to this runtime,
 * could plausibly decide to walk the scaler context to extract some
 * bit of state; a real address would at worst crash with a
 * diagnosable SIGSEGV in the caller that did so, whereas a literal
 * 1L would produce a wild jump through the low address space. The
 * extra cost of the sentinel is zero — it is one rodata symbol in
 * the final executable.
 *
 * The struct is declared `static` and lives at file scope, so the
 * linker keeps it in this translation unit and the address is stable
 * across the whole process lifetime. It is never written to; the
 * `const` qualifier on the pointer that the compiler sees is not
 * needed because nothing in this file writes to it, and adding it
 * would only complicate the bitcast.
 */
static struct NullScalerContextSentinel {
    int unused;
} null_scaler_context_sentinel;

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * static native long getNullScalerContext();
 *
 * Returns the address of the file-scope sentinel struct above. The
 * value is non-zero, stable across every call, and unique within the
 * process, matching the contract that the JDK's glyph-cache and
 * FileFontStrike code rely on: 0 means "no context", anything else
 * means "a context exists but produces nothing".
 *
 * STATIC native: no receiver argument.
 */
int64_t __jnative_fn_sun_font_NullFontScaler_getNullScalerContext___J(void) {
    return (int64_t)(intptr_t)&null_scaler_context_sentinel;
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * native long getGlyphImage(long pScalerContext, int glyphCode);
 *
 * Returns 0. On HotSpot this would return the native address of a
 * GlyphInfo struct holding the glyph's rendered image. NullFontScaler
 * never has a glyph image to return — that is the defining property
 * of the class — so the truthful answer is the "no image available"
 * sentinel, which is exactly what the Java-side callers check against.
 *
 * Both arguments are captured and discarded: there is no per-glyph
 * state to consult in a scaler that produces no glyphs.
 */
int64_t __jnative_fn_sun_font_NullFontScaler_getGlyphImage__JI_J(
        void* this_scaler, int64_t p_scaler_context, int32_t glyph_code)
{
    (void)this_scaler;
    (void)p_scaler_context;
    (void)glyph_code;
    return 0;
}
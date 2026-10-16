#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.font.NativeStrikeDisposer — native entry point.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * NativeStrikeDisposer is the sun.font.FontStrikeDisposer subclass that
 * owns the lifetime of a native font strike's scaler context. When a
 * NativeStrike (the strike produced by NativeFont and used to service
 * text drawn through an X11 server-side font) becomes unreachable, the
 * JDK's Disposer thread invokes this class's dispose() method, which:
 *
 *   1. releases the strike's glyph-image cache (handled by the
 *      superclass FontStrikeDisposer.dispose);
 *
 *   2. removes the strike's entry from the SunFontManager's per-key
 *      strike map (also handled by the superclass);
 *
 *   3. and, if the strike owned a native scaler context, calls
 *      freeNativeScalerContext(pNativeScalerContext) to release that
 *      context's native resources.
 *
 * The last step is the one this file implements. The class declares a
 * single native:
 *
 *     private native void freeNativeScalerContext(long pContext);
 *
 * and its body is reached only when the pNativeScalerContext field is
 * non-zero, i.e. when the strike was created with a real scaler
 * context. See the dispose() method:
 *
 *     public synchronized void dispose() {
 *         if (!disposed) {
 *             if (pNativeScalerContext != 0L) {
 *                 freeNativeScalerContext(pNativeScalerContext);
 *             }
 *             super.dispose();
 *         }
 *     }
 *
 * The class is reachable in the compiled image because it appears in
 * the type hierarchy that the JDK's font-disposal machinery walks
 * during class loading, and because the bytecode of its own dispose
 * method contains the call site. The call site is emitted into the
 * module unconditionally.
 *
 * Without a definition in the linked executable, clang rejects
 * sun/font/NativeStrikeDisposer.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_font_NativeStrikeDisposer_freeNativeScalerContext__J_V'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot with an X11 display and a native font strike in use, the
 * native side of NativeStrikeDisposer forwards the scaler-context
 * address to the JDK's font-manager C library, which releases the
 * server-side font resources associated with that context:
 *
 *   - for a NativeStrike produced by NativeFont, the context is a
 *     native X11InputMethodData-like struct holding an X font handle
 *     and the associated glyph-image cache;
 *
 *   - the native body calls the appropriate free routine for that
 *     struct, which closes the X font handle, drops any references to
 *     server-side glyph caches, and frees the struct itself.
 *
 * The exact C function depends on the platform and on which concrete
 * scaler produced the context; the Java side does not need to know
 * which one, because the context address is opaque to it and the
 * native side dispatches by inspecting the struct's own type tag.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no X font server, no native font
 * scaler context, and no per-strike native state. On every reachable
 * path:
 *
 *   - X11GraphicsEnvironment.isHeadless() is true, so no X11 display
 *     is available.
 *
 *   - NativeFont's constructor raises FontFormatException("Native font
 *     in headless toolkit") before any NativeStrike can be created, so
 *     no NativeStrike instance exists.
 *
 *   - The pNativeScalerContext field of every NativeStrikeDisposer
 *     instance that could be constructed is therefore 0, and the
 *     Java-side dispose() method's own guard
 *     (`if (pNativeScalerContext != 0L)`) prevents this native from
 *     being invoked.
 *
 * The native is nevertheless emitted into the module because the
 * bytecode of dispose() references it. The implementation below is a
 * strict no-op:
 *
 *   - There is no native scaler context to release.
 *
 *   - The address passed in would be 0 in every path that could
 *     possibly reach this native, and the reference implementation's
 *     own free routine is a no-op on a 0 address.
 *
 *   - No observable effect — neither in the program's output nor in
 *     any subsequent state visible to the Java layer — would be
 *     produced by freeing a context that was never allocated.
 *
 * This is a genuine "no native scaler" answer, not a stub that
 * aborts. Aborting would break the disposal of every FontStrike in
 * the runtime — including the strikes of the default physical font,
 * which every program that touches a Font creates during its own
 * initialisation — because the Disposer thread invokes dispose() on
 * the NativeStrikeDisposer instance regardless of whether the strike
 * was created through the native font path. The no-op keeps the
 * disposer on the documented "no native context to free" branch that
 * HotSpot itself exercises on a system without a native font scaler.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * freeNativeScalerContext is an INSTANCE native: the receiver is the
 * first argument, followed by the single Java-level parameter. The C
 * signature takes, in order:
 *
 *     this_disposer   NativeStrikeDisposer receiver
 *     p_context       int64_t native scaler-context address
 *
 * The `p_context` argument is the native address of the scaler context
 * that the strike's pNativeScalerContext field held. In this runtime
 * it is always 0 — see the file header — so the native body does not
 * consult it.
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
 *   class  "sun/font/NativeStrikeDisposer"
 *       -> "sun_font_NativeStrikeDisposer"
 *   method "freeNativeScalerContext"
 *       -> "freeNativeScalerContext"
 *   desc   "(J)V"
 *       -> "_J_V"
 *
 * giving the symbol the linker reported as undefined:
 *
 *     __jnative_fn_sun_font_NativeStrikeDisposer_freeNativeScalerContext__J_V
 *
 * The double underscore after the method name is the method-to-
 * descriptor separator followed by the sanitised `(`. The `J` is the
 * sanitised long parameter, and the trailing `_V` is the sanitised `)`
 * plus the void return type.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/font/NativeStrikeDisposer") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/font/NativeStrikeDisposer.c". That is the
 * path this file must live at for Compiler.extractSystemNativeSources
 * to find it and compile it into the executable.
 */

/*
 * private native void freeNativeScalerContext(long pContext);
 *
 * No-op. See the file header for the full rationale: the runtime has
 * no native font scaler, no NativeStrike instance is ever constructed,
 * and the Java-side dispose() method's own guard
 * (`if (pNativeScalerContext != 0L)`) prevents this native from being
 * invoked on any path that could reach it. The two arguments are
 * captured and discarded.
 */
void __jnative_fn_sun_font_NativeStrikeDisposer_freeNativeScalerContext__J_V(
        void* this_disposer,
        int64_t p_context)
{
    (void)this_disposer;
    (void)p_context;
}
#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#include "jnative_runtime.h"

/*
 * sun.awt.X11.XWindow — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares five native methods. Every one of them is called
 * from some reachable method of the class or of one of its subclasses,
 * so the LLVM emitter records a call site for each in the generated IR.
 * Without definitions in the linked executable, clang rejects
 * sun/awt/X11/XWindow.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_X11_XWindow_haveCurrentX11InputMethodInstance___Z'
 *
 * and stops. Providing only the symbol in the diagnostic would move
 * the error to the next one; all five are defined here so the module
 * links in one pass:
 *
 *   initIDs                          — XWindow.<clinit> hook
 *   x11inputMethodLookupString       — XIM keysym lookup
 *   haveCurrentX11InputMethodInstance — XIM presence probe
 *   getAWTKeyCodeForKeySym           — keysym → AWT keycode
 *   getKeySymForAWTKeyCode           — AWT keycode → keysym
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, XWindow is the base class of every X11 AWT peer. Its
 * native side links against libX11, libXext and the AWT's own XIM bridge
 * (libawt_xawt) and provides the small amount of C-level glue that the
 * Java-side peers need to talk to the X server:
 *
 *   initIDs                          caches the JNI field IDs of
 *                                    XWindow, XWindowPeer and
 *                                    XWindowAttributesData.
 *
 *   x11inputMethodLookupString       forwards a key event to the active
 *                                    XIM input context and fills the
 *                                    caller's long[2] with the composed
 *                                    keysym and modifier state.
 *
 *   haveCurrentX11InputMethodInstance
 *                                    reports whether an X11InputMethod
 *                                    instance is currently bound to
 *                                    the toolkit's awt_lock-protected
 *                                    per-display XIM state.
 *
 *   getAWTKeyCodeForKeySym           maps an X keysym to the
 *                                    corresponding java.awt.event.KeyEvent
 *                                    VK_* code.
 *
 *   getKeySymForAWTKeyCode           performs the reverse mapping.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no display, no XIM server, and no
 * C-side AWT bridge. GraphicsEnvironment.isHeadless() is true on every
 * reachable path, so:
 *
 *   - XWindow.<clinit> takes the headless guard and never reaches the
 *     initIDs call site.
 *
 *   - handleKeyPress / handleKeyRelease are only invoked from the
 *     toolkit thread's event loop, which is never started.
 *
 *   - XGlobalCursorManager, XAwtState, and the rest of the peer
 *     infrastructure that would query the input-method state are
 *     themselves never constructed.
 *
 * Every native is nevertheless reachable from the bytecode and must
 * resolve at link time. The implementations below provide the answers
 * that keep a headless runtime internally consistent:
 *
 *   initIDs                          no-op (no JNI field IDs cached in
 *                                    this runtime).
 *
 *   x11inputMethodLookupString       returns false. The Java-side caller
 *                                    interprets a false return as "the
 *                                    input method did not consume this
 *                                    key event", and then falls through
 *                                    to the do-it-yourself keysym /
 *                                    unicode conversion that does not
 *                                    need an input method at all. The
 *                                    keysymArray argument is a runtime
 *                                    long[2] whose payload starts at
 *                                    JAVA_ARR_HDR; it is captured and
 *                                    discarded.
 *
 *   haveCurrentX11InputMethodInstance returns false. The Java-side
 *                                    caller uses this as the branch
 *                                    selector in handleKeyPress: a
 *                                    false return takes the "no input
 *                                    method instance" path, which
 *                                    produces a keysym directly from
 *                                    the X keycode via XKeysym's static
 *                                    tables. That path is entirely
 *                                    Java-side and does not need any
 *                                    native support.
 *
 *   getAWTKeyCodeForKeySym           returns the AWT VK_UNDEFINED
 *                                    sentinel (0). The Java-side callers
 *                                    in XKeysym use this as the fallback
 *                                    when their own table lookup fails,
 *                                    and VK_UNDEFINED is what the
 *                                    Java-side hot path in
 *                                    handleKeyPress produces for any
 *                                    unmapped keysym. Returning it here
 *                                    keeps the two sides consistent.
 *
 *   getKeySymForAWTKeyCode           returns 0 (the NoSymbol keysym).
 *                                    Same reasoning as the reverse
 *                                    direction: a caller that receives
 *                                    NoSymbol knows the mapping was not
 *                                    found and falls through to its own
 *                                    default.
 *
 * These are genuine "no X11 / no XIM" answers, not stubs that abort.
 * The Java-side hot paths in XWindow.handleKeyPress and
 * XKeysym.getJavaKeycode all have well-defined branches for the "no
 * input method" and "no mapping" cases, and the answers above keep
 * those branches on their documented paths. Aborting would break every
 * AWT initialisation that constructs an XWindow peer, including the
 * many JDK classes that probe Toolkit.getDefaultToolkit() before
 * deciding whether any AWT operation is possible.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * initIDs, haveCurrentX11InputMethodInstance, getAWTKeyCodeForKeySym and
 * getKeySymForAWTKeyCode are STATIC natives in the Java declaration:
 * no receiver argument.
 *
 * x11inputMethodLookupString is an INSTANCE native: the receiver is the
 * first argument, followed by the XEvent data pointer (a Java long) and
 * the keysym array (a Java long[]).
 *
 * The boolean return of x11inputMethodLookupString and
 * haveCurrentX11InputMethodInstance is represented as int32_t at the C
 * ABI level, matching every other boolean native in this runtime
 * (Thread.isAlive, Net.isIPv6Available0, Deflater.init, …).
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
 *   class "sun/awt/X11/XWindow" -> "sun_awt_X11_XWindow"
 *
 *   initIDs                             desc "()V"
 *       -> "__jnative_fn_sun_awt_X11_XWindow_initIDs___V"
 *   x11inputMethodLookupString          desc "(J[J)Z"
 *       -> "__jnative_fn_sun_awt_X11_XWindow_x11inputMethodLookupString__J_J_Z"
 *   haveCurrentX11InputMethodInstance   desc "()Z"
 *       -> "__jnative_fn_sun_awt_X11_XWindow_haveCurrentX11InputMethodInstance___Z"
 *   getAWTKeyCodeForKeySym              desc "(I)I"
 *       -> "__jnative_fn_sun_awt_X11_XWindow_getAWTKeyCodeForKeySym__I_I"
 *   getKeySymForAWTKeyCode              desc "(I)I"
 *       -> "__jnative_fn_sun_awt_X11_XWindow_getKeySymForAWTKeyCode__I_I"
 *
 * The haveCurrentX11InputMethodInstance name is the one the linker
 * reported as undefined; the others are emitted as well and would
 * produce the same diagnostic on the next rebuild if left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/X11/XWindow") does not strip
 * a leading "java/" — the class is in the sun.* namespace — so it
 * returns "jnative/sun/awt/X11/XWindow.c". That is the path this file
 * must live at for Compiler.extractSystemNativeSources to find it and
 * compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native void initIDs();
 *
 * Called from XWindow.<clinit>. On HotSpot this hook caches the JNI
 * field IDs of XWindow, XWindowPeer and XWindowAttributesData so the
 * other natives can reach into those objects without repeated lookups.
 * This runtime accesses every field through its LLVM-computed byte
 * offset and never consults JNI field IDs, so there is nothing to
 * cache. The symbol must exist because the class's static initializer
 * emits a native call to it.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_X11_XWindow_initIDs___V(void) {
}

/*
 * private native boolean haveCurrentX11InputMethodInstance();
 *
 * Returns false. On HotSpot this probes the toolkit's per-display XIM
 * state to find out whether an X11InputMethod instance is currently
 * bound to it; the Java-side handleKeyPress uses the answer as the
 * branch selector between "delegate this event to the input method"
 * and "convert the keysym myself from the X keycode". A false return
 * takes the second branch, which is entirely Java-side and needs no
 * native support — and is exactly the branch a runtime with no XIM
 * server must take.
 */
int32_t __jnative_fn_sun_awt_X11_XWindow_haveCurrentX11InputMethodInstance___Z(void) {
    return 0;
}

/*
 * static native int getAWTKeyCodeForKeySym(int keysym);
 *
 * Returns the AWT VK_UNDEFINED sentinel (0). On HotSpot this maps an X
 * keysym to the corresponding java.awt.event.KeyEvent VK_* code using
 * a pair of tables emitted into libawt_xawt. The Java-side callers in
 * XKeysym treat a VK_UNDEFINED return as "no mapping found" and fall
 * back to their own primary-unicode lookup. Returning the sentinel here
 * keeps the two sides consistent: with no X server there is no key
 * event to map, and VK_UNDEFINED is what the Java-side hot path
 * produces for every unmapped keysym anyway.
 *
 * STATIC native: no receiver argument.
 */
int32_t __jnative_fn_sun_awt_X11_XWindow_getAWTKeyCodeForKeySym__I_I(
        int32_t keysym)
{
    (void)keysym;
    return 0;   /* java.awt.event.KeyEvent.VK_UNDEFINED */
}

/*
 * static native int getKeySymForAWTKeyCode(int keycode);
 *
 * Returns 0 (the NoSymbol keysym). On HotSpot this performs the reverse
 * of getAWTKeyCodeForKeySym: it maps a java.awt.event.KeyEvent VK_*
 * code back to the X keysym that the server would report for the
 * corresponding physical key. The Java-side callers use a 0 return as
 * the "no mapping found" signal and fall through to their own default.
 * With no X server to report a keysym for, NoSymbol is the honest
 * answer.
 *
 * STATIC native: no receiver argument.
 */
int32_t __jnative_fn_sun_awt_X11_XWindow_getKeySymForAWTKeyCode__I_I(
        int32_t keycode)
{
    (void)keycode;
    return 0;   /* XConstants.NoSymbol */
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * private native boolean x11inputMethodLookupString(long event,
 *                                                   long[] keysymArray);
 *
 * Returns false. On HotSpot this forwards the X event data pointer to
 * the active XIM input context, calls XmbLookupString (or its Xutf8
 * equivalent) on it, and fills the caller's long[2] with the composed
 * keysym and modifier state. The Java-side caller interprets a false
 * return as "the input method did not consume this key event", and then
 * falls through to its do-it-yourself keysym conversion, which produces
 * the keysym directly from the X keycode via the static tables in
 * XKeysym. That fallback is entirely Java-side and needs no native
 * support — and is exactly the branch a runtime with no XIM server
 * must take.
 *
 * The `event` argument is the raw XEvent pointer, which in this runtime
 * is always 0 because no X event is ever dispatched. The
 * `keysymArray` argument is a runtime long[2] whose payload starts at
 * JAVA_ARR_HDR; both are captured and discarded.
 */
int32_t __jnative_fn_sun_awt_X11_XWindow_x11inputMethodLookupString__J_J_Z(
        void* this_window, int64_t event, void* keysym_array)
{
    (void)this_window;
    (void)event;
    (void)keysym_array;
    return 0;
}
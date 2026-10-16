#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.awt.X11InputMethodBase — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * X11InputMethodBase is the abstract base of the XIM (X Input Method)
 * adapters that connect an AWT text component to the user's installed
 * input-method engine through the X server's input-method server. Two
 * concrete subclasses exist in the JDK — sun.awt.X11.XInputMethod and
 * sun.awt.X11InputMethod — and both delegate their native methods to
 * the six declared here.
 *
 * The class declares six natives:
 *
 *   private static native void initIDs();
 *
 *     Called from the class's static initializer. On HotSpot this hook
 *     caches the JNI field IDs used by the other natives.
 *
 *   protected native void turnoffStatusWindow();
 *
 *     Hides the status window that the XIM server owns for the active
 *     input context. Called from hideWindows and from the deactivation
 *     path.
 *
 *   protected native void disposeXIC();
 *
 *     Destroys the input context (XIC) associated with the adapter,
 *     releasing the server-side resources it holds. Called from
 *     disposeImpl on every disposal path.
 *
 *   private native String resetXIC();
 *
 *     Resets the input context to its initial state, discarding any
 *     pending preedit text, and returns the text that the server had
 *     accumulated, or null if there was none. Called from endComposition
 *     and from resetXICifneeded.
 *
 *   protected native boolean setCompositionEnabledNative(boolean enable);
 *
 *     Toggles the XIC's composition mode. Returns true on success, false
 *     when the platform cannot honour the request. Called from
 *     setCompositionEnabled.
 *
 *   private native boolean isCompositionEnabledNative();
 *
 *     Reads the XIC's current composition mode. Called from
 *     isCompositionEnabled and, indirectly, from getCompositionState.
 *
 * Every one of these natives is emitted into the module because the
 * bytecode of X11InputMethodBase or of one of its concrete subclasses
 * contains a call site. Without definitions in the linked executable,
 * clang rejects sun/awt/X11InputMethodBase.ll and
 * sun/awt/X11InputMethod.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_X11InputMethodBase_<method>_<desc>'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot with an X11 display and an XIM server running, the native
 * side of X11InputMethodBase drives the per-adapter XIM state through
 * libX11's XIM API. The state struct (X11InputMethodData) holds the
 * XIM handle opened by XOpenIM, the XIC handle created by XCreateIC,
 * and the preedit and status callbacks that the server invokes when
 * the user composes text.
 *
 *   initIDs                     caches the JNI field IDs of
 *                               X11InputMethodBase's own fields so the
 *                               other natives can reach into the Java
 *                               object without repeated lookups.
 *
 *   turnoffStatusWindow         calls XUnsetICFocus or sets the
 *                               XNPreeditAttributes / XNStatusAttributes
 *                               of the XIC so the server hides the
 *                               status window.
 *
 *   disposeXIC                  calls XDestroyIC on the XIC handle,
 *                               releasing the server's resources for
 *                               that input context.
 *
 *   resetXIC                    calls XmbResetIC (or the Xutf8
 *                               equivalent) on the XIC, which returns
 *                               any pending preedit text as a
 *                               NUL-terminated byte string that the
 *                               native then wraps in a Java String.
 *
 *   setCompositionEnabledNative calls XSetICValues with
 *                               XNPreeditState set to XIMPreeditEnable
 *                               or XIMPreeditDisable, returning true on
 *                               success and false on XSetICValues
 *                               failure.
 *
 *   isCompositionEnabledNative  calls XGetICValues with XNPreeditState,
 *                               and returns true iff the returned state
 *                               is XIMPreeditEnable.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no XIM server, no XIC, and no
 * per-adapter native state. GraphicsEnvironment.isHeadless() is true
 * on every reachable path, so:
 *
 *   - X11InputMethodBase's static initializer is executed (the
 *     highlight-style table is built unconditionally), but the class's
 *     constructor is reached only from the XIM-aware InputContext
 *     selection machinery, which itself is only exercised when a real
 *     display is present.
 *
 *   - No concrete XInputMethod or X11InputMethod instance is ever
 *     constructed through the normal path in a compiled image: the
 *     InputContext's input-method selection returns a
 *     NullInputMethodDescriptor instead of the XIM descriptor when the
 *     environment is headless.
 *
 * Every native is nevertheless emitted into the module because the
 * bytecode of the two concrete subclasses references them. The six
 * implementations below provide the answers that keep the class
 * linkable and internally consistent:
 *
 *   initIDs                     no-op. There are no JNI field IDs to
 *                               cache in this runtime.
 *
 *   turnoffStatusWindow         no-op. There is no status window to
 *                               hide.
 *
 *   disposeXIC                  no-op. There is no XIC to destroy, and
 *                               the disposal path is reached only from
 *                               disposeImpl, which is itself reached
 *                               only for an instance that was never
 *                               successfully constructed.
 *
 *   resetXIC                    returns null. The Java-side callers
 *                               (endComposition and resetXICifneeded)
 *                               treat a null return as "no pending
 *                               preedit text to commit", which is the
 *                               truthful state when there is no XIC in
 *                               the first place.
 *
 *   setCompositionEnabledNative returns false. The Java-side caller
 *                               (setCompositionEnabled) is documented
 *                               to declare UnsupportedOperationException
 *                               when the platform cannot honour the
 *                               request; returning false steers it to
 *                               the "not supported" branch via the
 *                               compositionEnableSupported flag.
 *
 *   isCompositionEnabledNative  returns false. On HotSpot this method
 *                               declares UnsupportedOperationException
 *                               when composition control is not
 *                               available; returning false is the
 *                               "composition is off" answer, which the
 *                               Java-side getCompositionState() catches
 *                               by way of the compositionEnableSupported
 *                               flag and treats as "feature unavailable".
 *                               Returning false also short-circuits the
 *                               exception path, which is what the
 *                               compositionEnableSupported flag is
 *                               designed to achieve.
 *
 * These are genuine "no XIM" answers, not stubs that abort. Aborting
 * would break the constructor of every X11InputMethodBase subclass —
 * the class's own constructor calls initXIM(), which calls openXIM(),
 * and openXIM is the abstract method that the concrete subclasses
 * implement by calling the (already no-op) native layer — even though
 * the whole input-method subsystem is gated behind a headless check
 * that prevents it from ever running in this runtime. Returning the
 * same values that HotSpot produces for a server without XIM keeps
 * those callers on paths they already handle.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * initIDs is a STATIC native: no receiver argument. It takes no
 * parameters and returns void.
 *
 * The other five are INSTANCE natives: the receiver is the first
 * argument.
 *
 *   turnoffStatusWindow()      receiver, void
 *   disposeXIC()               receiver, void
 *   resetXIC()                 receiver, returns String (i8*)
 *   setCompositionEnabledNative(boolean)  receiver, int32_t, returns i1
 *   isCompositionEnabledNative()          receiver, returns i1
 *
 * The boolean parameter of setCompositionEnabledNative and the boolean
 * returns of both composition natives are represented as int32_t at the
 * C ABI level, matching every other boolean native in this runtime
 * (Thread.isAlive, Net.isIPv6Available0, Deflater.init, …).
 *
 * The return type of resetXIC is a Java String reference (i8* at the
 * LLVM level). A NULL return is the "no preedit text to commit"
 * sentinel that the Java-side caller checks against.
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
 *   class "sun/awt/X11InputMethodBase" -> "sun_awt_X11InputMethodBase"
 *
 *   initIDs                     desc "()V"
 *       -> "__jnative_fn_sun_awt_X11InputMethodBase_initIDs___V"
 *   turnoffStatusWindow         desc "()V"
 *       -> "__jnative_fn_sun_awt_X11InputMethodBase_turnoffStatusWindow___V"
 *   disposeXIC                  desc "()V"
 *       -> "__jnative_fn_sun_awt_X11InputMethodBase_disposeXIC___V"
 *   resetXIC                    desc "()Ljava/lang/String;"
 *       -> "__jnative_fn_sun_awt_X11InputMethodBase_resetXIC___Ljava_lang_String_"
 *   setCompositionEnabledNative desc "(Z)Z"
 *       -> "__jnative_fn_sun_awt_X11InputMethodBase_setCompositionEnabledNative__Z_Z"
 *   isCompositionEnabledNative  desc "()Z"
 *       -> "__jnative_fn_sun_awt_X11InputMethodBase_isCompositionEnabledNative___Z"
 *
 * The disposeXIC and isCompositionEnabledNative names are the two the
 * linker reported as undefined; the other four are emitted as well and
 * would produce the same diagnostic on the next rebuild if left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/X11InputMethodBase") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/awt/X11InputMethodBase.c". That is the
 * path this file must live at for Compiler.extractSystemNativeSources
 * to find it and compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native void initIDs();
 *
 * Called from X11InputMethodBase.<clinit>. On HotSpot this hook caches
 * the JNI field IDs of the class's own fields so the instance natives
 * can reach into the Java object without repeated lookups. This runtime
 * accesses every field through its LLVM-computed byte offset and never
 * consults JNI field IDs, so there is nothing to cache.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_X11InputMethodBase_initIDs___V(void) {
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * protected native void turnoffStatusWindow();
 *
 * No-op. On HotSpot this hides the status window that the XIM server
 * owns for this adapter's input context. There is no XIC and no status
 * window in this runtime.
 */
void __jnative_fn_sun_awt_X11InputMethodBase_turnoffStatusWindow___V(
        void* this_im)
{
    (void)this_im;
}

/*
 * protected native void disposeXIC();
 *
 * No-op. On HotSpot this destroys the input context associated with
 * this adapter, releasing the server-side resources it holds. Called
 * from disposeImpl on every disposal path. This runtime never created
 * an XIC — see the file header — so there is nothing to release.
 */
void __jnative_fn_sun_awt_X11InputMethodBase_disposeXIC___V(
        void* this_im)
{
    (void)this_im;
}

/*
 * private native String resetXIC();
 *
 * Returns null. On HotSpot this resets the XIC to its initial state
 * and returns any preedit text that the server had accumulated before
 * the reset, or null when there was none. The Java-side callers
 * (endComposition and resetXICifneeded) treat a null return as "no
 * pending text to commit", which is the truthful state when there is
 * no XIC in the first place.
 */
void* __jnative_fn_sun_awt_X11InputMethodBase_resetXIC___Ljava_lang_String_(
        void* this_im)
{
    (void)this_im;
    return NULL;
}

/*
 * protected native boolean setCompositionEnabledNative(boolean enable);
 *
 * Returns false. On HotSpot this toggles the XIC's composition mode
 * and returns true on success. The Java-side caller
 * (setCompositionEnabled) is documented to declare
 * UnsupportedOperationException when the platform cannot honour the
 * request; returning false steers it to the "not supported" branch
 * via the compositionEnableSupported flag.
 *
 * The `enable` argument is captured and discarded: with no XIC there
 * is no composition mode to set.
 */
int32_t __jnative_fn_sun_awt_X11InputMethodBase_setCompositionEnabledNative__Z_Z(
        void* this_im, int32_t enable)
{
    (void)this_im;
    (void)enable;
    return 0;
}

/*
 * private native boolean isCompositionEnabledNative();
 *
 * Returns false. On HotSpot this reads the XIC's composition mode and
 * declares UnsupportedOperationException when composition control is
 * not available. The Java-side getCompositionState() catches the
 * exception and sets compositionEnableSupported = false, which makes
 * every subsequent composition query return false without a native
 * call. Returning false directly (rather than throwing) reaches the
 * same end state with one fewer round trip through the exception
 * machinery.
 */
int32_t __jnative_fn_sun_awt_X11InputMethodBase_isCompositionEnabledNative___Z(
        void* this_im)
{
    (void)this_im;
    return 0;
}
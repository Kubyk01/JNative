#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.awt.X11.XInputMethod — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares four native methods, all of them reached from
 * bytecode that the reachability walk pulls into the compiled image:
 *
 *   openXIMNative      — from openXIM(), called by the base class
 *                        X11InputMethod during the XIM connection
 *                        lifecycle.
 *
 *   createXICNative    — from createXIC(), called by the base class
 *                        after openXIM() has succeeded.
 *
 *   setXICFocusNative  — from setXICFocus(), called whenever the client
 *                        component's focus changes.
 *
 *   adjustStatusWindow — from notifyClientWindowChange(), called when
 *                        the client window is moved or resized.
 *
 * The LLVM emitter records a call site for each in the generated IR, so
 * without definitions in the linked executable clang rejects
 * sun/awt/X11/XInputMethod.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_X11_XInputMethod_adjustStatusWindow__J_V'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, XInputMethod is the X11 XIM (X Input Method) adapter: the
 * legacy input-method protocol that connects a Java AWT text component
 * to the user's installed IME (fcitx, ibus, SCIM, …) through the X
 * server's input-method server.
 *
 *   openXIMNative      calls XOpenIM on the display connection and
 *                      stores the resulting XIM handle.
 *
 *   createXICNative    calls XCreateIC to create a per-component input
 *                      context bound to the XIM handle, installs the
 *                      preedit and status callbacks, and remembers the
 *                      context so subsequent focus changes route
 *                      through it.
 *
 *   setXICFocusNative  calls XSetICFocus / XUnsetICFocus to tell the
 *                      XIM server that the input focus has moved to or
 *                      away from the component, so the server can
 *                      direct the preedit and status windows to the
 *                      right place.
 *
 *   adjustStatusWindow calls XMoveWindow / XResizeWindow on the status
 *                      window that the XIM server owns, keeping it
 *                      aligned with the client component as the
 *                      component moves and resizes.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no XIM server, and no display.
 * GraphicsEnvironment.isHeadless() is true on every reachable path, so
 * no AWT text component ever acquires a native peer, no XComponentPeer
 * is created, and the base class's dispatch into these natives either
 * never happens or happens with a zero display/window argument.
 *
 * All four natives are therefore strict no-ops or defaulted returns:
 *
 *   openXIMNative      — returns false. The base class X11InputMethod
 *                        interprets a false return as "this input
 *                        method has no native backend to route
 *                        through" and does not proceed to createXIC.
 *                        This is exactly the same answer HotSpot
 *                        produces when no XIM server is running.
 *
 *   createXICNative    — returns false. Same reasoning; the base class
 *                        treats a false return as "this input method
 *                        cannot service a client component" and falls
 *                        back to the platform's default behaviour.
 *
 *   setXICFocusNative  — no-op. There is no IC to focus or unfocus.
 *
 *   adjustStatusWindow — no-op. There is no status window to move.
 *
 * These are genuine "input method unavailable" answers, not stubs that
 * abort. The base class's lifecycle is documented to tolerate a false
 * return from openXIM: on HotSpot, a client that has no XIM server
 * installed takes exactly this path and the AWT text components still
 * function (they simply do not support IME-mediated composition). In
 * this runtime the same fallback applies, and every text component
 * works without IME composition, which is the only behaviour a headless
 * runtime can offer.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * Every native here is an instance method, so the receiver is the first
 * argument. The Java-level method signatures are:
 *
 *   private native boolean openXIMNative(long display);
 *   private native boolean createXICNative(long window);
 *   private native void    setXICFocusNative(long window,
 *                                            boolean value,
 *                                            boolean active);
 *   private native void    adjustStatusWindow(long window);
 *
 * The `display`, `window` arguments are the native X11 Display* and
 * Window handles, both carried in the JVM as Java `long` values. In
 * this runtime they are always 0 (there is no display and no X window),
 * which the C side captures and discards.
 *
 * The boolean parameters value/active are represented as int32_t in
 * this runtime's C ABI, matching every other boolean parameter of every
 * other native (Thread.setDaemon, Net.bind0, Deflater.init, …).
 *
 * The return type of openXIMNative and createXICNative is boolean
 * (i1 at the IR level, int32_t at the C ABI level). The two void
 * natives have no return.
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
 *   class   "sun/awt/X11/XInputMethod" -> "sun_awt_X11_XInputMethod"
 *
 *   openXIMNative       desc "(J)Z"    -> "_J_Z"
 *       full "__jnative_fn_sun_awt_X11_XInputMethod_openXIMNative__J_Z"
 *
 *   createXICNative     desc "(J)Z"    -> "_J_Z"
 *       full "__jnative_fn_sun_awt_X11_XInputMethod_createXICNative__J_Z"
 *
 *   setXICFocusNative   desc "(JZZ)V"  -> "_JZZ_V"
 *       full "__jnative_fn_sun_awt_X11_XInputMethod_setXICFocusNative__JZZ_V"
 *
 *   adjustStatusWindow  desc "(J)V"    -> "_J_V"
 *       full "__jnative_fn_sun_awt_X11_XInputMethod_adjustStatusWindow__J_V"
 *
 * The last one is the symbol the linker reported as undefined; the
 * other three are emitted as well and would produce the same diagnostic
 * on the next rebuild if left out. All four are defined here.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/X11/XInputMethod") does not
 * strip a leading "java/" — the class is in the sun.* namespace — so
 * it returns "jnative/sun/awt/X11/XInputMethod.c". That is the path
 * this file must live at for Compiler.extractSystemNativeSources to
 * find it and compile it into the executable.
 */

/*
 * private native boolean openXIMNative(long display);
 *
 * Returns false. See the file header for the full rationale: the base
 * class X11InputMethod treats a false return from openXIM() as "no XIM
 * server is available" and takes the same fallback path it takes on any
 * HotSpot system without an IME installed. That fallback is exactly what
 * this runtime can provide, so a truthful false is the correct answer.
 */
int32_t __jnative_fn_sun_awt_X11_XInputMethod_openXIMNative__J_Z(
        void* this_im, int64_t display)
{
    (void)this_im;
    (void)display;
    return 0;
}

/*
 * private native boolean createXICNative(long window);
 *
 * Returns false. See the file header for the full rationale: the base
 * class treats a false return from createXIC() as "this input method
 * cannot service a client component" and falls back to the platform's
 * default behaviour, which for this runtime means text components
 * function without IME-mediated composition.
 */
int32_t __jnative_fn_sun_awt_X11_XInputMethod_createXICNative__J_Z(
        void* this_im, int64_t window)
{
    (void)this_im;
    (void)window;
    return 0;
}

/*
 * private native void setXICFocusNative(long window,
 *                                       boolean value,
 *                                       boolean active);
 *
 * No-op. There is no XIC (input context) to focus or unfocus, because
 * createXICNative never created one. The `active` argument distinguishes
 * the two flavours of focus change the Java layer requests (an actual
 * focus change versus a global activation update); both are absorbed by
 * the same no-op.
 */
void __jnative_fn_sun_awt_X11_XInputMethod_setXICFocusNative__JZZ_V(
        void* this_im, int64_t window, int32_t value, int32_t active)
{
    (void)this_im;
    (void)window;
    (void)value;
    (void)active;
}

/*
 * private native void adjustStatusWindow(long window);
 *
 * No-op. There is no XIM status window to reposition, because
 * openXIMNative never opened an XIM session. On HotSpot this is where
 * the status window — the small floating panel the IME uses to show
 * candidate text or composition state — is kept aligned with the client
 * component as the component moves and resizes. With no status window
 * and no client component peer, there is nothing to align.
 */
void __jnative_fn_sun_awt_X11_XInputMethod_adjustStatusWindow__J_V(
        void* this_im, int64_t window)
{
    (void)this_im;
    (void)window;
}
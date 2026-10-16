#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#include "jnative_runtime.h"

/*
 * sun.awt.X11GraphicsEnvironment — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares nine native methods. Every one of them is called
 * from some reachable method of the class or of its callers, so the
 * LLVM emitter records a call site for each in the generated IR.
 * Without definitions in the linked executable clang rejects
 * sun/awt/X11GraphicsEnvironment.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_X11GraphicsEnvironment_getDefaultScreenNum___I'
 *
 * and stops. Providing only the symbol in the diagnostic would move the
 * error to the next one; all nine are defined here so the module links
 * in one pass:
 *
 *   initGLX             — static, GLX availability probe
 *   initXRender         — static, XRender availability probe
 *   checkShmExt         — static, MIT-SHM extension state query
 *   getDisplayString    — static, the X server display name
 *   initDisplay         — static, X display connection setup
 *   getNumScreens       — instance, number of X screens
 *   getDefaultScreenNum — instance, index of the default screen
 *   initNativeData      — static, per-screen native data refresh
 *   pRunningXinerama    — static, Xinerama state query
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, X11GraphicsEnvironment is the GraphicsEnvironment
 * implementation for a real X11 session. Its native side links against
 * libX11, libXext, GLX, XRender and MIT-SHM, and:
 *
 *   initDisplay         opens the display connection from the DISPLAY
 *                       environment variable and stores the resulting
 *                       Display* for every other native to use.
 *   getDisplayString    returns the display name from the connection.
 *   checkShmExt         probes the MIT-SHM extension.
 *   initGLX             probes the GLX 1.3 extension.
 *   initXRender         probes the XRender extension and its version.
 *   getNumScreens       reads ScreenCount(display).
 *   getDefaultScreenNum reads DefaultScreen(display).
 *   initNativeData      refreshes the per-screen native device data
 *                       (dimensions, DPI, multi-monitor layout) that
 *                       X11GraphicsDevice queries.
 *   pRunningXinerama    probes whether Xinerama is active.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no display, and no X server.
 * GraphicsEnvironment.isHeadless() is true on every reachable path, so
 * the static initializer in X11GraphicsEnvironment takes the headless
 * branch and never calls initDisplay, initGLX, initXRender or
 * initNativeData. The class's public constructor also takes the
 * headless branch and returns early. Every native is nevertheless
 * emitted into the module because the call sites exist in the
 * bytecode.
 *
 * The nine natives provide the answers that keep a runtime without an
 * X server internally consistent:
 *
 *   initGLX             returns false. No GLX extension is present.
 *   initXRender         returns false. No XRender extension is present.
 *   checkShmExt         returns -1. No MIT-SHM extension is present;
 *                       the Java-side caller interprets -1 as
 *                       "server doesn't support MITShm" and takes the
 *                       same fallback path it takes on a headless
 *                       server with no shared-memory pixmaps.
 *   getDisplayString    returns the empty string. There is no display
 *                       name to report; the Java-side callers all
 *                       guard against empty results.
 *   initDisplay         no-op. There is no connection to open.
 *   getNumScreens       returns 1. The Java-side caller
 *                       (initDevices) throws AWTError when the return
 *                       value is 0 and otherwise allocates exactly
 *                       that many X11GraphicsDevice objects; 1 is the
 *                       smallest honest answer and matches the runtime's
 *                       single virtual screen.
 *   getDefaultScreenNum returns 0, the index of the single virtual
 *                       screen.
 *   initNativeData      no-op. There is no per-screen native data to
 *                       refresh.
 *   pRunningXinerama    returns false. Xinerama is not active.
 *
 * These are genuine "no X11" answers, not stubs that abort. Every
 * Java-side caller of every native has a documented "no X11" branch
 * that it takes on a headless server, and returning the same values
 * that HotSpot returns on such a server keeps those callers on paths
 * they already handle.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * Six natives are STATIC: initGLX, initXRender, checkShmExt,
 * getDisplayString, initDisplay, initNativeData, pRunningXinerama.
 *
 * Two natives are INSTANCE: getNumScreens and getDefaultScreenNum. The
 * receiver is the first argument.
 *
 * The boolean parameters and boolean returns of initGLX, initXRender
 * and pRunningXinerama, and the boolean parameter of initDisplay, are
 * represented as int32_t at the C ABI level, matching every other
 * boolean native in this runtime.
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
 *   class "sun/awt/X11GraphicsEnvironment"
 *       -> "sun_awt_X11GraphicsEnvironment"
 *
 *   initGLX             "()Z"    -> "___Z"
 *   initXRender         "(ZZ)Z"  -> "__ZZ_Z"
 *   checkShmExt         "()I"    -> "___I"
 *   getDisplayString    "()Ljava/lang/String;" -> "___Ljava_lang_String_"
 *   initDisplay         "(Z)V"   -> "__Z_V"
 *   getNumScreens       "()I"    -> "___I"
 *   getDefaultScreenNum "()I"    -> "___I"
 *   initNativeData      "()V"    -> "___V"
 *   pRunningXinerama    "()Z"    -> "___Z"
 *
 * Full symbols:
 *
 *   __jnative_fn_sun_awt_X11GraphicsEnvironment_initGLX___Z
 *   __jnative_fn_sun_awt_X11GraphicsEnvironment_initXRender__ZZ_Z
 *   __jnative_fn_sun_awt_X11GraphicsEnvironment_checkShmExt___I
 *   __jnative_fn_sun_awt_X11GraphicsEnvironment_getDisplayString___Ljava_lang_String_
 *   __jnative_fn_sun_awt_X11GraphicsEnvironment_initDisplay__Z_V
 *   __jnative_fn_sun_awt_X11GraphicsEnvironment_getNumScreens___I
 *   __jnative_fn_sun_awt_X11GraphicsEnvironment_getDefaultScreenNum___I
 *   __jnative_fn_sun_awt_X11GraphicsEnvironment_initNativeData___V
 *   __jnative_fn_sun_awt_X11GraphicsEnvironment_pRunningXinerama___Z
 *
 * The getDefaultScreenNum name is the one the linker reported as
 * undefined; the others are emitted as well and would produce the same
 * diagnostic on the next rebuild if left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/X11GraphicsEnvironment") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns
 * "jnative/sun/awt/X11GraphicsEnvironment.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native boolean initGLX();
 *
 * Returns false. On HotSpot this probes the GLX 1.3 extension on the
 * open X connection and stores the answer in the Java-side
 * glxAvailable field. With no X connection and no GLX extension, false
 * is the truthful answer; it steers every caller away from the
 * OpenGL-backed pipeline, which is exactly what every accelerated
 * pipeline selection path expects on a runtime without a GPU.
 *
 * STATIC native: no receiver argument.
 */
int32_t __jnative_fn_sun_awt_X11GraphicsEnvironment_initGLX___Z(void) {
    return 0;
}

/*
 * private static native boolean initXRender(boolean verbose,
 *                                           boolean ignoreLinuxVersion);
 *
 * Returns false. On HotSpot this probes the XRender extension and
 * stores the answer in the Java-side xRenderAvailable field. With no X
 * connection and no XRender extension, false is the truthful answer;
 * it steers every caller away from the XRender-backed pipeline.
 *
 * Both arguments are captured and discarded. The `verbose` flag would
 * enable diagnostic output during the probe; there is nothing to
 * probe.
 *
 * STATIC native: no receiver argument.
 */
int32_t __jnative_fn_sun_awt_X11GraphicsEnvironment_initXRender__ZZ_Z(
        int32_t verbose, int32_t ignore_linux_version)
{
    (void)verbose;
    (void)ignore_linux_version;
    return 0;
}

/*
 * private static native int checkShmExt();
 *
 * Returns -1, the "server doesn't support MITShm" sentinel. On HotSpot
 * this probes the MIT-SHM extension and returns:
 *
 *   -1 : server does not support MIT-SHM
 *    1 : server supports MIT-SHM and it can be used
 *    0 : server supports MIT-SHM but it cannot be used
 *
 * The Java-side caller (_isDisplayLocal) interprets -1 as "fall through
 * to the display-string-based locality check", which is the correct
 * behaviour on a runtime without shared-memory pixmaps. Returning 1
 * would falsely claim that shared-memory pixmaps are available and
 * would send every subsequent allocation down an accelerated path the
 * runtime cannot support.
 *
 * STATIC native: no receiver argument.
 */
int32_t __jnative_fn_sun_awt_X11GraphicsEnvironment_checkShmExt___I(void) {
    return -1;
}

/*
 * private static native String getDisplayString();
 *
 * Returns the empty string. On HotSpot this returns the display name
 * from the open X connection (e.g. ":0" for the local display, or
 * "host:0" for a remote one). With no X connection there is no display
 * name to report; the Java-side caller (_isDisplayLocal) guards against
 * an empty result and takes its remote-display path.
 *
 * STATIC native: no receiver argument.
 */
void* __jnative_fn_sun_awt_X11GraphicsEnvironment_getDisplayString___Ljava_lang_String_(void) {
    return __jnative_make_string_obj("", 0);
}

/*
 * private static native void initDisplay(boolean glxRequested);
 *
 * No-op. On HotSpot this opens the display connection from the DISPLAY
 * environment variable and stores the resulting Display* for every
 * other native to use. The `glxRequested` argument informs the native
 * of the caller's preference before the probe runs. This runtime has
 * no X connection to open and no per-display state to store; the no-op
 * is the only truthful behaviour.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_X11GraphicsEnvironment_initDisplay__Z_V(
        int32_t glx_requested)
{
    (void)glx_requested;
}

/*
 * private static native void initNativeData();
 *
 * No-op. On HotSpot this refreshes the per-screen native device data
 * (dimensions, DPI, multi-monitor layout) that X11GraphicsDevice
 * queries. There is no per-screen native data to refresh in a runtime
 * with no X server.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_X11GraphicsEnvironment_initNativeData___V(void) {
}

/*
 * private static native boolean pRunningXinerama();
 *
 * Returns false. On HotSpot this probes whether Xinerama is active,
 * which the Java-side isDisplayChangeSupported() uses to decide
 * whether full-screen mode changes are permitted. Xinerama is not
 * active on a runtime with no X server; false is the truthful answer
 * and steers the caller to its single-screen behavior.
 *
 * STATIC native: no receiver argument.
 */
int32_t __jnative_fn_sun_awt_X11GraphicsEnvironment_pRunningXinerama___Z(void) {
    return 0;
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * protected native int getNumScreens();
 *
 * Returns 1. The Java-side caller (initDevices) throws AWTError when
 * the return value is 0 and otherwise allocates exactly that many
 * X11GraphicsDevice objects, indexed 0..numScreens-1. 1 is the
 * smallest honest answer for a runtime that presents a single virtual
 * screen and matches what HotSpot returns for a single-head display.
 *
 * Returning 0 would make initDevices throw AWTError("no screen
 * devices") and abort every code path that constructs a
 * GraphicsEnvironment — including code that never draws anything.
 */
int32_t __jnative_fn_sun_awt_X11GraphicsEnvironment_getNumScreens___I(
        void* this_env)
{
    (void)this_env;
    return 1;
}

/*
 * private native int getDefaultScreenNum();
 *
 * Returns 0, the index of the single virtual screen. The Java-side
 * caller (initDevices) uses the value as the index of the main screen
 * in the devices map; 0 is the correct answer for a runtime with one
 * virtual screen, and it is what HotSpot returns when the default
 * screen is screen 0 (the common case on every single-head display).
 */
int32_t __jnative_fn_sun_awt_X11GraphicsEnvironment_getDefaultScreenNum___I(
        void* this_env)
{
    (void)this_env;
    return 0;
}
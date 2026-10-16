#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.awt.X11GraphicsConfig — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares eleven native methods. Every one of them is called
 * from some reachable method of the class, so the LLVM emitter records
 * a call site for each in the generated IR. Without definitions in the
 * linked executable, clang rejects sun/awt/X11GraphicsConfig.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_X11GraphicsConfig_createBackBuffer__JI_J'
 *
 * and stops. Providing only the symbol in the diagnostic would move the
 * error to the next one; all eleven are defined here so the module
 * links in one pass:
 *
 *   getNumColors              — X11 colormap entry count
 *   init                      — per-instance native state setup
 *   makeColorModel            — construct the config's ColorModel
 *   getXResolution            — DPI query, X axis
 *   getYResolution            — DPI query, Y axis
 *   initIDs                   — X11GraphicsConfig.<clinit> hook
 *   dispose                   — release a native AwtGraphicsConfigData
 *   createBackBuffer          — XDBE back-buffer allocation
 *   swapBuffers               — XDBE page-flip
 *   destroyBackBuffer         — XDBE back-buffer release
 *   isTranslucencyCapable     — pixel-format support probe
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, X11GraphicsConfig is the GraphicsConfiguration for a
 * single X11 visual. Its native side links against libX11, libXext and
 * XDBE, and drives the C-side state that every X11 peer needs:
 *
 *   getNumColors              reads the number of entries in the
 *                             visual's colormap.
 *
 *   init                      allocates the AwtGraphicsConfigData
 *                             struct that holds the visual's
 *                             XVisualInfo, colormap, depth, and
 *                             pixel-format information, and stores its
 *                             address in the Java-side aData field.
 *
 *   makeColorModel            constructs a ColorModel that matches the
 *                             visual's pixel format.
 *
 *   getXResolution/getYResolution
 *                             read the display's DPI from the X server
 *                             and return it as a double.
 *
 *   initIDs                   caches the JNI field IDs used by the
 *                             other natives.
 *
 *   dispose                   releases the AwtGraphicsConfigData
 *                             struct.
 *
 *   createBackBuffer          allocates an XDBE back buffer for the
 *                             given X window and registers the
 *                             associated swap action.
 *
 *   swapBuffers               performs the XDBE page-flip.
 *
 *   destroyBackBuffer         releases the XDBE back buffer.
 *
 *   isTranslucencyCapable     checks whether the visual's pixel format
 *                             has an alpha channel the compositor can
 *                             blend against.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no display, and no XDBE extension.
 * GraphicsEnvironment.isHeadless() is true on every reachable path, so:
 *
 *   - The X11GraphicsConfig constructor calls init(visualnum, screen)
 *     unconditionally (it is the first thing the constructor does after
 *     storing the Java-side fields), but the configuration object is
 *     never used to create a real window because no peer ever asks it
 *     to.
 *
 *   - makeColorModel is called lazily from getColorModel() the first
 *     time the color model is read; the Java-side code already handles
 *     a null return by falling back to
 *     Toolkit.getDefaultToolkit().getColorModel().
 *
 *   - createBackBuffer is called only from X11ComponentPeer's
 *     createBackBuffer path, and only after isDBESupported() has
 *     returned true — which it never does in this runtime (see
 *     jnative/sun/awt/X11GraphicsDevice.c). The Java caller is
 *     therefore never reached.
 *
 * The eleven natives provide the answers that keep a headless runtime
 * internally consistent:
 *
 *   getNumColors              returns 256. A power-of-two colour count
 *                             that every downstream ColorModel path
 *                             handles without special-casing.
 *
 *   init                      no-op. There is no AwtGraphicsConfigData
 *                             struct to allocate; the Java-side aData
 *                             field stays 0, which the Disposer
 *                             correctly treats as "nothing to release".
 *
 *   makeColorModel            returns null. The Java-side getColorModel
 *                             checks for null and substitutes
 *                             Toolkit.getDefaultToolkit().getColorModel(),
 *                             which is the runtime's canonical default
 *                             (an sRGB DirectColorModel). That is the
 *                             correct color model for a runtime whose
 *                             only actual pixel storage is
 *                             BufferedImage-backed.
 *
 *   getXResolution            returns 96.0. The canonical Linux desktop
 *                             DPI, matching what X servers report on a
 *                             standard 96 DPI display.
 *
 *   getYResolution            returns 96.0.
 *
 *   initIDs                   no-op (no JNI field IDs are cached in
 *                             this runtime).
 *
 *   dispose                   no-op. The Java-side Disposer passes 0
 *                             for any config whose init() call was a
 *                             no-op (aData is left at 0), so this is
 *                             not reached with a live pointer anyway.
 *
 *   createBackBuffer          returns 0. The Java caller never reaches
 *                             it because isDBESupported() is false; if
 *                             a future caller did, 0 is the "no buffer
 *                             allocated" sentinel the Java-side
 *                             caller checks against.
 *
 *   swapBuffers               no-op. There is no back buffer to flip.
 *
 *   destroyBackBuffer         no-op. There is no back buffer to release.
 *
 *   isTranslucencyCapable     returns 0 (false). The reference
 *                             implementation checks for a pixel format
 *                             with an alpha channel; a runtime with no
 *                             X server cannot have one, and returning
 *                             false keeps the caller on the opaque path
 *                             that every downstream SurfaceData
 *                             expects.
 *
 * These are genuine "no X11" answers, not stubs that abort. Aborting
 * would break every AWT initialisation that constructs an
 * X11GraphicsConfig — including the many JDK classes that probe
 * GraphicsEnvironment.getLocalGraphicsEnvironment() before deciding
 * whether any AWT operation is possible. Returning the same defaults
 * that HotSpot returns for a headless-capable screen keeps those
 * callers on paths they already handle.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * Two of the eleven natives are STATIC: initIDs and dispose. The other
 * nine are INSTANCE natives: the receiver is the first argument.
 *
 * The boolean return of isTranslucencyCapable is represented as
 * int32_t at the C ABI level, matching every other boolean native in
 * this runtime (Thread.isAlive, Net.isIPv6Available0, Deflater.init, …).
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
 *   class "sun/awt/X11GraphicsConfig" -> "sun_awt_X11GraphicsConfig"
 *
 *   getNumColors          desc "()I"
 *       -> "__jnative_fn_sun_awt_X11GraphicsConfig_getNumColors___I"
 *   init                  desc "(II)V"
 *       -> "__jnative_fn_sun_awt_X11GraphicsConfig_init__II_V"
 *   makeColorModel        desc "()Ljava/awt/image/ColorModel;"
 *       -> "__jnative_fn_sun_awt_X11GraphicsConfig_makeColorModel___Ljava_awt_image_ColorModel_"
 *   getXResolution        desc "(I)D"
 *       -> "__jnative_fn_sun_awt_X11GraphicsConfig_getXResolution__I_D"
 *   getYResolution        desc "(I)D"
 *       -> "__jnative_fn_sun_awt_X11GraphicsConfig_getYResolution__I_D"
 *   initIDs               desc "()V"
 *       -> "__jnative_fn_sun_awt_X11GraphicsConfig_initIDs___V"
 *   dispose               desc "(J)V"
 *       -> "__jnative_fn_sun_awt_X11GraphicsConfig_dispose__J_V"
 *   createBackBuffer      desc "(JI)J"
 *       -> "__jnative_fn_sun_awt_X11GraphicsConfig_createBackBuffer__JI_J"
 *   swapBuffers           desc "(JI)V"
 *       -> "__jnative_fn_sun_awt_X11GraphicsConfig_swapBuffers__JI_V"
 *   destroyBackBuffer     desc "(J)V"
 *       -> "__jnative_fn_sun_awt_X11GraphicsConfig_destroyBackBuffer__J_V"
 *   isTranslucencyCapable desc "(J)Z"
 *       -> "__jnative_fn_sun_awt_X11GraphicsConfig_isTranslucencyCapable__J_Z"
 *
 * The createBackBuffer name is the one the linker reported as
 * undefined; the others are emitted as well and would produce the same
 * diagnostic on the next rebuild if left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/X11GraphicsConfig") does not
 * strip a leading "java/" — the class is in the sun.* namespace — so it
 * returns "jnative/sun/awt/X11GraphicsConfig.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native void initIDs();
 *
 * Called from X11GraphicsConfig.<clinit>. On HotSpot this hook caches
 * the JNI field IDs of X11GraphicsConfig and its inner classes so the
 * other natives can reach into those objects without repeated lookups.
 * This runtime accesses every field through its LLVM-computed byte
 * offset and never consults JNI field IDs, so there is nothing to
 * cache. The symbol must exist because the class's static initializer
 * emits a native call to it.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_X11GraphicsConfig_initIDs___V(void) {
}

/*
 * private static native void dispose(long x11ConfigData);
 *
 * No-op. Called from X11GCDisposerRecord.dispose() with the address of
 * a native AwtGraphicsConfigData struct, and only when that address is
 * non-zero. In this runtime init() is a no-op, so the Java-side aData
 * field is always 0, and the Disposer's own guard prevents this
 * function from being invoked at all. The native still needs a
 * definition because the call site exists in the bytecode.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_X11GraphicsConfig_dispose__J_V(int64_t x11_config_data) {
    (void)x11_config_data;
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * private native int getNumColors();
 *
 * Returns 256. On HotSpot this reads the number of entries in the
 * visual's colormap, which the ColorModel constructor uses to size its
 * color table. With no X server there is no colormap to query; 256 is
 * the power-of-two count that every downstream ColorModel path handles
 * without special-casing and is what the reference implementation
 * reports for a standard 8-bit-per-channel visual.
 */
int32_t __jnative_fn_sun_awt_X11GraphicsConfig_getNumColors___I(
        void* this_config)
{
    (void)this_config;
    return 256;
}

/*
 * private native void init(int visualNum, int screen);
 *
 * No-op. On HotSpot this allocates the AwtGraphicsConfigData struct
 * that holds the visual's XVisualInfo, colormap, depth and pixel-format
 * information, and stores its address in the Java-side aData field.
 * With no X server there is no XVisualInfo to query and no struct to
 * allocate; leaving aData at its zero-initialised value is the correct
 * state, and the Disposer's X11GCDisposerRecord (see the Java source)
 * correctly treats 0 as "nothing to release".
 */
void __jnative_fn_sun_awt_X11GraphicsConfig_init__II_V(
        void* this_config, int32_t visual_num, int32_t screen)
{
    (void)this_config;
    (void)visual_num;
    (void)screen;
}

/*
 * private native ColorModel makeColorModel();
 *
 * Returns null. On HotSpot this constructs a ColorModel that matches
 * the visual's pixel format. With no X server there is no pixel format
 * to match; the Java-side getColorModel() checks for a null return and
 * substitutes Toolkit.getDefaultToolkit().getColorModel(), which is
 * the runtime's canonical default — an sRGB DirectColorModel, the
 * correct model for a runtime whose only actual pixel storage is
 * BufferedImage-backed.
 */
void* __jnative_fn_sun_awt_X11GraphicsConfig_makeColorModel___Ljava_awt_image_ColorModel_(
        void* this_config)
{
    (void)this_config;
    return NULL;
}

/*
 * private native double getXResolution(int screen);
 *
 * Returns 96.0. On HotSpot this reads the display's DPI from the X
 * server. With no X server there is no DPI to query; 96.0 is the
 * canonical Linux desktop DPI and the value the reference
 * implementation reports on any standard display, so
 * getNormalizingTransform() produces the identity-preserving scaling
 * that graphics code expects.
 */
double __jnative_fn_sun_awt_X11GraphicsConfig_getXResolution__I_D(
        void* this_config, int32_t screen)
{
    (void)this_config;
    (void)screen;
    return 96.0;
}

/*
 * private native double getYResolution(int screen);
 *
 * Returns 96.0, for the same reason as getXResolution. A display with
 * non-square pixels would report different values for the two axes,
 * but no modern display has that property and no headless runtime can
 * have one at all.
 */
double __jnative_fn_sun_awt_X11GraphicsConfig_getYResolution__I_D(
        void* this_config, int32_t screen)
{
    (void)this_config;
    (void)screen;
    return 96.0;
}

/*
 * private native long createBackBuffer(long window, int swapAction);
 *
 * Returns 0. On HotSpot this allocates an XDBE back buffer for the
 * given X window and registers the swap action that a subsequent
 * page-flip will use. The Java-side caller
 * (X11GraphicsConfig.createBackBuffer(X11ComponentPeer, int,
 * BufferCapabilities)) is only reached after
 * X11GraphicsDevice.isDBESupported() returns true, and in this runtime
 * that method returns false — see jnative/sun/awt/X11GraphicsDevice.c.
 * The native is therefore never invoked at run time; it is emitted
 * because the call site exists in the bytecode, and 0 is the sentinel
 * the Java-side caller checks against if it ever were reached.
 */
int64_t __jnative_fn_sun_awt_X11GraphicsConfig_createBackBuffer__JI_J(
        void* this_config, int64_t window, int32_t swap_action)
{
    (void)this_config;
    (void)window;
    (void)swap_action;
    return 0;
}

/*
 * private native void swapBuffers(long window, int swapAction);
 *
 * No-op. On HotSpot this performs the XDBE page-flip that turns a
 * back buffer into the visible front buffer. The Java-side caller
 * (X11GraphicsConfig.flip(...)) is only reached on a peer that has
 * successfully created a back buffer through createBackBuffer, which
 * in this runtime never happens. There is nothing to flip.
 */
void __jnative_fn_sun_awt_X11GraphicsConfig_swapBuffers__JI_V(
        void* this_config, int64_t window, int32_t swap_action)
{
    (void)this_config;
    (void)window;
    (void)swap_action;
}

/*
 * public native void destroyBackBuffer(long backBuffer);
 *
 * No-op. On HotSpot this releases the XDBE back buffer whose handle
 * was returned by createBackBuffer. In this runtime createBackBuffer
 * returns 0, and the Java-side caller guards the destroy call on a
 * non-zero handle, so this native is not reached with a live handle.
 */
void __jnative_fn_sun_awt_X11GraphicsConfig_destroyBackBuffer__J_V(
        void* this_config, int64_t back_buffer)
{
    (void)this_config;
    (void)back_buffer;
}

/*
 * private native boolean isTranslucencyCapable(long x11ConfigData);
 *
 * Returns 0 (false). On HotSpot this checks whether the visual's pixel
 * format has an alpha channel that a compositing manager can blend
 * against. With no X server there is no visual and no compositor, so
 * no config is translucency-capable; returning false keeps the Java-
 * side caller (X11GraphicsConfig.isTranslucencyCapable()) on the
 * opaque path that every downstream SurfaceData path expects.
 */
int32_t __jnative_fn_sun_awt_X11GraphicsConfig_isTranslucencyCapable__J_Z(
        void* this_config, int64_t x11_config_data)
{
    (void)this_config;
    (void)x11_config_data;
    return 0;
}
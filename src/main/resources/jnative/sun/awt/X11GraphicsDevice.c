#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>

#include "jnative_runtime.h"

/*
 * sun.awt.X11GraphicsDevice — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares fifteen native methods. Every one of them is
 * called from some reachable method of the class or of its callers
 * (XToolkit, X11GraphicsEnvironment, X11GraphicsConfig), so the LLVM
 * emitter records a call site for each in the generated IR. Without
 * definitions in the linked executable, clang rejects the module with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_X11GraphicsDevice_getDisplay___J'
 *
 * and stops. Providing only the symbol named in the first diagnostic
 * would simply move the error to the next one; the whole set is defined
 * here so the module links in one pass:
 *
 *   getDisplay               — the X11 Display* of this device
 *   getNumConfigs            — number of visual configs for the screen
 *   getConfigVisualId        — visual id at a config index
 *   getConfigDepth           — bit depth at a config index
 *   getConfigColormap        — colormap id at a config index
 *   isDBESupported           — whether the DBE extension is present
 *   getDoubleBufferVisuals   — enumerate DBE-capable visuals
 *   enterFullScreenExclusive — X11 fullscreen entry
 *   exitFullScreenExclusive  — X11 fullscreen exit
 *   initXrandrExtension      — probe for Xrandr
 *   getCurrentDisplayMode    — read the current Xrandr mode
 *   enumDisplayModes         — enumerate Xrandr modes
 *   configDisplayMode        — switch Xrandr mode
 *   getNativeScaleFactor     — read the X11 DPI-derived scale
 *   pGetBounds               — screen bounds in device coordinates
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, X11GraphicsDevice is the GraphicsDevice for a single X11
 * screen. Its native side opens the display connection through XToolkit
 * and queries the X server via Xlib, Xrandr, Xrender and the
 * double-buffering extension (DBE):
 *
 *   getDisplay               returns the Display* that XToolkit opened.
 *   getNumConfigs            returns the X server's visual count for the
 *                            screen.
 *   getConfigVisualId        returns the visual id of one visual.
 *   getConfigDepth           returns the visual's depth in bits.
 *   getConfigColormap        returns the colormap id Xlib allocated.
 *   isDBESupported           probes the DBE extension.
 *   getDoubleBufferVisuals   fills the Java-side set with every visual
 *                            that DBE marks as double-buffer capable,
 *                            calling back into addDoubleBufferVisual for
 *                            each one.
 *   enterFullScreenExclusive reserves the full screen via Xrandr's
 *                            crtc / output protocol.
 *   exitFullScreenExclusive  releases it.
 *   initXrandrExtension      probes the Xrandr extension.
 *   getCurrentDisplayMode    queries Xrandr for the current mode.
 *   enumDisplayModes         queries Xrandr for the full mode list.
 *   configDisplayMode        applies an Xrandr mode change.
 *   getNativeScaleFactor     reads the X server's DPI and derives the
 *                            integer UI scale factor.
 *   pGetBounds               reads the X screen's pixel bounds from
 *                            XDisplayWidth / XDisplayHeight.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no display, and no X server to
 * talk to. GraphicsEnvironment.isHeadless() is true on every reachable
 * path, so X11GraphicsEnvironment never creates an X11GraphicsDevice
 * for a real screen — but the class is still pulled into the compiled
 * image by XToolkit's initialisation chain, and its natives must
 * resolve at link time.
 *
 * Every native here therefore returns the "no X11 present" answer or is
 * a no-op, matching the semantics the Java layer already handles for a
 * screen with no extensions:
 *
 *   getDisplay               returns 0 (a null Display*). XToolkit never
 *                            opens a connection in a headless runtime,
 *                            so no caller observes a non-zero value.
 *
 *   getNumConfigs            returns 1. A return of 0 would make
 *                            makeConfigurations() allocate a zero-length
 *                            GraphicsConfiguration[] and then assign
 *                            ret[0], raising ArrayIndexOutOfBounds. The
 *                            reference implementation always has at
 *                            least one visual; 1 is the smallest honest
 *                            answer that keeps the Java-side state
 *                            consistent.
 *
 *   getConfigVisualId        returns 0. This is the pseudo-visual id of
 *                            the default visual, and it is the value
 *                            the Java side would pass to
 *                            X11GraphicsConfig.getConfig — which in a
 *                            headless runtime is never constructed.
 *
 *   getConfigDepth           returns 24. The reference implementation
 *                            reports the actual depth of the visual; 24
 *                            is the depth of every default X11 visual
 *                            and is safe to store in the Java-side
 *                            GraphicsConfiguration.
 *
 *   getConfigColormap        returns 0. This is the default colormap of
 *                            the screen; with no X server there is no
 *                            colormap to allocate.
 *
 *   isDBESupported           returns false. No DBE extension is present.
 *
 *   getDoubleBufferVisuals   no-op. The Java side only invokes it when
 *                            isDBESupported() returned true, and then
 *                            only to fill doubleBufferVisuals; with no
 *                            DBE there is nothing to enumerate.
 *
 *   enterFullScreenExclusive no-op. isFullScreenSupported() returns
 *                            false because initXrandrExtension returns
 *                            false, so this native is never invoked from
 *                            a reachable path.
 *
 *   exitFullScreenExclusive  no-op. Same reasoning.
 *
 *   initXrandrExtension      returns false. No Xrandr extension is
 *                            present, so isFullScreenSupported() and
 *                            isDisplayChangeSupported() both return
 *                            false and every display-mode operation
 *                            falls back to the Java-layer defaults.
 *
 *   getCurrentDisplayMode    returns null. The Java-side caller
 *                            substitutes getDefaultDisplayMode() when
 *                            the native returns null, which is exactly
 *                            the correct answer when there is no Xrandr
 *                            to query.
 *
 *   enumDisplayModes         no-op. The Java side only invokes it after
 *                            isFullScreenSupported() has returned true
 *                            and Xinerama is off; in this runtime
 *                            isFullScreenSupported() is false, so the
 *                            fallback path (super.getDisplayModes())
 *                            runs instead and this native is
 *                            unreachable.
 *
 *   configDisplayMode        no-op. isDisplayChangeSupported() returns
 *                            false, so setDisplayMode() short-circuits
 *                            to super.setDisplayMode and never reaches
 *                            this native.
 *
 *   getNativeScaleFactor     returns 1.0. Without an X server there is
 *                            no DPI to query; the Java-side
 *                            initScaleFactor rounds this to 1 and uses
 *                            it as the fallback UI scale.
 *
 *   pGetBounds               returns a Rectangle with a nominal
 *                            headless size. getBoundsImpl() dereferences
 *                            the return value immediately, so a null
 *                            return would NPE the constructor; the
 *                            Rectangle is allocated with a valid vtable
 *                            and its x/y/width/height fields are
 *                            written through the emitter-computed
 *                            offsets.
 *
 * These are genuine "no X11" answers, not stubs that abort. Aborting
 * would break every AWT initialisation that constructs an
 * X11GraphicsDevice — including the many JDK classes that probe
 * GraphicsEnvironment.getLocalGraphicsEnvironment() before deciding
 * whether any AWT operation is possible. Returning the same defaults
 * that HotSpot returns for a screen with no extensions keeps those
 * callers on a path they already handle.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * The nine instance methods take the receiver first, followed by their
 * Java-level parameters. The six static methods (isDBESupported,
 * enter/exitFullScreenExclusive, initXrandrExtension,
 * getCurrentDisplayMode, enumDisplayModes, configDisplayMode,
 * getNativeScaleFactor) have no receiver.
 *
 * The boolean return of isDBESupported, initXrandrExtension, and the
 * boolean parameters in the two fullscreen natives are represented as
 * int32_t at the C ABI level, matching every other boolean native in
 * this runtime (Thread.setAlive, Net.isIPv6Available0,
 * Deflater.init, …).
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/X11GraphicsDevice") does not
 * strip a leading "java/" — the class is in the sun.* namespace — so
 * it returns "jnative/sun/awt/X11GraphicsDevice.c". That is the path
 * this file must live at for Compiler.extractSystemNativeSources to
 * find it and compile it into the executable.
 */

/*
 * Find the byte offset of an instance field by walking the reflection
 * metadata emitted by LlvmGlobalEmitter. Uses the same @reffields_*
 * tables the generated bytecode consults, so the returned offset
 * agrees byte-for-byte with what a direct GET_FIELD / PUT_FIELD would
 * have used. Returns -1 when the field is not present.
 */
static int x11gd_find_field_offset(ReflectionClass* cls, const char* field_name) {
    ReflectionClass* cur = cls;
    while (cur != NULL) {
        if (cur->fields != NULL) {
            ReflectionField** fp = cur->fields;
            while (*fp != NULL) {
                ReflectionField* f = *fp;
                if (f->name != NULL
                    && strcmp((const char*)f->name, field_name) == 0) {
                    return f->offset;
                }
                fp++;
            }
        }
        cur = cur->superclass;
    }
    return -1;
}

/*
 * Allocate a java.awt.Rectangle whose x/y/width/height fields are set
 * to the given values. The Rectangle is created through the runtime's
 * canonical object allocator, so its vtable is the one the emitter
 * emitted and its instance fields land at the offsets the generated
 * bytecode expects.
 *
 * Returns NULL on allocation failure, which the caller treats as "no
 * bounds available" and substitutes a zero-size rectangle for.
 */
static void* make_rectangle(int32_t x, int32_t y, int32_t w, int32_t h) {
    ReflectionClass* cls = jnative_class_by_name("java/awt/Rectangle");
    if (cls == NULL) return NULL;

    void* r = jnative_alloc_object(cls);
    if (r == NULL) return NULL;

    int off_x      = x11gd_find_field_offset(cls, "x");
    int off_y      = x11gd_find_field_offset(cls, "y");
    int off_width  = x11gd_find_field_offset(cls, "width");
    int off_height = x11gd_find_field_offset(cls, "height");

    if (off_x      >= 0) *(int32_t*)((char*)r + off_x)      = x;
    if (off_y      >= 0) *(int32_t*)((char*)r + off_y)      = y;
    if (off_width  >= 0) *(int32_t*)((char*)r + off_width)  = w;
    if (off_height >= 0) *(int32_t*)((char*)r + off_height) = h;

    return r;
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * public native long getDisplay();
 *
 * Returns 0. This runtime has no X11 connection, so there is no
 * Display* to return; every caller that would dereference the result
 * (XToolkit's screen enumeration, XInputMethod's XIM open path, …) is
 * itself guarded by GraphicsEnvironment.isHeadless() and never runs.
 */
int64_t __jnative_fn_sun_awt_X11GraphicsDevice_getDisplay___J(void* this_device) {
    (void)this_device;
    return 0;
}

/*
 * public native int getNumConfigs(int screen);
 *
 * Returns 1. The value drives makeConfigurations()'s loop bound and
 * must therefore be >= 1: a zero return would cause the Java side to
 * allocate an empty GraphicsConfiguration[] and then write ret[0],
 * raising ArrayIndexOutOfBounds. The single entry is the default
 * configuration, which is what a screen with no extra visuals would
 * legitimately report.
 *
 * The `screen` argument is captured and discarded; there is only one
 * nominal screen and its visual count is the same as every other.
 */
int32_t __jnative_fn_sun_awt_X11GraphicsDevice_getNumConfigs__I_I(
        void* this_device, int32_t screen)
{
    (void)this_device;
    (void)screen;
    return 1;
}

/*
 * public native int getConfigVisualId(int index, int screen);
 *
 * Returns 0, the pseudo-visual id of the default visual. The Java side
 * stores this value on each GraphicsConfiguration and would pass it to
 * X11GraphicsConfig.getConfig / GLXGraphicsConfig.getConfig /
 * XRGraphicsConfig.getConfig, none of which is reachable in a headless
 * runtime.
 */
int32_t __jnative_fn_sun_awt_X11GraphicsDevice_getConfigVisualId__II_I(
        void* this_device, int32_t index, int32_t screen)
{
    (void)this_device;
    (void)index;
    (void)screen;
    return 0;
}

/*
 * private native int getConfigDepth(int index, int screen);
 *
 * Returns 24, the depth of the default X11 visual on every modern
 * server. The Java side stores this value on each GraphicsConfiguration
 * and uses it to decide between the XRender and X11 pipelines; 24 is
 * the correct default when there is no server to query.
 */
int32_t __jnative_fn_sun_awt_X11GraphicsDevice_getConfigDepth__II_I(
        void* this_device, int32_t index, int32_t screen)
{
    (void)this_device;
    (void)index;
    (void)screen;
    return 24;
}

/*
 * private native int getConfigColormap(int index, int screen);
 *
 * Returns 0, the id of the default colormap. The value is passed
 * through to the GraphicsConfig constructors, none of which is
 * reachable in a headless runtime.
 */
int32_t __jnative_fn_sun_awt_X11GraphicsDevice_getConfigColormap__II_I(
        void* this_device, int32_t index, int32_t screen)
{
    (void)this_device;
    (void)index;
    (void)screen;
    return 0;
}

/*
 * private native void getDoubleBufferVisuals(int screen);
 *
 * No-op. On HotSpot this hook enumerates the DBE-capable visuals and
 * calls back into the Java-side addDoubleBufferVisual for each one.
 * The Java caller invokes it only when isDBESupported() returned true,
 * and in this runtime that method returns false, so this hook is never
 * reached from a reachable path.
 */
void __jnative_fn_sun_awt_X11GraphicsDevice_getDoubleBufferVisuals__I_V(
        void* this_device, int32_t screen)
{
    (void)this_device;
    (void)screen;
}

/*
 * private native Rectangle pGetBounds(int screenNum);
 *
 * Returns a java.awt.Rectangle with a nominal headless size. The
 * Java-side getBoundsImpl() dereferences the return value immediately
 * (to apply the scale-down), so a null return would NPE the
 * constructor; the Rectangle is allocated through the runtime's object
 * allocator and its four fields are written through the emitter-
 * computed offsets.
 *
 * 1920x1080 is a common modern default and gives AWT's layout code a
 * plausible canvas to work with. Headless operations never actually
 * display anything, so the exact dimensions do not affect any
 * observable behaviour.
 */
void* __jnative_fn_sun_awt_X11GraphicsDevice_pGetBounds__I_Ljava_awt_Rectangle_(
        void* this_device, int32_t screen_num)
{
    (void)this_device;
    (void)screen_num;

    void* r = make_rectangle(0, 0, 1920, 1080);
    if (r == NULL) {
        /*
         * The allocator failed. Return null rather than aborting: the
         * Java-side getBoundsImpl would NPE on the next field access,
         * which is the same failure mode as any other allocation
         * failure in the constructor chain. Raising OutOfMemoryError
         * here would be more truthful but would take down the whole
         * AWT initialisation, whereas a null return at worst affects
         * one device.
         */
        return NULL;
    }
    return r;
}

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * static native boolean isDBESupported();
 *
 * Returns false. No DBE extension is present. The Java side uses this
 * to decide whether to enumerate the double-buffer visuals; a false
 * return skips the enumeration and leaves doubleBufferVisuals null,
 * which the Java code handles on every path.
 */
int32_t __jnative_fn_sun_awt_X11GraphicsDevice_isDBESupported___Z(void) {
    return 0;
}

/*
 * private static native void enterFullScreenExclusive(long window);
 *
 * No-op. The Java caller invokes it only from setFullScreenWindow(w)
 * after isFullScreenSupported() returned true, and in this runtime
 * that method returns false because initXrandrExtension returns false.
 * The native is emitted because the call site exists in the bytecode,
 * and the linker requires the symbol.
 */
void __jnative_fn_sun_awt_X11GraphicsDevice_enterFullScreenExclusive__J_V(
        int64_t window)
{
    (void)window;
}

/*
 * private static native void exitFullScreenExclusive(long window);
 *
 * No-op, for the same reason as enterFullScreenExclusive: the Java
 * caller guards it on isFullScreenSupported(), which is false in this
 * runtime.
 */
void __jnative_fn_sun_awt_X11GraphicsDevice_exitFullScreenExclusive__J_V(
        int64_t window)
{
    (void)window;
}

/*
 * private static native boolean initXrandrExtension();
 *
 * Returns false. No Xrandr extension is present. The Java-side
 * isXrandrExtensionSupported() caches this value in
 * xrandrExtSupported, and every subsequent fullscreen / display-change
 * operation is gated on it: isFullScreenSupported() returns false,
 * isDisplayChangeSupported() returns false, and setDisplayMode /
 * setFullScreenWindow take their fallback paths.
 */
int32_t __jnative_fn_sun_awt_X11GraphicsDevice_initXrandrExtension___Z(void) {
    return 0;
}

/*
 * private static native DisplayMode getCurrentDisplayMode(int screen);
 *
 * Returns null. The Java-side getDisplayMode() substitutes
 * getDefaultDisplayMode() when this native returns null, which is
 * exactly the behaviour the reference implementation produces for a
 * screen with no Xrandr extension.
 */
void* __jnative_fn_sun_awt_X11GraphicsDevice_getCurrentDisplayMode__I_Ljava_awt_DisplayMode_(
        int32_t screen)
{
    (void)screen;
    return NULL;
}

/*
 * private static native void enumDisplayModes(int screen,
 *                                             ArrayList<DisplayMode> modes);
 *
 * No-op. The Java-side getDisplayModes() invokes it only after
 * isFullScreenSupported() returned true and Xinerama is off; with
 * initXrandrExtension returning false, that guard fails and the
 * fallback path (super.getDisplayModes()) is taken instead. The list
 * argument is a runtime java.util.ArrayList whose backing array would
 * have been appended to; with no modes to enumerate, appending nothing
 * is the correct answer.
 */
void __jnative_fn_sun_awt_X11GraphicsDevice_enumDisplayModes__ILjava_util_ArrayList__V(
        int32_t screen, void* modes)
{
    (void)screen;
    (void)modes;
}

/*
 * private static native void configDisplayMode(int screen,
 *                                              int width, int height,
 *                                              int displayMode);
 *
 * No-op. The Java-side setDisplayMode() invokes it only after
 * isDisplayChangeSupported() returned true; with
 * initXrandrExtension returning false, that guard fails and
 * setDisplayMode takes its Java-level fallback (super.setDisplayMode)
 * before ever reaching this native.
 */
void __jnative_fn_sun_awt_X11GraphicsDevice_configDisplayMode__IIII_V(
        int32_t screen, int32_t width, int32_t height, int32_t display_mode)
{
    (void)screen;
    (void)width;
    (void)height;
    (void)display_mode;
}

/*
 * private static native double getNativeScaleFactor(int screen);
 *
 * Returns 1.0. On HotSpot this reads the X server's DPI and derives an
 * integer UI scale; with no server to query, 1.0 is the correct "no
 * scaling" answer. The Java-side initScaleFactor rounds this to 1 and
 * uses it directly.
 */
double __jnative_fn_sun_awt_X11GraphicsDevice_getNativeScaleFactor__I_D(
        int32_t screen)
{
    (void)screen;
    return 1.0;
}
#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.x11.XSurfaceData — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares eight native methods. Every one of them is called
 * from some reachable method of XSurfaceData or of its subclasses
 * (XRSurfaceData, X11SurfaceData, X11PMBlitLoops, …), so the LLVM
 * emitter records a call site for each in the generated IR. Without
 * definitions in the linked executable, clang rejects
 * sun/java2d/xr/XRSurfaceData.ll (and its siblings) with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_x11_XSurfaceData_initOps__Lsun_awt_X11ComponentPeer_Lsun_awt_X11GraphicsConfig_I_V'
 *
 * and stops. Providing only the symbol in the diagnostic would move the
 * error to the next one; all eight are defined here so the module links
 * in one pass:
 *
 *   initOps                  — per-surface native state setup
 *   XCreateGC                — allocate an Xlib graphics context
 *   XResetClip               — clear the GC's clip region
 *   XSetClip                — set the GC's clip region
 *   flushNativeSurface       — flush pending drawing operations
 *   isDrawableValid          — check whether the X drawable is usable
 *   setInvalid               — mark the surface as no longer drawable
 *   XSetGraphicsExposures    — toggle GraphicsExpose event delivery
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, XSurfaceData is the abstract base class of every
 * X11-backed java2d.SurfaceData. Its native side links against libX11
 * and stores, in each surface's native state struct:
 *
 *   initOps                  extracts the X11 drawable ID from the
 *                            component peer, allocates a per-surface
 *                            native struct, and stores the drawable,
 *                            depth, and graphics configuration data
 *                            into it.
 *
 *   XCreateGC                calls XCreateGC on the surface's display
 *                            and returns the resulting graphics
 *                            context handle.
 *
 *   XResetClip               calls XSetClipMask(gc, None) to clear any
 *                            clip region previously installed on the
 *                            GC.
 *
 *   XSetClip                 calls XSetClipRectangles with the given
 *                            bounding rectangle and complex region.
 *
 *   flushNativeSurface       calls XFlush on the surface's display so
 *                            any pending drawing reaches the server.
 *
 *   isDrawableValid          reports whether the surface's X drawable
 *                            ID is still valid (non-zero and not
 *                            destroyed).
 *
 *   setInvalid               marks the native struct as no longer
 *                            drawable, so a subsequent isDrawableValid
 *                            returns false.
 *
 *   XSetGraphicsExposures    calls XSetGraphicsExposures to toggle
 *                            whether the GC delivers GraphicsExpose
 *                            events to the client.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no display, and no X drawables.
 * GraphicsEnvironment.isHeadless() is true on every reachable path, so
 * no X11ComponentPeer is ever created, no XSurfaceData instance is
 * constructed with a real XID, and no drawing operation ever reaches
 * these natives. Every native is nevertheless emitted into the module
 * because the call sites exist in the bytecode.
 *
 * The eight natives provide the answers that keep a runtime without an
 * X server internally consistent:
 *
 *   initOps                  no-op. The Java-side fields that would
 *                            hold the extracted drawable ID stay at
 *                            their zero-initialised values.
 *
 *   XCreateGC                returns 0. There is no display to create
 *                            a GC on; 0 is the sentinel the Java side
 *                            checks against to detect failure.
 *
 *   XResetClip               no-op. There is no GC to reset.
 *
 *   XSetClip                 no-op. There is no GC to configure.
 *
 *   flushNativeSurface       no-op. There are no pending drawing
 *                            operations and no display to flush to.
 *
 *   isDrawableValid          returns false. A surface with no
 *                            underlying X drawable cannot be drawable,
 *                            and false is what every downstream
 *                            caller uses as the "this surface cannot
 *                            accept drawing" signal.
 *
 *   setInvalid               no-op. The surface's state is already the
 *                            "no drawable" state, so marking it
 *                            invalid changes nothing.
 *
 *   XSetGraphicsExposures    no-op. There is no GC to configure and no
 *                            client connection to deliver events to.
 *
 * These are genuine "no X11" answers, not stubs that abort. Aborting
 * would break every program that constructs a Graphics2D or a
 * BufferedImage-backed SurfaceData, because some of these natives are
 * invoked during surface initialisation even on the
 * BufferedImage-only path — isDrawableValid, for example, is consulted
 * by the SurfaceManager's validation logic before any actual drawing
 * attempt. Returning false keeps those callers on the Java-side
 * fallback path they already handle.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * Four natives are STATIC: XCreateGC, XResetClip, XSetClip,
 * XSetGraphicsExposures.
 *
 * Four natives are INSTANCE: initOps, flushNativeSurface,
 * isDrawableValid, setInvalid.
 *
 * The boolean parameter of XSetGraphicsExposures and the boolean return
 * of isDrawableValid are represented as int32_t at the C ABI level,
 * matching every other boolean native in this runtime.
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
 *   class "sun/java2d/x11/XSurfaceData" -> "sun_java2d_x11_XSurfaceData"
 *
 *   initOps                desc "(Lsun/awt/X11ComponentPeer;Lsun/awt/X11GraphicsConfig;I)V"
 *       -> "__jnative_fn_sun_java2d_x11_XSurfaceData_initOps__Lsun_awt_X11ComponentPeer_Lsun_awt_X11GraphicsConfig_I_V"
 *   XCreateGC              desc "(J)J"
 *       -> "__jnative_fn_sun_java2d_x11_XSurfaceData_XCreateGC__J_J"
 *   XResetClip             desc "(J)V"
 *       -> "__jnative_fn_sun_java2d_x11_XSurfaceData_XResetClip__J_V"
 *   XSetClip               desc "(JIIIILsun/java2d/pipe/Region;)V"
 *       -> "__jnative_fn_sun_java2d_x11_XSurfaceData_XSetClip__JIIIILsun_java2d_pipe_Region__V"
 *   flushNativeSurface     desc "()V"
 *       -> "__jnative_fn_sun_java2d_x11_XSurfaceData_flushNativeSurface___V"
 *   isDrawableValid        desc "()Z"
 *       -> "__jnative_fn_sun_java2d_x11_XSurfaceData_isDrawableValid___Z"
 *   setInvalid             desc "()V"
 *       -> "__jnative_fn_sun_java2d_x11_XSurfaceData_setInvalid___V"
 *   XSetGraphicsExposures  desc "(JZ)V"
 *       -> "__jnative_fn_sun_java2d_x11_XSurfaceData_XSetGraphicsExposures__JZ_V"
 *
 * The initOps name is the one the linker reported as undefined; the
 * others are emitted as well and would produce the same diagnostic on
 * the next rebuild if left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/x11/XSurfaceData") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/java2d/x11/XSurfaceData.c". That is the
 * path this file must live at for Compiler.extractSystemNativeSources
 * to find it and compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * protected static native long XCreateGC(long pXSData);
 *
 * Returns 0. On HotSpot this takes the native struct address of an
 * XSurfaceData, extracts its X display connection, calls XCreateGC, and
 * returns the resulting graphics context handle. With no X display
 * there is nothing to create; 0 is the sentinel the Java side checks
 * against to detect failure.
 *
 * STATIC native: no receiver argument.
 */
int64_t __jnative_fn_sun_java2d_x11_XSurfaceData_XCreateGC__J_J(
        int64_t p_xsdata)
{
    (void)p_xsdata;
    return 0;
}

/*
 * protected static native void XResetClip(long xgc);
 *
 * No-op. On HotSpot this calls XSetClipMask(xgc, None) to clear any
 * clip region previously installed on the given graphics context. There
 * is no graphics context in this runtime, and xgc is always 0.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_java2d_x11_XSurfaceData_XResetClip__J_V(
        int64_t xgc)
{
    (void)xgc;
}

/*
 * protected static native void XSetClip(long xgc, int lox, int loy,
 *                                       int hix, int hiy,
 *                                       Region complexclip);
 *
 * No-op. On HotSpot this calls XSetClipRectangles with the given
 * bounding rectangle (lox, loy, hix, hiy) and the optional complex
 * region, so that subsequent drawing through the GC is clipped
 * accordingly. There is no graphics context in this runtime, and xgc
 * is always 0.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_java2d_x11_XSurfaceData_XSetClip__JIIIILsun_java2d_pipe_Region__V(
        int64_t xgc,
        int32_t lox, int32_t loy,
        int32_t hix, int32_t hiy,
        void* complex_clip)
{
    (void)xgc;
    (void)lox;
    (void)loy;
    (void)hix;
    (void)hiy;
    (void)complex_clip;
}

/*
 * protected static native void XSetGraphicsExposures(long xgc,
 *                                                    boolean needExposures);
 *
 * No-op. On HotSpot this calls XSetGraphicsExposures to toggle whether
 * the given GC delivers GraphicsExpose events to the client when a
 * drawing operation is clipped. With no GC and no client connection to
 * deliver events to, there is nothing to configure.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_java2d_x11_XSurfaceData_XSetGraphicsExposures__JZ_V(
        int64_t xgc, int32_t need_exposures)
{
    (void)xgc;
    (void)need_exposures;
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * protected native void initOps(X11ComponentPeer peer,
 *                               X11GraphicsConfig gc,
 *                               int depth);
 *
 * No-op. On HotSpot this allocates the per-surface native struct,
 * extracts the X drawable ID from the component peer, and stores the
 * drawable, depth, and configuration data into it. This runtime has no
 * X11 component peer and no native struct to allocate; the Java-side
 * fields that would hold the extracted values stay at their
 * zero-initialised state, which is what a surface with no drawable
 * looks like.
 *
 * The three arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_x11_XSurfaceData_initOps__Lsun_awt_X11ComponentPeer_Lsun_awt_X11GraphicsConfig_I_V(
        void* this_surface,
        void* peer,
        void* gc,
        int32_t depth)
{
    (void)this_surface;
    (void)peer;
    (void)gc;
    (void)depth;
}

/*
 * protected native void flushNativeSurface();
 *
 * No-op. On HotSpot this calls XFlush on the surface's display so any
 * pending drawing operations reach the X server. There is no display
 * and no pending drawing, so the call is a no-op.
 */
void __jnative_fn_sun_java2d_x11_XSurfaceData_flushNativeSurface___V(
        void* this_surface)
{
    (void)this_surface;
}

/*
 * protected native boolean isDrawableValid();
 *
 * Returns false. On HotSpot this reports whether the surface's X
 * drawable ID is non-zero and not destroyed — i.e. whether the surface
 * can accept drawing operations. A surface with no underlying X
 * drawable cannot be drawable; false is the signal every downstream
 * caller uses to fall through to a Java-side validation path that does
 * not need the native drawable.
 */
int32_t __jnative_fn_sun_java2d_x11_XSurfaceData_isDrawableValid___Z(
        void* this_surface)
{
    (void)this_surface;
    return 0;
}

/*
 * protected native void setInvalid();
 *
 * No-op. On HotSpot this marks the surface's native struct as no longer
 * drawable, so a subsequent isDrawableValid returns false. In this
 * runtime the surface is already in the "no drawable" state — see
 * isDrawableValid above — so marking it invalid changes nothing.
 */
void __jnative_fn_sun_java2d_x11_XSurfaceData_setInvalid___V(
        void* this_surface)
{
    (void)this_surface;
}
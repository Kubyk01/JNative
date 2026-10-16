#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.xr.XRSurfaceData — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * XRSurfaceData is the abstract base of every XRender-backed
 * java2d.SurfaceData. It sits on top of the X11 SurfaceData family
 * (XSurfaceData, which itself extends java2d.SurfaceData) and adds the
 * XRender-specific state: an XRender Picture handle, a picture format
 * code, and the drawing pipes (XRRenderer, XRTextRenderer, XRDrawImage)
 * that operate on that picture.
 *
 * The class declares four natives:
 *
 *   private static native void initIDs();
 *
 *     Called once from XRSurfaceData.initXRSurfaceData. On HotSpot this
 *     hook caches the JNI field IDs of XRSurfaceData's own fields so the
 *     other natives can reach into the Java object without repeated
 *     lookups.
 *
 *   protected native void XRInitSurface(int depth, int width, int height,
 *                                       long drawable, int pictFormat);
 *
 *     Creates the XRender picture object for an offscreen XR pixmap.
 *     Called from XRPixmapSurfaceData.initSurface after the X11 pixmap
 *     drawable has already been allocated.
 *
 *   native void initXRPicture(long xsdo, int pictForm);
 *
 *     Creates the XRender picture object for an onscreen XR window or
 *     for an XRInternalSurfaceData that reuses an existing picture.
 *     Called from XRSurfaceData.initXRender, which the concrete
 *     constructors invoke after establishing the surface's drawable.
 *
 *   native void freeXSDOPicture(long xsdo);
 *
 *     Releases the XRender picture object associated with the surface.
 *     Called from XRWindowSurfaceData.invalidate, which is the
 *     disposal path for an onscreen XR surface.
 *
 * The LLVM emitter records a call site for each in the generated IR —
 * the two concrete inner classes and the initXRSurfaceData static hook
 * all reference them from their own bytecode. Without definitions in
 * the linked executable, clang rejects
 * sun/java2d/xr/XRSurfaceData$XRPixmapSurfaceData.ll and
 * sun/java2d/xr/XRSurfaceData$XRWindowSurfaceData.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_xr_XRSurfaceData_<method>_<desc>'
 *
 * The two diagnostics that motivated this file name XRInitSurface and
 * freeXSDOPicture, which are simply the first undefined symbols clang
 * happened to reach in each concrete subclass's translation unit; the
 * static initIDs and the initXRPicture instance native are emitted as
 * well and would produce the same diagnostic on the next rebuild.
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot with an X11 display, the XRender extension, and the
 * accelerated XRender pipeline selected, the native side of
 * XRSurfaceData drives the surface's XRender state through the X11
 * Display connection shared with the rest of the java2d X11 backend:
 *
 *   initIDs
 *       caches the JNI field IDs of XRSurfaceData's own reference
 *       fields (the X11ComponentPeer, the XRGraphicsConfig, the
 *       XRBackend render queue, and the SurfaceType variants).
 *
 *   XRInitSurface
 *       after the offscreen X11 pixmap has been created (the `drawable`
 *       argument is its X pixmap id), calls XRenderCreatePicture with
 *       the supplied `pictFormat` and stores the resulting Picture
 *       handle in the surface's `picture` field. The `depth`, `width`
 *       and `height` arguments describe the pixmap's geometry.
 *
 *   initXRPicture
 *       the onscreen counterpart of XRInitSurface. For a window surface
 *       the drawable is the window's X id and the picture format is
 *       derived from the window's colour model; for an
 *       XRInternalSurfaceData the picture handle is passed in directly
 *       and this native is effectively a no-op that records the format.
 *
 *   freeXSDOPicture
 *       calls XRenderFreePicture on the surface's current Picture
 *       handle and clears the field, releasing the X server's
 *       resources for that picture. Called from the surface's disposal
 *       path.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no XRender extension, no X
 * server, and no XR picture objects. Every precondition that would
 * allow an XRSurfaceData of any concrete subclass to be constructed
 * is false on every reachable path:
 *
 *   - X11GraphicsEnvironment.isXRenderAvailable() returns false (see
 *     jnative/sun/awt/X11GraphicsEnvironment.c), so the XRender
 *     pipeline is never selected by SunGraphics2D.validatePipe().
 *
 *   - XRGraphicsConfig is only reachable through X11GraphicsConfig's
 *     XRender-specific subclass, and the X11 pipeline's own
 *     isAccelerationEnabled() returns false, so no XRGraphicsConfig
 *     is ever constructed.
 *
 *   - XRSurfaceData.initXRSurfaceData is only called from the
 *     XR-specific pipeline setup, which never runs in a headless
 *     runtime; consequently no XRWindowSurfaceData, no
 *     XRPixmapSurfaceData and no XRInternalSurfaceData is ever
 *     instantiated through the normal path.
 *
 * Every native here is nevertheless emitted into the module because
 * the bytecode of XRSurfaceData and of its inner subclasses contains
 * the call sites. The four implementations below provide the answers
 * that keep the class linkable and internally consistent:
 *
 *   initIDs        no-op. This runtime accesses every field through
 *                  its LLVM-computed byte offset and never consults
 *                  JNI field IDs, so there is nothing to cache. The
 *                  symbol must exist because the static hook emits a
 *                  native call to it.
 *
 *   XRInitSurface  no-op. There is no XRender connection to create a
 *                  picture on; the surface's `picture` field stays at
 *                  its Java-level default of 0, which every downstream
 *                  XR consumer treats as "no picture available".
 *
 *   initXRPicture  no-op, for the same reason. The `xsdo` argument is
 *                  the surface's native ops pointer, which in this
 *                  runtime is always 0 because XRInitSurface never
 *                  allocated one.
 *
 *   freeXSDOPicture no-op. There is no picture to release; the
 *                  disposal path is reached only from
 *                  XRWindowSurfaceData.invalidate, which is itself
 *                  only reached for surfaces that were never
 *                  successfully constructed.
 *
 * These are genuine "no XRender" answers, not stubs that abort.
 * Aborting would break the class initialisation of XRSurfaceData
 * itself, because the static XRPipeline hook references initIDs and
 * every program that constructs a Graphics2D would trip the abort
 * before the software path could take over. The no-ops keep every
 * caller on the documented "XRender acceleration unavailable" branch
 * that HotSpot itself exercises on a system without the XRender
 * extension.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * initIDs is a STATIC native: no receiver argument. It takes no
 * parameters and returns void.
 *
 * The other three are INSTANCE natives: the receiver is the first
 * argument. Their Java-level signatures give the following C shapes:
 *
 *   XRInitSurface(int depth, int width, int height,
 *                 long drawable, int pictFormat)
 *       receiver, int32_t, int32_t, int32_t, int64_t, int32_t
 *
 *   initXRPicture(long xsdo, int pictForm)
 *       receiver, int64_t, int32_t
 *
 *   freeXSDOPicture(long xsdo)
 *       receiver, int64_t
 *
 * Every argument is captured and discarded. None of them can affect
 * the outcome in a runtime with no XRender connection to issue the
 * picture operations against.
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
 *   class "sun/java2d/xr/XRSurfaceData" -> "sun_java2d_xr_XRSurfaceData"
 *
 *   initIDs         desc "()V"
 *       -> "__jnative_fn_sun_java2d_xr_XRSurfaceData_initIDs___V"
 *   XRInitSurface   desc "(IIIJI)V"
 *       -> "__jnative_fn_sun_java2d_xr_XRSurfaceData_XRInitSurface__IIIJI_V"
 *   initXRPicture   desc "(JI)V"
 *       -> "__jnative_fn_sun_java2d_xr_XRSurfaceData_initXRPicture__JI_V"
 *   freeXSDOPicture desc "(J)V"
 *       -> "__jnative_fn_sun_java2d_xr_XRSurfaceData_freeXSDOPicture__J_V"
 *
 * The XRInitSurface and freeXSDOPicture names are the two the linker
 * reported as undefined; the other two are emitted as well and would
 * produce the same diagnostic on the next rebuild if left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/xr/XRSurfaceData") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/java2d/xr/XRSurfaceData.c". That is the
 * path this file must live at for Compiler.extractSystemNativeSources
 * to find it and compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native void initIDs();
 *
 * Called from XRSurfaceData.initXRSurfaceData. On HotSpot this hook
 * caches the JNI field IDs of XRSurfaceData's reference fields so the
 * other natives can reach into the Java object without repeated
 * lookups. This runtime accesses every field through its LLVM-computed
 * byte offset and never consults JNI field IDs, so there is nothing to
 * cache. The symbol must exist because the static hook emits a native
 * call to it.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_java2d_xr_XRSurfaceData_initIDs___V(void) {
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * protected native void XRInitSurface(int depth, int width, int height,
 *                                     long drawable, int pictFormat);
 *
 * No-op. See the file header for the full rationale: the runtime has
 * no XRender connection to create a picture on, so the surface's
 * `picture` field stays at its Java-level default of 0. Every
 * downstream XR consumer checks that field against 0 to decide
 * whether the surface has an XRender picture; a 0 value steers it to
 * the "no picture" branch, which is exactly the behaviour HotSpot
 * produces for a surface whose XRenderCreatePicture call failed.
 *
 * The five arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_xr_XRSurfaceData_XRInitSurface__IIIJI_V(
        void* this_surface,
        int32_t depth,
        int32_t width,
        int32_t height,
        int64_t drawable,
        int32_t pict_format)
{
    (void)this_surface;
    (void)depth;
    (void)width;
    (void)height;
    (void)drawable;
    (void)pict_format;
}

/*
 * native void initXRPicture(long xsdo, int pictForm);
 *
 * No-op. On HotSpot this creates the XRender picture object for an
 * onscreen XR window surface or records the pre-existing picture
 * handle for an XRInternalSurfaceData. There is no XRender connection
 * in this runtime; the surface's `picture` field is left at 0, which
 * is the same "no picture" state the reference implementation
 * produces for a window whose XRenderCreatePicture call failed.
 *
 * The two arguments are captured and discarded. `xsdo` would be the
 * surface's native ops pointer, which in this runtime is always 0
 * because XRInitSurface never allocated one.
 */
void __jnative_fn_sun_java2d_xr_XRSurfaceData_initXRPicture__JI_V(
        void* this_surface,
        int64_t xsdo,
        int32_t pict_form)
{
    (void)this_surface;
    (void)xsdo;
    (void)pict_form;
}

/*
 * native void freeXSDOPicture(long xsdo);
 *
 * No-op. On HotSpot this calls XRenderFreePicture on the surface's
 * current picture handle and clears the field, releasing the X
 * server's resources. There is no picture in this runtime to release;
 * the disposal path (XRWindowSurfaceData.invalidate) is only reached
 * for surfaces that were successfully constructed, which never
 * happens here.
 *
 * The `xsdo` argument is captured and discarded.
 */
void __jnative_fn_sun_java2d_xr_XRSurfaceData_freeXSDOPicture__J_V(
        void* this_surface,
        int64_t xsdo)
{
    (void)this_surface;
    (void)xsdo;
}
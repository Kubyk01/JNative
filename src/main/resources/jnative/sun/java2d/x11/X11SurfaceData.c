#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.x11.X11SurfaceData — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares six native methods. Every one of them is called
 * from some reachable method of the class or of its inner subclasses
 * (X11WindowSurfaceData, X11PixmapSurfaceData), so the LLVM emitter
 * records a call site for each in the generated IR. Without definitions
 * in the linked executable, clang rejects
 * sun/java2d/x11/X11SurfaceData$X11PixmapSurfaceData.ll (and its
 * siblings) with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_x11_X11SurfaceData_initSurface__IIIJ_V'
 *
 * and stops. Providing only the symbol in the diagnostic would move the
 * error to the next one; all six are defined here so the module links
 * in one pass:
 *
 *   initIDs            — static, <clinit> hook
 *   initSurface        — instance, per-pixmap native state setup
 *   isShmPMAvailable   — static, shared-memory pixmap probe
 *   XSetCopyMode       — static, GC raster-op selector (copy)
 *   XSetXorMode        — static, GC raster-op selector (xor)
 *   XSetForeground     — static, GC foreground colour
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, X11SurfaceData is the base class of every X11-backed
 * java2d.SurfaceData. Its native side links against libX11 and libXext
 * and drives two areas of the Java-to-native boundary:
 *
 *   initIDs            caches the JNI field IDs of XORComposite so the
 *                      other natives can read its pixel-mod value
 *                      without repeated lookups. The `xorComp` argument
 *                      is the java.lang.Class object for XORComposite,
 *                      passed by the static initializer.
 *
 *   initSurface        initialises the per-pixmap native state (an
 *                      X11PixmapSurfaceData struct): the depth, size,
 *                      and X drawable id of a freshly-allocated
 *                      offscreen pixmap. Called from the pixmap
 *                      constructor after the pixmap has been created.
 *
 *   isShmPMAvailable   probes whether the X server supports the MIT-SHM
 *                      extension with shared-memory pixmaps (SHM
 *                      pixmaps are faster than regular pixmaps because
 *                      the client writes directly into shared memory).
 *
 *   XSetCopyMode       calls XSetFunction(gc, GXcopy) on the GC.
 *   XSetXorMode        calls XSetFunction(gc, GXxor) on the GC.
 *   XSetForeground     calls XSetForeground(gc, pixel) on the GC.
 *                      The last three are the Java-side GC state cache
 *                      updaters used by X11SurfaceData.getGC.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no display, and no X server.
 * GraphicsEnvironment.isHeadless() is true on every reachable path, so
 * the static initializer in X11SurfaceData takes the headless branch
 * and never calls initIDs, never probes for SHM pixmaps, and never
 * registers the X11 blit loops. Every native is nevertheless emitted
 * into the module because the call sites exist in the bytecode.
 *
 * The six natives provide the answers that keep a runtime without an
 * X server internally consistent:
 *
 *   initIDs            no-op. There are no JNI field IDs to cache in
 *                      this runtime.
 *
 *   initSurface        no-op. There is no native state struct to
 *                      allocate or populate; the Java-side fields
 *                      that would hold the struct pointer stay at
 *                      their zero-initialised values.
 *
 *   isShmPMAvailable   returns false. No MIT-SHM extension to probe;
 *                      false is the answer the Java-side
 *                      isAccelerationEnabled() expects on a system
 *                      without shared-memory pixmaps, and it correctly
 *                      steers the runtime away from the acceleration
 *                      path.
 *
 *   XSetCopyMode       no-op. There is no GC to configure.
 *   XSetXorMode        no-op. There is no GC to configure.
 *   XSetForeground     no-op. There is no GC to configure.
 *
 * These are genuine "no X11" answers, not stubs that abort. Aborting
 * would break every program that constructs an X11GraphicsConfig or a
 * java2d.SurfaceData during AWT initialisation, even on paths that
 * never actually draw anything. Returning false from isShmPMAvailable
 * and letting the other five be no-ops is what the reference
 * implementation does on a headless-capable build, and every
 * downstream consumer is written against those values.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * initIDs, isShmPMAvailable, XSetCopyMode, XSetXorMode and
 * XSetForeground are STATIC natives: no receiver argument.
 *
 * initSurface is an INSTANCE native: the receiver is the first
 * argument.
 *
 * The boolean return of isShmPMAvailable is represented as int32_t at
 * the C ABI level, matching every other boolean native in this runtime.
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
 *   class "sun/java2d/x11/X11SurfaceData" -> "sun_java2d_x11_X11SurfaceData"
 *
 *   initIDs           desc "(Ljava/lang/Class;)V"
 *       -> "__jnative_fn_sun_java2d_x11_X11SurfaceData_initIDs__Ljava_lang_Class__V"
 *   initSurface       desc "(IIIJ)V"
 *       -> "__jnative_fn_sun_java2d_x11_X11SurfaceData_initSurface__IIIJ_V"
 *   isShmPMAvailable  desc "()Z"
 *       -> "__jnative_fn_sun_java2d_x11_X11SurfaceData_isShmPMAvailable___Z"
 *   XSetCopyMode      desc "(J)V"
 *       -> "__jnative_fn_sun_java2d_x11_X11SurfaceData_XSetCopyMode__J_V"
 *   XSetXorMode       desc "(J)V"
 *       -> "__jnative_fn_sun_java2d_x11_X11SurfaceData_XSetXorMode__J_V"
 *   XSetForeground    desc "(JI)V"
 *       -> "__jnative_fn_sun_java2d_x11_X11SurfaceData_XSetForeground__JI_V"
 *
 * The initSurface name is the one the linker reported as undefined; the
 * others are emitted as well and would produce the same diagnostic on
 * the next rebuild if left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/x11/X11SurfaceData") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/java2d/x11/X11SurfaceData.c". That is the
 * path this file must live at for Compiler.extractSystemNativeSources
 * to find it and compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native void initIDs(Class<?> xorComp);
 *
 * Called from X11SurfaceData.<clinit>. On HotSpot this hook caches the
 * JNI field IDs of XORComposite so the other natives can read its
 * pixel-mod field without repeated lookups. The `xorComp` argument is
 * the java.lang.Class object for XORComposite, passed by the static
 * initializer. This runtime accesses every field through its
 * LLVM-computed byte offset and never consults JNI field IDs, so there
 * is nothing to cache.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_java2d_x11_X11SurfaceData_initIDs__Ljava_lang_Class__V(
        void* xor_comp)
{
    (void)xor_comp;
}

/*
 * private static native boolean isShmPMAvailable();
 *
 * Returns false. On HotSpot this probes whether the X server supports
 * the MIT-SHM extension with shared-memory pixmaps, which the Java-side
 * isAccelerationEnabled() uses to decide whether to steer the runtime
 * toward the accelerated pixmap path. With no X server and no MIT-SHM
 * extension, the truthful answer is false, and it correctly steers the
 * runtime away from an accelerated pixmap path it cannot support.
 *
 * STATIC native: no receiver argument.
 */
int32_t __jnative_fn_sun_java2d_x11_X11SurfaceData_isShmPMAvailable___Z(void) {
    return 0;
}

/*
 * private static native void XSetCopyMode(long xgc);
 *
 * No-op. On HotSpot this calls XSetFunction(xgc, GXcopy) on the given
 * graphics context, selecting the copy raster operation. There is no
 * GC in this runtime, and xgc is always 0 (see the private xgc field on
 * the Java side).
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_java2d_x11_X11SurfaceData_XSetCopyMode__J_V(
        int64_t xgc)
{
    (void)xgc;
}

/*
 * private static native void XSetXorMode(long xgc);
 *
 * No-op. On HotSpot this calls XSetFunction(xgc, GXxor) on the given
 * graphics context, selecting the XOR raster operation. There is no
 * GC in this runtime.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_java2d_x11_X11SurfaceData_XSetXorMode__J_V(
        int64_t xgc)
{
    (void)xgc;
}

/*
 * private static native void XSetForeground(long xgc, int pixel);
 *
 * No-op. On HotSpot this calls XSetForeground(xgc, pixel) on the given
 * graphics context, updating the foreground colour that subsequent
 * drawing operations use. There is no GC in this runtime.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_java2d_x11_X11SurfaceData_XSetForeground__JI_V(
        int64_t xgc, int32_t pixel)
{
    (void)xgc;
    (void)pixel;
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * protected native void initSurface(int depth, int width, int height,
 *                                   long drawable);
 *
 * No-op. On HotSpot this initialises the per-pixmap native state (an
 * X11PixmapSurfaceData struct) with the depth, size and X drawable id
 * of a freshly-allocated offscreen pixmap. This runtime has no X
 * drawable to record and no native struct to allocate; the Java-side
 * fields that would hold the struct pointer stay at their
 * zero-initialised values, which is what a surface with no native
 * backing looks like.
 *
 * The four arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_x11_X11SurfaceData_initSurface__IIIJ_V(
        void* this_surface,
        int32_t depth,
        int32_t width,
        int32_t height,
        int64_t drawable)
{
    (void)this_surface;
    (void)depth;
    (void)width;
    (void)height;
    (void)drawable;
}
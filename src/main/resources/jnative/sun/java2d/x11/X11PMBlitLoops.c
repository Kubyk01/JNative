#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.x11.X11PMBlitLoops — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * X11PMBlitLoops is the X11-specific subclass of sun.java2d.loops.Blit.
 * It accelerates Pixmap-to-Pixmap blits — the onscreen X11 surface and
 * the offscreen X11 pixmap surfaces that X11OffScreenImage creates —
 * for every source / composite / destination-type triple that the X11
 * pipeline knows how to handle.
 *
 * The class declares two natives:
 *
 *     private native void nativeBlit(long srcData, long dstData,
 *                                    long xgc, Region clip,
 *                                    int sx, int sy,
 *                                    int dx, int dy,
 *                                    int w, int h);
 *
 *     private static native void updateBitmask(SurfaceData src,
 *                                              SurfaceData dst,
 *                                              boolean isICM);
 *
 * nativeBlit is reached through the inherited Blit invoker method,
 * which acquires the AWT lock, obtains a graphics context from the
 * destination X11SurfaceData with a null clip (the clip is applied
 * inside the native), and delegates the actual copy.
 *
 * updateBitmask is reached from the DelegateBlitLoop inner class's
 * own Blit implementation. That inner class first dispatches the copy
 * to an opaque delegate Blit obtained from the global primitive cache,
 * then calls updateBitmask to reconcile the destination's X11 clip
 * bitmask with the transparent pixels of the source.
 *
 * Both call sites are emitted into the module because the bytecode of
 * the class and of its inner class contains them. The class is
 * reachable because X11PMBlitLoops.register() is called from
 * X11SurfaceData.<clinit>, which the java2d pipeline-selection
 * machinery touches during SunGraphics2D.validatePipe().
 *
 * Without definitions in the linked executable, clang rejects
 * sun/java2d/x11/X11PMBlitLoops.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_x11_X11PMBlitLoops_<method>_<desc>'
 *
 * The diagnostic that motivated this file names nativeBlit, which is
 * simply the first undefined symbol clang happened to reach; the
 * static updateBitmask would surface on the next rebuild if left out.
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot with an X11 display and the accelerated X11 pipeline
 * selected, nativeBlit issues a single X11 XCopyArea through the
 * graphics context that the Java-level invoker obtained from the
 * destination surface's getBlitGC. The clip Region is applied by the
 * native itself: it sets the graphics context's clip rectangles to
 * the Region's own rectangles before issuing the copy, so the transfer
 * is confined to the parts of the destination drawable that the clip
 * allows. The source and destination are both Pixmap-backed, so the
 * copy is a straightforward server-side pixel transfer.
 *
 * updateBitmask reconciles the destination's X11 clipping bitmask with
 * the source's transparency. For a 1-bit-transparent BITMASK source
 * being copied onto an opaque X11 pixmap, the server-side XCopyArea
 * above writes the source's transparent pixels into the destination
 * as opaque black. The bitmask on the destination — the per-pixel
 * transparency information that X11 uses for shape masking — is left
 * inconsistent with the copied pixels. updateBitmask walks the source
 * pixels, identifies which ones are transparent (using the source's
 * IndexColorModel transparency index when `isICM` is true, or the
 * alpha channel otherwise), and updates the destination's X11
 * bitmask so that subsequent compositing against the destination
 * sees the correct transparency.
 *
 * Both natives live in the JDK's own libawt_xawt sources; this runtime
 * does not link them.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no display, and no X server to
 * copy pixels between or to update bitmasks on. GraphicsEnvironment
 * .isHeadless() is true on every reachable path, and
 * X11GraphicsEnvironment.isAccelerationEnabled() returns false, so
 * the accelerated X11 pipeline is never selected by
 * SunGraphics2D.validatePipe(). Consequently no X11SurfaceData is
 * ever constructed with a live GraphicsConfig, no X11PMBlitLoops
 * instance is ever created through the normal path, and neither
 * native is invoked from a live path.
 *
 * Both natives are nevertheless emitted into the module because the
 * bytecode of the class and of its DelegateBlitLoop inner class
 * references them. Their implementations below are strict no-ops:
 *
 *   - There is no source drawable to copy from.
 *
 *   - There is no destination drawable to copy into.
 *
 *   - There is no X graphics context whose clip could be set or whose
 *     XCopyArea could be issued.
 *
 *   - There is no X11 bitmask to reconcile with the source's
 *     transparency.
 *
 *   - No observable effect — neither in the program's output nor in
 *     any subsequent read of the destination surface — would be
 *     produced by drawing in a runtime that cannot display the
 *     result, and no reachable caller would observe the difference.
 *
 * These are genuine "no X11" answers, not stubs that abort. Aborting
 * would break class initialisation of X11SurfaceData itself, because
 * X11SurfaceData.<clinit> calls X11PMBlitLoops.register() and that
 * call installs a DelegateBlitLoop instance whose class-loading
 * touches this file's static native declaration. Every program that
 * constructs a Graphics2D would trip the abort before reaching any
 * actual drawing call. The no-ops keep every caller on the documented
 * "X11 acceleration unavailable" branch that HotSpot itself exercises
 * on a system without a display.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * nativeBlit is an INSTANCE native: the receiver is the first
 * argument, followed by the ten Java-level parameters. The C
 * signature takes, in order:
 *
 *     this_prim   X11PMBlitLoops receiver
 *     srcData     int64_t native ops pointer of the source surface
 *     dstData     int64_t native ops pointer of the destination surface
 *     xgc         int64_t X11 graphics context handle
 *     clip        Region reference (may be null when the caller has
 *                 already established that no clip is in effect)
 *     sx, sy      int32_t source rectangle top-left corner
 *     dx, dy      int32_t destination rectangle top-left corner
 *     w, h        int32_t rectangle width and height
 *
 * updateBitmask is a STATIC native: no receiver argument. The C
 * signature takes the two SurfaceData references and the boolean
 * `isICM` flag directly.
 *
 * The boolean parameter of updateBitmask and the boolean parameters
 * elsewhere in this codebase are represented as int32_t at the C ABI
 * level, matching every other boolean native in this runtime.
 *
 * Every argument of both natives is captured and discarded. None of
 * them can affect the outcome in a runtime with no X server to issue
 * the drawing primitive against.
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
 *   class "sun/java2d/x11/X11PMBlitLoops"
 *       -> "sun_java2d_x11_X11PMBlitLoops"
 *
 *   nativeBlit   desc "(JJJLsun/java2d/pipe/Region;IIIIII)V"
 *       -> "__jnative_fn_sun_java2d_x11_X11PMBlitLoops_nativeBlit__JJJLsun_java2d_pipe_Region_IIIIII_V"
 *
 *   updateBitmask desc "(Lsun/java2d/SurfaceData;Lsun/java2d/SurfaceData;Z)V"
 *       -> "__jnative_fn_sun_java2d_x11_X11PMBlitLoops_updateBitmask__Lsun_java2d_SurfaceData_Lsun_java2d_SurfaceData_Z_V"
 *
 * The nativeBlit name is the one the linker reported as undefined; the
 * static updateBitmask is emitted as well and would produce the same
 * diagnostic on the next rebuild if left out. The double underscore
 * after the method name is the method-to-descriptor separator followed
 * by the sanitised `(`. The three `J`s are the three long parameters,
 * the `L…_Region_` token is the reference parameter with its `/` and
 * `;` sanitized to `_`, the six `I`s are the int rectangle
 * coordinates, and the trailing `_V` is the sanitised `)` plus the
 * void return type.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/x11/X11PMBlitLoops") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/java2d/x11/X11PMBlitLoops.c". That is the
 * path this file must live at for Compiler.extractSystemNativeSources
 * to find it and compile it into the executable.
 */

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * private native void nativeBlit(long srcData, long dstData,
 *                                long xgc, Region clip,
 *                                int sx, int sy, int dx, int dy,
 *                                int w, int h);
 *
 * No-op. See the file header for the full rationale: the runtime has
 * no X11 connection, the accelerated X11 pipeline is never selected,
 * and every caller in this runtime has a well-defined software
 * fallback path that operates on the destination raster's Java-side
 * data instead. The ten arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_x11_X11PMBlitLoops_nativeBlit__JJJLsun_java2d_pipe_Region_IIIIII_V(
        void* this_prim,
        int64_t src_data,
        int64_t dst_data,
        int64_t xgc,
        void* clip,
        int32_t sx, int32_t sy,
        int32_t dx, int32_t dy,
        int32_t w, int32_t h)
{
    (void)this_prim;
    (void)src_data;
    (void)dst_data;
    (void)xgc;
    (void)clip;
    (void)sx; (void)sy;
    (void)dx; (void)dy;
    (void)w;  (void)h;
}

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native void updateBitmask(SurfaceData src,
 *                                          SurfaceData dst,
 *                                          boolean isICM);
 *
 * No-op. On HotSpot this reconciles the destination X11 pixmap's
 * clipping bitmask with the source's transparent pixels after a
 * DelegateBlitLoop copy. In a runtime with no X11 bitmask to update
 * and no copied pixels to reconcile, the call has no observable
 * effect. The three arguments are captured and discarded.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_java2d_x11_X11PMBlitLoops_updateBitmask__Lsun_java2d_SurfaceData_Lsun_java2d_SurfaceData_Z_V(
        void* src,
        void* dst,
        int32_t is_icm)
{
    (void)src;
    (void)dst;
    (void)is_icm;
}
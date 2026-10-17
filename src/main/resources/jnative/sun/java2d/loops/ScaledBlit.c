#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.ScaledBlit — native entry point.
 *
 * The scaled-blit GraphicsPrimitive: copy the source rectangle
 * [sx1, sy1, sx2, sy2) to the destination rectangle
 * [dx1, dy1, dx2, dy2), scaling the pixel content as required. All
 * four boundary pairs are given as absolute coordinates rather than
 * as (origin, width, height), so the scaling factors are implicit in
 * the differences. Reachable through the primitive table walk; never
 * invoked from a live path in a headless runtime.
 */
void __jnative_fn_sun_java2d_loops_ScaledBlit_ScaledBlit__Lsun_java2d_SurfaceData_Lsun_java2d_SurfaceData_Ljava_awt_Composite_Lsun_java2d_pipe_Region_IIIIIIII_V(
        void* this_prim,
        void* src,
        void* dst,
        void* comp,
        void* clip,
        int32_t sx1, int32_t sy1,
        int32_t sx2, int32_t sy2,
        int32_t dx1, int32_t dy1,
        int32_t dx2, int32_t dy2)
{
    (void)this_prim;
    (void)src;
    (void)dst;
    (void)comp;
    (void)clip;
    (void)sx1; (void)sy1;
    (void)sx2; (void)sy2;
    (void)dx1; (void)dy1;
    (void)dx2; (void)dy2;
}

/*
 * sun.java2d.loops.ScaledBlit.Scale — the scale-only variant of the
 * scaled-blit primitive. The affine matrix collapses to four doubles
 * (scaleX, scaleY, translateX, translateY) and the source / destination
 * rectangles are given as (x, y, w, h) triples, so the emitted
 * descriptor is different from the general ScaledBlit.ScaledBlit form.
 *
 * Accelerated pipeline is never selected in this runtime; the body is a
 * no-op that keeps the class's vtable linkable.
 */
void __jnative_fn_sun_java2d_loops_ScaledBlit_Scale__Lsun_java2d_SurfaceData_Lsun_java2d_SurfaceData_Ljava_awt_Composite_Lsun_java2d_pipe_Region_IIIIDDDD_V(
        void* this_prim,
        void* src,
        void* dst,
        void* comp,
        void* clip,
        int32_t srcx, int32_t srcy, int32_t srcw, int32_t srch,
        double scaleX, double scaleY,
        double translateX, double translateY)
{
    (void)this_prim; (void)src; (void)dst; (void)comp; (void)clip;
    (void)srcx; (void)srcy; (void)srcw; (void)srch;
    (void)scaleX; (void)scaleY;
    (void)translateX; (void)translateY;
}

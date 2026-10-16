#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.TransformBlit — native entry point.
 *
 * The transform-and-blit GraphicsPrimitive: copy the source rectangle
 * [sx1, sy1, sx2, sy2) to the destination rectangle
 * [dx1, dy1, dx2, dy2), applying the affine transform `at` to the
 * source coordinates on the way. The `txtype` argument carries the
 * java2d transform-type code that tells the native loop which fast
 * path (translate, scale, general affine) applies. Reachable through
 * the primitive table walk; never invoked from a live path in a
 * headless runtime.
 */
void __jnative_fn_sun_java2d_loops_TransformBlit_TransformBlit__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_Lsun_java2d_SurfaceData_Ljava_awt_Composite_Lsun_java2d_pipe_Region_Ljava_awt_geom_AffineTransform_IIIIIIIII_V(
        void* this_prim,
        void* sg2d,
        void* src,
        void* dst,
        void* comp,
        void* clip,
        void* at,
        int32_t txtype,
        int32_t sx1, int32_t sy1,
        int32_t sx2, int32_t sy2,
        int32_t dx1, int32_t dy1,
        int32_t dx2, int32_t dy2)
{
    (void)this_prim;
    (void)sg2d;
    (void)src;
    (void)dst;
    (void)comp;
    (void)clip;
    (void)at;
    (void)txtype;
    (void)sx1; (void)sy1;
    (void)sx2; (void)sy2;
    (void)dx1; (void)dy1;
    (void)dx2; (void)dy2;
}

#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.MaskBlit — native entry point.
 *
 * The masked-blit GraphicsPrimitive: blit the source rectangle into
 * the destination rectangle, modulating each pixel's contribution by
 * the corresponding byte in `mask`. The `maskoff` and `maskscan`
 * arguments give the mask's byte offset within its array and its row
 * stride. Reachable through the primitive table walk; never invoked
 * from a live path in a headless runtime.
 */
void __jnative_fn_sun_java2d_loops_MaskBlit_MaskBlit__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_Lsun_java2d_SurfaceData_Ljava_awt_Composite_Lsun_java2d_pipe_Region_IIIIII_BII_V(
        void* this_prim,
        void* sg2d,
        void* src,
        void* dst,
        void* comp,
        void* clip,
        int32_t srcx, int32_t srcy,
        int32_t dstx, int32_t dsty,
        int32_t width, int32_t height,
        void* mask,
        int32_t maskoff,
        int32_t maskscan)
{
    (void)this_prim;
    (void)sg2d;
    (void)src;
    (void)dst;
    (void)comp;
    (void)clip;
    (void)srcx; (void)srcy;
    (void)dstx; (void)dsty;
    (void)width; (void)height;
    (void)mask;
    (void)maskoff;
    (void)maskscan;
}

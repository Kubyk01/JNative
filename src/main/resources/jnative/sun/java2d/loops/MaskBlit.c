#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.MaskBlit — native entry point.
 *
 * Masked blit: copies the rectangle [srcx, srcy, width, height) of the
 * source into [dstx, dsty, width, height) of the destination,
 * modulating each pixel with the corresponding byte mask. `maskoff` is
 * the mask offset into the array, `maskscan` is the mask row stride.
 *
 * The implementation is a no-op: the accelerated java2d pipeline that
 * would call this function is never instantiated in a headless runtime
 * (see jnative/sun/awt/X11GraphicsEnvironment.c: every
 * isXXXAvailable() returns false). The remaining java2d paths run on
 * the Java side through the software primitive tables.
 *
 * The symbol must exist because it appears in
 * vtable_methods_sun_java2d_loops_MaskBlit and, transitively, in all
 * MaskBlit subclasses (OGLMaskBlit, XRMaskBlit, D3DMaskBlit).
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

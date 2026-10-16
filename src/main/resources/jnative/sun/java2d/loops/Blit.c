#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.Blit — native entry point.
 *
 * The class is one of the java2d GraphicsPrimitive subclasses,
 * representing the "blit one surface region to another" operation:
 * copy the pixels of the source rectangle [sx, sy, w, h) into the
 * destination rectangle [dx, dy, w, h), compositing through `comp`
 * and clipping against `clip`. Reachable in the compiled image
 * because GraphicsPrimitiveMgr.locate walks the primitive table
 * during SunGraphics2D.validatePipe(). Never invoked from a live
 * path in a headless runtime; the body is a strict no-op.
 */
void __jnative_fn_sun_java2d_loops_Blit_Blit__Lsun_java2d_SurfaceData_Lsun_java2d_SurfaceData_Ljava_awt_Composite_Lsun_java2d_pipe_Region_IIIIII_V(
        void* this_prim,
        void* src,
        void* dst,
        void* comp,
        void* clip,
        int32_t sx, int32_t sy,
        int32_t dx, int32_t dy,
        int32_t w, int32_t h)
{
    (void)this_prim;
    (void)src;
    (void)dst;
    (void)comp;
    (void)clip;
    (void)sx; (void)sy;
    (void)dx; (void)dy;
    (void)w;  (void)h;
}

#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.MaskFill — native entry point.
 *
 * The masked-fill GraphicsPrimitive: fill the destination rectangle
 * with the current colour, modulating each pixel's coverage by the
 * corresponding byte in `mask`. The `maskoff` and `maskscan`
 * arguments give the mask's byte offset within its array and its row
 * stride. Reachable through the primitive table walk; never invoked
 * from a live path in a headless runtime.
 */
void __jnative_fn_sun_java2d_loops_MaskFill_MaskFill__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_Ljava_awt_Composite_IIII_BII_V(
        void* this_prim,
        void* sg2d,
        void* s_data,
        void* comp,
        int32_t x, int32_t y,
        int32_t w, int32_t h,
        void* mask,
        int32_t maskoff,
        int32_t maskscan)
{
    (void)this_prim;
    (void)sg2d;
    (void)s_data;
    (void)comp;
    (void)x; (void)y;
    (void)w; (void)h;
    (void)mask;
    (void)maskoff;
    (void)maskscan;
}

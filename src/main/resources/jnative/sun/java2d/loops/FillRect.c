#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.FillRect — native entry point.
 *
 * The java2d GraphicsPrimitive that fills an axis-aligned rectangle
 * with the graphics context's current colour, honouring the
 * composite, clip and XOR state. Reachable through the primitive
 * table walk; never invoked from a live path in a headless runtime.
 */
void __jnative_fn_sun_java2d_loops_FillRect_FillRect__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_IIII_V(
        void* this_prim,
        void* sg2d,
        void* dest,
        int32_t x, int32_t y,
        int32_t w, int32_t h)
{
    (void)this_prim;
    (void)sg2d;
    (void)dest;
    (void)x; (void)y;
    (void)w; (void)h;
}

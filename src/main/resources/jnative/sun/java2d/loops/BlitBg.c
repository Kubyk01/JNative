#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.BlitBg — native entry point.
 *
 * The background-blit variant of sun.java2d.loops.Blit: in addition
 * to the source/destination rectangles and the composite state, it
 * takes a `bgColor` that is filled into the parts of the destination
 * that the source does not cover. Reachable through the primitive
 * table walk; never invoked from a live path in a headless runtime.
 */
void __jnative_fn_sun_java2d_loops_BlitBg_BlitBg__Lsun_java2d_SurfaceData_Lsun_java2d_SurfaceData_Ljava_awt_Composite_Lsun_java2d_pipe_Region_IIIIIII_V(
        void* this_prim,
        void* src,
        void* dst,
        void* comp,
        void* clip,
        int32_t bg_color,
        int32_t sx, int32_t sy,
        int32_t dx, int32_t dy,
        int32_t w, int32_t h)
{
    (void)this_prim;
    (void)src;
    (void)dst;
    (void)comp;
    (void)clip;
    (void)bg_color;
    (void)sx; (void)sy;
    (void)dx; (void)dy;
    (void)w;  (void)h;
}

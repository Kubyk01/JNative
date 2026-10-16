#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.MaskFill — native entry points.
 *
 * Three native methods, all part of the accelerated java2d pipeline:
 *
 *   MaskFill      — fills a rectangle through a byte mask
 *   FillAAPgram   — antialiased fill of a parallelogram
 *   DrawAAPgram   — antialiased draw of a parallelogram
 *
 * All three are unreachable in a headless runtime for the same reason
 * as MaskBlit: the accelerated pipelines are never instantiated. The
 * symbols must exist because they are required by
 * vtable_methods_sun_java2d_loops_MaskFill and the vtable_methods of
 * its subclasses (OGLMaskFill, XRMaskFill, D3DMaskFill,
 * MaskFill_General).
 */

/*
 * public native void MaskFill(SunGraphics2D sg2d, SurfaceData sData,
 *                             Composite comp,
 *                             int x, int y, int w, int h,
 *                             byte[] mask, int maskoff, int maskscan);
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

/*
 * public native void FillAAPgram(SunGraphics2D sg2d, SurfaceData sData,
 *                                Composite comp,
 *                                double x, double y,
 *                                double dx1, double dy1,
 *                                double dx2, double dy2);
 */
void __jnative_fn_sun_java2d_loops_MaskFill_FillAAPgram__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_Ljava_awt_Composite_DDDDDD_V(
        void* this_prim,
        void* sg2d,
        void* s_data,
        void* comp,
        double x,  double y,
        double dx1, double dy1,
        double dx2, double dy2)
{
    (void)this_prim;
    (void)sg2d;
    (void)s_data;
    (void)comp;
    (void)x;   (void)y;
    (void)dx1; (void)dy1;
    (void)dx2; (void)dy2;
}

/*
 * public native void DrawAAPgram(SunGraphics2D sg2d, SurfaceData sData,
 *                                Composite comp,
 *                                double x, double y,
 *                                double dx1, double dy1,
 *                                double dx2, double dy2,
 *                                double lw1, double lw2);
 */
void __jnative_fn_sun_java2d_loops_MaskFill_DrawAAPgram__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_Ljava_awt_Composite_DDDDDDDD_V(
        void* this_prim,
        void* sg2d,
        void* s_data,
        void* comp,
        double x,  double y,
        double dx1, double dy1,
        double dx2, double dy2,
        double lw1, double lw2)
{
    (void)this_prim;
    (void)sg2d;
    (void)s_data;
    (void)comp;
    (void)x;   (void)y;
    (void)dx1; (void)dy1;
    (void)dx2; (void)dy2;
    (void)lw1; (void)lw2;
}

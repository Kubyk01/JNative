#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.xr.XRMaskBlit — native entry point.
 *
 * The XRender-backed masked-blit GraphicsPrimitive. Like every other
 * XRender primitive it is only reachable through the accelerated
 * pipeline, which GraphicsEnvironment.isHeadless() prevents from ever
 * being selected in this runtime. The body is a strict no-op so the
 * class's vtable links.
 *
 * ABI: receiver first, then the two long native-ops pointers, the ten
 * int geometry / mask parameters, and finally the mask byte[].
 */
void __jnative_fn_sun_java2d_xr_XRMaskBlit_maskBlit__JJIIIIIIIII_B_V(
        void* this_prim,
        int64_t src_ops,
        int64_t dst_ops,
        int32_t src_type,
        int32_t dst_type,
        int32_t dstx, int32_t dsty,
        int32_t width, int32_t height,
        int32_t maskoff, int32_t maskscan, int32_t masklen,
        void* mask)
{
    (void)this_prim;
    (void)src_ops; (void)dst_ops;
    (void)src_type; (void)dst_type;
    (void)dstx; (void)dsty;
    (void)width; (void)height;
    (void)maskoff; (void)maskscan; (void)masklen;
    (void)mask;
}

#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.xr.XRMaskFill — native entry point.
 *
 * The XRender-backed masked-fill GraphicsPrimitive. Same reasoning as
 * XRMaskBlit: the accelerated pipeline is never selected in a headless
 * runtime, so the body is a strict no-op that keeps the class's vtable
 * linkable.
 *
 * ABI: receiver first, then the long native-ops pointer, seven int
 * geometry / mask parameters, and finally the mask byte[].
 */
void __jnative_fn_sun_java2d_xr_XRMaskFill_maskFill__JIIIIIII_B_V(
        void* this_prim,
        int64_t dst_ops,
        int32_t dstx, int32_t dsty,
        int32_t width, int32_t height,
        int32_t maskoff, int32_t maskscan, int32_t masklen,
        void* mask)
{
    (void)this_prim;
    (void)dst_ops;
    (void)dstx; (void)dsty;
    (void)width; (void)height;
    (void)maskoff; (void)maskscan; (void)masklen;
    (void)mask;
}

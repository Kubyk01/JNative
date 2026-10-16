#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.DrawGlyphListAA — native entry point.
 *
 * The anti-aliased variant of sun.java2d.loops.DrawGlyphList. The
 * GlyphList carries anti-aliased coverage bitmaps rather than 1-bit
 * masks, and the native blends each glyph's coverage into the
 * destination through the graphics context's composite. Reachable
 * through the primitive table walk; never invoked from a live path
 * in a headless runtime.
 */
void __jnative_fn_sun_java2d_loops_DrawGlyphListAA_DrawGlyphListAA__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_Lsun_font_GlyphList_II_V(
        void* this_prim,
        void* sg2d,
        void* dest,
        void* glyph_list,
        int32_t from_glyph,
        int32_t to_glyph)
{
    (void)this_prim;
    (void)sg2d;
    (void)dest;
    (void)glyph_list;
    (void)from_glyph;
    (void)to_glyph;
}

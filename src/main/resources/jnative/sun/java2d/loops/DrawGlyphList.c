#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.DrawGlyphList — native entry point.
 *
 * The java2d GraphicsPrimitive that renders a range of glyphs from a
 * GlyphList onto a destination surface, using the glyph images and
 * advances cached in the GlyphList. The `fromGlyph` / `toGlyph`
 * arguments bound the range to render; the GlyphList itself holds the
 * cached glyph bitmaps. Reachable through the primitive table walk;
 * never invoked from a live path in a headless runtime.
 */
void __jnative_fn_sun_java2d_loops_DrawGlyphList_DrawGlyphList__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_Lsun_font_GlyphList_II_V(
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

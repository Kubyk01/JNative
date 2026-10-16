#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.xr.XRBackendNative — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares twenty-seven native methods. Every one of them is
 * called from some reachable method of the class or of one of its
 * callers in the XRender pipeline, so the LLVM emitter records a call
 * site for each in the generated IR. Without definitions in the linked
 * executable, clang rejects sun/java2d/xr/XRBackendNative.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_xr_XRBackendNative_XRSetTransformNative__IIIIIII_V'
 *
 * and stops. Providing only the symbol in the diagnostic would move the
 * error to the next one; all twenty-seven are defined here so the module
 * links in one pass.
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, XRBackendNative is a nearly one-to-one binding to the X
 * Render extension (libXrender). Each native forwards to a single
 * libXrender function or to a small helper in the JDK's own
 * src/java.desktop/unix/native/libawt_xawt/awt/ … /xrender/ sources:
 *
 *   createGC / freeGC                    → XCreateGC / XFreeGC
 *   createPixmap / freePixmap            → XCreatePixmap / XFreePixmap
 *   createPictureNative / freePicture    → XRenderCreatePicture / XRenderFreePicture
 *   setGCExposures                       → XSetGraphicsExposures
 *   setGCForeground                      → XSetForeground
 *   setPictureRepeat                     → XRenderSetPictureRepeat
 *   copyArea                             → XCopyArea
 *   setGCMode                            → XSetFunction
 *   GCRectanglesNative                   → XFillRectangles
 *   renderComposite                      → XRenderComposite
 *   renderRectangle                      → XRenderFillRectangle
 *   XRenderRectanglesNative              → XRenderFillRectangles
 *   XRSetTransformNative                 → XRenderSetPictureTransform
 *   XRCreateLinearGradientPaintNative    → XRenderCreateLinearGradient
 *   XRCreateRadialGradientPaintNative    → XRenderCreateRadialGradient
 *   setFilter                            → XRenderSetPictureFilter
 *   XRSetClipNative                      → XRenderSetPictureClipRectangles
 *   XRAddGlyphsNative / XRFreeGlyphsNative → XRenderAddGlyphs / XRenderFreeGlyphs
 *   XRenderCompositeTextNative           → XRenderCompositeText
 *   XRenderCreateGlyphSetNative          → XRenderCreateGlyphSet
 *   putMaskNative                        → helper (PutMaskImage)
 *   padBlitNative                        → helper (PadBlit)
 *
 * The three private static fields (FMTPTR_A8, FMTPTR_ARGB32, MASK_XIMG)
 * that the Java class declares hold native pointers to XRender format
 * structs and to the XImage structure used by putMaskNative. They are
 * populated by initIDs.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no X Render extension, no X
 * graphics contexts, no X pixmaps, no XRender pictures, no glyph sets,
 * and no XImage. GraphicsEnvironment.isHeadless() is true on every
 * reachable path, so no XRBackendNative instance is ever created
 * through the normal path, and no drawing operation ever reaches these
 * natives. Every native is nevertheless emitted into the module because
 * the call sites exist in the bytecode.
 *
 * The twenty-seven natives provide the answers that keep a runtime
 * without an X server internally consistent:
 *
 *   initIDs                        no-op. The three static native-
 *                                  pointer fields stay at their
 *                                  zero-initialised values, which
 *                                  getFormatPtr handles correctly by
 *                                  returning 0 for both format ids.
 *
 *   createGC                       returns 0. No X display to create a
 *                                  GC on.
 *   freeGC                         no-op.
 *
 *   createPixmap                   returns 0.
 *   freePixmap                     no-op.
 *   freePicture                    no-op.
 *   createPictureNative            returns 0.
 *
 *   setGCExposures                 no-op.
 *   setGCForeground                no-op.
 *   setPictureRepeat               no-op.
 *   copyArea                       no-op.
 *   setGCMode                      no-op.
 *   setFilter                      no-op.
 *
 *   GCRectanglesNative             no-op.
 *   XRenderRectanglesNative        no-op.
 *   renderComposite                no-op.
 *   renderRectangle                no-op.
 *
 *   XRSetTransformNative           no-op.
 *   XRSetClipNative                no-op.
 *
 *   XRCreateLinearGradientPaintNative  returns 0.
 *   XRCreateRadialGradientPaintNative  returns 0.
 *
 *   XRAddGlyphsNative              no-op.
 *   XRFreeGlyphsNative             no-op.
 *   XRenderCompositeTextNative     no-op.
 *   XRenderCreateGlyphSetNative    returns 0.
 *   putMaskNative                  no-op.
 *   padBlitNative                  no-op.
 *
 * These are genuine "no X Render" answers, not stubs that abort. The
 * Java-side callers in XRRenderer, XRSurfaceData and the pipeline
 * selection code all check the returned handles against 0 to detect
 * failure, and the void natives have no observable effect in a runtime
 * that has no display to draw on. Aborting would break every program
 * that constructs a Graphics2D with an XRender-capable configuration
 * during pipeline initialisation, even when the program never draws a
 * shape.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * initIDs, GCRectanglesNative, XRenderRectanglesNative,
 * XRCreateLinearGradientPaintNative, XRCreateRadialGradientPaintNative,
 * XRSetClipNative, XRAddGlyphsNative, XRFreeGlyphsNative,
 * XRenderCompositeTextNative, XRenderCreateGlyphSetNative,
 * putMaskNative and padBlitNative are STATIC natives: no receiver.
 *
 * The other fifteen are INSTANCE natives: the receiver is the first
 * argument.
 *
 * Boolean parameters and returns are represented as int32_t at the C
 * ABI level, matching every other boolean native in this runtime.
 *
 * ============================================================================
 * The mangled names
 * ============================================================================
 *
 * LlvmRuntime.mangleMethod applies
 *     "fn_" + sanitize(class) + "_" + sanitize(method) + "_" + sanitize(desc)
 * where sanitize replaces every character outside [a-zA-Z0-9_] with a
 * single underscore. The emitter prefixes the result with "__jnative_".
 *
 *   class "sun/java2d/xr/XRBackendNative" -> "sun_java2d_xr_XRBackendNative"
 *
 * Symbol names, one per Java declaration:
 *
 *   static initIDs()V
 *   createGC(I)J
 *   freeGC(J)V
 *   createPixmap(IIII)I
 *   createPictureNative(IJ)I
 *   freePicture(I)V
 *   freePixmap(I)V
 *   setGCExposures(JZ)V
 *   setGCForeground(JI)V
 *   setPictureRepeat(II)V
 *   copyArea(IIJIIIIII)V
 *   setGCMode(JZ)V
 *   static GCRectanglesNative(IJ[II)V
 *   renderComposite(BIIIIIIIIIIII)V
 *   renderRectangle(IBSSSSIIII)V
 *   static XRenderRectanglesNative(IBSSSS[II)V
 *   XRSetTransformNative(IIIIIII)V
 *   static XRCreateLinearGradientPaintNative([F[SIIIIII)I
 *   static XRCreateRadialGradientPaintNative([F[SIIIIIII)I
 *   setFilter(II)V
 *   static XRSetClipNative(JIIIILsun/java2d/pipe/Region;Z)V
 *   static XRAddGlyphsNative(I[JI[BI)V
 *   static XRFreeGlyphsNative(I[II)V
 *   static XRenderCompositeTextNative(IIIIIJ[I[III)V
 *   static XRenderCreateGlyphSetNative(J)I
 *   static putMaskNative(IJ[BIIIIIIIIFLJ)V
 *   static padBlitNative(BIIIIIIIIIIIIIIIIIII)V
 *
 * The XRSetTransformNative name is the one the linker reported as
 * undefined; the others are emitted as well and would produce the same
 * diagnostic on the next rebuild if left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/xr/XRBackendNative") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/java2d/xr/XRBackendNative.c". That is the
 * path this file must live at for Compiler.extractSystemNativeSources
 * to find it and compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native void initIDs();
 *
 * Called from XRBackendNative.<clinit>. On HotSpot this hook populates
 * the three static fields FMTPTR_A8, FMTPTR_ARGB32 and MASK_XIMG with
 * native pointers to XRender format structs and the shared XImage used
 * by putMaskNative. This runtime has no such structs, so the fields
 * stay at their zero-initialised values; getFormatPtr returns 0 for
 * both format ids, and every downstream caller checks against 0 as a
 * "not available" sentinel.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_java2d_xr_XRBackendNative_initIDs___V(void) {
}

/*
 * private static native void GCRectanglesNative(int drawable, long gc,
 *                                               int[] rectArray,
 *                                               int rectCnt);
 *
 * No-op. On HotSpot this calls XFillRectangles on the drawable with the
 * rectangles described by the packed int[] (x, y, w, h per rectangle).
 * There is no display and no GC to fill on.
 */
/* ---------- GCRectanglesNative: descriptor (IJ[II)V ---------- */
void __jnative_fn_sun_java2d_xr_XRBackendNative_GCRectanglesNative__IJ_II_V(
        int32_t drawable, int64_t gc,
        void* rect_array, int32_t rect_cnt, int32_t extra_reserved)
{
    (void)drawable; (void)gc;
    (void)rect_array; (void)rect_cnt; (void)extra_reserved;
}

/*
 * private static native void
 *     XRenderRectanglesNative(int dst, byte op,
 *                             short red, short green, short blue, short alpha,
 *                             int[] rects, int rectCnt);
 *
 * No-op. On HotSpot this calls XRenderFillRectangles on the destination
 * picture with the colour and the packed rectangles. There is no
 * picture to fill.
 */
/* ---------- XRenderRectanglesNative: descriptor (IBSSSS[II)V ---------- */
void __jnative_fn_sun_java2d_xr_XRBackendNative_XRenderRectanglesNative__IBSSSS_II_V(
        int32_t dst, int8_t op,
        int16_t red, int16_t green, int16_t blue, int16_t alpha,
        void* rects, int32_t rect_cnt, int32_t extra_reserved)
{
    (void)dst; (void)op;
    (void)red; (void)green; (void)blue; (void)alpha;
    (void)rects; (void)rect_cnt; (void)extra_reserved;
}

/*
 * private static native int
 *     XRCreateLinearGradientPaintNative(float[] fractionsArray,
 *                                       short[] pixelsArray,
 *                                       int x1, int y1, int x2, int y2,
 *                                       int numStops, int repeat);
 *
 * Returns 0. On HotSpot this calls XRenderCreateLinearGradient and
 * returns the resulting picture id. There is no display to create a
 * gradient on; 0 is the "no picture created" sentinel the Java-side
 * caller checks against.
 */
int32_t __jnative_fn_sun_java2d_xr_XRBackendNative_XRCreateLinearGradientPaintNative___F_SIIIIII_I(
        void* fractions_array, void* pixels_array,
        int32_t x1, int32_t y1, int32_t x2, int32_t y2,
        int32_t num_stops, int32_t repeat)
{
    (void)fractions_array;
    (void)pixels_array;
    (void)x1;
    (void)y1;
    (void)x2;
    (void)y2;
    (void)num_stops;
    (void)repeat;
    return 0;
}

/*
 * private static native int
 *     XRCreateRadialGradientPaintNative(float[] fractionsArray,
 *                                       short[] pixelsArray,
 *                                       int numStops,
 *                                       int centerX, int centerY,
 *                                       int innerRadius, int outerRadius,
 *                                       int repeat);
 *
 * Returns 0. Same reasoning as the linear-gradient counterpart above:
 * no display, no picture, and 0 is the caller's "not created" sentinel.
 */
/* ---------- XRCreateRadialGradientPaintNative: descriptor ([F[SIIIIIII)I ---------- */
int32_t __jnative_fn_sun_java2d_xr_XRBackendNative_XRCreateRadialGradientPaintNative___F_SIIIIIII_I(
        void* fractions_array, void* pixels_array,
        int32_t num_stops,
        int32_t center_x, int32_t center_y,
        int32_t inner_radius, int32_t outer_radius,
        int32_t repeat, int32_t extra_reserved)
{
    (void)fractions_array; (void)pixels_array;
    (void)num_stops;
    (void)center_x; (void)center_y;
    (void)inner_radius; (void)outer_radius;
    (void)repeat; (void)extra_reserved;
    return 0;
}

/*
 * private static native void
 *     XRSetClipNative(long dst, int x1, int y1, int x2, int y2,
 *                     Region clip, boolean isGC);
 *
 * No-op. On HotSpot this installs a clip on either a graphics context
 * (isGC true) or an XRender picture (isGC false), using the bounding
 * rectangle x1,y1,x2,y2 and, when the region is not rectangular, the
 * additional clip rectangles encoded in the Region object. There is no
 * GC and no picture to configure.
 */
void __jnative_fn_sun_java2d_xr_XRBackendNative_XRSetClipNative__JIIIILsun_java2d_pipe_Region_Z_V(
        int64_t dst,
        int32_t x1, int32_t y1, int32_t x2, int32_t y2,
        void* clip, int32_t is_gc)
{
    (void)dst;
    (void)x1;
    (void)y1;
    (void)x2;
    (void)y2;
    (void)clip;
    (void)is_gc;
}

/*
 * private static native void XRAddGlyphsNative(int glyphSet,
 *                                              long[] glyphInfoPtrs,
 *                                              int glyphCnt,
 *                                              byte[] pixelData,
 *                                              int pixelDataLength);
 *
 * No-op. On HotSpot this calls XRenderAddGlyphs to register a set of
 * glyph bitmaps with the server-side glyph cache. There is no glyph
 * set to register into.
 */
/* ---------- XRAddGlyphsNative: descriptor (I[JI[BI)V ---------- */
void __jnative_fn_sun_java2d_xr_XRBackendNative_XRAddGlyphsNative__I_JI_BI_V(
        int32_t glyph_set,
        void* glyph_info_ptrs, int32_t glyph_cnt,
        void* pixel_data, int32_t pixel_data_length)
{
    (void)glyph_set;
    (void)glyph_info_ptrs;
    (void)glyph_cnt;
    (void)pixel_data;
    (void)pixel_data_length;
}

/*
 * private static native void XRFreeGlyphsNative(int glyphSet,
 *                                               int[] gids,
 *                                               int idCnt);
 *
 * No-op. On HotSpot this calls XRenderFreeGlyphs to release the given
 * glyph ids from the server-side cache. There is no glyph set.
 */
/* ---------- XRFreeGlyphsNative: descriptor (I[II)V ---------- */
void __jnative_fn_sun_java2d_xr_XRBackendNative_XRFreeGlyphsNative__I_II_V(
        int32_t glyph_set, void* gids, int32_t id_cnt)
{
    (void)glyph_set;
    (void)gids;
    (void)id_cnt;
}

/*
 * private static native void
 *     XRenderCompositeTextNative(int op, int src, int dst,
 *                                int srcX, int srcY, long maskFormat,
 *                                int[] eltArray, int[] glyphIDs,
 *                                int eltCnt, int glyphCnt);
 *
 * No-op. On HotSpot this calls XRenderCompositeText to draw glyph runs
 * from the source picture into the destination picture. There is
 * neither a source nor a destination to draw between.
 */
/* ---------- XRenderCompositeTextNative: descriptor (IIIIIJ[I[III)V ---------- */
void __jnative_fn_sun_java2d_xr_XRBackendNative_XRenderCompositeTextNative__IIIIIJ_I_III_V(
        int32_t op, int32_t src, int32_t dst,
        int32_t src_x, int32_t src_y, int64_t mask_format,
        void* elt_array, void* glyph_ids,
        int32_t elt_cnt, int32_t glyph_cnt,
        int32_t glyphset_or_reserved)
{
    (void)op; (void)src; (void)dst;
    (void)src_x; (void)src_y; (void)mask_format;
    (void)elt_array; (void)glyph_ids;
    (void)elt_cnt; (void)glyph_cnt;
    (void)glyphset_or_reserved;
}

/*
 * private static native int XRenderCreateGlyphSetNative(long format);
 *
 * Returns 0. On HotSpot this calls XRenderCreateGlyphSet for the given
 * pixel-format struct and returns the resulting glyph-set id. There is
 * no display to create a glyph set on; 0 is the "not created" sentinel.
 */
int32_t __jnative_fn_sun_java2d_xr_XRBackendNative_XRenderCreateGlyphSetNative__J_I(
        int64_t format)
{
    (void)format;
    return 0;
}

/*
 * private static native void putMaskNative(int drawable, long gc,
 *                                          byte[] imageData,
 *                                          int sx, int sy, int dx, int dy,
 *                                          int width, int height,
 *                                          int maskOff, int maskScan,
 *                                          float ea, long xImg);
 *
 * No-op. On HotSpot this converts the byte[] mask into an XImage (the
 * xImg argument is a preallocated native XImage struct shared across
 * calls) and blits it to the destination drawable through the given GC.
 * There is no drawable and no GC to blit onto.
 */
/* ---------- putMaskNative: descriptor (IJ[BIIIIIIIIFJ)V ---------- */
void __jnative_fn_sun_java2d_xr_XRBackendNative_putMaskNative__IJ_BIIIIIIIIFJ_V(
        int32_t drawable, int64_t gc,
        void* image_data,
        int32_t sx, int32_t sy, int32_t dx, int32_t dy,
        int32_t width, int32_t height,
        int32_t mask_off, int32_t mask_scan,
        float ea, int64_t x_img)
{
    (void)drawable; (void)gc; (void)image_data;
    (void)sx; (void)sy; (void)dx; (void)dy;
    (void)width; (void)height;
    (void)mask_off; (void)mask_scan;
    (void)ea; (void)x_img;
}

/*
 * private static native void
 *     padBlitNative(byte op, int srcPict, int maskPict, int dstPict,
 *                   int m00, int m01, int m02,
 *                   int m10, int m11, int m12,
 *                   int maskWidth, int maskHeight,
 *                   int lastMaskWidth, int lastMaskHeight,
 *                   int sx, int sy, int dx, int dy, int w, int h);
 *
 * No-op. On HotSpot this performs a masked blit between three XRender
 * pictures, applying the given affine transform to the mask. There are
 * no pictures to composite.
 */
void __jnative_fn_sun_java2d_xr_XRBackendNative_padBlitNative__BIIIIIIIIIIIIIIIIIII_V(
        int8_t op,
        int32_t src_pict, int32_t mask_pict, int32_t dst_pict,
        int32_t m00, int32_t m01, int32_t m02,
        int32_t m10, int32_t m11, int32_t m12,
        int32_t mask_width, int32_t mask_height,
        int32_t last_mask_width, int32_t last_mask_height,
        int32_t sx, int32_t sy, int32_t dx, int32_t dy,
        int32_t w, int32_t h)
{
    (void)op;
    (void)src_pict;
    (void)mask_pict;
    (void)dst_pict;
    (void)m00;
    (void)m01;
    (void)m02;
    (void)m10;
    (void)m11;
    (void)m12;
    (void)mask_width;
    (void)mask_height;
    (void)last_mask_width;
    (void)last_mask_height;
    (void)sx;
    (void)sy;
    (void)dx;
    (void)dy;
    (void)w;
    (void)h;
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * public native long createGC(int drawable);
 *
 * Returns 0. No display to create a graphics context on.
 */
int64_t __jnative_fn_sun_java2d_xr_XRBackendNative_createGC__I_J(
        void* this_backend, int32_t drawable)
{
    (void)this_backend;
    (void)drawable;
    return 0;
}

/*
 * public native void freeGC(long gc);
 *
 * No-op. No graphics context to release.
 */
void __jnative_fn_sun_java2d_xr_XRBackendNative_freeGC__J_V(
        void* this_backend, int64_t gc)
{
    (void)this_backend;
    (void)gc;
}

/*
 * public native int createPixmap(int drawable, int depth,
 *                                int width, int height);
 *
 * Returns 0. No display to create an X pixmap on.
 */
int32_t __jnative_fn_sun_java2d_xr_XRBackendNative_createPixmap__IIII_I(
        void* this_backend, int32_t drawable, int32_t depth,
        int32_t width, int32_t height)
{
    (void)this_backend;
    (void)drawable;
    (void)depth;
    (void)width;
    (void)height;
    return 0;
}

/*
 * private native int createPictureNative(int drawable, long formatID);
 *
 * Returns 0. No display to create an XRender picture on.
 */
int32_t __jnative_fn_sun_java2d_xr_XRBackendNative_createPictureNative__IJ_I(
        void* this_backend, int32_t drawable, int64_t format_id)
{
    (void)this_backend;
    (void)drawable;
    (void)format_id;
    return 0;
}

/*
 * public native void freePicture(int picture);
 *
 * No-op. No picture to release.
 */
void __jnative_fn_sun_java2d_xr_XRBackendNative_freePicture__I_V(
        void* this_backend, int32_t picture)
{
    (void)this_backend;
    (void)picture;
}

/*
 * public native void freePixmap(int pixmap);
 *
 * No-op. No pixmap to release.
 */
void __jnative_fn_sun_java2d_xr_XRBackendNative_freePixmap__I_V(
        void* this_backend, int32_t pixmap)
{
    (void)this_backend;
    (void)pixmap;
}

/*
 * public native void setGCExposures(long gc, boolean exposure);
 *
 * No-op. No graphics context to configure.
 */
void __jnative_fn_sun_java2d_xr_XRBackendNative_setGCExposures__JZ_V(
        void* this_backend, int64_t gc, int32_t exposure)
{
    (void)this_backend;
    (void)gc;
    (void)exposure;
}

/*
 * public native void setGCForeground(long gc, int pixel);
 *
 * No-op. No graphics context to configure.
 */
void __jnative_fn_sun_java2d_xr_XRBackendNative_setGCForeground__JI_V(
        void* this_backend, int64_t gc, int32_t pixel)
{
    (void)this_backend;
    (void)gc;
    (void)pixel;
}

/*
 * public native void setPictureRepeat(int picture, int repeat);
 *
 * No-op. No picture to configure.
 */
void __jnative_fn_sun_java2d_xr_XRBackendNative_setPictureRepeat__II_V(
        void* this_backend, int32_t picture, int32_t repeat)
{
    (void)this_backend;
    (void)picture;
    (void)repeat;
}

/*
 * public native void copyArea(int src, int dst, long gc,
 *                             int srcx, int srcy, int width, int height,
 *                             int dstx, int dsty);
 *
 * No-op. On HotSpot this calls XCopyArea to blit a rectangle from the
 * source drawable to the destination drawable. There is no display to
 * blit on.
 */
void __jnative_fn_sun_java2d_xr_XRBackendNative_copyArea__IIJIIIIII_V(
        void* this_backend, int32_t src, int32_t dst, int64_t gc,
        int32_t srcx, int32_t srcy, int32_t width, int32_t height,
        int32_t dstx, int32_t dsty)
{
    (void)this_backend;
    (void)src;
    (void)dst;
    (void)gc;
    (void)srcx;
    (void)srcy;
    (void)width;
    (void)height;
    (void)dstx;
    (void)dsty;
}

/*
 * public native void setGCMode(long gc, boolean copy);
 *
 * No-op. On HotSpot this calls XSetFunction to select either GXcopy or
 * GXxor as the GC's raster operation. There is no graphics context to
 * configure.
 */
void __jnative_fn_sun_java2d_xr_XRBackendNative_setGCMode__JZ_V(
        void* this_backend, int64_t gc, int32_t copy)
{
    (void)this_backend;
    (void)gc;
    (void)copy;
}

/*
 * public native void renderComposite(byte op, int src, int mask,
 *                                    int dst, int srcX, int srcY,
 *                                    int maskX, int maskY,
 *                                    int dstX, int dstY,
 *                                    int width, int height);
 *
 * No-op. On HotSpot this calls XRenderComposite to blend the source
 * picture through the mask picture into the destination picture. There
 * are no pictures to composite.
 */
void __jnative_fn_sun_java2d_xr_XRBackendNative_renderComposite__BIIIIIIIIIIII_V(
        void* this_backend,
        int8_t op, int32_t src, int32_t mask, int32_t dst,
        int32_t src_x, int32_t src_y,
        int32_t mask_x, int32_t mask_y,
        int32_t dst_x, int32_t dst_y,
        int32_t width, int32_t height)
{
    (void)this_backend;
    (void)op;
    (void)src;
    (void)mask;
    (void)dst;
    (void)src_x;
    (void)src_y;
    (void)mask_x;
    (void)mask_y;
    (void)dst_x;
    (void)dst_y;
    (void)width;
    (void)height;
}

/*
 * private native void renderRectangle(int dst, byte op,
 *                                     short red, short green, short blue,
 *                                     short alpha,
 *                                     int x, int y, int width, int height);
 *
 * No-op. On HotSpot this calls XRenderFillRectangle to fill a single
 * rectangle in the destination picture with the given colour. There is
 * no picture to fill.
 */
void __jnative_fn_sun_java2d_xr_XRBackendNative_renderRectangle__IBSSSSIIII_V(
        void* this_backend, int32_t dst, int8_t op,
        int16_t red, int16_t green, int16_t blue, int16_t alpha,
        int32_t x, int32_t y, int32_t width, int32_t height)
{
    (void)this_backend;
    (void)dst;
    (void)op;
    (void)red;
    (void)green;
    (void)blue;
    (void)alpha;
    (void)x;
    (void)y;
    (void)width;
    (void)height;
}

/*
 * private native void XRSetTransformNative(int pic,
 *                                          int m00, int m01, int m02,
 *                                          int m10, int m11, int m12);
 *
 * No-op. On HotSpot this calls XRenderSetPictureTransform to install
 * the given 2×3 fixed-point affine transform on the picture. There is
 * no picture to configure.
 */
void __jnative_fn_sun_java2d_xr_XRBackendNative_XRSetTransformNative__IIIIIII_V(
        void* this_backend, int32_t pic,
        int32_t m00, int32_t m01, int32_t m02,
        int32_t m10, int32_t m11, int32_t m12)
{
    (void)this_backend;
    (void)pic;
    (void)m00;
    (void)m01;
    (void)m02;
    (void)m10;
    (void)m11;
    (void)m12;
}

/*
 * public native void setFilter(int picture, int filter);
 *
 * No-op. On HotSpot this calls XRenderSetPictureFilter to select the
 * picture's resampling filter (nearest, bilinear, …). There is no
 * picture to configure.
 */
void __jnative_fn_sun_java2d_xr_XRBackendNative_setFilter__II_V(
        void* this_backend, int32_t picture, int32_t filter)
{
    (void)this_backend;
    (void)picture;
    (void)filter;
}
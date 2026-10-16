#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.awt.image.BufImgSurfaceData — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares two native methods:
 *
 *     private static native void initIDs(Class<?> ICM,
 *                                        Class<?> ICMColorData);
 *     protected native void initRaster(Object theArray,
 *                                      int offset,
 *                                      int bitoffset,
 *                                      int width,
 *                                      int height,
 *                                      int pixStr,
 *                                      int scanStr,
 *                                      IndexColorModel icm);
 *
 * initIDs is called from the class's static initializer, once. The
 * initRaster native is called from every createDataIC / createDataSC /
 * createDataBC / createDataBP factory method — i.e. from every
 * BufferedImage → SurfaceData conversion the AWT performs. The LLVM
 * emitter records a call site for each in the generated IR, so without
 * definitions in the linked executable clang rejects
 * sun/awt/image/BufImgSurfaceData.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_image_BufImgSurfaceData_initRaster__Ljava_lang_Object_IIIIIILjava_awt_image_IndexColorModel__V'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, BufImgSurfaceData is the SurfaceData implementation for
 * BufferedImage-backed drawing surfaces. Its native side stores, in a
 * native BufImgSurfaceData struct, the pointer to the image's pixel
 * storage along with the geometric parameters needed to address it:
 *
 *   - `theArray` — the DataBuffer's underlying Java array (byte[],
 *     short[], or int[], depending on the BufferedImage type);
 *
 *   - `offset` — the first pixel's byte offset within the array;
 *
 *   - `bitoffset` — the bit offset within that byte (only non-zero for
 *     packed 1/2/4-bit rasters);
 *
 *   - `width`, `height`, `pixStr`, `scanStr` — the raster geometry;
 *
 *   - `icm` — the IndexColorModel, when the pixel data is index-based;
 *     null otherwise.
 *
 * The native pixel-blit loops in libawt read this struct to copy
 * pixels between BufferedImages without upcalling into Java. It is one
 * of the few places where the AWT pipeline genuinely accelerates a
 * software-only operation.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime does not link the native pixel-blit loops. There is no
 * per-pixel C code that could read the BufImgSurfaceData struct, so
 * storing the struct would provide no benefit — and it would introduce
 * a native allocation per BufferedImage that would never be released
 * (there is no dispose path for it in the Java-side class).
 *
 * Both natives are therefore strict no-ops:
 *
 *   initIDs      no-op. There are no JNI field IDs to cache.
 *
 *   initRaster   no-op. The Java-side class's own `getRaster` method
 *                returns `bufImg.getRaster()` directly, and every
 *                drawing operation that goes through a
 *                BufImgSurfaceData is dispatched to the Java-level
 *                `Raster.setDataElements` / `getDataElements` methods
 *                (or to `BufferedImage.setRGB` / `getRGB` for the
 *                fallback path). None of those paths consults the
 *                native struct that this method would have populated.
 *
 * The Java-side code is fully functional without the native struct:
 * the createData* factories build a BufImgSurfaceData whose `bufImg`
 * field points at the original BufferedImage, and every subsequent
 * operation reaches into that image through its own Java-level API.
 * The initializer is called for consistency of the class's
 * initialization protocol, but it has no work to do.
 *
 * This is a genuine "no native acceleration" answer, not a stub that
 * aborts. Aborting would break every program that draws into a
 * BufferedImage-backed Graphics2D — a purely in-memory operation that
 * has nothing to do with the display — because every such Graphics2D
 * reaches BufImgSurfaceData.createData during its construction.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * initIDs is a STATIC native: no receiver argument. It takes two
 * java.lang.Class references, which in LLVM terms arrive as two i8*
 * arguments. Both are captured and discarded.
 *
 * initRaster is an INSTANCE native: the receiver is the first argument,
 * followed by the eight Java-level parameters. The `theArray` argument
 * is an Object reference; `icm` is an IndexColorModel reference (or
 * null); the six int parameters carry the raster geometry. Every
 * argument is captured and discarded.
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
 *   class "sun/awt/image/BufImgSurfaceData"
 *       -> "sun_awt_image_BufImgSurfaceData"
 *
 *   initIDs    desc "(Ljava/lang/Class;Ljava/lang/Class;)V"
 *       -> "__jnative_fn_sun_awt_image_BufImgSurfaceData_initIDs__Ljava_lang_Class_Ljava_lang_Class__V"
 *   initRaster desc "(Ljava/lang/Object;IIIIIILjava/awt/image/IndexColorModel;)V"
 *       -> "__jnative_fn_sun_awt_image_BufImgSurfaceData_initRaster__Ljava_lang_Object_IIIIIILjava_awt_image_IndexColorModel__V"
 *
 * The initRaster name is the one the linker reported as undefined;
 * initIDs is emitted as well and would produce the same diagnostic on
 * the next rebuild if it were left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/image/BufImgSurfaceData") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns
 * "jnative/sun/awt/image/BufImgSurfaceData.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native void initIDs(Class<?> ICM,
 *                                    Class<?> ICMColorData);
 *
 * Called from BufImgSurfaceData.<clinit>. On HotSpot this hook caches
 * the JNI field IDs of IndexColorModel and of the inner
 * BufImgSurfaceData.ICMColorData class so the native struct can store
 * the ICM's data array pointer without a method lookup. This runtime
 * never consults JNI field IDs, so there is nothing to cache.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_image_BufImgSurfaceData_initIDs__Ljava_lang_Class_Ljava_lang_Class__V(
        void* icm_class,
        void* icm_color_data_class)
{
    (void)icm_class;
    (void)icm_color_data_class;
}

/* ============================================================================
 *  Instance natives
 * ============================================================================
 */

/*
 * protected native void initRaster(Object theArray,
 *                                  int offset,
 *                                  int bitoffset,
 *                                  int width,
 *                                  int height,
 *                                  int pixStr,
 *                                  int scanStr,
 *                                  IndexColorModel icm);
 *
 * No-op. On HotSpot this allocates a native BufImgSurfaceData struct
 * and stores the pixel-storage pointer and raster geometry in it, so
 * the C-side blit loops can copy pixels without upcalling into Java.
 * This runtime does not link those blit loops; every drawing operation
 * against a BufImgSurfaceData goes through the Java-level
 * Raster.setDataElements / getDataElements methods instead, and the
 * Java-side `getRaster` accessor returns the original BufferedImage's
 * raster directly. There is nothing for this native to store and no
 * consumer that would read the stored value.
 *
 * Every argument is captured and discarded.
 */
void __jnative_fn_sun_awt_image_BufImgSurfaceData_initRaster__Ljava_lang_Object_IIIIIILjava_awt_image_IndexColorModel__V(
        void* this_surface,
        void* the_array,
        int32_t offset,
        int32_t bitoffset,
        int32_t width,
        int32_t height,
        int32_t pix_str,
        int32_t scan_str,
        void* icm)
{
    (void)this_surface;
    (void)the_array;
    (void)offset;
    (void)bitoffset;
    (void)width;
    (void)height;
    (void)pix_str;
    (void)scan_str;
    (void)icm;
}
#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.awt.image.ImageRepresentation — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares three native methods:
 *
 *     private static native void initIDs();
 *     private native boolean setICMpixels(int x, int y, int w, int h,
 *                                         int[] lut, byte[] pix,
 *                                         int off, int scansize,
 *                                         IntegerComponentRaster ict);
 *     private native boolean setDiffICM(int x, int y, int w, int h,
 *                                       int[] lut, int transPix,
 *                                       int numLut, IndexColorModel icm,
 *                                       byte[] pix, int off, int scansize,
 *                                       ByteComponentRaster bct,
 *                                       int chanOff);
 *
 * initIDs is called from the class's static initializer. setICMpixels
 * and setDiffICM are called from setPixels(...) whenever the image is
 * produced through the AWT ImageProductionPipeline and the destination
 * raster happens to be an IndexColorModel-backed one. The LLVM emitter
 * records a call site for each in the generated IR, so without
 * definitions in the linked executable clang rejects
 * sun/awt/image/ImageRepresentation.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_image_ImageRepresentation_setDiffICM__IIII_IIILjava_awt_image_IndexColorModel__BIILsun_awt_image_ByteComponentRaster_I_Z'
 *
 * and stops. Providing only the symbol in the diagnostic would move the
 * error to the next one; all three are defined here so the module links
 * in one pass.
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, ImageRepresentation is the pipeline through which all
 * AWT Images produced by Toolkit.createImage() flow. Its native side
 * accelerates two specific pixel-transfer patterns that occur when the
 * image producer emits an IndexColorModel-backed image and the target
 * raster is either an IntegerComponentRaster (setICMpixels) or a
 * ByteComponentRaster (setDiffICM):
 *
 *   setICMpixels
 *       reads bytes from the source byte[], indexes them through the
 *       int[] LUT, and writes the resulting ARGB ints directly into
 *       the target IntegerComponentRaster's int storage. This avoids
 *       the per-pixel Java-level loop that would otherwise run.
 *
 *   setDiffICM
 *       handles the case where the incoming image's IndexColorModel
 *       differs from the one already installed on the target. It reads
 *       the source bytes, looks up the color for each in the new
 *       IndexColorModel, and writes the byte-indexed result into the
 *       ByteComponentRaster, remapping through the new LUT. This is a
 *       "differential ICM" update: only the pixels are changed, the
 *       raster's storage format stays byte-indexed.
 *
 * The two natives are called from setPixels(int, int, int, int,
 * ColorModel, byte[], int, int) on specific color-model combinations.
 * Both have a Java-side fallback path in the caller:
 *
 *   - setICMpixels: returning false makes the caller invoke abort(),
 *     which terminates image production for this image.
 *
 *   - setDiffICM: returning false makes the caller invoke
 *     convertToRGB(), which rewrites the entire image into RGB form
 *     and re-creates the raster as an IntegerComponentRaster. Every
 *     subsequent setPixels call then takes the IntegerComponentRaster
 *     code path.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime does not implement the native per-pixel acceleration
 * that libawt provides. The AWT image production path itself
 * (Toolkit.createImage -> InputStreamImageSource -> ImageProducer)
 * is never exercised in a headless runtime: no reachable code in a
 * compiled JNative image calls Toolkit.createImage with an input
 * stream or file.
 *
 * The three natives therefore provide the answers that keep the class
 * well-formed and that route every reachable code path to a working
 * Java-side fallback:
 *
 *   initIDs           no-op. There are no JNI field IDs to cache in
 *                     this runtime.
 *
 *   setICMpixels      returns false. The caller's fallback is to call
 *                     abort() and stop producing the current image —
 *                     the only truthful outcome for a runtime without
 *                     native pixel acceleration. Since no reachable
 *                     path in a compiled image invokes setPixels with
 *                     an IndexColorModel-backed source and an
 *                     IntegerComponentRaster destination, this
 *                     fallback never actually executes in practice.
 *
 *   setDiffICM        returns false. The caller's fallback is to call
 *                     convertToRGB(), a pure-Java method that
 *                     rewrites the image into RGB form and every
 *                     subsequent setPixels call uses the
 *                     IntegerComponentRaster code path. This is a
 *                     working Java-side fallback, so a hypothetical
 *                     caller would still get a correct (if
 *                     unaccelerated) image.
 *
 * Returning true from either native while silently skipping the pixel
 * update — as an alternative to returning false — would be worse:
 * markDirty() is invoked by the caller on a true return, and the raster
 * would then present whatever stale data was already in its storage to
 * every downstream consumer. Returning false is the only non-corrupting
 * answer.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * initIDs is a STATIC native: no receiver argument.
 *
 * setICMpixels and setDiffICM are INSTANCE natives: the receiver is the
 * first argument, followed by their Java-level parameters.
 *
 * The boolean returns of setICMpixels and setDiffICM are represented as
 * int32_t at the C ABI level, matching every other boolean native in
 * this runtime.
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
 *   class "sun/awt/image/ImageRepresentation"
 *       -> "sun_awt_image_ImageRepresentation"
 *
 *   initIDs      desc "()V"
 *       -> "__jnative_fn_sun_awt_image_ImageRepresentation_initIDs___V"
 *   setICMpixels desc "(IIII[I[BIILsun/awt/image/IntegerComponentRaster;)Z"
 *       -> "__jnative_fn_sun_awt_image_ImageRepresentation_setICMpixels__IIII_I_BIILsun_awt_image_IntegerComponentRaster__Z"
 *   setDiffICM   desc "(IIII[IIILjava/awt/image/IndexColorModel;[BIILsun/awt/image/ByteComponentRaster;I)Z"
 *       -> "__jnative_fn_sun_awt_image_ImageRepresentation_setDiffICM__IIII_IIILjava_awt_image_IndexColorModel__BIILsun_awt_image_ByteComponentRaster_I_Z"
 *
 * The setDiffICM name is the one the linker reported as undefined; the
 * other two are emitted as well and would produce the same diagnostic on
 * the next rebuild if left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/image/ImageRepresentation")
 * does not strip a leading "java/" — the class is in the sun.*
 * namespace — so it returns
 * "jnative/sun/awt/image/ImageRepresentation.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native void initIDs();
 *
 * Called from ImageRepresentation.<clinit>, after
 * NativeLibLoader.loadLibraries(). On HotSpot this hook caches the JNI
 * field and method IDs of the classes referenced by the two
 * pixel-transfer natives so that the C code can reach into their
 * objects without repeated lookups. This runtime accesses every field
 * through its LLVM-computed byte offset and never consults JNI field
 * IDs, so there is nothing to cache.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_image_ImageRepresentation_initIDs___V(void) {
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * private native boolean setICMpixels(int x, int y, int w, int h,
 *                                     int[] lut, byte[] pix,
 *                                     int off, int scansize,
 *                                     IntegerComponentRaster ict);
 *
 * Returns false. See the file header for the full rationale: this
 * runtime does not implement the native per-pixel transfer that this
 * method would perform, and the AWT image-production path that would
 * call it is not reachable in a headless runtime. Returning false
 * routes the Java-side caller to its abort() fallback, which is the
 * only non-corrupting answer for a runtime without native acceleration.
 *
 * The nine arguments are captured and discarded. Since no pixels are
 * transferred, none of them can affect the outcome.
 */
int32_t __jnative_fn_sun_awt_image_ImageRepresentation_setICMpixels__IIII_I_BIILsun_awt_image_IntegerComponentRaster__Z(
        void* this_rep,
        int32_t x, int32_t y, int32_t w, int32_t h,
        void* lut,
        void* pix, int32_t off, int32_t scansize,
        void* ict)
{
    (void)this_rep;
    (void)x;
    (void)y;
    (void)w;
    (void)h;
    (void)lut;
    (void)pix;
    (void)off;
    (void)scansize;
    (void)ict;
    return 0;
}

/*
 * private native boolean setDiffICM(int x, int y, int w, int h,
 *                                   int[] lut, int transPix,
 *                                   int numLut, IndexColorModel icm,
 *                                   byte[] pix, int off, int scansize,
 *                                   ByteComponentRaster bct,
 *                                   int chanOff);
 *
 * Returns false. See the file header for the full rationale: this
 * runtime does not implement the native differential-ICM remap that
 * this method would perform. Returning false routes the Java-side
 * caller to its convertToRGB() fallback, which rewrites the image into
 * RGB form entirely in Java and re-creates the target raster as an
 * IntegerComponentRaster. Every subsequent setPixels call then takes
 * the Java-side IntegerComponentRaster code path, so the resulting
 * image is fully correct even without native acceleration.
 *
 * The thirteen arguments are captured and discarded.
 */
int32_t __jnative_fn_sun_awt_image_ImageRepresentation_setDiffICM__IIII_IIILjava_awt_image_IndexColorModel__BIILsun_awt_image_ByteComponentRaster_I_Z(
        void* this_rep,
        int32_t x, int32_t y, int32_t w, int32_t h,
        void* lut,
        int32_t trans_pix, int32_t num_lut,
        void* icm,
        void* pix, int32_t off, int32_t scansize,
        void* bct, int32_t chan_off)
{
    (void)this_rep;
    (void)x;
    (void)y;
    (void)w;
    (void)h;
    (void)lut;
    (void)trans_pix;
    (void)num_lut;
    (void)icm;
    (void)pix;
    (void)off;
    (void)scansize;
    (void)bct;
    (void)chan_off;
    return 0;
}
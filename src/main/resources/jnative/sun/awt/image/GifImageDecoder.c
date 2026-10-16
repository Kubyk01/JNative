#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.awt.image.GifImageDecoder — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares two native methods:
 *
 *     private static native void initIDs();
 *     private native boolean parseImage(int x, int y, int width, int height,
 *                                       boolean interlace, int initCodeSize,
 *                                       byte[] block, byte[] rasline,
 *                                       IndexColorModel model);
 *
 * initIDs is called from the class's static initializer, immediately
 * after NativeLibLoader.loadLibraries(). parseImage is called from the
 * private readImage(...) method, which is itself reached from the public
 * produceImage() method whenever an application constructs a GIF-backed
 * Image via Toolkit.createImage(InputStream).
 *
 * The LLVM emitter records a call site for each in the generated IR, so
 * without definitions in the linked executable clang rejects
 * sun/awt/image/GifImageDecoder.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_image_GifImageDecoder_parseImage__IIIIZI_B_BLjava_awt_image_IndexColorModel__Z'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, GifImageDecoder is the ImageDecoder subclass that reads a
 * GIF bitstream from an InputStream through the native LZW decoder
 * linked into libawt:
 *
 *   initIDs           caches the JNI field IDs of GifImageDecoder so the
 *                     C decoder can reach into the Java-side per-frame
 *                     state without repeated lookups.
 *
 *   parseImage        runs the LZW decompression loop over the GIF data
 *                     sub-blocks. It reads the compressed codes from the
 *                     `block` argument, expands them through the code
 *                     table (the prefix/suffix/outCode arrays that the
 *                     Java-side class keeps for use by the native), and
 *                     writes one scanline of palette indices into
 *                     `rasline`. For each completed scanline it calls
 *                     back into the Java-side sendPixels method through
 *                     the GifFrame wrapper. Returns true on success,
 *                     false on any decompression error.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime does not link the native LZW GIF parser. The reasoning
 * that governs the sibling JPEG decoders (see
 * jnative/sun/awt/image/JPEGImageDecoder.c and
 * jnative/com/sun/imageio/plugins/jpeg/JPEGImageReader.c) applies here
 * as well:
 *
 *   - Reproducing the native LZW decoder would require linking a
 *     substantial C subsystem and re-deriving the JNI object-layout
 *     agreement between the Java-side callback (sendPixels, kept on
 *     GifFrame) and the C-side decompression state, for every JDK
 *     release.
 *
 *   - The AWT Image-production path that reaches this class
 *     (Toolkit.createImage(InputStream) -> InputStreamImageSource ->
 *     GifImageDecoder.produceImage) is not exercised by any reachable
 *     code path in a compiled JN image.
 *
 * The two natives therefore provide the answers that keep the class
 * well-formed:
 *
 *   initIDs      no-op. There are no JNI field IDs to cache in this
 *                runtime.
 *
 *   parseImage   returns false. The Java-side caller in readImage does
 *
 *                    if (!ret) { abort(); }
 *
 *                and abort() cleanly terminates image production for
 *                this image: it removes the consumer from the source,
 *                clears all per-frame state, and posts an ABORT
 *                notification to every watcher. That is exactly the
 *                same well-defined failure state the reference
 *                implementation produces when a GIF stream is malformed,
 *                and it is what every consumer in the ImageProducer
 *                chain is written to handle.
 *
 * Returning true while silently skipping the pixel transfer — as an
 * alternative to returning false — would be worse: the caller's
 * `if (!ret)` guard would not fire, `readImage` would proceed to the
 * next step, and the image's frame would be presented with whatever
 * stale pixels happen to be in `rasline` (typically zeros). Returning
 * false is the only non-corrupting answer for a runtime without the
 * native LZW decoder.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * initIDs is a STATIC native: no receiver argument.
 *
 * parseImage is an INSTANCE native: the receiver is the first argument,
 * followed by the nine Java-level parameters. The two boolean
 * parameters (`interlace` is the only boolean in the Java declaration;
 * the diagnostic shows it as `i1`) are represented as int32_t at the C
 * ABI level, matching every other boolean parameter of every other
 * native in this runtime. The return value is a boolean, represented as
 * int32_t at the C ABI level.
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
 *   class "sun/awt/image/GifImageDecoder"
 *       -> "sun_awt_image_GifImageDecoder"
 *
 *   initIDs    desc "()V"
 *       -> "__jnative_fn_sun_awt_image_GifImageDecoder_initIDs___V"
 *   parseImage desc "(IIIIZI[B[BLjava/awt/image/IndexColorModel;)Z"
 *       -> "__jnative_fn_sun_awt_image_GifImageDecoder_parseImage__IIIIZI_B_BLjava_awt_image_IndexColorModel__Z"
 *
 * The parseImage name is the one the linker reported as undefined;
 * initIDs is emitted as well and would produce the same diagnostic on
 * the next rebuild if it were left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/image/GifImageDecoder") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns
 * "jnative/sun/awt/image/GifImageDecoder.c". That is the path this file
 * must live at for Compiler.extractSystemNativeSources to find it and
 * compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native void initIDs();
 *
 * Called from GifImageDecoder.<clinit>, after NativeLibLoader
 * .loadLibraries(). On HotSpot this hook caches the JNI field IDs of
 * GifImageDecoder so the C LZW decoder can reach into the per-instance
 * code tables (prefix/suffix/outCode) and the per-frame state without
 * repeated lookups. This runtime never consults JNI field IDs, so there
 * is nothing to cache.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_image_GifImageDecoder_initIDs___V(void) {
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * private native boolean parseImage(int x, int y, int width, int height,
 *                                   boolean interlace, int initCodeSize,
 *                                   byte[] block, byte[] rasline,
 *                                   IndexColorModel model);
 *
 * Returns false. See the file header for the full rationale: this
 * runtime does not link the native LZW GIF parser, and the AWT
 * ImageProducer path that reaches this method is not exercised by any
 * reachable code in a compiled JN image.
 *
 * The caller (readImage) is documented to invoke abort() when the
 * return value is false, which cleanly terminates image production
 * for this image — the same failure state the reference implementation
 * produces on a malformed GIF, and the state every consumer in the
 * ImageProducer chain is written to handle.
 *
 * Every argument is captured and discarded. Since no pixel data is
 * transferred, none of them can affect the outcome.
 */
int32_t __jnative_fn_sun_awt_image_GifImageDecoder_parseImage__IIIIZI_B_BLjava_awt_image_IndexColorModel__Z(
        void* this_decoder,
        int32_t x,
        int32_t y,
        int32_t width,
        int32_t height,
        int32_t interlace,
        int32_t init_code_size,
        void* block,
        void* rasline,
        void* model)
{
    (void)this_decoder;
    (void)x;
    (void)y;
    (void)width;
    (void)height;
    (void)interlace;
    (void)init_code_size;
    (void)block;
    (void)rasline;
    (void)model;
    return 0;
}
/* src/main/resources/jnative/sun/awt/image/JPEGImageDecoder.c */

#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.awt.image.JPEGImageDecoder — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares two native methods:
 *
 *     private static native void initIDs(Class<?> InputStreamClass);
 *     private native void readImage(InputStream is, byte[] buf)
 *         throws ImageFormatException, IOException;
 *
 * initIDs is called from the class's static initializer, immediately
 * after System.loadLibrary("javajpeg"). readImage is called from the
 * public produceImage() method, which is itself reached whenever an
 * application constructs a JPEG-backed Image via
 * Toolkit.createImage(InputStream).
 *
 * The LLVM emitter records a call site for each in the generated IR,
 * so without definitions in the linked executable clang rejects
 * sun/awt/image/JPEGImageDecoder.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_image_JPEGImageDecoder_readImage__Ljava_io_InputStream__B_V'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, JPEGImageDecoder is the ImageDecoder subclass that reads
 * a JPEG bitstream from an InputStream through the IJG (Independent
 * JPEG Group) decoder linked into libjavajpeg:
 *
 *   initIDs           caches the JNI method ID of
 *                     InputStream.read(byte[], int, int) so the C
 *                     decoder can pull compressed bytes without a
 *                     method lookup on every read.
 *
 *   readImage         installs the InputStream as the decoder's byte
 *                     source, runs the IJG decompression loop, and for
 *                     each decoded scanline or full-image pass calls
 *                     back into the Java-side sendHeaderInfo and
 *                     sendPixels methods. It throws ImageFormatException
 *                     for malformed JPEG data and IOException for
 *                     underlying stream failures.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime does not link libjpeg. The same reasoning that governs
 * sun/awt/JPEGImageReader and JPEGImageWriter — see their sibling .c
 * files under jnative/com/sun/imageio/plugins/jpeg/ — applies here:
 *
 *   - Reproducing the IJG decoder would require shipping libjpeg,
 *     reproducing the entire JNI object-layout agreement between the
 *     Java-side JPEGImageDecoder callbacks (sendHeaderInfo, sendPixels)
 *     and the C-side decompression state, and re-deriving that
 *     agreement for every JDK release.
 *
 *   - The AWT Image-production path that reaches this class
 *     (Toolkit.createImage(InputStream) -> InputStreamImageSource ->
 *     JPEGImageDecoder.produceImage) is never exercised in a headless
 *     runtime. Nothing in a compiled JNative image's reachable closure
 *     calls Toolkit.createImage with a JPEG stream.
 *
 * The two natives therefore provide the answers that keep the class
 * well-formed:
 *
 *   initIDs           no-op. There are no JNI method IDs to cache in
 *                     this runtime.
 *
 *   readImage         throws an IOException with a diagnostic message
 *                     that names the missing codec. The Java-side
 *                     caller (produceImage) declares
 *                     `throws IOException, ImageFormatException`, so
 *                     an IOException is a well-defined propagation
 *                     that every caller in the ImageProducer chain
 *                     handles via ImageConsumer.imageComplete(ERROR).
 *
 * Returning silently from readImage without producing any image data
 * would be worse than throwing: the ImageProducer would complete with
 * an empty image, and the eventual Image that the caller holds would
 * be a permanently blank placeholder with no indication that the
 * decoder was unavailable.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * initIDs is a STATIC native: no receiver argument. Its Class<?>
 * parameter is captured and discarded.
 *
 * readImage is an INSTANCE native: the receiver is the first argument,
 * followed by the InputStream reference and the byte[] buffer. All
 * three (receiver, stream, buffer) are captured; the stream and buffer
 * would be the decoder's data source in a runtime that had one.
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
 *   class  "sun/awt/image/JPEGImageDecoder"
 *       -> "sun_awt_image_JPEGImageDecoder"
 *
 *   initIDs   desc "(Ljava/lang/Class;)V"
 *       -> "__jnative_fn_sun_awt_image_JPEGImageDecoder_initIDs__Ljava_lang_Class__V"
 *   readImage desc "(Ljava/io/InputStream;[B)V"
 *       -> "__jnative_fn_sun_awt_image_JPEGImageDecoder_readImage__Ljava_io_InputStream__B_V"
 *
 * The readImage name is the one the linker reported as undefined;
 * initIDs is emitted as well and would produce the same diagnostic on
 * the next rebuild if it were left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/image/JPEGImageDecoder") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns
 * "jnative/sun/awt/image/JPEGImageDecoder.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/*
 * Construct and throw a java.io.IOException that names the missing
 * codec. Never returns.
 *
 * The vtable lookup is by symbol name. If the class is somehow absent
 * from the image, __jnative_construct_exception returns NULL and
 * __jnative_throw_exception substitutes a generic Throwable carrying
 * the same diagnostic message; both outcomes preserve the message text
 * and both are strictly better than crashing on a null function
 * pointer inside the LLVM-generated call.
 */
__attribute__((noreturn))
static void throw_jpeg_decoder_not_implemented(void) {
    static const char MSG[] =
        "JPEG decoding is not implemented in this JNative image "
        "(sun.awt.image.JPEGImageDecoder.readImage). This runtime does "
        "not link libjpeg; the class is present only so that the AWT "
        "ImageProducer chain links. If you need to read JPEG images, "
        "rebuild the runtime with the libjavajpeg JNI bridge and its IJG "
        "dependency included in the build.";

    void* exc = __jnative_construct_exception(
        "vtable_java_io_IOException", MSG);
    __jnative_throw_exception(exc);
}

/*
 * private static native void initIDs(Class<?> InputStreamClass);
 *
 * Called from JPEGImageDecoder.<clinit>, immediately after
 * System.loadLibrary("javajpeg"). On HotSpot this hook caches the JNI
 * method ID of InputStream.read(byte[], int, int) so the C decoder can
 * pull compressed bytes without a method lookup on every read. This
 * runtime never consults JNI method IDs, so there is nothing to cache.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_image_JPEGImageDecoder_initIDs__Ljava_lang_Class__V(
        void* input_stream_class)
{
    (void)input_stream_class;
}

/*
 * private native void readImage(InputStream is, byte[] buf)
 *     throws ImageFormatException, IOException;
 *
 * Throws a diagnosed IOException. See the file header for the full
 * rationale: this runtime does not link libjpeg, and the AWT
 * ImageProducer path that reaches this method is never exercised in a
 * headless runtime. The Java-side caller declares `throws IOException`,
 * so the throw propagates cleanly to every consumer in the
 * ImageProducer chain, which handles it through
 * ImageConsumer.imageComplete(ERROR).
 *
 * The `is` and `buf` arguments are captured and discarded.
 */
void __jnative_fn_sun_awt_image_JPEGImageDecoder_readImage__Ljava_io_InputStream__B_V(
        void* this_decoder,
        void* input_stream,
        void* buf)
{
    (void)this_decoder;
    (void)input_stream;
    (void)buf;

    throw_jpeg_decoder_not_implemented();
}
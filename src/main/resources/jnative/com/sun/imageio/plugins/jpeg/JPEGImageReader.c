#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#include "jnative_runtime.h"

/*
 * com.sun.imageio.plugins.jpeg.JPEGImageReader — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares eleven native methods. Every one of them is called
 * unconditionally from some reachable method of the class, so the LLVM
 * emitter records a call site for each in the generated IR. Without
 * definitions in the linked executable, clang rejects the generated
 * com/sun/imageio/plugins/jpeg/JPEGImageReader.ll with
 *     error: use of undefined value '@__jnative_fn_...'
 * and the build stops.
 *
 * ============================================================================
 * Why the codec is not implemented
 * ============================================================================
 *
 * The reference JDK implements JPEGImageReader on top of libjpeg through
 * the JNI bridge in src/java.desktop/share/native/libjavajpeg/. Linking
 * libjpeg into a JNative image would require:
 *
 *   - shipping libjpeg (a large external dependency) with the build;
 *   - reproducing the entire JNI object-layout agreement between the
 *     JDK-side Java objects and the IJG library's C structs, which is
 *     exactly the class of hand-maintained ABI this runtime was built to
 *     avoid;
 *   - re-deriving those agreements for every JDK release, since the
 *     fields the IJG binding writes into (JPEGQTable.data,
 *     JPEGHuffmanTable.values, JPEGMetadata.markerSequence, …) are not
 *     stable API.
 *
 * That work is out of proportion to the benefit. What this runtime
 * actually needs, in the images that pull JPEGImageReader into the
 * reachability closure, is: the class must be constructible, its
 * Disposer must be able to release it, and any attempt to decode an
 * actual JPEG must fail with a diagnostic that names the problem.
 *
 * ============================================================================
 * What this implementation provides
 * ============================================================================
 *
 *   initReaderIDs        no-op  (nothing to cache: the runtime does not
 *                                consult JNI field IDs)
 *   initJPEGImageReader  calloc a small state struct, return its address
 *   setSource            no-op  (no source attachment to record)
 *   readImageHeader      returns false ("not a tables-only image") and
 *                        does NOT call setImageData; the Java layer's
 *                        subsequent getImageTypes() will see colorSpaceCode
 *                        == 0 and return an empty iterator, so read()
 *                        raises "Unsupported Image Type" cleanly
 *   setOutColorSpace     no-op
 *   readImage            throws javax.imageio.IIOException with a message
 *                        that names the missing codec
 *   clearNativeReadAbortFlag  no-op
 *   abortRead            stores the abort flag in the state struct
 *   resetLibraryState    no-op
 *   resetReader          clears the state struct in place
 *   disposeReader        frees the state struct
 *
 * Every constructor path in the class (the ImageReaderSpi-driven
 * instantiation from ImageIO, a direct `new JPEGImageReader(spi)`,
 * Disposer registration) therefore succeeds. Every release path
 * (dispose(), the Disposer's fallback) is idempotent and safe. Every
 * actual decode path fails with a message the caller can act on, rather
 * than producing a zeroed BufferedImage that looks like a successful
 * decode of a black photograph.
 *
 * ============================================================================
 * Disposer interaction
 * ============================================================================
 *
 * JPEGReaderDisposerRecord.dispose() invokes disposeReader(pData) and
 * then sets pData = 0, so disposeReader is called exactly once per
 * reader instance. A zero argument is treated as "already disposed"
 * and is a no-op; a non-zero argument is freed and the pointer is not
 * consulted again (the Java side has already dropped its reference by
 * the time the call returns).
 *
 * The state struct is deliberately minimal: two ints. The struct exists
 * only so that a non-zero, distinguishable handle can be handed back to
 * Java and later recognised as a valid one; the codec itself has no
 * state to preserve between calls.
 */

typedef struct {
    int aborted;
    int header_read;
} JpegReaderState;

/*
 * Construct and throw a javax.imageio.IIOException that names the
 * missing codec. Never returns.
 *
 * The vtable lookup is by symbol name; if the class is somehow absent
 * from the image, __jnative_construct_exception returns NULL and
 * __jnative_throw_exception substitutes a generic Throwable carrying
 * the same diagnostic message. Both outcomes are strictly better than
 * the previous behaviour — crashing on a null function pointer inside
 * the LLVM-generated call — and both preserve the message text so a
 * user who hits this path can find the source immediately.
 */
__attribute__((noreturn))
static void throw_jpeg_decoder_not_implemented(const char* where) {
    char msg[512];
    snprintf(msg, sizeof(msg),
        "JPEG decoding is not implemented in this JNative image (%s). "
        "This runtime does not link libjpeg; the class com.sun."
        "imageio.plugins.jpeg.JPEGImageReader is present only so that "
        "ImageIO can enumerate and construct it. If you need JPEG "
        "decoding, rebuild the runtime with the libjavajpeg JNI bridge "
        "and its IJG dependency included in the build.",
        where != NULL ? where : "<unknown>");
    void* exc = __jnative_construct_exception(
        "vtable_javax_imageio_IIOException", msg);
    __jnative_throw_exception(exc);
}

/*
 * static native void initReaderIDs(Class<?> iisClass,
 *                                  Class<?> qTableClass,
 *                                  Class<?> huffClass);
 *
 * Called from JPEGImageReader.<clinit>. On HotSpot this hook caches the
 * JNI field IDs of ImageInputStream, JPEGQTable and JPEGHuffmanTable so
 * the native read path can reach into those objects without repeated
 * lookups. This runtime accesses every field through its LLVM-computed
 * byte offset and never consults JNI field IDs, so there is nothing to
 * cache. The symbol must exist because the class's static initializer
 * emits a native call to it.
 */
void __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageReader_initReaderIDs__Ljava_lang_Class_Ljava_lang_Class_Ljava_lang_Class__V(
        void* iis_cls, void* qtable_cls, void* huff_cls)
{
    (void)iis_cls;
    (void)qtable_cls;
    (void)huff_cls;
}

/*
 * private native long initJPEGImageReader();
 *
 * Called from the public JPEGImageReader(ImageReaderSpi) constructor.
 * Returns the address of a freshly allocated, zero-initialised state
 * struct. The Java side stores the value in a long field and passes it
 * back to every subsequent native call.
 *
 * A calloc failure surfaces as OutOfMemoryError through the generic
 * throw helper; there is no "null pointer" path because the Java
 * constructor cannot recover from one.
 */
int64_t __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageReader_initJPEGImageReader___J(
        void* self)
{
    (void)self;
    JpegReaderState* s = (JpegReaderState*)calloc(1, sizeof(JpegReaderState));
    if (s == NULL) {
        __jnative_throw_out_of_memory_error_ctx("initJPEGImageReader");
    }
    return (int64_t)(intptr_t)s;
}

/*
 * private native void setSource(long structPointer);
 *
 * Called from setInput after the native struct has been created. On
 * HotSpot this hook attaches the ImageInputStream to the IJG source
 * manager so that the C decoder can pull bytes. This runtime has no C
 * decoder to attach; the Java-side iis field is what every reachable
 * code path in this runtime uses.
 */
void __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageReader_setSource__J_V(
        void* self, int64_t structPointer)
{
    (void)self;
    (void)structPointer;
}

/*
 * private native boolean readImageHeader(long structPointer,
 *                                        boolean clearBuffer,
 *                                        boolean reset)
 *     throws IOException;
 *
 * On HotSpot this hook drives the IJG library to parse the SOI/SOF/…
 * marker sequence and, at the end, invoke setImageData(width, height,
 * colorSpaceCode, outColorSpaceCode, numComponents, iccData) so the Java
 * layer learns the image's dimensions and colour space.
 *
 * This runtime cannot parse a JPEG stream, so it returns false — "this
 * is not a tables-only image" — without invoking setImageData. The
 * consequence at the Java layer is well-defined: colorSpaceCode stays
 * at its int-field default of 0, getImageTypes() sees a code outside
 * the valid range and returns an empty iterator, and readInternal()
 * raises
 *
 *     IIOException("Unsupported Image Type")
 *
 * at the point where the caller actually tries to read pixels. Before
 * that point, all of the reader's metadata-only query methods
 * (getNumImages, getWidth with no prior read, …) return their
 * defaults without raising, so the class remains usable for the
 * "enumerate and inspect" use cases that pulled it into the reachability
 * closure in the first place.
 *
 * Returning false rather than throwing is deliberate. Throwing here
 * would make setInput itself fail, which would abort the ImageIO
 * registry scan on the first JPEG entry it encountered even if the
 * program never actually opened a JPEG. Returning false confines the
 * failure to the moment of the first real decode attempt.
 */
int32_t __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageReader_readImageHeader__JZZ_Z(
        void* self, int64_t structPointer,
        int32_t clearBuffer, int32_t reset)
{
    (void)self;
    (void)clearBuffer;
    (void)reset;

    JpegReaderState* s = (JpegReaderState*)(intptr_t)structPointer;
    if (s != NULL) {
        s->header_read = 1;
    }
    return 0;
}

/*
 * private native void setOutColorSpace(long structPointer, int id);
 *
 * Called from checkColorConversion when the Java layer has decided that
 * IJG should perform the colour-space conversion itself (for example,
 * converting a YCbCr stream to RGB on the fly). This runtime has no IJG
 * to command, so the call is a no-op. The Java side also updates its own
 * outColorSpaceCode and numComponents fields immediately after the call,
 * so the state stays consistent from Java's point of view.
 */
void __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageReader_setOutColorSpace__JI_V(
        void* self, int64_t structPointer, int32_t id)
{
    (void)self;
    (void)structPointer;
    (void)id;
}

/*
 * private native boolean readImage(int imageIndex,
 *                                  long structPointer,
 *                                  byte[] buffer,
 *                                  int numRasterBands,
 *                                  int[] srcBands,
 *                                  int[] bandSizes,
 *                                  int sourceXOffset, int sourceYOffset,
 *                                  int sourceWidth,  int sourceHeight,
 *                                  int periodX, int periodY,
 *                                  JPEGQTable[] abbrevQTables,
 *                                  JPEGHuffmanTable[] abbrevDCHuffmanTables,
 *                                  JPEGHuffmanTable[] abbrevACHuffmanTables,
 *                                  int minProgressivePass,
 *                                  int maxProgressivePass,
 *                                  boolean wantUpdates);
 *
 * The single decode entry point. On HotSpot this is where the actual
 * JPEG decoding happens: a native main loop calls back into
 * acceptPixels() for each decoded scanline and into passStarted /
 * passComplete for each progressive pass, and returns a boolean that
 * indicates whether the read was aborted via abortRead.
 *
 * This runtime cannot decode JPEG, so the method raises a diagnosed
 * IIOException. The Java caller in readInternal() will catch it (the
 * method declares `throws IOException`), call processReadAborted() or
 * propagate, and the caller of read() sees the diagnostic.
 *
 * Returning false ("not aborted, success") would be wrong: the output
 * buffer would still contain only zeros, the BufferedImage would be
 * returned to the caller as a successfully decoded all-black image,
 * and any code that depends on the decoded content would silently
 * operate on garbage. Throwing is the honest answer.
 *
 * The 18 arguments are all captured but not consulted. The read cannot
 * make progress without a decoder, so none of them can affect the
 * outcome. They are accepted with their exact C signatures so the
 * LLVM-emitted call site links against this definition.
 */
int32_t __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageReader_readImage__IJ_BI_I_IIIIIII_Ljavax_imageio_plugins_jpeg_JPEGQTable__Ljavax_imageio_plugins_jpeg_JPEGHuffmanTable__Ljavax_imageio_plugins_jpeg_JPEGHuffmanTable__IIZ_Z(
        void* self,
        int32_t imageIndex,
        int64_t structPointer,
        void* buffer,
        int32_t numRasterBands,
        void* srcBands,
        void* bandSizes,
        int32_t sourceXOffset,
        int32_t sourceYOffset,
        int32_t sourceWidth,
        int32_t sourceHeight,
        int32_t periodX,
        int32_t periodY,
        void* abbrevQTables,
        void* abbrevDCHuffmanTables,
        void* abbrevACHuffmanTables,
        int32_t minProgressivePass,
        int32_t maxProgressivePass,
        int32_t wantUpdates)
{
    (void)self;
    (void)imageIndex;
    (void)structPointer;
    (void)buffer;
    (void)numRasterBands;
    (void)srcBands;
    (void)bandSizes;
    (void)sourceXOffset;
    (void)sourceYOffset;
    (void)sourceWidth;
    (void)sourceHeight;
    (void)periodX;
    (void)periodY;
    (void)abbrevQTables;
    (void)abbrevDCHuffmanTables;
    (void)abbrevACHuffmanTables;
    (void)minProgressivePass;
    (void)maxProgressivePass;
    (void)wantUpdates;

    throw_jpeg_decoder_not_implemented("readImage");
}

/*
 * private native void clearNativeReadAbortFlag(long structPointer);
 *
 * Called by readInternal immediately before invoking readImage, so
 * that an abort request issued during a previous, already-terminated
 * read does not spuriously abort this one. The flag lives in the state
 * struct; clearing it is the only work required.
 *
 * A null struct pointer is a programming error at the Java layer — the
 * constructor always allocates one and dispose() nulls the field only
 * after calling disposeReader — but it is treated as a no-op rather
 * than raising, because the flag's only consumer (readImage) also
 * treats a null struct as "no abort requested".
 */
void __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageReader_clearNativeReadAbortFlag__J_V(
        void* self, int64_t structPointer)
{
    (void)self;
    JpegReaderState* s = (JpegReaderState*)(intptr_t)structPointer;
    if (s != NULL) {
        s->aborted = 0;
    }
}

/*
 * private native void abortRead(long structPointer);
 *
 * Sets the abort flag in the state struct. The flag is consulted by
 * any subsequent readImage call on this reader; in this runtime
 * readImage always throws before reaching the flag check, so the flag
 * is effectively a record of the abort request rather than a signal
 * that drives a decoder loop. Storing it is the truthful representation
 * of the class's internal state and keeps abort() idempotent.
 */
void __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageReader_abortRead__J_V(
        void* self, int64_t structPointer)
{
    (void)self;
    JpegReaderState* s = (JpegReaderState*)(intptr_t)structPointer;
    if (s != NULL) {
        s->aborted = 1;
    }
}

/*
 * private native void resetLibraryState(long structPointer);
 *
 * Called from the catch blocks in read() and readRaster() when a
 * RuntimeException or IOException escapes the decode path. On HotSpot
 * this restores the IJG library to a clean state so the next read can
 * proceed without re-creating the C structure. This runtime has no
 * library state to reset; the state struct's fields are already the
 * only bookkeeping it carries.
 */
void __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageReader_resetLibraryState__J_V(
        void* self, int64_t structPointer)
{
    (void)self;
    (void)structPointer;
}

/*
 * private native void resetReader(long structPointer);
 *
 * Called from resetInternalState on every setInput. On HotSpot this
 * tears down the per-stream IJG state and re-creates it from scratch,
 * so a fresh ImageInputStream can be attached. This runtime has no
 * per-stream state to tear down, but clearing the two bookkeeping
 * fields keeps the struct in the same "freshly constructed" shape it
 * has after initJPEGImageReader.
 */
void __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageReader_resetReader__J_V(
        void* self, int64_t structPointer)
{
    (void)self;
    JpegReaderState* s = (JpegReaderState*)(intptr_t)structPointer;
    if (s != NULL) {
        s->aborted = 0;
        s->header_read = 0;
    }
}

/*
 * private static native void disposeReader(long structPointer);
 *
 * The actual release path. Called exactly once per reader instance
 * from JPEGReaderDisposerRecord.dispose(), which sets its own pData to
 * zero after the call, so a second invocation for the same instance
 * cannot happen from the Java side.
 *
 * A zero argument is a no-op: the Disposer records pData == 0 as "this
 * reader has already been released", and the guard at the top of
 * JPEGReaderDisposerRecord.dispose() means the native is not invoked
 * in that state. A defensive zero check is still worth having, because
 * a future caller could bypass the Disposer and call this directly.
 *
 * This is a STATIC native: there is no receiver argument. The emitted
 * call site passes only the long, exactly as the signature below
 * declares.
 */
void __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageReader_disposeReader__J_V(
        int64_t structPointer)
{
    if (structPointer != 0) {
        free((void*)(intptr_t)structPointer);
    }
}
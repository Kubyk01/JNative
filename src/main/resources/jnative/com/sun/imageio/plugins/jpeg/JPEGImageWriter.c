#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#include "jnative_runtime.h"

/*
 * com.sun.imageio.plugins.jpeg.JPEGImageWriter — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * Symmetrically with JPEGImageReader, the writer class declares eight
 * native methods, every one of which is called from some reachable
 * method of the class. The LLVM emitter records a call site for each,
 * and without a definition in the linked executable clang rejects the
 * generated JPEGImageWriter.ll with
 *     error: use of undefined value '@__jnative_fn_...'
 *
 * ============================================================================
 * What this implementation provides
 * ============================================================================
 *
 *   initWriterIDs        no-op  (nothing to cache: no JNI field IDs are
 *                                consulted by this runtime)
 *   initJPEGImageWriter  calloc a small state struct, return its address
 *   setDest              no-op  (no destination to attach at the C level)
 *   writeImage           throws javax.imageio.IIOException with a
 *                        message that names the missing codec
 *   writeTables          throws the same diagnostic
 *   abortWrite           stores the abort flag
 *   resetWriter          clears the state struct in place
 *   disposeWriter        frees the state struct
 *
 * Constructor, setOutput, reset, and dispose all succeed. Actually
 * writing a JPEG fails with a message that names the missing codec, at
 * the exact point where the first pixel would have been encoded.
 *
 * The alternative — returning true from writeImage without writing
 * anything — is a silent data-loss bug: the caller's ImageOutputStream
 * would end up containing nothing, the caller would think the write
 * succeeded, and the resulting file would be unopenable. Throwing is
 * the only honest answer.
 *
 * ============================================================================
 * Disposer interaction
 * ============================================================================
 *
 * Same structure as the reader side: JPEGWriterDisposerRecord.dispose()
 * invokes disposeWriter(pData) and then sets pData = 0, so the native
 * sees a zero argument at most once per writer instance and only if a
 * caller bypasses the Disposer's own guard.
 */

typedef struct {
    int aborted;
} JpegWriterState;

__attribute__((noreturn))
static void throw_jpeg_encoder_not_implemented(const char* where) {
    char msg[512];
    snprintf(msg, sizeof(msg),
        "JPEG encoding is not implemented in this JNative image (%s). "
        "This runtime does not link libjpeg; the class com.sun."
        "imageio.plugins.jpeg.JPEGImageWriter is present only so that "
        "ImageIO can enumerate and construct it. If you need JPEG "
        "encoding, rebuild the runtime with the libjavajpeg JNI bridge "
        "and its IJG dependency included in the build.",
        where != NULL ? where : "<unknown>");
    void* exc = __jnative_construct_exception(
        "vtable_javax_imageio_IIOException", msg);
    __jnative_throw_exception(exc);
}

/*
 * private static native void initWriterIDs(Class<?> qTableClass,
 *                                          Class<?> huffClass);
 *
 * Called from JPEGImageWriter.<clinit>. Same reasoning as the reader's
 * initReaderIDs: there are no JNI field IDs to cache in this runtime,
 * and the symbol must exist because the static initializer emits a
 * native call to it.
 *
 * This is a STATIC native: no receiver argument.
 */
void __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageWriter_initWriterIDs__Ljava_lang_Class_Ljava_lang_Class__V(
        void* qtable_cls, void* huff_cls)
{
    (void)qtable_cls;
    (void)huff_cls;
}

/*
 * private native long initJPEGImageWriter();
 *
 * Called from the public JPEGImageWriter(ImageWriterSpi) constructor.
 * Returns a freshly allocated, zero-initialised state struct address.
 * A calloc failure surfaces as OutOfMemoryError through the generic
 * throw helper.
 */
int64_t __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageWriter_initJPEGImageWriter___J(
        void* self)
{
    (void)self;
    JpegWriterState* s = (JpegWriterState*)calloc(1, sizeof(JpegWriterState));
    if (s == NULL) {
        __jnative_throw_out_of_memory_error_ctx("initJPEGImageWriter");
    }
    return (int64_t)(intptr_t)s;
}

/*
 * private native void setDest(long structPointer);
 *
 * Called from setOutput after the destination ImageOutputStream has
 * been installed on the Java side. On HotSpot this hook attaches the
 * stream to the IJG destination manager so the C encoder can push
 * bytes out through it. This runtime has no C encoder to attach; the
 * Java-side ios field is what every reachable code path in this
 * runtime uses.
 */
void __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageWriter_setDest__J_V(
        void* self, int64_t structPointer)
{
    (void)self;
    (void)structPointer;
}

/*
 * private native boolean writeImage(long structPointer,
 *                                   byte[] data,
 *                                   int inCsType, int outCsType,
 *                                   int numBands,
 *                                   int[] bandSizes,
 *                                   int srcWidth,
 *                                   int destWidth, int destHeight,
 *                                   int stepX, int stepY,
 *                                   JPEGQTable[] qtables,
 *                                   boolean writeDQT,
 *                                   JPEGHuffmanTable[] DCHuffmanTables,
 *                                   JPEGHuffmanTable[] ACHuffmanTables,
 *                                   boolean writeDHT,
 *                                   boolean optimizeHuffman,
 *                                   boolean progressive,
 *                                   int numScans,
 *                                   int[] scans,
 *                                   int[] componentIds,
 *                                   int[] HsamplingFactors,
 *                                   int[] VsamplingFactors,
 *                                   int[] QtableSelectors,
 *                                   boolean haveMetadata,
 *                                   int restartInterval);
 *
 * The single encode entry point. On HotSpot this drives libjpeg's
 * main compression loop, calling back into grabPixels() for each
 * source scanline and into writeOutputData() for each chunk of encoded
 * bytes. It returns a boolean that indicates whether the write was
 * aborted.
 *
 * This runtime cannot encode JPEG. The method raises a diagnosed
 * IIOException, which is propagated through writeOnThread's
 * `throws IOException` declaration and reaches the caller of write().
 *
 * Returning false ("not aborted, success") would leave the destination
 * stream empty while the caller believed the image had been written —
 * a silent data-loss bug. Returning true ("aborted") would attribute
 * the failure to an abort that never happened and would silently skip
 * the caller's processWriteAborted()/processImageComplete() handling.
 * Throwing is the only truthful option.
 *
 * All twenty-six arguments are captured but not consulted.
 */
int32_t __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageWriter_writeImage__J_BIII_IIIIII_Ljavax_imageio_plugins_jpeg_JPEGQTable_Z_Ljavax_imageio_plugins_jpeg_JPEGHuffmanTable__Ljavax_imageio_plugins_jpeg_JPEGHuffmanTable__ZZZI_I_I_I_I_IZI_Z(
        void* self,
        int64_t structPointer,
        void* data,
        int32_t inCsType,
        int32_t outCsType,
        int32_t numBands,
        void* bandSizes,
        int32_t srcWidth,
        int32_t destWidth,
        int32_t destHeight,
        int32_t stepX,
        int32_t stepY,
        void* qtables,
        int32_t writeDQT,
        void* DCHuffmanTables,
        void* ACHuffmanTables,
        int32_t writeDHT,
        int32_t optimizeHuffman,
        int32_t progressive,
        int32_t numScans,
        void* scans,
        void* componentIds,
        void* HsamplingFactors,
        void* VsamplingFactors,
        void* QtableSelectors,
        int32_t haveMetadata,
        int32_t restartInterval)
{
    (void)self;
    (void)structPointer;
    (void)data;
    (void)inCsType;
    (void)outCsType;
    (void)numBands;
    (void)bandSizes;
    (void)srcWidth;
    (void)destWidth;
    (void)destHeight;
    (void)stepX;
    (void)stepY;
    (void)qtables;
    (void)writeDQT;
    (void)DCHuffmanTables;
    (void)ACHuffmanTables;
    (void)writeDHT;
    (void)optimizeHuffman;
    (void)progressive;
    (void)numScans;
    (void)scans;
    (void)componentIds;
    (void)HsamplingFactors;
    (void)VsamplingFactors;
    (void)QtableSelectors;
    (void)haveMetadata;
    (void)restartInterval;

    throw_jpeg_encoder_not_implemented("writeImage");
}

/*
 * private native void writeTables(long structPointer,
 *                                 JPEGQTable[] qtables,
 *                                 JPEGHuffmanTable[] DCHuffmanTables,
 *                                 JPEGHuffmanTable[] ACHuffmanTables);
 *
 * Called from prepareWriteSequenceOnThread when the caller has supplied
 * stream metadata and asked for a tables-only image to be written at
 * the head of the sequence. On HotSpot this emits the DQT and DHT
 * marker segments that the following images will reference.
 *
 * This runtime cannot emit a legal JPEG marker segment, so the method
 * raises a diagnosed IIOException. The caller's `throws IOException`
 * declaration propagates it to whoever invoked prepareWriteSequence.
 */
void __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageWriter_writeTables__J_Ljavax_imageio_plugins_jpeg_JPEGQTable__Ljavax_imageio_plugins_jpeg_JPEGHuffmanTable__Ljavax_imageio_plugins_jpeg_JPEGHuffmanTable__V(
        void* self,
        int64_t structPointer,
        void* qtables,
        void* DCHuffmanTables,
        void* ACHuffmanTables)
{
    (void)self;
    (void)structPointer;
    (void)qtables;
    (void)DCHuffmanTables;
    (void)ACHuffmanTables;

    throw_jpeg_encoder_not_implemented("writeTables");
}

/*
 * private native void abortWrite(long structPointer);
 *
 * Sets the abort flag. Called from abort() (which is itself callable at
 * any time, including before a write has started). The flag is stored
 * so that a subsequent writeImage on this writer sees the request; in
 * this runtime writeImage throws before reaching any flag check, so the
 * stored value is a record of the last abort request rather than a
 * signal that drives an encoder loop. Storing it keeps abort()
 * idempotent and the state struct consistent.
 */
void __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageWriter_abortWrite__J_V(
        void* self, int64_t structPointer)
{
    (void)self;
    JpegWriterState* s = (JpegWriterState*)(intptr_t)structPointer;
    if (s != NULL) {
        s->aborted = 1;
    }
}

/*
 * private native void resetWriter(long structPointer);
 *
 * Called from resetInternalState (on setOutput) and from
 * clearAbortRequest. On HotSpot this tears the IJG encoder down and
 * re-creates it, so a fresh ImageOutputStream can be attached and a
 * pending abort can be discarded. This runtime has no encoder to
 * rebuild, but clearing the bookkeeping field brings the struct back
 * to its freshly-constructed shape.
 */
void __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageWriter_resetWriter__J_V(
        void* self, int64_t structPointer)
{
    (void)self;
    JpegWriterState* s = (JpegWriterState*)(intptr_t)structPointer;
    if (s != NULL) {
        s->aborted = 0;
    }
}

/*
 * private static native void disposeWriter(long structPointer);
 *
 * The actual release path. Called exactly once per writer instance from
 * JPEGWriterDisposerRecord.dispose(), which sets its own pData to zero
 * after the call. Same reasoning as the reader's disposeReader: a zero
 * argument is a defensive no-op, a non-zero argument is freed and not
 * consulted again.
 *
 * This is a STATIC native: no receiver argument.
 */
void __jnative_fn_com_sun_imageio_plugins_jpeg_JPEGImageWriter_disposeWriter__J_V(
        int64_t structPointer)
{
    if (structPointer != 0) {
        free((void*)(intptr_t)structPointer);
    }
}
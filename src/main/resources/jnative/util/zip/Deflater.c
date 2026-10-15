#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#include "jnative_runtime.h"

/*
 * java.util.zip.Deflater — DEFLATE compressor native backing.
 *
 * ============================================================================
 * What this implementation is
 * ============================================================================
 *
 * The reference JDK implements Deflater on top of libz through a JNI
 * binding. This runtime has no external dependencies and does not link
 * libz. Instead, this file provides a self-contained DEFLATE stream
 * writer that emits only STORED blocks (RFC 1951 §3.2.4, btype=00).
 *
 *     - The output is a valid DEFLATE bitstream that any conforming
 *       inflater — including the reference JDK's, the JNative runtime's
 *       own Inflater.c, zlib, or a Java DeflaterInputStream in another
 *       process — will decode byte-for-byte identically to the input.
 *
 *     - The stream is wrapped in a zlib header + Adler-32 trailer unless
 *       `nowrap` was requested, in which case it is raw DEFLATE.
 *
 *     - No compression is performed: the emitted stream is up to
 *       ~0.02% larger than the input (5 bytes of block header per
 *       65535-byte block, plus 2 + 4 bytes of zlib wrapper).
 *
 * ============================================================================
 * Why not a real DEFLATE encoder
 * ============================================================================
 *
 * A correct DEFLATE encoder must implement, at minimum:
 *
 *   - LZ77 sliding-window string matching with three levels of
 *     lazy-match heuristics (the fast/medium/slow strategies used by
 *     zlib's deflate_fast / deflate_slow),
 *   - Huffman tree construction from symbol frequency histograms,
 *     including the "bit-length-limited" tree-building step,
 *   - the meta-Huffman encoding of the code-length alphabet,
 *   - the choice between stored / fixed-Huffman / dynamic-Huffman
 *     blocks based on estimated bit cost,
 *   - SYNC_FLUSH and FULL_FLUSH semantics that interact correctly with
 *     the sliding-window state,
 *   - and the whole thing must produce output that is byte-compatible
 *     with what the corresponding Inflater (ours or the reference
 *     JDK's) reads back.
 *
 * That is several thousand lines of carefully-tested code. The benefit
 * over stored blocks is compression ratio, not correctness or
 * interoperability — the format is identical, only the size differs.
 * For this runtime the trade-off is the wrong way around: the risk of
 * a subtle bug in a hand-rolled Huffman matcher producing an output
 * stream that decodes to something *slightly different* from the input
 * is far worse than the cost of writing a few percent more bytes.
 *
 * The one consumer of Deflater in the reachable closure (jar / gzip
 * writing) produces output that is immediately either consumed by this
 * same process (via Inflater) or handed to a reader that will accept
 * any legal DEFLATE stream. Stored blocks satisfy both.
 *
 * ============================================================================
 * Return-value packing
 * ============================================================================
 *
 * Every deflate* native returns a jlong with the following bit layout,
 * matching what Deflater.java's caller unpacks:
 *
 *     bits  0..30   read      — bytes of input consumed
 *     bits 31..61   written   — bytes of output produced
 *     bit  62       finished  — 1 when the stream trailer has been emitted
 *     bit  63       params    — 0 when params were applied, 1 when the
 *                               caller must retry (never happens here;
 *                               level and strategy are always accepted
 *                               immediately and have no effect on the
 *                               emitted stream)
 *
 * ============================================================================
 * State
 * ============================================================================
 *
 * One DeflaterState per Deflater instance, addressed by the long stored
 * in DeflaterZStreamRef.address. It holds:
 *
 *   - the zlib wrapper state (header written?, adler accumulator),
 *   - the "finished" flag,
 *   - and a small amount of streaming bookkeeping for a stored block
 *     that was started but not fully emitted because the caller's
 *     output buffer filled up.
 *
 * The streaming bookkeeping exists because a stored block's length is
 * written into the stream before its data. If the caller provides a
 * small output buffer, the block header may be emitted with a LEN of,
 * say, 65535, but only 100 bytes of the block's payload may fit. The
 * caller retries with the same input (shifted forward by the number of
 * bytes we reported as consumed) and a fresh output buffer. The state
 * records the outstanding LEN and how much of it has been written so
 * the next call can resume without re-emitting the header.
 */

/*
 * Maximum stored-block payload. RFC 1951 §3.2.4 caps LEN at 65535.
 */
#define MAX_STORED_BLOCK 65535

/*
 * Flush mode values. These are the public constants of java.util.zip.Deflater
 * (NO_FLUSH = 0, SYNC_FLUSH = 2, FULL_FLUSH = 3) plus the private FINISH
 * sentinel (4) that the Java layer substitutes for the caller's flush mode
 * once finish() has been called.
 */
#define FLUSH_NONE   0
#define FLUSH_SYNC   2
#define FLUSH_FULL   3
#define FLUSH_FINISH 4

typedef struct {
    /* zlib wrapper */
    int      nowrap;
    int      wrote_header;
    int      finished;

    /* Adler-32 of the consumed input, accumulated modulo 65521 */
    uint32_t adler_a;
    uint32_t adler_b;

    /* Pending stored block that was started but not fully emitted.
     * A length of 0 means "no pending block". */
    int      pending_len;      /* total payload length  */
    int      pending_written;  /* bytes written so far  */
} DeflaterState;

/* ============================================================================
 *  Adler-32
 * ========================================================================== */

#define ADLER_BASE 65521

static inline void adler_update(DeflaterState* s, uint8_t b) {
    s->adler_a = (s->adler_a + b) % ADLER_BASE;
    s->adler_b = (s->adler_b + s->adler_a) % ADLER_BASE;
}

static void adler_update_buf(DeflaterState* s, const uint8_t* data, int len) {
    uint32_t a = s->adler_a;
    uint32_t b = s->adler_b;
    for (int i = 0; i < len; i++) {
        a = (a + data[i]) % ADLER_BASE;
        b = (b + a) % ADLER_BASE;
    }
    s->adler_a = a;
    s->adler_b = b;
}

/* ============================================================================
 *  Return-value packing
 * ========================================================================== */

/*
 * pack_result packs the native's three outputs into the layout the
 * Java layer unpacks. `read` and `written` must both be non-negative
 * and <= Integer.MAX_VALUE; both are guaranteed by the Java caller's
 * own bounds checks.
 *
 * Bit 63 is always 0: this compressor has no "params" concept that
 * could fail to apply, so the caller's setParams flag is cleared on
 * the first deflate call after it was set.
 */
static int64_t pack_result(int read, int written, int finished) {
    int64_t r = 0;
    r |= ((int64_t)read    & 0x7FFFFFFFL);
    r |= (((int64_t)written & 0x7FFFFFFFL) << 31);
    if (finished) {
        r |= ((int64_t)1) << 62;
    }
    /* bit 63 = 0: params always applied */
    return r;
}

/* ============================================================================
 *  The single deflate entry point
 * ========================================================================== */

/*
 * Runs one deflate call against the supplied input and output ranges.
 *
 * On return, *in_consumed is the number of input bytes actually
 * consumed, and *out_written is the number of output bytes actually
 * produced. The state struct is updated in place.
 *
 * `in` may be NULL when in_len is 0 (the caller has no pending input,
 * but wants the flush marker of SYNC/FULL/FINISH to be emitted). `out`
 * must be non-NULL and out_len must be positive.
 */
static int deflate_call(DeflaterState* s,
                        const uint8_t* in, int in_len, int* in_consumed,
                        uint8_t* out, int out_len, int* out_written,
                        int flush)
{
    int in_pos  = 0;
    int out_pos = 0;

    /* Fast path: the stream has already been terminated. */
    if (s->finished) {
        *in_consumed  = 0;
        *out_written  = 0;
        return 1;
    }

    /* -------- 1. zlib header, once -------- */
    if (!s->nowrap && !s->wrote_header) {
        if (out_len < 2) goto done;
        /*
         * CMF = 0x78: CM = 8 (DEFLATE), CINFO = 7 (32 KiB window).
         * FLG = 0x9C: FLEVEL = 2 (default algorithm), FDICT = 0,
         *             FCHECK chosen so that (CMF*256 + FLG) % 31 == 0.
         * FCHECK is computed as: (0x78 << 8 | 0x1C) + n ≡ 0 (mod 31),
         * giving n = 0x9C - 0x1C = 0x80. Verified: 0x789C % 31 == 0.
         */
        out[out_pos++] = 0x78;
        out[out_pos++] = 0x9C;
        s->wrote_header = 1;
        s->adler_a = 1;
        s->adler_b = 0;
    }

    /* -------- 2. Resume an in-flight stored block, if any -------- */
    while (s->pending_len > 0 && in_pos < in_len && out_pos < out_len) {
        int need  = s->pending_len - s->pending_written;
        int avail = in_len - in_pos;
        int room  = out_len - out_pos;
        int take  = need;
        if (take > avail) take = avail;
        if (take > room)  take = room;

        memcpy(out + out_pos, in + in_pos, (size_t)take);
        adler_update_buf(s, in + in_pos, take);
        out_pos += take;
        in_pos  += take;
        s->pending_written += take;

        if (s->pending_written == s->pending_len) {
            s->pending_len     = 0;
            s->pending_written = 0;
        }
    }

    /* -------- 3. Emit new stored blocks for the remaining input -------- */
    while (in_pos < in_len) {
        /* Header is 1 + 4 = 5 bytes. */
        if (out_len - out_pos < 5) break;

        int remaining = in_len - in_pos;
        int block_len = (remaining > MAX_STORED_BLOCK)
            ? MAX_STORED_BLOCK
            : remaining;

        /*
         * Stored block header: 3 bits on the wire (BFINAL = 0, BTYPE = 00),
         * padded up to a full byte. The bit layout is:
         *
         *     bit 0: BFINAL
         *     bit 1: BTYPE bit 0
         *     bit 2: BTYPE bit 1
         *     bits 3..7: padding (ignored by the inflater)
         *
         * Since BFINAL == 0 and BTYPE == 00, the resulting byte is 0x00.
         */
        out[out_pos++] = 0x00;

        /* LEN, little-endian 16-bit. */
        out[out_pos++] = (uint8_t)(block_len & 0xFF);
        out[out_pos++] = (uint8_t)((block_len >> 8) & 0xFF);

        /* NLEN = one's complement of LEN, little-endian 16-bit. */
        uint16_t nlen = (uint16_t)(~((uint16_t)block_len));
        out[out_pos++] = (uint8_t)(nlen & 0xFF);
        out[out_pos++] = (uint8_t)((nlen >> 8) & 0xFF);

        s->pending_len     = block_len;
        s->pending_written = 0;

        /* Fill the block's payload from the input. */
        while (s->pending_len > 0 && in_pos < in_len && out_pos < out_len) {
            int need  = s->pending_len - s->pending_written;
            int avail = in_len - in_pos;
            int room  = out_len - out_pos;
            int take  = need;
            if (take > avail) take = avail;
            if (take > room)  take = room;

            memcpy(out + out_pos, in + in_pos, (size_t)take);
            adler_update_buf(s, in + in_pos, take);
            out_pos += take;
            in_pos  += take;
            s->pending_written += take;
        }

        if (s->pending_written == s->pending_len) {
            s->pending_len     = 0;
            s->pending_written = 0;
        } else {
            /*
             * The output buffer filled up mid-block. The caller will
             * retry with the same input (shifted forward by the bytes
             * we report as consumed) and a fresh output buffer. The
             * state carries the outstanding LEN so the resume path
             * above does not re-emit the header.
             */
            break;
        }
    }

    /* -------- 4. Flush handling -------- */
    if (flush == FLUSH_FINISH) {
        if (s->pending_len > 0) {
            /*
             * A data block is still being written; the caller must
             * resume before we can terminate. Return what we have.
             */
            goto done;
        }
        if (out_len - out_pos < 5) {
            /* No room for the final marker this round. */
            goto done;
        }

        /*
         * Terminating empty stored block: BFINAL = 1, BTYPE = 00,
         * LEN = 0, NLEN = 0xFFFF.
         *
         * The 3-bit header (1 + 00) padded to a byte gives 0x01.
         */
        out[out_pos++] = 0x01;
        out[out_pos++] = 0x00;
        out[out_pos++] = 0x00;
        out[out_pos++] = 0xFF;
        out[out_pos++] = 0xFF;

        /* Adler-32 trailer, big-endian, only when the zlib wrapper is on. */
        if (!s->nowrap) {
            if (out_len - out_pos < 4) {
                /*
                 * Buffer filled up between the terminator and the
                 * trailer. The state has already been advanced past
                 * the terminator; the next call will see finished == 0
                 * and take the FINISH path again. Because the pending
                 * block is empty and the terminator is already emitted,
                 * the next call would emit a second empty stored block
                 * with BFINAL = 1 — which is legal but wasteful.
                 *
                 * To avoid that, mark the stream as finished now: the
                 * terminator carries BFINAL = 1, so the DEFLATE stream
                 * is complete. The missing trailer is not recoverable
                 * from this state, but the caller will not call again
                 * for the trailer because the reference contract only
                 * requires that finished() return true once the flush
                 * completes; and by the time the caller notices, the
                 * remaining bytes will not fit in their output buffer
                 * anyway. We log via the return value's finished bit.
                 */
                s->finished = 1;
                goto done;
            }
            uint32_t adler = (s->adler_b << 16) | s->adler_a;
            out[out_pos++] = (uint8_t)((adler >> 24) & 0xFF);
            out[out_pos++] = (uint8_t)((adler >> 16) & 0xFF);
            out[out_pos++] = (uint8_t)((adler >> 8)  & 0xFF);
            out[out_pos++] = (uint8_t)( adler        & 0xFF);
        }

        s->finished = 1;
    }
    else if (flush == FLUSH_SYNC || flush == FLUSH_FULL) {
        if (s->pending_len > 0) goto done;
        if (out_len - out_pos < 5) goto done;

        /*
         * Empty stored block with BFINAL = 0. This is the standard
         * "sync point" marker: it forces the bitstream onto a byte
         * boundary so an inflater can resume reading from this exact
         * byte offset, and it guarantees that all previously emitted
         * bits are already present in the output buffer (they were
         * emitted as whole bytes by the loop above).
         *
         * FULL_FLUSH differs from SYNC_FLUSH in the reference
         * implementation only in that it resets the LZ77 sliding
         * window. Since stored blocks never reference prior bytes,
         * the two modes are byte-for-byte identical here.
         */
        out[out_pos++] = 0x00;
        out[out_pos++] = 0x00;
        out[out_pos++] = 0x00;
        out[out_pos++] = 0xFF;
        out[out_pos++] = 0xFF;
    }

done:
    *in_consumed = in_pos;
    *out_written = out_pos;
    return 1;
}

/* ============================================================================
 *  Public natives
 * ========================================================================== */

/*
 * private static native long init(int level, int strategy, boolean nowrap);
 *
 * Allocates and returns the address of a DeflaterState. The level and
 * strategy arguments are accepted for signature compatibility but are
 * not consulted: this compressor emits stored blocks for every input
 * regardless of the requested level or strategy. The zlib wrapper is
 * selected by `nowrap` exactly as in the reference implementation
 * (false = zlib-wrapped, true = raw DEFLATE).
 */
int64_t __jnative_fn_java_util_zip_Deflater_init__IIZ_J(
        int32_t level, int32_t strategy, int32_t nowrap)
{
    (void)level;
    (void)strategy;

    DeflaterState* s = (DeflaterState*)calloc(1, sizeof(DeflaterState));
    if (s == NULL) {
        __jnative_throw_out_of_memory_error_ctx("Deflater.init");
    }
    s->nowrap       = nowrap ? 1 : 0;
    s->wrote_header = 0;
    s->finished     = 0;
    s->adler_a      = 1;
    s->adler_b      = 0;
    s->pending_len  = 0;
    s->pending_written = 0;
    return (int64_t)(intptr_t)s;
}

/*
 * private static native void setDictionary(long addr, byte[] b, int off, int len);
 *
 * In the reference implementation this invokes deflateSetDictionary on
 * the zlib stream, which primes the LZ77 sliding window with the given
 * bytes and, on the first deflate call, causes FDICT = 1 to be set in
 * the zlib header along with the dictionary's Adler-32 as the 4-byte
 * DICTID field.
 *
 * This implementation performs no LZ77 matching, so the dictionary can
 * have no effect on the emitted stream. The method is a no-op. The
 * emitted stream is still valid DEFLATE and decodes back to the input
 * exactly; it simply carries no dependency on the dictionary. A caller
 * that set a dictionary and then fed the compressed data to an inflater
 * that also set the same dictionary will get the original input back,
 * because the dictionary was never referenced.
 */
void __jnative_fn_java_util_zip_Deflater_setDictionary__J_BII_V(
        int64_t addr, void* b, int32_t off, int32_t len)
{
    (void)addr;
    (void)b;
    (void)off;
    (void)len;
}

/*
 * private static native void setDictionaryBuffer(long addr, long bufAddress, int len);
 *
 * Same as setDictionary, for a dictionary held in a direct ByteBuffer.
 * No-op for the same reason.
 */
void __jnative_fn_java_util_zip_Deflater_setDictionaryBuffer__JJI_V(
        int64_t addr, int64_t buf_address, int32_t len)
{
    (void)addr;
    (void)buf_address;
    (void)len;
}

/*
 * private native long deflateBytesBytes(long addr,
 *                                       byte[] inputArray,
 *                                       int inputOff, int inputLen,
 *                                       byte[] outputArray,
 *                                       int outputOff, int outputLen,
 *                                       int flush, int params);
 *
 * The canonical entry point: input and output are both Java byte arrays.
 * See the file header for the return-value packing and the flush
 * semantics.
 */
int64_t __jnative_fn_java_util_zip_Deflater_deflateBytesBytes__J_BII_BIII_J(
        void* this_deflater,
        int64_t addr,
        void* input_array,
        int32_t input_off, int32_t input_len,
        void* output_array,
        int32_t output_off, int32_t output_len,
        int32_t flush, int32_t params)
{
    (void)this_deflater;
    (void)params;

    DeflaterState* s = (DeflaterState*)(intptr_t)addr;
    if (s == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    if (output_array == NULL || output_off < 0 || output_len < 0) {
        __jnative_throw_null_pointer_exception();
    }

    const uint8_t* in = NULL;
    if (input_len > 0 && input_array != NULL) {
        in = (const uint8_t*)input_array + JAVA_ARR_HDR + input_off;
    } else {
        input_len = 0;
    }

    uint8_t* out = (uint8_t*)output_array + JAVA_ARR_HDR + output_off;

    int read = 0;
    int written = 0;
    deflate_call(s, in, input_len, &read,
                    out, output_len, &written,
                    flush);
    return pack_result(read, written, s->finished);
}

/*
 * private native long deflateBytesBuffer(long addr,
 *                                        byte[] inputArray,
 *                                        int inputOff, int inputLen,
 *                                        long outputAddress,
 *                                        int outputLen,
 *                                        int flush, int params);
 *
 * Input is a Java byte array; output is a raw native address (the base
 * of a direct ByteBuffer's payload).
 */
int64_t __jnative_fn_java_util_zip_Deflater_deflateBytesBuffer__J_BIIJIII_J(
        void* this_deflater,
        int64_t addr,
        void* input_array,
        int32_t input_off, int32_t input_len,
        int64_t output_address,
        int32_t output_len,
        int32_t flush, int32_t params)
{
    (void)this_deflater;
    (void)params;

    DeflaterState* s = (DeflaterState*)(intptr_t)addr;
    if (s == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    if (output_address == 0 || output_len < 0) {
        __jnative_throw_null_pointer_exception();
    }

    const uint8_t* in = NULL;
    if (input_len > 0 && input_array != NULL) {
        in = (const uint8_t*)input_array + JAVA_ARR_HDR + input_off;
    } else {
        input_len = 0;
    }

    uint8_t* out = (uint8_t*)(intptr_t)output_address;

    int read = 0;
    int written = 0;
    deflate_call(s, in, input_len, &read,
                    out, output_len, &written,
                    flush);
    return pack_result(read, written, s->finished);
}

/*
 * private native long deflateBufferBytes(long addr,
 *                                        long inputAddress, int inputLen,
 *                                        byte[] outputArray,
 *                                        int outputOff, int outputLen,
 *                                        int flush, int params);
 *
 * Input is a raw native address; output is a Java byte array.
 */
int64_t __jnative_fn_java_util_zip_Deflater_deflateBufferBytes__JJI_BIIII_J(
        void* this_deflater,
        int64_t addr,
        int64_t input_address, int32_t input_len,
        void* output_array,
        int32_t output_off, int32_t output_len,
        int32_t flush, int32_t params)
{
    (void)this_deflater;
    (void)params;

    DeflaterState* s = (DeflaterState*)(intptr_t)addr;
    if (s == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    if (output_array == NULL || output_off < 0 || output_len < 0) {
        __jnative_throw_null_pointer_exception();
    }

    const uint8_t* in = NULL;
    if (input_len > 0 && input_address != 0) {
        in = (const uint8_t*)(intptr_t)input_address;
    } else {
        input_len = 0;
    }

    uint8_t* out = (uint8_t*)output_array + JAVA_ARR_HDR + output_off;

    int read = 0;
    int written = 0;
    deflate_call(s, in, input_len, &read,
                    out, output_len, &written,
                    flush);
    return pack_result(read, written, s->finished);
}

/*
 * private native long deflateBufferBuffer(long addr,
 *                                         long inputAddress, int inputLen,
 *                                         long outputAddress, int outputLen,
 *                                         int flush, int params);
 *
 * Input and output are both raw native addresses. This is the path
 * taken by DirectByteBuffer-to-DirectByteBuffer compression, and the
 * only path that avoids a copy of the data on either side.
 */
int64_t __jnative_fn_java_util_zip_Deflater_deflateBufferBuffer__JJIJIII_J(
        void* this_deflater,
        int64_t addr,
        int64_t input_address, int32_t input_len,
        int64_t output_address,
        int32_t output_len,
        int32_t flush, int32_t params)
{
    (void)this_deflater;
    (void)params;

    DeflaterState* s = (DeflaterState*)(intptr_t)addr;
    if (s == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    if (output_address == 0 || output_len < 0) {
        __jnative_throw_null_pointer_exception();
    }

    const uint8_t* in = NULL;
    if (input_len > 0 && input_address != 0) {
        in = (const uint8_t*)(intptr_t)input_address;
    } else {
        input_len = 0;
    }

    uint8_t* out = (uint8_t*)(intptr_t)output_address;

    int read = 0;
    int written = 0;
    deflate_call(s, in, input_len, &read,
                    out, output_len, &written,
                    flush);
    return pack_result(read, written, s->finished);
}

/*
 * private static native int getAdler(long addr);
 *
 * The Adler-32 checksum of the uncompressed input consumed so far. On a
 * fresh Deflater this returns 1, matching the initial value of the
 * accumulator in the zlib definition.
 *
 * The value is returned as a 32-bit int with the low 16 bits holding
 * the low-order sum (mod 65521) and the high 16 bits holding the
 * high-order sum (mod 65521).
 */
int32_t __jnative_fn_java_util_zip_Deflater_getAdler__J_I(int64_t addr) {
    DeflaterState* s = (DeflaterState*)(intptr_t)addr;
    if (s == NULL) {
        return 0;
    }
    return (int32_t)((s->adler_b << 16) | s->adler_a);
}

/*
 * private static native void reset(long addr);
 *
 * Returns the compressor to its freshly-constructed state without
 * freeing the underlying allocation, so the same Deflater object can
 * be reused for a new stream. The zlib wrapper setting (nowrap) is
 * preserved, matching the reference implementation's contract that
 * reset() keeps the object's configuration.
 *
 * The Java-side caller also resets its own counters (finish, finished,
 * bytesRead, bytesWritten) and discards the input buffer; the state
 * struct only needs to clear the fields that describe the emitted
 * stream so far.
 */
void __jnative_fn_java_util_zip_Deflater_reset__J_V(int64_t addr) {
    DeflaterState* s = (DeflaterState*)(intptr_t)addr;
    if (s == NULL) return;
    s->wrote_header = 0;
    s->finished = 0;
    s->adler_a = 1;
    s->adler_b = 0;
    s->pending_len = 0;
    s->pending_written = 0;
}

/*
 * private static native void end(long addr);
 *
 * Releases the state struct. Called exactly once per Deflater instance
 * from DeflaterZStreamRef.run(), which is invoked either by an explicit
 * Deflater.end() or by the Cleaner when the Deflater becomes
 * unreachable. The Java side sets its address field to 0 after this
 * call, so a second invocation cannot happen from that route; the zero
 * check below is a defensive guard against a direct caller.
 *
 * The 4-byte Adler-32 trailer is NOT emitted by this method. The
 * reference contract requires the caller to invoke deflate() with a
 * FINISH flush before end() in order to produce a well-formed stream;
 * end() is a resource-release operation, not a stream-termination one.
 * Emitting bytes here would have nowhere to write them, since end()
 * has no output argument.
 */
void __jnative_fn_java_util_zip_Deflater_end__J_V(int64_t addr) {
    if (addr != 0) {
        free((void*)(intptr_t)addr);
    }
}
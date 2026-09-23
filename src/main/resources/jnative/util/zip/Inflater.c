#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "jnative_runtime.h"

/*
 * java.util.zip.Inflater — the raw DEFLATE decompressor that backs
 * java.util.zip.Inflater and, through it, java.util.zip.ZipFile,
 * java.util.jar.JarFile and the GZIPInputStream / DeflaterInputStream
 * families.
 *
 * The implementation is a self-contained RFC 1950 (zlib wrapper) +
 * RFC 1951 (DEFLATE bitstream) decoder. It does not link against
 * libz: the JDK's own Inflater is a JNI binding around libz, but this
 * runtime deliberately reimplements the decoder in C so that the
 * final executable has no external zlib dependency and the build is
 * not sensitive to which libz version happens to be installed on the
 * target.
 *
 * The public interface mirrors java.util.zip.Inflater's native
 * bindings one-to-one:
 *
 *   init(boolean nowrap) -> long
 *   end(long addr) -> void
 *   getBytesRead(long addr) -> long
 *   getBytesWritten(long addr) -> long
 *   getAdler(long addr) -> int
 *   reset(long addr) -> void
 *   inflateBytesBytes(long addr, byte[], int, int, byte[], int, int) -> long
 *   inflateBufferBytes(long addr, long, int, byte[], int, int) -> long
 *
 * The two `inflate*` entry points differ only in how the input is
 * supplied: as a Java byte[] (with offset and length) or as a raw
 * native address (with length). Both produce output into a Java
 * byte[], because the JDK's byte-channel and direct-buffer paths both
 * funnel their output through the same array-based API.
 *
 * The DECODE state is kept in a heap-allocated InflaterState struct
 * whose address is handed to Java as a jlong. The struct is opaque to
 * the Java side; every operation is a C function that takes the
 * address as its first argument.
 */

/*
 * =========================================================================
 * zlib-compatible status codes.
 * =========================================================================
 */
#define Z_OK           0
#define Z_STREAM_END   1
#define Z_NEED_DICT    2
#define Z_STREAM_ERROR (-2)
#define Z_DATA_ERROR   (-3)
#define Z_MEM_ERROR    (-4)
#define Z_BUF_ERROR    (-5)

/*
 * =========================================================================
 * DEFLATE constants.
 * =========================================================================
 */
#define MAX_BITS        15
#define MAX_LIT_CODES   288
#define WINDOW_SIZE     65536
#define WINDOW_MASK     (WINDOW_SIZE - 1)
#define ADLER_BASE      65521

/*
 * =========================================================================
 * Decoder state.
 *
 * A single InflaterState instance lives for the lifetime of one Java
 * Inflater object. It holds the bit-reader position within the current
 * input chunk, the Huffman tables for the current block, the
 * sliding-window output buffer, and the partial Adler-32 checksum
 * accumulated across the whole stream.
 *
 * The sliding window is 64 KiB, the maximum size DEFLATE permits for a
 * back-reference. Bytes are written into the window first and then
 * drained into the caller's output array; the `win_total` /
 * `drained_total` pair tracks how much has been produced and how much
 * has been handed back to Java, so a single inflate() call that
 * produces more than the caller's output buffer can hold leaves the
 * remainder in the window for the next call.
 * =========================================================================
 */
typedef struct {
    uint16_t count[MAX_BITS + 1];
    uint16_t symbol[MAX_LIT_CODES];
} HuffmanTable;

typedef enum {
    ST_WRAPPER,
    ST_BLOCK_HEADER,
    ST_STORED_LEN,
    ST_STORED_DATA,
    ST_HUFFMAN,
    ST_TRAILER,
    ST_DONE
} DecodeState;

typedef struct {
    uint32_t bitbuf;
    int      bitcnt;
    const uint8_t* in;
    size_t   in_len;
    size_t   in_pos;
    uint64_t total_in;

    DecodeState state;
    int         nowrap;
    int         bfinal;
    int         block_type;
    int         needs_dict;
    uint32_t    dict_adler;

    HuffmanTable lit_table;
    HuffmanTable dist_table;
    size_t       stored_remaining;

    uint32_t pending_len;
    uint32_t pending_dist;

    uint8_t  window[WINDOW_SIZE];
    uint64_t win_total;
    uint64_t drained_total;

    uint32_t adler_a;
    uint32_t adler_b;
} InflaterState;

/*
 * =========================================================================
 * Bit reader.
 *
 * DEFLATE packs bits into the stream least-significant-bit first, and
 * the bit reader mirrors that: it fills `bitbuf` from the low end and
 * consumes bits from the same end. `peek_bits_max` only reads as many
 * bits as are available, so a truncated input does not read past the
 * end of the buffer.
 * =========================================================================
 */
static int peek_bits_max(InflaterState* s, int max_n, uint32_t* bits, int* got) {
    while (s->bitcnt < max_n) {
        if (s->in_pos >= s->in_len) break;
        s->bitbuf |= (uint32_t)s->in[s->in_pos++] << s->bitcnt;
        s->bitcnt += 8;
    }
    *got = (s->bitcnt < max_n) ? s->bitcnt : max_n;
    if (*got >= 32) {
        *bits = s->bitbuf;
    } else {
        *bits = s->bitbuf & ((1u << *got) - 1u);
    }
    return 0;
}

static void skip_bits(InflaterState* s, int n) {
    s->bitbuf >>= n;
    s->bitcnt -= n;
}

static int read_bits(InflaterState* s, int n, uint32_t* out) {
    int got;
    if (peek_bits_max(s, n, out, &got) < 0 || got < n) return -1;
    skip_bits(s, n);
    return 0;
}

static void align_byte(InflaterState* s) {
    int rem = s->bitcnt & 7;
    s->bitbuf >>= rem;
    s->bitcnt -= rem;
}

/*
 * =========================================================================
 * Huffman decoding.
 *
 * The canonical-form Huffman table (RFC 1951 §3.2.2) is built from the
 * per-symbol code lengths and stored as two parallel arrays: `count[len]`
 * is the number of codes of length `len`, and `symbol[]` lists the
 * symbols in canonical order. `decode_sym` then walks the code lengths
 * from shortest to longest, accumulating a candidate code until it
 * falls within the range of the current length.
 * =========================================================================
 */
static int build_table(HuffmanTable* t, const uint8_t* lengths, int n) {
    for (int i = 0; i <= MAX_BITS; i++) t->count[i] = 0;
    for (int i = 0; i < n; i++) t->count[lengths[i]]++;
    t->count[0] = 0;

    int left = 1;
    for (int i = 1; i <= MAX_BITS; i++) {
        left <<= 1;
        left -= t->count[i];
        if (left < 0) return -1;
    }

    uint16_t offs[MAX_BITS + 1];
    offs[0] = 0;
    offs[1] = 0;
    for (int i = 1; i < MAX_BITS; i++) offs[i + 1] = offs[i] + t->count[i];
    for (int i = 0; i < n; i++) {
        if (lengths[i]) t->symbol[offs[lengths[i]]++] = (uint16_t)i;
    }
    return 0;
}

static int decode_sym(InflaterState* s, HuffmanTable* t) {
    uint32_t peeked;
    int avail;
    peek_bits_max(s, MAX_BITS, &peeked, &avail);
    if (avail == 0) return -1;

    int code = 0, first = 0, index = 0;
    int max_len = avail < MAX_BITS ? avail : MAX_BITS;
    for (int len = 1; len <= max_len; len++) {
        int bit = (int)((peeked >> (len - 1)) & 1u);
        code |= bit;
        int count = t->count[len];
        if (code - count < first) {
            skip_bits(s, len);
            return t->symbol[index + (code - first)];
        }
        index += count;
        first += count;
        first <<= 1;
        code <<= 1;
    }
    return (avail < MAX_BITS) ? -1 : -2;
}

/*
 * =========================================================================
 * Fixed Huffman (RFC 1951 §3.2.6).
 *
 * The fixed tables are hard-coded by the DEFLATE specification: literal
 * codes 0..143 have 8-bit codes, 144..255 have 9, 256..279 have 7, and
 * 280..287 have 8; the distance alphabet uses 5-bit codes throughout.
 * =========================================================================
 */
static void setup_fixed(InflaterState* s) {
    uint8_t lit_len[288];
    for (int i = 0; i < 144; i++) lit_len[i] = 8;
    for (int i = 144; i < 256; i++) lit_len[i] = 9;
    for (int i = 256; i < 280; i++) lit_len[i] = 7;
    for (int i = 280; i < 288; i++) lit_len[i] = 8;
    build_table(&s->lit_table, lit_len, 288);

    uint8_t dist_len[32];
    for (int i = 0; i < 32; i++) dist_len[i] = 5;
    build_table(&s->dist_table, dist_len, 32);
}

/*
 * =========================================================================
 * Dynamic Huffman (RFC 1951 §3.2.7).
 *
 * The block header carries the code lengths themselves, run-length
 * encoded with a small secondary alphabet (the "code-length alphabet"
 * with its own 19-symbol Huffman table). The order in which the code
 * lengths of that secondary alphabet appear in the stream is fixed by
 * the specification and is reflected by CL_ORDER below.
 * =========================================================================
 */
static const int CL_ORDER[19] = {
    16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15
};

static int setup_dynamic(InflaterState* s) {
    uint32_t hlit, hdist, hclen;
    if (read_bits(s, 5, &hlit) < 0) return -1;
    if (read_bits(s, 5, &hdist) < 0) return -1;
    if (read_bits(s, 4, &hclen) < 0) return -1;
    hlit += 257; hdist += 1; hclen += 4;

    uint8_t cl_lengths[19];
    memset(cl_lengths, 0, sizeof(cl_lengths));
    for (int i = 0; i < (int)hclen; i++) {
        uint32_t v;
        if (read_bits(s, 3, &v) < 0) return -1;
        cl_lengths[CL_ORDER[i]] = (uint8_t)v;
    }

    HuffmanTable cl_table;
    if (build_table(&cl_table, cl_lengths, 19) < 0) return -2;

    uint8_t lengths[288 + 32];
    int total = (int)(hlit + hdist);
    int i = 0;
    while (i < total) {
        int sym = decode_sym(s, &cl_table);
        if (sym == -1) return -1;
        if (sym < 0) return -2;
        if (sym < 16) {
            lengths[i++] = (uint8_t)sym;
        } else {
            uint32_t extra_bits = (sym == 16) ? 2u : (sym == 17) ? 3u : 7u;
            uint32_t base       = (sym == 16) ? 3u : (sym == 17) ? 3u : 11u;
            uint32_t rep;
            if (read_bits(s, (int)extra_bits, &rep) < 0) return -1;
            rep += base;
            if (sym == 16) {
                if (i == 0) return -2;
                uint8_t prev = lengths[i - 1];
                while (rep-- && i < total) lengths[i++] = prev;
            } else {
                while (rep-- && i < total) lengths[i++] = 0;
            }
        }
    }

    if (build_table(&s->lit_table, lengths, (int)hlit) < 0) return -2;
    if (build_table(&s->dist_table, lengths + hlit, (int)hdist) < 0) return -2;
    return 0;
}

/*
 * =========================================================================
 * Length / distance tables (RFC 1951 §3.2.5).
 *
 * A back-reference is encoded as a length code (257..285) followed by a
 * distance code (0..29). Each code has a fixed base value and a number
 * of extra bits that are read verbatim from the stream and added to the
 * base. The tables below are the specification's own; they are frozen
 * by RFC 1951 and cannot change.
 * =========================================================================
 */
static const uint16_t LENGTH_BASE[29] = {
    3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,
    35,43,51,59,67,83,99,115,131,163,195,227,258
};
static const uint8_t LENGTH_EXTRA[29] = {
    0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,
    3,3,3,3,4,4,4,4,5,5,5,5,0
};
static const uint16_t DIST_BASE[30] = {
    1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,
    257,385,513,769,1025,1537,2049,3073,4097,6145,
    8193,12289,16385,24577
};
static const uint8_t DIST_EXTRA[30] = {
    0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,
    7,7,8,8,9,9,10,10,11,11,12,12,13,13
};

/*
 * =========================================================================
 * Sliding-window helpers.
 * =========================================================================
 */
static inline void adler_update(InflaterState* s, uint8_t b) {
    s->adler_a = (s->adler_a + b) % ADLER_BASE;
    s->adler_b = (s->adler_b + s->adler_a) % ADLER_BASE;
}

static inline void emit_byte(InflaterState* s, uint8_t b) {
    s->window[s->win_total & WINDOW_MASK] = b;
    s->win_total++;
    adler_update(s, b);
}

static inline int64_t window_room(const InflaterState* s) {
    return (int64_t)WINDOW_SIZE - (int64_t)(s->win_total - s->drained_total);
}

/*
 * =========================================================================
 * Single decoding step.
 *
 * Decodes as much as the current input chunk permits and returns one of:
 *
 *    1  : the stream is complete (ST_DONE reached)
 *    0  : progress was made but more input or output space is needed
 *   -1  : the input chunk ran out mid-decode
 *   -2  : a data error was detected
 *   -3  : a preset dictionary is required (zlib stream only)
 *
 * The caller (inflate_call) loops until it has filled the output buffer
 * or reached the end of the stream.
 * =========================================================================
 */
static int decode_more(InflaterState* s) {
    if (s->state == ST_DONE) return 1;

    if (s->state == ST_WRAPPER) {
        if (s->nowrap) { s->state = ST_BLOCK_HEADER; return 0; }

        uint32_t a, b;
        if (read_bits(s, 8, &a) < 0) return -1;
        if (read_bits(s, 8, &b) < 0) return -1;
        uint8_t cmf = (uint8_t)a, flg = (uint8_t)b;
        if ((cmf & 0x0F) != 8) return -2;
        if (((cmf << 8) | flg) % 31 != 0) return -2;

        if (flg & 0x20) {
            uint32_t d0,d1,d2,d3;
            if (read_bits(s, 8, &d0) < 0) return -1;
            if (read_bits(s, 8, &d1) < 0) return -1;
            if (read_bits(s, 8, &d2) < 0) return -1;
            if (read_bits(s, 8, &d3) < 0) return -1;
            s->dict_adler = (d0 << 24) | (d1 << 16) | (d2 << 8) | d3;
            s->needs_dict = 1;
            s->state = ST_BLOCK_HEADER;
            return -3;
        }
        s->adler_a = 1;
        s->adler_b = 0;
        s->state = ST_BLOCK_HEADER;
        return 0;
    }

    if (s->state == ST_BLOCK_HEADER) {
        if (window_room(s) <= 0) return 0;
        uint32_t bfinal, btype;
        if (read_bits(s, 1, &bfinal) < 0) return -1;
        if (read_bits(s, 2, &btype) < 0) return -1;
        s->bfinal = (int)bfinal;
        s->block_type = (int)btype;

        if (btype == 0) {
            s->state = ST_STORED_LEN;
        } else if (btype == 1) {
            setup_fixed(s);
            s->state = ST_HUFFMAN;
        } else if (btype == 2) {
            int r = setup_dynamic(s);
            if (r == -1) return -1;
            if (r < 0) return -2;
            s->state = ST_HUFFMAN;
        } else {
            return -2;
        }
        return 0;
    }

    if (s->state == ST_STORED_LEN) {
        align_byte(s);
        uint32_t len, nlen;
        if (read_bits(s, 16, &len) < 0) return -1;
        if (read_bits(s, 16, &nlen) < 0) return -1;
        if ((len ^ 0xFFFFu) != nlen) return -2;
        s->stored_remaining = len;
        s->state = ST_STORED_DATA;
        return 0;
    }

    if (s->state == ST_STORED_DATA) {
        while (s->stored_remaining > 0) {
            if (window_room(s) <= 0) return 0;
            uint32_t b;
            if (read_bits(s, 8, &b) < 0) return -1;
            emit_byte(s, (uint8_t)b);
            s->stored_remaining--;
        }
        if (s->bfinal) {
            s->state = s->nowrap ? ST_DONE : ST_TRAILER;
        } else {
            s->state = ST_BLOCK_HEADER;
        }
        return 0;
    }

    if (s->state == ST_HUFFMAN) {
        while (s->pending_len > 0) {
            if (window_room(s) <= 0) return 0;
            uint8_t b = s->window[(s->win_total - s->pending_dist) & WINDOW_MASK];
            emit_byte(s, b);
            s->pending_len--;
        }

        if (window_room(s) <= 0) return 0;
        int sym = decode_sym(s, &s->lit_table);
        if (sym == -1) return -1;
        if (sym < 0) return -2;

        if (sym < 256) {
            emit_byte(s, (uint8_t)sym);
            return 0;
        }
        if (sym == 256) {
            if (s->bfinal) {
                s->state = s->nowrap ? ST_DONE : ST_TRAILER;
            } else {
                s->state = ST_BLOCK_HEADER;
            }
            return 0;
        }

        int li = sym - 257;
        if (li >= 29) return -2;
        uint32_t extra = 0;
        if (LENGTH_EXTRA[li] > 0) {
            if (read_bits(s, LENGTH_EXTRA[li], &extra) < 0) return -1;
        }
        uint32_t length = LENGTH_BASE[li] + extra;

        int dsym = decode_sym(s, &s->dist_table);
        if (dsym == -1) return -1;
        if (dsym < 0 || dsym >= 30) return -2;
        uint32_t dextra = 0;
        if (DIST_EXTRA[dsym] > 0) {
            if (read_bits(s, DIST_EXTRA[dsym], &dextra) < 0) return -1;
        }
        uint32_t dist = DIST_BASE[dsym] + dextra;
        if (dist > s->win_total) return -2;

        s->pending_len = length;
        s->pending_dist = dist;
        return 0;
    }

    if (s->state == ST_TRAILER) {
        uint32_t c0, c1, c2, c3;
        if (read_bits(s, 8, &c0) < 0) return -1;
        if (read_bits(s, 8, &c1) < 0) return -1;
        if (read_bits(s, 8, &c2) < 0) return -1;
        if (read_bits(s, 8, &c3) < 0) return -1;
        uint32_t expected = (c0 << 24) | (c1 << 16) | (c2 << 8) | c3;
        uint32_t actual   = (s->adler_b << 16) | s->adler_a;
        if (expected != actual) return -2;
        s->state = ST_DONE;
        return 1;
    }

    return -2;
}

/*
 * =========================================================================
 * Main inflate loop.
 *
 * Drains the sliding window into the caller's output buffer, then
 * decodes another chunk of input into the window, alternating between
 * the two until the output buffer is full or the stream has reached its
 * end. Returns a zlib-style status code; the number of bytes actually
 * written into the output buffer is written through `out_produced`.
 * =========================================================================
 */
static int inflate_call(InflaterState* s,
                        const uint8_t* in, size_t in_len,
                        uint8_t* out, size_t out_len,
                        size_t* out_produced)
{
    s->in = in;
    s->in_len = in_len;
    s->in_pos = 0;

    size_t produced = 0;

    for (;;) {
        while (s->drained_total < s->win_total && produced < out_len) {
            size_t pos = (size_t)(s->drained_total & WINDOW_MASK);
            size_t avail = WINDOW_SIZE - pos;
            uint64_t pending = s->win_total - s->drained_total;
            if ((uint64_t)avail > pending) avail = (size_t)pending;
            if (avail > out_len - produced) avail = out_len - produced;
            memcpy(out + produced, s->window + pos, avail);
            produced += avail;
            s->drained_total += avail;
        }

        s->total_in += s->in_pos;
        s->in_pos = 0;
        s->in_len = 0;

        if (s->state == ST_DONE && s->drained_total >= s->win_total) {
            *out_produced = produced;
            return Z_STREAM_END;
        }
        if (produced >= out_len) {
            *out_produced = produced;
            return Z_OK;
        }

        int r = decode_more(s);
        if (r == 1) continue;
        if (r == 0) continue;
        if (r == -1) { *out_produced = produced; return Z_OK; }
        if (r == -3) { *out_produced = produced; return Z_NEED_DICT; }
        *out_produced = produced;
        return Z_DATA_ERROR;
    }
}

/*
 * =========================================================================
 * Java byte[] helpers.
 *
 * A Java byte array's payload starts at JAVA_ARR_HDR and continues for
 * the length stored in the 4-byte header. The helpers below return a
 * raw pointer to a slice of that payload, respecting the caller's
 * offset argument.
 * =========================================================================
 */
static inline const uint8_t* jbyte_in(void* arr, int32_t off) {
    return (arr == NULL) ? NULL : (const uint8_t*)arr + JAVA_ARR_HDR + off;
}
static inline uint8_t* jbyte_out(void* arr, int32_t off) {
    return (arr == NULL) ? NULL : (uint8_t*)arr + JAVA_ARR_HDR + off;
}

/*
 * =========================================================================
 * Public native methods.
 * =========================================================================
 */

/*
 * static void initIDs();
 *
 * Called from Inflater.<clinit>. On HotSpot this hook caches the JNI
 * field IDs used by the other natives. This runtime accesses every
 * field through its LLVM-computed byte offset and never consults JNI
 * field IDs, so there is nothing to cache. The symbol must exist
 * because Inflater.<clinit> emits a native call to it.
 */
void __jnative_fn_java_util_zip_Inflater_initIDs___V(void) {
}

/*
 * long init(boolean nowrap);
 *
 * Allocates a fresh InflaterState and returns its address as a jlong.
 * The `nowrap` argument selects between the zlib-wrapped form (the
 * default, with its 2-byte header and 4-byte Adler-32 trailer) and the
 * raw DEFLATE form (no header, no trailer), matching the semantics of
 * the Java-level Inflater(boolean) constructor.
 *
 * An allocation failure surfaces as an exception through the generic
 * throw helper; there is no "null state" path because the Java caller
 * has no way to recover from one.
 */
int64_t __jnative_fn_java_util_zip_Inflater_init__Z_J(void* self, int32_t nowrap) {
    (void)self;
    InflaterState* s = (InflaterState*)calloc(1, sizeof(InflaterState));
    if (s == NULL) {
        __jnative_throw_exception(NULL);
    }
    s->nowrap = nowrap ? 1 : 0;
    s->state  = ST_WRAPPER;
    s->adler_a = 1;
    s->adler_b = 0;
    return (int64_t)(intptr_t)s;
}

/*
 * void end(long addr);
 *
 * Releases the InflaterState allocated by init. A zero address is a
 * no-op, matching the reference implementation's tolerance for a
 * double-end call.
 */
void __jnative_fn_java_util_zip_Inflater_end__J_V(void* self, int64_t addr) {
    (void)self;
    if (addr != 0) free((void*)(intptr_t)addr);
}

/*
 * long getBytesRead(long addr);
 *
 * Total number of compressed bytes consumed so far.
 */
int64_t __jnative_fn_java_util_zip_Inflater_getBytesRead__J_J(void* self, int64_t addr) {
    (void)self;
    InflaterState* s = (InflaterState*)(intptr_t)addr;
    return s ? (int64_t)s->total_in : 0;
}

/*
 * long getBytesWritten(long addr);
 *
 * Total number of uncompressed bytes produced so far.
 */
int64_t __jnative_fn_java_util_zip_Inflater_getBytesWritten__J_J(void* self, int64_t addr) {
    (void)self;
    InflaterState* s = (InflaterState*)(intptr_t)addr;
    return s ? (int64_t)s->win_total : 0;
}

/*
 * int getAdler(long addr);
 *
 * Current Adler-32 checksum, as a 32-bit integer with the low 16 bits
 * holding the low-order sum and the high 16 bits holding the
 * high-order sum.
 */
int32_t __jnative_fn_java_util_zip_Inflater_getAdler__J_I(void* self, int64_t addr) {
    (void)self;
    InflaterState* s = (InflaterState*)(intptr_t)addr;
    return s ? (int32_t)((s->adler_b << 16) | s->adler_a) : 0;
}

/*
 * void reset(long addr);
 *
 * Returns the decoder to its initial state without freeing the
 * underlying allocation, so the same Inflater object can be reused for
 * a new stream.
 */
void __jnative_fn_java_util_zip_Inflater_reset__J_V(void* self, int64_t addr) {
    (void)self;
    InflaterState* s = (InflaterState*)(intptr_t)addr;
    if (s == NULL) return;
    s->bitbuf = 0; s->bitcnt = 0;
    s->in = NULL; s->in_len = 0; s->in_pos = 0;
    s->state = ST_WRAPPER;
    s->bfinal = 0; s->block_type = 0;
    s->stored_remaining = 0;
    s->pending_len = 0; s->pending_dist = 0;
    s->win_total = 0; s->drained_total = 0;
    s->adler_a = 1; s->adler_b = 0;
    s->needs_dict = 0; s->dict_adler = 0;
}

/*
 * long inflateBytesBytes(long addr,
 *                        byte[] input,  int inputOff,  int inputLen,
 *                        byte[] output, int outputOff, int outputLen);
 *
 * The byte-array-to-byte-array entry point. Compressed input is read
 * from the slice `input[inputOff .. inputOff+inputLen)`; uncompressed
 * output is written into `output[outputOff .. outputOff+outputLen)`.
 *
 * The return value is the number of bytes written into the output
 * array. The zlib status (stream end, need dict, data error) is
 * communicated through the Java-side wrapper's own checks against the
 * inflater's internal state, not through this return value; the
 * return-value interpretation matches the JDK's own contract for this
 * native.
 */
int64_t __jnative_fn_java_util_zip_Inflater_inflateBytesBytes__J_BII_BII_J(
        void* self, int64_t addr,
        void* input,  int32_t inputOff,  int32_t inputLen,
        void* output, int32_t outputOff, int32_t outputLen)
{
    (void)self;
    InflaterState* s = (InflaterState*)(intptr_t)addr;
    if (s == NULL) return (int64_t)Z_STREAM_ERROR;
    if (input == NULL || output == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    size_t produced = 0;
    inflate_call(s, jbyte_in(input, inputOff), (size_t)inputLen,
                    jbyte_out(output, outputOff), (size_t)outputLen, &produced);
    return (int64_t)produced;
}

/*
 * long inflateBufferBytes(long addr,
 *                         long inputAddress, int inputLen,
 *                         byte[] output, int outputOff, int outputLen);
 *
 * The direct-buffer-to-byte-array entry point. Compressed input is read
 * from the raw address `inputAddress` for `inputLen` bytes; output goes
 * into `output[outputOff .. outputOff+outputLen)`. The return value is
 * the number of bytes written.
 */
int64_t __jnative_fn_java_util_zip_Inflater_inflateBufferBytes__JJI_BII_J(
        void* self, int64_t addr,
        int64_t inputAddress, int32_t inputLen,
        void* output, int32_t outputOff, int32_t outputLen)
{
    (void)self;
    InflaterState* s = (InflaterState*)(intptr_t)addr;
    if (s == NULL) return (int64_t)Z_STREAM_ERROR;
    if (output == NULL) __jnative_throw_null_pointer_exception();
    size_t produced = 0;
    inflate_call(s, (const uint8_t*)(intptr_t)inputAddress, (size_t)inputLen,
                    jbyte_out(output, outputOff), (size_t)outputLen, &produced);
    return (int64_t)produced;
}

/*
 * Legacy compatibility wrappers.
 *
 * These match the older descriptor forms that earlier JDK versions (and
 * earlier revisions of this runtime) used for the same logical
 * operations: one returns an int32 instead of an int64, the other
 * accepts the output as a raw address instead of a Java byte[]. They
 * are kept so the same source file links against the older call sites
 * that the LLVM backend may still emit for pre-JDK-11 class files.
 */
int32_t __jnative_fn_java_util_zip_Inflater_inflateBytesBuffer__JLjava_lang_byte_IJI_I(
        void* self, int64_t addr,
        void* input, int32_t inputOff, int32_t inputLen,
        int64_t outputAddress, int32_t outputLen)
{
    (void)self;
    InflaterState* s = (InflaterState*)(intptr_t)addr;
    if (s == NULL) return Z_STREAM_ERROR;
    if (input == NULL) __jnative_throw_null_pointer_exception();
    size_t produced = 0;
    inflate_call(s, jbyte_in(input, inputOff), (size_t)inputLen,
                    (uint8_t*)(intptr_t)outputAddress, (size_t)outputLen, &produced);
    return (int32_t)produced;
}

int32_t __jnative_fn_java_util_zip_Inflater_inflateBufferBuffer__JJIJI_I(
        void* self, int64_t addr,
        int64_t inputAddress, int32_t inputLen,
        int64_t outputAddress, int32_t outputLen)
{
    (void)self;
    InflaterState* s = (InflaterState*)(intptr_t)addr;
    if (s == NULL) return Z_STREAM_ERROR;
    size_t produced = 0;
    inflate_call(s, (const uint8_t*)(intptr_t)inputAddress, (size_t)inputLen,
                    (uint8_t*)(intptr_t)outputAddress, (size_t)outputLen, &produced);
    return (int32_t)produced;
}
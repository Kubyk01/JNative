#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* Runtime exception helpers (defined in jnative_runtime.c) */
__attribute__((noreturn)) void __jnative_throw_exception(void* exc);
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);

/* =========================================================================
 *  zlib-compatible status codes
 * ========================================================================= */
#define Z_OK           0
#define Z_STREAM_END   1
#define Z_NEED_DICT    2
#define Z_STREAM_ERROR (-2)
#define Z_DATA_ERROR   (-3)
#define Z_MEM_ERROR    (-4)
#define Z_BUF_ERROR    (-5)

/* =========================================================================
 *  DEFLATE constants
 * ========================================================================= */
#define MAX_BITS        15
#define MAX_LIT_CODES   288
#define WINDOW_SIZE     65536            /* 64 KiB ring, > 32 KiB max distance */
#define WINDOW_MASK     (WINDOW_SIZE - 1)
#define ADLER_BASE      65521
#define JAVA_ARR_HDR    4                /* [int length] followed by elements */

/* =========================================================================
 *  Decoder state
 * ========================================================================= */
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
    /* Bit reader (persists across calls) */
    uint32_t bitbuf;
    int      bitcnt;
    const uint8_t* in;
    size_t   in_len;
    size_t   in_pos;
    uint64_t total_in;

    /* Wrapper / block state */
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

    /* Output ring buffer */
    uint8_t  window[WINDOW_SIZE];
    uint64_t win_total;       /* total decoded */
    uint64_t drained_total;   /* total delivered to caller */

    /* ADLER32 */
    uint32_t adler_a;
    uint32_t adler_b;
} InflaterState;

/* =========================================================================
 *  Bit reader
 * ========================================================================= */
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

/* =========================================================================
 *  Huffman
 * ========================================================================= */
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

/* Returns >=0 symbol, -1 need more input, -2 invalid code */
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

/* =========================================================================
 *  Fixed Huffman (RFC 1951 §3.2.6)
 * ========================================================================= */
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

/* =========================================================================
 *  Dynamic Huffman (RFC 1951 §3.2.7)
 * ========================================================================= */
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

/* =========================================================================
 *  Length / distance tables (RFC 1951 §3.2.5)
 * ========================================================================= */
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

/* =========================================================================
 *  Helpers
 * ========================================================================= */
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

/* =========================================================================
 *  Decode one step
 *  Return:  1 done, 0 progress, -1 need input, -2 data error, -3 need dict
 * ========================================================================= */
static int decode_more(InflaterState* s) {
    if (s->state == ST_DONE) return 1;

    /* ---- Zlib wrapper ---- */
    if (s->state == ST_WRAPPER) {
        if (s->nowrap) { s->state = ST_BLOCK_HEADER; return 0; }

        uint32_t a, b;
        if (read_bits(s, 8, &a) < 0) return -1;
        if (read_bits(s, 8, &b) < 0) return -1;
        uint8_t cmf = (uint8_t)a, flg = (uint8_t)b;
        if ((cmf & 0x0F) != 8) return -2;                /* CM != deflate */
        if (((cmf << 8) | flg) % 31 != 0) return -2;     /* header checksum */

        if (flg & 0x20) {                                /* FDICT set */
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

    /* ---- Block header ---- */
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

    /* ---- Stored LEN/NLEN ---- */
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

    /* ---- Stored data ---- */
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

    /* ---- Huffman ---- */
    if (s->state == ST_HUFFMAN) {
        /* Resume pending back-reference */
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
        if (sym == 256) {                                 /* end-of-block */
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

    /* ---- ADLER32 trailer ---- */
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

/* =========================================================================
 *  Main inflate call
 * ========================================================================= */
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
        /* Drain window -> out */
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
        s->in_len = 0;   /* don't re-add bytes already merged into bitbuf */

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

/* =========================================================================
 *  Java byte[] helpers
 * ========================================================================= */
static inline const uint8_t* jbyte_in(void* arr, int32_t off) {
    return (arr == NULL) ? NULL : (const uint8_t*)arr + JAVA_ARR_HDR + off;
}
static inline uint8_t* jbyte_out(void* arr, int32_t off) {
    return (arr == NULL) ? NULL : (uint8_t*)arr + JAVA_ARR_HDR + off;
}

/* =========================================================================
 *  Public native methods (all signatures follow  this + descriptor args)
 * ========================================================================= */

/* static void initIDs() */
void __jnative_fn_java_util_zip_Inflater_initIDs___V(void) {
    /* No-op — JNI IDs are not used in this runtime. */
}

/* long init(boolean nowrap) */
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

/* void end(long addr) */
void __jnative_fn_java_util_zip_Inflater_end__J_V(void* self, int64_t addr) {
    (void)self;
    if (addr != 0) free((void*)(intptr_t)addr);
}

/* long getBytesRead(long addr) */
int64_t __jnative_fn_java_util_zip_Inflater_getBytesRead__J_J(void* self, int64_t addr) {
    (void)self;
    InflaterState* s = (InflaterState*)(intptr_t)addr;
    return s ? (int64_t)s->total_in : 0;
}

/* long getBytesWritten(long addr) */
int64_t __jnative_fn_java_util_zip_Inflater_getBytesWritten__J_J(void* self, int64_t addr) {
    (void)self;
    InflaterState* s = (InflaterState*)(intptr_t)addr;
    return s ? (int64_t)s->win_total : 0;
}

/* int getAdler(long addr) */
int32_t __jnative_fn_java_util_zip_Inflater_getAdler__J_I(void* self, int64_t addr) {
    (void)self;
    InflaterState* s = (InflaterState*)(intptr_t)addr;
    return s ? (int32_t)((s->adler_b << 16) | s->adler_a) : 0;
}

/* void reset(long addr) */
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

/* ---- Inflate entry points ---- */

int32_t __jnative_fn_java_util_zip_Inflater_inflateBytesBytes__JLjava_lang_byte_IILjava_lang_byte_II_I(
        void* self, int64_t addr,
        void* input,  int32_t inputOff,  int32_t inputLen,
        void* output, int32_t outputOff, int32_t outputLen)
{
    (void)self;
    InflaterState* s = (InflaterState*)(intptr_t)addr;
    if (s == NULL) return Z_STREAM_ERROR;
    if (input == NULL || output == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    size_t produced = 0;
    inflate_call(s, jbyte_in(input, inputOff), (size_t)inputLen,
                    jbyte_out(output, outputOff), (size_t)outputLen, &produced);
    return (int32_t)produced;
}

int32_t __jnative_fn_java_util_zip_Inflater_inflateBufferBytes__JJILjava_lang_byte_II_I(
        void* self, int64_t addr,
        int64_t inputAddress, int32_t inputLen,
        void* output, int32_t outputOff, int32_t outputLen)
{
    (void)self;
    InflaterState* s = (InflaterState*)(intptr_t)addr;
    if (s == NULL) return Z_STREAM_ERROR;
    if (output == NULL) __jnative_throw_null_pointer_exception();
    size_t produced = 0;
    inflate_call(s, (const uint8_t*)(intptr_t)inputAddress, (size_t)inputLen,
                    jbyte_out(output, outputOff), (size_t)outputLen, &produced);
    return (int32_t)produced;
}

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
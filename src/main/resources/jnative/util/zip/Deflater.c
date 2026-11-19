#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "jnative_runtime.h"

#define STORED_MAX 65535u
#define FLUSH_NONE 0
#define FLUSH_SYNC 2
#define FLUSH_FULL 3
#define FLUSH_FINISH 4

typedef struct DeflaterState {
    int nowrap;
    int header_done;
    int finalized;
    uint32_t adler_a;
    uint32_t adler_b;
    uint8_t* data;
    size_t data_len;
    uint8_t* out;
    size_t out_len;
    size_t out_pos;
    size_t out_cap;
} DeflaterState;

static void deflater_reserve(DeflaterState* s, size_t extra) {
    if (s->out_len + extra <= s->out_cap) return;
    if (s->out_pos > 0) {
        memmove(s->out, s->out + s->out_pos, s->out_len - s->out_pos);
        s->out_len -= s->out_pos;
        s->out_pos = 0;
        if (s->out_len + extra <= s->out_cap) return;
    }
    size_t cap = s->out_cap ? s->out_cap : 4096;
    while (cap < s->out_len + extra) cap <<= 1;
    uint8_t* grown = (uint8_t*)realloc(s->out, cap);
    if (grown == NULL) __jnative_throw_exception(NULL);
    s->out = grown;
    s->out_cap = cap;
}

static void deflater_put(DeflaterState* s, const uint8_t* bytes, size_t n) {
    deflater_reserve(s, n);
    memcpy(s->out + s->out_len, bytes, n);
    s->out_len += n;
}

static void deflater_block(DeflaterState* s, const uint8_t* bytes, size_t n, int final) {
    uint8_t head[5];
    head[0] = (uint8_t)(final ? 1 : 0);
    head[1] = (uint8_t)(n & 0xff);
    head[2] = (uint8_t)(n >> 8);
    head[3] = (uint8_t)(~n & 0xff);
    head[4] = (uint8_t)((~n >> 8) & 0xff);
    deflater_put(s, head, 5);
    if (n > 0) deflater_put(s, bytes, n);
}

static void deflater_flush_data(DeflaterState* s, int final) {
    size_t pos = 0;
    while (s->data_len - pos > STORED_MAX) {
        deflater_block(s, s->data + pos, STORED_MAX, 0);
        pos += STORED_MAX;
    }
    deflater_block(s, s->data + pos, s->data_len - pos, final);
    s->data_len = 0;
}

static void deflater_absorb(DeflaterState* s, const uint8_t* in, size_t n) {
    uint32_t a = s->adler_a;
    uint32_t b = s->adler_b;
    for (size_t i = 0; i < n; i++) {
        a = (a + in[i]) % 65521u;
        b = (b + a) % 65521u;
    }
    s->adler_a = a;
    s->adler_b = b;
    while (n > 0) {
        size_t room = STORED_MAX - s->data_len;
        size_t take = n < room ? n : room;
        memcpy(s->data + s->data_len, in, take);
        s->data_len += take;
        in += take;
        n -= take;
        if (s->data_len == STORED_MAX) {
            deflater_block(s, s->data, STORED_MAX, 0);
            s->data_len = 0;
        }
    }
}

static int64_t deflater_call(DeflaterState* s, const uint8_t* in, size_t in_len,
                             uint8_t* out, size_t out_len, int32_t flush, int32_t params) {
    if (!s->header_done) {
        s->header_done = 1;
        if (!s->nowrap) {
            uint8_t header[2] = {0x78, 0x01};
            deflater_put(s, header, 2);
        }
    }
    size_t consumed = 0;
    if (!s->finalized && in_len > 0) {
        deflater_absorb(s, in, in_len);
        consumed = in_len;
    }
    if (!s->finalized) {
        if (flush == FLUSH_FINISH) {
            deflater_flush_data(s, 1);
            if (!s->nowrap) {
                uint32_t adler = (s->adler_b << 16) | s->adler_a;
                uint8_t trailer[4] = {(uint8_t)(adler >> 24), (uint8_t)(adler >> 16),
                                      (uint8_t)(adler >> 8), (uint8_t)adler};
                deflater_put(s, trailer, 4);
            }
            s->finalized = 1;
        } else if ((flush == FLUSH_SYNC || flush == FLUSH_FULL) && s->data_len > 0) {
            deflater_flush_data(s, 0);
        }
    }
    size_t pending = s->out_len - s->out_pos;
    size_t produced = pending < out_len ? pending : out_len;
    if (produced > 0) {
        memcpy(out, s->out + s->out_pos, produced);
        s->out_pos += produced;
        if (s->out_pos == s->out_len) {
            s->out_pos = 0;
            s->out_len = 0;
        }
    }
    int finished = s->finalized && s->out_len == s->out_pos;
    return (int64_t)(consumed & 0x7fffffffu)
         | ((int64_t)(produced & 0x7fffffffu) << 31)
         | (finished ? (int64_t)1 << 62 : 0)
         | ((params & 1) ? (int64_t)((uint64_t)1 << 63) : 0);
}

static inline const uint8_t* deflater_in(void* arr, int32_t off) {
    return (arr == NULL) ? NULL : (const uint8_t*)arr + JAVA_ARR_HDR + off;
}

static inline uint8_t* deflater_out(void* arr, int32_t off) {
    return (arr == NULL) ? NULL : (uint8_t*)arr + JAVA_ARR_HDR + off;
}

static DeflaterState* deflater_state(int64_t addr) {
    DeflaterState* s = (DeflaterState*)(intptr_t)addr;
    if (s == NULL) __jnative_throw_null_pointer_exception();
    return s;
}

int64_t __jnative_fn_java_util_zip_Deflater_init__IIZ_J(int32_t level, int32_t strategy, int32_t nowrap) {
    (void)level;
    (void)strategy;
    DeflaterState* s = (DeflaterState*)calloc(1, sizeof(DeflaterState));
    if (s == NULL) __jnative_throw_exception(NULL);
    s->data = (uint8_t*)malloc(STORED_MAX);
    if (s->data == NULL) __jnative_throw_exception(NULL);
    s->nowrap = nowrap ? 1 : 0;
    s->adler_a = 1;
    return (int64_t)(intptr_t)s;
}

void __jnative_fn_java_util_zip_Deflater_setDictionary__J_BII_V(int64_t addr, void* b, int32_t off, int32_t len) {
    (void)addr;
    (void)b;
    (void)off;
    (void)len;
}

void __jnative_fn_java_util_zip_Deflater_setDictionaryBuffer__JJI_V(int64_t addr, int64_t buf, int32_t len) {
    (void)addr;
    (void)buf;
    (void)len;
}

int64_t __jnative_fn_java_util_zip_Deflater_deflateBytesBytes__J_BII_BIIII_J(
        void* self, int64_t addr,
        void* input, int32_t inputOff, int32_t inputLen,
        void* output, int32_t outputOff, int32_t outputLen,
        int32_t flush, int32_t params)
{
    (void)self;
    DeflaterState* s = deflater_state(addr);
    if (output == NULL || (input == NULL && inputLen > 0)) __jnative_throw_null_pointer_exception();
    return deflater_call(s, deflater_in(input, inputOff), (size_t)inputLen,
                         deflater_out(output, outputOff), (size_t)outputLen, flush, params);
}

int64_t __jnative_fn_java_util_zip_Deflater_deflateBytesBuffer__J_BIIJIII_J(
        void* self, int64_t addr,
        void* input, int32_t inputOff, int32_t inputLen,
        int64_t outputAddress, int32_t outputLen,
        int32_t flush, int32_t params)
{
    (void)self;
    DeflaterState* s = deflater_state(addr);
    if (input == NULL && inputLen > 0) __jnative_throw_null_pointer_exception();
    return deflater_call(s, deflater_in(input, inputOff), (size_t)inputLen,
                         (uint8_t*)(intptr_t)outputAddress, (size_t)outputLen, flush, params);
}

int64_t __jnative_fn_java_util_zip_Deflater_deflateBufferBytes__JJI_BIIII_J(
        void* self, int64_t addr,
        int64_t inputAddress, int32_t inputLen,
        void* output, int32_t outputOff, int32_t outputLen,
        int32_t flush, int32_t params)
{
    (void)self;
    DeflaterState* s = deflater_state(addr);
    if (output == NULL) __jnative_throw_null_pointer_exception();
    return deflater_call(s, (const uint8_t*)(intptr_t)inputAddress, (size_t)inputLen,
                         deflater_out(output, outputOff), (size_t)outputLen, flush, params);
}

int64_t __jnative_fn_java_util_zip_Deflater_deflateBufferBuffer__JJIJIII_J(
        void* self, int64_t addr,
        int64_t inputAddress, int32_t inputLen,
        int64_t outputAddress, int32_t outputLen,
        int32_t flush, int32_t params)
{
    (void)self;
    DeflaterState* s = deflater_state(addr);
    return deflater_call(s, (const uint8_t*)(intptr_t)inputAddress, (size_t)inputLen,
                         (uint8_t*)(intptr_t)outputAddress, (size_t)outputLen, flush, params);
}

int32_t __jnative_fn_java_util_zip_Deflater_getAdler__J_I(int64_t addr) {
    DeflaterState* s = (DeflaterState*)(intptr_t)addr;
    return s ? (int32_t)((s->adler_b << 16) | s->adler_a) : 1;
}

void __jnative_fn_java_util_zip_Deflater_reset__J_V(int64_t addr) {
    DeflaterState* s = (DeflaterState*)(intptr_t)addr;
    if (s == NULL) return;
    s->header_done = 0;
    s->finalized = 0;
    s->adler_a = 1;
    s->adler_b = 0;
    s->data_len = 0;
    s->out_len = 0;
    s->out_pos = 0;
}

void __jnative_fn_java_util_zip_Deflater_end__J_V(int64_t addr) {
    DeflaterState* s = (DeflaterState*)(intptr_t)addr;
    if (s == NULL) return;
    free(s->data);
    free(s->out);
    free(s);
}

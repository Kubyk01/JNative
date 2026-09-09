#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

/* Runtime exception helpers (defined in jnative_runtime.c) */
__attribute__((noreturn)) void __jnative_throw_exception(void* exc);
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);

/* 4-byte length header, then elements (matches LLVM NEW_ARRAY codegen) */
#define ARRAY_HEADER_SIZE 4

static inline uint8_t* byte_array_data(void* arr) {
    if (arr == NULL) return NULL;
    return (uint8_t*)arr + ARRAY_HEADER_SIZE;
}

/* ---------- long init(boolean nowrap) ---------- */
int64_t __jnative_fn_java_util_zip_Inflater_init__Z_J(int32_t nowrap) {
    z_stream* strm = (z_stream*)calloc(1, sizeof(z_stream));
    if (strm == NULL) {
        __jnative_throw_exception(NULL); /* OutOfMemoryError */
    }
    int windowBits = nowrap ? -MAX_WBITS : MAX_WBITS;
    int ret = inflateInit2(strm, windowBits);
    if (ret != Z_OK) {
        free(strm);
        __jnative_throw_exception(NULL);
    }
    return (int64_t)(intptr_t)strm;
}

/* ---------- void initIDs() ---------- */
void __jnative_fn_java_util_zip_Inflater_initIDs___V(void) {
    /* Nothing to do: IDs are not used in this runtime. */
}

/* ---------- int getAdler(long addr) ---------- */
int32_t __jnative_fn_java_util_zip_Inflater_getAdler__J_I(int64_t addr) {
    z_stream* strm = (z_stream*)(intptr_t)addr;
    if (strm == NULL) return 0;
    return (int32_t)strm->adler;
}

/* ---------- void reset(long addr) ---------- */
void __jnative_fn_java_util_zip_Inflater_reset__J_V(int64_t addr) {
    z_stream* strm = (z_stream*)(intptr_t)addr;
    if (strm != NULL) {
        inflateReset(strm);
    }
}

/* ---------- void end(long addr) ---------- */
void __jnative_fn_java_util_zip_Inflater_end__J_V(int64_t addr) {
    z_stream* strm = (z_stream*)(intptr_t)addr;
    if (strm != NULL) {
        inflateEnd(strm);
        free(strm);
    }
}

/* ---------- long getBytesRead(long addr) ---------- */
int64_t __jnative_fn_java_util_zip_Inflater_getBytesRead__J_J(int64_t addr) {
    z_stream* strm = (z_stream*)(intptr_t)addr;
    if (strm == NULL) return 0;
    return (int64_t)strm->total_in;
}

/* ---------- long getBytesWritten(long addr) ---------- */
int64_t __jnative_fn_java_util_zip_Inflater_getBytesWritten__J_J(int64_t addr) {
    z_stream* strm = (z_stream*)(intptr_t)addr;
    if (strm == NULL) return 0;
    return (int64_t)strm->total_out;
}

/* ---------- int inflateBytesBytes(long, byte[], int, int, byte[], int, int) ---------- */
int32_t __jnative_fn_java_util_zip_Inflater_inflateBytesBytes__JLjava_lang_byte_IILjava_lang_byte_II_I(
        int64_t addr,
        void* input,  int32_t inputOff,  int32_t inputLen,
        void* output, int32_t outputOff, int32_t outputLen)
{
    z_stream* strm = (z_stream*)(intptr_t)addr;
    if (strm == NULL) return Z_STREAM_ERROR;
    if (input == NULL || output == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    strm->next_in  = (Bytef*)byte_array_data(input)  + inputOff;
    strm->avail_in = (uInt)inputLen;
    strm->next_out = (Bytef*)byte_array_data(output) + outputOff;
    strm->avail_out = (uInt)outputLen;

    int ret = inflate(strm, Z_NO_FLUSH);
    return (int32_t)ret;
}

/* ---------- int inflateBufferBytes(long, long, int, byte[], int, int) ---------- */
int32_t __jnative_fn_java_util_zip_Inflater_inflateBufferBytes__JJILjava_lang_byte_II_I(
        int64_t addr, int64_t inputAddress, int32_t inputLen,
        void* output, int32_t outputOff, int32_t outputLen)
{
    z_stream* strm = (z_stream*)(intptr_t)addr;
    if (strm == NULL) return Z_STREAM_ERROR;
    if (output == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    strm->next_in  = (Bytef*)(intptr_t)inputAddress;
    strm->avail_in = (uInt)inputLen;
    strm->next_out = (Bytef*)byte_array_data(output) + outputOff;
    strm->avail_out = (uInt)outputLen;

    int ret = inflate(strm, Z_NO_FLUSH);
    return (int32_t)ret;
}

/* ---------- int inflateBytesBuffer(long, byte[], int, int, long, int) ---------- */
int32_t __jnative_fn_java_util_zip_Inflater_inflateBytesBuffer__JLjava_lang_byte_IJI_I(
        int64_t addr, void* input, int32_t inputOff, int32_t inputLen,
        int64_t outputAddress, int32_t outputLen)
{
    z_stream* strm = (z_stream*)(intptr_t)addr;
    if (strm == NULL) return Z_STREAM_ERROR;
    if (input == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    strm->next_in  = (Bytef*)byte_array_data(input) + inputOff;
    strm->avail_in = (uInt)inputLen;
    strm->next_out = (Bytef*)(intptr_t)outputAddress;
    strm->avail_out = (uInt)outputLen;

    int ret = inflate(strm, Z_NO_FLUSH);
    return (int32_t)ret;
}

/* ---------- int inflateBufferBuffer(long, long, int, long, int) ---------- */
int32_t __jnative_fn_java_util_zip_Inflater_inflateBufferBuffer__JJIJI_I(
        int64_t addr, int64_t inputAddress, int32_t inputLen,
        int64_t outputAddress, int32_t outputLen)
{
    z_stream* strm = (z_stream*)(intptr_t)addr;
    if (strm == NULL) return Z_STREAM_ERROR;

    strm->next_in  = (Bytef*)(intptr_t)inputAddress;
    strm->avail_in = (uInt)inputLen;
    strm->next_out = (Bytef*)(intptr_t)outputAddress;
    strm->avail_out = (uInt)outputLen;

    int ret = inflate(strm, Z_NO_FLUSH);
    return (int32_t)ret;
}
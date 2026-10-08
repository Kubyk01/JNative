#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * java.util.zip.CRC32 — the single native entry point that the class
 * delegates to the runtime.
 *
 * The class declares one native:
 *
 *   private static native int updateBytes0(int crc, byte[] b,
 *                                          int off, int len);
 *
 * which performs the actual 32-bit cyclic-redundancy computation over a
 * byte array. The Java layer wraps the raw int in a long-accumulating
 * outer method and exposes both the streaming (update) and one-shot
 * (CRC32 constructor) forms; the arithmetic in this file follows the
 * IEEE 802.3 / zlib CRC-32 variant, which is the one java.util.zip.CRC32
 * is specified to compute.
 */

/*
 * IEEE 802.3 / zlib CRC-32 polynomial, reflected form. The reflected
 * representation is used because the table-driven implementation below
 * processes each input byte from its least-significant bit upward,
 * which matches the bit order the polynomial is specified in.
 */
#define CRC32_POLY 0xEDB88320u

/*
 * Lookup table, computed once on first use. The build is guarded by a
 * separate flag rather than by relying on a zero entry as a sentinel:
 * the table legitimately contains a zero at index zero on some
 * polynomials, so "the first slot is non-zero" would not be a reliable
 * "already built" test.
 */
static uint32_t crc_table[256];
static int      crc_table_ready = 0;

static void build_crc_table(void) {
    for (uint32_t i = 0; i < 256; i++) {
        uint32_t c = i;
        for (int k = 0; k < 8; k++) {
            c = (c & 1u) ? (CRC32_POLY ^ (c >> 1)) : (c >> 1);
        }
        crc_table[i] = c;
    }
    crc_table_ready = 1;
}

/*
 * private static native int updateBytes0(int crc, byte[] b,
 *                                        int off, int len);
 *
 * Follows the java.util.zip.CRC32.updateBytes contract:
 *
 *   - `crc` is the accumulated value; a fresh CRC32 starts with 0, and
 *     each call returns a value that can be fed back into the next
 *     call to continue the computation over a longer stream.
 *
 *   - The final CRC is complemented on output, and the incoming crc is
 *     complemented on input (the standard CRC-32 processing).
 *
 *   - `b[off .. off+len)` is the slice of the array to process.
 *
 * A NULL array or a non-positive length returns the incoming crc
 * unchanged rather than throwing. The Java-side wrapper performs its
 * own null and bounds checks before reaching the native, so these
 * branches are defensive: they guarantee that a pathological call does
 * not corrupt the accumulator or read beyond the array, without
 * introducing a second error-reporting path that the caller would have
 * to know about.
 */
int32_t __jnative_fn_java_util_zip_CRC32_updateBytes0__I_BII_I(
        int32_t crc, void* b, int32_t off, int32_t len)
{
    if (b == NULL) {
        return crc;
    }
    if (len <= 0) {
        return crc;
    }

    if (!crc_table_ready) {
        build_crc_table();
    }

    const uint8_t* data = (const uint8_t*)b + JAVA_ARR_HDR + (size_t)off;

    uint32_t c = (uint32_t)crc ^ 0xFFFFFFFFu;
    for (int32_t i = 0; i < len; i++) {
        c = crc_table[(c ^ data[i]) & 0xFFu] ^ (c >> 8);
    }
    return (int32_t)(c ^ 0xFFFFFFFFu);
}

/*
 * private static native int updateByteBuffer0(int crc, long addr,
 *                                             int off, int len);
 *
 * Identical arithmetic to updateBytes0 above; the only difference is
 * that the input is the raw address of a direct ByteBuffer's payload
 * rather than a Java byte[]'s payload. `addr` is the base address of
 * the buffer as returned by DirectBuffer.address(), and `off` is the
 * caller's byte offset within that buffer. The payload slice being
 * hashed is therefore exactly [addr + off, addr + off + len).
 *
 * The CRC32.update(ByteBuffer) overload reaches this path whenever the
 * caller supplies a direct buffer, and the checksummed bytes are
 * identical to what the array-based path would compute over the same
 * content — the two entry points share the same table-driven inner
 * loop for exactly that reason.
 */
int32_t __jnative_fn_java_util_zip_CRC32_updateByteBuffer0__IJII_I(
        int32_t crc, int64_t addr, int32_t off, int32_t len)
{
    if (addr == 0 || len <= 0) {
        return crc;
    }

    if (!crc_table_ready) {
        build_crc_table();
    }

    const uint8_t* data = (const uint8_t*)(intptr_t)(addr + off);

    uint32_t c = (uint32_t)crc ^ 0xFFFFFFFFu;
    for (int32_t i = 0; i < len; i++) {
        c = crc_table[(c ^ data[i]) & 0xFFu] ^ (c >> 8);
    }
    return (int32_t)(c ^ 0xFFFFFFFFu);
}

/*
 * private static native int update(int crc, int b);
 *
 * Single-byte continuation of a CRC-32 computation. The Java-level
 * CRC32.update(int b) masks the byte to 8 bits and calls this; the
 * arithmetic is exactly the same inner step the bulk updateBytes0
 * performs, just with a single byte. Extracted into its own entry
 * point because the caller is on the critical path of the
 * DeflaterOutputStream / GZIPOutputStream write(int) path, and
 * routing it through the bulk API would require synthesising a
 * one-element byte array per call.
 */
int32_t __jnative_fn_java_util_zip_CRC32_update__II_I(
        int32_t crc, int32_t b)
{
    if (!crc_table_ready) {
        build_crc_table();
    }
    uint32_t c = (uint32_t)crc ^ 0xFFFFFFFFu;
    c = crc_table[(c ^ (uint8_t)b) & 0xFFu] ^ (c >> 8);
    return (int32_t)(c ^ 0xFFFFFFFFu);
}
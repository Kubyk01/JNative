#include <stdint.h>
#include <stddef.h>

/*
 * Java array layout: [ int32 length ][ payload ... ]
 * The length header occupies exactly 4 bytes (see NEW_ARRAY in
 * LlvmFunctionEmitter and __jnative_create_string_array in the runtime).
 */
#define JAVA_ARR_HDR 4

/* IEEE 802.3 / zlib CRC-32 polynomial, reflected form. */
#define CRC32_POLY 0xEDB88320u

/* Lookup table, computed once on first use. */
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
 * private static native int updateBytes0(int crc, byte[] b, int off, int len);
 *
 * Follows java.util.zip.CRC32.updateBytes contract:
 *   - crc is the accumulated value (starts at 0 for a fresh CRC32).
 *   - the final CRC is complemented on output, and the incoming crc is
 *     complemented on input (standard CRC-32 processing).
 */
int32_t __jnative_fn_java_util_zip_CRC32_updateBytes0__I_BII_I(
        int32_t crc, void* b, int32_t off, int32_t len)
{
    if (b == NULL) {
        /* The Java-side wrapper throws NullPointerException before the
         * native call, so reaching this is not expected; keep the
         * original value to avoid corrupting the checksum. */
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
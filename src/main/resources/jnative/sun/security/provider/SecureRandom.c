#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <sys/random.h>

#include "jnative_runtime.h"

/*
 * Self-contained replacement for the SPI methods of
 * sun.security.provider.SecureRandom.
 *
 * JDK implementation builds every engine* call on top of a
 * MessageDigest("SHA-1") instance obtained from
 * MessageDigest.getInstance("SHA-1", "SUN"), which resolves the
 * implementation class sun.security.provider.SHA reflectively through
 * Class.forName. That class is not part of the native image, so the
 * lookup fails with ClassNotFoundException and the SPI's init() wraps
 * it into an InternalError.
 *
 * These overrides source entropy directly from the host kernel
 * (getrandom(2), falling back to /dev/urandom), which is exactly what
 * SHA1PRNG is a deterministic wrapper around. No reflective class
 * loading is involved.
 */

static int fill_random(void* buf, size_t len) {
    uint8_t* p = (uint8_t*)buf;
    size_t remaining = len;

    while (remaining > 0) {
        ssize_t n = getrandom(p, remaining, 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;                  /* fall back to /dev/urandom */
        }
        p += n;
        remaining -= (size_t)n;
    }
    if (remaining == 0) return 0;

    int fd = open("/dev/urandom", O_RDONLY);
    if (fd < 0) return -1;
    while (remaining > 0) {
        ssize_t n = read(fd, p, remaining);
        if (n < 0) {
            if (errno == EINTR) continue;
            close(fd);
            return -1;
        }
        if (n == 0) { close(fd); return -1; }
        p += n;
        remaining -= (size_t)n;
    }
    close(fd);
    return 0;
}

/* private void init(byte[] seed) — no reflective digest to set up. */
void __jnative_override_sun_security_provider_SecureRandom_init(
        void* self, void* seed)
{
    (void)self; (void)seed;
}

/* protected void engineSetSeed(byte[] seed) */
void __jnative_override_sun_security_provider_SecureRandom_engineSetSeed(
        void* self, void* seed)
{
    (void)self; (void)seed;   /* underlying CSPRNG cannot be reseeded */
}

/* protected void engineNextBytes(byte[] bytes) */
void __jnative_override_sun_security_provider_SecureRandom_engineNextBytes(
        void* self, void* bytes)
{
    (void)self;
    if (bytes == NULL) return;
    int32_t len = jnative_array_length(bytes);
    if (len <= 0) return;
    fill_random(jnative_array_data(bytes), (size_t)len);
}

/* protected byte[] engineGenerateSeed(int numBytes) */
void* __jnative_override_sun_security_provider_SecureRandom_engineGenerateSeed(
        void* self, int32_t num_bytes)
{
    (void)self;
    if (num_bytes < 0) num_bytes = 0;
    void* arr = jnative_byte_array(NULL, num_bytes);
    if (arr == NULL) return NULL;
    if (num_bytes > 0) {
        fill_random(jnative_array_data(arr), (size_t)num_bytes);
    }
    return arr;
}
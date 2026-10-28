#define _GNU_SOURCE
#include "jnative_platform.h"

#include <stdint.h>
#include <stddef.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>

#if defined(_WIN32)
    /*
     * advapi32.dll exports the kernel cryptographic RNG under the
     * name SystemFunction036. The declaration is written by hand
     * because MinGW does not ship a public header for it, and
     * because advapi32 is already linked by
     * Compiler.appendLinkerLibraries (the -ladvapi32 entry).
     */
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <windows.h>
   BOOLEAN NTAPI SystemFunction036(PVOID RandomBuffer, ULONG RandomBufferLength);
#else
#  include <fcntl.h>
#  include <unistd.h>
#endif

#include "jnative_runtime.h"

/* ============================================================================
 *  Compile-time detection of getrandom(2).
 *
 *  __has_include is a C11 extension implemented by clang, GCC 5+ and
 *  MSVC. The outer `defined(__has_include)` guard keeps the file
 *  parseable on older compilers that would otherwise reject
 *  `__has_include(<...>)` as a syntax error.
 *
 *  The __linux__ / __FreeBSD__ / __APPLE__ distinctions inside the
 *  __has_include branch matter because the header's content differs
 *  by platform:
 *
 *    - Linux, FreeBSD: the header declares getrandom();
 *    - macOS 10.12+:   the header declares getentropy() but never
 *                      declared getrandom() in any release.
 *
 *  Using getentropy() on macOS is not done in this revision. The
 *  entropy source on macOS is /dev/urandom, which is what the
 *  reference NativePRNG uses there as well, and which is available
 *  on every macOS version the runtime targets. getrandom() is the
 *  preferred path on Linux and FreeBSD because it avoids a file
 *  descriptor, a vnode lookup, and the fork-time race that
 *  /dev/urandom closes only via O_CLOEXEC.
 * ========================================================================== */

#if !defined(_WIN32)
#  if defined(__has_include)
#    if __has_include(<sys/random.h>)
#      include <sys/random.h>
#      if defined(__linux__) || defined(__FreeBSD__)
#        define JNATIVE_HAVE_GETRANDOM 1
#      endif
#    endif
#  endif

#  if !defined(JNATIVE_HAVE_GETRANDOM) && defined(__linux__)
#    include <sys/syscall.h>
#    if !defined(SYS_getrandom)
        /*
         * glibc older than 2.15 does not define SYS_getrandom in
         * <sys/syscall.h>. Fill in the number for the four
         * architectures the runtime has ever been built for. Any
         * other architecture falls through to /dev/urandom below.
         */
#      if defined(__x86_64__)
#        define SYS_getrandom 318
#      elif defined(__aarch64__)
#        define SYS_getrandom 278
#      elif defined(__i386__)
#        define SYS_getrandom 355
#      elif defined(__arm__)
#        define SYS_getrandom 384
#      elif defined(__riscv)
#        define SYS_getrandom 278
#      endif
#    endif
#    if defined(SYS_getrandom)
#      define JNATIVE_HAVE_SYSCALL_GETRANDOM 1
#    endif
#  endif
#endif  /* !_WIN32 */

/* ============================================================================
 *  fill_random — the single platform-portable entropy primitive.
 *
 *  Fills `buf[0 .. len)` with bytes from the platform's cryptographic
 *  RNG. Returns 0 on a complete fill, -1 on any failure. A failure is
 *  not recoverable inside this function — the callers translate it
 *  into the appropriate Java-level exception.
 *
 *  Every loop tolerates short reads and EINTR. Short reads from a
 *  kernel RNG are normal on a loaded machine and on the transition
 *  between early-boot entropy state and normal operation; treating
 *  them as failures would reject perfectly serviceable reads and
 *  make the fallback path hit far more often than necessary.
 * ========================================================================== */

static int fill_random(void* buf, size_t len) {
    if (len == 0) return 0;
    if (buf == NULL) return -1;

#if defined(_WIN32)
    /*
     * SystemFunction036 (RtlGenRandom) fills the whole request
     * atomically or rejects it — there is no partial fill to loop
     * over. TRUE on success, FALSE on failure.
     */
    return SystemFunction036(buf, (ULONG)len) ? 0 : -1;
#else
    uint8_t* p = (uint8_t*)buf;
    size_t remaining = len;

#  if defined(JNATIVE_HAVE_GETRANDOM)
    while (remaining > 0) {
        ssize_t n = getrandom(p, remaining, 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;                          /* fall through to /dev/urandom */
        }
        if (n == 0) break;
        p         += (size_t)n;
        remaining -= (size_t)n;
    }
    if (remaining == 0) return 0;
#  elif defined(JNATIVE_HAVE_SYSCALL_GETRANDOM)
    while (remaining > 0) {
        long n = syscall(SYS_getrandom, p, remaining, 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (n == 0) break;
        p         += (size_t)n;
        remaining -= (size_t)n;
    }
    if (remaining == 0) return 0;
#  endif

    /*
     * /dev/urandom fallback.
     *
     * O_CLOEXEC requires POSIX.1-2008. On a stripped-down musl or a
     * POSIX profile that does not expose it, the second open() call
     * without the flag takes over. The descriptor is closed before
     * this function returns, so the missing flag is at worst a
     * negligible window that no realistic program can observe
     * (there is no exec() between open and close).
     */
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        fd = open("/dev/urandom", O_RDONLY);
    }
    if (fd < 0) return -1;

    while (remaining > 0) {
        ssize_t n = read(fd, p, remaining);
        if (n < 0) {
            if (errno == EINTR) continue;
            close(fd);
            return -1;
        }
        if (n == 0) {
            /* /dev/urandom never returns zero. A zero here means the
             * kernel's file description has been tampered with; treat
             * it as a hard failure. */
            close(fd);
            return -1;
        }
        p         += (size_t)n;
        remaining -= (size_t)n;
    }
    close(fd);
    return 0;
#endif  /* _WIN32 */
}

/* ============================================================================
 *  Exception helpers.
 *
 *  The classes below are the ones the JDK contract for the
 *  corresponding method requires. __jnative_construct_exception looks
 *  the vtable up by symbol name; a NULL return means the class was not
 *  reached by the reachability analysis and is not present in the
 *  image, in which case __jnative_throw_exception substitutes a
 *  generic Throwable. That is not the same class the caller declared,
 *  but it is a well-formed exception object and the failure is not
 *  silent.
 * ========================================================================== */

static void throw_negative_array_size(int32_t n) {
    char msg[32];
    snprintf(msg, sizeof(msg), "%d", (int)n);
    void* exc = __jnative_construct_exception(
        "vtable_java_lang_NegativeArraySizeException", msg);
    __jnative_throw_exception(exc);   /* NULL → generic Throwable */
}

static void throw_entropy_failure(const char* context) {
    void* exc = __jnative_construct_exception(
        "vtable_java_lang_InternalError", context);
    __jnative_throw_exception(exc);   /* NULL → generic Throwable */
}

/* ============================================================================
 *  Overrides.
 * ========================================================================== */

/*
 * <init>()V — no-argument constructor.
 *
 * The reference implementation allocates a NativePRNG and stores it
 * in `this.prng`. This override skips that allocation entirely: every
 * engine* call below reads directly from the OS RNG and never touches
 * `this.prng`. The field is left at the value Java's field
 * initialisation produced (null), which is the state that matters
 * only if some other code path ever reads it — and no such path is
 * reachable from a SecureRandom that was constructed through this
 * override.
 *
 * Arity is 1 because the Java-level call site for a no-argument
 * constructor emits exactly one argument (the freshly allocated
 * receiver). A 2-parameter C signature here would fail the LLVM
 * verifier on the very first call with "Call parameter type does not
 * match function signature". See the file header for the full
 * reasoning about the sentinel and the parametrised constructor.
 */
void __jnative_override_sun_security_provider_SecureRandom_init(void* self) {
    (void)self;
}

/*
 * engineSetSeed(byte[] seed) — protected.
 *
 * The seed is dropped. This matches the reference
 * sun.security.provider.NativePRNG.MD implementation, whose
 * engineSetSeed is a no-op for the same reason: the underlying source
 * is a stateless OS RNG that has no internal state a seed could
 * perturb.
 *
 * SecureRandom's own contract explicitly permits this:
 *
 *     "…does not require that the same sequence of numbers be
 *      produced for the same seed."
 *
 * A caller that needs reproducibility across runs must instantiate a
 * different SecureRandomSpi; sun.security.provider.SHA1PRNG is the
 * one the JDK provides for that purpose.
 */
void __jnative_override_sun_security_provider_SecureRandom_engineSetSeed(
        void* self, void* seed)
{
    (void)self;
    (void)seed;
}

/*
 * engineNextBytes(byte[] out) — protected.
 *
 * Fills `out` in place with random bytes from the OS RNG. A null
 * argument is a NullPointerException, matching the reference
 * implementation's dereference of `bytes.length`. A zero-length array
 * is a successful no-op — the caller asked for zero bytes and that is
 * exactly what it gets.
 */
void __jnative_override_sun_security_provider_SecureRandom_engineNextBytes(
        void* self, void* out)
{
    (void)self;

    if (out == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    int32_t len = jnative_array_length(out);
    if (len < 0) {
        /* jnative_array_length returns -1 for a non-array pointer. A
         * genuine Java byte[] cannot produce this, so treating it as
         * NPE matches what the reference implementation would do on a
         * dereference of the same bogus header. */
        __jnative_throw_null_pointer_exception();
    }
    if (len == 0) return;

    if (fill_random(jnative_array_data(out), (size_t)len) != 0) {
        throw_entropy_failure(
            "Operating system random number source is unavailable");
    }
}

/*
 * engineGenerateSeed(int numBytes) — protected.
 *
 * Allocates a fresh byte[numBytes] and fills it with that many random
 * bytes. A negative argument is a NegativeArraySizeException,
 * matching what `new byte[numBytes]` in the reference implementation
 * throws.
 *
 * The array is allocated with jnative_array_alloc (which uses calloc)
 * and then filled with fill_random. The intermediate zero fill is
 * harmless: it is overwritten completely by the subsequent
 * fill_random, and for the typical seed size (8-32 bytes) the extra
 * write is invisible.
 */
void* __jnative_override_sun_security_provider_SecureRandom_engineGenerateSeed(
        void* self, int32_t num_bytes)
{
    (void)self;

    if (num_bytes < 0) {
        throw_negative_array_size(num_bytes);
    }

    void* arr = jnative_array_alloc("[B", num_bytes, JNATIVE_ELEM_SIZE_BYTE);
    if (arr == NULL) {
        __jnative_throw_out_of_memory_error_ctx(
            "SecureRandom.engineGenerateSeed");
    }

    if (num_bytes > 0) {
        if (fill_random(jnative_array_data(arr), (size_t)num_bytes) != 0) {
            throw_entropy_failure(
                "Operating system random number source is unavailable");
        }
    }

    return arr;
}
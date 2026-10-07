#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <dlfcn.h>

#include "jnative_runtime.h"

/*
 * =========================================================================
 * java.security.SecureRandom — native override for getDefaultPRNG.
 * =========================================================================
 *
 * The Java body of SecureRandom.getDefaultPRNG(boolean, byte[]) is
 * replaced entirely by the C function below. NativeOverrideScanner
 * finds the function by its __jnative_override_* prefix, BytecodeToIr
 * skips translating the Java method, and every call site of
 * getDefaultPRNG resolves to the C symbol instead. The Java method's
 * body is not present anywhere in the compiled image.
 *
 * ---------------------------------------------------------------------
 * Why an override is needed
 * ---------------------------------------------------------------------
 *
 * The reference implementation does:
 *
 *     String prng = getPrngAlgorithm();          // Security property
 *     if (prng == null) prng = "SHA1PRNG";
 *     this.secureRandomSpi = (SecureRandomSpi)
 *         Class.forName(prng).getConstructor().newInstance();
 *     this.provider = Providers.getSunProvider();
 *     if (setSeed) this.secureRandomSpi.engineSetSeed(seed);
 *
 * The reflective Class.forName / getConstructor / newInstance chain
 * depends on the java.security.Security property machinery, on
 * Class.getDeclaredConstructors0, and on the reflection table being
 * fully populated for the PRNG class. In a native image none of those
 * are guaranteed: Security.<clinit> loads its properties from
 * conf/security/java.security, which does not exist, and the SHA1PRNG
 * class name is only reachable through the reflection table if it was
 * explicitly registered.
 *
 * The override below reproduces the same behaviour without going
 * through any of that. It always selects sun.security.provider
 * .SecureRandom as the SPI, which is the class the JDK's own default
 * (DEFAULT_PRNG = "SHA1PRNG") resolves to.
 *
 * ---------------------------------------------------------------------
 * Object layout of java.security.SecureRandom
 * ---------------------------------------------------------------------
 *
 * Computed by LlvmGlobalEmitter.getFieldOffset for the JDK 21 layout of
 * java.security.SecureRandom and its superclass java.util.Random, using
 * the emitter's rules (8-byte object header, then instance fields in
 * declaration order at their natural alignment):
 *
 *     offset   field
 *     ------   ------------------------------------------------
 *          0   vtable
 *          8   java.util.Random.seed (AtomicLong)         [inherited]
 *         16   java.util.Random.nextNextGaussian (double)  [inherited]
 *         24   java.util.Random.haveNextNextGaussian (bool) [inherited]
 *         32   provider (java.security.Provider)
 *         40   secureRandomSpi (java.security.SecureRandomSpi)
 *         48   algorithm (java.lang.String)
 *         56   isSeeded (boolean)
 *
 * If the JDK changes the field order these numbers change. A future
 * revision can push them in from @main the same way Thread.c does
 * (__jnative_thread_set_layout) — for now they are hard-coded here.
 *
 * ---------------------------------------------------------------------
 * Visibility of the SPI class
 * ---------------------------------------------------------------------
 *
 * sun.security.provider.SecureRandom is forced into the reachability
 * closure by Orchestrator.forceProviderServiceClasses, so both its
 * class mirror (@refclass_sun_security_provider_SecureRandom) and its
 * methods (engineSetSeed, engineNextBytes, engineGenerateSeed, and the
 * no-arg constructor) are emitted into the module. This file relies on
 * that: it looks up the constructor and engineSetSeed by their mangled
 * symbol names at run time, which is more robust than declaring them
 * as extern because a missing symbol produces a clear runtime
 * diagnostic rather than a link error.
 */

/* Field offsets inside java.security.SecureRandom. See the table above. */
#define SR_PROVIDER_OFFSET           32
#define SR_SECURE_RANDOM_SPI_OFFSET  40
#define SR_ALGORITHM_OFFSET          48
#define SR_IS_SEEDED_OFFSET          56

/* Internal class name of the SPI we install. */
#define SPI_CLASS_INTERNAL "sun/security/provider/SecureRandom"

/*
 * Mangled names of the SPI's methods that this override calls. These
 * follow LlvmRuntime.mangleMethod exactly. Recompute them from
 * mangleMethod(className, methodName, descriptor) if the SPI's method
 * set ever changes.
 */
#define SPI_CTOR_SYMBOL \
    "fn_sun_security_provider_SecureRandom__init____V"
#define SPI_SET_SEED_SYMBOL \
    "fn_sun_security_provider_SecureRandom_engineSetSeed___B_V"

/*
 * Looks up a symbol in the running process image. Returns NULL when the
 * symbol is not present; callers decide whether that is fatal.
 *
 * The JNative binary is linked with -rdynamic (see Compiler.java), so
 * every emitted function is visible to dlsym.
 */
static void* lookup_symbol(const char* name) {
    void* handle = dlopen(NULL, RTLD_LAZY);
    if (handle == NULL) return NULL;
    void* sym = dlsym(handle, name);
    dlclose(handle);
    return sym;
}

/*
 * Override for java.security.SecureRandom.getDefaultPRNG(boolean, byte[]).
 *
 * Called exactly once per SecureRandom instance, from the constructor,
 * after the java.util.Random superclass has been fully initialised.
 *
 *     void getDefaultPRNG(boolean setSeed, byte[] seed);
 *
 * Argument mapping for the C ABI:
 *
 *     void*   self     — the SecureRandom receiver
 *     int32_t setSeed  — whether the caller supplied a seed
 *     void*   seed     — a Java byte[] (runtime array layout), or NULL
 *
 * Return value: void.
 *
 * The function:
 *
 *   1. Locates sun.security.provider.SecureRandom in the runtime's
 *      class registry (populated from the @refclass_* globals).
 *   2. Allocates a fresh instance and calls its no-arg constructor.
 *   3. Stores the instance in this.secureRandomSpi.
 *   4. Stores the algorithm name "SHA1PRNG" in this.algorithm.
 *   5. If the caller supplied a seed, passes it to
 *      spi.engineSetSeed(byte[]).
 *   6. Marks this.isSeeded true.
 *
 * On any hard failure — the SPI class is absent, its constructor
 * symbol is missing, allocation fails — the method throws a generic
 * exception rather than leaving a half-initialised SecureRandom behind.
 * Every subsequent call to engineNextBytes on such an instance would
 * NPE on the null secureRandomSpi field, and a failure at construction
 * time is far easier to diagnose than one at first use.
 */
void __jnative_override_java_security_SecureRandom_getDefaultPRNG(
        void* self, int32_t setSeed, void* seed)
{
    if (self == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    /*
     * 1. Locate the SPI class in the runtime registry. The registry is
     *    populated from @refclass_* globals, which
     *    LlvmGlobalEmitter.generateReflectionData emits for every class
     *    in the class map — including this one, because
     *    Orchestrator.forceProviderServiceClasses calls
     *    addInstantiatedClass on it.
     */
    ReflectionClass* spi_cls = jnative_class_by_name(SPI_CLASS_INTERNAL);
    if (spi_cls == NULL) {
        __jnative_throw_exception(NULL);
        return;
    }

    /*
     * 2. Allocate the SPI instance. jnative_alloc_object writes the
     *    class's vtable into word 0 and zeroes the rest of the object,
     *    so the instance is a valid Java object before the constructor
     *    runs.
     */
    void* spi = jnative_alloc_object(spi_cls);
    if (spi == NULL) {
        __jnative_throw_out_of_memory_error_ctx(
            "SecureRandom.getDefaultPRNG");
        return;
    }

    /*
     * 3. Call spi.<init>(). The symbol is looked up at run time rather
     *    than declared as an extern so that a missing constructor — a
     *    class the reachability analysis did not pull in, or a
     *    different JDK whose SPI has only the (byte[]) constructor —
     *    surfaces as a clear diagnostic instead of a link error.
     */
    typedef void (*noarg_ctor_t)(void*);
    noarg_ctor_t ctor = (noarg_ctor_t)lookup_symbol(SPI_CTOR_SYMBOL);
    if (ctor == NULL) {
        __jnative_throw_exception(NULL);
        return;
    }
    ctor(spi);

    /*
     * 4. Install the SPI and algorithm name on the receiver.
     *    `provider` is left null: this runtime does not model a
     *    java.security.Provider registry, and the only consumer of the
     *    field — SecureRandom.getProvider() — is documented to return
     *    null when the object was not created through a provider-based
     *    constructor.
     */
    *(void**)((char*)self + SR_SECURE_RANDOM_SPI_OFFSET) = spi;
    *(void**)((char*)self + SR_PROVIDER_OFFSET)          = NULL;
    *(void**)((char*)self + SR_ALGORITHM_OFFSET)         =
        jnative_string("SHA1PRNG");

    /*
     * 5. Forward the caller's seed, if any, to the SPI. The seed is a
     *    Java byte[] in the runtime's array layout, which is exactly
     *    what engineSetSeed(byte[]) expects, so no conversion is
     *    needed.
     */
    if (setSeed && seed != NULL) {
        typedef void (*set_seed_t)(void*, void*);
        set_seed_t set_seed = (set_seed_t)lookup_symbol(SPI_SET_SEED_SYMBOL);
        if (set_seed != NULL) {
            set_seed(spi, seed);
        }
        /*
         * A missing engineSetSeed symbol is not fatal: the SPI seeds
         * itself from the system entropy source on the first call to
         * engineNextBytes, and the caller-supplied seed is only a hint.
         */
    }

    /*
     * 6. Mark the instance as seeded. The SHA1PRNG SPI lazy-seeds
     *    itself, so no explicit seed call is required here even when
     *    the caller passed no bytes; the flag only tells
     *    SecureRandom.ensureIsSeeded that it can skip its own
     *    self-seed step.
     */
    *(uint8_t*)((char*)self + SR_IS_SEEDED_OFFSET) = 1;
}
#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <dlfcn.h>

#include "jnative_runtime.h"

#define SR_PROVIDER_OFFSET           32
#define SR_SECURE_RANDOM_SPI_OFFSET  40
#define SR_ALGORITHM_OFFSET          48
#define SR_IS_SEEDED_OFFSET          56

#define SPI_CLASS_INTERNAL "sun/security/provider/SecureRandom"

#define SPI_CTOR_SYMBOL \
    "fn_sun_security_provider_SecureRandom__init____V"
#define SPI_SET_SEED_SYMBOL \
    "fn_sun_security_provider_SecureRandom_engineSetSeed___B_V"

static void* lookup_symbol(const char* name) {
    void* handle = dlopen(NULL, RTLD_LAZY);
    if (handle == NULL) return NULL;
    void* sym = dlsym(handle, name);
    dlclose(handle);
    return sym;
}

void __jnative_override_java_security_SecureRandom_getDefaultPRNG(
        void* self, int32_t setSeed, void* seed)
{
    if (self == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    ReflectionClass* spi_cls = jnative_class_by_name(SPI_CLASS_INTERNAL);
    if (spi_cls == NULL) {
        __jnative_throw_exception(NULL);
        return;
    }

    void* spi = jnative_alloc_object(spi_cls);
    if (spi == NULL) {
        __jnative_throw_out_of_memory_error_ctx(
            "SecureRandom.getDefaultPRNG");
        return;
    }

    typedef void (*noarg_ctor_t)(void*);
    noarg_ctor_t ctor = (noarg_ctor_t)lookup_symbol(SPI_CTOR_SYMBOL);
    if (ctor == NULL) {
        __jnative_throw_exception(NULL);
        return;
    }
    ctor(spi);

    *(void**)((char*)self + SR_SECURE_RANDOM_SPI_OFFSET) = spi;
    *(void**)((char*)self + SR_PROVIDER_OFFSET)          = NULL;
    *(void**)((char*)self + SR_ALGORITHM_OFFSET)         =
        jnative_string("SHA1PRNG");

    if (setSeed && seed != NULL) {
        typedef void (*set_seed_t)(void*, void*);
        set_seed_t set_seed = (set_seed_t)lookup_symbol(SPI_SET_SEED_SYMBOL);
        if (set_seed != NULL) {
            set_seed(spi, seed);
        }
    }

    *(uint8_t*)((char*)self + SR_IS_SEEDED_OFFSET) = 1;
}
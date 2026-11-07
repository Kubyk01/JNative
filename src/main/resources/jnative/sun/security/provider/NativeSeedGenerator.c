#if defined(_WIN32)

#ifndef WIN32_LEAN_AND_MEAN
#  define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <stdint.h>

#include "jnative_runtime.h"

BOOLEAN NTAPI SystemFunction036(PVOID RandomBuffer, ULONG RandomBufferLength);

int32_t __jnative_fn_sun_security_provider_NativeSeedGenerator_nativeGenerateSeed___B_Z(void* seed)
{
    if (seed == NULL) __jnative_throw_null_pointer_exception();
    int32_t len = jnative_array_length(seed);
    if (len <= 0) return 1;
    return SystemFunction036(jnative_array_data(seed), (ULONG)len) ? 1 : 0;
}

#endif

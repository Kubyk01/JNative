#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

void* __jnative_override_jdk_internal_jimage_ImageReaderFactory_getImageReader(void* image) {
    (void)image;
    return NULL;
}
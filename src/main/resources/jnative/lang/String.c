#define _GNU_SOURCE
#include <stdint.h>

#include "jnative_runtime.h"

void* __jnative_fn_java_lang_String_intern___Ljava_lang_String_(void* this_str) {
    return __jnative_string_intern(this_str);
}
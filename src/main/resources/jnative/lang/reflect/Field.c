#include <stdint.h>
#include <stdlib.h>

#include "jnative_runtime.h"

void* __jnative_fn_java_lang_reflect_Field_getTypeAnnotationBytes0____B(void* this_field) {
    (void)this_field;
    /*
     * A zero-length byte[]. jnative_array_alloc() rather than a bare
     * malloc() so the result carries a well-formed header: a [B class
     * mirror, length 0 and elem_size 1. A hand-written header left the
     * class word unset and the payload offset wrong.
     */
    return jnative_array_alloc("[B", 0, JNATIVE_ELEM_SIZE_BYTE);
}
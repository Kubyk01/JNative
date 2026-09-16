#include <stdint.h>
#include <stdlib.h>

#define JAVA_ARR_HDR 4

void* __jnative_fn_java_lang_reflect_Field_getTypeAnnotationBytes0____B(void* this_field) {
    (void)this_field;
    void* array = malloc(JAVA_ARR_HDR);
    if (array == NULL) {
        return NULL;
    }
    *(int32_t*)array = 0;
    return array;
}
#include "jnative_runtime.h"

extern void* __jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(void* obj);

void* __jnative_fn_java_util_Collection_getClass___Ljava_lang_Class_(void* this_obj) {
    return __jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(this_obj);
}
/*
 * java.util.List does not declare getClass() — it is a final method of
 * java.lang.Object. The reachability resolver nevertheless ends up calling
 * java/util/List.getClass when it encounters an INVOKEINTERFACE against a
 * List-typed receiver. Rather than duplicating the reflection logic we
 * forward the call to the canonical implementation in java/lang/Object.c.
 */
extern void* __jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(void* obj);

void* __jnative_fn_java_util_List_getClass___Ljava_lang_Class_(void* this_obj) {
    return __jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(this_obj);
}
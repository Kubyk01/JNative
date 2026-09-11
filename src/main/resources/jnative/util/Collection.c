/*
 * Same rationale as util/Set.c: java.util.Collection inherits getClass()
 * from java.lang.Object. Forward to the canonical implementation.
 */
extern void* __jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(void* obj);

void* __jnative_fn_java_util_Collection_getClass___Ljava_lang_Class_(void* this_obj) {
    return __jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(this_obj);
}
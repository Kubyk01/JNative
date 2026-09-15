/*
 * java.util.Set does not declare getClass() – it inherits the final method
 * from java.lang.Object. The reachability resolver nevertheless emits a
 * native call to Set.getClass when it sees an INVOKEINTERFACE against a
 * Set-typed receiver. Forward to the canonical Object.getClass implementation.
 */
extern void* __jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(void* obj);

void* __jnative_fn_java_util_Set_getClass___Ljava_lang_Class_(void* this_obj) {
    return __jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(this_obj);
}
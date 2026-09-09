#include <stdlib.h>
#include <string.h>
#include <stdint.h>

__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);
__attribute__((noreturn)) void __jnative_throw_exception(void* exc);

struct ReflectionClass {
    void* name;
    struct ReflectionClass* superclass;
    struct ReflectionClass** interfaces;
    void** methods;
    void** fields;
    void** constructors;
    int modifiers;
    int object_size;
};

/* In our runtime, arrays have a 4-byte header (element count) followed by
 * elements. This matches what the LLVM emitter writes in NEW_ARRAY and what
 * __jnative_create_string_array / __jnative_new_multi_array allocate. */
#define ARRAY_HEADER_SIZE 4

/* Determine the size in bytes of one element for the given component class.
 * Primitive classes are identified by their canonical names ("int", "long",
 * ...). Anything else is treated as a reference (8 bytes on x86_64). */
static int element_size_for_class(struct ReflectionClass* cls) {
    if (cls == NULL || cls->name == NULL) return 8;
    const char* name = (const char*)cls->name;

    if (strcmp(name, "boolean") == 0 || strcmp(name, "byte") == 0) return 1;
    if (strcmp(name, "short")   == 0 || strcmp(name, "char") == 0) return 2;
    if (strcmp(name, "int")     == 0 || strcmp(name, "float") == 0) return 4;
    if (strcmp(name, "long")    == 0 || strcmp(name, "double") == 0) return 8;
    return 8;   /* reference type */
}

/* public static native Object newArray(Class<?> componentType, int length)
 *     throws NegativeArraySizeException; */
void* __jnative_fn_java_lang_reflect_Array_newArray__Ljava_lang_Class_I_Ljava_lang_Object_(
        void* component_class, int32_t length) {

    if (component_class == NULL) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }
    if (length < 0) {
        /* NegativeArraySizeException */
        __jnative_throw_exception(NULL);
        return NULL;
    }

    struct ReflectionClass* cls = (struct ReflectionClass*)component_class;
    int elem_size = element_size_for_class(cls);

    int64_t total = (int64_t)ARRAY_HEADER_SIZE + (int64_t)length * (int64_t)elem_size;
    void* arr = calloc(1, (size_t)total);
    if (!arr) {
        /* OutOfMemoryError */
        __jnative_throw_exception(NULL);
        return NULL;
    }
    *((int32_t*)arr) = length;
    return arr;
}
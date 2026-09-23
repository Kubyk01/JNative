#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "jnative_runtime.h"

/*
 * The array header (klass, length, elem_size) and the payload offset come
 * from jnative_runtime.h — see the JAVA_ARR_* constants there. This file
 * used to carry a local `#define ARRAY_HEADER_SIZE 8` and wrote the
 * length/elem_size words at offsets 0 and 4, which is no longer where
 * they live.
 */

/* Determine the size in bytes of one element for the given component class.
 * Primitive classes are identified by their canonical names ("int", "long",
 * ...). Anything else is treated as a reference (8 bytes on x86_64). */
static int element_size_for_class(struct ReflectionClass* cls) {
    if (cls == NULL || cls->cname == NULL) return 8;
    const char* name = cls->cname;

    if (strcmp(name, "boolean") == 0 || strcmp(name, "byte") == 0) return 1;
    if (strcmp(name, "short")   == 0 || strcmp(name, "char") == 0) return 2;
    if (strcmp(name, "int")     == 0 || strcmp(name, "float") == 0) return 4;
    if (strcmp(name, "long")    == 0 || strcmp(name, "double") == 0) return 8;
    return 8;   /* reference type */
}

/*
 * Builds the JVM array descriptor for an array whose component class is
 * `comp_name` (an internal name, an array descriptor, or a primitive name)
 * and writes it into `desc`.
 *
 * A component class that is itself an array contributes one more leading
 * '['; a reference class contributes the "L...;" form; a primitive
 * contributes its single-letter code. An unrecognised name yields an empty
 * descriptor and false, so the caller can fall back to Object[].
 */
static int array_descriptor_for_component(const char* comp_name,
                                          char* desc, size_t desc_size) {
    if (comp_name == NULL || comp_name[0] == '\0') return 0;
    if (desc == NULL || desc_size == 0) return 0;

    if (comp_name[0] == '[') {
        snprintf(desc, desc_size, "[%s", comp_name);
        return 1;
    }

    if (strchr(comp_name, '/') != NULL) {
        snprintf(desc, desc_size, "[L%s;", comp_name);
        return 1;
    }

    static const struct { const char* n; char c; } prim[] = {
        {"boolean", 'Z'}, {"byte", 'B'}, {"short", 'S'}, {"char", 'C'},
        {"int", 'I'},    {"long",  'J'}, {"float", 'F'}, {"double", 'D'},
        {NULL, 0}
    };
    for (int i = 0; prim[i].n != NULL; i++) {
        if (strcmp(prim[i].n, comp_name) == 0) {
            snprintf(desc, desc_size, "[%c", prim[i].c);
            return 1;
        }
    }
    return 0;
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

    /*
     * The array's class mirror is derived from the component class, so that
     * getClass() on the result answers with e.g. [I rather than a null
     * mirror. Object[] is the honest fallback for a component class this
     * runtime cannot name.
     */
    char desc[512];
    if (!array_descriptor_for_component(cls->cname, desc, sizeof(desc))) {
        snprintf(desc, sizeof(desc), "[Ljava/lang/Object;");
    }

    void* arr = jnative_array_alloc(desc, length, elem_size);
    if (!arr) {
        /* OutOfMemoryError */
        __jnative_throw_exception(NULL);
        return NULL;
    }
    return arr;
}

/*
 * public static native Object multiNewArray(Class<?> componentType,
 *                                           int[] dimensions)
 *     throws IllegalArgumentException, NegativeArraySizeException;
 *
 * Backs the varargs overload of Array.newInstance — the one whose
 * call sites pass a Class and an int[] of per-dimension sizes — and,
 * on JDK builds whose Array.newInstance(Class, int) is itself compiled
 * as a thin wrapper over this method, the single-int overload as well.
 *
 * Examples:
 *
 *     Array.multiNewArray(int.class,    new int[]{3, 4})  -> new int[3][4]
 *     Array.multiNewArray(String.class, new int[]{5, 6})  -> new String[5][6]
 *     Array.multiNewArray(int[].class,  new int[]{5})     -> new int[5][]
 *
 * Contract, per the JDK javadoc of Array.newInstance(Class, int...):
 *
 *   - a null component type           -> NullPointerException
 *   - a null dimensions array         -> NullPointerException
 *   - componentType == void.class     -> IllegalArgumentException
 *   - dimensions.length == 0          -> IllegalArgumentException
 *   - dimensions.length >  255        -> IllegalArgumentException
 *   - any dimensions[i] < 0           -> NegativeArraySizeException
 *
 * All four failure classes are reported through the runtime's generic
 * throw helper, which the Java-side caller translates into the checked
 * exception the specification requires.
 *
 * Implementation:
 *
 *   The actual array tree is allocated by __jnative_new_multi_array,
 *   which walks the descriptor from the outermost dimension inward and
 *   allocates each sub-array through jnative_array_alloc — the same
 *   allocation path used by every other array factory in the runtime,
 *   so the resulting arrays carry the standard header (class mirror at
 *   JAVA_ARR_KLASS_OFFSET, length at JAVA_ARR_LENGTH_OFFSET, element
 *   size at JAVA_ARR_ELEM_SIZE_OFFSET) and are indistinguishable from
 *   ones the LLVM emitter allocated for a MULTI_NEW_ARRAY instruction.
 *
 *   The descriptor handed to __jnative_new_multi_array is assembled
 *   here by prepending one '[' per outer dimension to the leaf array
 *   descriptor returned by array_descriptor_for_component. That helper
 *   already produces the correct leaf form for every input the runtime
 *   can receive:
 *
 *       "int"                  -> "[I"
 *       "java/lang/String"     -> "[Ljava/lang/String;"
 *       "[I"   (int[].class)   -> "[[I"
 *       "[Ljava/lang/Object;"  -> "[[Ljava/lang/Object;"
 *
 *   so for dims == 2 and componentType == int.class the composed
 *   descriptor is "[I" prefixed by one '[' — "[[I" — which is exactly
 *   the JVM descriptor of the array the caller asked for.
 */
void* __jnative_fn_java_lang_reflect_Array_multiNewArray__Ljava_lang_Class__I_Ljava_lang_Object_(
        void* component_class, void* dimensions_array) {

    if (component_class == NULL || dimensions_array == NULL) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }

    struct ReflectionClass* cls = (struct ReflectionClass*)component_class;

    /*
     * void is not a legal array component. The Java layer never
     * reaches this path with void.class in practice — the reflective
     * entry points reject it before the call — but a direct call from
     * generated bytecode can, so the guard is kept.
     */
    if (cls->cname != NULL && strcmp(cls->cname, "void") == 0) {
        __jnative_throw_exception(NULL);
        return NULL;
    }

    int32_t dims = jnative_array_length(dimensions_array);
    if (dims <= 0 || dims > 255) {
        /* IllegalArgumentException: zero-dimensional or too deep. */
        __jnative_throw_exception(NULL);
        return NULL;
    }

    /*
     * Leaf array descriptor. The Java int[] payload is a contiguous
     * run of int32_t values starting at JAVA_ARR_HDR, so
     * jnative_array_data returns a pointer suitable for both the
     * bounds check below and for __jnative_new_multi_array.
     */
    int32_t* dims_ptr = (int32_t*)jnative_array_data(dimensions_array);

    for (int32_t i = 0; i < dims; i++) {
        if (dims_ptr[i] < 0) {
            /* NegativeArraySizeException. */
            __jnative_throw_exception(NULL);
            return NULL;
        }
    }

    /*
     * Assemble the full descriptor. Starting from the leaf form
     * (which already carries one '['), prepend dims-1 additional '['
     * characters so the total leading '[' count equals dims.
     *
     * The buffer is sized for the worst case allowed by the spec:
     * 255 dimensions plus the longest leaf descriptor the helper can
     * produce, which is a fully-qualified reference descriptor of the
     * form "[L<package>/<Class>;".
     */
    char leaf_desc[512];
    if (!array_descriptor_for_component(cls->cname, leaf_desc, sizeof(leaf_desc))) {
        /* The helper only fails for an empty or NULL name; fall back
         * to Object[] rather than producing an unparseable descriptor. */
        snprintf(leaf_desc, sizeof(leaf_desc), "[Ljava/lang/Object;");
    }

    char full_desc[768];
    size_t pos = 0;
    for (int32_t i = 1; i < dims; i++) {
        full_desc[pos++] = '[';
    }
    size_t leaf_len = strlen(leaf_desc);
    if (pos + leaf_len + 1 > sizeof(full_desc)) {
        __jnative_throw_exception(NULL);
        return NULL;
    }
    memcpy(full_desc + pos, leaf_desc, leaf_len + 1);

    /*
     * Leaf element size: the byte width of the innermost non-array
     * element type. element_size_for_class already returns the correct
     * width for every primitive class and 8 for references, which is
     * exactly what __jnative_new_multi_array expects. The intermediate
     * dimensions use the pointer-sized stride internally; only the
     * innermost level is created with this size.
     */
    int leaf_elem_size = element_size_for_class(cls);

    void* result = __jnative_new_multi_array(full_desc, (int)dims,
                                             dims_ptr, leaf_elem_size);
    if (result == NULL) {
        __jnative_throw_exception(NULL);
    }
    return result;
}

int32_t __jnative_fn_java_lang_reflect_Array_getLength__Ljava_lang_Object__I(
        void* array) {

    if (array == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    return jnative_array_length(array);
}

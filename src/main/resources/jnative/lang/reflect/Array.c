#include "jnative_platform.h"
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

/* ========================================================================
 *  Shared externs used by the reflective accessors below.
 * ===================================================================== */

extern void* __jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(void* obj);
extern int   __jnative_fn_java_lang_Class_isInstance__Ljava_lang_Object__Z(void* this_cls, void* obj);
extern int   __jnative_fn_java_lang_Class_isAssignableFrom__Ljava_lang_Class__Z(void* this_cls, void* other_cls);

/* ========================================================================
 *  Helper routines used by Array.set.
 *
 *  These implement the strict JLS type rules for reflective element
 *  assignment: a reference store must satisfy isAssignableFrom for the
 *  component type, and a primitive store must be one of the widening
 *  primitive conversions (JLS §5.1.2). The check for the reference case
 *  consults isInstance first (fast path — exact subtype test) and falls
 *  back to isAssignableFrom on the value's class when isInstance is
 *  conservative.
 * ===================================================================== */

__attribute__((noreturn))
static void array_throw_iae(const char* msg) {
    __jnative_throw_exception(__jnative_construct_exception(
        "vtable_java_lang_IllegalArgumentException", msg));
}

static ReflectionClass* array_class_of(void* array) {
    if (array == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    ReflectionClass* cls = jnative_array_klass(array);
    if (cls == NULL || !__jnative_is_class_mirror(cls)
        || cls->cname == NULL || cls->cname[0] != '[') {
        array_throw_iae("Argument is not an array");
    }
    return cls;
}

static void* array_element_address(void* array, int32_t index, int32_t elem_size) {
    int32_t length = jnative_array_length(array);
    if (index < 0 || index >= length) {
        char msg[96];
        snprintf(msg, sizeof(msg), "Index %d out of bounds for length %d",
                 (int)index, (int)length);
        __jnative_throw_exception(__jnative_construct_exception(
            "vtable_java_lang_ArrayIndexOutOfBoundsException", msg));
    }
    return (char*)jnative_array_data(array) + (size_t)index * (size_t)elem_size;
}

/*
 * Locates the instance "value" field of a boxed-primitive class (or one
 * of its ancestors) and returns its byte offset. Falls back to
 * OBJECT_HEADER_SIZE when the field cannot be located; every supported
 * wrapper class does declare `value`, so the fallback only triggers for
 * a hand-crafted reflective class that shouldn't reach this code.
 */
static int32_t wrapper_value_offset(ReflectionClass* cls) {
    for (ReflectionClass* c = cls; c != NULL; c = c->superclass) {
        if (c->fields == NULL) continue;
        for (ReflectionField** fp = c->fields; *fp != NULL; fp++) {
            ReflectionField* f = *fp;
            if (f->name != NULL && strcmp((const char*)f->name, "value") == 0
                && (f->modifiers & JNATIVE_ACC_STATIC) == 0) {
                return f->offset;
            }
        }
    }
    return OBJECT_HEADER_SIZE;
}

/*
 * Recognises the eight boxed-primitive classes and extracts the primitive
 * value they wrap into `*ival` (integral and boolean types) or `*dval`
 * (float / double). Returns the JVM descriptor code ('Z','B','C','S','I',
 * 'J','F','D') on success, or 0 when `value` is not a wrapper.
 */
static char unbox_primitive(void* value, int64_t* ival, double* dval) {
    ReflectionClass* vcls =
        (ReflectionClass*)__jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(value);
    if (vcls == NULL || vcls->cname == NULL) return 0;
    static const struct { const char* name; char code; } wrappers[] = {
        {"java/lang/Boolean", 'Z'}, {"java/lang/Byte", 'B'},
        {"java/lang/Character", 'C'}, {"java/lang/Short", 'S'},
        {"java/lang/Integer", 'I'}, {"java/lang/Long", 'J'},
        {"java/lang/Float", 'F'}, {"java/lang/Double", 'D'},
        {NULL, 0}
    };
    char code = 0;
    for (int i = 0; wrappers[i].name != NULL; i++) {
        if (strcmp(vcls->cname, wrappers[i].name) == 0) {
            code = wrappers[i].code;
            break;
        }
    }
    if (code == 0) return 0;
    const char* p = (const char*)value + wrapper_value_offset(vcls);
    switch (code) {
        case 'Z': *ival = (*(const uint8_t*)p) != 0; break;
        case 'B': *ival = *(const int8_t*)p; break;
        case 'C': *ival = *(const uint16_t*)p; break;
        case 'S': *ival = *(const int16_t*)p; break;
        case 'I': *ival = *(const int32_t*)p; break;
        case 'J': *ival = *(const int64_t*)p; break;
        case 'F': *dval = *(const float*)p; break;
        case 'D': *dval = *(const double*)p; break;
    }
    return code;
}

/*
 * The JLS §5.1.2 widening primitive conversion table. Returns 1 iff a
 * value of primitive type `src` may be stored, without an explicit cast,
 * into an array whose component type is `dst`.
 */
static int primitive_widens_to(char src, char dst) {
    if (src == dst) return 1;
    switch (src) {
        case 'B': return dst == 'S' || dst == 'I' || dst == 'J' || dst == 'F' || dst == 'D';
        case 'S':
        case 'C': return dst == 'I' || dst == 'J' || dst == 'F' || dst == 'D';
        case 'I': return dst == 'J' || dst == 'F' || dst == 'D';
        case 'J': return dst == 'F' || dst == 'D';
        case 'F': return dst == 'D';
        default:  return 0;
    }
}

/*
 * Resolves the component class of an array descriptor. Returns NULL for a
 * descriptor whose component is a primitive (no class object exists for a
 * primitive component) or for a malformed reference descriptor.
 */
static ReflectionClass* component_class_of(const char* array_name) {
    const char* comp = array_name + 1;
    if (comp[0] == '[') return jnative_class_by_name(comp);
    if (comp[0] != 'L') return NULL;
    size_t len = strlen(comp);
    if (len < 3 || comp[len - 1] != ';') return NULL;
    char name[512];
    if (len - 2 >= sizeof(name)) return NULL;
    memcpy(name, comp + 1, len - 2);
    name[len - 2] = '\0';
    return jnative_class_by_name(name);
}

/*
 * The reference-store type check: a null value is always assignable; an
 * Object[] component accepts anything; otherwise the value's class must
 * be assignable to the component class.
 */
static int reference_store_allowed(const char* array_name, void* value) {
    if (value == NULL) return 1;
    if (strcmp(array_name, "[Ljava/lang/Object;") == 0) return 1;
    ReflectionClass* comp = component_class_of(array_name);
    if (comp == NULL) return 0;
    if (__jnative_fn_java_lang_Class_isInstance__Ljava_lang_Object__Z(comp, value)) return 1;
    void* vcls = __jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(value);
    return __jnative_fn_java_lang_Class_isAssignableFrom__Ljava_lang_Class__Z(comp, vcls);
}

/* ========================================================================
 *  Helper routines used by Array.get.
 *
 *  Boxed primitives are produced through the standard java.lang.<Wrapper>
 *  .valueOf call, which is linked as a weak extern so that an image that
 *  never reaches Array.get does not pull the wrapper classes in. When a
 *  valueOf is not available (unlinked weak symbol) the reflective get
 *  reports the failure rather than fabricating a value.
 * ===================================================================== */

extern void* fn_java_lang_Boolean_valueOf__Z_Ljava_lang_Boolean_(_Bool)        __attribute__((weak));
extern void* fn_java_lang_Byte_valueOf__B_Ljava_lang_Byte_(int8_t)             __attribute__((weak));
extern void* fn_java_lang_Short_valueOf__S_Ljava_lang_Short_(int16_t)          __attribute__((weak));
extern void* fn_java_lang_Character_valueOf__C_Ljava_lang_Character_(uint16_t) __attribute__((weak));
extern void* fn_java_lang_Integer_valueOf__I_Ljava_lang_Integer_(int32_t)      __attribute__((weak));
extern void* fn_java_lang_Long_valueOf__J_Ljava_lang_Long_(int64_t)            __attribute__((weak));
extern void* fn_java_lang_Float_valueOf__F_Ljava_lang_Float_(float)            __attribute__((weak));
extern void* fn_java_lang_Double_valueOf__D_Ljava_lang_Double_(double)         __attribute__((weak));

static void* box_primitive(char type_char, const void* elem) {
    switch (type_char) {
        case 'Z':
            return fn_java_lang_Boolean_valueOf__Z_Ljava_lang_Boolean_
                   ? fn_java_lang_Boolean_valueOf__Z_Ljava_lang_Boolean_(*(const _Bool*)elem)
                   : NULL;
        case 'B':
            return fn_java_lang_Byte_valueOf__B_Ljava_lang_Byte_
                   ? fn_java_lang_Byte_valueOf__B_Ljava_lang_Byte_(*(const int8_t*)elem)
                   : NULL;
        case 'S':
            return fn_java_lang_Short_valueOf__S_Ljava_lang_Short_
                   ? fn_java_lang_Short_valueOf__S_Ljava_lang_Short_(*(const int16_t*)elem)
                   : NULL;
        case 'C':
            return fn_java_lang_Character_valueOf__C_Ljava_lang_Character_
                   ? fn_java_lang_Character_valueOf__C_Ljava_lang_Character_(*(const uint16_t*)elem)
                   : NULL;
        case 'I':
            return fn_java_lang_Integer_valueOf__I_Ljava_lang_Integer_
                   ? fn_java_lang_Integer_valueOf__I_Ljava_lang_Integer_(*(const int32_t*)elem)
                   : NULL;
        case 'J':
            return fn_java_lang_Long_valueOf__J_Ljava_lang_Long_
                   ? fn_java_lang_Long_valueOf__J_Ljava_lang_Long_(*(const int64_t*)elem)
                   : NULL;
        case 'F':
            return fn_java_lang_Float_valueOf__F_Ljava_lang_Float_
                   ? fn_java_lang_Float_valueOf__F_Ljava_lang_Float_(*(const float*)elem)
                   : NULL;
        case 'D':
            return fn_java_lang_Double_valueOf__D_Ljava_lang_Double_
                   ? fn_java_lang_Double_valueOf__D_Ljava_lang_Double_(*(const double*)elem)
                   : NULL;
        default:
            return NULL;
    }
}

void* __jnative_fn_java_lang_reflect_Array_get__Ljava_lang_Object_I_Ljava_lang_Object_(
        void* array, int32_t index) {

    if (array == NULL) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }

    int32_t length = jnative_array_length(array);
    if (index < 0 || index >= length) {
        __jnative_throw_array_index_out_of_bounds();
        return NULL;
    }

    struct ReflectionClass* arr_cls =
        *(struct ReflectionClass**)((char*)array + JAVA_ARR_KLASS_OFFSET);
    const char* desc = (arr_cls != NULL) ? arr_cls->cname : NULL;
    if (desc == NULL || desc[0] != '[') {
        __jnative_throw_exception(NULL);
        return NULL;
    }

    int32_t elem_size = *(int32_t*)((char*)array + JAVA_ARR_ELEM_SIZE_OFFSET);
    char* elem = (char*)jnative_array_data(array) + ((size_t)index * elem_size);

    char type_char = desc[1];

    if (type_char == 'L' || type_char == '[') {
        return *(void**)elem;
    }

    if (type_char == 'Z' || type_char == 'B' || type_char == 'S'
        || type_char == 'C' || type_char == 'I' || type_char == 'J'
        || type_char == 'F' || type_char == 'D') {
        void* boxed = box_primitive(type_char, elem);
        if (boxed == NULL) {
            __jnative_throw_exception(NULL);
        }
        return boxed;
    }

    __jnative_throw_exception(NULL);
    return NULL;
}

void __jnative_fn_java_lang_reflect_Array_set__Ljava_lang_Object_ILjava_lang_Object__V(
        void* array, int32_t index, void* value) {

    ReflectionClass* cls = array_class_of(array);
    char dst = cls->cname[1];

    if (dst == '[' || dst == 'L') {
        if (!reference_store_allowed(cls->cname, value)) {
            array_throw_iae("array element type mismatch");
        }
        void** slot = (void**)array_element_address(array, index, (int32_t)sizeof(void*));
        *slot = value;
        return;
    }

    if (value == NULL) {
        array_throw_iae("argument type mismatch");
    }
    int64_t ival = 0;
    double dval = 0.0;
    char src = unbox_primitive(value, &ival, &dval);
    if (src == 0 || !primitive_widens_to(src, dst)) {
        array_throw_iae("argument type mismatch");
    }
    int src_is_fp = (src == 'F' || src == 'D');

    switch (dst) {
        case 'Z':
            *(uint8_t*)array_element_address(array, index, 1) = (uint8_t)(ival != 0);
            break;
        case 'B':
            *(int8_t*)array_element_address(array, index, 1) = (int8_t)ival;
            break;
        case 'C':
            *(uint16_t*)array_element_address(array, index, 2) = (uint16_t)ival;
            break;
        case 'S':
            *(int16_t*)array_element_address(array, index, 2) = (int16_t)ival;
            break;
        case 'I':
            *(int32_t*)array_element_address(array, index, 4) = (int32_t)ival;
            break;
        case 'J':
            *(int64_t*)array_element_address(array, index, 8) = ival;
            break;
        case 'F':
            *(float*)array_element_address(array, index, 4) =
                src_is_fp ? (float)dval : (float)ival;
            break;
        case 'D':
            *(double*)array_element_address(array, index, 8) =
                src_is_fp ? dval : (double)ival;
            break;
        default:
            array_throw_iae("argument type mismatch");
    }
}
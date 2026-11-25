#include "jnative_platform.h"
#include <stdint.h>

#include "jnative_runtime.h"

static ReflectionParameter* find_parameter_metadata(void* executable,
                                                    int32_t* out_count)
{
    if (out_count != NULL) *out_count = 0;
    if (executable == NULL) return NULL;

    void* object_vtable = *(void**)executable;
    ReflectionClass* method_class = jnative_class_by_name("java/lang/reflect/Method");
    if (method_class != NULL
            && object_vtable == jnative_lookup_class_vtable(method_class)) {
        ReflectionClass* declaring = *(ReflectionClass**)((char*)executable
                + JNATIVE_METHOD_CLAZZ_OFFSET);
        int32_t slot = *(int32_t*)((char*)executable + JNATIVE_METHOD_SLOT_OFFSET);
        if (declaring == NULL || declaring->methods == NULL || slot < 0) return NULL;
        for (ReflectionMethod** p = declaring->methods; *p != NULL; p++) {
            if ((*p)->slot == slot) {
                if (out_count != NULL) *out_count = (*p)->parameter_count;
                return (*p)->parameters;
            }
        }
        return NULL;
    }

    ReflectionClass* constructor_class =
        jnative_class_by_name("java/lang/reflect/Constructor");
    if (constructor_class != NULL
            && object_vtable == jnative_lookup_class_vtable(constructor_class)) {
        ReflectionClass* declaring = *(ReflectionClass**)((char*)executable
                + JNATIVE_CTOR_CLAZZ_OFFSET);
        int32_t slot = *(int32_t*)((char*)executable + JNATIVE_CTOR_SLOT_OFFSET);
        if (declaring == NULL || declaring->constructors == NULL || slot < 0) return NULL;
        for (ReflectionConstructor** p = declaring->constructors; *p != NULL; p++) {
            if ((*p)->slot == slot) {
                if (out_count != NULL) *out_count = (*p)->parameter_count;
                return (*p)->parameters;
            }
        }
    }

    return NULL;
}

/*
 * Executable.getParameters0() supplies real MethodParameters metadata to
 * java.lang.reflect.Executable. Returning NULL deliberately leaves the JDK
 * to synthesize argN values when a class file has no complete attribute.
 */
void* __jnative_fn_java_lang_reflect_Executable_getParameters0____Ljava_lang_reflect_Parameter_(
        void* this_executable)
{
    if (this_executable == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    int32_t parameter_count = 0;
    ReflectionParameter* metadata =
        find_parameter_metadata(this_executable, &parameter_count);
    if (metadata == NULL || parameter_count <= 0) return NULL;

    if (JNATIVE_PARAMETER_NAME_OFFSET < 0
            || JNATIVE_PARAMETER_MODIFIERS_OFFSET < 0
            || JNATIVE_PARAMETER_EXECUTABLE_OFFSET < 0
            || JNATIVE_PARAMETER_INDEX_OFFSET < 0) {
        return NULL;
    }

    ReflectionClass* parameter_class =
        jnative_class_by_name("java/lang/reflect/Parameter");
    if (parameter_class == NULL) return NULL;

    void* result = jnative_ref_array_of_class(NULL, parameter_count,
            "[Ljava/lang/reflect/Parameter;");
    if (result == NULL) {
        __jnative_throw_out_of_memory_error_ctx("Executable.getParameters0");
    }

    void** parameter_slots = (void**)((char*)result + JAVA_ARR_HDR);
    for (int32_t i = 0; i < parameter_count; i++) {
        void* parameter = jnative_alloc_object(parameter_class);
        if (parameter == NULL) {
            __jnative_throw_out_of_memory_error_ctx("Executable.getParameters0");
        }

        void* parameter_name = jnative_string(metadata[i].name);
        __jnative_rc_inc(parameter_name);
        *(void**)((char*)parameter + JNATIVE_PARAMETER_NAME_OFFSET) = parameter_name;
        *(int32_t*)((char*)parameter + JNATIVE_PARAMETER_MODIFIERS_OFFSET) =
            metadata[i].modifiers;
        __jnative_rc_inc(this_executable);
        *(void**)((char*)parameter + JNATIVE_PARAMETER_EXECUTABLE_OFFSET) =
            this_executable;
        *(int32_t*)((char*)parameter + JNATIVE_PARAMETER_INDEX_OFFSET) = i;
        __jnative_rc_inc(parameter);
        parameter_slots[i] = parameter;
    }

    return result;
}

/*
 * private native byte[] getTypeAnnotationBytes0();
 *
 * Returns the raw RuntimeVisibleTypeAnnotations attribute bytes for
 * this executable. The JDK contract is: return null when the executable
 * carries no type annotations. The Java-side callers
 * (Executable.getAnnotatedReturnType0, getAnnotatedParameterTypes,
 * getAnnotatedReceiverType, getAnnotatedExceptionTypes, and
 * TypeAnnotationParser.parseTypeAnnotations) explicitly check for null
 * and substitute an empty TypeAnnotation[] in that case.
 *
 * This runtime does not retain the raw class-file attribute payload
 * attached to a Method/Constructor object -- the class parser
 * (DependencyResolver) discards bytecode attributes after extracting
 * the structural information. Consequently the only semantically
 * correct answer that the caller can act on is "no type annotations",
 * which the JDK represents as null (not an empty array).
 *
 * Returning an empty byte[] would be wrong:
 * TypeAnnotationParser.parseTypeAnnotations would attempt to parse it
 * as a serialised RuntimeVisibleTypeAnnotations structure and fail
 * with an ArrayIndexOutOfBoundsException.
 */
void* __jnative_fn_java_lang_reflect_Executable_getTypeAnnotationBytes0____B(void* this_executable) {
    (void)this_executable;
    return (void*)0;
}

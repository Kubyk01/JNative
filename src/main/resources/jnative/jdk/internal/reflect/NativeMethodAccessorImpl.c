#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "jnative_runtime.h"

#define METHOD_LAYOUT_OLD_CLAZZ            56
#define METHOD_LAYOUT_OLD_NAME             72
#define METHOD_LAYOUT_OLD_RETURN_TYPE      80
#define METHOD_LAYOUT_OLD_PARAMETER_TYPES  88

#define METHOD_LAYOUT_NEW_CLAZZ            16
#define METHOD_LAYOUT_NEW_NAME             32
#define METHOD_LAYOUT_NEW_RETURN_TYPE      40
#define METHOD_LAYOUT_NEW_PARAMETER_TYPES  48

/* =========================================================================
 *  Registry helpers
 * ========================================================================= */

static int is_registered_reflection_class(void* candidate) {
    if (candidate == NULL) return 0;
    if (reflect_all_classes == NULL) return 0;
    ReflectionClass** pp = reflect_all_classes;
    while (*pp != NULL) {
        if ((void*)(*pp) == candidate) return 1;
        pp++;
    }
    return 0;
}

static int append_class_descriptor(ReflectionClass* cls,
                                   char* buf, size_t buf_size)
{
    if (cls == NULL || cls->cname == NULL) return -1;
    const char* name = cls->cname;

    /* Primitive names. */
    char code = 0;
    if (strcmp(name, "void") == 0)         code = 'V';
    else if (strcmp(name, "boolean") == 0) code = 'Z';
    else if (strcmp(name, "byte") == 0)    code = 'B';
    else if (strcmp(name, "char") == 0)    code = 'C';
    else if (strcmp(name, "short") == 0)   code = 'S';
    else if (strcmp(name, "int") == 0)     code = 'I';
    else if (strcmp(name, "long") == 0)    code = 'J';
    else if (strcmp(name, "float") == 0)   code = 'F';
    else if (strcmp(name, "double") == 0)  code = 'D';

    if (code != 0) {
        if (buf_size < 2) return -1;
        buf[0] = code;
        buf[1] = '\0';
        return 1;
    }

    /* Array descriptors are stored verbatim in the class's name. */
    if (name[0] == '[') {
        size_t n = strlen(name);
        if (n + 1 > buf_size) return -1;
        memcpy(buf, name, n + 1);
        return (int)n;
    }

    /* Ordinary reference type: wrap in L...; with '/' separators
     * unchanged, which is exactly the class-file form. */
    size_t n = strlen(name);
    if (n + 3 > buf_size) return -1;
    buf[0] = 'L';
    memcpy(buf + 1, name, n);
    buf[n + 1] = ';';
    buf[n + 2] = '\0';
    return (int)(n + 2);
}

static int build_parameter_list(void* parameter_types_array,
                                char* buf, size_t buf_size)
{
    size_t pos = 0;

    if (pos + 1 >= buf_size) return -1;
    buf[pos++] = '(';

    if (parameter_types_array != NULL) {
        int32_t count = jnative_array_length(parameter_types_array);
        if (count < 0) return -1;
        void** slots = (void**)jnative_array_data(parameter_types_array);
        for (int32_t i = 0; i < count; i++) {
            ReflectionClass* p = (ReflectionClass*)slots[i];
            int written = append_class_descriptor(p, buf + pos, buf_size - pos);
            if (written < 0) return -1;
            pos += (size_t)written;
        }
    }

    buf[pos] = '\0';
    return (int)pos;
}

static int build_method_descriptor(void* parameter_types_array,
                                   ReflectionClass* return_class,
                                   char* buf, size_t buf_size)
{
    int written = build_parameter_list(parameter_types_array, buf, buf_size);
    if (written < 0) return -1;
    size_t pos = (size_t)written;

    if (pos + 2 >= buf_size) return -1;
    buf[pos++] = ')';

    int ret_written = append_class_descriptor(return_class,
                                              buf + pos,
                                              buf_size - pos);
    if (ret_written < 0) return -1;
    pos += (size_t)ret_written;

    buf[pos] = '\0';
    return (int)pos;
}

static int build_constructor_descriptor(void* parameter_types_array,
                                        char* buf, size_t buf_size)
{
    int written = build_parameter_list(parameter_types_array, buf, buf_size);
    if (written < 0) return -1;
    size_t pos = (size_t)written;

    if (pos + 2 >= buf_size) return -1;
    buf[pos++] = ')';
    buf[pos++] = 'V';
    buf[pos] = '\0';
    return (int)pos;
}

static ReflectionMethod* lookup_reflection_method(
        ReflectionClass* cls,
        const char* name,
        const char* descriptor)
{
    if (cls == NULL || name == NULL || descriptor == NULL) return NULL;
    if (cls->methods == NULL) return NULL;

    ReflectionMethod** mp = cls->methods;
    while (*mp != NULL) {
        ReflectionMethod* m = *mp;
        const char* mname = (const char*)m->name;
        const char* mdesc = (const char*)m->descriptor;
        if (mname != NULL && mdesc != NULL
            && strcmp(mname, name) == 0
            && strcmp(mdesc, descriptor) == 0) {
            return m;
        }
        mp++;
    }
    return NULL;
}

static ReflectionConstructor* lookup_reflection_constructor(
        ReflectionClass* cls,
        const char* descriptor)
{
    if (cls == NULL || descriptor == NULL) return NULL;
    if (cls->constructors == NULL) return NULL;

    ReflectionConstructor** cp =
        (ReflectionConstructor**)cls->constructors;
    while (*cp != NULL) {
        ReflectionConstructor* c = *cp;
        const char* cdesc = (const char*)c->descriptor;
        if (cdesc != NULL && strcmp(cdesc, descriptor) == 0) {
            return c;
        }
        cp++;
    }
    return NULL;
}

struct reflect_layout {
    int clazz_offset;
    int name_offset;
    int return_type_offset;
    int parameter_types_offset;
};

static int detect_reflect_layout(void* obj, struct reflect_layout* out) {
    void* old_clazz = *(void**)((char*)obj + METHOD_LAYOUT_OLD_CLAZZ);
    void* new_clazz = *(void**)((char*)obj + METHOD_LAYOUT_NEW_CLAZZ);

    int old_ok = is_registered_reflection_class(old_clazz);
    int new_ok = is_registered_reflection_class(new_clazz);

    if (old_ok && !new_ok) {
        out->clazz_offset           = METHOD_LAYOUT_OLD_CLAZZ;
        out->name_offset            = METHOD_LAYOUT_OLD_NAME;
        out->return_type_offset     = METHOD_LAYOUT_OLD_RETURN_TYPE;
        out->parameter_types_offset = METHOD_LAYOUT_OLD_PARAMETER_TYPES;
        return 1;
    }
    if (new_ok && !old_ok) {
        out->clazz_offset           = METHOD_LAYOUT_NEW_CLAZZ;
        out->name_offset            = METHOD_LAYOUT_NEW_NAME;
        out->return_type_offset     = METHOD_LAYOUT_NEW_RETURN_TYPE;
        out->parameter_types_offset = METHOD_LAYOUT_NEW_PARAMETER_TYPES;
        return 1;
    }
    if (old_ok && new_ok) {
        out->clazz_offset           = METHOD_LAYOUT_NEW_CLAZZ;
        out->name_offset            = METHOD_LAYOUT_NEW_NAME;
        out->return_type_offset     = METHOD_LAYOUT_NEW_RETURN_TYPE;
        out->parameter_types_offset = METHOD_LAYOUT_NEW_PARAMETER_TYPES;
        return 1;
    }
    return 0;
}

void* __jnative_fn_jdk_internal_reflect_NativeMethodAccessorImpl_invoke0__Ljava_lang_reflect_Method_Ljava_lang_Object__Ljava_lang_Object__Ljava_lang_Object_(
        void* method_obj, void* obj, void* args_array)
{
    if (method_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    struct reflect_layout layout;
    if (!detect_reflect_layout(method_obj, &layout)) {
        /*
         * The Method object does not present a recognised field layout:
         * either it has not been initialised, or it was built by a JDK
         * whose hierarchy this file does not model. Refuse to guess.
         */
        __jnative_throw_null_pointer_exception();
    }

    void* clazz = *(void**)((char*)method_obj + layout.clazz_offset);
    void* name_str = *(void**)((char*)method_obj + layout.name_offset);
    void* return_class =
        *(void**)((char*)method_obj + layout.return_type_offset);
    void* parameter_types_array =
        *(void**)((char*)method_obj + layout.parameter_types_offset);

    if (clazz == NULL || name_str == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    ReflectionClass* rc = (ReflectionClass*)clazz;

    const char* name = __jnative_read_string_bytes(name_str, NULL);
    if (name == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    char descriptor[1024];
    if (build_method_descriptor(parameter_types_array,
                                (ReflectionClass*)return_class,
                                descriptor, sizeof(descriptor)) < 0) {
        __jnative_throw_exception(NULL);
    }

    ReflectionMethod* m =
        lookup_reflection_method(rc, name, descriptor);

    if (m == NULL || m->adaptor == NULL) {
        __jnative_throw_exception(NULL);
    }

    typedef void* (*adaptor_t)(void*, void**);
    adaptor_t fn = (adaptor_t)m->adaptor;

    void** args_payload = NULL;
    if (args_array != NULL) {
        args_payload = (void**)((char*)args_array + JAVA_ARR_HDR);
    }

    return fn(obj, args_payload);
}

void* __jnative_fn_jdk_internal_reflect_NativeConstructorAccessorImpl_newInstance0__Ljava_lang_reflect_Constructor__Ljava_lang_Object__Ljava_lang_Object_(
        void* constructor_obj, void* args_array)
{
    if (constructor_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    struct reflect_layout layout;
    if (!detect_reflect_layout(constructor_obj, &layout)) {
        __jnative_throw_null_pointer_exception();
    }

    void* clazz = *(void**)((char*)constructor_obj + layout.clazz_offset);
    void* parameter_types_array =
        *(void**)((char*)constructor_obj + layout.parameter_types_offset);

    if (clazz == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    ReflectionClass* rc = (ReflectionClass*)clazz;

    char descriptor[1024];
    if (build_constructor_descriptor(parameter_types_array,
                                     descriptor, sizeof(descriptor)) < 0) {
        __jnative_throw_exception(NULL);
    }

    ReflectionConstructor* c =
        lookup_reflection_constructor(rc, descriptor);

    if (c == NULL || c->adaptor == NULL) {
        __jnative_throw_exception(NULL);
    }

    typedef void* (*adaptor_t)(void**);
    adaptor_t fn = (adaptor_t)c->adaptor;

    void** args_payload = NULL;
    if (args_array != NULL) {
        args_payload = (void**)((char*)args_array + JAVA_ARR_HDR);
    }

    return fn(args_payload);
}
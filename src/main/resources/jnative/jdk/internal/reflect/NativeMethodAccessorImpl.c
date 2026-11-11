#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <setjmp.h>
#include "jnative_runtime.h"

/* =========================================================================
 *  Registry helpers
 * ========================================================================= */

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



extern void* __jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(void* obj);
extern void* __jnative_get_exception_object(void);
extern int   __jnative_fn_java_lang_Class_isInstance__Ljava_lang_Object__Z(void* cls, void* obj);

extern void* fn_java_lang_Boolean_valueOf__Z_Ljava_lang_Boolean_(_Bool)        __attribute__((weak));
extern void* fn_java_lang_Byte_valueOf__B_Ljava_lang_Byte_(int8_t)             __attribute__((weak));
extern void* fn_java_lang_Short_valueOf__S_Ljava_lang_Short_(int16_t)          __attribute__((weak));
extern void* fn_java_lang_Character_valueOf__C_Ljava_lang_Character_(uint16_t) __attribute__((weak));
extern void* fn_java_lang_Integer_valueOf__I_Ljava_lang_Integer_(int32_t)      __attribute__((weak));
extern void* fn_java_lang_Long_valueOf__J_Ljava_lang_Long_(int64_t)            __attribute__((weak));
extern void* fn_java_lang_Float_valueOf__F_Ljava_lang_Float_(float)            __attribute__((weak));
extern void* fn_java_lang_Double_valueOf__D_Ljava_lang_Double_(double)         __attribute__((weak));
extern void fn_java_lang_reflect_InvocationTargetException__init___Ljava_lang_Throwable__V(void*, void*) __attribute__((weak));

#define REFLECT_BOXED_VALUE_OFFSET 8
#define REFLECT_ACC_PRIVATE 0x0002
#define REFLECT_ACC_STATIC  0x0008

JNATIVE_NORETURN static void reflect_throw(const char* vtable_symbol, const char* msg)
{
    __jnative_throw_exception(__jnative_construct_exception(vtable_symbol, msg));
}

static char reflect_primitive_code(ReflectionClass* cls)
{
    if (cls == NULL || cls->cname == NULL) return 0;
    const char* n = cls->cname;
    if (strcmp(n, "boolean") == 0) return 'Z';
    if (strcmp(n, "byte") == 0)    return 'B';
    if (strcmp(n, "char") == 0)    return 'C';
    if (strcmp(n, "short") == 0)   return 'S';
    if (strcmp(n, "int") == 0)     return 'I';
    if (strcmp(n, "long") == 0)    return 'J';
    if (strcmp(n, "float") == 0)   return 'F';
    if (strcmp(n, "double") == 0)  return 'D';
    if (strcmp(n, "void") == 0)    return 'V';
    return 0;
}

static char reflect_wrapper_code(void* boxed)
{
    ReflectionClass* cls =
        (ReflectionClass*)__jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(boxed);
    if (cls == NULL || cls->cname == NULL) return 0;
    const char* n = cls->cname;
    if (strcmp(n, "java/lang/Boolean") == 0)   return 'Z';
    if (strcmp(n, "java/lang/Byte") == 0)      return 'B';
    if (strcmp(n, "java/lang/Character") == 0) return 'C';
    if (strcmp(n, "java/lang/Short") == 0)     return 'S';
    if (strcmp(n, "java/lang/Integer") == 0)   return 'I';
    if (strcmp(n, "java/lang/Long") == 0)      return 'J';
    if (strcmp(n, "java/lang/Float") == 0)     return 'F';
    if (strcmp(n, "java/lang/Double") == 0)    return 'D';
    return 0;
}

static int reflect_read_integral(char src, const char* p, int64_t* out)
{
    switch (src) {
        case 'B': *out = *(const int8_t*)p;   return 1;
        case 'S': *out = *(const int16_t*)p;  return 1;
        case 'C': *out = *(const uint16_t*)p; return 1;
        case 'I': *out = *(const int32_t*)p;  return 1;
        case 'J': *out = *(const int64_t*)p;  return 1;
        default:  return 0;
    }
}

static int reflect_unbox_widen(void* boxed, char dst, void** slot)
{
    if (boxed == NULL) return 0;
    char src = reflect_wrapper_code(boxed);
    if (src == 0) return 0;
    const char* p = (const char*)boxed + REFLECT_BOXED_VALUE_OFFSET;
    int64_t iv = 0;
    int integral = reflect_read_integral(src, p, &iv);
    *slot = NULL;
    switch (dst) {
        case 'Z':
            if (src != 'Z') return 0;
            *(int8_t*)slot = (int8_t)(*(const int8_t*)p != 0);
            return 1;
        case 'B':
            if (src != 'B') return 0;
            *(int8_t*)slot = (int8_t)iv;
            return 1;
        case 'C':
            if (src != 'C') return 0;
            *(uint16_t*)slot = (uint16_t)iv;
            return 1;
        case 'S':
            if (src != 'S' && src != 'B') return 0;
            *(int16_t*)slot = (int16_t)iv;
            return 1;
        case 'I':
            if (src != 'B' && src != 'S' && src != 'C' && src != 'I') return 0;
            *(int32_t*)slot = (int32_t)iv;
            return 1;
        case 'J':
            if (!integral) return 0;
            *(int64_t*)slot = iv;
            return 1;
        case 'F':
            if (integral)        *(float*)slot = (float)iv;
            else if (src == 'F') *(float*)slot = *(const float*)p;
            else return 0;
            return 1;
        case 'D':
            if (integral)        *(double*)slot = (double)iv;
            else if (src == 'F') *(double*)slot = (double)*(const float*)p;
            else if (src == 'D') *(double*)slot = *(const double*)p;
            else return 0;
            return 1;
        default:
            return 0;
    }
}

static void* reflect_box(char code, const void* mem)
{
    void* r = NULL;
    switch (code) {
        case 'Z': if (fn_java_lang_Boolean_valueOf__Z_Ljava_lang_Boolean_)
                      r = fn_java_lang_Boolean_valueOf__Z_Ljava_lang_Boolean_(*(const _Bool*)mem);
                  break;
        case 'B': if (fn_java_lang_Byte_valueOf__B_Ljava_lang_Byte_)
                      r = fn_java_lang_Byte_valueOf__B_Ljava_lang_Byte_(*(const int8_t*)mem);
                  break;
        case 'S': if (fn_java_lang_Short_valueOf__S_Ljava_lang_Short_)
                      r = fn_java_lang_Short_valueOf__S_Ljava_lang_Short_(*(const int16_t*)mem);
                  break;
        case 'C': if (fn_java_lang_Character_valueOf__C_Ljava_lang_Character_)
                      r = fn_java_lang_Character_valueOf__C_Ljava_lang_Character_(*(const uint16_t*)mem);
                  break;
        case 'I': if (fn_java_lang_Integer_valueOf__I_Ljava_lang_Integer_)
                      r = fn_java_lang_Integer_valueOf__I_Ljava_lang_Integer_(*(const int32_t*)mem);
                  break;
        case 'J': if (fn_java_lang_Long_valueOf__J_Ljava_lang_Long_)
                      r = fn_java_lang_Long_valueOf__J_Ljava_lang_Long_(*(const int64_t*)mem);
                  break;
        case 'F': if (fn_java_lang_Float_valueOf__F_Ljava_lang_Float_)
                      r = fn_java_lang_Float_valueOf__F_Ljava_lang_Float_(*(const float*)mem);
                  break;
        case 'D': if (fn_java_lang_Double_valueOf__D_Ljava_lang_Double_)
                      r = fn_java_lang_Double_valueOf__D_Ljava_lang_Double_(*(const double*)mem);
                  break;
        default: break;
    }
    if (r == NULL) {
        reflect_throw("vtable_java_lang_InternalError",
                      "primitive wrapper valueOf is not available");
    }
    return r;
}

static void** reflect_prepare_args(void* parameter_types_array, void* args_array)
{
    int32_t expected = parameter_types_array != NULL
        ? jnative_array_length(parameter_types_array) : 0;
    int32_t actual = args_array != NULL ? jnative_array_length(args_array) : 0;
    if (expected != actual) {
        char msg[96];
        snprintf(msg, sizeof(msg), "wrong number of arguments: %d expected: %d",
                 (int)actual, (int)expected);
        reflect_throw("vtable_java_lang_IllegalArgumentException", msg);
    }
    if (expected == 0) return NULL;

    void** slots = (void**)calloc((size_t)expected, sizeof(void*));
    if (slots == NULL) __jnative_throw_out_of_memory_error();
    void** types = (void**)jnative_array_data(parameter_types_array);
    void** values = (void**)jnative_array_data(args_array);
    for (int32_t i = 0; i < expected; i++) {
        char code = reflect_primitive_code((ReflectionClass*)types[i]);
        if (code == 0) {
            slots[i] = values[i];
        } else if (!reflect_unbox_widen(values[i], code, &slots[i])) {
            free(slots);
            reflect_throw("vtable_java_lang_IllegalArgumentException",
                          "argument type mismatch");
        }
    }
    return slots;
}

JNATIVE_NORETURN static void reflect_throw_invocation_target(void* target)
{
    ReflectionClass* cls =
        jnative_class_by_name("java/lang/reflect/InvocationTargetException");
    void* ite = NULL;
    if (cls != NULL
        && fn_java_lang_reflect_InvocationTargetException__init___Ljava_lang_Throwable__V != NULL) {
        ite = jnative_alloc_object(cls);
    }
    if (ite == NULL) {
        __jnative_throw_exception(target);
    }
    fn_java_lang_reflect_InvocationTargetException__init___Ljava_lang_Throwable__V(ite, target);
    __jnative_throw_exception(ite);
}

typedef void* (*reflect_method_adaptor_t)(void*, void**);
typedef void* (*reflect_ctor_adaptor_t)(void**);

static void* reflect_call_method(reflect_method_adaptor_t fn, void* obj, void** args)
{
    jmp_buf jb;
    void* volatile result = NULL;
    if (setjmp(jb) == 0) {
        __jnative_push_catch(&jb, NULL);
        result = fn(obj, args);
        __jnative_pop_catch();
        free(args);
        return result;
    }
    __jnative_pop_catch();
    free(args);
    reflect_throw_invocation_target(__jnative_get_exception_object());
}

static void* reflect_call_ctor(reflect_ctor_adaptor_t fn, void** args)
{
    jmp_buf jb;
    void* volatile result = NULL;
    if (setjmp(jb) == 0) {
        __jnative_push_catch(&jb, NULL);
        result = fn(args);
        __jnative_pop_catch();
        free(args);
        return result;
    }
    __jnative_pop_catch();
    free(args);
    reflect_throw_invocation_target(__jnative_get_exception_object());
}

static ReflectionMethod* reflect_resolve_virtual(void* obj,
                                                 const char* name,
                                                 const char* descriptor)
{
    ReflectionClass* cls =
        (ReflectionClass*)__jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(obj);
    while (cls != NULL) {
        ReflectionMethod* m = lookup_reflection_method(cls, name, descriptor);
        if (m != NULL && m->adaptor != NULL
            && (m->modifiers & (REFLECT_ACC_STATIC | REFLECT_ACC_PRIVATE)) == 0) {
            return m;
        }
        cls = cls->superclass;
    }
    return NULL;
}

void* __jnative_fn_jdk_internal_reflect_NativeMethodAccessorImpl_invoke0__Ljava_lang_reflect_Method_Ljava_lang_Object__Ljava_lang_Object__Ljava_lang_Object_(
        void* method_obj, void* obj, void* args_array)
{
    if (method_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    void* clazz = *(void**)((char*)method_obj + JNATIVE_METHOD_CLAZZ_OFFSET);
    void* name_str = *(void**)((char*)method_obj + JNATIVE_METHOD_NAME_OFFSET);
    void* return_class =
        *(void**)((char*)method_obj + JNATIVE_METHOD_RETURN_TYPE_OFFSET);
    void* parameter_types_array =
        *(void**)((char*)method_obj + JNATIVE_METHOD_PARAM_TYPES_OFFSET);
    int32_t modifiers =
        *(int32_t*)((char*)method_obj + JNATIVE_METHOD_MODIFIERS_OFFSET);

    if (clazz == NULL || name_str == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    const char* name = __jnative_read_string_bytes(name_str, NULL);
    if (name == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    char descriptor[1024];
    if (build_method_descriptor(parameter_types_array,
                                (ReflectionClass*)return_class,
                                descriptor, sizeof(descriptor)) < 0) {
        reflect_throw("vtable_java_lang_InternalError", "method descriptor too long");
    }

    int is_static = (modifiers & REFLECT_ACC_STATIC) != 0;
    if (!is_static && obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    /* DirectMethodHandleAccessor.checkReceiver: without it a mismatched
     * receiver is dispatched anyway and crashes inside the target (e.g. H2
     * invoking com.sun.management.OperatingSystemMXBean methods on the base
     * java.lang.management OS bean). */
    if (!is_static
        && !__jnative_fn_java_lang_Class_isInstance__Ljava_lang_Object__Z(clazz, obj)) {
        reflect_throw("vtable_java_lang_IllegalArgumentException",
                      "object is not an instance of declaring class");
    }

    ReflectionMethod* m = NULL;
    if (!is_static && (modifiers & REFLECT_ACC_PRIVATE) == 0) {
        m = reflect_resolve_virtual(obj, name, descriptor);
    }
    if (m == NULL) {
        m = lookup_reflection_method((ReflectionClass*)clazz, name, descriptor);
    }
    if (m == NULL || m->adaptor == NULL) {
        char msg[1400];
        const char* cname = ((ReflectionClass*)clazz)->cname;
        snprintf(msg, sizeof(msg), "no reflective entry point for method %s.%s%s",
                 cname != NULL ? cname : "?", name, descriptor);
        reflect_throw("vtable_java_lang_InternalError", msg);
    }

    void** args = reflect_prepare_args(parameter_types_array, args_array);
    void* raw = reflect_call_method((reflect_method_adaptor_t)m->adaptor,
                                    is_static ? NULL : obj, args);

    char ret_code = reflect_primitive_code((ReflectionClass*)return_class);
    if (ret_code == 'V') return NULL;
    if (ret_code == 0) return raw;
    void* boxed = reflect_box(ret_code, raw);
    free(raw);
    return boxed;
}

/* --------------------------------------------------------------------------
 * NativeConstructorAccessorImpl.newInstance0 — implementation.
 * --------------------------------------------------------------------------
 *
 * The public mangled symbol
 *
 *     __jnative_fn_jdk_internal_reflect_NativeConstructorAccessorImpl_newInstance0__…
 *
 * is defined by the forwarding shim in NativeConstructorAccessorImpl.c.
 * That shim has three responsibilities:
 *
 *   1. It satisfies BytecodeToIr.findJNativeFileOwner and
 *      Compiler.hasNativeSupport, both of which decide "does class C
 *      have a JNative implementation?" by probing for a C file whose
 *      name matches <package>/<SimpleClassName>.c.
 *
 *   2. It keeps the single definition of that symbol unambiguous —
 *      there is exactly one function whose name is the mangled form.
 *
 *   3. It keeps the actual logic in this file, where it can share the
 *      descriptor/lookup helpers (build_constructor_descriptor,
 *      lookup_reflection_constructor, append_class_descriptor,
 *      build_parameter_list) with invoke0. Splitting the helpers across
 *      two files would mean duplicating ~150 lines of layout code, which
 *      is worse than the one-function shim.
 *
 * The implementation itself is unchanged; only the function's external
 * symbol name has changed, from the mangled form to a plain
 * __jnative_impl_* identifier that no tool treats specially. The
 * forwarding shim in NativeConstructorAccessorImpl.c is the only
 * caller, and DirectConstructorHandleAccessor.c forwards through the
 * same shim, so the whole delegation chain (NativeConstructorAccessorImpl
 * -> DirectConstructorHandleAccessor -> this implementation) remains
 * intact.
 *
 * The consequences of the previous arrangement — where the mangled
 * symbol was defined here, in a file named after a sibling class — were:
 *
 *   - both the reachability probe and the JNI fallback thought
 *     NativeConstructorAccessorImpl had no JNative implementation;
 *
 *   - BytecodeToIr produced a JNI symbol
 *     Java_jdk_internal_reflect_NativeConstructorAccessorImpl_newInstance0;
 *
 *   - the compiler's JNI-native discovery found no C file to satisfy
 *     it, so the link step failed with:
 *
 *         ld.lld: error: undefined symbol:
 *             Java_jdk_internal_reflect_NativeConstructorAccessorImpl_newInstance0
 *
 * Renaming the implementation and providing the shim in the correct
 * file keeps everything together without that mismatch.
 * ------------------------------------------------------------------------ */
void* __jnative_impl_reflect_NativeConstructorAccessorImpl_newInstance0__(
        void* constructor_obj, void* args_array)
{
    if (constructor_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    void* clazz = *(void**)((char*)constructor_obj + JNATIVE_CTOR_CLAZZ_OFFSET);
    void* parameter_types_array =
        *(void**)((char*)constructor_obj + JNATIVE_CTOR_PARAM_TYPES_OFFSET);

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
        reflect_throw("vtable_java_lang_InternalError",
                      "no reflective entry point for constructor");
    }

    void** args = reflect_prepare_args(parameter_types_array, args_array);
    return reflect_call_ctor((reflect_ctor_adaptor_t)c->adaptor, args);
}
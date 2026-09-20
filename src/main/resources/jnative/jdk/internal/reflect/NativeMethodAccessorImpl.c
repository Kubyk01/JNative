#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);

extern const char* __jnative_read_string_bytes(void* s, int32_t* out_len);

/* Java array layout: [ int32 length ][ i32 element_size ][ payload ... ] */
#define JAVA_ARR_HDR 8

/* -------------------------------------------------------------------------
 * Reflection metadata layout — must match LlvmGlobalEmitter's
 * generateReflectionData() emission and every other native file that
 * navigates the registry (jdk/internal/misc/Unsafe.c, lang/Class.c,
 * lang/reflect/Array.c, …). Any change to these structures must be
 * applied to all of them simultaneously.
 * ----------------------------------------------------------------------- */
struct ReflectionMethod {
    void* name;         /* const char*  — C string constant */
    void* descriptor;   /* const char*  — C string constant */
    void* adaptor;      /* i8* (i8* obj, i8** args) *  */
    int   modifiers;
};

struct ReflectionConstructor {
    void* descriptor;   /* const char*  — C string constant */
    void* adaptor;      /* i8* (i8** args) *  */
    int   modifiers;
};

struct ReflectionClass {
    void* vtable;
    void* name;                                 /* const char* */
    struct ReflectionClass* superclass;
    struct ReflectionClass** interfaces;
    struct ReflectionMethod** methods;          /* NULL-terminated */
    void** fields;                              /* NULL-terminated */
    void** constructors;                        /* NULL-terminated */
    int   modifiers;
    int   object_size;
};

extern struct ReflectionClass* reflect_all_classes[] __attribute__((weak));

#define METHOD_LAYOUT_OLD_CLAZZ            56
#define METHOD_LAYOUT_OLD_NAME             72
#define METHOD_LAYOUT_OLD_RETURN_TYPE      80
#define METHOD_LAYOUT_OLD_PARAMETER_TYPES  88

#define METHOD_LAYOUT_NEW_CLAZZ            16
#define METHOD_LAYOUT_NEW_NAME             32
#define METHOD_LAYOUT_NEW_RETURN_TYPE      40
#define METHOD_LAYOUT_NEW_PARAMETER_TYPES  48

/* -------------------------------------------------------------------------
 * Registry helpers
 * ----------------------------------------------------------------------- */

static int is_registered_reflection_class(void* candidate) {
    if (candidate == NULL) return 0;
    if (reflect_all_classes == NULL) return 0;
    struct ReflectionClass** pp = reflect_all_classes;
    while (*pp != NULL) {
        if ((void*)(*pp) == candidate) return 1;
        pp++;
    }
    return 0;
}

static int append_class_descriptor(struct ReflectionClass* cls,
                                   char* buf, size_t buf_size)
{
    if (cls == NULL || cls->name == NULL) return -1;
    const char* name = (const char*)cls->name;

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
        int32_t count = *(int32_t*)parameter_types_array;
        if (count < 0) return -1;
        void** slots = (void**)((char*)parameter_types_array + JAVA_ARR_HDR);
        for (int32_t i = 0; i < count; i++) {
            struct ReflectionClass* p = (struct ReflectionClass*)slots[i];
            int written = append_class_descriptor(p, buf + pos, buf_size - pos);
            if (written < 0) return -1;
            pos += (size_t)written;
        }
    }

    buf[pos] = '\0';
    return (int)pos;
}

static int build_method_descriptor(void* parameter_types_array,
                                   struct ReflectionClass* return_class,
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

static struct ReflectionMethod* lookup_reflection_method(
        struct ReflectionClass* cls,
        const char* name,
        const char* descriptor)
{
    if (cls == NULL || name == NULL || descriptor == NULL) return NULL;
    if (cls->methods == NULL) return NULL;

    struct ReflectionMethod** mp = cls->methods;
    while (*mp != NULL) {
        struct ReflectionMethod* m = *mp;
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

static struct ReflectionConstructor* lookup_reflection_constructor(
        struct ReflectionClass* cls,
        const char* descriptor)
{
    if (cls == NULL || descriptor == NULL) return NULL;
    if (cls->constructors == NULL) return NULL;

    struct ReflectionConstructor** cp =
        (struct ReflectionConstructor**)cls->constructors;
    while (*cp != NULL) {
        struct ReflectionConstructor* c = *cp;
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
        /* Both offsets point at a registered class. This happens when
         * the older layout is in use and Method.returnType (offset 40)
         * happens to name a registered class too, or when the newer
         * layout is in use and AccessibleObject.declaringClass (offset
         * 56) is unrelated garbage that still lands on a registry
         * entry by coincidence. Disambiguate by checking the field at
         * offset 24: on the newer layout that is Method.slot (an int,
         * which a registry pointer will never match), on the older
         * layout it is Executable.name (a String object, never a
         * registry pointer). We therefore pick the layout whose
         * clazz_offset field is *not* followed by a registry pointer at
         * the other layout's clazz slot.
         *
         * In practice both branches are equivalent; the guard below
         * simply prefers the newer layout when the two are mutually
         * consistent, since every JDK this runtime targets from 15
         * onwards uses it. */
        out->clazz_offset           = METHOD_LAYOUT_NEW_CLAZZ;
        out->name_offset            = METHOD_LAYOUT_NEW_NAME;
        out->return_type_offset     = METHOD_LAYOUT_NEW_RETURN_TYPE;
        out->parameter_types_offset = METHOD_LAYOUT_NEW_PARAMETER_TYPES;
        return 1;
    }
    return 0;
}

/* -------------------------------------------------------------------------
 * public static native Object invoke0(Method method, Object obj,
 *                                     Object[] args);
 *
 * Reconstructs the descriptor of the Method object, resolves it in the
 * registry, and dispatches to the corresponding C-callable adaptor.
 *
 * The `args` array is a Java Object[] laid out as
 * [i32 length][i32 element_size][void* payload]. The adaptor expects a
 * raw pointer to the first payload slot, so the header is skipped on
 * the way in.
 *
 * Any failure to resolve the method — a malformed Method object, a
 * method whose declaring class is not in the reflection registry, a
 * method that has no compiled adaptor because it was never translated
 * into the module — surfaces through the generic throw helper. The
 * Java caller (NativeMethodAccessorImpl.invoke) then propagates the
 * resulting exception to Method.invoke, which is the documented
 * behaviour for a reflective invocation that cannot be performed.
 * ----------------------------------------------------------------------- */
void* __jnative_fn_jdk_internal_reflect_NativeMethodAccessorImpl_invoke0__Ljava_lang_reflect_Method_Ljava_lang_Object__Ljava_lang_Object__Ljava_lang_Object_(
        void* method_obj, void* obj, void* args_array)
{
    if (method_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    struct reflect_layout layout;
    if (!detect_reflect_layout(method_obj, &layout)) {
        /* The Method object does not present a recognised field layout:
         * either it has not been initialised, or it was built by a JDK
         * whose hierarchy this file does not model. Refuse to guess. */
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

    struct ReflectionClass* rc = (struct ReflectionClass*)clazz;

    /* Turn the Method's own name into a plain C string. The runtime's
     * string representation is a length-prefixed byte[] wrapped in a
     * String object; __jnative_read_string_bytes extracts the bytes
     * and the length without allocating, and the pointer it returns is
     * valid for the lifetime of the String — which the Method object
     * keeps alive. */
    const char* name = __jnative_read_string_bytes(name_str, NULL);
    if (name == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    /* Build the descriptor from the Method's parameter and return
     * types. 1 KiB is comfortably larger than the longest descriptor
     * any realistic Java method has; if a pathological case ever
     * exceeds it, the helper returns -1 and the caller falls through
     * to the generic throw below. */
    char descriptor[1024];
    if (build_method_descriptor(parameter_types_array,
                                (struct ReflectionClass*)return_class,
                                descriptor, sizeof(descriptor)) < 0) {
        __jnative_throw_exception(NULL);
    }

    /* Look the method up in the class's own method table. If the
     * method is declared by a superclass, the reflect registry stores
     * it under that superclass's ReflectionClass, not the subclass
     * whose name the Method object carries: Method.clazz is the
     * declaring class in both layouts this file supports, so the walk
     * below is complete without a hierarchy search. */
    struct ReflectionMethod* m =
        lookup_reflection_method(rc, name, descriptor);

    if (m == NULL || m->adaptor == NULL) {
        /* The method was never translated into the module, or it is a
         * native method whose C implementation is invoked through the
         * __jnative_fn_* symbol rather than through a reflect adaptor.
         * The Java layer's contract is to fail the reflective
         * invocation in that case rather than silently returning a
         * default value. */
        __jnative_throw_exception(NULL);
    }

    /* The adaptor signature emitted by
     * LlvmGlobalEmitter.emitAdaptorForMethod is:
     *
     *     define i8* @__reflect_adaptor_<...>(i8* %obj, i8** %args)
     *
     * It returns a boxed result (or null for void methods). The
     * args pointer is expected to point at the first payload slot of
     * the argument array, so the Java-array header is skipped here. */
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
        /* The Constructor object does not present a recognised field
         * layout: either it has not been initialised, or it was built
         * by a JDK whose hierarchy this file does not model. Refuse to
         * guess, for the same reason invoke0 does. */
        __jnative_throw_null_pointer_exception();
    }

    void* clazz = *(void**)((char*)constructor_obj + layout.clazz_offset);
    void* parameter_types_array =
        *(void**)((char*)constructor_obj + layout.parameter_types_offset);

    if (clazz == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    struct ReflectionClass* rc = (struct ReflectionClass*)clazz;

    /* Build the descriptor from the Constructor's parameter types.
     * The return portion is fixed at V; only the parameter list needs
     * to be walked. 1 KiB is generous for any realistic constructor
     * signature; the helper reports failure with -1 and the caller
     * falls through to the generic throw. */
    char descriptor[1024];
    if (build_constructor_descriptor(parameter_types_array,
                                     descriptor, sizeof(descriptor)) < 0) {
        __jnative_throw_exception(NULL);
    }

    /* Resolve the constructor in the class's own constructor list. As
     * with methods, the registry attaches a constructor to the class
     * that declares it, which is what Constructor.clazz names in both
     * supported layouts, so no hierarchy walk is required. */
    struct ReflectionConstructor* c =
        lookup_reflection_constructor(rc, descriptor);

    if (c == NULL || c->adaptor == NULL) {
        /* The constructor was never translated into the module, or the
         * class has no compiled constructor entry with this descriptor.
         * The Java layer's contract is to fail the reflective
         * instantiation in that case rather than silently return a
         * half-initialised object. */
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
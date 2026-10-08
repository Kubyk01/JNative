#define _GNU_SOURCE
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <dlfcn.h>
#include "jnative_runtime.h"

extern struct ReflectionClass* reflect_all_classes[];
extern int __jnative_instanceof(void* obj, void** type_info);

extern void* __jnative_make_string_obj(const char* bytes, int32_t len);
extern const char* __jnative_read_string_bytes(void* s, int32_t* out_len);

/*
 * --------------------------------------------------------------------------
 * Guard for the reflect-mirror "root" fields.
 *
 * JNATIVE_METHOD_ROOT_OFFSET and JNATIVE_CTOR_ROOT_OFFSET are published by
 * @main through __jnative_reflect_set_layout. The call site in
 * LlvmGenerator.generateMain currently passes only 17 arguments to a
 * function whose signature declares 19 — the two new root offsets were
 * never threaded through. The C side therefore observes two stack slots
 * worth of stale data, and a value that happens to be a plausible-looking
 * positive integer drives a write to method + <garbage>.
 *
 * The bounds check below is the last line of defence: it is better to
 * silently skip the root write (which only matters for one corner case of
 * Method.copy()) than to corrupt arbitrary heap. The upper bound is
 * generous relative to any plausible reflect-mirror layout — JDK 21's
 * Method and Constructor both put root within the first ~256 bytes of the
 * object.
 *
 * A root offset of -1 (the initial value, and the value after the
 * mis-signature is corrected on the Java side) means "not set" and the
 * write is skipped.
 */
#define JNATIVE_ROOT_OFFSET_MAX 512

static inline int jnative_root_offset_is_sane(int32_t offset) {
    return offset > 0 && offset <= JNATIVE_ROOT_OFFSET_MAX;
}

/*
 * --------------------------------------------------------------------------
 * Minimum-size helper.
 *
 * ReflectionClass.object_size for java.lang.reflect.Method and
 * java.lang.reflect.Constructor is computed by LlvmGlobalEmitter as the
 * unaligned sum of the declared field sizes, which can be smaller than
 * the highest field offset returned by getFieldOffset (which applies
 * natural alignment). Writing to the last few fields of such a mirror
 * would then land past the end of the calloc'ed allocation.
 *
 * This helper computes a minimum allocation size that covers every field
 * the mirror routines actually write, so the allocation can never be
 * short no matter how object_size was computed.
 */
static inline size_t jnative_reflect_min_size(int32_t* offsets, size_t count) {
    size_t min_size = OBJECT_HEADER_SIZE;
    for (size_t i = 0; i < count; i++) {
        int32_t off = offsets[i];
        if (off <= 0 || off > JNATIVE_ROOT_OFFSET_MAX) continue;
        size_t end = (size_t)off + 8;
        if (end > min_size) min_size = end;
    }
    return (min_size + 7) & ~(size_t)7;
}

/* --------------------------------------------------------------------------
 * Small helpers
 * ------------------------------------------------------------------------ */

/*
 * Class[] from a NULL-terminated mirror-pointer array. Routed through
 * jnative_ref_array_of_class() so the result carries a real klass mirror
 * and Class.getInterfaces()[i].getClass() resolves to java.lang.Class.
 */
static void* make_class_array(struct ReflectionClass** ptrs) {
    int count = 0;
    if (ptrs != NULL) {
        while (ptrs[count] != NULL) count++;
    }
    return jnative_ref_array_of_class((void**)ptrs, count,
                                      "[Ljava/lang/Class;");
}

/*
 * Empty [Ljava/lang/Class; array.
 *
 * Used as the fallback for any reflect-mirror slot that must present a
 * non-null Class[] to the Java layer: Method.parameterTypes,
 * Method.exceptionTypes, Constructor.parameterTypes,
 * Constructor.exceptionTypes.
 *
 * Every java.lang.reflect.Executable consumer — sharedToString,
 * toGenericString, getParameterTypes, getExceptionTypes, the access-check
 * diagnostics in sun.invoke.util.VerifyAccess, Method.copy — iterates
 * at least one of those arrays without a null check. A null slot turns
 * the very first reflective toString into the
 *
 *     java.lang.NullPointerException: Cannot invoke
 *     java.lang.reflect.Executable.sharedToString(...) because <array>
 *     is null
 *
 * that aborted MethodHandleImpl$CountingWrapper.<clinit> during the
 * VarHandle bootstrap. An empty array is indistinguishable from
 * `new Class[0]`, so every consumer behaves as if the executable
 * genuinely declared no parameters / no thrown exceptions.
 */
static void* empty_param_types_array(void) {
    return jnative_ref_array_of_class(NULL, 0, "[Ljava/lang/Class;");
}

static struct ReflectionClass* find_registered_class(const char* name) {
    if (name == NULL) return NULL;
    struct ReflectionClass** pp = reflect_all_classes;
    while (*pp) {
        struct ReflectionClass* c = *pp;
        const char* n = c->cname;
        if (n && strcmp(n, name) == 0) return c;
        pp++;
    }
    return NULL;
}

/*
 * Same lookup, but accepts a binary name whose package segments are
 * separated by '.' instead of '/'. java.lang.Class.forName0 receives the
 * binary name exactly as written in the Java source, so we must normalise
 * before hitting reflect_all_classes[] (which is keyed on the slash form
 * emitted by LlvmGlobalEmitter).
 */
static struct ReflectionClass* find_registered_class_dotted(const char* name) {
    if (name == NULL) return NULL;

    struct ReflectionClass* cls = find_registered_class(name);
    if (cls != NULL) return cls;

    char buf[512];
    size_t n = strlen(name);
    if (n >= sizeof(buf)) return NULL;
    memcpy(buf, name, n + 1);
    for (char* p = buf; *p; p++) {
        if (*p == '.') *p = '/';
    }
    return find_registered_class(buf);
}

static int class_implements_interface(struct ReflectionClass* cls,
                                      struct ReflectionClass* target) {
    if (!cls || !target) return 0;
    struct ReflectionClass** iface = cls->interfaces;
    while (iface && *iface) {
        if (*iface == target) return 1;
        if (class_implements_interface(*iface, target)) return 1;
        iface++;
    }
    return 0;
}

static char* build_type_info_symbol(const char* class_name) {
    static char buf[512];
    jnative_type_info_name(class_name, buf, sizeof(buf));
    return buf;
}

static void** lookup_type_info(struct ReflectionClass* cls) {
    if (!cls || !cls->cname) return NULL;
    const char* symbol = build_type_info_symbol(cls->cname);
    void* handle = dlopen(NULL, RTLD_LAZY);
    if (!handle) return NULL;
    void** type_info = (void**)dlsym(handle, symbol);
    dlclose(handle);
    return type_info;
}

static void* allocate_reflection_object(struct ReflectionClass* cls) {
    if (!cls) return NULL;
    int size = cls->object_size;
    if (size <= 0) size = 8;

    void* obj = calloc(1, (size_t)size);
    if (!obj) return NULL;

    void** type_info = lookup_type_info(cls);
    if (!type_info || !type_info[0]) {
        free(obj);
        return NULL;
    }
    *(void**)obj = type_info[0];
    return obj;
}

/* ========================================================================
 *  Reflection object construction
 * ====================================================================== */

/*
 * Convert a JVM type descriptor into a registered Class mirror.
 *
 * Handles reference ("L...;"), array ("[...") and primitive ("I", "J",
 * ...) descriptors. Returns NULL when the corresponding mirror is not
 * present in reflect_all_classes[].
 */
static ReflectionClass* descriptor_to_class_mirror(const char* desc)
{
    if (desc == NULL || desc[0] == '\0') return NULL;

    if (desc[0] == 'L') {
        size_t n = strlen(desc);
        if (n < 3 || desc[n - 1] != ';') return NULL;
        char buf[512];
        if (n - 2 >= sizeof(buf)) return NULL;
        memcpy(buf, desc + 1, n - 2);
        buf[n - 2] = '\0';
        return jnative_class_by_name(buf);
    }

    if (desc[0] == '[') {
        return jnative_class_by_name(desc);
    }

    if (desc[1] == '\0') {
        const char* prim = NULL;
        switch (desc[0]) {
            case 'Z': prim = "boolean"; break;
            case 'B': prim = "byte";    break;
            case 'S': prim = "short";   break;
            case 'C': prim = "char";    break;
            case 'I': prim = "int";     break;
            case 'J': prim = "long";    break;
            case 'F': prim = "float";   break;
            case 'D': prim = "double";  break;
            case 'V': prim = "void";    break;
        }
        if (prim != NULL) return jnative_class_by_name(prim);
    }

    return NULL;
}

/*
 * Advance a descriptor cursor past exactly one type descriptor.
 */
static int descriptor_element_end(const char* desc, int i)
{
    if (desc[i] == '\0') return -1;

    while (desc[i] == '[') {
        i++;
        if (desc[i] == '\0') return -1;
    }

    if (desc[i] == 'L') {
        const char* semi = strchr(desc + i, ';');
        if (semi == NULL) return -1;
        return (int)(semi - desc) + 1;
    }

    return i + 1;
}

/*
 * Build a Class[] from a JVM method descriptor's parameter list.
 */
static void* build_parameter_types_array(const char* desc)
{
    if (desc == NULL || desc[0] != '(') return NULL;

    int32_t count = 0;
    int i = 1;
    while (desc[i] != '\0' && desc[i] != ')') {
        int next = descriptor_element_end(desc, i);
        if (next < 0) break;
        i = next;
        count++;
    }

    void* array = jnative_ref_array_of_class(NULL, count,
                                             "[Ljava/lang/Class;");
    if (array == NULL) return NULL;

    void** slots = (void**)((char*)array + JAVA_ARR_HDR);

    int32_t idx = 0;
    i = 1;
    while (desc[i] != '\0' && desc[i] != ')' && idx < count) {
        int next = descriptor_element_end(desc, i);
        if (next < 0) break;

        size_t len = (size_t)(desc + next - (desc + i));
        if (len > 0 && len < 512) {
            char buf[512];
            memcpy(buf, desc + i, len);
            buf[len] = '\0';
            slots[idx] = descriptor_to_class_mirror(buf);
        }
        i = next;
        idx++;
    }

    return array;
}

/*
 * Build a java.lang.reflect.Field from a native ReflectionField
 * descriptor.
 *
 * The mirror is sized generously: the offsets published by @main
 * through __jnative_reflect_set_layout are checked, and the allocation
 * covers every field the routine writes.
 */
static void* create_field_mirror(ReflectionClass* declaring_cls,
                                 ReflectionField* rf)
{
    if (declaring_cls == NULL || rf == NULL) return NULL;

    ReflectionClass* field_cls =
        jnative_class_by_name("java/lang/reflect/Field");
    if (field_cls == NULL) return NULL;

    int32_t offsets[] = {
        JNATIVE_FIELD_CLAZZ_OFFSET,
        JNATIVE_FIELD_NAME_OFFSET,
        JNATIVE_FIELD_TYPE_OFFSET,
    };
    size_t min_size = jnative_reflect_min_size(
        offsets, sizeof(offsets) / sizeof(offsets[0]));
    if ((size_t)(JNATIVE_FIELD_MODIFIERS_OFFSET > 0
                 ? JNATIVE_FIELD_MODIFIERS_OFFSET + 4 : 0) > min_size) {
        min_size = (size_t)JNATIVE_FIELD_MODIFIERS_OFFSET + 4;
        min_size = (min_size + 7) & ~(size_t)7;
    }

    void* field = jnative_alloc_object_at_least(field_cls, min_size);
    if (field == NULL) return NULL;

    const char* name = (const char*)rf->name;
    void* name_str = (name != NULL)
        ? __jnative_make_string_obj(name, (int32_t)strlen(name))
        : NULL;

    const char* desc = (const char*)rf->descriptor;
    ReflectionClass* type_cls =
        (desc != NULL && desc[0] != '\0')
            ? descriptor_to_class_mirror(desc)
            : NULL;
    if (type_cls == NULL) {
        type_cls = jnative_class_by_name("java/lang/Object");
    }

    *(void**)((char*)field + JNATIVE_FIELD_CLAZZ_OFFSET)       = declaring_cls;
    *(int32_t*)((char*)field + JNATIVE_FIELD_SLOT_OFFSET)      = rf->offset;
    *(void**)((char*)field + JNATIVE_FIELD_NAME_OFFSET)        = name_str;
    *(void**)((char*)field + JNATIVE_FIELD_TYPE_OFFSET)        = type_cls;
    *(int32_t*)((char*)field + JNATIVE_FIELD_MODIFIERS_OFFSET) = rf->modifiers;

    return field;
}

/*
 * Build a java.lang.reflect.Method from a native ReflectionMethod
 * descriptor.
 *
 * Both parameterTypes and exceptionTypes are emitted as non-null arrays,
 * matching the contract every Executable consumer expects.
 *
 * The allocation is sized by jnative_reflect_min_size() using every
 * field offset this routine writes, so a short ReflectionClass.object_size
 * (which LlvmGlobalEmitter computes as an unaligned sum) cannot cause a
 * heap overflow.
 *
 * The `root` write — which is what makes Method.copy() work on a mirror
 * that has already had its methodAccessor set — is gated on a bounds
 * check. When the offset published by __jnative_reflect_set_layout is
 * either -1 (because the emitter-side argument was never threaded
 * through) or a garbage value that happens to be inside the plausible
 * range, the write is skipped rather than performed at a wild address.
 * A mirror that is not marked as its own root will fail Method.copy()
 * with "Can not copy a non-root Method", which is a clean Java-level
 * error rather than a segfault.
 */
static void* create_method_mirror(ReflectionClass* declaring_cls,
                                  ReflectionMethod* rm)
{
    if (declaring_cls == NULL || rm == NULL) return NULL;

    ReflectionClass* method_cls =
        jnative_class_by_name("java/lang/reflect/Method");
    if (method_cls == NULL) return NULL;

    int32_t offsets[] = {
        JNATIVE_METHOD_CLAZZ_OFFSET,
        JNATIVE_METHOD_NAME_OFFSET,
        JNATIVE_METHOD_RETURN_TYPE_OFFSET,
        JNATIVE_METHOD_PARAM_TYPES_OFFSET,
        JNATIVE_METHOD_EXC_TYPES_OFFSET,
        JNATIVE_METHOD_ROOT_OFFSET,
    };
    size_t min_size = jnative_reflect_min_size(
        offsets, sizeof(offsets) / sizeof(offsets[0]));
    if (JNATIVE_METHOD_MODIFIERS_OFFSET > 0) {
        size_t end = (size_t)JNATIVE_METHOD_MODIFIERS_OFFSET + 4;
        if (end > min_size) {
            min_size = (end + 7) & ~(size_t)7;
        }
    }
    if (JNATIVE_METHOD_SLOT_OFFSET > 0) {
        size_t end = (size_t)JNATIVE_METHOD_SLOT_OFFSET + 4;
        if (end > min_size) {
            min_size = (end + 7) & ~(size_t)7;
        }
    }

    void* method = jnative_alloc_object_at_least(method_cls, min_size);
    if (method == NULL) return NULL;

    const char* name = (const char*)rm->name;
    const char* desc = (const char*)rm->descriptor;
    void* name_str = (name != NULL)
        ? __jnative_make_string_obj(name, (int32_t)strlen(name))
        : NULL;

    ReflectionClass* return_type = NULL;
    void* param_types = NULL;
    if (desc != NULL && desc[0] == '(') {
        const char* close = strchr(desc, ')');
        if (close != NULL && close[1] != '\0') {
            return_type = descriptor_to_class_mirror(close + 1);
        }
        param_types = build_parameter_types_array(desc);
    }
    if (param_types == NULL) {
        param_types = empty_param_types_array();
    }
    void* exc_types = empty_param_types_array();

    *(void**)((char*)method + JNATIVE_METHOD_CLAZZ_OFFSET)       = declaring_cls;
    *(int32_t*)((char*)method + JNATIVE_METHOD_SLOT_OFFSET)      = 0;
    *(void**)((char*)method + JNATIVE_METHOD_NAME_OFFSET)        = name_str;
    *(void**)((char*)method + JNATIVE_METHOD_RETURN_TYPE_OFFSET) = return_type;
    *(void**)((char*)method + JNATIVE_METHOD_PARAM_TYPES_OFFSET) = param_types;
    *(void**)((char*)method + JNATIVE_METHOD_EXC_TYPES_OFFSET)   = exc_types;
    *(int32_t*)((char*)method + JNATIVE_METHOD_MODIFIERS_OFFSET) = rm->modifiers;

    if (jnative_root_offset_is_sane(JNATIVE_METHOD_ROOT_OFFSET)) {
        *(void**)((char*)method + JNATIVE_METHOD_ROOT_OFFSET) = method;
    }

    return method;
}

/*
 * Build a java.lang.reflect.Constructor from a native
 * ReflectionConstructor descriptor.
 *
 * Same non-null contract for both parameterTypes and exceptionTypes as
 * create_method_mirror above, and the same bounds-checked root write.
 */
static void* create_constructor_mirror(ReflectionClass* declaring_cls,
                                       ReflectionConstructor* rc_ctor)
{
    if (declaring_cls == NULL || rc_ctor == NULL) return NULL;

    ReflectionClass* ctor_cls =
        jnative_class_by_name("java/lang/reflect/Constructor");
    if (ctor_cls == NULL) return NULL;

    int32_t offsets[] = {
        JNATIVE_CTOR_CLAZZ_OFFSET,
        JNATIVE_CTOR_PARAM_TYPES_OFFSET,
        JNATIVE_CTOR_EXC_TYPES_OFFSET,
        JNATIVE_CTOR_ROOT_OFFSET,
    };
    size_t min_size = jnative_reflect_min_size(
        offsets, sizeof(offsets) / sizeof(offsets[0]));
    if (JNATIVE_CTOR_MODIFIERS_OFFSET > 0) {
        size_t end = (size_t)JNATIVE_CTOR_MODIFIERS_OFFSET + 4;
        if (end > min_size) {
            min_size = (end + 7) & ~(size_t)7;
        }
    }
    if (JNATIVE_CTOR_SLOT_OFFSET > 0) {
        size_t end = (size_t)JNATIVE_CTOR_SLOT_OFFSET + 4;
        if (end > min_size) {
            min_size = (end + 7) & ~(size_t)7;
        }
    }

    void* ctor = jnative_alloc_object_at_least(ctor_cls, min_size);
    if (ctor == NULL) return NULL;

    const char* desc = (const char*)rc_ctor->descriptor;
    void* param_types = NULL;
    if (desc != NULL && desc[0] == '(') {
        param_types = build_parameter_types_array(desc);
    }
    if (param_types == NULL) {
        param_types = empty_param_types_array();
    }
    void* exc_types = empty_param_types_array();

    *(void**)((char*)ctor + JNATIVE_CTOR_CLAZZ_OFFSET)       = declaring_cls;
    *(int32_t*)((char*)ctor + JNATIVE_CTOR_SLOT_OFFSET)      = 0;
    *(void**)((char*)ctor + JNATIVE_CTOR_PARAM_TYPES_OFFSET) = param_types;
    *(void**)((char*)ctor + JNATIVE_CTOR_EXC_TYPES_OFFSET)   = exc_types;
    *(int32_t*)((char*)ctor + JNATIVE_CTOR_MODIFIERS_OFFSET) = rc_ctor->modifiers;

    if (jnative_root_offset_is_sane(JNATIVE_CTOR_ROOT_OFFSET)) {
        *(void**)((char*)ctor + JNATIVE_CTOR_ROOT_OFFSET) = ctor;
    }

    return ctor;
}

/* --------------------------------------------------------------------------
 * Simple property accessors
 * ------------------------------------------------------------------------ */

int __jnative_fn_java_lang_Class_getModifiers___I(void* this_cls) {
    if (this_cls == NULL) return 0;
    return ((struct ReflectionClass*)this_cls)->modifiers;
}

void* __jnative_fn_java_lang_Class_getSuperclass___Ljava_lang_Class_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    return (void*)((struct ReflectionClass*)this_cls)->superclass;
}

void** __jnative_fn_java_lang_Class_getInterfaces___Ljava_lang_Class_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    return (void**)((struct ReflectionClass*)this_cls)->interfaces;
}

int __jnative_fn_java_lang_Class_isInterface___Z(void* this_cls) {
    if (this_cls == NULL) return 0;
    return (((struct ReflectionClass*)this_cls)->modifiers & 0x0200) != 0;
}

int __jnative_fn_java_lang_Class_isArray___Z(void* this_cls) {
    if (this_cls == NULL) return 0;
    const char* name = ((struct ReflectionClass*)this_cls)->cname;
    if (!name) return 0;
    return name[0] == '[';
}

int __jnative_fn_java_lang_Class_isPrimitive___Z(void* this_cls) {
    if (this_cls == NULL) return 0;
    const char* name = ((struct ReflectionClass*)this_cls)->cname;
    if (!name) return 0;
    return strcmp(name, "boolean") == 0 || strcmp(name, "byte") == 0 ||
           strcmp(name, "short")   == 0 || strcmp(name, "char") == 0 ||
           strcmp(name, "int")     == 0 || strcmp(name, "long") == 0 ||
           strcmp(name, "float")   == 0 || strcmp(name, "double") == 0 ||
           strcmp(name, "void")    == 0;
}

void* __jnative_fn_java_lang_Class_getName___Ljava_lang_String_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    const char* name = ((struct ReflectionClass*)this_cls)->cname;
    if (name == NULL) return NULL;
    return __jnative_make_string_obj(name, (int32_t)strlen(name));
}

void* __jnative_fn_java_lang_Class_initClassName___Ljava_lang_String_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    const char* internal = ((struct ReflectionClass*)this_cls)->cname;
    if (internal == NULL) return NULL;

    size_t len = strlen(internal);
    char* out = malloc(len + 1);
    if (out == NULL) return NULL;
    for (size_t i = 0; i < len; i++) {
        out[i] = (internal[i] == '/') ? '.' : internal[i];
    }
    out[len] = '\0';
    void* result = __jnative_make_string_obj(out, (int32_t)len);
    free(out);
    return result;
}

void* __jnative_fn_java_lang_Class_getClassLoader___Ljava_lang_ClassLoader_(void* this_cls) {
    (void)this_cls;
    return NULL;
}

void* __jnative_fn_java_lang_Class_getEnclosingMethod0____Ljava_lang_Object_(void* self) {
    (void)self;
    return NULL;
}

int __jnative_fn_java_lang_Class_isAssignableFrom__Ljava_lang_Class__Z(void* this_cls, void* other_cls) {
    if (this_cls == NULL || other_cls == NULL) return 0;
    if (this_cls == other_cls) return 1;

    struct ReflectionClass* target = (struct ReflectionClass*)this_cls;
    struct ReflectionClass* cur    = (struct ReflectionClass*)other_cls;

    while (cur) {
        if (cur == target) return 1;
        if (class_implements_interface(cur, target)) return 1;
        cur = cur->superclass;
    }
    return 0;
}

void* __jnative_fn_java_lang_Class_getDeclaringClass0___Ljava_lang_Class_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    struct ReflectionClass* cls = (struct ReflectionClass*)this_cls;
    const char* name = cls->cname;
    if (name == NULL) return NULL;

    const char* last_dollar = strrchr(name, '$');
    if (last_dollar == NULL || last_dollar == name || last_dollar[1] == '\0') {
        return NULL;
    }

    size_t outer_len = (size_t)(last_dollar - name);
    char outer[512];
    if (outer_len >= sizeof(outer)) return NULL;
    memcpy(outer, name, outer_len);
    outer[outer_len] = '\0';

    return (void*)find_registered_class(outer);
}

void* __jnative_fn_java_lang_Class_getConstantPool___Ljdk_internal_reflect_ConstantPool_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    struct ReflectionClass* cp_cls = find_registered_class("jdk/internal/reflect/ConstantPool");
    if (!cp_cls) return NULL;
    return allocate_reflection_object(cp_cls);
}

void* __jnative_fn_java_lang_Class_getRawAnnotations____B(void* this_cls) {
    (void)this_cls;
    return NULL;
}

void* __jnative_fn_java_lang_Class_getRawTypeAnnotations____B(void* this_cls) {
    (void)this_cls;
    return NULL;
}

void* __jnative_fn_java_lang_Class_getSigners____Ljava_lang_Object_(void* this_cls) {
    (void)this_cls;
    return NULL;
}

void __jnative_fn_java_lang_Class_setSigners___Ljava_lang_Object__V(void* this_cls, void* signers) {
    (void)this_cls;
    (void)signers;
}

int __jnative_fn_java_lang_Class_isHidden___Z(void* this_cls) {
    (void)this_cls;
    return 0;
}

int __jnative_fn_java_lang_Class_isInstance__Ljava_lang_Object__Z(void* this_cls, void* obj) {
    if (this_cls == NULL || obj == NULL) return 0;
    struct ReflectionClass* cls = (struct ReflectionClass*)this_cls;
    void** type_info = lookup_type_info(cls);
    if (!type_info) return 0;
    return __jnative_instanceof(obj, type_info);
}

void* __jnative_fn_java_lang_Class_getPrimitiveClass__Ljava_lang_String__Ljava_lang_Class_(
        void* name_str)
{
    if (name_str == NULL) return NULL;
    int32_t len = 0;
    const char* name = __jnative_read_string_bytes(name_str, &len);
    (void)len;
    return (void*)find_registered_class(name);
}

void* __jnative_fn_java_lang_Class_getProtectionDomain0___Ljava_security_ProtectionDomain_(
        void* this_cls)
{
    (void)this_cls;
    return NULL;
}

void __jnative_fn_java_lang_Class_registerNatives___V(void) {
}

/* --------------------------------------------------------------------------
 * Class.forName0
 * ------------------------------------------------------------------------ */

static void* make_class_not_found_exception(const char* name, int32_t len) {
    char buf[512];
    if (name != NULL && len > 0 && (size_t)len < sizeof(buf)) {
        memcpy(buf, name, (size_t)len);
        buf[len] = '\0';
    } else {
        strncpy(buf, "<unknown>", sizeof(buf) - 1);
        buf[sizeof(buf) - 1] = '\0';
    }
    return __jnative_construct_exception(
        "vtable_java_lang_ClassNotFoundException", buf);
}

void* __jnative_fn_java_lang_Class_forName0__Ljava_lang_String_ZLjava_lang_ClassLoader_Ljava_lang_Class__Ljava_lang_Class_(
        void* name_str,
        int32_t initialize,
        void* loader,
        void* caller)
{
    (void)initialize;
    (void)loader;
    (void)caller;

    if (name_str == NULL) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }

    int32_t len = 0;
    const char* name = __jnative_read_string_bytes(name_str, &len);
    if (name == NULL || len <= 0) {
        __jnative_throw_exception(make_class_not_found_exception(name, len));
        return NULL;
    }

    struct ReflectionClass* cls = find_registered_class_dotted(name);
    if (cls == NULL) {
        __jnative_throw_exception(make_class_not_found_exception(name, len));
        return NULL;
    }
    return (void*)cls;
}

/* --------------------------------------------------------------------------
 * desiredAssertionStatus0
 * ------------------------------------------------------------------------ */
int32_t __jnative_fn_java_lang_Class_desiredAssertionStatus0__Ljava_lang_Class__Z(
        void* this_cls)
{
    (void)this_cls;
    return 0;
}

/* --------------------------------------------------------------------------
 * Reflection queries
 * ------------------------------------------------------------------------ */

void* __jnative_fn_java_lang_Class_getInterfaces0____Ljava_lang_Class_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    struct ReflectionClass* cls = (struct ReflectionClass*)this_cls;
    return make_class_array(cls->interfaces);
}

void* __jnative_fn_java_lang_Class_getGenericSignature0___Ljava_lang_String_(void* this_cls) {
    (void)this_cls;
    return NULL;
}

/*
 * private native Method[] getDeclaredMethods0(boolean publicOnly);
 *
 * Walks ReflectionClass.methods and builds a java.lang.reflect.Method
 * per entry. Returns a zero-length array only when the class genuinely
 * has no methods in the emitted metadata.
 *
 * This is not optional. MethodHandles.Lookup.findVarHandle() resolves
 * through MemberName.Factory.resolve -> MethodHandleNatives.resolve,
 * which reports failure by way of a ReflectiveOperationException; the
 * <clinit> of Striped64, Striped64$Cell, AtomicReference,
 * AtomicBoolean, AtomicMarkableReference, ConcurrentSkipListMap,
 * LinkedTransferQueue and FutureTask all catch exactly that and rethrow
 * it as an ExceptionInInitializerError.
 *
 * The implementation is deliberately defensive about the metadata
 * array: entries whose `name` or `descriptor` pointer is NULL are
 * skipped rather than dereferenced, and a top-level NULL in `rc->methods`
 * terminates the walk without crashing.
 */
void* __jnative_fn_java_lang_Class_getDeclaredMethods0__Z__Ljava_lang_reflect_Method_(
        void* this_cls, int32_t public_only) {
    if (this_cls == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    ReflectionClass* rc = (ReflectionClass*)this_cls;
    ReflectionMethod** mp = rc->methods;

    int32_t count = 0;
    if (mp != NULL) {
        for (ReflectionMethod** p = mp; *p != NULL; p++) {
            ReflectionMethod* rm = *p;
            if (rm->name == NULL || rm->descriptor == NULL) continue;
            if (public_only && !(rm->modifiers & JNATIVE_ACC_PUBLIC)) continue;
            count++;
        }
    }

    void* array = jnative_ref_array_of_class(NULL, count,
                                             "[Ljava/lang/reflect/Method;");
    if (array == NULL) {
        __jnative_throw_out_of_memory_error_ctx("Class.getDeclaredMethods0");
    }

    void** slots = (void**)((char*)array + JAVA_ARR_HDR);
    int32_t i = 0;
    if (mp != NULL) {
        for (ReflectionMethod** p = mp; *p != NULL; p++) {
            ReflectionMethod* rm = *p;
            if (rm->name == NULL || rm->descriptor == NULL) continue;
            if (public_only && !(rm->modifiers & JNATIVE_ACC_PUBLIC)) continue;
            slots[i++] = create_method_mirror(rc, rm);
        }
    }

    return array;
}

/*
 * private native Field[] getDeclaredFields0(boolean publicOnly);
 *
 * Walks ReflectionClass.fields and builds a java.lang.reflect.Field per
 * entry. Defensive about NULL name/descriptor pointers, same as the
 * method variant above.
 */
void* __jnative_fn_java_lang_Class_getDeclaredFields0__Z__Ljava_lang_reflect_Field_(
        void* this_cls, int32_t public_only) {
    if (this_cls == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    ReflectionClass* rc = (ReflectionClass*)this_cls;
    ReflectionField** fp = rc->fields;

    int32_t count = 0;
    if (fp != NULL) {
        for (ReflectionField** p = fp; *p != NULL; p++) {
            ReflectionField* rf = *p;
            if (rf->name == NULL) continue;
            if (public_only && !(rf->modifiers & JNATIVE_ACC_PUBLIC)) continue;
            count++;
        }
    }

    void* array = jnative_ref_array_of_class(NULL, count,
                                             "[Ljava/lang/reflect/Field;");
    if (array == NULL) {
        __jnative_throw_out_of_memory_error_ctx("Class.getDeclaredFields0");
    }

    void** slots = (void**)((char*)array + JAVA_ARR_HDR);
    int32_t i = 0;
    if (fp != NULL) {
        for (ReflectionField** p = fp; *p != NULL; p++) {
            ReflectionField* rf = *p;
            if (rf->name == NULL) continue;
            if (public_only && !(rf->modifiers & JNATIVE_ACC_PUBLIC)) continue;
            slots[i++] = create_field_mirror(rc, rf);
        }
    }

    return array;
}

/*
 * private native Constructor<T>[] getDeclaredConstructors0(boolean publicOnly);
 *
 * Same shape as getDeclaredMethods0, over ReflectionClass.constructors.
 */
void* __jnative_fn_java_lang_Class_getDeclaredConstructors0__Z__Ljava_lang_reflect_Constructor_(
        void* this_cls, int32_t public_only) {
    if (this_cls == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    ReflectionClass* rc = (ReflectionClass*)this_cls;
    ReflectionConstructor** cp = rc->constructors;

    int32_t count = 0;
    if (cp != NULL) {
        for (ReflectionConstructor** p = cp; *p != NULL; p++) {
            ReflectionConstructor* rc_ctor = *p;
            if (rc_ctor->descriptor == NULL) continue;
            if (public_only && !(rc_ctor->modifiers & JNATIVE_ACC_PUBLIC)) continue;
            count++;
        }
    }

    void* array = jnative_ref_array_of_class(NULL, count,
                                             "[Ljava/lang/reflect/Constructor;");
    if (array == NULL) {
        __jnative_throw_out_of_memory_error_ctx("Class.getDeclaredConstructors0");
    }

    void** slots = (void**)((char*)array + JAVA_ARR_HDR);
    int32_t i = 0;
    if (cp != NULL) {
        for (ReflectionConstructor** p = cp; *p != NULL; p++) {
            ReflectionConstructor* rc_ctor = *p;
            if (rc_ctor->descriptor == NULL) continue;
            if (public_only && !(rc_ctor->modifiers & JNATIVE_ACC_PUBLIC)) continue;
            slots[i++] = create_constructor_mirror(rc, rc_ctor);
        }
    }

    return array;
}

void* __jnative_fn_java_lang_Class_getSimpleBinaryName0___Ljava_lang_String_(void* this_cls) {
    (void)this_cls;
    return NULL;
}

void* __jnative_fn_java_lang_Class_getNestHost0___Ljava_lang_Class_(void* this_cls) {
    (void)this_cls;
    return NULL;
}

void* __jnative_fn_java_lang_Class_getPermittedSubclasses0____Ljava_lang_Class_(
        void* this_cls) {
    (void)this_cls;
    return NULL;
}

void* __jnative_fn_java_lang_Class_getRecordComponents0____Ljava_lang_reflect_RecordComponent_(
        void* this_cls) {
    (void)this_cls;
    return NULL;
}

int32_t __jnative_fn_java_lang_Class_isRecord0___Z(void* this_cls) {
    (void)this_cls;
    return 0;
}
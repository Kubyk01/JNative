#define _GNU_SOURCE
#include "jnative_platform.h"
#include "jnative_portability.h"
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include "jnative_runtime.h"

extern struct ReflectionClass* reflect_all_classes[];
extern struct JNativeLazyClinitEntryC jnative_lazy_clinit_map[];
extern const int64_t jnative_lazy_clinit_map_size;
extern int __jnative_instanceof(void* obj, void** type_info);

extern void* __jnative_make_string_obj(const char* bytes, int32_t len);
extern const char* __jnative_read_string_bytes(void* s, int32_t* out_len);

/* --------------------------------------------------------------------------
 * Guard for the reflect-mirror "root" fields.
 * ------------------------------------------------------------------------ */
#define JNATIVE_ROOT_OFFSET_MAX 512

static inline int jnative_root_offset_is_sane(int32_t offset) {
    return offset > 0 && offset <= JNATIVE_ROOT_OFFSET_MAX;
}

/* --------------------------------------------------------------------------
 * Minimum-size helper.
 * ------------------------------------------------------------------------ */
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

static void* make_class_array(struct ReflectionClass** ptrs) {
    int count = 0;
    if (ptrs != NULL) {
        while (ptrs[count] != NULL) count++;
    }
    return jnative_ref_array_of_class((void**)ptrs, count,
                                      "[Ljava/lang/Class;");
}

static void* empty_param_types_array(void) {
    return jnative_ref_array_of_class(NULL, 0, "[Ljava/lang/Class;");
}

/*
 * Hash-based lookup over reflect_all_classes[].
 *
 * Delegates to jnative_class_by_name(), which is backed by the
 * runtime's class-name hash map (build_class_name_map() in
 * jnative_runtime.c). This replaces the previous O(N) linear scan
 * (strcmp over 40 631 entries) that dominated the profile.
 *
 * All keys in the table are internal (slash-form) names, primitive
 * names ("void", "int", ...), or array descriptors ("[I",
 * "[Ljava/lang/String;", ...) — none contain '.', so the exact
 * lookup is always correct when the input is already in that form.
 */
static struct ReflectionClass* find_registered_class(const char* name) {
    if (name == NULL) return NULL;
    return (struct ReflectionClass*)jnative_class_by_name(name);
}

/*
 * Binary-name lookup for Class.forName0.
 *
 * Class.forName receives a binary name with dots as package
 * separators (and dots inside array element descriptors). The table is
 * keyed on the slash form, so any name containing '.' must be
 * normalised before the lookup.
 *
 * The previous revision always performed an exact lookup first, even
 * when the name was guaranteed to miss (a dotted name never matches a
 * slash key), then normalised and scanned again — two full O(N)
 * passes. Since find_registered_class() is now O(1), the exact lookup
 * is cheap, but skipping the guaranteed miss when a dot is present is
 * still the right thing to do: it halves the number of hash lookups
 * per Class.forName and, more importantly, removes a misleading path
 * from the profile.
 *
 * No-dot names (primitive names, default-package classes, array
 * descriptors) go straight to the exact lookup.
 */
static struct ReflectionClass* find_registered_class_dotted(const char* name) {
    if (name == NULL) return NULL;

    if (strchr(name, '.') == NULL) {
        return find_registered_class(name);
    }

    char buf[512];
    size_t n = strlen(name);
    if (n >= sizeof(buf)) return NULL;
    for (size_t i = 0; i <= n; i++) {
        buf[i] = (name[i] == '.') ? '/' : name[i];
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

static void** lookup_type_info(struct ReflectionClass* cls) {
    if (!cls || !cls->cname) return NULL;
    char symbol[512];
    jnative_type_info_name(cls->cname, symbol, sizeof(symbol));
    void* handle = jn_dlsym_default;
    if (!handle) return NULL;
    void** type_info = (void**)jn_dlsym_default( symbol);
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
        ? __jnative_string_intern(__jnative_make_string_obj(name, (int32_t)strlen(name)))
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

    const JNativeAnnotationMember* am = jnative_annotation_member(declaring_cls, name, desc);
    if (am != NULL && JNATIVE_FIELD_ANNOTATIONS_OFFSET > 0) {
        *(void**)((char*)field + JNATIVE_FIELD_ANNOTATIONS_OFFSET) =
            jnative_annotation_bytes(am->annotations, am->annotations_len);
    }

    return field;
}

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
        ? __jnative_string_intern(__jnative_make_string_obj(name, (int32_t)strlen(name)))
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

    const JNativeAnnotationMember* am = jnative_annotation_member(declaring_cls, name, desc);
    if (am != NULL) {
        if (JNATIVE_METHOD_ANNOTATIONS_OFFSET > 0)
            *(void**)((char*)method + JNATIVE_METHOD_ANNOTATIONS_OFFSET) =
                jnative_annotation_bytes(am->annotations, am->annotations_len);
        if (JNATIVE_METHOD_PARAM_ANNOTATIONS_OFFSET > 0)
            *(void**)((char*)method + JNATIVE_METHOD_PARAM_ANNOTATIONS_OFFSET) =
                jnative_annotation_bytes(am->parameter_annotations, am->parameter_annotations_len);
        if (JNATIVE_METHOD_ANNOTATION_DEFAULT_OFFSET > 0)
            *(void**)((char*)method + JNATIVE_METHOD_ANNOTATION_DEFAULT_OFFSET) =
                jnative_annotation_bytes(am->annotation_default, am->annotation_default_len);
    }

    if (jnative_root_offset_is_sane(JNATIVE_METHOD_ROOT_OFFSET)) {
        *(void**)((char*)method + JNATIVE_METHOD_ROOT_OFFSET) = NULL;
    }

    return method;
}

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

    const JNativeAnnotationMember* am = jnative_annotation_member(declaring_cls, "<init>", desc);
    if (am != NULL) {
        if (JNATIVE_CTOR_ANNOTATIONS_OFFSET > 0)
            *(void**)((char*)ctor + JNATIVE_CTOR_ANNOTATIONS_OFFSET) =
                jnative_annotation_bytes(am->annotations, am->annotations_len);
        if (JNATIVE_CTOR_PARAM_ANNOTATIONS_OFFSET > 0)
            *(void**)((char*)ctor + JNATIVE_CTOR_PARAM_ANNOTATIONS_OFFSET) =
                jnative_annotation_bytes(am->parameter_annotations, am->parameter_annotations_len);
    }

    if (jnative_root_offset_is_sane(JNATIVE_CTOR_ROOT_OFFSET)) {
        *(void**)((char*)ctor + JNATIVE_CTOR_ROOT_OFFSET) = NULL;
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
    return ((struct ReflectionClass*)this_cls)->primitive != NULL;
}

/* ============================================================================
 * Class.getName() / Class.initClassName() — cached dotted class name.
 * ========================================================================== */

static void* cached_dotted_class_name(struct ReflectionClass* cls) {
    if (cls == NULL) return NULL;

    void* cached = __atomic_load_n(&cls->name, __ATOMIC_ACQUIRE);
    if (cached != NULL) return cached;

    const char* internal = cls->cname;
    if (internal == NULL) return NULL;

    size_t len = strlen(internal);
    if (len == 0) return NULL;

    char* out = (char*)malloc(len + 1);
    if (out == NULL) return NULL;

    for (size_t i = 0; i < len; i++) {
        out[i] = (internal[i] == '/') ? '.' : internal[i];
    }
    out[len] = '\0';

    void* computed = __jnative_make_string_obj(out, (int32_t)len);
    free(out);

    if (computed == NULL) return NULL;

    void* expected = NULL;
    if (!__atomic_compare_exchange_n(&cls->name, &expected, computed, 0,
                                      __ATOMIC_RELEASE, __ATOMIC_ACQUIRE)) {
        return expected;
    }
    return computed;
}

void* __jnative_fn_java_lang_Class_getName___Ljava_lang_String_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    return cached_dotted_class_name((struct ReflectionClass*)this_cls);
}

void* __jnative_fn_java_lang_Class_initClassName___Ljava_lang_String_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    return cached_dotted_class_name((struct ReflectionClass*)this_cls);
}

void* __jnative_fn_java_lang_Class_getClassLoader___Ljava_lang_ClassLoader_(void* this_cls) {
    (void)this_cls;
    return NULL;
}

void* __jnative_fn_java_lang_Class_getEnclosingMethod0____Ljava_lang_Object_(void* self) {
    (void)self;
    return NULL;
}

static int array_descriptor_assignable(const char* src, const char* dst);

static int class_descriptor(const struct ReflectionClass* cls, char* buf, size_t cap) {
    const char* name = cls->cname;
    if (name == NULL) return 0;
    if (name[0] == '[') return snprintf(buf, cap, "%s", name) < (int)cap;
    if (cls->primitive != NULL) return 0;
    return snprintf(buf, cap, "L%s;", name) < (int)cap;
}

int __jnative_fn_java_lang_Class_isAssignableFrom__Ljava_lang_Class__Z(void* this_cls, void* other_cls) {
    if (this_cls == NULL || other_cls == NULL) return 0;
    if (this_cls == other_cls) return 1;

    struct ReflectionClass* target = (struct ReflectionClass*)this_cls;
    struct ReflectionClass* cur    = (struct ReflectionClass*)other_cls;

    if (cur->cname != NULL && cur->cname[0] == '[') {
        char dst[1024];
        if (!class_descriptor(target, dst, sizeof(dst))) return 0;
        return array_descriptor_assignable(cur->cname, dst);
    }

    while (cur) {
        if (cur == target) return 1;
        if (class_implements_interface(cur, target)) return 1;
        cur = cur->superclass;
    }
    return 0;
}

static int array_descriptor_assignable(const char* src, const char* dst) {
    if (strcmp(src, dst) == 0) return 1;
    if (src[0] == '[' && dst[0] == '[') return array_descriptor_assignable(src + 1, dst + 1);
    if (dst[0] != 'L') return 0;
    if (strcmp(dst, "Ljava/lang/Object;") == 0) return src[0] == 'L' || src[0] == '[';
    if (src[0] == '[') {
        return strcmp(dst, "Ljava/lang/Cloneable;") == 0
            || strcmp(dst, "Ljava/io/Serializable;") == 0;
    }
    if (src[0] != 'L') return 0;
    char src_name[512];
    char dst_name[512];
    size_t src_len = strlen(src);
    size_t dst_len = strlen(dst);
    if (src_len < 3 || dst_len < 3 || src_len - 2 >= sizeof(src_name)
        || dst_len - 2 >= sizeof(dst_name)) {
        return 1;
    }
    memcpy(src_name, src + 1, src_len - 2);
    src_name[src_len - 2] = '\0';
    memcpy(dst_name, dst + 1, dst_len - 2);
    dst_name[dst_len - 2] = '\0';
    struct ReflectionClass* src_cls = find_registered_class(src_name);
    struct ReflectionClass* dst_cls = find_registered_class(dst_name);
    if (src_cls == NULL || dst_cls == NULL) return 1;
    return __jnative_fn_java_lang_Class_isAssignableFrom__Ljava_lang_Class__Z(dst_cls, src_cls);
}

int __jnative_array_instanceof(void* obj, const char* type_name) {
    if (obj == NULL) return 0;
    void* klass = *(void**)obj;
    if (klass == NULL || type_name == NULL) return 1;
    if (!__jnative_is_class_mirror(klass)) return 0;
    const char* cname = ((struct ReflectionClass*)klass)->cname;
    if (cname == NULL || cname[0] != '[') return 0;
    return array_descriptor_assignable(cname, type_name);
}

typedef struct {
    ReflectionClass*  cls;
    ReflectionClass*  declaring;
    ReflectionClass** members;
    int32_t           member_count;
} JNativeNestedClassEntry;

extern const JNativeNestedClassEntry jnative_nested_classes[] JNATIVE_WEAK;
extern const int64_t jnative_nested_classes_size JNATIVE_WEAK;

static const JNativeNestedClassEntry** nested_table;
static size_t nested_mask;
static jn_once_t nested_once = JN_ONCE_INIT;

static size_t nested_hash(const void* p) {
    uintptr_t v = (uintptr_t)p;
    v ^= v >> 17;
    v *= (uintptr_t)0x9E3779B97F4A7C15ull;
    return (size_t)(v ^ (v >> 29));
}

static void nested_init(void) {
    if ((uintptr_t)&jnative_nested_classes_size == 0) return;
    int64_t n = jnative_nested_classes_size;
    if (n <= 0) return;
    size_t cap = 16;
    while (cap < (size_t)n * 2) cap <<= 1;
    const JNativeNestedClassEntry** table = calloc(cap, sizeof(*table));
    if (table == NULL) return;
    for (int64_t i = 0; i < n; i++) {
        size_t h = nested_hash(jnative_nested_classes[i].cls) & (cap - 1);
        while (table[h] != NULL) h = (h + 1) & (cap - 1);
        table[h] = &jnative_nested_classes[i];
    }
    nested_mask = cap - 1;
    nested_table = table;
}

static const JNativeNestedClassEntry* find_nested_entry(const void* cls) {
    if (cls == NULL) return NULL;
    jn_once(&nested_once, nested_init);
    if (nested_table == NULL) return NULL;
    size_t h = nested_hash(cls) & nested_mask;
    while (nested_table[h] != NULL) {
        if (nested_table[h]->cls == cls) return nested_table[h];
        h = (h + 1) & nested_mask;
    }
    return NULL;
}

void* __jnative_fn_java_lang_Class_getDeclaringClass0___Ljava_lang_Class_(void* this_cls) {
    const JNativeNestedClassEntry* e = find_nested_entry(this_cls);
    return e != NULL ? (void*)e->declaring : NULL;
}

void* __jnative_fn_java_lang_Class_getDeclaredClasses0____Ljava_lang_Class_(void* this_cls) {
    if (this_cls == NULL) __jnative_throw_null_pointer_exception();
    const JNativeNestedClassEntry* e = find_nested_entry(this_cls);
    int32_t n = e != NULL ? e->member_count : 0;
    void* array = jnative_ref_array_of_class(n > 0 ? (void**)e->members : NULL, n, "[Ljava/lang/Class;");
    if (array == NULL) __jnative_throw_out_of_memory_error_ctx("Class.getDeclaredClasses0");
    return array;
}

/* --------------------------------------------------------------------------
 * Class.getConstantPool() — cached lookup.
 *
 * The target class name is a compile-time constant. The previous
 * revision performed a linear scan over reflect_all_classes[] on
 * every call (~7 200 calls, 182 M strcmp). Caching the
 * ReflectionClass* once via jn_once removes both the scan and the
 * per-call hash computation.
 * ------------------------------------------------------------------------ */
static struct ReflectionClass* constant_pool_class;
static jn_once_t constant_pool_once = JN_ONCE_INIT;

static void constant_pool_init(void) {
    constant_pool_class = find_registered_class("jdk/internal/reflect/ConstantPool");
}

void* __jnative_fn_java_lang_Class_getConstantPool___Ljdk_internal_reflect_ConstantPool_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    jn_once(&constant_pool_once, constant_pool_init);
    if (constant_pool_class == NULL) return NULL;
    void* cp = allocate_reflection_object(constant_pool_class);
    if (cp != NULL) *(void**)((char*)cp + 8) = this_cls;
    return cp;
}

void* __jnative_fn_java_lang_Class_getRawAnnotations____B(void* this_cls) {
    const JNativeAnnotationClass* e = jnative_annotation_class((ReflectionClass*)this_cls);
    if (e == NULL) return NULL;
    return jnative_annotation_bytes(e->annotations, e->annotations_len);
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
    void* header = *(void**)obj;
    if (header != NULL && __jnative_is_class_mirror(header)) {
        const char* src = ((struct ReflectionClass*)header)->cname;
        char dst[1024];
        if (src == NULL || src[0] != '[' || !class_descriptor(cls, dst, sizeof(dst))) return 0;
        return array_descriptor_assignable(src, dst);
    }
    if (cls->cname != NULL && cls->cname[0] == '[') return 0;
    void** type_info = lookup_type_info(cls);
    if (!type_info) return 0;
    return __jnative_instanceof(obj, type_info);
}

void* __jnative_fn_java_lang_Class_getPrimitiveClass__Ljava_lang_String__Ljava_lang_Class_(
        void* name_str)
{
    if (name_str == NULL) return NULL;
    int32_t len = 0;
    const char* name = __jnative_read_string_cstr(name_str, &len);
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

/*
 * Hash-map over jnative_lazy_clinit_map, keyed by ReflectionClass*.
 *
 * The previous linear scan over 5 560 entries ran on every
 * Class.forName(initialize=true). Same construction as the nested-
 * class table above: open addressing, pointer hash, built once under
 * jn_once. The map only ever grows during startup; afterwards every
 * lookup is O(1).
 */
static const struct JNativeLazyClinitEntryC** lazy_table;
static size_t lazy_mask;
static jn_once_t lazy_once = JN_ONCE_INIT;

static size_t lazy_hash(const void* p) {
    uintptr_t v = (uintptr_t)p;
    v ^= v >> 17;
    v *= (uintptr_t)0x9E3779B97F4A7C15ull;
    return (size_t)(v ^ (v >> 29));
}

static void lazy_init(void) {
    if ((uintptr_t)&jnative_lazy_clinit_map_size == 0) return;
    int64_t n = jnative_lazy_clinit_map_size;
    if (n <= 0) return;
    size_t cap = 16;
    while (cap < (size_t)n * 2) cap <<= 1;
    const struct JNativeLazyClinitEntryC** table =
        calloc(cap, sizeof(*table));
    if (table == NULL) return;
    for (int64_t i = 0; i < n; i++) {
        struct ReflectionClass* c = jnative_lazy_clinit_map[i].cls;
        if (c == NULL) continue;
        size_t h = lazy_hash(c) & (cap - 1);
        while (table[h] != NULL) h = (h + 1) & (cap - 1);
        table[h] = &jnative_lazy_clinit_map[i];
    }
    lazy_mask = cap - 1;
    lazy_table = table;
}

static void run_lazy_clinit_if_present(struct ReflectionClass* cls) {
    if (cls == NULL) return;
    jn_once(&lazy_once, lazy_init);
    if (lazy_table == NULL) return;
    size_t h = lazy_hash(cls) & lazy_mask;
    while (lazy_table[h] != NULL) {
        if (lazy_table[h]->cls == cls) {
            void (*wrapper)(void) =
                (void (*)(void))lazy_table[h]->wrapper;
            if (wrapper != NULL) wrapper();
            return;
        }
        h = (h + 1) & lazy_mask;
    }
}

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
    (void)loader;
    (void)caller;

    if (name_str == NULL) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }

    int32_t len = 0;
    const char* name = __jnative_read_string_cstr(name_str, &len);
    if (name == NULL || len <= 0) {
        __jnative_throw_exception(make_class_not_found_exception(name, len));
        return NULL;
    }

    struct ReflectionClass* cls = find_registered_class_dotted(name);
    if (cls == NULL) {
        __jnative_throw_exception(make_class_not_found_exception(name, len));
        return NULL;
    }

    if (initialize) {
        run_lazy_clinit_if_present(cls);
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

typedef struct {
    ReflectionClass* cls;
    const char*      signature;
} JNativeClassSignatureEntry;

extern const JNativeClassSignatureEntry jnative_class_signatures[] JNATIVE_WEAK;
extern const int64_t jnative_class_signatures_size JNATIVE_WEAK;

void* __jnative_fn_java_lang_Class_getGenericSignature0___Ljava_lang_String_(void* this_cls) {
    if (this_cls == NULL || jnative_class_signatures == NULL
        || &jnative_class_signatures_size == NULL) {
        return NULL;
    }
    for (int64_t i = 0; i < jnative_class_signatures_size; i++) {
        if (jnative_class_signatures[i].cls == this_cls) {
            const char* sig = jnative_class_signatures[i].signature;
            return __jnative_make_string_obj(sig, (int32_t)strlen(sig));
        }
    }
    return NULL;
}

void* __jnative_fn_java_lang_Class_getDeclaredMethods0__Z__Ljava_lang_reflect_Method_(
        void* this_cls, int32_t public_only) {
    if (this_cls == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    ReflectionClass* rc = (ReflectionClass*)this_cls;
    ReflectionMethod** mp = rc->methods;

    int hide_define_class = rc == jnative_class_by_name("java/lang/ClassLoader");

    int32_t count = 0;
    if (mp != NULL) {
        for (ReflectionMethod** p = mp; *p != NULL; p++) {
            ReflectionMethod* rm = *p;
            if (rm->name == NULL || rm->descriptor == NULL) continue;
            if (public_only && !(rm->modifiers & JNATIVE_ACC_PUBLIC)) continue;
            if (hide_define_class && strcmp(rm->name, "defineClass") == 0) continue;
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
            if (hide_define_class && strcmp(rm->name, "defineClass") == 0) continue;
            slots[i++] = create_method_mirror(rc, rm);
        }
    }

    return array;
}

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

/* ============================================================================
 * Class.getResourceAsStream override
 * ========================================================================== */

#define RES_PATH_BUF 1024

extern void* fn_java_lang_ClassLoader_getResourceAsStream__Ljava_lang_String__Ljava_io_InputStream_(
        void* loader, void* name) JNATIVE_WEAK;
void* __jnative_override_java_lang_Class_getClassLoader(void* self);

void* __jnative_override_Class_getResourceAsStream(void* this_class,
                                                   void* name_str)
{
    if (this_class == NULL) {
        __jnative_throw_null_pointer_exception_ctx(
            "__jnative_override_Class_getResourceAsStream", "this_class");
    }
    if (name_str == NULL) {
        __jnative_throw_null_pointer_exception_ctx(
            "__jnative_override_Class_getResourceAsStream", "name_str");
    }

    int32_t name_len = 0;
    const char* name = __jnative_read_string_bytes(name_str, &name_len);
    if (name == NULL || name_len <= 0) return NULL;

    char lookup_path[RES_PATH_BUF];
    int32_t lookup_len;

    if (name[0] == '/') {
        lookup_len = name_len - 1;
        if (lookup_len <= 0 || lookup_len >= RES_PATH_BUF) return NULL;
        memcpy(lookup_path, name + 1, (size_t)lookup_len);
    } else {
        const char* cname = ((ReflectionClass*)this_class)->cname;
        if (cname == NULL) return NULL;
        const char* slash = strrchr(cname, '/');
        if (slash == NULL) {
            if (name_len >= RES_PATH_BUF) return NULL;
            memcpy(lookup_path, name, (size_t)name_len);
            lookup_len = name_len;
        } else {
            size_t pkg_len = (size_t)(slash - cname) + 1;
            if (pkg_len + (size_t)name_len >= RES_PATH_BUF) return NULL;
            memcpy(lookup_path, cname, pkg_len);
            memcpy(lookup_path + pkg_len, name, (size_t)name_len);
            lookup_len = (int32_t)(pkg_len + (size_t)name_len);
        }
    }
    lookup_path[lookup_len] = '\0';

    const JNativeResourceEntry* res = jnative_find_resource(lookup_path, lookup_len);
    if (res == NULL) {
        if (fn_java_lang_ClassLoader_getResourceAsStream__Ljava_lang_String__Ljava_io_InputStream_ == NULL)
            return NULL;
        void* loader = __jnative_override_java_lang_Class_getClassLoader(this_class);
        if (loader == NULL) return NULL;
        void* path_str = __jnative_make_string_obj(lookup_path, lookup_len);
        return fn_java_lang_ClassLoader_getResourceAsStream__Ljava_lang_String__Ljava_io_InputStream_(
            loader, path_str);
    }

    void* bytes = jnative_byte_array(res->data, res->size);
    if (bytes == NULL) {
        __jnative_throw_out_of_memory_error_ctx("Class.getResourceAsStream");
    }

    return __jnative_make_byte_array_input_stream(bytes);
}

extern void* fn_java_lang_ClassLoader_getSystemClassLoader___Ljava_lang_ClassLoader_(void) JNATIVE_WEAK;

void* __jnative_override_java_lang_Class_getClassLoader(void* self) {
    if (self == NULL) {
        __jnative_throw_null_pointer_exception_ctx(
            "__jnative_override_java_lang_Class_getClassLoader", "self");
    }
    ReflectionClass* cls = (ReflectionClass*)self;
    if (cls->class_loader != NULL) return cls->class_loader;
    const char* name = cls->cname;
    if (name == NULL || name[0] == '[' || cls->primitive != NULL) return NULL;
    static const char* const boot_prefixes[] = {
        "java/", "javax/", "jdk/", "sun/", "com/sun/", NULL
    };
    for (int i = 0; boot_prefixes[i] != NULL; i++) {
        if (strncmp(name, boot_prefixes[i], strlen(boot_prefixes[i])) == 0) return NULL;
    }
    if (fn_java_lang_ClassLoader_getSystemClassLoader___Ljava_lang_ClassLoader_ == NULL) return NULL;
    run_lazy_clinit_if_present(jnative_class_by_name("java/lang/ClassLoader"));
    return fn_java_lang_ClassLoader_getSystemClassLoader___Ljava_lang_ClassLoader_();
}

typedef struct {
    ReflectionClass* cls;
    int32_t          version;
} JNativeClassFileVersionEntry;

extern const JNativeClassFileVersionEntry jnative_class_file_versions[] JNATIVE_WEAK;
extern const int64_t jnative_class_file_versions_size JNATIVE_WEAK;

#define JNATIVE_DEFAULT_CLASS_FILE_VERSION 69

static int32_t jnative_class_file_version_of(const ReflectionClass* cls) {
    if (cls == NULL || jnative_class_file_versions == NULL
        || &jnative_class_file_versions_size == NULL) {
        return -1;
    }
    for (int64_t i = 0; i < jnative_class_file_versions_size; i++) {
        if (jnative_class_file_versions[i].cls == cls) {
            return jnative_class_file_versions[i].version;
        }
    }
    return -1;
}

int32_t __jnative_fn_java_lang_Class_getClassFileVersion0___I(void* this_class) {
    if (this_class == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int32_t version = jnative_class_file_version_of((const ReflectionClass*)this_class);
    if (version >= 0) return version;
    version = jnative_class_file_version_of(jnative_class_by_name("java/lang/Object"));
    return version >= 0 ? version : JNATIVE_DEFAULT_CLASS_FILE_VERSION;
}
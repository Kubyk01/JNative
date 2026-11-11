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

/*
 * --------------------------------------------------------------------------
 * Guard for the reflect-mirror "root" fields.
 *
 * JNATIVE_METHOD_ROOT_OFFSET and JNATIVE_CTOR_ROOT_OFFSET are emitted by
 * LlvmGlobalEmitter.generateLayoutConstants() as `constant i32` globals.
 * The value is not guaranteed to be meaningful on every JDK: a field that
 * a given JDK release no longer declares is published as -1, and a future
 * rename could surface any other value the layout computation produced.
 *
 * The bounds check below is the last line of defence: it is better to
 * silently skip the (redundant) NULL store than to corrupt arbitrary
 * heap. The upper bound is generous relative to any plausible
 * reflect-mirror layout — JDK 21's Method and Constructor both put root
 * within the first ~256 bytes of the object.
 *
 * A root offset of -1 (the value published when the JDK does not declare
 * the field) means "not set" and the store is skipped.
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

static void** lookup_type_info(struct ReflectionClass* cls) {
    if (!cls || !cls->cname) return NULL;
    /* Stack buffer: a shared static one raced between threads, so a
     * concurrent Class.isInstance could dlsym another class's type info
     * and report false (AtomicXFieldUpdater.accessCheck -> bare CCE). */
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
 * The mirror is sized generously: the offsets read from the .rodata
 * constants emitted by LlvmGlobalEmitter.generateLayoutConstants() are
 * checked, and the allocation covers every field the routine writes.
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
 * ----------------------------------------------------------------------
 * THE `root` FIELD — CRITICAL SEMANTICS
 * ----------------------------------------------------------------------
 *
 * In the JDK's java.lang.reflect.Method, `root` distinguishes an
 * ORIGINAL Method from a COPY produced by Method.copy():
 *
 *     // original Method created by reflection
 *     root == null
 *
 *     // copy created by Class.getMethod() -> ReflectionFactory.copyMethod()
 *     Method res = new Method(...);
 *     res.root = original;   // non-null -> res IS a copy
 *
 * The very first thing Method.copy() does is reject copies:
 *
 *     if (this.root != null)
 *         throw new IllegalArgumentException("Can not copy a non-root Method");
 *     Method res = new Method(...);
 *     res.root = this;
 *     res.methodAccessor = methodAccessor;
 *     return res;
 *
 * Constructor.copy() mirrors the same logic with the message
 * "Can not copy a non-root Constructor".
 *
 * Therefore a mirror produced by this routine MUST behave as an ORIGINAL:
 * its `root` field must be NULL, and the JDK will set it to point at this
 * mirror on the first copy(). The JDK layer explicitly relies on this:
 *
 *     Class.getDeclaredMethod(...)
 *         -> getDeclaredMethods0()    // this routine; produces root-candidates
 *         -> searchMethods()          // picks the matching mirror
 *         -> ReflectionFactory.copyMethod(mirror)
 *             -> mirror.copy()        // requires mirror.root == null
 *
 * A previous revision of this file wrote `root = method` ("self"),
 * intending to make the mirror "its own root". That reading is incorrect
 * for the JDK's actual semantics: `root != null` marks a mirror as
 * ALREADY a copy, and the next copy() throws. The observable failure was
 * the very first reflective lookup performed by any JDK or library code:
 *
 *     com.google.gson.internal.reflect.ReflectionHelper.<clinit>
 *         -> Class.class.getMethod("isRecord")
 *             -> Method.copy()
 *                 -> IllegalArgumentException:
 *                        Can not copy a non-root Method
 *
 * Gson 2.10.1's <clinit> runs this exact sequence to set up its
 * RecordSupportedHelper, and the resulting exception cascaded through
 * the entire image:
 *
 *     jnative: <clinit> of com/google/gson/internal/reflect/ReflectionHelper
 *              abandoned: ...
 *     Exception in thread "main"
 *         java.lang.IllegalArgumentException:
 *             GSON (2.10.1) cannot handle io.github.kubyk01.Main$Person
 *
 * The fix is to leave `root` at its calloc-initialised value of NULL.
 * Because `jnative_alloc_object_at_least()` uses calloc(1, size), the
 * root slot is already zero. The bounds-checked write below is retained
 * purely as an explicit no-op for the reader — it stores NULL (the value
 * the slot already has), which is idempotent and keeps the invariant
 * visible at every call site. The guard still protects against a wild
 * offset from a JDK whose Method layout changed and in which
 * JNATIVE_METHOD_ROOT_OFFSET ended up pointing outside the object.
 *
 * The same reasoning and the same fix apply to
 * create_constructor_mirror().
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

    /*
     * root MUST be NULL for the mirror to be a valid "original" Method.
     * jnative_alloc_object_at_least() calloc()s the storage, so the slot
     * is already NULL; the store below is a self-documenting no-op that
     * also re-applies the bounds guard. See the long comment above.
     */
    if (jnative_root_offset_is_sane(JNATIVE_METHOD_ROOT_OFFSET)) {
        *(void**)((char*)method + JNATIVE_METHOD_ROOT_OFFSET) = NULL;
    }

    return method;
}

/*
 * Build a java.lang.reflect.Constructor from a native
 * ReflectionConstructor descriptor.
 *
 * Same non-null contract for both parameterTypes and exceptionTypes as
 * create_method_mirror above.
 *
 * The same `root == NULL` invariant applies here: Constructor.copy()
 * begins with exactly the same check as Method.copy() and rejects a
 * source whose `root != null`. The mirror produced by this routine is
 * an ORIGINAL — root stays NULL until the JDK's first copy() sets it to
 * point back at the mirror.
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
 *
 * Both the JDK-level Class.getName() and the native initClassName() are
 * specified to return the same thing: the binary name of the class, with
 * every '/' replaced by '.'. (For array types, the JVM descriptor is kept
 * verbatim: "[I", "[Ljava.lang.String;", "[[I" — only the '/' characters
 * inside the "L...;" component are folded, which is exactly what the
 * documented JDK behaviour requires.)
 *
 * The JDK-level implementation of Class.getName() caches the result in the
 * Class object's `name` field (see java.base/java/lang/Class.java:
 *
 *     public String getName() {
 *         String name = this.name;
 *         if (name == null) {
 *             name = initClassName();
 *             this.name = name;
 *         }
 *         return name;
 *     }
 *
 * ). In this runtime, that field lives at offset 8 of the Class mirror
 * (ReflectionClass.name in jnative_runtime.h), is emitted as "i8* null" in
 * every @refclass_* constant, and is *supposed* to be written back by the
 * emitted IR the first time the JDK path runs. In practice that write does
 * not take effect: profiling shows initClassName being called on every
 * single getName(), and because initClassName delegates to
 * __jnative_make_string_obj -> jnative_array_alloc("[B") ->
 * jnative_class_by_name("[B"), which is a linear scan over
 * reflect_all_classes[], the cost is O(N) per call — 29 % of the whole
 * benchmark in the report.
 *
 * To make the caching independent of the emitted bytecode, both C functions
 * below publish the result into the same slot the JDK path reads from:
 *
 *     1. Load the `name` slot at offset 8 with acquire semantics.
 *     2. If it is non-NULL, return it directly (fast path, O(1)).
 *     3. Otherwise compute the dotted name, publish it into the slot with
 *        a release store, and return it.
 *
 * The publish is a compare-and-swap against NULL, so if two threads race on
 * the first call they both end up returning the same String — the loser's
 * computed String is leaked (one per class, only under contention) and the
 * winner's is returned. This matches the JDK's own behaviour: the Java
 * field is not synchronized, and a torn write would be a JMM violation
 * anyway, so the JDK code relies on idempotent recomputation under races.
 *
 * After the first call, the fast path is a single acquire-load of the slot.
 * It does not allocate, does not call into __jnative_make_string_obj, and
 * does not touch jnative_class_by_name. The 29 % of the benchmark that was
 * spent recomputing the name every iteration collapses to one-time setup
 * cost per class.
 *
 * Arrays: the descriptor form is preserved as required by the JDK
 * documentation for Class.getName():
 *
 *     [I          -> "[I"
 *     [Ljava/lang/String; -> "[Ljava.lang.String;"
 *     [[Ljava/lang/Object; -> "[[Ljava.lang.Object;"
 *
 * because the transformation is a pure '/' -> '.' substitution: the leading
 * '[' characters, the primitive single-letter codes, and the surrounding
 * 'L' / ';' all pass through unchanged.
 * ========================================================================== */

static void* cached_dotted_class_name(struct ReflectionClass* cls) {
    if (cls == NULL) return NULL;

    /* Fast path: another caller (either the JDK bytecode path or a
     * previous invocation of this function) already published the name.
     * The acquire barrier pairs with the release in the CAS below, so a
     * non-NULL slot here implies the backing String's fields are visible
     * to this thread. */
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

    /* Publish into the Class mirror's `name` slot with a release store.
     * If another thread won the race, discard our duplicate (its memory
     * is small and only ever allocated once per class per contending
     * thread) and return the winner's instance — that guarantees all
     * callers observe the same String object, which is what the JDK's
     * own caching documents. */
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

void* __jnative_fn_java_lang_Class_getConstantPool___Ljdk_internal_reflect_ConstantPool_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    struct ReflectionClass* cp_cls = find_registered_class("jdk/internal/reflect/ConstantPool");
    if (!cp_cls) return NULL;
    void* cp = allocate_reflection_object(cp_cls);
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

static void run_lazy_clinit_if_present(struct ReflectionClass* cls) {
    if (cls == NULL) return;
    const int64_t size = jnative_lazy_clinit_map_size;
    if (size <= 0) return;
    for (int64_t i = 0; i < size; i++) {
        if (jnative_lazy_clinit_map[i].cls == cls) {
            void (*wrapper)(void) =
                (void (*)(void))jnative_lazy_clinit_map[i].wrapper;
            if (wrapper != NULL) wrapper();
            return;
        }
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
        /* Not embedded: like the JDK, ask the class's loader, which reads the
         * runtime classpath jars (e.g. spring-web's CodecConfigurer.properties
         * via CodecConfigurerFactory.class). Boot classes have no loader. */
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

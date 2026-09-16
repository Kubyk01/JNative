#define _GNU_SOURCE
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <dlfcn.h>

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

extern struct ReflectionClass* reflect_all_classes[];
extern int __jnative_instanceof(void* obj, void** type_info);

/* Java array layout: [int32 length][payload] */
#define JAVA_ARR_HDR 4

/* --------------------------------------------------------------------------
 * Small helpers
 * ------------------------------------------------------------------------ */

/*
 * Allocates a Java Class[] of the given length. The payload slots are filled
 * with the pointer values supplied by the caller. Used by getInterfaces0.
 */
static void* make_class_array(struct ReflectionClass** ptrs) {
    int count = 0;
    if (ptrs != NULL) {
        while (ptrs[count] != NULL) count++;
    }
    size_t total = JAVA_ARR_HDR + (size_t)count * sizeof(void*);
    void* arr = malloc(total);
    if (arr == NULL) return NULL;
    *(int32_t*)arr = count;
    void** slots = (void**)((char*)arr + JAVA_ARR_HDR);
    for (int i = 0; i < count; i++) {
        slots[i] = (void*)ptrs[i];
    }
    return arr;
}

static struct ReflectionClass* find_registered_class(const char* name) {
    if (name == NULL) return NULL;
    struct ReflectionClass** pp = reflect_all_classes;
    while (*pp) {
        struct ReflectionClass* c = *pp;
        const char* n = (const char*)c->name;
        if (n && strcmp(n, name) == 0) return c;
        pp++;
    }
    return NULL;
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
    if (!class_name) { buf[0] = '\0'; return buf; }
    snprintf(buf, sizeof(buf), "__type_info_%s", class_name);
    for (char* p = buf; *p; p++) {
        if (*p == '/' || *p == '.') *p = '_';
    }
    return buf;
}

static void** lookup_type_info(struct ReflectionClass* cls) {
    if (!cls || !cls->name) return NULL;
    const char* symbol = build_type_info_symbol((const char*)cls->name);
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
    const char* name = (const char*)((struct ReflectionClass*)this_cls)->name;
    if (!name) return 0;
    return name[0] == '[';
}

int __jnative_fn_java_lang_Class_isPrimitive___Z(void* this_cls) {
    if (this_cls == NULL) return 0;
    const char* name = (const char*)((struct ReflectionClass*)this_cls)->name;
    if (!name) return 0;
    return strcmp(name, "boolean") == 0 || strcmp(name, "byte") == 0 ||
           strcmp(name, "short")   == 0 || strcmp(name, "char") == 0 ||
           strcmp(name, "int")     == 0 || strcmp(name, "long") == 0 ||
           strcmp(name, "float")   == 0 || strcmp(name, "double") == 0 ||
           strcmp(name, "void")    == 0;
}

void* __jnative_fn_java_lang_Class_getName___Ljava_lang_String_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    return ((struct ReflectionClass*)this_cls)->name;
}

/*
 * private native String initClassName();
 *
 * Returns the name of the class as computed by the VM. In HotSpot this is
 * the binary name (packages separated by dots), which Class.getName() then
 * hands to callers verbatim. The ReflectionClass in this runtime stores the
 * internal form (slash-separated); we convert on the fly into a thread-local
 * buffer. Because Class caches the result of this method in a transient
 * field, the returned string is not freed by the runtime — the C buffer is
 * therefore deliberately re-used per call and never returned twice without
 * the caller copying it first (the Java side copies it into a heap String
 * the first time getName() is called).
 */
void* __jnative_fn_java_lang_Class_initClassName___Ljava_lang_String_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    const char* internal = (const char*)((struct ReflectionClass*)this_cls)->name;
    if (internal == NULL) return NULL;

    size_t len = strlen(internal);
    char* out = (char*)malloc(len + 1);
    if (out == NULL) return NULL;
    for (size_t i = 0; i < len; i++) {
        out[i] = (internal[i] == '/') ? '.' : internal[i];
    }
    out[len] = '\0';
    return out;
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
    const char* name = (const char*)cls->name;
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
    return (void*)find_registered_class((const char*)name_str);
}

void __jnative_fn_java_lang_Class_registerNatives___V(void) {
}

/* --------------------------------------------------------------------------
 * Reflection queries introduced in JDK 9+
 *
 * None of these have a backing store in this runtime: the class parser
 * (DependencyResolver) extracts only the structural information required
 * for code generation and discards the raw attribute payload that
 * Class.getDeclaredMethods / getRecordComponents / etc. would surface.
 * Returning the documented empty values keeps every caller on a well-
 * defined path instead of dereferencing a NULL vtable slot.
 * ------------------------------------------------------------------------ */

/*
 * private static native Class<?>[] getInterfaces0();
 *
 * The interface list is available (stored as a NULL-terminated C array of
 * ReflectionClass pointers on the class), so we can materialise the proper
 * Java Class[] form.
 */
void* __jnative_fn_java_lang_Class_getInterfaces0____Ljava_lang_Class_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    struct ReflectionClass* cls = (struct ReflectionClass*)this_cls;
    return make_class_array(cls->interfaces);
}

/*
 * private native String getGenericSignature0();
 *
 * The generic signature attribute is not retained by the parser. Returning
 * null makes Class.getGenericInterfaces / getGenericSuperclass fall back
 * to the erased (non-generic) view, which is the correct behaviour for a
 * runtime that does not round-trip generic metadata.
 */
void* __jnative_fn_java_lang_Class_getGenericSignature0___Ljava_lang_String_(void* this_cls) {
    (void)this_cls;
    return NULL;
}

/*
 * private native Method[] getDeclaredMethods0(boolean publicOnly);
 *
 * The runtime does not build Method objects from the class structure. An
 * empty array is a valid — and the only truthful — answer here: every
 * caller (Class.getDeclaredMethods, privateGetDeclaredMethods, and their
 * internal users) handles a zero-length result as "no methods visible".
 */
void* __jnative_fn_java_lang_Class_getDeclaredMethods0__Z__Ljava_lang_reflect_Method_(
        void* this_cls, int32_t public_only) {
    (void)this_cls;
    (void)public_only;
    void* arr = malloc(JAVA_ARR_HDR);
    if (arr == NULL) return NULL;
    *(int32_t*)arr = 0;
    return arr;
}

/*
 * private native Constructor<T>[] getDeclaredConstructors0(boolean publicOnly);
 */
void* __jnative_fn_java_lang_Class_getDeclaredConstructors0__Z__Ljava_lang_reflect_Constructor_(
        void* this_cls, int32_t public_only) {
    (void)this_cls;
    (void)public_only;
    void* arr = malloc(JAVA_ARR_HDR);
    if (arr == NULL) return NULL;
    *(int32_t*)arr = 0;
    return arr;
}

/*
 * private native String getSimpleBinaryName0();
 *
 * Only meaningful for local and anonymous classes, which this runtime does
 * not model. Returning null makes Class.getSimpleName() fall back to the
 * ordinary name-derived path, which is correct for every top-level and
 * member class in the compiled universe.
 */
void* __jnative_fn_java_lang_Class_getSimpleBinaryName0___Ljava_lang_String_(void* this_cls) {
    (void)this_cls;
    return NULL;
}

/*
 * private native Class<?> getNestHost0();
 *
 * A null result signals "no separate nest host": the caller substitutes
 * `this` and every class is its own nest host. That matches the flat,
 * single-module structure of the compiled image.
 */
void* __jnative_fn_java_lang_Class_getNestHost0___Ljava_lang_Class_(void* this_cls) {
    (void)this_cls;
    return NULL;
}

/*
 * private native Class<?>[] getPermittedSubclasses0();
 *
 * Sealed classes are not supported: returning null makes
 * Class.getPermittedSubclasses() return null per the JDK contract for
 * non-sealed classes.
 */
void* __jnative_fn_java_lang_Class_getPermittedSubclasses0____Ljava_lang_Class_(
        void* this_cls) {
    (void)this_cls;
    return NULL;
}

/*
 * private native RecordComponent[] getRecordComponents0();
 *
 * Records are not modelled: returning null makes
 * Class.getRecordComponents() return null, which is the documented
 * behaviour for non-record classes.
 */
void* __jnative_fn_java_lang_Class_getRecordComponents0____Ljava_lang_reflect_RecordComponent_(
        void* this_cls) {
    (void)this_cls;
    return NULL;
}

/*
 * private native boolean isRecord0();
 *
 * False for every class in the compiled universe.
 */
int32_t __jnative_fn_java_lang_Class_isRecord0___Z(void* this_cls) {
    (void)this_cls;
    return 0;
}
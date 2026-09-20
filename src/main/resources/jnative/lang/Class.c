#define _GNU_SOURCE
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <dlfcn.h>

struct ReflectionClass {
    void* vtable;
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

extern void* __jnative_make_string_obj(const char* bytes, int32_t len);
extern const char* __jnative_read_string_bytes(void* s, int32_t* out_len);

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);

/* Java array layout: [int32 length][payload] */
#define JAVA_ARR_HDR 8

/* --------------------------------------------------------------------------
 * Small helpers
 * ------------------------------------------------------------------------ */

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

static void* make_empty_ref_array(void) {
    void* arr = malloc(JAVA_ARR_HDR);
    if (arr == NULL) return NULL;
    *(int32_t*)arr = 0;
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
    const char* name = (const char*)((struct ReflectionClass*)this_cls)->name;
    if (name == NULL) return NULL;
    return __jnative_make_string_obj(name, (int32_t)strlen(name));
}

void* __jnative_fn_java_lang_Class_initClassName___Ljava_lang_String_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    const char* internal = (const char*)((struct ReflectionClass*)this_cls)->name;
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
 *
 *   private static native Class<?> forName0(String name,
 *                                           boolean initialize,
 *                                           ClassLoader loader,
 *                                           Class<?> caller)
 *       throws ClassNotFoundException;
 *
 * The runtime's universe of classes is fixed at build time and lives in the
 * reflect_all_classes[] table. There is no bytecode-loaded-at-runtime path
 * and no user class loader hierarchy, so the loader and caller arguments
 * are ignored. A name that is not present in the table causes the generic
 * throw helper to fire, which the Java side's ClassNotFoundException catch
 * block converts into the appropriate checked exception.
 *
 * The `initialize` flag is likewise ignored: the runtime eagerly initialises
 * every reachable class from @main (see LlvmGenerator.generateMain), so a
 * class is either already initialised by the time forName0 runs or it is not
 * part of the compiled image at all.
 */
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
        /* ClassNotFoundException */
        __jnative_throw_exception(NULL);
        return NULL;
    }

    struct ReflectionClass* cls = find_registered_class_dotted(name);
    if (cls == NULL) {
        /* ClassNotFoundException */
        __jnative_throw_exception(NULL);
        return NULL;
    }
    return (void*)cls;
}

/* --------------------------------------------------------------------------
 * desiredAssertionStatus0
 *
 *   private static native boolean desiredAssertionStatus0(Class<?> clazz);
 *
 * Asserts are always disabled in this runtime: java.lang.Class's
 * desiredAssertionStatus() path is short-circuited at the Java layer only
 * for classes whose assertion status was explicitly set, and every other
 * class reaches this native. The reference JDK consults the class's
 * per-loader assert setting; this runtime has no per-loader assert state
 * (there is only the bootstrap loader and no -ea/-da mechanism), so the
 * only correct answer is false.
 */
int32_t __jnative_fn_java_lang_Class_desiredAssertionStatus0__Ljava_lang_Class__Z(
        void* this_cls)
{
    (void)this_cls;
    return 0;
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
 * The runtime does not build Method objects from the class structure. An
 * empty array is a valid — and the only truthful — answer here.
 */
void* __jnative_fn_java_lang_Class_getDeclaredMethods0__Z__Ljava_lang_reflect_Method_(
        void* this_cls, int32_t public_only) {
    (void)this_cls;
    (void)public_only;
    return make_empty_ref_array();
}

/*
 * private native Field[] getDeclaredFields0(boolean publicOnly);
 *
 * Same rationale as getDeclaredMethods0: the class parser keeps only the
 * structural information required for code generation and never builds
 * Field objects. Every caller (Class.getDeclaredFields / getFields and
 * their internal users) handles a zero-length array as "no fields visible".
 *
 * The LLVM backend currently emits an external call to this symbol from
 * java.lang.Class's getFields path, so its presence is required for the
 * module to link.
 */
void* __jnative_fn_java_lang_Class_getDeclaredFields0__Z__Ljava_lang_reflect_Field_(
        void* this_cls, int32_t public_only) {
    (void)this_cls;
    (void)public_only;
    return make_empty_ref_array();
}

/*
 * private native Constructor<T>[] getDeclaredConstructors0(boolean publicOnly);
 */
void* __jnative_fn_java_lang_Class_getDeclaredConstructors0__Z__Ljava_lang_reflect_Constructor_(
        void* this_cls, int32_t public_only) {
    (void)this_cls;
    (void)public_only;
    return make_empty_ref_array();
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
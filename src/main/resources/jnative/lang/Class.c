#include <stddef.h>
#include <stdint.h>
#include <string.h>

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

/* public native int getModifiers(); */
int __jnative_fn_java_lang_Class_getModifiers___I(void* this_cls) {
    if (this_cls == NULL) return 0;
    struct ReflectionClass* cls = (struct ReflectionClass*)this_cls;
    return cls->modifiers;
}

/* public native Class<?> getSuperclass(); */
void* __jnative_fn_java_lang_Class_getSuperclass___Ljava_lang_Class_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    struct ReflectionClass* cls = (struct ReflectionClass*)this_cls;
    return (void*)cls->superclass;
}

/* public native Class<?>[] getInterfaces(); */
void** __jnative_fn_java_lang_Class_getInterfaces___Ljava_lang_Class_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    struct ReflectionClass* cls = (struct ReflectionClass*)this_cls;
    return (void**)cls->interfaces;
}

/* public native boolean isInterface(); */
int __jnative_fn_java_lang_Class_isInterface___Z(void* this_cls) {
    if (this_cls == NULL) return 0;
    struct ReflectionClass* cls = (struct ReflectionClass*)this_cls;
    return (cls->modifiers & 0x0200) != 0;   /* ACC_INTERFACE */
}

/* public native boolean isArray(); */
int __jnative_fn_java_lang_Class_isArray___Z(void* this_cls) {
    if (this_cls == NULL) return 0;
    struct ReflectionClass* cls = (struct ReflectionClass*)this_cls;
    const char* name = (const char*)cls->name;
    if (!name) return 0;
    /* Arrays are registered with names starting with '['. */
    return name[0] == '[';
}

/* public native boolean isPrimitive(); */
int __jnative_fn_java_lang_Class_isPrimitive___Z(void* this_cls) {
    if (this_cls == NULL) return 0;
    struct ReflectionClass* cls = (struct ReflectionClass*)this_cls;
    const char* name = (const char*)cls->name;
    if (!name) return 0;
    return strcmp(name, "boolean") == 0 || strcmp(name, "byte") == 0 ||
           strcmp(name, "short")   == 0 || strcmp(name, "char") == 0 ||
           strcmp(name, "int")     == 0 || strcmp(name, "long") == 0 ||
           strcmp(name, "float")   == 0 || strcmp(name, "double") == 0 ||
           strcmp(name, "void")    == 0;
}

/* public native String getName(); */
void* __jnative_fn_java_lang_Class_getName___Ljava_lang_String_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    struct ReflectionClass* cls = (struct ReflectionClass*)this_cls;
    return cls->name;
}

/* public native ClassLoader getClassLoader(); */
void* __jnative_fn_java_lang_Class_getClassLoader___Ljava_lang_ClassLoader_(void* this_cls) {
    (void)this_cls;
    return NULL;
}

/* todo fix */
void* __jnative_fn_java_lang_Class_getEnclosingMethod0____Ljava_lang_Object_(void* self) {
    (void)self;
    return NULL;
}

/* ---------------------------------------------------------------------------
 * Helpers for isAssignableFrom / getDeclaringClass0
 * ------------------------------------------------------------------------- */

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

/* public native boolean isAssignableFrom(Class<?> cls); */
int __jnative_fn_java_lang_Class_isAssignableFrom__Ljava_lang_Class__Z(void* this_cls, void* other_cls) {
    if (this_cls == NULL || other_cls == NULL) return 0;
    if (this_cls == other_cls) return 1;

    struct ReflectionClass* target = (struct ReflectionClass*)this_cls;
    struct ReflectionClass* cur    = (struct ReflectionClass*)other_cls;

    /* Walk the entire superclass chain of `other`, checking each class and
     * its interfaces. */
    while (cur) {
        if (cur == target) return 1;
        if (class_implements_interface(cur, target)) return 1;
        cur = cur->superclass;
    }
    return 0;
}

/* private native Class<?> getDeclaringClass0(); */
void* __jnative_fn_java_lang_Class_getDeclaringClass0___Ljava_lang_Class_(void* this_cls) {
    if (this_cls == NULL) return NULL;
    struct ReflectionClass* cls = (struct ReflectionClass*)this_cls;
    const char* name = (const char*)cls->name;
    if (name == NULL) return NULL;

    /* JDK convention: nested / local / anonymous classes have a '$' in
     * their binary name. The declaring class is the part before the last '$'. */
    const char* last_dollar = strrchr(name, '$');
    if (last_dollar == NULL || last_dollar == name || last_dollar[1] == '\0') {
        return NULL;   /* top-level class -> no declaring class */
    }

    size_t outer_len = (size_t)(last_dollar - name);
    char outer[512];
    if (outer_len >= sizeof(outer)) return NULL;
    memcpy(outer, name, outer_len);
    outer[outer_len] = '\0';

    struct ReflectionClass* outer_cls = find_registered_class(outer);
    return (void*)outer_cls;
}
#define _GNU_SOURCE
#include <stddef.h>
#include <stdint.h>
#include <string.h>

/*
 * ClassLoader native methods.
 *
 * This runtime has a single, build-time-known universe of classes: every
 * class that reaches the LLVM emitter is materialised as a @__type_info_*
 * global and — if it is reachable via reflection — registered in the
 * reflect_all_classes[] table. There is no bytecode-loaded-at-runtime path
 * and no user-defined class loader hierarchy. The two natives below are the
 * only ones that the JDK's ClassLoader implementation actually reaches in
 * that setup.
 */

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

extern struct ReflectionClass* reflect_all_classes[] __attribute__((weak));

/* --------------------------------------------------------------------------
 * Lookup helper — finds a ReflectionClass by its binary name. Accepts both
 * slash-separated ("java/lang/Object") and dot-separated ("java.lang.Object")
 * forms, because ClassLoader.findLoadedClass0 receives the binary name as it
 * was spelled in the original source, whereas reflect_all_classes[] is keyed
 * on the internal slash form emitted by LlvmGlobalEmitter.
 * ------------------------------------------------------------------------ */
static struct ReflectionClass* find_registered_class(const char* name) {
    if (name == NULL || reflect_all_classes == NULL) return NULL;

    struct ReflectionClass** pp = reflect_all_classes;
    while (*pp) {
        const char* cls_name = (const char*)(*pp)->name;
        if (cls_name && strcmp(cls_name, name) == 0) {
            return *pp;
        }
        pp++;
    }
    return NULL;
}

static struct ReflectionClass* find_registered_class_dotted(const char* name) {
    if (name == NULL) return NULL;

    struct ReflectionClass* cls = find_registered_class(name);
    if (cls != NULL) return cls;

    /* Convert '.' -> '/' in a stack buffer. Binary class names used by
     * ClassLoader are short (well under 512 chars in practice). */
    char buf[512];
    size_t n = strlen(name);
    if (n >= sizeof(buf)) return NULL;
    memcpy(buf, name, n + 1);
    for (char* p = buf; *p; p++) {
        if (*p == '.') *p = '/';
    }
    return find_registered_class(buf);
}

/* --------------------------------------------------------------------------
 * protected final native Class<?> findLoadedClass0(String name);
 *
 * Returns the already-loaded class with the given binary name, or null if it
 * is not currently loaded. Callers (ClassLoader.loadClass) treat a null
 * result as "not yet loaded" and proceed to the defineClass path, which in
 * this runtime returns null and surfaces a ClassNotFoundException at the
 * Java layer — the correct outcome for any class that is not part of the
 * compiled universe.
 * ------------------------------------------------------------------------ */
void* __jnative_fn_java_lang_ClassLoader_findLoadedClass0__Ljava_lang_String__Ljava_lang_Class_(
        void* this_loader, void* name_str) {
    (void)this_loader;
    if (name_str == NULL) return NULL;
    return (void*)find_registered_class_dotted((const char*)name_str);
}

/* --------------------------------------------------------------------------
 * private native Class<?> defineClass1(ClassLoader loader, String name,
 *     byte[] b, int off, int len, ProtectionDomain pd, String source);
 *
 * This runtime has no runtime bytecode loader. Every class must already be
 * present in the compiled module. Returning null makes ClassLoader.loadClass
 * raise ClassNotFoundException with a meaningful message at the Java level,
 * instead of dereferencing an undefined symbol or corrupting the heap.
 * ------------------------------------------------------------------------ */
void* __jnative_fn_java_lang_ClassLoader_defineClass1__Ljava_lang_ClassLoader_Ljava_lang_String__BIILjava_security_ProtectionDomain_Ljava_lang_String__Ljava_lang_Class_(
        void* this_loader,
        void* loader,
        void* name,
        void* b,
        int32_t off,
        int32_t len,
        void* protection_domain,
        void* source) {
    (void)this_loader;
    (void)loader;
    (void)name;
    (void)b;
    (void)off;
    (void)len;
    (void)protection_domain;
    (void)source;
    return NULL;
}

/* --------------------------------------------------------------------------
 * private native Class<?> defineClass2(ClassLoader loader, String name,
 *     ByteBuffer b, int off, int len, ProtectionDomain pd, String source);
 *
 * The ByteBuffer overload, reached by some JDK code paths. Same rationale
 * and same return value as defineClass1.
 * ------------------------------------------------------------------------ */
void* __jnative_fn_java_lang_ClassLoader_defineClass2__Ljava_lang_ClassLoader_Ljava_lang_String_Ljava_nio_ByteBuffer_IILjava_security_ProtectionDomain_Ljava_lang_String__Ljava_lang_Class_(
        void* this_loader,
        void* loader,
        void* name,
        void* b,
        int32_t off,
        int32_t len,
        void* protection_domain,
        void* source) {
    (void)this_loader;
    (void)loader;
    (void)name;
    (void)b;
    (void)off;
    (void)len;
    (void)protection_domain;
    (void)source;
    return NULL;
}

/* --------------------------------------------------------------------------
 * private static native void registerNatives();
 *
 * Called from ClassLoader.<clinit>. This runtime resolves every native
 * method through its statically-linked __jnative_fn_<class>_<method>_<desc>
 * symbol emitted by the LLVM backend, so there is nothing to register. The
 * symbol must exist because ClassLoader.<clinit> emits a native call to it.
 * ------------------------------------------------------------------------ */
void __jnative_fn_java_lang_ClassLoader_registerNatives___V(void) {
}
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
 * and no user-defined class loader hierarchy. The natives below are the
 * only ones that the JDK's ClassLoader implementation actually reaches in
 * that setup.
 */

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

extern struct ReflectionClass* reflect_all_classes[] __attribute__((weak));

extern const char* __jnative_read_string_bytes(void* s, int32_t* out_len);

/* --------------------------------------------------------------------------
 * Lookup helper — finds a ReflectionClass by its binary name. Accepts both
 * slash-separated ("java/lang/Object") and dot-separated ("java.lang.Object")
 * forms, because ClassLoader's natives receive the binary name as it was
 * spelled in the original source, whereas reflect_all_classes[] is keyed on
 * the internal slash form emitted by LlvmGlobalEmitter.
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
    int32_t len = 0;
    const char* name = __jnative_read_string_bytes(name_str, &len);
    (void)len;
    return (void*)find_registered_class_dotted(name);
}

/* --------------------------------------------------------------------------
 * private static native Class<?> findBootstrapClass(String name);
 *
 * Bootstrap-loader counterpart of findLoadedClass0. In this runtime the
 * bootstrap loader is the only loader and there is no separate bootstrap
 * class table — every class reachable at build time is already present in
 * reflect_all_classes[]. The name arrives as a binary name (dots), so we
 * reuse the dotted-form lookup.
 *
 * The caller (ClassLoader.findBootstrapClassOrNull) treats a null result as
 * "not present in the bootstrap loader" and falls through to the ordinary
 * loadClass path, which will in turn raise ClassNotFoundException at the
 * Java layer for any name outside the compiled universe. That is the
 * correct behaviour: this runtime cannot load classes that were not part
 * of the build-time reachability closure.
 * ------------------------------------------------------------------------ */
void* __jnative_fn_java_lang_ClassLoader_findBootstrapClass__Ljava_lang_String__Ljava_lang_Class_(
        void* name_str) {
    if (name_str == NULL) return NULL;
    int32_t len = 0;
    const char* name = __jnative_read_string_bytes(name_str, &len);
    (void)len;
    return (void*)find_registered_class_dotted(name);
}

/* --------------------------------------------------------------------------
 * private static native Class<?> defineClass0(ClassLoader loader,
 *                                             Class<?> lookup,
 *                                             String name,
 *                                             byte[] b,
 *                                             int off,
 *                                             int len,
 *                                             ProtectionDomain pd,
 *                                             boolean initialize,
 *                                             int flags,
 *                                             Object classData);
 *
 * The modern (JDK 9+) entry point that ClassLoader.defineClass delegates
 * to, reached from ClassLoader's own defineClass paths and from
 * MethodHandles.Lookup.defineClass / java.lang.invoke's hidden-class
 * machinery. The `lookup` argument is the caller's Lookup object, `flags`
 * carries the access-mode bits that distinguish a normal class from a
 * hidden one, and `classData` is the opaque token that the JDK's Class
 * object exposes through Class.getClassData.
 *
 * This runtime has no runtime bytecode loader at all: every class that
 * reaches the LLVM emitter is compiled into the executable at build
 * time, and there is no mechanism by which new bytes can be turned into
 * a live Class object. Returning NULL is the documented "loading
 * refused" answer, and every Java caller of this native translates a
 * NULL return into the appropriate checked exception:
 *
 *   - ClassLoader.defineClass -> ClassFormatError / NoClassDefFoundError
 *   - MethodHandles.Lookup.defineClass -> IllegalArgumentException
 *   - the hidden-class path in InnerClassLambdaMetafactory -> an
 *     InternalError about the VM not supporting hidden classes
 *
 * All three failure modes are well-defined and the Java layer already
 * handles them. Returning NULL is therefore both truthful and safe.
 *
 * The arguments are deliberately ignored. There is no class table to
 * look the name up in, no bytecode buffer to parse, no protection
 * domain to attach, and no class-data slot to write into.
 * ------------------------------------------------------------------------ */
void* __jnative_fn_java_lang_ClassLoader_defineClass0__Ljava_lang_ClassLoader_Ljava_lang_Class_Ljava_lang_String__BIILjava_security_ProtectionDomain_ZILjava_lang_Object__Ljava_lang_Class_(
        void* this_loader,
        void* lookup,
        void* name,
        void* b,
        int32_t off,
        int32_t len,
        void* protection_domain,
        int32_t initialize,
        int32_t flags,
        void* class_data) {
    (void)this_loader;
    (void)lookup;
    (void)name;
    (void)b;
    (void)off;
    (void)len;
    (void)protection_domain;
    (void)initialize;
    (void)flags;
    (void)class_data;
    return NULL;
}

/* --------------------------------------------------------------------------
 * private native Class<?> defineClass1(ClassLoader loader, String name,
 *     byte[] b, int off, int len, ProtectionDomain pd, String source);
 *
 * Pre-JDK-9 signature. Retained for builds whose ClassLoader still emits
 * the older call site. Same rationale and same return value as
 * defineClass0.
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
#include "jnative_platform.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>

#include "jnative_runtime.h"

void* __jnative_fn_jdk_internal_loader_BootLoader_getSystemPackageNames____Ljava_lang_String_(void)
{
    /* Worst case: every class has a distinct package. */
    size_t capacity = 64;
    size_t count = 0;
    char** packages = (char**)malloc(capacity * sizeof(char*));
    if (packages == NULL) {
        return NULL;
    }

    ReflectionClass** pp = reflect_all_classes;
    while (*pp != NULL) {
        const char* clsName = (*pp)->cname;
        pp++;

        if (clsName == NULL) continue;

        const char* lastSlash = strrchr(clsName, '/');
        if (lastSlash == NULL || lastSlash == clsName) {
            /* Unnamed package - skip. */
            continue;
        }

        size_t pkgLen = (size_t)(lastSlash - clsName);

        /* Build a dotted package name. */
        char* pkg = (char*)malloc(pkgLen + 1);
        if (pkg == NULL) continue;
        for (size_t i = 0; i < pkgLen; i++) {
            pkg[i] = (clsName[i] == '/') ? '.' : clsName[i];
        }
        pkg[pkgLen] = '\0';

        /* Deduplicate. */
        int already = 0;
        for (size_t i = 0; i < count; i++) {
            if (strcmp(packages[i], pkg) == 0) {
                already = 1;
                break;
            }
        }
        if (already) {
            free(pkg);
            continue;
        }

        if (count == capacity) {
            capacity *= 2;
            char** grown = (char**)realloc(packages, capacity * sizeof(char*));
            if (grown == NULL) {
                free(pkg);
                break;
            }
            packages = grown;
        }
        packages[count++] = pkg;
    }

    /*
     * Pack the result into a Java String[]. The slots must hold real
     * java.lang.String objects, not the raw C names collected above —
     * the JDK reads these through String.length()/charAt(). Each name is
     * converted first, and the array itself is allocated through
     * jnative_ref_array_of_class() so that its header carries the
     * [Ljava/lang/String; class mirror.
     */
    if (count > 0) {
        for (size_t i = 0; i < count; i++) {
            void* str = jnative_string(packages[i]);
            if (str == NULL) {
                /* Release the names gathered so far and give up. */
                for (size_t j = 0; j < count; j++) free(packages[j]);
                free(packages);
                return NULL;
            }
            packages[i] = (char*)str;
        }
    }

    void* array = jnative_ref_array_of_class((void**)packages, (int32_t)count,
                                             "[Ljava/lang/String;");
    free(packages);
    return array;
}

/*
 * private static native String getSystemPackageLocation(String name);
 *
 * Returns the filesystem location from which the named system package was
 * loaded — typically the path of the modular image, JAR, or directory that
 * contains the package's classes. The JDK uses the result to build
 * Package objects in Package.getPackages()/getPackage(), and to fill in
 * the package's display name for diagnostics.
 *
 * This runtime has no filesystem loader at all: every class that reaches
 * the LLVM emitter is compiled directly into the executable, and there is
 * no per-package source location to report. Returning NULL is the
 * documented contract for "no location available": the JDK callers
 * (BootLoader.getDefinedPackage / getSystemPackage / getSystemPackages)
 * check for null and fall back to constructing the Package without a
 * location, which is exactly what happens under HotSpot for the unnamed
 * module and for packages coming from the runtime image itself.
 *
 * The name arrives with '/' separators, matching the internal form used
 * by the ClassLoader hierarchy. It is read but not consulted — the answer
 * is the same for every package.
 */
typedef struct {
    const char*    package;
    const char*    location;
    const uint8_t* manifest;
    int32_t        manifest_len;
} JNativePackageLocation;

extern const JNativePackageLocation jnative_package_locations[] JNATIVE_WEAK;
extern const int64_t jnative_package_locations_size JNATIVE_WEAK;

static const JNativePackageLocation* package_location(void* name_str) {
    if (name_str == NULL) return NULL;
    if ((uintptr_t)&jnative_package_locations_size == 0) return NULL;
    if ((uintptr_t)jnative_package_locations == 0) return NULL;
    char name[1024];
    int32_t len = __jnative_read_string_into(name_str, name, (int32_t)sizeof(name));
    if (len <= 0 || len >= (int32_t)sizeof(name)) return NULL;
    name[len] = '\0';
    for (int32_t i = 0; i < len; i++) {
        if (name[i] == '.') name[i] = '/';
    }
    int64_t lo = 0;
    int64_t hi = jnative_package_locations_size;
    while (lo < hi) {
        int64_t mid = lo + (hi - lo) / 2;
        int cmp = strcmp(jnative_package_locations[mid].package, name);
        if (cmp == 0) return &jnative_package_locations[mid];
        if (cmp < 0) lo = mid + 1;
        else         hi = mid;
    }
    return NULL;
}

void* __jnative_fn_jdk_internal_loader_BootLoader_getSystemPackageLocation__Ljava_lang_String__Ljava_lang_String_(
        void* name_str)
{
    const JNativePackageLocation* loc = package_location(name_str);
    return loc != NULL ? jnative_string(loc->location) : NULL;
}

extern void* fn_jdk_internal_loader_ClassLoaders_bootLoader___Ljdk_internal_loader_BuiltinClassLoader_(void) JNATIVE_WEAK;
extern void* fn_java_lang_ClassLoader_getDefinedPackage__Ljava_lang_String__Ljava_lang_Package_(
        void* self, void* name) JNATIVE_WEAK;
extern void* fn_jdk_internal_loader_BuiltinClassLoader_defineOrCheckPackage__Ljava_lang_String_Ljava_util_jar_Manifest_Ljava_net_URL__Ljava_lang_Package_(
        void* self, void* pn, void* man, void* url) JNATIVE_WEAK;
extern void fn_java_util_jar_Manifest__init___Ljava_io_InputStream__V(void* self, void* in) JNATIVE_WEAK;
extern char __type_info_java_io_IOException JNATIVE_WEAK;

JNATIVE_NORETURN static void boot_loader_internal_error(const char* msg) {
    __jnative_throw_exception(__jnative_construct_exception("vtable_java_lang_InternalError", msg));
}

static void* package_manifest(const JNativePackageLocation* loc) {
    if (loc->manifest == NULL || loc->manifest_len <= 0) return NULL;
    ReflectionClass* cls = jnative_class_by_name("java/util/jar/Manifest");
    void* manifest = cls != NULL ? jnative_alloc_object(cls) : NULL;
    if (manifest == NULL) boot_loader_internal_error("BootLoader: java.util.jar.Manifest is not in the image");
    void* bytes = jnative_byte_array(loc->manifest, loc->manifest_len);
    if (bytes == NULL) __jnative_throw_out_of_memory_error_ctx("BootLoader package manifest");
    void* in = __jnative_make_byte_array_input_stream(bytes);
    if (in == NULL) boot_loader_internal_error("BootLoader: cannot open package manifest");

    jmp_buf jb;
    if (setjmp(jb) == 0) {
        __jnative_push_catch(&jb, NULL);
        fn_java_util_jar_Manifest__init___Ljava_io_InputStream__V(manifest, in);
        __jnative_pop_catch();
        return manifest;
    }
    __jnative_pop_catch();
    void* exc = __jnative_get_exception_object();
    if ((uintptr_t)&__type_info_java_io_IOException != 0
        && __jnative_catch_matches(exc, &__type_info_java_io_IOException)) {
        return NULL;
    }
    __jnative_throw_exception(exc);
}

void* __jnative_override_jdk_internal_loader_BootLoader_getDefinedPackage(void* pn)
{
    if (pn == NULL) __jnative_throw_null_pointer_exception();
    if (fn_jdk_internal_loader_ClassLoaders_bootLoader___Ljdk_internal_loader_BuiltinClassLoader_ == NULL
        || fn_java_lang_ClassLoader_getDefinedPackage__Ljava_lang_String__Ljava_lang_Package_ == NULL
        || fn_jdk_internal_loader_BuiltinClassLoader_defineOrCheckPackage__Ljava_lang_String_Ljava_util_jar_Manifest_Ljava_net_URL__Ljava_lang_Package_ == NULL
        || fn_java_util_jar_Manifest__init___Ljava_io_InputStream__V == NULL) {
        boot_loader_internal_error("BootLoader.getDefinedPackage: package definition is not linked");
    }
    void* loader = fn_jdk_internal_loader_ClassLoaders_bootLoader___Ljdk_internal_loader_BuiltinClassLoader_();
    if (loader == NULL) return NULL;
    void* pkg = fn_java_lang_ClassLoader_getDefinedPackage__Ljava_lang_String__Ljava_lang_Package_(loader, pn);
    if (pkg != NULL) return pkg;
    const JNativePackageLocation* loc = package_location(pn);
    if (loc == NULL) return NULL;
    return fn_jdk_internal_loader_BuiltinClassLoader_defineOrCheckPackage__Ljava_lang_String_Ljava_util_jar_Manifest_Ljava_net_URL__Ljava_lang_Package_(
        loader, pn, package_manifest(loc), NULL);
}

void __jnative_fn_jdk_internal_loader_BootLoader_setBootLoaderUnnamedModule0__Ljava_lang_Module__V(void* module)
{
    (void)module;
}
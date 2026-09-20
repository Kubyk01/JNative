#include <stdint.h>
#include <stdlib.h>
#include <string.h>

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

extern const char* __jnative_read_string_bytes(void* s, int32_t* out_len);

/* Java array layout: [int32 length][pointer elements] (see create_string_array) */
#define JAVA_ARR_HDR 8

/*
 * Returns a String[] of unique package names derived from every registered
 * class name. Class names use '/' as separator; package names use '.'.
 * Top-level classes (no '/' in the name) belong to the unnamed package and
 * are skipped, matching the JDK's behaviour of not returning "".
 */
void* __jnative_fn_jdk_internal_loader_BootLoader_getSystemPackageNames____Ljava_lang_String_(void)
{
    /* Worst case: every class has a distinct package. */
    size_t capacity = 64;
    size_t count = 0;
    char** packages = (char**)malloc(capacity * sizeof(char*));
    if (packages == NULL) {
        return NULL;
    }

    struct ReflectionClass** pp = reflect_all_classes;
    while (pp != NULL && *pp != NULL) {
        const char* clsName = (const char*)(*pp)->name;
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

    /* Pack result into a Java String[] object. */
    size_t totalBytes = JAVA_ARR_HDR + count * sizeof(void*);
    void* array = malloc(totalBytes);
    if (array == NULL) {
        for (size_t i = 0; i < count; i++) free(packages[i]);
        free(packages);
        return NULL;
    }
    *(int32_t*)array = (int32_t)count;

    void** slots = (void**)((char*)array + JAVA_ARR_HDR);
    for (size_t i = 0; i < count; i++) {
        slots[i] = packages[i];
    }
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
void* __jnative_fn_jdk_internal_loader_BootLoader_getSystemPackageLocation__Ljava_lang_String__Ljava_lang_String_(
        void* name_str)
{
    (void)name_str;
    return NULL;
}

void __jnative_fn_jdk_internal_loader_BootLoader_setBootLoaderUnnamedModule0__Ljava_lang_Module__V(void* module)
{
    (void)module;
}
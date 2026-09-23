#include <stdint.h>
#include <stdlib.h>
#include <string.h>

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
    while (pp != NULL && *pp != NULL) {
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
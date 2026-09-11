#include <stdint.h>
#include <stdlib.h>
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

/* Java array layout: [int32 length][pointer elements] (see create_string_array) */
#define JAVA_ARR_HDR 4

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
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <execinfo.h>
#include "jnative_runtime.h"

/*
 * JAVA_ARR_HDR is taken from jnative_runtime.h; the local `#define ... 8`
 * that used to sit here shadowed the real header size and made this file
 * build arrays whose payload started at the wrong offset.
 */

static const char* extract_class_name(const char* symbol, char* buffer, size_t size) {
    if (!symbol) return NULL;
    const char* p = symbol;
    if (strncmp(p, "__jnative_fn_", 13) == 0) {
        p += 13;
    } else if (strncmp(p, "fn_", 3) == 0) {
        p += 3;
    } else {
        return NULL;
    }

    const char* dunder = strstr(p, "__");
    if (!dunder) return NULL;

    const char* last_single = NULL;
    for (const char* q = p; q < dunder; q++) {
        if (*q == '_') last_single = q;
    }
    if (!last_single || last_single == p) return NULL;

    size_t len = (size_t)(last_single - p);
    if (len >= size) len = size - 1;
    memcpy(buffer, p, len);
    buffer[len] = '\0';
    return buffer;
}

static struct ReflectionClass* lookup_class(const char* name) {
    if (!name || !reflect_all_classes) return NULL;
    struct ReflectionClass** pp = reflect_all_classes;
    while (*pp) {
        const char* n = (*pp)->cname;
        if (n && strcmp(n, name) == 0) return *pp;
        pp++;
    }
    return NULL;
}

void* __jnative_fn_java_lang_SecurityManager_getClassContext____Ljava_lang_Class_(void) {
    void* frames[256];
    int frame_count = backtrace(frames, 256);

    struct ReflectionClass* classes[256];
    int class_count = 0;

    for (int i = 0; i < frame_count; i++) {
        Dl_info info;
        if (dladdr(frames[i], &info) == 0) continue;
        if (!info.dli_sname) continue;

        char class_name[512];
        if (!extract_class_name(info.dli_sname, class_name, sizeof(class_name))) continue;
        struct ReflectionClass* cls = lookup_class(class_name);
        if (!cls) continue;

        if (class_count > 0 && classes[class_count - 1] == cls) continue;
        classes[class_count++] = cls;
    }

    /* Class[] — the descriptor matches the Java return type. */
    void* array = jnative_ref_array_of_class((void**)classes, class_count,
                                             "[Ljava/lang/Class;");
    if (!array) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }
    return array;
}
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <dbghelp.h>
#pragma comment(lib, "dbghelp.lib")
#endif

struct ReflectionField {
    void* name;
    void* descriptor;
    int offset;
    int modifiers;
};

struct ReflectionMethod {
    void* name;
    void* descriptor;
    void* adaptor;
    int modifiers;
};

struct ReflectionConstructor {
    void* descriptor;
    void* adaptor;
    int modifiers;
};

struct ReflectionClass {
    void* name;
    struct ReflectionClass* superclass;
    struct ReflectionClass** interfaces;
    struct ReflectionMethod** methods;
    struct ReflectionField** fields;
    struct ReflectionConstructor** constructors;
    int modifiers;
    int object_size;
};

extern struct ReflectionClass* reflect_all_classes[];

static const char* extract_class_name(const char* func_name) {
    static char class_name[256];
    const char* p = func_name;

    if (strncmp(p, "__jnative_fn_", 13) == 0) {
        p += 13;
    } else if (strncmp(p, "fn_", 3) == 0) {
        p += 3;
    } else {
        return NULL;
    }

    const char* end = strchr(p, '_');
    if (!end) return NULL;

    size_t len = end - p;
    if (len >= sizeof(class_name)) len = sizeof(class_name) - 1;
    memcpy(class_name, p, len);
    class_name[len] = '\0';
    return class_name;
}

static struct ReflectionClass* find_class(const char* class_name) {
    if (!class_name) return NULL;
    struct ReflectionClass** pp = reflect_all_classes;
    while (*pp) {
        struct ReflectionClass* cls = *pp;
        const char* cls_name = (const char*)cls->name;
        if (cls_name && strcmp(cls_name, class_name) == 0) {
            return cls;
        }
        pp++;
    }
    return NULL;
}

#if defined(__linux__) || defined(__APPLE__)
static const char* get_caller_function_name(void) {
    void* ret_addr = __builtin_return_address(2);
    if (!ret_addr) return NULL;

    Dl_info info;
    if (dladdr(ret_addr, &info) == 0) return NULL;
    return info.dli_sname;
}
#elif defined(_WIN32)
static const char* get_caller_function_name(void) {
    void* ret_addr = __builtin_return_address(2);
    if (!ret_addr) return NULL;

    static int sym_initialized = 0;
    if (!sym_initialized) {
        SymInitialize(GetCurrentProcess(), NULL, TRUE);
        sym_initialized = 1;
    }

    DWORD64 addr = (DWORD64)ret_addr;
    DWORD64 displacement = 0;
    char buffer[sizeof(SYMBOL_INFO) + MAX_SYM_NAME];
    PSYMBOL_INFO pSymbol = (PSYMBOL_INFO)buffer;
    pSymbol->SizeOfStruct = sizeof(SYMBOL_INFO);
    pSymbol->MaxNameLen = MAX_SYM_NAME;

    if (SymFromAddr(GetCurrentProcess(), addr, &displacement, pSymbol)) {
        return pSymbol->Name;
    }
    return NULL;
}
#else
#error "Unsupported platform"
#endif

void* __jnative_fn_jdk_internal_reflect_Reflection_getCallerClass___Ljava_lang_Class_(void) {
    const char* func_name = get_caller_function_name();
    if (!func_name) return NULL;

    const char* class_name = extract_class_name(func_name);
    if (!class_name) return NULL;

    struct ReflectionClass* cls = find_class(class_name);
    return (void*)cls;
}
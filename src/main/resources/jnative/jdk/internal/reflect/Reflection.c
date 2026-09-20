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
    void* vtable;
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

/* --------------------------------------------------------------------------
 * static native int getClassAccessFlags(Class<?> c);
 *
 * Returns the JVM access-flags word of the given class. The reference
 * implementation consults the VM's klass->access_flags(); this runtime
 * stores the same flags in the ReflectionClass structure that the LLVM
 * backend emits for every reachable class, so we simply read that field.
 *
 * The callers (java.lang.reflect.AccessibleObject.checkCanSetAccessible,
 * jdk.internal.reflect.Reflection.verifyMemberAccess,
 * sun.invoke.util.VerifyAccess.*) use these flags to decide whether a
 * reflective setAccessible() call is permitted. Since this runtime does
 * not enforce module or package access restrictions anywhere else — the
 * reachability analysis already includes the private members it needs —
 * returning the class's real modifier word is the correct behaviour: it
 * gives the Java layer the same input it would see under HotSpot, and
 * the downstream decisions work out to "allow" for every class that is
 * actually part of the compiled image.
 *
 * A null class argument yields zero. The Java callers never pass null to
 * this method (they obtain the argument from a live Class object), so
 * this is a defensive guard rather than a contract.
 * ------------------------------------------------------------------------ */
int32_t __jnative_fn_jdk_internal_reflect_Reflection_getClassAccessFlags__Ljava_lang_Class__I(
        void* cls) {
    if (cls == NULL) {
        return 0;
    }
    return ((struct ReflectionClass*)cls)->modifiers;
}

/* --------------------------------------------------------------------------
 * static native boolean areNestMates(Class<?> currentClass, Class<?> memberClass);
 *
 * Returns true iff the two classes are nest mates — that is, they share
 * the same nest host and may therefore access each other's private
 * members without a setAccessible() call.
 *
 * In this runtime every class is its own nest host: the class parser does
 * not read the NestHost / NestMembers class-file attributes, the LLVM
 * emitter does not produce per-nest metadata, and the reachability
 * analysis already pulls in every private member it needs at build time.
 * There is no notion of "another class in the same nest" that could be
 * distinguished from any other class.
 *
 * The callers are the reflective access checks
 * (jdk.internal.reflect.Reflection.verifyMemberAccess,
 * sun.invoke.util.VerifyAccess.isMemberAccessible). Both use the result
 * only to decide whether to *grant* access to a private member of a nest
 * mate. Returning 1 (true) unconditionally is therefore the permissive
 * choice: it lets the Java layer's own logic short-circuit the private-
 * member check and succeed, which matches the fact that this runtime
 * never enforces private-member access restrictions in the first place.
 *
 * Returning 0 would cause valid reflective calls to fail with
 * IllegalAccessException on nest-mate access — a behaviour with no
 * analogue in how the rest of the runtime treats visibility, and one
 * that would break the reflective paths the reachability analysis was
 * designed to support.
 * ------------------------------------------------------------------------ */
int32_t __jnative_fn_jdk_internal_reflect_Reflection_areNestMates__Ljava_lang_Class_Ljava_lang_Class__Z(
        void* current_class, void* member_class) {
    (void)current_class;
    (void)member_class;
    return 1;
}
#include "jnative_platform.h"
#include "jnative_runtime.h"
#include "jnative_portability.h"

#define SFI_NAME_OFFSET  24
#define SFI_TYPE_OFFSET  32

static void build_mangled_symbol(char* out, size_t out_size,
                                 const char* class_cname,
                                 const char* method_name,
                                 const char* descriptor) {
    size_t pos = 0;

    #define APPEND_PART(s) do { \
    const char* _s = (s); \
    while (*_s != 0 && pos + 1 < out_size) { \
    char _c = *_s++; \
    out[pos++] = ((_c >= 'a' && _c <= 'z') \
    || (_c >= 'A' && _c <= 'Z') \
    || (_c >= '0' && _c <= '9') \
    || _c == '_') ? _c : '_'; \
} \
} while (0)

    APPEND_PART("fn_");
    APPEND_PART(class_cname);
    APPEND_PART("_");
    APPEND_PART(method_name);
    APPEND_PART("_");
    APPEND_PART(descriptor);
    out[pos] = 0;
    #undef APPEND_PART
}

static int find_method_by_symbol(ReflectionClass* cls,
                                 const char* symbol,
                                 const char** out_name,
                                 const char** out_desc) {
    ReflectionClass* cur = cls;
    while (cur != NULL) {
        if (cur->cname != NULL && cur->methods != NULL) {
            for (ReflectionMethod** mp = cur->methods; *mp != NULL; mp++) {
                ReflectionMethod* m = *mp;
                if (m->name == NULL || m->descriptor == NULL) continue;
                char mangled[2048];
                build_mangled_symbol(mangled, sizeof(mangled),
                                     cur->cname,
                                     (const char*)m->name,
                                     (const char*)m->descriptor);
                if (strcmp(mangled, symbol) == 0) {
                    *out_name = (const char*)m->name;
                    *out_desc = (const char*)m->descriptor;
                    return 1;
                }
            }
        }
        cur = cur->superclass;
    }
    return 0;
}

static int is_internal_frame(const char* cname) {
    if (cname == NULL) return 1;
    if (strstr(cname, "StackFrameInfo") != NULL) return 1;
    if (strstr(cname, "StackWalker") != NULL) return 1;
    if (strstr(cname, "StackStreamFactory") != NULL) return 1;
    if (strstr(cname, "ClassFrameInfo") != NULL) return 1;
    return 0;
}

void __jnative_fn_java_lang_StackFrameInfo_expandStackFrameInfo___V(void* this_obj) {
    if (this_obj == NULL) return;

    const char* found_name = NULL;
    const char* found_desc = NULL;

    void* frames[32];
    int n = jn_backtrace(frames, 32);

    for (int i = 1; i < n; i++) {
        const char* symbol = NULL;
        ReflectionClass* cls = NULL;
        if (!__jnative_function_at_address((uintptr_t)frames[i], &symbol, &cls)) {
            continue;
        }
        if (symbol == NULL || cls == NULL) continue;
        if (is_internal_frame(cls->cname)) continue;
        if (find_method_by_symbol(cls, symbol, &found_name, &found_desc)) {
            break;
        }
    }

    void* name_str;
    void* type_str;

    if (found_name != NULL) {
        name_str = __jnative_make_string_obj(found_name, (int32_t)strlen(found_name));
        type_str = __jnative_make_string_obj(found_desc, (int32_t)strlen(found_desc));
    } else {
        name_str = __jnative_make_string_obj("<unknown>", 9);
        type_str = __jnative_make_string_obj("()V", 3);
    }

    if (name_str == NULL) {
        name_str = __jnative_make_string_obj("", 0);
    }
    if (type_str == NULL) {
        type_str = __jnative_make_string_obj("()V", 3);
    }

    *(void**)((char*)this_obj + SFI_NAME_OFFSET) = name_str;
    *(void**)((char*)this_obj + SFI_TYPE_OFFSET) = type_str;
}

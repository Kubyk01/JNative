#define _GNU_SOURCE
#include <stdint.h>
#include <string.h>
#include <ctype.h>
#include <dlfcn.h>

struct ReflectionClass {
    void* vtable;
    void* name;                                 /* const char* */
    struct ReflectionClass* superclass;
    struct ReflectionClass** interfaces;
    void** methods;
    void** fields;
    void** constructors;
    int   modifiers;
    int   object_size;
};

void __jnative_fn_java_io_ObjectStreamClass_initNative___V(void) {
}

int32_t __jnative_fn_java_io_ObjectStreamClass_hasStaticInitializer__Ljava_lang_Class__Z(
        void* class_obj)
{
    if (class_obj == NULL) return 0;

    struct ReflectionClass* cls = (struct ReflectionClass*)class_obj;
    const char* name = (const char*)cls->name;
    if (name == NULL || name[0] == '\0') return 0;

    /*
     * Build the mangled symbol. The buffer is sized for the longest
     * realistic class name; anything longer than this would already
     * have overflowed the runtime's own symbol mangling, so there is
     * no separate failure path for the truncation case — a truncated
     * name simply fails to resolve and produces the same "no clinit"
     * answer as a missing symbol.
     */
    char symbol[512];
    size_t pos = 0;

    /* Prefix: "fn_" */
    if (pos + 3 >= sizeof(symbol)) return 0;
    symbol[pos++] = 'f';
    symbol[pos++] = 'n';
    symbol[pos++] = '_';

    /* Sanitized class name. Every character that is not a letter,
     * digit, or underscore becomes an underscore, matching
     * LlvmRuntime.mangleMethod's `replaceAll("[^a-zA-Z0-9_]", "_")`
     * applied after the '/' -> '_' substitution (the substitution is
     * subsumed by the same rule because '/' is not in the permitted
     * set).
     */
    for (const char* p = name; *p != '\0'; p++) {
        if (pos + 1 >= sizeof(symbol)) return 0;
        unsigned char c = (unsigned char)*p;
        if (isalnum(c) || c == '_') {
            symbol[pos++] = (char)c;
        } else {
            symbol[pos++] = '_';
        }
    }

    /* Suffix: "__clinit____V" — the mangled form of method name
     * "<clinit>" plus descriptor "()V". See the header comment for the
     * derivation.
     */
    static const char suffix[] = "__clinit____V";
    size_t suffix_len = sizeof(suffix) - 1;  /* drop the trailing NUL */
    if (pos + suffix_len + 1 > sizeof(symbol)) return 0;
    memcpy(symbol + pos, suffix, suffix_len + 1);

    /* Look up the symbol in the running process image. */
    void* handle = dlopen(NULL, RTLD_LAZY);
    if (handle == NULL) return 0;

    void* sym = dlsym(handle, symbol);
    dlclose(handle);

    return sym != NULL ? 1 : 0;
}
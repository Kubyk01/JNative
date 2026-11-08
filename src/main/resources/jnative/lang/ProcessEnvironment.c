#if defined(_WIN32)

#ifndef WIN32_LEAN_AND_MEAN
#  define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <stdint.h>
#include <stdlib.h>

#include "jnative_runtime.h"

extern JNativeVTable vtable_java_lang_String;

static void* pe_make_string(const WCHAR* w, int32_t n)
{
    int latin1 = 1;
    for (int32_t i = 0; i < n; i++) {
        if (w[i] > 0xFF) { latin1 = 0; break; }
    }
    ReflectionClass* ba = jnative_get_byte_array_class();
    int32_t bytes = latin1 ? n : n * 2;
    void* value = jnative_array_alloc_with_class(ba, bytes + 2, 1);
    void* s = value ? calloc(1, 32) : NULL;
    if (s == NULL) __jnative_throw_out_of_memory_error_ctx("ProcessEnvironment.environmentBlock");
    *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET) = bytes;
    uint8_t* d = (uint8_t*)value + JAVA_ARR_HDR;
    for (int32_t i = 0; i < n; i++) {
        if (latin1) {
            d[i] = (uint8_t)w[i];
        } else {
            d[i * 2] = (uint8_t)(w[i] >> 8);
            d[i * 2 + 1] = (uint8_t)(w[i] & 0xFF);
        }
    }
    d[bytes] = 0;
    d[bytes + 1] = 0;
    *(void**)s = (void*)&vtable_java_lang_String;
    *(void**)((char*)s + 8) = value;
    *(uint8_t*)((char*)s + 16) = latin1 ? 0 : 1;
    return s;
}

void* __jnative_fn_java_lang_ProcessEnvironment_environmentBlock___Ljava_lang_String_(void)
{
    WCHAR* block = GetEnvironmentStringsW();
    if (block == NULL) return pe_make_string(L"", 0);
    int32_t i = 0;
    while (block[i]) {
        while (block[i++]) {
        }
    }
    void* s = pe_make_string(block, i);
    FreeEnvironmentStringsW(block);
    return s;
}

#else

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "jnative_runtime.h"

#if defined(__APPLE__)
#include <crt_externs.h>
#define JNATIVE_ENVIRON (*_NSGetEnviron())
#else
extern char** environ;
#define JNATIVE_ENVIRON environ
#endif

void* __jnative_fn_java_lang_ProcessEnvironment_environ_____B(void)
{
    char** env = JNATIVE_ENVIRON;
    int32_t count = 0;
    if (env != NULL) {
        for (int32_t i = 0; env[i] != NULL; i++) {
            const char* eq = strchr(env[i], '=');
            if (eq != NULL && eq != env[i]) count++;
        }
    }

    void* result = jnative_ref_array_of_class(NULL, count * 2, "[[B");
    if (result == NULL) __jnative_throw_out_of_memory_error_ctx("ProcessEnvironment.environ");
    if (count == 0) return result;

    void** slots = (void**)jnative_array_data(result);
    int32_t j = 0;
    for (int32_t i = 0; env[i] != NULL && j < count; i++) {
        const char* eq = strchr(env[i], '=');
        if (eq == NULL || eq == env[i]) continue;
        void* name = jnative_byte_array(env[i], (int32_t)(eq - env[i]));
        void* value = jnative_byte_array(eq + 1, (int32_t)strlen(eq + 1));
        if (name == NULL || value == NULL) {
            __jnative_throw_out_of_memory_error_ctx("ProcessEnvironment.environ");
        }
        slots[2 * j] = name;
        slots[2 * j + 1] = value;
        j++;
    }
    return result;
}

#endif

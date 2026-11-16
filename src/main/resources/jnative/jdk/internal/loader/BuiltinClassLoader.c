#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <string.h>

#include "jnative_runtime.h"

extern void* fn_jdk_internal_loader_BuiltinClassLoader_findResource__Ljava_lang_String_Ljava_lang_String__Ljava_net_URL_(
        void* self, void* module_name, void* name) JNATIVE_WEAK;
extern void* fn_java_net_URL_openStream___Ljava_io_InputStream_(void* self) JNATIVE_WEAK;

static int is_java_base(void* module_name) {
    if (module_name == NULL) return 0;
    int32_t len = 0;
    const char* name = __jnative_read_string_bytes(module_name, &len);
    if (name == NULL || len != 9) return 0;
    return memcmp(name, "java.base", 9) == 0;
}

static const JNativeResourceEntry* embedded_resource(void* name_str) {
    int32_t len = 0;
    const char* name = __jnative_read_string_bytes(name_str, &len);
    if (name == NULL || len <= 0) return NULL;
    if (name[0] == '/') {
        name++;
        len--;
    }
    return jnative_find_resource(name, len);
}

void* __jnative_override_jdk_internal_loader_BuiltinClassLoader_findResourceAsStream(
        void* self, void* module_name, void* name_str)
{
    if (name_str == NULL) {
        __jnative_throw_null_pointer_exception_ctx(
            "BuiltinClassLoader.findResourceAsStream", "name");
    }

    if (is_java_base(module_name)) {
        const JNativeResourceEntry* res = embedded_resource(name_str);
        if (res != NULL) {
            void* bytes = jnative_byte_array(res->data, res->size);
            if (bytes == NULL) {
                __jnative_throw_out_of_memory_error_ctx(
                    "BuiltinClassLoader.findResourceAsStream");
            }
            return __jnative_make_byte_array_input_stream(bytes);
        }
    }

    if (fn_jdk_internal_loader_BuiltinClassLoader_findResource__Ljava_lang_String_Ljava_lang_String__Ljava_net_URL_ == NULL
        || fn_java_net_URL_openStream___Ljava_io_InputStream_ == NULL) {
        return NULL;
    }
    void* url = fn_jdk_internal_loader_BuiltinClassLoader_findResource__Ljava_lang_String_Ljava_lang_String__Ljava_net_URL_(
        self, module_name, name_str);
    return url != NULL ? fn_java_net_URL_openStream___Ljava_io_InputStream_(url) : NULL;
}

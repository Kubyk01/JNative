#define _GNU_SOURCE
#include <stdint.h>
#include <unistd.h>

extern void* __jnative_make_string_obj(const char* bytes, int32_t len);

int32_t __jnative_fn_java_io_Console_istty___Z(void) {
    return (isatty(STDIN_FILENO) && isatty(STDOUT_FILENO)) ? 1 : 0;
}

void* __jnative_fn_java_io_Console_encoding___Ljava_lang_String_(void) {
    static const char utf8_name[] = "UTF-8";
    return __jnative_make_string_obj(utf8_name, (int32_t)(sizeof(utf8_name) - 1));
}
#include <stdint.h>

int __jnative_fn_java_lang_StringUTF16_isBigEndian___Z(void) {
    union { uint16_t v; uint8_t b[2]; } u;
    u.v = 0x0100u;
    return (u.b[0] == 0x01u) ? 1 : 0;
}
#include <stdint.h>
#include <math.h>

int32_t __jnative_fn_java_lang_Float_floatToRawIntBits__F_I(float value) {
    union { float f; int32_t i; } u;
    u.f = value;
    return u.i;
}

int32_t __jnative_fn_java_lang_Float_floatToIntBits__F_I(float value) {
    if (isnan(value)) {
        return 0x7fc00000; // канонический NaN
    }
    union { float f; int32_t i; } u;
    u.f = value;
    return u.i;
}

float __jnative_fn_java_lang_Float_intBitsToFloat__I_F(int32_t bits) {
    union { int32_t i; float f; } u;
    u.i = bits;
    return u.f;
}

int32_t __jnative_fn_java_lang_Float_isNaN__F_Z(float value) {
    return isnan(value) ? 1 : 0;
}

int32_t __jnative_fn_java_lang_Float_isInfinite__F_Z(float value) {
    return isinf(value) ? 1 : 0;
}
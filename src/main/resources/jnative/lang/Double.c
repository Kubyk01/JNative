#include <stdint.h>
#include <string.h>

int64_t __jnative_fn_java_lang_Double_doubleToRawLongBits__D_J(double value) {
    union { double d; int64_t i; } u;
    u.d = value;
    return u.i;
}

double __jnative_fn_java_lang_Double_longBitsToDouble__J_D(int64_t bits) {
    union { double d; int64_t i; } u;
    u.i = bits;
    return u.d;
}
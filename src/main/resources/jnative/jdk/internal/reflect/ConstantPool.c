#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#include "jnative_runtime.h"

JNATIVE_NORETURN static void cp_wrong_type(int32_t index) {
    char msg[96];
    snprintf(msg, sizeof(msg), "Wrong type at constant pool index %d", (int)index);
    void* exc = __jnative_construct_exception("vtable_java_lang_IllegalArgumentException", msg);
    if (exc != NULL) __jnative_throw_exception(exc);
    __jnative_throw_array_index_out_of_bounds();
}

static const uint8_t* cp_entry(void* oop, int32_t index, uint8_t expected) {
    if (oop == NULL) __jnative_throw_null_pointer_exception();
    uint8_t tag = 0;
    const uint8_t* p = jnative_constant_pool_entry((ReflectionClass*)oop, index, &tag);
    if (p == NULL) __jnative_throw_array_index_out_of_bounds();
    if (tag != expected) cp_wrong_type(index);
    return p;
}

static uint32_t cp_u4(const uint8_t* p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}

static uint64_t cp_u8(const uint8_t* p) {
    return ((uint64_t)cp_u4(p) << 32) | cp_u4(p + 4);
}

static void* cp_utf8_string(void* oop, int32_t index) {
    const uint8_t* p = cp_entry(oop, index, 1);
    int32_t len = (p[0] << 8) | p[1];
    return __jnative_make_string_mutf8(p + 2, len);
}

int32_t __jnative_fn_jdk_internal_reflect_ConstantPool_getSize0__Ljava_lang_Object__I(void* self, void* oop) {
    (void)self;
    const JNativeAnnotationClass* e = jnative_annotation_class((ReflectionClass*)oop);
    return e != NULL ? e->constant_pool_count : 0;
}

int32_t __jnative_fn_jdk_internal_reflect_ConstantPool_getIntAt0__Ljava_lang_Object_I_I(
        void* self, void* oop, int32_t index) {
    (void)self;
    return (int32_t)cp_u4(cp_entry(oop, index, 3));
}

int64_t __jnative_fn_jdk_internal_reflect_ConstantPool_getLongAt0__Ljava_lang_Object_I_J(
        void* self, void* oop, int32_t index) {
    (void)self;
    return (int64_t)cp_u8(cp_entry(oop, index, 5));
}

float __jnative_fn_jdk_internal_reflect_ConstantPool_getFloatAt0__Ljava_lang_Object_I_F(
        void* self, void* oop, int32_t index) {
    (void)self;
    uint32_t bits = cp_u4(cp_entry(oop, index, 4));
    float f;
    memcpy(&f, &bits, sizeof(f));
    return f;
}

double __jnative_fn_jdk_internal_reflect_ConstantPool_getDoubleAt0__Ljava_lang_Object_I_D(
        void* self, void* oop, int32_t index) {
    (void)self;
    uint64_t bits = cp_u8(cp_entry(oop, index, 6));
    double d;
    memcpy(&d, &bits, sizeof(d));
    return d;
}

void* __jnative_fn_jdk_internal_reflect_ConstantPool_getUTF8At0__Ljava_lang_Object_I_Ljava_lang_String_(
        void* self, void* oop, int32_t index) {
    (void)self;
    return cp_utf8_string(oop, index);
}

void* __jnative_fn_jdk_internal_reflect_ConstantPool_getStringAt0__Ljava_lang_Object_I_Ljava_lang_String_(
        void* self, void* oop, int32_t index) {
    (void)self;
    const uint8_t* p = cp_entry(oop, index, 8);
    return cp_utf8_string(oop, (p[0] << 8) | p[1]);
}

void* __jnative_fn_jdk_internal_reflect_ConstantPool_getClassAt0__Ljava_lang_Object_I_Ljava_lang_Class_(
        void* self, void* oop, int32_t index) {
    (void)self;
    const uint8_t* p = cp_entry(oop, index, 7);
    const uint8_t* name = cp_entry(oop, (p[0] << 8) | p[1], 1);
    int32_t len = (name[0] << 8) | name[1];
    char buf[1024];
    if (len >= (int32_t)sizeof(buf)) cp_wrong_type(index);
    memcpy(buf, name + 2, (size_t)len);
    buf[len] = '\0';
    ReflectionClass* cls = jnative_class_by_name(buf);
    if (cls == NULL) {
        void* exc = __jnative_construct_exception("vtable_java_lang_NoClassDefFoundError", buf);
        if (exc != NULL) __jnative_throw_exception(exc);
        __jnative_throw_null_pointer_exception();
    }
    return cls;
}

void* __jnative_fn_jdk_internal_reflect_ConstantPool_getClassAtIfLoaded0__Ljava_lang_Object_I_Ljava_lang_Class_(
        void* self, void* oop, int32_t index) {
    (void)self;
    const uint8_t* p = cp_entry(oop, index, 7);
    const uint8_t* name = cp_entry(oop, (p[0] << 8) | p[1], 1);
    int32_t len = (name[0] << 8) | name[1];
    char buf[1024];
    if (len >= (int32_t)sizeof(buf)) return NULL;
    memcpy(buf, name + 2, (size_t)len);
    buf[len] = '\0';
    return jnative_class_by_name(buf);
}

int8_t __jnative_fn_jdk_internal_reflect_ConstantPool_getTagAt0__Ljava_lang_Object_I_B(
        void* self, void* oop, int32_t index) {
    (void)self;
    if (oop == NULL) __jnative_throw_null_pointer_exception();
    uint8_t tag = 0;
    if (jnative_constant_pool_entry((ReflectionClass*)oop, index, &tag) == NULL) return 0;
    return (int8_t)tag;
}

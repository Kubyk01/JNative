#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>

__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);
__attribute__((noreturn)) void __jnative_throw_array_index_out_of_bounds(void);

#define CP_CLASS_FILE_OFFSET 8
#define CP_OOP_OFFSET        16

struct ReflectionClass {
    void* name;
    struct ReflectionClass* superclass;
    struct ReflectionClass** interfaces;
    void** methods;
    void** fields;
    void** constructors;
    int modifiers;
    int object_size;
};

extern struct ReflectionClass* reflect_all_classes[] __attribute__((weak));

static int cp_size_of_class(struct ReflectionClass* cls) {
    if (cls == NULL) return 0;
    return cls->object_size;
}

static void cp_check_index(void* constantPoolOop, int32_t index) {
    if (constantPoolOop == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    if (index < 0) {
        __jnative_throw_array_index_out_of_bounds();
    }
}

int32_t __jnative_fn_jdk_internal_reflect_ConstantPool_getIntAt0__Ljava_lang_Object_I_I(
        void* constantPoolOop, int32_t index) {
    cp_check_index(constantPoolOop, index);
    struct ReflectionClass* cls =
        (struct ReflectionClass*)*(void**)((char*)constantPoolOop + CP_CLASS_FILE_OFFSET);
    if (cls == NULL || cp_size_of_class(cls) <= 0) {
        __jnative_throw_array_index_out_of_bounds();
    }
    return 0;
}

int64_t __jnative_fn_jdk_internal_reflect_ConstantPool_getLongAt0__Ljava_lang_Object_I_J(
        void* constantPoolOop, int32_t index) {
    cp_check_index(constantPoolOop, index);
    struct ReflectionClass* cls =
        (struct ReflectionClass*)*(void**)((char*)constantPoolOop + CP_CLASS_FILE_OFFSET);
    if (cls == NULL || cp_size_of_class(cls) <= 0) {
        __jnative_throw_array_index_out_of_bounds();
    }
    return 0;
}

float __jnative_fn_jdk_internal_reflect_ConstantPool_getFloatAt0__Ljava_lang_Object_I_F(
        void* constantPoolOop, int32_t index) {
    cp_check_index(constantPoolOop, index);
    struct ReflectionClass* cls =
        (struct ReflectionClass*)*(void**)((char*)constantPoolOop + CP_CLASS_FILE_OFFSET);
    if (cls == NULL || cp_size_of_class(cls) <= 0) {
        __jnative_throw_array_index_out_of_bounds();
    }
    return 0.0f;
}

double __jnative_fn_jdk_internal_reflect_ConstantPool_getDoubleAt0__Ljava_lang_Object_I_D(
        void* constantPoolOop, int32_t index) {
    cp_check_index(constantPoolOop, index);
    struct ReflectionClass* cls =
        (struct ReflectionClass*)*(void**)((char*)constantPoolOop + CP_CLASS_FILE_OFFSET);
    if (cls == NULL || cp_size_of_class(cls) <= 0) {
        __jnative_throw_array_index_out_of_bounds();
    }
    return 0.0;
}

static char __cp_empty_string[] = "";

void* __jnative_fn_jdk_internal_reflect_ConstantPool_getUTF8At0__Ljava_lang_Object_I_Ljava_lang_String_(
        void* constantPoolOop, int32_t index) {
    cp_check_index(constantPoolOop, index);
    struct ReflectionClass* cls =
        (struct ReflectionClass*)*(void**)((char*)constantPoolOop + CP_CLASS_FILE_OFFSET);
    if (cls == NULL || cp_size_of_class(cls) <= 0) {
        __jnative_throw_array_index_out_of_bounds();
    }
    return (void*)__cp_empty_string;
}
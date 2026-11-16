#include "jnative_platform.h"
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "jnative_runtime.h"

extern int64_t __jnative_fn_jdk_internal_misc_Unsafe_objectFieldOffset__Ljava_lang_reflect_Field_J(void* this_unsafe, void* field);
extern int64_t __jnative_fn_jdk_internal_misc_Unsafe_staticFieldOffset__Ljava_lang_reflect_Field_J(void* this_unsafe, void* field);
extern void* __jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(void* obj);
extern int __jnative_fn_java_lang_Class_isAssignableFrom__Ljava_lang_Class__Z(void* this_cls, void* other_cls);

extern void fn_jdk_internal_reflect_MethodHandleAccessorFactory_ensureClassInitialized__Ljava_lang_Class__V(void* cls) __attribute__((weak));
extern uint8_t fn_jdk_internal_reflect_MethodHandleFieldAccessorImpl_isReadOnly___Z(void* self) __attribute__((weak));
extern void fn_jdk_internal_reflect_MethodHandleFieldAccessorImpl_ensureObj__Ljava_lang_Object__V(void* self, void* obj) __attribute__((weak));
extern void* fn_jdk_internal_reflect_MethodHandleFieldAccessorImpl_newGetIllegalArgumentException__Ljava_lang_Object__Ljava_lang_IllegalArgumentException_(void* self, void* obj) __attribute__((weak));
extern void* fn_jdk_internal_reflect_MethodHandleFieldAccessorImpl_newSetIllegalArgumentException__Ljava_lang_Object__Ljava_lang_IllegalArgumentException_(void* self, void* obj) __attribute__((weak));
extern void fn_jdk_internal_reflect_FieldAccessorImpl_throwSetIllegalArgumentException__Ljava_lang_Object__V(void* self, void* value) __attribute__((weak));
extern void fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__Z_V(void* self, _Bool v) __attribute__((weak));
extern void fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__B_V(void* self, int8_t v) __attribute__((weak));
extern void fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__C_V(void* self, uint16_t v) __attribute__((weak));
extern void fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__S_V(void* self, int16_t v) __attribute__((weak));
extern void fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__I_V(void* self, int32_t v) __attribute__((weak));
extern void fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__J_V(void* self, int64_t v) __attribute__((weak));
extern void fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__F_V(void* self, float v) __attribute__((weak));
extern void fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__D_V(void* self, double v) __attribute__((weak));
extern void fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__Ljava_lang_Object__V(void* self, void* v) __attribute__((weak));

#define MHFA_INIT_FN(T) fn_jdk_internal_reflect_MethodHandle##T##FieldAccessorImpl__init___Ljava_lang_reflect_Field_Ljava_lang_invoke_MethodHandle_Ljava_lang_invoke_MethodHandle_ZZ_V
#define MHFA_DECLARE_INIT(T) extern void MHFA_INIT_FN(T)(void* self, void* field, void* getter, void* setter, _Bool read_only, _Bool is_static) __attribute__((weak));

MHFA_DECLARE_INIT(Boolean)
MHFA_DECLARE_INIT(Byte)
MHFA_DECLARE_INIT(Short)
MHFA_DECLARE_INIT(Character)
MHFA_DECLARE_INIT(Integer)
MHFA_DECLARE_INIT(Long)
MHFA_DECLARE_INIT(Float)
MHFA_DECLARE_INIT(Double)
MHFA_DECLARE_INIT(Object)

typedef void (*mhfa_init_fn)(void*, void*, void*, void*, _Bool, _Bool);

typedef struct {
    const char*  primitive;
    const char*  accessor;
    mhfa_init_fn init;
} MhfaKind;

static const MhfaKind mhfa_kinds[] = {
    { "boolean", "jdk/internal/reflect/MethodHandleBooleanFieldAccessorImpl",   MHFA_INIT_FN(Boolean)   },
    { "byte",    "jdk/internal/reflect/MethodHandleByteFieldAccessorImpl",      MHFA_INIT_FN(Byte)      },
    { "short",   "jdk/internal/reflect/MethodHandleShortFieldAccessorImpl",     MHFA_INIT_FN(Short)     },
    { "char",    "jdk/internal/reflect/MethodHandleCharacterFieldAccessorImpl", MHFA_INIT_FN(Character) },
    { "int",     "jdk/internal/reflect/MethodHandleIntegerFieldAccessorImpl",   MHFA_INIT_FN(Integer)   },
    { "long",    "jdk/internal/reflect/MethodHandleLongFieldAccessorImpl",      MHFA_INIT_FN(Long)      },
    { "float",   "jdk/internal/reflect/MethodHandleFloatFieldAccessorImpl",     MHFA_INIT_FN(Float)     },
    { "double",  "jdk/internal/reflect/MethodHandleDoubleFieldAccessorImpl",    MHFA_INIT_FN(Double)    },
    { NULL,      "jdk/internal/reflect/MethodHandleObjectFieldAccessorImpl",    MHFA_INIT_FN(Object)    },
};

JNATIVE_NORETURN static void mhfa_throw_internal_error(const char* what, void* field) {
    char msg[512];
    const char* owner = "<unknown>";
    const char* name = "<unknown>";
    int32_t name_len = 9;
    if (field != NULL) {
        ReflectionClass* declaring = *(ReflectionClass**)((char*)field + JNATIVE_FIELD_CLAZZ_OFFSET);
        if (declaring != NULL && declaring->cname != NULL) owner = declaring->cname;
        void* name_str = *(void**)((char*)field + JNATIVE_FIELD_NAME_OFFSET);
        const char* bytes = name_str != NULL ? __jnative_read_string_bytes(name_str, &name_len) : NULL;
        if (bytes != NULL) name = bytes;
        else name_len = 9;
    }
    snprintf(msg, sizeof(msg), "%s: %s.%.*s", what, owner, (int)name_len, name);
    __jnative_throw_exception(__jnative_construct_exception("vtable_java_lang_InternalError", msg));
}

static int32_t mhfa_field_slot = -1;

static void* mhfa_field(void* self) {
    int32_t off = __atomic_load_n(&mhfa_field_slot, __ATOMIC_ACQUIRE);
    if (off < 0) {
        for (ReflectionClass* c = jnative_class_by_name("jdk/internal/reflect/FieldAccessorImpl");
             c != NULL && off < 0; c = c->superclass) {
            if (c->fields == NULL) continue;
            for (ReflectionField** fp = c->fields; *fp != NULL; fp++) {
                if ((*fp)->name != NULL && strcmp((const char*)(*fp)->name, "field") == 0) {
                    off = (*fp)->offset;
                    break;
                }
            }
        }
        if (off < 0) mhfa_throw_internal_error("FieldAccessorImpl.field is not present in the image", NULL);
        __atomic_store_n(&mhfa_field_slot, off, __ATOMIC_RELEASE);
    }
    return *(void**)((char*)self + off);
}

static int mhfa_is_static(void* field) {
    return (*(int32_t*)((char*)field + JNATIVE_FIELD_MODIFIERS_OFFSET) & JNATIVE_ACC_STATIC) != 0;
}

static void* mhfa_address(void* self, void* obj, int for_set) {
    void* field = mhfa_field(self);
    if (mhfa_is_static(field)) {
        int64_t addr = __jnative_fn_jdk_internal_misc_Unsafe_staticFieldOffset__Ljava_lang_reflect_Field_J(NULL, field);
        if (addr == 0) mhfa_throw_internal_error("static field storage is not present in the image", field);
        return (void*)(intptr_t)addr;
    }
    if (obj == NULL) __jnative_throw_null_pointer_exception();
    void* declaring = *(void**)((char*)field + JNATIVE_FIELD_CLAZZ_OFFSET);
    if (!__jnative_fn_java_lang_Class_isAssignableFrom__Ljava_lang_Class__Z(
            declaring, __jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(obj))) {
        __jnative_throw_exception(for_set
            ? fn_jdk_internal_reflect_MethodHandleFieldAccessorImpl_newSetIllegalArgumentException__Ljava_lang_Object__Ljava_lang_IllegalArgumentException_(self, obj)
            : fn_jdk_internal_reflect_MethodHandleFieldAccessorImpl_newGetIllegalArgumentException__Ljava_lang_Object__Ljava_lang_IllegalArgumentException_(self, obj));
    }
    return (char*)obj + __jnative_fn_jdk_internal_misc_Unsafe_objectFieldOffset__Ljava_lang_reflect_Field_J(NULL, field);
}

static int mhfa_read_only(void* self, void* obj) {
    if ((fn_jdk_internal_reflect_MethodHandleFieldAccessorImpl_isReadOnly___Z(self) & 1) == 0) return 0;
    fn_jdk_internal_reflect_MethodHandleFieldAccessorImpl_ensureObj__Ljava_lang_Object__V(self, obj);
    return 1;
}

void* __jnative_override_jdk_internal_reflect_MethodHandleAccessorFactory_newFieldAccessor(void* field, int32_t is_read_only) {
    if (field == NULL) __jnative_throw_null_pointer_exception();
    fn_jdk_internal_reflect_MethodHandleAccessorFactory_ensureClassInitialized__Ljava_lang_Class__V(
        *(void**)((char*)field + JNATIVE_FIELD_CLAZZ_OFFSET));

    size_t count = sizeof(mhfa_kinds) / sizeof(mhfa_kinds[0]);
    const MhfaKind* kind = &mhfa_kinds[count - 1];
    ReflectionClass* type = *(ReflectionClass**)((char*)field + JNATIVE_FIELD_TYPE_OFFSET);
    if (type != NULL && type->primitive != NULL && type->cname != NULL) {
        for (size_t i = 0; i + 1 < count; i++) {
            if (strcmp(type->cname, mhfa_kinds[i].primitive) == 0) {
                kind = &mhfa_kinds[i];
                break;
            }
        }
    }

    ReflectionClass* cls = jnative_class_by_name(kind->accessor);
    if (cls == NULL || kind->init == NULL) mhfa_throw_internal_error(kind->accessor, field);
    void* accessor = jnative_alloc_object(cls);
    kind->init(accessor, field, NULL, NULL, (is_read_only & 1) != 0, mhfa_is_static(field) != 0);
    return accessor;
}

int32_t __jnative_override_jdk_internal_reflect_MethodHandleBooleanFieldAccessorImpl_getBoolean(void* self, void* obj) {
    uint8_t v;
    __atomic_load((uint8_t*)mhfa_address(self, obj, 0), &v, __ATOMIC_SEQ_CST);
    return v != 0;
}

void __jnative_override_jdk_internal_reflect_MethodHandleBooleanFieldAccessorImpl_setBoolean(void* self, void* obj, int32_t v) {
    uint8_t b = (uint8_t)(v & 1);
    if (mhfa_read_only(self, obj)) fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__Z_V(self, b != 0);
    __atomic_store((uint8_t*)mhfa_address(self, obj, 1), &b, __ATOMIC_SEQ_CST);
}

int8_t __jnative_override_jdk_internal_reflect_MethodHandleByteFieldAccessorImpl_getByte(void* self, void* obj) {
    int8_t v;
    __atomic_load((int8_t*)mhfa_address(self, obj, 0), &v, __ATOMIC_SEQ_CST);
    return v;
}

void __jnative_override_jdk_internal_reflect_MethodHandleByteFieldAccessorImpl_setByte(void* self, void* obj, int8_t v) {
    if (mhfa_read_only(self, obj)) fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__B_V(self, v);
    __atomic_store((int8_t*)mhfa_address(self, obj, 1), &v, __ATOMIC_SEQ_CST);
}

uint16_t __jnative_override_jdk_internal_reflect_MethodHandleCharacterFieldAccessorImpl_getChar(void* self, void* obj) {
    uint16_t v;
    __atomic_load((uint16_t*)mhfa_address(self, obj, 0), &v, __ATOMIC_SEQ_CST);
    return v;
}

void __jnative_override_jdk_internal_reflect_MethodHandleCharacterFieldAccessorImpl_setChar(void* self, void* obj, uint16_t v) {
    if (mhfa_read_only(self, obj)) fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__C_V(self, v);
    __atomic_store((uint16_t*)mhfa_address(self, obj, 1), &v, __ATOMIC_SEQ_CST);
}

int16_t __jnative_override_jdk_internal_reflect_MethodHandleShortFieldAccessorImpl_getShort(void* self, void* obj) {
    int16_t v;
    __atomic_load((int16_t*)mhfa_address(self, obj, 0), &v, __ATOMIC_SEQ_CST);
    return v;
}

void __jnative_override_jdk_internal_reflect_MethodHandleShortFieldAccessorImpl_setShort(void* self, void* obj, int16_t v) {
    if (mhfa_read_only(self, obj)) fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__S_V(self, v);
    __atomic_store((int16_t*)mhfa_address(self, obj, 1), &v, __ATOMIC_SEQ_CST);
}

int32_t __jnative_override_jdk_internal_reflect_MethodHandleIntegerFieldAccessorImpl_getInt(void* self, void* obj) {
    int32_t v;
    __atomic_load((int32_t*)mhfa_address(self, obj, 0), &v, __ATOMIC_SEQ_CST);
    return v;
}

void __jnative_override_jdk_internal_reflect_MethodHandleIntegerFieldAccessorImpl_setInt(void* self, void* obj, int32_t v) {
    if (mhfa_read_only(self, obj)) fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__I_V(self, v);
    __atomic_store((int32_t*)mhfa_address(self, obj, 1), &v, __ATOMIC_SEQ_CST);
}

int64_t __jnative_override_jdk_internal_reflect_MethodHandleLongFieldAccessorImpl_getLong(void* self, void* obj) {
    int64_t v;
    __atomic_load((int64_t*)mhfa_address(self, obj, 0), &v, __ATOMIC_SEQ_CST);
    return v;
}

void __jnative_override_jdk_internal_reflect_MethodHandleLongFieldAccessorImpl_setLong(void* self, void* obj, int64_t v) {
    if (mhfa_read_only(self, obj)) fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__J_V(self, v);
    __atomic_store((int64_t*)mhfa_address(self, obj, 1), &v, __ATOMIC_SEQ_CST);
}

float __jnative_override_jdk_internal_reflect_MethodHandleFloatFieldAccessorImpl_getFloat(void* self, void* obj) {
    float v;
    __atomic_load((float*)mhfa_address(self, obj, 0), &v, __ATOMIC_SEQ_CST);
    return v;
}

void __jnative_override_jdk_internal_reflect_MethodHandleFloatFieldAccessorImpl_setFloat(void* self, void* obj, float v) {
    if (mhfa_read_only(self, obj)) fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__F_V(self, v);
    __atomic_store((float*)mhfa_address(self, obj, 1), &v, __ATOMIC_SEQ_CST);
}

double __jnative_override_jdk_internal_reflect_MethodHandleDoubleFieldAccessorImpl_getDouble(void* self, void* obj) {
    double v;
    __atomic_load((double*)mhfa_address(self, obj, 0), &v, __ATOMIC_SEQ_CST);
    return v;
}

void __jnative_override_jdk_internal_reflect_MethodHandleDoubleFieldAccessorImpl_setDouble(void* self, void* obj, double v) {
    if (mhfa_read_only(self, obj)) fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__D_V(self, v);
    __atomic_store((double*)mhfa_address(self, obj, 1), &v, __ATOMIC_SEQ_CST);
}

void* __jnative_override_jdk_internal_reflect_MethodHandleObjectFieldAccessorImpl_get(void* self, void* obj) {
    void* v;
    __atomic_load((void**)mhfa_address(self, obj, 0), &v, __ATOMIC_SEQ_CST);
    return v;
}

void __jnative_override_jdk_internal_reflect_MethodHandleObjectFieldAccessorImpl_set(void* self, void* obj, void* value) {
    fn_jdk_internal_reflect_MethodHandleFieldAccessorImpl_ensureObj__Ljava_lang_Object__V(self, obj);
    if (fn_jdk_internal_reflect_MethodHandleFieldAccessorImpl_isReadOnly___Z(self) & 1) {
        fn_jdk_internal_reflect_FieldAccessorImpl_throwFinalFieldIllegalAccessException__Ljava_lang_Object__V(self, value);
    }
    void* field = mhfa_field(self);
    void* type = *(void**)((char*)field + JNATIVE_FIELD_TYPE_OFFSET);
    if (value != NULL && !__jnative_fn_java_lang_Class_isAssignableFrom__Ljava_lang_Class__Z(
            type, __jnative_fn_java_lang_Object_getClass___Ljava_lang_Class_(value))) {
        fn_jdk_internal_reflect_FieldAccessorImpl_throwSetIllegalArgumentException__Ljava_lang_Object__V(self, value);
    }
    __atomic_store((void**)mhfa_address(self, obj, 1), &value, __ATOMIC_SEQ_CST);
}

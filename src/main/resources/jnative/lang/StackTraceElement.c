#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#include "jnative_runtime.h"

extern int jnative_stack_frame_describe(void* sfi, ReflectionClass** out_cls,
                                        void** out_name, int32_t* out_modifiers) JNATIVE_WEAK;

extern const int32_t JNATIVE_STE_DECLARING_CLASS_OBJECT_OFFSET JNATIVE_WEAK;
extern const int32_t JNATIVE_STE_DECLARING_CLASS_OFFSET        JNATIVE_WEAK;
extern const int32_t JNATIVE_STE_METHOD_NAME_OFFSET            JNATIVE_WEAK;
extern const int32_t JNATIVE_STE_FILE_NAME_OFFSET              JNATIVE_WEAK;
extern const int32_t JNATIVE_STE_LINE_NUMBER_OFFSET            JNATIVE_WEAK;

#define STE_ACC_NATIVE 0x0100

static int ste_offset_ok(const int32_t* offset) {
    return (uintptr_t)offset != 0 && *offset >= 0;
}

static void* ste_binary_name(const char* cname) {
    size_t n = strlen(cname);
    char* buf = (char*)malloc(n + 1);
    if (buf == NULL) __jnative_throw_out_of_memory_error_ctx("StackTraceElement");
    for (size_t i = 0; i < n; i++) buf[i] = cname[i] == '/' ? '.' : cname[i];
    buf[n] = '\0';
    void* s = __jnative_make_string_obj(buf, (int32_t)n);
    free(buf);
    return s;
}

void __jnative_fn_java_lang_StackTraceElement_initStackTraceElement__Ljava_lang_StackTraceElement_Ljava_lang_StackFrameInfo__V(
        void* ste, void* frame)
{
    if (ste == NULL || frame == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    ReflectionClass* cls = NULL;
    void* method_name = NULL;
    int32_t modifiers = 0;
    if (jnative_stack_frame_describe == NULL
        || !jnative_stack_frame_describe(frame, &cls, &method_name, &modifiers)
        || cls == NULL || cls->cname == NULL) {
        __jnative_throw_exception(__jnative_construct_exception(
            "vtable_java_lang_InternalError",
            "StackTraceElement: frame was not filled by a stack walk"));
    }
    if (!ste_offset_ok(&JNATIVE_STE_DECLARING_CLASS_OBJECT_OFFSET)
        || !ste_offset_ok(&JNATIVE_STE_DECLARING_CLASS_OFFSET)
        || !ste_offset_ok(&JNATIVE_STE_METHOD_NAME_OFFSET)
        || !ste_offset_ok(&JNATIVE_STE_FILE_NAME_OFFSET)
        || !ste_offset_ok(&JNATIVE_STE_LINE_NUMBER_OFFSET)) {
        __jnative_throw_exception(__jnative_construct_exception(
            "vtable_java_lang_InternalError",
            "StackTraceElement: field layout is unknown to the image"));
    }

    char* base = (char*)ste;
    *(void**)(base + JNATIVE_STE_DECLARING_CLASS_OBJECT_OFFSET) = cls;
    *(void**)(base + JNATIVE_STE_DECLARING_CLASS_OFFSET) = ste_binary_name(cls->cname);
    *(void**)(base + JNATIVE_STE_METHOD_NAME_OFFSET) = method_name;
    *(void**)(base + JNATIVE_STE_FILE_NAME_OFFSET) = NULL;
    *(int32_t*)(base + JNATIVE_STE_LINE_NUMBER_OFFSET) =
        (modifiers & STE_ACC_NATIVE) ? -2 : -1;
}

void __jnative_fn_java_lang_StackTraceElement_initStackTraceElements___Ljava_lang_StackTraceElement_Ljava_lang_Object_I_V(
        void* stes,
        void* backtrace,
        int32_t depth)
{
    if (stes == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    (void)backtrace;
    (void)depth;
}

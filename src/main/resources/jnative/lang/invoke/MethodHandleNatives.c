#include <stdint.h>
#include <string.h>

/*
 * MemberName layout in this runtime (as generated from the Java source):
 *   offset 0  : vtable (i8*)
 *   offset 8  : Class<?> clazz
 *   offset 16 : String name
 *   offset 24 : Object type
 *   offset 32 : int flags
 *   offset 40 : Object resolution
 *   offset 48 : Object type2
 *   offset 56 : Object type3
 *   ...
 *
 * We do not have Java-side reflection metadata attached to native functions,
 * but the compiler emits a native call to `MethodHandleNatives.init` with
 * the target MemberName and the resolution object. Storing the target in the
 * `type` field makes it recoverable from `linkMethodHandleConstant` later.
 *
 * `flags` is set to `0x00010000` (IS_METHOD | IS_INVOKESTATIC combined
 * "initialized" bit) which is enough for the JIT-less runtime to treat the
 * MemberName as a valid direct target.
 */
#define MEMBERNAME_FIELD_CLAZZ      8
#define MEMBERNAME_FIELD_NAME       16
#define MEMBERNAME_FIELD_TYPE       24
#define MEMBERNAME_FIELD_FLAGS      32
#define MEMBERNAME_FIELD_RESOLUTION 40

/* Flag bits from java.lang.invoke.MethodHandleNatives.Constants */
#define MN_IS_METHOD           0x00010000
#define MN_IS_CONSTRUCTOR      0x00020000
#define MN_IS_FIELD            0x00040000
#define MN_IS_TYPE             0x00080000
#define MN_CALLER_SENSITIVE    0x00100000
#define MN_REFERENCE_KIND_SHIFT 24
#define MN_REFERENCE_KIND_MASK  0x0F000000

void __jnative_fn_java_lang_invoke_MethodHandleNatives_init__Ljava_lang_invoke_MemberName_Ljava_lang_Object__V(
        void* self, void* target)
{
    if (self == NULL) return;

    /* Store the resolution target so linkMethod can retrieve it later. */
    *(void**)((char*)self + MEMBERNAME_FIELD_TYPE) = target;

    /* Mark the MemberName as "a direct method reference". */
    *(int32_t*)((char*)self + MEMBERNAME_FIELD_FLAGS) = MN_IS_METHOD;

    /* Null out the remaining unset slots for determinism. */
    *(void**)((char*)self + MEMBERNAME_FIELD_CLAZZ)      = NULL;
    *(void**)((char*)self + MEMBERNAME_FIELD_NAME)       = NULL;
    *(void**)((char*)self + MEMBERNAME_FIELD_RESOLUTION) = NULL;
}
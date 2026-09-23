#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <dlfcn.h>

#include "jnative_runtime.h"

/*
 * --------------------------------------------------------------------------
 * MemberName layout
 * --------------------------------------------------------------------------
 *
 * Object header:
 *
 *   +0   vtable
 *
 * MemberName fields used by this runtime:
 *
 *   +8   clazz
 *   +16  name
 *   +24  type
 *   +32  flags
 *   +40  resolution
 *   +48  vmtarget
 *   +56  vmindex
 *
 * The actual JDK MemberName layout contains more VM-specific state, but
 * this runtime only needs the fields above for the compatibility paths
 * implemented here.
 */

#define MEMBERNAME_FIELD_CLAZZ       8
#define MEMBERNAME_FIELD_NAME       16
#define MEMBERNAME_FIELD_TYPE       24
#define MEMBERNAME_FIELD_FLAGS      32
#define MEMBERNAME_FIELD_RESOLUTION 40

/*
 * java.lang.invoke.MethodHandleNatives.Constants
 */
#define MN_IS_METHOD            0x00010000
#define MN_IS_CONSTRUCTOR       0x00020000
#define MN_IS_FIELD             0x00040000
#define MN_IS_TYPE              0x00080000
#define MN_CALLER_SENSITIVE     0x00100000

#define MN_REFERENCE_KIND_SHIFT 24
#define MN_REFERENCE_KIND_MASK  0x0F000000

/* ACC_STATIC, matching the JVM access-flag bit. */
#ifndef JNATIVE_ACC_STATIC
#define JNATIVE_ACC_STATIC 0x0008
#endif

/*
 * --------------------------------------------------------------------------
 * Helpers
 * --------------------------------------------------------------------------
 */

/*
 * Returns the internal name of the class a vtable belongs to, or NULL.
 * Used to distinguish Method / Constructor / Field reflect objects when
 * MethodHandleNatives.init receives an opaque Object.
 */
static const char* reflect_kind_of(void* obj) {
    if (obj == NULL) return NULL;
    void* vtable = *(void**)obj;
    if (vtable == NULL) return NULL;
    return ((JNativeVTable*)vtable)->name;
}

/*
 * Invoke Method.getType() to obtain a MethodType for the given reflect
 * Method. The call is attempted through two independent channels:
 *
 *   1. The reflection adaptor emitted by LlvmGlobalEmitter for
 *      java/lang/reflect/Method.getType. It exists only when the method
 *      was explicitly registered in the reflection table.
 *
 *   2. The direct mangled symbol fn_java_lang_reflect_Method_getType__
 *      Ljava_lang_invoke_MethodType_, which exists whenever the method
 *      body was translated into the module at all.
 *
 * Returns NULL when neither channel yields a MethodType. Callers then
 * leave MemberName.type null; MemberName.<init>(Method) in the JDK
 * treats that as "unresolved" and continues (it only throws when clazz
 * is also null).
 */
static void* method_get_type(void* method_obj) {
    ReflectionClass* mc = jnative_class_by_name("java/lang/reflect/Method");
    if (mc != NULL && mc->methods != NULL) {
        ReflectionMethod** mp = mc->methods;
        while (*mp != NULL) {
            ReflectionMethod* m = *mp;
            if (m->name && strcmp((const char*)m->name, "getType") == 0) {
                if (m->adaptor != NULL) {
                    typedef void* (*adaptor_t)(void*, void**);
                    adaptor_t fn = (adaptor_t)m->adaptor;
                    return fn(method_obj, NULL);
                }
            }
            mp++;
        }
    }

    typedef void* (*get_type_fn)(void*);
    get_type_fn fn = (get_type_fn)dlsym(RTLD_DEFAULT,
        "fn_java_lang_reflect_Method_getType__Ljava_lang_invoke_MethodType_");
    if (fn != NULL) {
        return fn(method_obj);
    }

    return NULL;
}

/*
 * ==========================================================================
 * init(MemberName, Object)
 * ==========================================================================
 *
 * Java:
 *
 *   static native void init(MemberName self, Object ref);
 *
 * Initializes a MemberName from a reflect Method, Constructor or Field.
 * In HotSpot this is a VM-level call that reads the internal VM
 * representation of the reflect object and populates the MemberName with
 * the declaring class, the member name, the type, the access flags, and
 * the vmtarget/vmindex pair that marks the MemberName as resolved.
 *
 * This runtime has no VM-internal representation of reflect mirrors, so
 * the fields are read directly from the reflect object that Class.c
 * produced (see create_method_mirror / create_constructor_mirror /
 * create_field_mirror in jnative/lang/Class.c) using the layout published
 * by LlvmGenerator.generateMain through __jnative_reflect_set_layout.
 *
 * For a Method, the MemberName's `type` field must be a MethodType, not a
 * Class[]. The MethodType is obtained by calling Method.getType(); when
 * neither the reflection adaptor nor the mangled symbol is available, the
 * field is left null. The caller — MemberName.<init>(Method) in the JDK —
 * only throws when `clazz` is null, so as long as clazz is set the
 * constructor completes. A MemberName built this way reports itself as
 * unresolved, which matches the JDK's own "VM cannot use MethodHandles
 * in this case" branch.
 */
void __jnative_fn_java_lang_invoke_MethodHandleNatives_init__Ljava_lang_invoke_MemberName_Ljava_lang_Object__V(
        void* self,
        void* target)
{
    if (self == NULL || target == NULL) {
        return;
    }

    const char* kind = reflect_kind_of(target);
    if (kind == NULL) {
        return;
    }

    if (strcmp(kind, "java/lang/reflect/Method") == 0) {
        void* clazz     = *(void**)((char*)target + JNATIVE_METHOD_CLAZZ_OFFSET);
        void* name      = *(void**)((char*)target + JNATIVE_METHOD_NAME_OFFSET);
        int32_t modifiers = *(int32_t*)((char*)target + JNATIVE_METHOD_MODIFIERS_OFFSET);
        void* method_type = method_get_type(target);

        *(void**)((char*)self + MEMBERNAME_FIELD_CLAZZ)  = clazz;
        *(void**)((char*)self + MEMBERNAME_FIELD_NAME)   = name;
        *(void**)((char*)self + MEMBERNAME_FIELD_TYPE)   = method_type;
        *(int32_t*)((char*)self + MEMBERNAME_FIELD_FLAGS) = modifiers | MN_IS_METHOD;
        return;
    }

    if (strcmp(kind, "java/lang/reflect/Constructor") == 0) {
        void* clazz     = *(void**)((char*)target + JNATIVE_CTOR_CLAZZ_OFFSET);
        int32_t modifiers = *(int32_t*)((char*)target + JNATIVE_CTOR_MODIFIERS_OFFSET);

        *(void**)((char*)self + MEMBERNAME_FIELD_CLAZZ)  = clazz;
        *(void**)((char*)self + MEMBERNAME_FIELD_NAME)   = NULL;
        *(void**)((char*)self + MEMBERNAME_FIELD_TYPE)   = NULL;
        *(int32_t*)((char*)self + MEMBERNAME_FIELD_FLAGS) = modifiers | MN_IS_CONSTRUCTOR;
        return;
    }

    if (strcmp(kind, "java/lang/reflect/Field") == 0) {
        void* clazz     = *(void**)((char*)target + JNATIVE_FIELD_CLAZZ_OFFSET);
        void* name      = *(void**)((char*)target + JNATIVE_FIELD_NAME_OFFSET);
        int32_t modifiers = *(int32_t*)((char*)target + JNATIVE_FIELD_MODIFIERS_OFFSET);

        *(void**)((char*)self + MEMBERNAME_FIELD_CLAZZ)  = clazz;
        *(void**)((char*)self + MEMBERNAME_FIELD_NAME)   = name;
        *(void**)((char*)self + MEMBERNAME_FIELD_TYPE)   = NULL;
        *(int32_t*)((char*)self + MEMBERNAME_FIELD_FLAGS) = modifiers | MN_IS_FIELD;
        return;
    }
}

/*
 * ==========================================================================
 * init(MemberName, Class)
 * ==========================================================================
 *
 * Some JDK versions / compiler resolution paths resolve init against the
 * Class-typed overload.
 */
void __jnative_fn_java_lang_invoke_MethodHandleNatives_init__Ljava_lang_invoke_MemberName_Ljava_lang_Class__V(
        void* self,
        void* target)
{
    if (self == NULL) {
        return;
    }

    *(void**)((char*)self + MEMBERNAME_FIELD_TYPE)   = target;
    *(int32_t*)((char*)self + MEMBERNAME_FIELD_FLAGS) = MN_IS_METHOD;
    *(void**)((char*)self + MEMBERNAME_FIELD_CLAZZ)   = NULL;
    *(void**)((char*)self + MEMBERNAME_FIELD_NAME)    = NULL;
    *(void**)((char*)self + MEMBERNAME_FIELD_RESOLUTION) = NULL;
}

/*
 * ==========================================================================
 * resolve(byte, MemberName, Class, int, boolean)
 * ==========================================================================
 *
 * JVM/JDK declaration:
 *
 *   static native MemberName resolve(
 *       byte refKind,
 *       MemberName member,
 *       Class<?> lookupClass,
 *       int allowedModes,
 *       boolean speculativeResolve);
 *
 * IMPORTANT:
 *
 * The exact LLVM symbol expected by the linker for this overload is:
 *
 *   __jnative_fn_java_lang_invoke_MethodHandleNatives_resolve__BLjava_lang_invoke_MemberName_Ljava_lang_Class_IZ_Ljava_lang_invoke_MemberName_
 *
 * This runtime has no HotSpot symbol-table resolution phase. If the
 * MemberName already contains a target, return it unchanged.
 */
void* __jnative_fn_java_lang_invoke_MethodHandleNatives_resolve__BLjava_lang_invoke_MemberName_Ljava_lang_Class_IZ_Ljava_lang_invoke_MemberName_(
        int8_t ref_kind,
        void* member,
        void* lookup_class,
        int32_t allowed_modes,
        int32_t speculative_resolve)
{
    (void)ref_kind;
    (void)lookup_class;
    (void)allowed_modes;
    (void)speculative_resolve;

    if (member == NULL) {
        return NULL;
    }

    void* target = *(void**)((char*)member + MEMBERNAME_FIELD_TYPE);
    if (target != NULL) {
        return member;
    }
    return member;
}

/*
 * ==========================================================================
 * resolve(MemberName, Class, int, boolean)
 * ==========================================================================
 */
void* __jnative_fn_java_lang_invoke_MethodHandleNatives_resolve__Ljava_lang_invoke_MemberName_Ljava_lang_Class_IZ_Ljava_lang_invoke_MemberName_(
        void* member,
        void* lookup_class,
        int32_t allowed_modes,
        int32_t speculative_resolve)
{
    return __jnative_fn_java_lang_invoke_MethodHandleNatives_resolve__BLjava_lang_invoke_MemberName_Ljava_lang_Class_IZ_Ljava_lang_invoke_MemberName_(
        (int8_t)0, member, lookup_class, allowed_modes, speculative_resolve);
}

/*
 * ==========================================================================
 * resolve(MemberName, Class)
 * ==========================================================================
 */
void* __jnative_fn_java_lang_invoke_MethodHandleNatives_resolve__Ljava_lang_invoke_MemberName_Ljava_lang_Class__Ljava_lang_Object_(
        void* self,
        void* caller)
{
    (void)caller;
    if (self == NULL) {
        return NULL;
    }
    return *(void**)((char*)self + MEMBERNAME_FIELD_TYPE);
}

/*
 * ==========================================================================
 * objectFieldOffset(MemberName)
 * ==========================================================================
 */
int64_t __jnative_fn_java_lang_invoke_MethodHandleNatives_objectFieldOffset__Ljava_lang_invoke_MemberName__J(
        void* member)
{
    (void)member;
    return 0;
}

/*
 * ==========================================================================
 * staticFieldBase(MemberName)
 * ==========================================================================
 */
void* __jnative_fn_java_lang_invoke_MethodHandleNatives_staticFieldBase__Ljava_lang_invoke_MemberName__Ljava_lang_Object_(
        void* member)
{
    (void)member;
    return NULL;
}

/*
 * ==========================================================================
 * staticFieldOffset(MemberName)
 * ==========================================================================
 */
int64_t __jnative_fn_java_lang_invoke_MethodHandleNatives_staticFieldOffset__Ljava_lang_invoke_MemberName__J(
        void* member)
{
    (void)member;
    return 0;
}

/*
 * ==========================================================================
 * expand(MemberName)
 * ==========================================================================
 */
void __jnative_fn_java_lang_invoke_MethodHandleNatives_expand__Ljava_lang_invoke_MemberName__V(
        void* self)
{
    (void)self;
}

/*
 * ==========================================================================
 * getMembers(...)
 * ==========================================================================
 */
int32_t __jnative_fn_java_lang_invoke_MethodHandleNatives_getMembers__Ljava_lang_Class_Ljava_lang_String_Ljava_lang_String_ILjava_lang_Class_I_Ljava_lang_invoke_MemberName__I(
        void* defc,
        void* match_name,
        void* match_sig,
        int32_t match_flags,
        void* caller,
        int32_t skip,
        void* results)
{
    (void)defc;
    (void)match_name;
    (void)match_sig;
    (void)match_flags;
    (void)caller;
    (void)skip;
    (void)results;

    return 0;
}

/*
 * ==========================================================================
 * getMemberVMInfo(MemberName)
 * ==========================================================================
 */
void* __jnative_fn_java_lang_invoke_MethodHandleNatives_getMemberVMInfo__Ljava_lang_invoke_MemberName__Ljava_lang_Object_(
        void* self)
{
    (void)self;
    return NULL;
}

/*
 * ==========================================================================
 * setCallSiteTargetNormal(CallSite, MethodHandle)
 * ==========================================================================
 */
void __jnative_fn_java_lang_invoke_MethodHandleNatives_setCallSiteTargetNormal__Ljava_lang_invoke_CallSite_Ljava_lang_invoke_MethodHandle__V(
        void* site,
        void* target)
{
    if (site == NULL) {
        return;
    }

    *(void**)((char*)site + OBJECT_HEADER_SIZE) = target;
}

/*
 * ==========================================================================
 * setCallSiteTargetVolatile(CallSite, MethodHandle)
 * ==========================================================================
 */
void __jnative_fn_java_lang_invoke_MethodHandleNatives_setCallSiteTargetVolatile__Ljava_lang_invoke_CallSite_Ljava_lang_invoke_MethodHandle__V(
        void* site,
        void* target)
{
    if (site == NULL) {
        return;
    }

    __atomic_store_n(
        (void**)((char*)site + OBJECT_HEADER_SIZE),
        target,
        __ATOMIC_SEQ_CST
    );
}

/*
 * ==========================================================================
 * getNamedCon(int, Object[])
 * ==========================================================================
 */
int32_t __jnative_fn_java_lang_invoke_MethodHandleNatives_getNamedCon__I_Ljava_lang_Object__I(
        int32_t which,
        void* name_array)
{
    (void)which;
    (void)name_array;

    return 0;
}

/*
 * ==========================================================================
 * registerNatives()
 * ==========================================================================
 */
void __jnative_fn_java_lang_invoke_MethodHandleNatives_registerNatives___V(void)
{
}

/*
 * ==========================================================================
 * clearCallerSensitive(MemberName)
 * ==========================================================================
 */
void __jnative_fn_java_lang_invoke_MethodHandleNatives_clearCallerSensitive__Ljava_lang_invoke_MemberName__V(
        void* member)
{
    if (member == NULL) {
        return;
    }

    int32_t flags = *(int32_t*)((char*)member + MEMBERNAME_FIELD_FLAGS);
    flags &= ~MN_CALLER_SENSITIVE;
    *(int32_t*)((char*)member + MEMBERNAME_FIELD_FLAGS) = flags;
}
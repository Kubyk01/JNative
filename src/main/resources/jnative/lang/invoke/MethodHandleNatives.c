#include <stdint.h>
#include <stddef.h>
#include <string.h>

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


/*
 * ==========================================================================
 * init(MemberName, Object)
 * ==========================================================================
 *
 * Java:
 *
 *   static native void init(MemberName self, Object ref);
 *
 * The runtime stores the supplied target in the MemberName's type slot.
 * This is the same representation used by the existing MethodHandle
 * implementation.
 */
void __jnative_fn_java_lang_invoke_MethodHandleNatives_init__Ljava_lang_invoke_MemberName_Ljava_lang_Object__V(
        void* self,
        void* target)
{
    if (self == NULL) {
        return;
    }

    *(void**)((char*)self + MEMBERNAME_FIELD_TYPE) = target;

    /*
     * Mark the MemberName as an initialized method reference.
     */
    *(int32_t*)((char*)self + MEMBERNAME_FIELD_FLAGS) = MN_IS_METHOD;

    /*
     * Clear fields which this runtime does not populate.
     */
    *(void**)((char*)self + MEMBERNAME_FIELD_CLAZZ) = NULL;
    *(void**)((char*)self + MEMBERNAME_FIELD_NAME) = NULL;
    *(void**)((char*)self + MEMBERNAME_FIELD_RESOLUTION) = NULL;
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

    *(void**)((char*)self + MEMBERNAME_FIELD_TYPE) = target;
    *(int32_t*)((char*)self + MEMBERNAME_FIELD_FLAGS) = MN_IS_METHOD;

    *(void**)((char*)self + MEMBERNAME_FIELD_CLAZZ) = NULL;
    *(void**)((char*)self + MEMBERNAME_FIELD_NAME) = NULL;
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

    /*
     * If init() stored a target, the MemberName is already resolved from
     * the point of view of this runtime.
     */
    void* target =
        *(void**)((char*)member + MEMBERNAME_FIELD_TYPE);

    if (target != NULL) {
        return member;
    }

    /*
     * There is no VM symbol table available here from which to perform a
     * real resolution.
     *
     * Returning the original MemberName is preferable to fabricating a
     * target or dereferencing invalid metadata.
     */
    return member;
}


/*
 * ==========================================================================
 * resolve(MemberName, Class, int, boolean)
 * ==========================================================================
 *
 * Compatibility overload reached through java.lang.invoke.MemberName$Factory
 * in some JDK versions, where the byte refKind argument is folded into the
 * MemberName itself before the JVM entry point is invoked.
 *
 * The mangled symbol the LLVM emitter produces for this call site is:
 *
 *   __jnative_fn_java_lang_invoke_MethodHandleNatives_resolve__Ljava_lang_invoke_MemberName_Ljava_lang_Class_IZ_Ljava_lang_invoke_MemberName_
 *
 * which is exactly the symbol the linker was previously unable to find.
 * The body simply forwards to the canonical (byte, MemberName, Class, int,
 * boolean) implementation with a neutral refKind of 0.
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
 *
 * Legacy overload used by some JDK versions / generated call paths.
 *
 * The LLVM emitter produces the symbol:
 *
 *   __jnative_fn_java_lang_invoke_MethodHandleNatives_resolve__Ljava_lang_invoke_MemberName_Ljava_lang_Class__Ljava_lang_Object_
 *
 * (note the Object return type) for call sites compiled against JDK 8.
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
 *
 * Java:
 *
 *   static native long objectFieldOffset(MemberName field);
 *
 * A full HotSpot implementation would resolve the MemberName to a field
 * descriptor and return the VM-computed instance-field offset.
 *
 * This runtime does not keep that VM-side field metadata inside
 * MemberName. Instance field offsets are calculated by the LLVM/code-
 * generation side from the Java class layout.
 *
 * Therefore this native entry point returns zero as a safe typed default.
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
 *
 * Static fields are emitted as LLVM globals by this runtime, so there is
 * no Java object that has to be used as the static-field base.
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
 *
 * Static accesses in generated code are resolved directly to LLVM globals.
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
#define OBJECT_HEADER_SIZE 8

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

    int32_t flags =
        *(int32_t*)((char*)member + MEMBERNAME_FIELD_FLAGS);

    flags &= ~MN_CALLER_SENSITIVE;

    *(int32_t*)((char*)member + MEMBERNAME_FIELD_FLAGS) = flags;
}
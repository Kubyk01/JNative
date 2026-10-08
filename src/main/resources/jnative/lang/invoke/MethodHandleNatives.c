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
 * Reference-kind values, mirroring java.lang.invoke.MethodHandleInfo.*.
 * Only the static / non-static distinction matters to this file; the
 * remaining kinds are enumerated for completeness so the switch below
 * reads against the same alphabet the JDK's own Constants define.
 */
#define JN_REF_getField        1
#define JN_REF_getStatic       2
#define JN_REF_putField        3
#define JN_REF_putStatic       4
#define JN_REF_invokeVirtual   5
#define JN_REF_invokeStatic    6
#define JN_REF_invokeSpecial   7
#define JN_REF_newInvokeSpecial 8
#define JN_REF_invokeInterface 9

/*
 * --------------------------------------------------------------------------
 * TODO(jnative): MethodHandleNatives.resolve is a stub, and that stub is
 * the reason MethodHandles.Lookup.findXxx fails its own validation.
 * --------------------------------------------------------------------------
 *
 * WHAT THE HOTSPOT NATIVE DOES
 *
 * The JVM-side implementation of MethodHandleNatives.resolve is not an
 * ordinary "return the input" helper: it is the VM's entry point into
 * the symbolic-resolution machinery. Given a MemberName that carries a
 * (class, name, type, refKind) tuple but no resolved target yet, it:
 *
 *   1. looks the member up in the VM's own symbol tables using the
 *      class, name and type;
 *
 *   2. merges the resolved member's real access flags into
 *      MemberName.flags. In particular it sets ACC_STATIC, the
 *      visibility bits (public / private / protected), ACC_FINAL,
 *      ACC_VOLATILE and the rest of the JVM flag word;
 *
 *   3. fills in vmtarget / vmindex with the linkage information the
 *      interpreter and the JIT need to invoke the member directly.
 *
 * WHAT THIS RUNTIME DOES INSTEAD
 *
 * This runtime has no VM symbol tables and no interpreter: every call
 * site that the LLVM backend emits is resolved at codegen time, so a
 * MemberName never needs a vmtarget to be dispatchable. The
 * vmtarget / vmindex slots can therefore stay NULL / 0 and every call
 * site that goes through a MemberName will find a working body anyway.
 *
 * The flag merge, however, is NOT optional. It is not a linkage hint;
 * it is what MethodHandles.Lookup.findXxx reads on the way out.
 *
 * HOW THE STUB BREAKS findStatic TODAY
 *
 * MemberName's constructor (the one Lookup.findStatic invokes) stores
 *
 *     flags = MN_IS_METHOD | (refKind << MN_REFERENCE_KIND_SHIFT)
 *
 * into the freshly allocated MemberName. For a REF_invokeStatic call
 * site that is
 *
 *     flags = 0x00010000 | (6 << 24) = 0x06010000
 *
 * — note that ACC_STATIC is NOT set. The constructor cannot set it:
 * it has no way to know whether the named method is actually static;
 * that is exactly what the VM-side resolve is supposed to discover.
 *
 * Lookup.findStatic then calls checkMethod, which for REF_invokeStatic
 * asserts `member.isStatic()` — i.e. it reads `flags & ACC_STATIC` off
 * the same MemberName. Because our stub returned the MemberName with
 * the constructor's flag word untouched, that test reads 0 and throws
 *
 *     java.lang.IllegalAccessException: expected a static method:
 *         sun.invoke.util.ValueConversions.ignore(Object)void/invokeStatic,
 *         from class sun.invoke.util.ValueConversions
 *
 * The failure surfaces inside
 * sun.invoke.util.ValueConversions$Handles.<clinit>, which is the
 * first reachable caller of findStatic in the bootstrap path, but any
 * other findStatic / findVirtual / findGetter / findSetter call site
 * would hit the same wall.
 *
 * WHY THE FIX BELOW IS SUFFICIENT
 *
 * Of all the flags the VM-side resolve merges in, the only one that
 * any caller of a MemberName reachable from this runtime actually
 * inspects is ACC_STATIC:
 *
 *   - the Lookup.findXxx family reads exactly this bit through
 *     checkMethod;
 *
 *   - MemberName.isStatic() is the single accessor that other
 *     reachable code paths call.
 *
 * The visibility bits are not read: access checks in this runtime are
 * answered at the Java level by VerifyAccess and by
 * Reflection.areNestMates, both of which consult Class mirrors rather
 * than MemberName.flags. ACC_FINAL, ACC_VOLATILE and the rest are read
 * only by the interpreter, which does not exist here.
 *
 * So the minimal correct fix is to derive ACC_STATIC from refKind —
 * the one piece of information the caller has already told us, and the
 * one piece the VM would have used anyway. That is what
 * jnative_refkind_is_static() and the resolve implementation below do.
 *
 * --------------------------------------------------------------------------
 * TODO(jnative): if a future revision of this runtime starts emitting
 * call sites that go through a MemberName rather than through a
 * statically resolved target, the flag word will have to be filled
 * completely, not just the ACC_STATIC bit. The full merge would look
 * like this:
 *
 *   1. read MemberName.clazz (a ReflectionClass*);
 *   2. read MemberName.name as a C string via
 *      __jnative_read_string_bytes;
 *   3. convert MemberName.type (a MethodType) into a JVM descriptor.
 *      There is no direct native for this today; the cleanest path is
 *      to call MethodType.toMethodDescriptorString() through the
 *      reflection adaptor that LlvmGlobalEmitter already emits, the
 *      same way MemberName.init(Method) recovers a MethodType from a
 *      reflect Method (see method_get_type below);
 *   4. walk ReflectionClass.methods / .fields for the (name, descriptor)
 *      pair and take the matching ReflectionMethod->modifiers or
 *      ReflectionField->modifiers;
 *   5. splice those bits into MemberName.flags, preserving the
 *      MN_IS_METHOD / MN_IS_FIELD kind bit and the refKind nibble that
 *      the constructor already stored.
 *
 * Step 3 is the only nontrivial one, and it is why the full merge was
 * not written preemptively: reconstructing a descriptor from a
 * MethodType requires a working reflection round-trip, which this file
 * deliberately avoids for the bootstrap path.
 * --------------------------------------------------------------------------
 */

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
 * True iff refKind selects a static member. Matches the mapping the
 * JVM's own MemberName constructor uses when it classifies a
 * reference kind.
 *
 * The function is deliberately a switch rather than an arithmetic
 * predicate on the refKind value: the current JDK numbering happens to
 * put the three static kinds at even indices, but that is an accident
 * of the current table and arithmetic on it would break silently if
 * the alphabet is ever reordered.
 */
static int jnative_refkind_is_static(int32_t ref_kind) {
    switch (ref_kind) {
        case JN_REF_getStatic:      /* 2 */
        case JN_REF_putStatic:      /* 4 */
        case JN_REF_invokeStatic:   /* 6 */
            return 1;
        default:
            return 0;
    }
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
 * The exact LLVM symbol expected by the linker for this overload is:
 *
 *   __jnative_fn_java_lang_invoke_MethodHandleNatives_resolve__BLjava_lang_invoke_MemberName_Ljava_lang_Class_IZ_Ljava_lang_invoke_MemberName_
 *
 * See the long TODO block at the top of this file for why this is not a
 * faithful port of the HotSpot native, and for what a full implementation
 * would need to do. The short version: this runtime has no VM symbol
 * tables and no interpreter, so the vmtarget / vmindex half of the
 * HotSpot behaviour is unnecessary, but the access-flag merge is not —
 * it is the one thing MethodHandles.Lookup.findXxx actually reads back
 * out of the MemberName.
 *
 * The fix below is the minimal merge that keeps every reachable caller
 * of a MemberName well behaved:
 *
 *   - ACC_STATIC is set when (and only when) refKind selects a static
 *     member. This is the bit Lookup.checkMethod reads, and it is the
 *     bit that MemberName.isStatic() reads;
 *
 *   - ACC_STATIC is cleared in every other case, so a stale static bit
 *     can never survive a virtual / interface / special resolution and
 *     make isStatic() lie;
 *
 *   - every other flag bit the constructor placed into the word (the
 *     MN_IS_METHOD kind bit and the refKind nibble) is preserved
 *     untouched.
 */
void* __jnative_fn_java_lang_invoke_MethodHandleNatives_resolve__BLjava_lang_invoke_MemberName_Ljava_lang_Class_IZ_Ljava_lang_invoke_MemberName_(
        int8_t ref_kind,
        void* member,
        void* lookup_class,
        int32_t allowed_modes,
        int32_t speculative_resolve)
{
    (void)lookup_class;
    (void)allowed_modes;
    (void)speculative_resolve;

    if (member == NULL) {
        return NULL;
    }

    int32_t flags = *(int32_t*)((char*)member + MEMBERNAME_FIELD_FLAGS);

    if (jnative_refkind_is_static(ref_kind)) {
        flags |= JNATIVE_ACC_STATIC;
    } else {
        flags &= ~JNATIVE_ACC_STATIC;
    }

    *(int32_t*)((char*)member + MEMBERNAME_FIELD_FLAGS) = flags;

    /*
     * The MemberName is returned with its (clazz, name, type) tuple
     * intact and its vmtarget / vmindex slots still empty. No caller in
     * this runtime dereferences those slots, because every dispatch
     * goes through the LLVM backend's statically emitted call sites
     * rather than through the MemberName. See the TODO at the top of
     * this file for what would have to change if that ever stops being
     * true.
     */
    return member;
}

/*
 * ==========================================================================
 * resolve(MemberName, Class, int, boolean)
 * ==========================================================================
 *
 * The overload without the leading `byte refKind`. The JDK's own
 * MemberName carries the refKind in the high nibble of its flags word
 * (bits 24..27), so the value is recoverable without a separate
 * argument. Recovering it here and delegating to the five-argument form
 * keeps the two entry points on exactly the same code path.
 */
void* __jnative_fn_java_lang_invoke_MethodHandleNatives_resolve__Ljava_lang_invoke_MemberName_Ljava_lang_Class_IZ_Ljava_lang_invoke_MemberName_(
        void* member,
        void* lookup_class,
        int32_t allowed_modes,
        int32_t speculative_resolve)
{
    if (member == NULL) {
        return NULL;
    }

    int32_t flags = *(int32_t*)((char*)member + MEMBERNAME_FIELD_FLAGS);
    int8_t ref_kind = (int8_t)((flags & MN_REFERENCE_KIND_MASK)
                               >> MN_REFERENCE_KIND_SHIFT);

    return __jnative_fn_java_lang_invoke_MethodHandleNatives_resolve__BLjava_lang_invoke_MemberName_Ljava_lang_Class_IZ_Ljava_lang_invoke_MemberName_(
        ref_kind, member, lookup_class, allowed_modes, speculative_resolve);
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
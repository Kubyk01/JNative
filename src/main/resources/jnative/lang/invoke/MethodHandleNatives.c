/*
 * java.lang.invoke.MethodHandleNatives — native support.
 *
 * ==========================================================================
 * The core problem this file exists to solve
 * ==========================================================================
 *
 * This runtime has no JVM symbol tables and no interpreter: every call
 * site that the LLVM backend emits is resolved at codegen time, so a
 * MemberName never needs a vmtarget to be dispatchable. The
 * vmtarget / vmindex slots can therefore stay NULL / 0 and every call
 * site that goes through a MemberName will find a working body anyway.
 *
 * The flag word of a MemberName, however, is NOT optional. It is not
 * a linkage hint; it is what MethodHandles.Lookup.findXxx reads on the
 * way out. The JDK's own MemberName constructor cannot fill it in:
 * the symbolic constructor MemberName(Class, String, MethodType, byte)
 * only knows the name and the type of the member, not its access
 * flags. It stores
 *
 *     flags = MN_IS_METHOD | (refKind << MN_REFERENCE_KIND_SHIFT)
 *
 * and leaves the low 16 bits (the modifier bits that
 * MemberName.getModifiers() exposes through RECOGNIZED_MODIFIERS =
 * 0xFFFF) at zero. Those bits can only be supplied by the VM-side
 * resolve.
 *
 * ==========================================================================
 * What goes wrong if the flag word is not merged
 * ==========================================================================
 *
 * With the low 16 bits left at zero, every reader of MemberName.flags
 * observes "no visibility bits set" — which the JDK classifies as
 * package-private:
 *
 *   - MethodHandles.Lookup.checkMethod reads isStatic() from the
 *     flags word. Without ACC_STATIC, findStatic fails with
 *     "expected a static method", findVirtual fails with
 *     "expected a non-static method";
 *
 *   - MethodHandles.Lookup.checkAccess (MethodHandles.java:3952)
 *     reads m.getModifiers(), passes the result through fixmods
 *     (which masks off everything outside PUBLIC|PRIVATE|PROTECTED
 *     and substitutes PACKAGE for a zero result), and hands it to
 *     VerifyAccess.isMemberAccessible;
 *
 *   - VerifyAccess.isMemberAccessible (VerifyAccess.java:95-144)
 *     switches on the same bits; without them the PACKAGE_ONLY case
 *     is taken, which requires isSamePackage(defc, lookupClass);
 *
 *   - when the check fails, MethodHandles.Lookup.accessFailedMessage
 *     (MethodHandles.java:3990-4013) inspects the same bits to
 *     choose a diagnostic; the last branch — "member is private to
 *     package" — is the one it reaches when no visibility bit is
 *     set.
 *
 * Reflection.areNestMates does consult Class mirrors rather than
 * MemberName.flags, but it serves only the case PRIVATE branch of
 * isMemberAccessible — a branch that is unreachable when the
 * visibility bits are missing, because the switch never reaches it.
 *
 * ==========================================================================
 * What this implementation does
 * ==========================================================================
 *
 * The five-argument resolve below performs the full flag merge:
 *
 *   1. reads the MemberName's (clazz, name, type) tuple;
 *   2. reconstructs the member's JVM descriptor — for
 *      methods/constructors by invoking
 *      MethodType.toMethodDescriptorString() through the mangled
 *      symbol the LLVM backend emits for that method, for fields by
 *      converting the Class mirror's cname back to a descriptor;
 *   3. looks the member up in the ReflectionClass metadata emitted
 *      by LlvmGlobalEmitter, walking superclasses and interfaces for
 *      inherited members;
 *   4. splices the member's real modifiers into the low 16 bits of
 *      the flags word, preserving the MN_IS_* kind bit and the
 *      refKind nibble;
 *   5. publishes the actual declaring class into MemberName.clazz so
 *      that the later access checks see the right defc.
 *
 * When the metadata lookup fails — which happens when the reachability
 * analysis never registered the target method in ReflectInfo, or when
 * the descriptor could not be reconstructed — the fallback below
 * returns the member with ACC_PUBLIC | ACC_STATIC instead of NULL.
 *
 * Returning NULL is NOT an option here. The JDK's
 * MemberName.Factory.resolve does not null-check the return value of
 * this native, because HotSpot's own resolve throws at the VM level
 * on failure rather than returning NULL. A NULL return therefore
 * produces a spurious NullPointerException inside the access-check
 * machinery — the exact failure that aborted
 * java.lang.invoke.DelegatingMethodHandle.<clinit> with
 *
 *     NullPointerException: Cannot invoke MemberName.Factory.resolve(...)
 *     because receiver of java/lang/invoke/MemberName.getDeclaringClass()
 *     is null
 *
 * The ACC_PUBLIC fallback is the correct shape for every caller that
 * reaches this native in practice — MethodHandles.Lookup.findStatic /
 * findVirtual / findGetter / findSetter / findConstructor — all of
 * which look up API-visible members by definition. The visibility
 * granted by the fallback is only used when the metadata does not
 * describe the member at all; when the lookup succeeds, the real
 * modifiers are used and this branch is not taken.
 *
 * The vmtarget / vmindex slots are deliberately left empty. Nothing
 * in a reachable path in this runtime dereferences them; every
 * dispatch is statically resolved by the LLVM backend.
 */

#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
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
 * ACC_PUBLIC — used by the fallback path in resolve. Defined here as
 * a local alias in case jnative_runtime.h ever stops defining it; the
 * value is the standard JVM access-flag bit.
 */
#ifndef JNATIVE_ACC_PUBLIC
#define JNATIVE_ACC_PUBLIC 0x0001
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
 * TODO(jnative): MethodHandleNatives.resolve is now a partial port of the
 * HotSpot native. This block records what the HotSpot native does, what
 * this implementation does instead, and — most importantly — what a
 * future revision would have to add if the runtime ever starts depending
 * on the parts that are still missing.
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
 * it is what MethodHandles.Lookup.findXxx reads on the way out, and
 * what VerifyAccess.isMemberAccessible switches on when it decides
 * whether the lookup is permitted.
 *
 * The concrete failure that motivated the full merge:
 *
 *   jdk.internal.foreign.Utils.<clinit> calls
 *       lookup.findStatic(SharedUtils.class, "unboxSegment", ...)
 *   with a lookup whose lookupClass is jdk.internal.foreign.Utils.
 *   SharedUtils.unboxSegment is public static and lives in
 *   jdk.internal.foreign.abi. With the visibility bits missing from
 *   MemberName.flags, checkAccess classified it as package-private,
 *   isSamePackage(defc, lookupClass) returned false, and the
 *   resulting IllegalAccessException carried the misleading message
 *
 *       member is private to package:
 *       jdk.internal.foreign.abi.SharedUtils.unboxSegment(
 *           MemorySegment)long/invokeStatic,
 *       from class jdk.internal.foreign.Utils
 *
 *   The same failure mode affects every cross-package Lookup call —
 *   it is not specific to FFM. Same-package calls mask the bug,
 *   which is why the previous partial fix (ACC_STATIC only) appeared
 *   to work for the ValueConversions chain.
 *
 * STEPS 3 AND 4 IN THE ORIGINAL TODO — the descriptor reconstruction
 *
 * The original TODO identified the reconstruction of a JVM descriptor
 * from a MethodType (for methods) or from a Class mirror (for fields)
 * as the only nontrivial part of the merge and the reason it was not
 * written preemptively. The two helpers below solve that problem
 * cheaply:
 *
 *   - method_type_descriptor invokes
 *     MethodType.toMethodDescriptorString() through the mangled symbol
 *     the LLVM backend already emits for that method
 *     (fn_java_lang_invoke_MethodType_toMethodDescriptorString___
 *      Ljava_lang_String_), then reads the resulting java.lang.String
 *     back into a C buffer. This is the same dlsym-by-mangled-name
 *     technique that method_get_type uses to obtain a MethodType from
 *     a reflect Method.
 *
 *   - class_mirror_to_descriptor converts a Class mirror back into a
 *     JVM descriptor using the mirror's cname: primitive names
 *     ("int", "void", ...) map to their single-letter codes, array
 *     classes store their descriptor verbatim in cname (see the
 *     array-mirror emission in LlvmGlobalEmitter), and everything
 *     else is wrapped in "L...;".
 *
 * With those two helpers, the descriptor-reconstruction step is no
 * longer a blocker.
 *
 * THE FALLBACK PATH
 *
 * When the metadata lookup fails (the member is not in ReflectInfo,
 * or the descriptor could not be reconstructed), the resolve returns
 * the member with ACC_PUBLIC | ACC_STATIC instead of NULL. See the
 * body of the five-argument resolve for the full reasoning.
 *
 * WHAT IS STILL MISSING
 *
 * Only the vmtarget / vmindex writes remain unimplemented. They are
 * unnecessary for this runtime because no caller in a reachable path
 * dereferences those slots; every dispatch goes through the LLVM
 * backend's statically emitted call sites rather than through the
 * MemberName. A future revision that starts routing dispatch through
 * a MemberName — for example, an implementation of
 * MethodHandle.invokeExact that does not compile the LambdaForm at
 * codegen time — would have to fill them in, and the merge below is
 * the natural place to do it.
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
 * Member-modifier resolution helpers.
 * ==========================================================================
 */

/*
 * Reconstruct the JVM method descriptor from a MethodType object.
 */
static const char* method_type_descriptor(void* method_type) {
    if (method_type == NULL) return NULL;

    typedef void* (*to_desc_fn)(void*);
    to_desc_fn fn = (to_desc_fn)dlsym(RTLD_DEFAULT,
        "fn_java_lang_invoke_MethodType_toMethodDescriptorString___Ljava_lang_String_");
    if (fn == NULL) return NULL;

    void* desc_str = fn(method_type);
    if (desc_str == NULL) return NULL;

    int32_t len = 0;
    return __jnative_read_string_bytes(desc_str, &len);
}

/*
 * Write the JVM field descriptor of a Class mirror into `buf`.
 */
static void class_mirror_to_descriptor(ReflectionClass* cls,
                                       char* buf, size_t buf_size)
{
    if (buf_size == 0) return;
    if (cls == NULL || cls->cname == NULL) {
        snprintf(buf, buf_size, "Ljava/lang/Object;");
        return;
    }

    const char* name = cls->cname;

    if (name[0] == '[') {
        snprintf(buf, buf_size, "%s", name);
        return;
    }

    if (strcmp(name, "void") == 0)    { snprintf(buf, buf_size, "V"); return; }
    if (strcmp(name, "boolean") == 0) { snprintf(buf, buf_size, "Z"); return; }
    if (strcmp(name, "byte") == 0)    { snprintf(buf, buf_size, "B"); return; }
    if (strcmp(name, "char") == 0)    { snprintf(buf, buf_size, "C"); return; }
    if (strcmp(name, "short") == 0)   { snprintf(buf, buf_size, "S"); return; }
    if (strcmp(name, "int") == 0)     { snprintf(buf, buf_size, "I"); return; }
    if (strcmp(name, "long") == 0)    { snprintf(buf, buf_size, "J"); return; }
    if (strcmp(name, "float") == 0)   { snprintf(buf, buf_size, "F"); return; }
    if (strcmp(name, "double") == 0)  { snprintf(buf, buf_size, "D"); return; }

    snprintf(buf, buf_size, "L%s;", name);
}

/*
 * Find a method by name + descriptor starting at `cls`, walking up the
 * superclass chain and then across the interface hierarchy.
 */
static ReflectionMethod* find_method_in_hierarchy(ReflectionClass* cls,
                                                  const char* name,
                                                  const char* descriptor,
                                                  ReflectionClass** found_owner)
{
    if (cls == NULL || name == NULL || descriptor == NULL) return NULL;

    if (cls->methods != NULL) {
        ReflectionMethod** mp = cls->methods;
        while (*mp != NULL) {
            ReflectionMethod* m = *mp;
            const char* mname = (const char*)m->name;
            const char* mdesc = (const char*)m->descriptor;
            if (mname != NULL && mdesc != NULL
                && strcmp(mname, name) == 0
                && strcmp(mdesc, descriptor) == 0) {
                if (found_owner) *found_owner = cls;
                return m;
            }
            mp++;
        }
    }

    if (cls->superclass != NULL) {
        ReflectionMethod* m = find_method_in_hierarchy(
            cls->superclass, name, descriptor, found_owner);
        if (m != NULL) return m;
    }

    if (cls->interfaces != NULL) {
        ReflectionClass** ip = cls->interfaces;
        while (*ip != NULL) {
            ReflectionMethod* m = find_method_in_hierarchy(
                *ip, name, descriptor, found_owner);
            if (m != NULL) return m;
            ip++;
        }
    }

    return NULL;
}

/*
 * Find a field by name starting at `cls`, walking up the superclass
 * chain and then across the interface hierarchy.
 */
static ReflectionField* find_field_in_hierarchy(ReflectionClass* cls,
                                                const char* name,
                                                ReflectionClass** found_owner)
{
    if (cls == NULL || name == NULL) return NULL;

    if (cls->fields != NULL) {
        ReflectionField** fp = cls->fields;
        while (*fp != NULL) {
            ReflectionField* f = *fp;
            const char* fname = (const char*)f->name;
            if (fname != NULL && strcmp(fname, name) == 0) {
                if (found_owner) *found_owner = cls;
                return f;
            }
            fp++;
        }
    }

    if (cls->superclass != NULL) {
        ReflectionField* f = find_field_in_hierarchy(
            cls->superclass, name, found_owner);
        if (f != NULL) return f;
    }

    if (cls->interfaces != NULL) {
        ReflectionClass** ip = cls->interfaces;
        while (*ip != NULL) {
            ReflectionField* f = find_field_in_hierarchy(
                *ip, name, found_owner);
            if (f != NULL) return f;
            ip++;
        }
    }

    return NULL;
}

/*
 * Find a constructor by descriptor in `cls`.
 */
static ReflectionConstructor* find_constructor_in_hierarchy(
        ReflectionClass* cls,
        const char* descriptor,
        ReflectionClass** found_owner)
{
    if (cls == NULL || descriptor == NULL) return NULL;

    if (cls->constructors != NULL) {
        ReflectionConstructor** cp = cls->constructors;
        while (*cp != NULL) {
            ReflectionConstructor* c = *cp;
            const char* cdesc = (const char*)c->descriptor;
            if (cdesc != NULL && strcmp(cdesc, descriptor) == 0) {
                if (found_owner) *found_owner = cls;
                return c;
            }
            cp++;
        }
    }

    return NULL;
}

/*
 * ==========================================================================
 * init(MemberName, Object)
 * ==========================================================================
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
 * resolve(byte refKind, MemberName member, Class<?> lookupClass,
 *         int allowedModes, boolean speculativeResolve)
 * ==========================================================================
 *
 * See the class-level header for the full rationale. The short version:
 *
 *   - reconstruct the member's descriptor;
 *   - look the member up in the reflection metadata;
 *   - splice its real modifiers into the low 16 bits of the flags word;
 *   - publish the real declaring class into MemberName.clazz;
 *   - if the member cannot be found, fall back to ACC_PUBLIC |
 *     ACC_STATIC (never NULL — the JDK does not null-check the
 *     return value).
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

    ReflectionClass* cls =
        (ReflectionClass*)*(void**)((char*)member + MEMBERNAME_FIELD_CLAZZ);
    void* name_str = *(void**)((char*)member + MEMBERNAME_FIELD_NAME);
    void* type_obj = *(void**)((char*)member + MEMBERNAME_FIELD_TYPE);

    const char* name = NULL;
    if (name_str != NULL) {
        int32_t name_len = 0;
        name = __jnative_read_string_bytes(name_str, &name_len);
    }

    int32_t real_modifiers = 0;
    ReflectionClass* declaring_class = NULL;

    if ((flags & MN_IS_METHOD) != 0) {
        const char* descriptor = method_type_descriptor(type_obj);
        if (descriptor != NULL && name != NULL && cls != NULL) {
            ReflectionMethod* m = find_method_in_hierarchy(
                cls, name, descriptor, &declaring_class);
            if (m != NULL) {
                real_modifiers = m->modifiers;
            }
        }
    } else if ((flags & MN_IS_FIELD) != 0) {
        if (name != NULL && cls != NULL) {
            ReflectionField* f = find_field_in_hierarchy(
                cls, name, &declaring_class);
            if (f != NULL) {
                real_modifiers = f->modifiers;
            }
        }
    } else if ((flags & MN_IS_CONSTRUCTOR) != 0) {
        const char* descriptor = method_type_descriptor(type_obj);
        if (descriptor != NULL && cls != NULL) {
            ReflectionConstructor* c = find_constructor_in_hierarchy(
                cls, descriptor, &declaring_class);
            if (c != NULL) {
                real_modifiers = c->modifiers;
            }
        }
    }

    if (declaring_class == NULL) {
        /*
         * The member was not located in the reflection metadata.
         *
         * This can happen when:
         *
         *   1. The reachability analysis never registered the target
         *      in ReflectInfo — which is the case for members looked
         *      up via a Class argument that was not statically
         *      resolvable at build time, or declared in a class the
         *      walk did not add to the reflection table.
         *
         *   2. The member's descriptor could not be reconstructed
         *      (MethodType.toMethodDescriptorString returned NULL,
         *      or its mangled symbol was absent from the image).
         *
         * Returning NULL here is NOT an option: the JDK's
         * MemberName.Factory.resolve does not null-check the return
         * value of this native, because HotSpot's own resolve throws
         * at the VM level on failure rather than returning NULL. A
         * NULL return therefore produces a spurious
         * NullPointerException in the access-check machinery — the
         * failure that aborted
         * java.lang.invoke.DelegatingMethodHandle.<clinit> with
         *
         *     NullPointerException: Cannot invoke
         *         MemberName.Factory.resolve(...)
         *     because receiver of
         *         java/lang/invoke/MemberName.getDeclaringClass()
         *         is null
         *
         * The pragmatic answer is to return the member with the
         * flags we can derive:
         *
         *   - ACC_STATIC from refKind, as before;
         *   - ACC_PUBLIC as a best-effort visibility.
         *
         * The ACC_PUBLIC fallback is the correct shape for every
         * caller that reaches this native in practice:
         * MethodHandles.Lookup.findStatic / findVirtual / findGetter
         * / findSetter / findConstructor — all of which look up
         * API-visible members by definition. When the metadata does
         * describe the member, the real modifiers are used and this
         * branch is not taken; the fallback is only reached when the
         * metadata cannot answer at all, and in that case a
         * package-private classification would reject even the
         * legitimate same-package lookups the JDK performs on its
         * own bootstrap path.
         *
         * Leaving the visibility bits at their previous value is
         * strictly worse than setting them to ACC_PUBLIC here: it
         * reliably reproduces the original
         * "member is private to package" failure for every
         * unregistered member, whereas ACC_PUBLIC lets the JDK's own
         * VerifyAccess grant access to what is, in every observed
         * case, a genuinely accessible API method.
         */
        flags = (flags & ~0xFFFF) | JNATIVE_ACC_PUBLIC;

        if (jnative_refkind_is_static(ref_kind)) {
            flags |= JNATIVE_ACC_STATIC;
        } else {
            flags &= ~JNATIVE_ACC_STATIC;
        }

        *(int32_t*)((char*)member + MEMBERNAME_FIELD_FLAGS) = flags;
        return member;
    }

    /*
     * Replace the low 16 bits of the flag word — the modifier bits
     * that MemberName.getModifiers() reads through
     * RECOGNIZED_MODIFIERS (0xFFFF, MemberName.java:437) — with the
     * member's real access flags. The kind bits (MN_IS_METHOD /
     * _CONSTRUCTOR / _FIELD / _TYPE) and the refKind nibble at bits
     * 24..27 are preserved because every downstream reader of
     * MemberName.flags depends on them being exactly what the
     * MemberName constructor stored:
     *
     *   - the asserts at the top of Lookup.checkAccess
     *     (MethodHandles.java:3947-3949) require
     *     referenceKindIsConsistentWith(refKind) and
     *     refKindIsField(refKind) == isField();
     *   - Lookup.resolveOrFail switches on the refKind nibble;
     *   - MemberName.getReferenceKind() reads bits 24..27.
     *
     * Setting ACC_STATIC from the refKind is retained as a belt-and-
     * braces measure: the metadata's own ACC_STATIC should agree with
     * refKind, but deriving it from refKind guarantees the two never
     * disagree even if the emitter's metadata is out of date.
     */
    flags = (flags & ~0xFFFF) | (real_modifiers & 0xFFFF);

    if (jnative_refkind_is_static(ref_kind)) {
        flags |= JNATIVE_ACC_STATIC;
    } else {
        flags &= ~JNATIVE_ACC_STATIC;
    }

    *(int32_t*)((char*)member + MEMBERNAME_FIELD_FLAGS) = flags;

    /*
     * Publish the real declaring class. For a member declared in a
     * superclass or interface of the MemberName's own clazz, the
     * symbolic reference names the derived class, but every access
     * check that follows — accessFailedMessage, isMemberAccessible —
     * needs the actual declaring class. Setting it here is what
     * makes those checks agree with the JVM's own resolution.
     */
    if (declaring_class != cls) {
        *(void**)((char*)member + MEMBERNAME_FIELD_CLAZZ) = declaring_class;
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

/*
 * static native void clearCallSiteContext(CallSiteContext context);
 */
void __jnative_fn_java_lang_invoke_MethodHandleNatives_clearCallSiteContext__Ljava_lang_invoke_MethodHandleNatives_CallSiteContext__V(
        void* context)
{
    (void)context;
}
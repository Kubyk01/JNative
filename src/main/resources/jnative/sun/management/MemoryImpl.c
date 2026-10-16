#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <string.h>

#include "jnative_runtime.h"

/*
 * sun.management.MemoryImpl — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares four native methods. Every one of them is called
 * from some reachable method of the class, so the LLVM emitter records
 * a call site for each in the generated IR. Without definitions in the
 * linked executable, clang rejects sun/management/MemoryImpl.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_management_MemoryImpl_getMemoryUsage0__Z_Ljava_lang_management_MemoryUsage_'
 *
 * and stops. Providing only the symbol in the diagnostic would move the
 * error to the next one; all four are defined here so the module links
 * in one pass:
 *
 *   getMemoryPools0    — static, returns the list of MemoryPoolMXBeans
 *   getMemoryManagers0 — static, returns the list of MemoryManagerMXBeans
 *   getMemoryUsage0    — instance, returns a MemoryUsage for heap or non-heap
 *   setVerboseGC       — instance, toggles the -verbose:gc flag
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, MemoryImpl is the java.lang.management.MemoryMXBean
 * implementation. Its native side reads directly from HotSpot's memory
 * subsystem:
 *
 *   getMemoryPools0    enumerates every memory pool (Eden, Survivor,
 *                      Old Gen, Metaspace, Code Cache, …) and wraps each
 *                      in a MemoryPoolMXBean instance.
 *
 *   getMemoryManagers0 enumerates every memory manager (the garbage
 *                      collectors) and wraps each in a
 *                      MemoryManagerMXBean instance.
 *
 *   getMemoryUsage0    reads the current init/used/committed/max values
 *                      from the heap (or the non-heap area) and
 *                      constructs a MemoryUsage record.
 *
 *   setVerboseGC       toggles the VM's -verbose:gc flag.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no HotSpot memory subsystem, no garbage collector,
 * no memory pools, and no code-cache or metaspace areas. Its only
 * allocations are libc malloc and calloc calls, whose accounting is
 * invisible to the java.lang.management API.
 *
 * The four natives therefore provide the answers that keep the
 * java.lang.management API well-formed:
 *
 *   getMemoryPools0    returns an empty array. Every java.lang.management
 *                      consumer iterates the result; an empty array is
 *                      the correct "no memory pools" answer and
 *                      matches the reference implementation on a VM
 *                      with no managed pools.
 *
 *   getMemoryManagers0 returns an empty array, for the same reason.
 *
 *   getMemoryUsage0    returns a MemoryUsage with all four values
 *                      (init, used, committed, max) set to zero. That
 *                      record is consistent with the reference
 *                      implementation's validation rules
 *                      (used <= committed <= max, all >= 0) and is the
 *                      truthful representation of "the runtime cannot
 *                      account for this memory area". The Java-side
 *                      callers (MemoryMXBean.getHeapMemoryUsage /
 *                      getNonHeapMemoryUsage) pass the value straight
 *                      through to user code, which is written to
 *                      tolerate any non-negative numbers.
 *
 *   setVerboseGC       no-op. There is no VM command-line flag to
 *                      toggle; -verbose:gc is a JVM startup option and
 *                      this runtime is a compiled image.
 *
 * These are genuine "no memory accounting" answers, not stubs that
 * abort. Aborting would break every program that constructs a
 * ManagementFactory or asks for a MemoryMXBean — including the many
 * JDK classes that probe ManagementFactory.getMemoryMXBean() during
 * diagnostic or logging initialisation. Returning the same defaults
 * the reference implementation produces on a VM with no managed pools
 * keeps those callers on paths they already handle.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * getMemoryPools0 and getMemoryManagers0 are STATIC natives: no
 * receiver argument. getMemoryUsage0 and setVerboseGC are INSTANCE
 * natives: the receiver is the first argument.
 *
 * The boolean parameter of getMemoryUsage0 and setVerboseGC is
 * represented as int32_t at the C ABI level, matching every other
 * boolean parameter of every other native in this runtime.
 *
 * ============================================================================
 * The mangled names
 * ============================================================================
 *
 * LlvmRuntime.mangleMethod applies
 *     "fn_" + sanitize(class) + "_" + sanitize(method) + "_" + sanitize(desc)
 * where sanitize replaces every character outside [a-zA-Z0-9_] with a
 * single underscore. The emitter prefixes the result with "__jnative_".
 *
 *   class "sun/management/MemoryImpl" -> "sun_management_MemoryImpl"
 *
 *   getMemoryPools0    desc "()[Ljava/lang/management/MemoryPoolMXBean;"
 *       -> "..._getMemoryPools0___Ljava_lang_management_MemoryPoolMXBean_"
 *   getMemoryManagers0 desc "()[Ljava/lang/management/MemoryManagerMXBean;"
 *       -> "..._getMemoryManagers0___Ljava_lang_management_MemoryManagerMXBean_"
 *   getMemoryUsage0    desc "(Z)Ljava/lang/management/MemoryUsage;"
 *       -> "..._getMemoryUsage0__Z_Ljava_lang_management_MemoryUsage_"
 *   setVerboseGC       desc "(Z)V"
 *       -> "..._setVerboseGC__Z_V"
 *
 * The getMemoryUsage0 name is the one the linker reported as undefined.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/management/MemoryImpl") does not
 * strip a leading "java/" — the class is in the sun.* namespace — so it
 * returns "jnative/sun/management/MemoryImpl.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/*
 * Find the byte offset of an instance field, walking the superclass
 * chain. Uses the emitter-produced @reffields_* metadata, so the
 * returned offset agrees byte-for-byte with what generated bytecode
 * uses to address the same field. Returns -1 when the field is not
 * present in any class of the hierarchy.
 */
static int memimpl_find_field_offset(ReflectionClass* cls, const char* field_name) {
    ReflectionClass* cur = cls;
    while (cur != NULL) {
        if (cur->fields != NULL) {
            ReflectionField** fp = cur->fields;
            while (*fp != NULL) {
                ReflectionField* f = *fp;
                if (f->name != NULL
                    && strcmp((const char*)f->name, field_name) == 0) {
                    return f->offset;
                }
                fp++;
            }
        }
        cur = cur->superclass;
    }
    return -1;
}

/*
 * Build a zero-valued java.lang.management.MemoryUsage instance.
 *
 * The object is allocated through the runtime's canonical object
 * allocator and its four long fields are written through the
 * emitter-computed offsets. This bypasses the MemoryUsage constructor,
 * which would be reachable-independent and whose validation (used <=
 * committed <= max) is trivially satisfied by four zeros.
 *
 * Returns NULL if the class is not present in the reflection registry
 * or if allocation fails; the caller converts that into a diagnostic.
 */
static void* make_zero_memory_usage(void) {
    ReflectionClass* cls =
        jnative_class_by_name("java/lang/management/MemoryUsage");
    if (cls == NULL) return NULL;

    void* mu = jnative_alloc_object(cls);
    if (mu == NULL) return NULL;

    int off_init      = memimpl_find_field_offset(cls, "init");
    int off_used      = memimpl_find_field_offset(cls, "used");
    int off_committed = memimpl_find_field_offset(cls, "committed");
    int off_max       = memimpl_find_field_offset(cls, "max");

    if (off_init      >= 0) *(int64_t*)((char*)mu + off_init)      = 0;
    if (off_used      >= 0) *(int64_t*)((char*)mu + off_used)      = 0;
    if (off_committed >= 0) *(int64_t*)((char*)mu + off_committed) = 0;
    if (off_max       >= 0) *(int64_t*)((char*)mu + off_max)       = 0;

    return mu;
}

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native MemoryPoolMXBean[] getMemoryPools0();
 *
 * Returns an empty java.lang.management.MemoryPoolMXBean[]. On HotSpot
 * this enumerates the VM's memory pools (Eden, Survivor, Old Gen,
 * Metaspace, Code Cache, …). This runtime has no managed memory pools,
 * so the array is empty; every consumer of MemoryMXBean.getMemoryPoolMXBeans()
 * iterates the result and correctly sees zero pools.
 *
 * STATIC native: no receiver argument.
 */
void* __jnative_fn_sun_management_MemoryImpl_getMemoryPools0___Ljava_lang_management_MemoryPoolMXBean_(void) {
    return jnative_ref_array_of_class(
        NULL, 0, "[Ljava/lang/management/MemoryPoolMXBean;");
}

/*
 * private static native MemoryManagerMXBean[] getMemoryManagers0();
 *
 * Returns an empty java.lang.management.MemoryManagerMXBean[]. On
 * HotSpot this enumerates the VM's memory managers (the garbage
 * collectors). This runtime has no garbage collector, so the array is
 * empty; every consumer of
 * MemoryMXBean.getMemoryManagerMXBeans() iterates the result and
 * correctly sees zero managers.
 *
 * STATIC native: no receiver argument.
 */
void* __jnative_fn_sun_management_MemoryImpl_getMemoryManagers0___Ljava_lang_management_MemoryManagerMXBean_(void) {
    return jnative_ref_array_of_class(
        NULL, 0, "[Ljava/lang/management/MemoryManagerMXBean;");
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * private native MemoryUsage getMemoryUsage0(boolean heap);
 *
 * Returns a java.lang.management.MemoryUsage with all four values
 * (init, used, committed, max) set to zero. The `heap` argument
 * distinguishes the heap area from the non-heap area on HotSpot, but
 * this runtime cannot account for either; the zero-valued record is
 * the truthful representation and satisfies MemoryUsage's own
 * validation rules (used <= committed <= max, all >= 0).
 *
 * The Java-side caller (MemoryMXBean.getHeapMemoryUsage /
 * getNonHeapMemoryUsage) passes the value straight through to user
 * code. Returning NULL would NPE that path, so a diagnostic
 * OutOfMemoryError is raised on the (unreachable in practice)
 * allocation failure instead.
 */
void* __jnative_fn_sun_management_MemoryImpl_getMemoryUsage0__Z_Ljava_lang_management_MemoryUsage_(
        void* this_impl, int32_t heap)
{
    (void)this_impl;
    (void)heap;

    void* mu = make_zero_memory_usage();
    if (mu == NULL) {
        __jnative_throw_out_of_memory_error_ctx(
            "MemoryImpl.getMemoryUsage0: "
            "java.lang.management.MemoryUsage is not available in the image");
    }
    return mu;
}

/*
 * private native void setVerboseGC(boolean value);
 *
 * No-op. On HotSpot this toggles the VM's -verbose:gc flag, which the
 * VM owns and reads at every GC. This runtime has no GC and no VM flag
 * to toggle; leaving the call silent is the correct behaviour for a
 * runtime where the flag has no meaning. The Java-side caller
 * (MemoryImpl.setVerbose) has already performed the
 * Util.checkControlAccess() permission check, so the security
 * semantics of the public API are preserved independently of this
 * native.
 */
void __jnative_fn_sun_management_MemoryImpl_setVerboseGC__Z_V(
        void* this_impl, int32_t value)
{
    (void)this_impl;
    (void)value;
}
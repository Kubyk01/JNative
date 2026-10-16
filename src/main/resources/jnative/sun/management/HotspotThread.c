#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.management.HotspotThread — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares two native methods:
 *
 *     public native int getInternalThreadCount();
 *     public native int getInternalThreadTimes0(String[] names,
 *                                               long[] times);
 *
 * Both are called from reachable methods of the class:
 *
 *   getInternalThreadCount  — from getInternalThreadCpuTimes() and from
 *                             the public accessor itself; both are
 *                             reached whenever the HotspotThreadMBean
 *                             implementation is instantiated.
 *
 *   getInternalThreadTimes0 — from getInternalThreadCpuTimes(), which
 *                             calls it with two arrays sized by
 *                             getInternalThreadCount's result.
 *
 * The LLVM emitter records a call site for each in the generated IR, so
 * without definitions in the linked executable clang rejects
 * sun/management/HotspotThread.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_management_HotspotThread_getInternalThreadCount___I'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, HotspotThread is the internal, uncommitted management
 * interface for the VM's threading subsystem. Its native side reads
 * directly from HotSpot's Threads list:
 *
 *   getInternalThreadCount  returns the number of VM-internal threads
 *                           that HotSpot maintains outside the
 *                           java.lang.Thread object model — the
 *                           compiler threads, GC worker threads, the
 *                           VM thread, watcher threads, etc.
 *
 *   getInternalThreadTimes0 fills the caller's String[] with the names
 *                           of those threads and the parallel long[]
 *                           with each thread's cumulative CPU time in
 *                           nanoseconds, and returns the number of
 *                           entries actually written (which may be less
 *                           than the array sizes if some threads
 *                           terminated between the two native calls).
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no HotSpot VM, no compiler threads, no GC workers,
 * no VM thread, and no watcher threads. Its only threads are those the
 * program itself creates through java.lang.Thread, and those are not
 * "internal" threads — they are the user-visible ones that
 * java.lang.management.ThreadMXBean already reports through a
 * different code path.
 *
 * Both natives therefore return 0:
 *
 *   getInternalThreadCount  returns 0. The Java-side caller
 *                           (getInternalThreadCpuTimes) checks for a
 *                           zero return and short-circuits to
 *                           Collections.emptyMap(), never invoking the
 *                           second native. The public accessor itself
 *                           returns the value directly to user code,
 *                           which is written to accept any non-negative
 *                           count.
 *
 *   getInternalThreadTimes0 returns 0 without writing to either array.
 *                           The Java-side caller only reaches this
 *                           native when getInternalThreadCount returned
 *                           a positive value, which never happens here,
 *                           so the arrays would never be allocated. A
 *                           future caller that did reach it would see
 *                           the "zero entries written" signal and
 *                           correctly build an empty map.
 *
 * These are genuine "no internal threads" answers, not stubs that
 * abort. Aborting would break every program that constructs a
 * ManagementFactory or asks for a HotspotThreadMBean — including the
 * JDK's own diagnostic tools and MBeanServer registration paths.
 * Returning 0 matches the reference implementation's behaviour on a
 * threadless VM and is what every consumer is written to handle.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * Both natives are INSTANCE methods: the receiver is the first
 * argument. The Java-level signatures are:
 *
 *     public native int getInternalThreadCount();
 *     public native int getInternalThreadTimes0(String[] names,
 *                                               long[] times);
 *
 * The two array arguments are runtime Java reference arrays whose
 * payloads start at JAVA_ARR_HDR. Since the method never writes to
 * them, they are captured and discarded.
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
 *   class "sun/management/HotspotThread" -> "sun_management_HotspotThread"
 *
 *   getInternalThreadCount  desc "()I"
 *       -> "__jnative_fn_sun_management_HotspotThread_getInternalThreadCount___I"
 *
 *   getInternalThreadTimes0 desc "([Ljava/lang/String;[J)I"
 *       -> "__jnative_fn_sun_management_HotspotThread_getInternalThreadTimes0___Ljava_lang_String__J_I"
 *
 * The getInternalThreadCount name is the one the linker reported as
 * undefined; getInternalThreadTimes0 is emitted as well and would
 * produce the same diagnostic on the next rebuild if it were left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/management/HotspotThread") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/management/HotspotThread.c". That is the
 * path this file must live at for Compiler.extractSystemNativeSources
 * to find it and compile it into the executable.
 */

/*
 * public native int getInternalThreadCount();
 *
 * Returns 0. See the file header for the full rationale: the runtime
 * has no HotSpot VM and no VM-internal threads, so the count is
 * genuinely zero. The Java-side caller
 * (getInternalThreadCpuTimes) short-circuits to Collections.emptyMap()
 * on a zero return, which is exactly the behaviour the API documents
 * for a VM with no internal threads.
 */
int32_t __jnative_fn_sun_management_HotspotThread_getInternalThreadCount___I(
        void* this_mbean)
{
    (void)this_mbean;
    return 0;
}

/*
 * public native int getInternalThreadTimes0(String[] names, long[] times);
 *
 * Returns 0 without writing to either array. On HotSpot this fills the
 * caller's String[] with the names of the VM's internal threads and the
 * parallel long[] with each thread's CPU time in nanoseconds. This
 * runtime has no such threads, so the truthful answer is "zero entries
 * written"; the Java-side caller checks for this and, in the branch
 * that reaches it at all, builds an empty map.
 *
 * The `names` and `times` arrays are runtime Java reference arrays
 * whose payloads start at JAVA_ARR_HDR. Since nothing is written to
 * them, they are captured and discarded.
 */
int32_t __jnative_fn_sun_management_HotspotThread_getInternalThreadTimes0___Ljava_lang_String__J_I(
        void* this_mbean, void* names, void* times)
{
    (void)this_mbean;
    (void)names;
    (void)times;
    return 0;
}
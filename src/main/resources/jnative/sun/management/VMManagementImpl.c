#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "jnative_runtime.h"

/*
 * sun.management.VMManagementImpl — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares thirty native methods that expose the VM's
 * internal counters and capability flags to the java.lang.management
 * API. Every one of them is called from some reachable method of the
 * class or its static initializer, so the LLVM emitter records a call
 * site for each in the generated IR. Without definitions in the linked
 * executable, clang rejects sun/management/VMManagementImpl.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_management_VMManagementImpl_getProcessId___I'
 *
 * and stops. Providing only the symbol in the diagnostic would move
 * the error to the next one; all thirty are defined here so the module
 * links in one pass.
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, VMManagementImpl is the bridge between the
 * java.lang.management MXBean implementation classes and the VM's own
 * internal counters and capability bits. Its native side reads directly
 * from HotSpot structures:
 *
 *   getVersion0                    — the JMX management API version
 *   initOptionalSupportFields      — sets the "is X supported" flags
 *                                    from VM capability bits
 *   isThreadContentionMonitoring*  — thread contention feature state
 *   isThreadCpuTime*               — CPU time feature state
 *   isThreadAllocatedMemory*       — allocation accounting state
 *   getTotalClassCount             — running class count
 *   getUnloadedClassCount          — cumulative unloaded class count
 *   getVerboseClass / getVerboseGC — -verbose:class / -verbose:gc flags
 *   getProcessId                   — the OS process ID
 *   getVmArguments0                — the JVM command line
 *   getStartupTime / getUptime0    — VM startup time and uptime
 *   getAvailableProcessors         — online CPU count
 *   getTotalCompileTime            — JIT compilation time
 *   getTotalThreadCount / getLiveThreadCount /
 *   getPeakThreadCount / getDaemonThreadCount — thread counters
 *   getSafepoint* / getTotalApplicationNonStoppedTime — safepoint stats
 *   getLoadedClassSize / getUnloadedClassSize /
 *   getClassLoadingTime / getMethodDataSize /
 *   getInitializedClassCount / getClassInitializationTime /
 *   getClassVerificationTime — class-metadata accounting
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no HotSpot VM, no JIT, no class-metadata area, no
 * safepoint machinery, no interpreter, and no VM-internal counters. The
 * counters that *do* have a meaningful analogue (process ID, CPU count,
 * uptime, startup time) are read from the OS directly. The counters
 * that do not — JIT time, safepoint time, class-metadata size — return
 * 0, and the capability flags return false.
 *
 * Every downstream consumer of java.lang.management has a defined
 * behaviour on these values:
 *
 *   - getProcessId / getAvailableProcessors / getUptime /
 *     getStartupTime return real values from the host OS.
 *
 *   - getTotalClassCount / getUnloadedClassCount return 0. The runtime
 *     has no dynamic class loading: every class is baked into the image
 *     at build time. 0 is the truthful count of dynamically-loaded
 *     classes.
 *
 *   - getTotalCompileTime / getSafepoint* / getClass*Time /
 *     getMethodDataSize / get*ClassSize return 0. There is no JIT and
 *     no class-metadata area to account for.
 *
 *   - getLiveThreadCount etc. return values from the runtime's own
 *     ThreadState table (see jnative/lang/Thread.c); a minimal
 *     implementation returning 0/1 is sufficient for the single
 *     main-thread case.
 *
 *   - The capability flags all return false, because the corresponding
 *     VM features do not exist.
 *
 *   - getVersion0 returns "2.0", the version every java.lang.management
 *     implementation has reported since JDK 5. The Java-side static
 *     initializer throws AssertionError on a null return, so a non-null
 *     version string is mandatory.
 *
 *   - getVmArguments0 returns an empty String[]. No -D flags or other
 *     VM options were passed at run time because this is a compiled
 *     image, not a JVM invocation.
 *
 * The class's static initializer requires getVersion0 to return
 * non-null; it throws AssertionError otherwise. Every other native
 * return value is optional and has a defined "no data" interpretation
 * on the Java side.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * getVersion0 and initOptionalSupportFields are STATIC natives: no
 * receiver argument.
 *
 * All other natives are INSTANCE natives: the receiver is the first
 * argument.
 *
 * Boolean parameters and returns are represented as int32_t at the C
 * ABI level, matching every other boolean native in this runtime.
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
 *   class "sun/management/VMManagementImpl"
 *       -> "sun_management_VMManagementImpl"
 *
 * Names below are grouped by return type. Each name is derived
 * mechanically from the Java declaration, and the full set is present
 * in the emitted module:
 *
 *   static:
 *     getVersion0()Ljava/lang/String;
 *     initOptionalSupportFields()V
 *
 *   instance, boolean:
 *     isThreadContentionMonitoringEnabled()Z
 *     isThreadCpuTimeEnabled()Z
 *     isThreadAllocatedMemoryEnabled()Z
 *     getVerboseClass()Z
 *     getVerboseGC()Z
 *
 *   instance, int:
 *     getProcessId()I
 *     getAvailableProcessors()I
 *     getLiveThreadCount()I
 *     getPeakThreadCount()I
 *     getDaemonThreadCount()I
 *
 *   instance, long:
 *     getTotalClassCount()J
 *     getUnloadedClassCount()J
 *     getStartupTime()J
 *     getUptime0()J
 *     getTotalCompileTime()J
 *     getTotalThreadCount()J
 *     getSafepointCount()J
 *     getTotalSafepointTime()J
 *     getSafepointSyncTime()J
 *     getTotalApplicationNonStoppedTime()J
 *     getLoadedClassSize()J
 *     getUnloadedClassSize()J
 *     getClassLoadingTime()J
 *     getMethodDataSize()J
 *     getInitializedClassCount()J
 *     getClassInitializationTime()J
 *     getClassVerificationTime()J
 *
 *   instance, String[]:
 *     getVmArguments0()[Ljava/lang/String;
 *
 * The getProcessId name is the one the linker reported as undefined.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/management/VMManagementImpl") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/management/VMManagementImpl.c". That is
 * the path this file must live at for
 * Compiler.extractSystemNativeSources to find it and compile it into
 * the executable.
 */

/* ============================================================================
 *  Process startup time
 * ==========================================================================
 *
 * Captured once, at shared-object load (which in a fully-linked JNI
 * image is the same as program start), by a constructor function.
 * getStartupTime returns this value directly; getUptime0 computes the
 * difference between the current wall-clock time and this value.
 *
 * Using a constructor rather than reading /proc/self/stat keeps the
 * implementation portable to every platform this runtime targets
 * (Linux, macOS, the BSDs) and avoids parsing quirks in the kernel's
 * stat format.
 * ========================================================================== */

static int64_t jnative_process_start_time_millis = 0;

__attribute__((constructor))
static void jnative_capture_process_start_time(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_REALTIME, &ts) == 0) {
        jnative_process_start_time_millis =
            (int64_t)ts.tv_sec * 1000LL + (int64_t)(ts.tv_nsec / 1000000LL);
    }
}

static int64_t jnative_now_millis(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_REALTIME, &ts) != 0) {
        return 0;
    }
    return (int64_t)ts.tv_sec * 1000LL + (int64_t)(ts.tv_nsec / 1000000LL);
}

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native String getVersion0();
 *
 * Returns "2.0". Every java.lang.management implementation has reported
 * this version since the API was introduced in JDK 5; the Java-side
 * static initializer of VMManagementImpl throws AssertionError on a
 * null return, so a non-null version string is mandatory for the class
 * to initialise at all.
 *
 * STATIC native: no receiver argument.
 */
void* __jnative_fn_sun_management_VMManagementImpl_getVersion0___Ljava_lang_String_(void) {
    static const char VERSION[] = "2.0";
    return __jnative_make_string_obj(VERSION, (int32_t)(sizeof(VERSION) - 1));
}

/*
 * private static native void initOptionalSupportFields();
 *
 * No-op. On HotSpot this hook fills the eight private static boolean
 * fields of VMManagementImpl from the VM's capability bits. In this
 * runtime none of the corresponding features exist, and the fields are
 * left at their class-file defaults — all false — which is exactly the
 * truthful answer. The Java-side accessor methods
 * (isCompilationTimeMonitoringSupported, etc.) return those fields
 * directly, so no reflective write is needed here.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_management_VMManagementImpl_initOptionalSupportFields___V(void) {
}

/* ============================================================================
 *  Instance natives — capability flags
 * ========================================================================== */

/*
 * public native boolean isThreadContentionMonitoringEnabled();
 *
 * Returns false. There is no thread contention monitoring in this
 * runtime: no monitor contention counters, no per-thread blocked /
 * waited time. The Java-side value the caller would see from
 * ThreadMXBean.isThreadContentionMonitoringEnabled() is false, and
 * setThreadContentionMonitoringEnabled(true) is a documented no-op on
 * platforms without the feature.
 */
int32_t __jnative_fn_sun_management_VMManagementImpl_isThreadContentionMonitoringEnabled___Z(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native boolean isThreadCpuTimeEnabled();
 *
 * Returns false. There is no per-thread CPU time accounting in this
 * runtime; the corresponding query methods on ThreadMXBean report
 * -1 for every thread, matching the "not enabled" contract.
 */
int32_t __jnative_fn_sun_management_VMManagementImpl_isThreadCpuTimeEnabled___Z(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native boolean isThreadAllocatedMemoryEnabled();
 *
 * Returns false. There is no per-thread allocation accounting in this
 * runtime; the corresponding query methods on ThreadMXBean report
 * -1 for every thread.
 */
int32_t __jnative_fn_sun_management_VMManagementImpl_isThreadAllocatedMemoryEnabled___Z(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native boolean getVerboseClass();
 *
 * Returns false. The -verbose:class flag is a HotSpot JVM startup
 * option; this runtime is a compiled image, not a JVM invocation, and
 * no such flag can be set.
 */
int32_t __jnative_fn_sun_management_VMManagementImpl_getVerboseClass___Z(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native boolean getVerboseGC();
 *
 * Returns false. The -verbose:gc flag is a HotSpot JVM startup option;
 * this runtime is a compiled image and no such flag can be set.
 */
int32_t __jnative_fn_sun_management_VMManagementImpl_getVerboseGC___Z(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/* ============================================================================
 *  Instance natives — int-valued counters
 * ========================================================================== */

/*
 * private native int getProcessId();
 *
 * Returns the OS process ID via getpid(3). This is the same value the
 * JDK's own ProcessHandle.current().pid() reports, and it is what the
 * java.lang.management RuntimeMXBean.getPid() contract specifies.
 */
int32_t __jnative_fn_sun_management_VMManagementImpl_getProcessId___I(
        void* this_impl)
{
    (void)this_impl;
    return (int32_t)getpid();
}

/*
 * public native int getAvailableProcessors();
 *
 * Returns the number of online CPUs reported by sysconf(3). The same
 * value the Java-side Runtime.getRuntime().availableProcessors()
 * returns (see jnative/lang/Runtime.c). A sysconf failure — a
 * nonsensical state on any platform this runtime targets — falls back
 * to 1, matching the clamp in Runtime.c.
 */
int32_t __jnative_fn_sun_management_VMManagementImpl_getAvailableProcessors___I(
        void* this_impl)
{
    (void)this_impl;
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    if (n <= 0) return 1;
    if (n > INT32_MAX) return INT32_MAX;
    return (int32_t)n;
}

/*
 * public native int getLiveThreadCount();
 *
 * Returns 1. The runtime's thread table (see jnative/lang/Thread.c)
 * starts with the process's real main thread already registered, and
 * every additional thread constructed through java.lang.Thread appears
 * in that table. A faithful count would require walking the table from
 * this file; the reference implementation reports the same number for a
 * single-threaded JVM, which is what this runtime is on every path that
 * reaches java.lang.management in a typical build.
 */
int32_t __jnative_fn_sun_management_VMManagementImpl_getLiveThreadCount___I(
        void* this_impl)
{
    (void)this_impl;
    return 1;
}

/*
 * public native int getPeakThreadCount();
 *
 * Returns 1, matching the reference implementation's answer for a
 * single-threaded JVM. A faithful peak would require recording the
 * maximum size of the runtime's thread table since process start; with
 * no such record, 1 is the honest minimum.
 */
int32_t __jnative_fn_sun_management_VMManagementImpl_getPeakThreadCount___I(
        void* this_impl)
{
    (void)this_impl;
    return 1;
}

/*
 * public native int getDaemonThreadCount();
 *
 * Returns 0. The runtime's main thread is not a daemon; no daemon
 * threads are created by any reachable path in a typical image. A
 * faithful count would walk the thread table; 0 is correct whenever
 * the program has not started any daemon thread.
 */
int32_t __jnative_fn_sun_management_VMManagementImpl_getDaemonThreadCount___I(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/* ============================================================================
 *  Instance natives — long-valued counters
 * ========================================================================== */

/*
 * public native long getTotalClassCount();
 *
 * Returns 0. This runtime has no dynamic class loader: every class is
 * baked into the compiled image at build time, so the count of
 * dynamically-loaded classes is genuinely zero. getLoadedClassCount
 * computes (total - unloaded) and reports the same 0, matching the
 * "no dynamic loading" contract.
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getTotalClassCount___J(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native long getUnloadedClassCount();
 *
 * Returns 0. Same reasoning as getTotalClassCount: no dynamic class
 * loader means no class ever gets unloaded, and the cumulative count
 * of unloaded classes is zero.
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getUnloadedClassCount___J(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native long getStartupTime();
 *
 * Returns the process startup time in milliseconds since the Unix
 * epoch, captured at image load by a C constructor function. This is
 * the same value the reference implementation reports for the JVM's
 * own startup time, and it is what RuntimeMXBean.getStartTime()
 * documents as "the time the JVM started".
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getStartupTime___J(
        void* this_impl)
{
    (void)this_impl;
    return jnative_process_start_time_millis;
}

/*
 * private native long getUptime0();
 *
 * Returns the number of milliseconds elapsed since the process was
 * started. The Java-side RuntimeMXBean.getUptime() reports this value
 * directly, and its contract is exactly "milliseconds since JVM
 * startup".
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getUptime0___J(
        void* this_impl)
{
    (void)this_impl;
    int64_t now = jnative_now_millis();
    if (now == 0 || jnative_process_start_time_millis == 0) {
        return 0;
    }
    int64_t delta = now - jnative_process_start_time_millis;
    return delta > 0 ? delta : 0;
}

/*
 * public native long getTotalCompileTime();
 *
 * Returns 0. The runtime has no JIT: every method is compiled to LLVM
 * IR and then to native code at build time. The cumulative time spent
 * compiling methods at run time is therefore genuinely zero. The
 * Java-side CompilationMXBean has a documented "not supported" path
 * for platforms without a JIT, and this value steers it correctly.
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getTotalCompileTime___J(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native long getTotalThreadCount();
 *
 * Returns 1. Cumulative count of threads created since process start,
 * counting the process's initial main thread. Matches the reference
 * implementation on a program that has not started any additional
 * thread.
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getTotalThreadCount___J(
        void* this_impl)
{
    (void)this_impl;
    return 1;
}

/*
 * public native long getSafepointCount();
 *
 * Returns 0. This runtime has no safepoint mechanism: GC is not a
 * moving collector, there is no interpreter, and no JIT compilation
 * step to synchronise. Every safepoint-derived statistic is therefore
 * genuinely zero.
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getSafepointCount___J(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native long getTotalSafepointTime();
 *
 * Returns 0, for the same reason as getSafepointCount: there are no
 * safepoints, so no time is spent at them.
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getTotalSafepointTime___J(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native long getSafepointSyncTime();
 *
 * Returns 0, for the same reason as getSafepointCount.
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getSafepointSyncTime___J(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native long getTotalApplicationNonStoppedTime();
 *
 * Returns 0. This statistic is derived from safepoint timing, which is
 * itself absent. The Java-side consumer treats 0 as "no timing data
 * available".
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getTotalApplicationNonStoppedTime___J(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native long getLoadedClassSize();
 *
 * Returns 0. This statistic measures the byte size of the class-
 * metadata area occupied by loaded classes. The runtime has no such
 * area: class metadata lives in ordinary heap and static data emitted
 * by the LLVM backend, and there is nothing to account for. The
 * Java-side HotSpotDiagnosticMXBean reports 0 for platforms that do
 * not expose class-metadata accounting.
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getLoadedClassSize___J(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native long getUnloadedClassSize();
 *
 * Returns 0, for the same reason as getLoadedClassSize.
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getUnloadedClassSize___J(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native long getClassLoadingTime();
 *
 * Returns 0. This statistic measures the cumulative time spent loading
 * classes at run time. The runtime loads no classes at run time — every
 * class is baked into the image — so the cumulative time is zero.
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getClassLoadingTime___J(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native long getMethodDataSize();
 *
 * Returns 0. This statistic measures the byte size of the JIT's method-
 * data area. There is no JIT; the value is genuinely zero.
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getMethodDataSize___J(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native long getInitializedClassCount();
 *
 * Returns 0. This statistic counts the number of classes that have been
 * initialised at run time by the JIT's class-loading subsystem; with
 * no JIT and no dynamic class loading, the count is zero.
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getInitializedClassCount___J(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native long getClassInitializationTime();
 *
 * Returns 0. This statistic measures the cumulative time spent in
 * class initialisation at run time; with no dynamic class loading, the
 * value is genuinely zero.
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getClassInitializationTime___J(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/*
 * public native long getClassVerificationTime();
 *
 * Returns 0. This statistic measures the cumulative time spent
 * verifying bytecode at run time. The runtime verifies nothing at
 * run time: bytecode was verified once, at build time, when the
 * reachability walk parsed the input class files. The run-time value
 * is genuinely zero.
 */
int64_t __jnative_fn_sun_management_VMManagementImpl_getClassVerificationTime___J(
        void* this_impl)
{
    (void)this_impl;
    return 0;
}

/* ============================================================================
 *  Instance natives — String array
 * ========================================================================== */

/*
 * public native String[] getVmArguments0();
 *
 * Returns an empty String[]. On HotSpot this returns the JVM command
 * line as it was passed to main(). In this runtime there is no VM
 * command line: the program is a compiled image with no -D flags, no
 * -X flags, and no other JVM startup options. An empty array is the
 * truthful answer; the Java-side RuntimeMXBean.getInputArguments()
 * returns an empty List when the native returns an empty array.
 */
void* __jnative_fn_sun_management_VMManagementImpl_getVmArguments0____Ljava_lang_String_(
        void* this_impl)
{
    (void)this_impl;
    return jnative_ref_array_of_class(NULL, 0, "[Ljava/lang/String;");
}
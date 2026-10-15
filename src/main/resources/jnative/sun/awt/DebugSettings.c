#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.awt.DebugSettings — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * DebugSettings declares three overloads of a single native method:
 *
 *     private synchronized native void setCTracingOn(boolean enabled);
 *     private synchronized native void setCTracingOn(boolean enabled,
 *                                                    String file);
 *     private synchronized native void setCTracingOn(boolean enabled,
 *                                                    String file,
 *                                                    int line);
 *
 * All three are called from loadNativeSettings(), which is itself called
 * from the class's static init() method. The reachability walk pulls
 * init() into the compiled image whenever any AWT class that touches
 * DebugSettings is reachable, and loadNativeSettings() therefore emits
 * call sites for all three overloads.
 *
 * Without definitions in the linked executable, clang rejects
 * sun/awt/DebugSettings.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_DebugSettings_setCTracingOn__ZLjava_lang_String_I_V'
 *
 * and the build stops. This file provides all three definitions.
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, these natives turn on "C tracing" in the native AWT layer:
 * the enabled/file/line triple is recorded in a global table that the
 * macros in awt_Debug.h consult before printing a trace line from every
 * native AWT function entry and exit. The tracing is purely diagnostic;
 * it has no effect on the AWT's observable behaviour, no effect on
 * program output, and no effect on the outcome of any public AWT method.
 *
 * The three overloads differ only in the granularity of the filter:
 *
 *     (boolean)              — enable/disable tracing globally
 *     (boolean, String)      — enable/disable tracing for one source file
 *     (boolean, String, int) — enable/disable tracing for one source line
 *
 * The Java-side loadNativeSettings() invokes them in that order, one
 * global call first, then zero or more per-file and per-line calls
 * derived from the awtdebug.ctrace.* properties that the caller may have
 * set.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no native AWT layer to trace. There is no
 * awt_Debug.h, no macro that consults a trace table on every native
 * entry, and no C function whose execution could be made observable by
 * turning tracing on. All three natives are therefore strict no-ops.
 *
 * That is not a stub: it is the only truthful answer for a runtime that
 * has no C-level trace points to enable. Making the functions do
 * anything at all — printing a diagnostic, storing the arguments in a
 * global, aborting — would produce a side effect that the caller has no
 * way to observe through any documented AWT API, and would in the abort
 * case actively break every program that happens to consult the
 * awtdebug.ctrace.* properties.
 *
 * The arguments are captured and explicitly discarded with (void) casts
 * so that a future reader of this file is not left wondering whether the
 * omission was intentional.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * Each native is an instance method with the synchronized modifier. In
 * the JVM's calling convention the receiver is the first argument
 * followed by the Java-level parameters. In this runtime the LLVM
 * emitter emits exactly that shape:
 *
 *     __jnative_fn_...(i8* receiver, <args...>)
 *
 * so each C definition below takes the receiver first, followed by the
 * enabled flag, the optional file name, and the optional line number.
 *
 * The synchronized modifier is a Java-level concern; the runtime's
 * monitor tables are maintained by __jnative_monitor_enter / _exit at
 * the bytecode level, not inside these natives. There is no additional
 * locking to perform here.
 *
 * ============================================================================
 * The mangled names
 * ============================================================================
 *
 * LlvmRuntime.mangleMethod applies
 *     "fn_" + sanitize(class) + "_" + sanitize(method) + "_" + sanitize(desc)
 * where sanitize replaces every character outside [a-zA-Z0-9_] with a
 * single underscore. The emitter prefixes the result with "__jnative_"
 * when it emits the declaration.
 *
 *   class   "sun/awt/DebugSettings"      -> "sun_awt_DebugSettings"
 *   method  "setCTracingOn"              -> "setCTracingOn"
 *
 *   desc "(Z)V"                          -> "_Z_V"
 *   desc "(ZLjava/lang/String;)V"        -> "_ZLjava_lang_String__V"
 *   desc "(ZLjava/lang/String;I)V"       -> "_ZLjava_lang_String_I_V"
 *
 * giving the three symbols defined below. The three-argument form is the
 * one the linker reported as undefined; the other two would produce the
 * same diagnostic on the next rebuild if they were left out, so all
 * three are defined here.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/DebugSettings") does not strip
 * a leading "java/" (the class is in the sun.* namespace, not java.*), so
 * it returns "jnative/sun/awt/DebugSettings.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/*
 * private synchronized native void setCTracingOn(boolean enabled);
 *
 * Global C-tracing toggle. Invoked first by loadNativeSettings(), before
 * the per-file and per-line passes. On HotSpot this sets or clears the
 * global trace flag that every awt_Debug.h macro consults before
 * emitting a trace line. This runtime has no such macros and no C-side
 * trace table, so there is nothing to toggle.
 */
void __jnative_fn_sun_awt_DebugSettings_setCTracingOn__Z_V(
        void* this_settings, int32_t enabled)
{
    (void)this_settings;
    (void)enabled;
}

/*
 * private synchronized native void setCTracingOn(boolean enabled,
 *                                                String file);
 *
 * Per-file C-tracing toggle. Invoked by loadNativeSettings() for each
 * awtdebug.ctrace.<file> property the caller has set. The file argument
 * is the source file whose C functions should be traced (or untraced);
 * the reference implementation matches it against the __FILE__ of each
 * native at trace time.
 *
 * This runtime has no C-side trace table to populate.
 */
void __jnative_fn_sun_awt_DebugSettings_setCTracingOn__ZLjava_lang_String__V(
        void* this_settings, int32_t enabled, void* file)
{
    (void)this_settings;
    (void)enabled;
    (void)file;
}

/*
 * private synchronized native void setCTracingOn(boolean enabled,
 *                                                String file,
 *                                                int line);
 *
 * Per-line C-tracing toggle. Invoked by loadNativeSettings() for each
 * awtdebug.ctrace.<file>@<line> property the caller has set. The
 * file/line pair identifies a specific source line within a C
 * translation unit; the reference implementation records it so that the
 * trace macro fires only when __FILE__ and __LINE__ both match.
 *
 * This runtime has no C-side trace table to populate.
 */
void __jnative_fn_sun_awt_DebugSettings_setCTracingOn__ZLjava_lang_String_I_V(
        void* this_settings, int32_t enabled, void* file, int32_t line)
{
    (void)this_settings;
    (void)enabled;
    (void)file;
    (void)line;
}
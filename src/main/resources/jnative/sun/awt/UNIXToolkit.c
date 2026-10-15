#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.awt.UNIXToolkit — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares a single native method:
 *
 *     private native boolean gtkCheckVersionImpl(int major,
 *                                                int minor,
 *                                                int micro);
 *
 * Its public wrapper gtkCheckVersion(int, int, int) is reached from
 * every GTK-backed code path in the AWT (GTKIconFactory,
 * GTKFileChooser, GTKColorChooserPanel, …) and is called
 * unconditionally at class initialization of those subsystems. The
 * reachability walk therefore pulls gtkCheckVersion into the compiled
 * image, the bytecode emits a call site for the native, and without a
 * definition in the linked executable clang rejects
 * sun/awt/UNIXToolkit.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_UNIXToolkit_gtkCheckVersionImpl__III_Z'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, gtkCheckVersionImpl delegates to the GTK library linked
 * into libawt_xawt.so: it reads gtk_get_major_version(),
 * gtk_get_minor_version() and gtk_get_micro_version() and compares them
 * against the triple the caller supplied. The result tells the Java
 * layer whether the running GTK is new enough to back the requested
 * feature, and therefore whether the GTK-based implementation of a
 * chooser / icon factory / file dialog should be used in place of the
 * Motif / X11 fallback.
 *
 * The Java-level wrapper short-circuits this call in a headless
 * environment:
 *
 *     public static boolean gtkCheckVersion(int major, int minor, int micro) {
 *         if (GraphicsEnvironment.isHeadless()) {
 *             return false;
 *         }
 *         return gtkCheckVersionImpl(major, minor, micro);
 *     }
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * GraphicsEnvironment.isHeadless() is true on every reachable path in
 * this runtime: there is no X11 connection, no Wayland compositor, no
 * framebuffer, and no GTK linkage. The Java-level guard therefore
 * prevents gtkCheckVersionImpl from ever being executed at run time,
 * and its only remaining requirement is that the symbol resolve at
 * link time.
 *
 * If the native were ever reached — a caller that bypasses the guard,
 * a future refactor of the wrapper, or a direct invocation from
 * another native — the honest answer is false. This runtime has no GTK
 * to report a version for, so the caller must fall back to whatever
 * non-GTK code path it would take for an older system. Returning true
 * would lie and cause the caller to try to use GTK-backed classes that
 * do not exist in the image.
 *
 * The three version arguments are captured and explicitly discarded.
 * There is no version to compare them against.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * The LLVM emitter lays out the function's parameters as:
 *
 *     param_0  i8*   receiver       (gtkCheckVersionImpl is an instance
 *                                    method in the JDK version being
 *                                    compiled — see the emitted call
 *                                    in the diagnostic above, which
 *                                    passes the receiver as the first
 *                                    argument)
 *     param_1  i32   major
 *     param_2  i32   minor
 *     param_3  i32   micro
 *
 * and the return type is i1 (boolean), which the C ABI represents as
 * int32_t — the same convention used by every other boolean-returning
 * native in this runtime (Thread.isAlive, Net.isIPv6Available0,
 * PortConfig.get*0, …).
 *
 * ============================================================================
 * The mangled name
 * ============================================================================
 *
 * LlvmRuntime.mangleMethod applies
 *     "fn_" + sanitize(class) + "_" + sanitize(method) + "_" + sanitize(desc)
 * where sanitize replaces every character outside [a-zA-Z0-9_] with a
 * single underscore. The emitter prefixes the result with "__jnative_".
 *
 *     class   "sun/awt/UNIXToolkit"  -> "sun_awt_UNIXToolkit"
 *     method  "gtkCheckVersionImpl"  -> "gtkCheckVersionImpl"
 *     desc    "(III)Z"               -> "_III_Z"
 *     full    "__jnative_fn_sun_awt_UNIXToolkit_gtkCheckVersionImpl__III_Z"
 *
 * The double underscore between "Impl" and "III" comes from the method-
 * to-descriptor separator followed by the sanitised '(' character.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/UNIXToolkit") does not strip
 * a leading "java/" — the class is in the sun.* namespace — so it
 * returns "jnative/sun/awt/UNIXToolkit.c". That is the path this file
 * must live at for Compiler.extractSystemNativeSources to find it and
 * compile it into the executable.
 */

/*
 * private native boolean gtkCheckVersionImpl(int major, int minor, int micro);
 *
 * Returns false unconditionally. See the file header for the full
 * rationale: this runtime has no GTK, the Java-level caller never
 * reaches this native because GraphicsEnvironment.isHeadless() is
 * always true, and a truthful "no GTK" answer is the only one that
 * keeps the caller on a code path the image can actually execute.
 */
int32_t __jnative_fn_sun_awt_UNIXToolkit_gtkCheckVersionImpl__III_Z(
        void* this_toolkit, int32_t major, int32_t minor, int32_t micro)
{
    (void)this_toolkit;
    (void)major;
    (void)minor;
    (void)micro;
    return 0;
}
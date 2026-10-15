#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.awt.X11.XDesktopPeer — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares two native methods:
 *
 *     private native boolean gnome_url_show(byte[] url);
 *     private static native boolean init(int gtkVersion, boolean verbose);
 *
 * Both are called from reachable Java-level methods:
 *
 *   init            — from initWithLock(), which runs once, guarded by
 *                     initExecuted, on the first XDesktopPeer
 *                     construction or the first isDesktopSupported()
 *                     query.
 *
 *   gnome_url_show  — from launch(URI), which implements open(), mail()
 *                     and browse().
 *
 * The LLVM emitter records a call site for each in the generated IR,
 * so without definitions in the linked executable clang rejects
 * sun/awt/X11/XDesktopPeer.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_X11_XDesktopPeer_gnome_url_show___B_Z'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * XDesktopPeer is the Gnome-backed implementation of java.awt.DesktopPeer
 * on Linux and Unix. Its native side links against libgnome / GIO and
 * drives the desktop's URL handler:
 *
 *   init            probes the GTK / GNOME stack at the requested
 *                   version, and on success installs the native
 *                   callback machinery the other native uses. Its
 *                   return value is stored in the Java-side
 *                   nativeLibraryLoaded flag, and its side effect is to
 *                   remove unsupported Action values from
 *                   supportedActions (for example, GNOME may not
 *                   support Action.EDIT).
 *
 *   gnome_url_show  invokes gnome_url_show(3) with the NUL-terminated
 *                   URL string. On success the desktop opens the URI
 *                   in the user's preferred application (browser for
 *                   http/https, mail client for mailto, file manager
 *                   for file://, and so on). The boolean result tells
 *                   the Java layer whether the call succeeded; a false
 *                   result is turned into an IOException by launch().
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no display, no desktop session, no X11 or Wayland
 * connection, no GNOME libraries, and no GTK linkage.
 * GraphicsEnvironment.isHeadless() is true on every reachable path, so
 * the java.awt.Desktop API itself is documented to report "no desktop
 * support" and every action method is expected to fail with
 * UnsupportedOperationException or a well-defined IOException.
 *
 * Both natives therefore return false, which is exactly the answer the
 * Java-side code needs:
 *
 *   init           returning false leaves nativeLibraryLoaded == false
 *                  and supportedActions unchanged. isDesktopSupported()
 *                  then returns false (because nativeLibraryLoaded is
 *                  false), and the java.awt.Desktop singleton reports
 *                  "not supported" without ever attempting a launch.
 *                  Removing actions from supportedActions would be
 *                  unnecessary: with the library unloaded no action can
 *                  succeed regardless.
 *
 *   gnome_url_show returning false causes launch(URI) to raise
 *                  IOException("Failed to show URI: ..."). The Java
 *                  layer's own contract is that launch() may fail for
 *                  any reason, and the caller is expected to handle
 *                  IOException — no AWT method depends on this native
 *                  succeeding.
 *
 * Returning true from either would be a lie: the runtime has no desktop
 * to open a URI with, and the caller would proceed to a code path it
 * cannot complete. Returning false is the same answer the reference
 * implementation produces on a system without a desktop session, which
 * is exactly the environment this runtime provides.
 *
 * These are genuine "unavailable" answers, not stubs that abort:
 * aborting would break the many JDK classes that construct a
 * DesktopPeer during AWT initialisation or that ask
 * Desktop.isDesktopSupported() as a feature probe, even though those
 * callers have a well-defined path for the "no desktop" answer.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * init is a STATIC native: no receiver argument. Its C signature takes
 * the gtkVersion int and the verbose boolean flag directly. The
 * boolean is represented as int32_t in this runtime's C ABI, matching
 * every other boolean parameter of every other native
 * (Thread.setDaemon, Net.bind0, Deflater.init, …).
 *
 * gnome_url_show is an INSTANCE native: the receiver is the first
 * argument, followed by the byte[] holding the URL. The byte[] is a
 * runtime Java array whose payload starts at JAVA_ARR_HDR; the caller
 * has already appended a NUL byte ("uri.toString() + '\0'"), so the
 * payload is a valid C string as long as the array is not empty.
 *
 * Both natives return boolean (i1 at the IR level, int32_t at the C
 * ABI level in this runtime's convention).
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
 *     class  "sun/awt/X11/XDesktopPeer" -> "sun_awt_X11_XDesktopPeer"
 *
 *     init            method "init",            desc "(IZ)Z"
 *         -> "fn_sun_awt_X11_XDesktopPeer_init__IZ_Z"
 *         -> "__jnative_fn_sun_awt_X11_XDesktopPeer_init__IZ_Z"
 *
 *     gnome_url_show  method "gnome_url_show",  desc "([B)Z"
 *         -> "fn_sun_awt_X11_XDesktopPeer_gnome_url_show___B_Z"
 *         -> "__jnative_fn_sun_awt_X11_XDesktopPeer_gnome_url_show___B_Z"
 *
 * The triple underscore in gnome_url_show's mangled form is the
 * method-to-descriptor separator plus the sanitised '(' and sanitised
 * '[' characters: "_" + "_" + "_". The linker diagnostic above shows
 * exactly that name.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/X11/XDesktopPeer") does not
 * strip a leading "java/" — the class is in the sun.* namespace — so
 * it returns "jnative/sun/awt/X11/XDesktopPeer.c". That is the path
 * this file must live at for Compiler.extractSystemNativeSources to
 * find it and compile it into the executable.
 */

/*
 * private static native boolean init(int gtkVersion, boolean verbose);
 *
 * Returns false: the runtime has no GTK / GNOME libraries to probe and
 * no desktop session to talk to. See the file header for the full
 * reasoning; the short version is that false causes
 * nativeLibraryLoaded to remain false, which is the truthful state of
 * this runtime and is exactly what every caller of
 * Desktop.isDesktopSupported() expects to see in a headless
 * environment.
 *
 * STATIC native: no receiver argument.
 */
int32_t __jnative_fn_sun_awt_X11_XDesktopPeer_init__IZ_Z(
        int32_t gtk_version, int32_t verbose)
{
    (void)gtk_version;
    (void)verbose;
    return 0;
}

/*
 * private native boolean gnome_url_show(byte[] url);
 *
 * Returns false: the runtime cannot open a URI on a desktop because
 * there is no desktop. See the file header for the full reasoning; the
 * short version is that launch() turns a false return into
 * IOException("Failed to show URI: ...") and every caller of
 * Desktop.open / mail / browse already handles IOException as the
 * documented "the operation could not be completed" outcome.
 *
 * The url argument is a runtime Java byte[] whose payload starts at
 * JAVA_ARR_HDR and, per the Java-side caller, ends with a NUL byte so
 * it is a valid C string. It is captured and discarded: there is
 * nothing to pass it to.
 */
int32_t __jnative_fn_sun_awt_X11_XDesktopPeer_gnome_url_show___B_Z(
        void* this_peer, void* url)
{
    (void)this_peer;
    (void)url;
    return 0;
}
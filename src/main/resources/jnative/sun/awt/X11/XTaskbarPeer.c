#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.awt.X11.XTaskbarPeer — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares six native methods. Every one of them is called
 * from some reachable method of the class, so the LLVM emitter records
 * a call site for each in the generated IR. Without definitions in the
 * linked executable, clang rejects sun/awt/X11/XTaskbarPeer.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_X11_XTaskbarPeer_setBadge__JZ_V'
 *
 * and stops. Providing only the symbol in the diagnostic would move
 * the error to the next one; all six are defined here so the module
 * links in one pass:
 *
 *   init            — from initWithLock(), guarded by initExecuted
 *   runloop         — from the daemon "TaskBar" thread spawned by
 *                     initWithLock() on the success path
 *   setBadge        — from setIconBadge(String)
 *   updateProgress  — from setProgressValue(int)
 *   setUrgent       — from requestUserAttention(boolean, boolean)
 *   setNativeMenu   — from setMenu(PopupMenu)
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * XTaskbarPeer is the Unity-launcher backend for java.awt.Taskbar on
 * Linux. Its native side links against libunity and, on systems running
 * the Unity desktop, drives the launcher icon's badge, progress
 * indicator, urgent flag, and right-click menu:
 *
 *   init            calls unity_launcher_entry_get_for_desktop_id,
 *                   checks that the launcher entry exists for the given
 *                   application name, and on success installs the
 *                   launcher entry as the singleton the other natives
 *                   operate on. Returns false when the Unity launcher
 *                   is not reachable — which is the normal state on
 *                   every desktop other than Unity.
 *
 *   runloop         blocks forever inside a GLib main loop so the
 *                   launcher entry can receive asynchronous events
 *                   (menu item clicks, for example) and dispatch them
 *                   back into Java through menuItemCallback.
 *
 *   setBadge        calls unity_launcher_entry_set_count on the launcher
 *                   entry with the numeric count and a visibility flag.
 *
 *   updateProgress  calls unity_launcher_entry_set_progress with the
 *                   fractional value and a visibility flag.
 *
 *   setUrgent       calls unity_launcher_entry_set_urgent.
 *
 *   setNativeMenu   translates a Java MenuItem[] into the D-Bus
 *                   serialisation Unity expects and passes it to
 *                   unity_launcher_entry_set_dbus_menu_structure.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no Unity launcher, no D-Bus session bus, and no
 * display. The static initializer already queries XDG_CURRENT_DESKTOP
 * and sets the Java-side isUnity flag to true only when the string is
 * exactly "Unity". On any other desktop — and on a headless system,
 * where XDG_CURRENT_DESKTOP is typically unset — isUnity is false and
 * isTaskbarSupported() returns false before reaching the native layer
 * at all.
 *
 * Even on a hypothetical Unity desktop, the runtime has no libunity to
 * call into. The six natives therefore provide the answers the Java
 * layer already knows how to handle when Unity is not available:
 *
 *   init            returns false. initWithLock() stores that value in
 *                   nativeLibraryLoaded; because the value is false, it
 *                   does not spawn the "TaskBar" daemon thread, does
 *                   not call runloop, and leaves every subsequent
 *                   operation on this peer in the "no native backend"
 *                   state that isTaskbarSupported() and the individual
 *                   setters correctly decline.
 *
 *   runloop         returns immediately instead of blocking forever.
 *                   This method is only reached on the success path of
 *                   init, which never fires here, but if a future
 *                   change did reach it, blocking forever on a runtime
 *                   with no GLib loop to run would leak a daemon thread
 *                   for no purpose. An immediate return is the correct
 *                   behaviour when there is nothing to wait for.
 *
 *   setBadge        no-op. No launcher entry to set a count on.
 *
 *   updateProgress  no-op. No launcher entry to set progress on.
 *
 *   setUrgent       no-op. No launcher entry to mark urgent.
 *
 *   setNativeMenu   no-op. No launcher entry to attach a menu to.
 *
 * These are genuine "taskbar backend unavailable" answers, not stubs
 * that abort. java.awt.Taskbar's public API is documented to make
 * every action method a no-op when the platform does not support it,
 * and the Java-side peer methods here already have well-defined
 * no-native-backend handling: they simply do not observe any visible
 * effect, which is exactly what a Taskbar on a desktop without a
 * launcher integration does. Aborting would break every caller that
 * constructs a Taskbar object or checks Taskbar.isSupported(...), which
 * is defined to work regardless of platform support.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * init and runloop are STATIC natives: no receiver argument. All four
 * remaining natives are INSTANCE natives with the receiver first.
 *
 * The Java-level method signatures are:
 *
 *   private static native boolean init(String name, int version,
 *                                      boolean verbose);
 *   private static native void    runloop();
 *   private native        void    setBadge(long value, boolean visible);
 *   private native        void    updateProgress(double value,
 *                                                boolean visible);
 *   private native        void    setUrgent(boolean urgent);
 *   private native        void    setNativeMenu(MenuItem[] items);
 *
 * The boolean parameters and the boolean return of init are
 * represented as int32_t at the C ABI level, matching every other
 * boolean native in this runtime (Thread.setDaemon, Net.bind0,
 * Deflater.init, …). The double parameter is a native double. The long
 * parameter is a native int64_t.
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
 *   class "sun/awt/X11/XTaskbarPeer" -> "sun_awt_X11_XTaskbarPeer"
 *
 *   init            desc "(Ljava/lang/String;IZ)Z"
 *                   sanitized -> "_Ljava_lang_String_IZ_Z"
 *                   full: "__jnative_fn_sun_awt_X11_XTaskbarPeer_init__Ljava_lang_String_IZ_Z"
 *
 *   runloop         desc "()V"
 *                   sanitized -> "__V"
 *                   full: "__jnative_fn_sun_awt_X11_XTaskbarPeer_runloop___V"
 *
 *   setBadge        desc "(JZ)V"
 *                   sanitized -> "_JZ_V"
 *                   full: "__jnative_fn_sun_awt_X11_XTaskbarPeer_setBadge__JZ_V"
 *
 *   updateProgress  desc "(DZ)V"
 *                   sanitized -> "_DZ_V"
 *                   full: "__jnative_fn_sun_awt_X11_XTaskbarPeer_updateProgress__DZ_V"
 *
 *   setUrgent       desc "(Z)V"
 *                   sanitized -> "_Z_V"
 *                   full: "__jnative_fn_sun_awt_X11_XTaskbarPeer_setUrgent__Z_V"
 *
 *   setNativeMenu   desc "([Ljava/awt/MenuItem;)V"
 *                   sanitized -> "__Ljava_awt_MenuItem__V"
 *                   full: "__jnative_fn_sun_awt_X11_XTaskbarPeer_setNativeMenu___Ljava_awt_MenuItem__V"
 *
 * The setBadge name is the one the linker reported as undefined; its
 * shape is the pattern every other name in this file follows.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/X11/XTaskbarPeer") does not
 * strip a leading "java/" — the class is in the sun.* namespace — so
 * it returns "jnative/sun/awt/X11/XTaskbarPeer.c". That is the path
 * this file must live at for Compiler.extractSystemNativeSources to
 * find it and compile it into the executable.
 */

/*
 * private static native boolean init(String name,
 *                                    int version,
 *                                    boolean verbose);
 *
 * Returns false: no Unity launcher is reachable and no libunity is
 * linked. The Java-side initWithLock stores that result in
 * nativeLibraryLoaded, which is then the sole gate on every other
 * operation of the peer.
 *
 * STATIC native: no receiver argument.
 */
int32_t __jnative_fn_sun_awt_X11_XTaskbarPeer_init__Ljava_lang_String_IZ_Z(
        void* name, int32_t version, int32_t verbose)
{
    (void)name;
    (void)version;
    (void)verbose;
    return 0;
}

/*
 * private static native void runloop();
 *
 * Returns immediately. See the file header: this method would only be
 * reached from the success path of init, which never fires here, and
 * blocking forever on a runtime with no GLib main loop to run would
 * leak a daemon thread. An immediate return is correct when there is
 * nothing to wait for.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_X11_XTaskbarPeer_runloop___V(void) {
}

/*
 * private native void setBadge(long value, boolean visible);
 *
 * No-op. Invoked from setIconBadge(String) whenever the caller supplies
 * a badge string that parses as a long, and with (0, false) when the
 * caller clears the badge. The launcher entry this would have addressed
 * does not exist.
 */
void __jnative_fn_sun_awt_X11_XTaskbarPeer_setBadge__JZ_V(
        void* this_peer, int64_t value, int32_t visible)
{
    (void)this_peer;
    (void)value;
    (void)visible;
}

/*
 * private native void updateProgress(double value, boolean visible);
 *
 * No-op. Invoked from setProgressValue(int) with the caller's value
 * normalised to the [0.0, 1.0] range and a visibility flag that is
 * false when the caller asked for "no progress" (a value outside
 * [0, 100]). The launcher entry this would have addressed does not
 * exist.
 */
void __jnative_fn_sun_awt_X11_XTaskbarPeer_updateProgress__DZ_V(
        void* this_peer, double value, int32_t visible)
{
    (void)this_peer;
    (void)value;
    (void)visible;
}

/*
 * private native void setUrgent(boolean urgent);
 *
 * No-op. Invoked from requestUserAttention(boolean, boolean) with the
 * caller's `enabled` flag. The `critical` flag is not forwarded — the
 * Java-side method discards it — because the reference native only
 * distinguishes urgent from not-urgent. The launcher entry this would
 * have addressed does not exist.
 */
void __jnative_fn_sun_awt_X11_XTaskbarPeer_setUrgent__Z_V(
        void* this_peer, int32_t urgent)
{
    (void)this_peer;
    (void)urgent;
}

/*
 * private native void setNativeMenu(MenuItem[] items);
 *
 * No-op. Invoked from setMenu(PopupMenu) with either the array of
 * MenuItem objects that populate the menu or null when the menu is
 * being cleared. On HotSpot the array would be marshalled to a D-Bus
 * menu structure and attached to the launcher entry, and item clicks
 * would be dispatched back through menuItemCallback. With no launcher
 * entry and no D-Bus session bus, there is nothing to attach and
 * nothing that could call back.
 *
 * The items argument is a runtime Java reference array; its payload
 * starts at JAVA_ARR_HDR. It is captured and discarded.
 */
void __jnative_fn_sun_awt_X11_XTaskbarPeer_setNativeMenu___Ljava_awt_MenuItem__V(
        void* this_peer, void* items)
{
    (void)this_peer;
    (void)items;
}
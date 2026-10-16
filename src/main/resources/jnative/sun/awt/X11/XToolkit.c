#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "jnative_runtime.h"

/*
 * sun.awt.X11.XToolkit — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares ten native methods. Every one of them is called
 * from some reachable method of the class or of one of the peer classes
 * it constructs, so the LLVM emitter records a call site for each in the
 * generated IR. Without definitions in the linked executable, clang
 * rejects sun/awt/X11/XToolkit.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_X11_XToolkit_getNumberOfButtonsImpl___I'
 *
 * and stops. Providing only the symbol in the diagnostic would move the
 * error to the next one; all ten are defined here so the module links
 * in one pass:
 *
 *   getTrayIconDisplayTimeout  — tray icon display timeout query
 *   initIDs                    — XToolkit.<clinit> hook
 *   waitForEvents              — toolkit event-loop wait
 *   awt_output_flush           — stdio flush after awt unlock
 *   nativeLoadSystemColors     — system colour table initialisation
 *   getEnv                     — environment variable read
 *   awt_toolkit_init           — toolkit thread initialisation
 *   getDefaultXColormap        — default X11 colormap accessor
 *   getNumberOfButtonsImpl     — mouse button count query
 *   wakeup_poll                — poll-wakeup from schedule()
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, XToolkit is the X11 backend for the AWT. Its native side
 * links against libX11, libXext, Xrandr, Xrender and XKB, and drives
 * the toolkit thread's event loop on top of a poll(2) over the X11
 * connection's file descriptor. The ten natives below cover the small
 * amount of per-native infrastructure that loop needs:
 *
 *   getTrayIconDisplayTimeout queries the System Tray host for its
 *                             balloon-message display timeout.
 *
 *   initIDs                   caches JNI field and method IDs used by
 *                             the other natives.
 *
 *   waitForEvents             is where the toolkit thread spends most
 *                             of its time: it releases awt_lock, polls
 *                             the X11 fd until either an X event is
 *                             queued or a scheduled timeout task is
 *                             due, then reacquires awt_lock.
 *
 *   awt_output_flush          flushes stdout after an awt unlock so
 *                             that println() from any thread reaches
 *                             the terminal in a timely fashion.
 *
 *   nativeLoadSystemColors    fills the AWT system colour table from
 *                             the X server's default colour map.
 *
 *   getEnv                    reads an environment variable, with X11's
 *                             usual PATH-fallback semantics.
 *
 *   awt_toolkit_init          runs one-time native initialisation on
 *                             the toolkit thread.
 *
 *   getDefaultXColormap       returns the X server's default colormap.
 *
 *   getNumberOfButtonsImpl    reads XPointer / XExtendedPointer and
 *                             returns the number of physical mouse
 *                             buttons the X server reports.
 *
 *   wakeup_poll               wakes the toolkit thread out of its
 *                             poll(2) so a newly scheduled timeout
 *                             task is noticed immediately.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no display, and no X server to
 * talk to. GraphicsEnvironment.isHeadless() is true on every reachable
 * path, so:
 *
 *   - XToolkit's <clinit> takes the headless branch and never calls
 *     initIDs, setupModifierMap or setBackingStoreType.
 *
 *   - The public XToolkit() constructor takes the headless branch and
 *     never calls init(), XWM.init(), or spawns the toolkit thread.
 *
 *   - Every peer-creating method either returns a peer whose natives
 *     are no-ops in this file (see the siblings under
 *     jnative/sun/awt/X11/) or falls back to the superclass default.
 *
 * Every native is nevertheless reachable from the bytecode and must
 * resolve at link time. The implementations below provide the answers
 * that keep a headless runtime internally consistent:
 *
 *   getTrayIconDisplayTimeout  returns 0, the "no tray host" timeout.
 *
 *   initIDs                    no-op (no JNI field IDs cached in this
 *                              runtime).
 *
 *   waitForEvents              returns immediately. The toolkit thread
 *                              is never started in a headless runtime,
 *                              so no caller actually enters the loop;
 *                              if a future caller did, blocking here on
 *                              an X11 fd that does not exist would
 *                              stall that thread forever. An immediate
 *                              return is the only correct answer when
 *                              there is no event source to wait on.
 *
 *   awt_output_flush           flushes stdout and stderr so any
 *                              println() issued before an awt unlock
 *                              reaches the terminal. This is safe on
 *                              every thread and matches the reference
 *                              implementation's intent.
 *
 *   nativeLoadSystemColors     no-op. The int[] the Java side passes in
 *                              is zero-initialised on entry; leaving it
 *                              at zeros is the correct representation
 *                              of a display with no system colour
 *                              table. MotifColorUtilities.loadSystem
 *                              Colors then runs over the zeroed array
 *                              and produces the fallback palette.
 *
 *   getEnv                     delegates to the C library's getenv(3).
 *                              The reference native resolves through
 *                              the same function under the hood.
 *
 *   awt_toolkit_init           no-op. One-time native initialisation
 *                              has nothing to initialise in a runtime
 *                              with no X11 layer.
 *
 *   getDefaultXColormap        returns 0, the null colormap id.
 *
 *   getNumberOfButtonsImpl     returns 3. The runtime has no X server
 *                              to query for the physical button count,
 *                              so the answer defaults to the standard
 *                              three-button mouse that every desktop
 *                              since the late 1990s has shipped.
 *                              Returning 0 would make the Java-side
 *                              getNumberOfButtons() clamp to 0, which
 *                              then breaks getNumberOfButtonsForMask()
 *                              and the desktop-property initialisation
 *                              that depends on a positive button count.
 *
 *   wakeup_poll                no-op. There is no poll(2) to wake.
 *
 * These are genuine "no X11" answers, not stubs that abort. Aborting
 * would break every AWT initialisation that constructs a Toolkit or
 * queries a Toolkit property, including the many JDK classes that
 * probe Toolkit.getDefaultToolkit() before deciding whether any AWT
 * operation is possible. Returning the same defaults that HotSpot
 * returns for a screen with no X server keeps those callers on paths
 * they already handle.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * Eight of the ten natives are STATIC: no receiver argument.
 * nativeLoadSystemColors and getNumberOfButtonsImpl are INSTANCE
 * natives: the receiver is the first argument.
 *
 * The boolean parameters and returns elsewhere in this codebase are
 * represented as int32_t at the C ABI level; the natives here have no
 * boolean parameters or returns, so that convention does not apply.
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
 *   class "sun/awt/X11/XToolkit" -> "sun_awt_X11_XToolkit"
 *
 *   getTrayIconDisplayTimeout   desc "()J"
 *       -> "__jnative_fn_sun_awt_X11_XToolkit_getTrayIconDisplayTimeout___J"
 *   initIDs                     desc "()V"
 *       -> "__jnative_fn_sun_awt_X11_XToolkit_initIDs___V"
 *   waitForEvents               desc "(J)V"
 *       -> "__jnative_fn_sun_awt_X11_XToolkit_waitForEvents__J_V"
 *   awt_output_flush            desc "()V"
 *       -> "__jnative_fn_sun_awt_X11_XToolkit_awt_output_flush___V"
 *   nativeLoadSystemColors      desc "([I)V"
 *       -> "__jnative_fn_sun_awt_X11_XToolkit_nativeLoadSystemColors___I_V"
 *   getEnv                      desc "(Ljava/lang/String;)Ljava/lang/String;"
 *       -> "__jnative_fn_sun_awt_X11_XToolkit_getEnv__Ljava_lang_String__Ljava_lang_String_"
 *   awt_toolkit_init            desc "()V"
 *       -> "__jnative_fn_sun_awt_X11_XToolkit_awt_toolkit_init___V"
 *   getDefaultXColormap         desc "()J"
 *       -> "__jnative_fn_sun_awt_X11_XToolkit_getDefaultXColormap___J"
 *   getNumberOfButtonsImpl      desc "()I"
 *       -> "__jnative_fn_sun_awt_X11_XToolkit_getNumberOfButtonsImpl___I"
 *   wakeup_poll                 desc "()V"
 *       -> "__jnative_fn_sun_awt_X11_XToolkit_wakeup_poll___V"
 *
 * The getNumberOfButtonsImpl name is the one the linker reported as
 * undefined; the others are emitted as well and would produce the same
 * diagnostic on the next rebuild if left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/X11/XToolkit") does not strip
 * a leading "java/" — the class is in the sun.* namespace — so it
 * returns "jnative/sun/awt/X11/XToolkit.c". That is the path this file
 * must live at for Compiler.extractSystemNativeSources to find it and
 * compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * static native long getTrayIconDisplayTimeout();
 *
 * Returns 0. On HotSpot this reads the System Tray host's preferred
 * balloon-message display timeout through the XEmbed protocol; with no
 * XEmbed host and no system tray at all, 0 is the honest answer and is
 * what the Java-side XTrayIconPeer treats as "use the platform default
 * from the desktop properties".
 *
 * STATIC native: no receiver argument.
 */
int64_t __jnative_fn_sun_awt_X11_XToolkit_getTrayIconDisplayTimeout___J(void) {
    return 0;
}

/*
 * private static native void initIDs();
 *
 * Called from XToolkit.<clinit> on the non-headless branch. This
 * runtime has no X11 layer to initialise, so the call is not reached;
 * the symbol must exist because the bytecode contains the call site.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_X11_XToolkit_initIDs___V(void) {
}

/*
 * static native void waitForEvents(long nextTaskTime);
 *
 * Returns immediately. On HotSpot this is where the toolkit thread
 * spends most of its time, releasing awt_lock and polling the X11 fd
 * until an event arrives or a scheduled task is due. In a headless
 * runtime the toolkit thread is never started, so no caller actually
 * enters this function; if a future caller did, blocking forever on an
 * X11 fd that does not exist would stall that thread for the lifetime
 * of the process. An immediate return is the only correct answer when
 * there is nothing to wait for.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_X11_XToolkit_waitForEvents__J_V(int64_t next_task_time) {
    (void)next_task_time;
}

/*
 * static native void awt_output_flush();
 *
 * Flushes stdout and stderr. Called by awtFUnlock() immediately after
 * awtUnlock(), so that any println() issued on another thread reaches
 * the terminal without waiting for the next buffer-full event. This
 * matches the reference implementation's intent and is safe to call
 * from any thread.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_X11_XToolkit_awt_output_flush___V(void) {
    fflush(stdout);
    fflush(stderr);
}

/*
 * static native String getEnv(String key);
 *
 * Reads an environment variable. The reference implementation calls the
 * C library's getenv(3), so this implementation does the same. A NULL
 * key or an unset variable both return null, matching the Java-side
 * caller's expectations (there is a null check at the call site before
 * any use).
 *
 * STATIC native: no receiver argument.
 */
void* __jnative_fn_sun_awt_X11_XToolkit_getEnv__Ljava_lang_String__Ljava_lang_String_(
        void* key)
{
    if (key == NULL) {
        return NULL;
    }
    int32_t len = 0;
    const char* key_str = __jnative_read_string_bytes(key, &len);
    if (key_str == NULL || len <= 0) {
        return NULL;
    }
    /*
     * __jnative_read_string_bytes returns a pointer into the Java
     * string's payload without a NUL terminator in general. The key is
     * a short ASCII name in every call site, but getenv(3) requires a
     * proper C string. Copy into a small stack buffer to guarantee the
     * terminator; an environment variable name longer than the buffer
     * cannot match any real variable, so truncation is safe and no
     * caller can be affected.
     */
    char buf[256];
    if (len >= (int32_t)sizeof(buf)) {
        return NULL;
    }
    memcpy(buf, key_str, (size_t)len);
    buf[len] = '\0';

    const char* value = getenv(buf);
    if (value == NULL) {
        return NULL;
    }
    return jnative_string(value);
}

/*
 * static native void awt_toolkit_init();
 *
 * Called once on the toolkit thread from XToolkit.run() before entering
 * the event loop. In this runtime the toolkit thread is never started,
 * so this method is not reached; the symbol must exist because the
 * bytecode contains the call site.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_X11_XToolkit_awt_toolkit_init___V(void) {
}

/*
 * static native long getDefaultXColormap();
 *
 * Returns 0, the null colormap id. On HotSpot this returns the X
 * server's default colormap, which a handful of peer constructors use
 * as the initial colormap of a new window. With no X server there is
 * no colormap, and 0 is what every caller treats as "no colormap
 * allocated yet".
 *
 * STATIC native: no receiver argument.
 */
int64_t __jnative_fn_sun_awt_X11_XToolkit_getDefaultXColormap___J(void) {
    return 0;
}

/*
 * static native void wakeup_poll();
 *
 * No-op. Called by XToolkit.schedule() when a newly-added timeout task
 * becomes the earliest one in the queue, so the toolkit thread notices
 * it without waiting for the previous earliest task's deadline. In this
 * runtime the toolkit thread is never running its poll loop, so there
 * is nothing to wake.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_X11_XToolkit_wakeup_poll___V(void) {
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * private native void nativeLoadSystemColors(int[] systemColors);
 *
 * No-op. On HotSpot this fills the AWT system colour table with the
 * values the X server reports for the default colour map. With no X
 * server there is no colour table to read; the array the Java side
 * passed in is zero-initialised, so leaving it at zeros represents a
 * display whose entire palette is black. The Java-side caller then
 * runs MotifColorUtilities.loadSystemColors on top, which replaces
 * the zeros with the toolkit's built-in fallback palette for any slot
 * it knows how to compute without a server.
 *
 * The argument is a runtime Java int array whose payload starts at
 * JAVA_ARR_HDR. It is captured and discarded.
 */
void __jnative_fn_sun_awt_X11_XToolkit_nativeLoadSystemColors___I_V(
        void* this_toolkit, void* system_colors)
{
    (void)this_toolkit;
    (void)system_colors;
}

/*
 * private native int getNumberOfButtonsImpl();
 *
 * Returns 3. On HotSpot this queries XPointer / XExtendedPointer
 * through the X server and reports the number of physical mouse
 * buttons the server has configured. With no X server there is no
 * physical device to query, so the answer defaults to the standard
 * three-button mouse layout that every desktop this runtime targets
 * has used for decades.
 *
 * Returning 0 would be wrong: the Java-side getNumberOfButtons()
 * caches the native's value and uses it to clamp the number of buttons
 * exposed through Toolkit.getDesktopProperty("awt.mouse.numButtons").
 * A zero value would then propagate into getNumberOfButtonsForMask(),
 * which returns min(MAX_BUTTONS, 0) == 0, and downstream code that
 * builds mouse-event modifier masks by iterating button indices would
 * silently produce no modifiers. Returning 3 keeps the whole chain
 * well-defined and consistent with what the AWT expects on every
 * desktop it supports.
 */
int32_t __jnative_fn_sun_awt_X11_XToolkit_getNumberOfButtonsImpl___I(
        void* this_toolkit)
{
    (void)this_toolkit;
    return 3;
}
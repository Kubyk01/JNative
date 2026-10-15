#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.awt.X11.GtkFileDialogPeer — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares five native methods. Every one of them is called
 * from some reachable method of the class or of its peers, so the LLVM
 * emitter records a call site for each in the generated IR. Without
 * definitions in the linked executable, clang rejects
 * sun/awt/X11/GtkFileDialogPeer.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_X11_GtkFileDialogPeer_quit___V'
 *
 * and stops. Providing only the symbol named in the first diagnostic
 * would simply move the error to the next one; the whole set is defined
 * here so the module links in one pass:
 *
 *     initIDs      — static block, class initialisation
 *     run          — from showNativeDialog(), the dialog's main loop
 *     quit         — from setVisible(false) and dispose()
 *     toFront      — override of DialogPeer.toFront
 *     setBounds    — override of ComponentPeer.setBounds
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, GtkFileDialogPeer is the GTK-backed implementation of the
 * java.awt.FileDialog peer. Its native side links against libgtk and
 * libawt_xawt and drives a native GTK file-chooser dialog:
 *
 *   initIDs       caches the JNI field IDs and method IDs of
 *                 GtkFileDialogPeer and java.awt.FileDialog so the
 *                 native callbacks (setFileInternal,
 *                 filenameFilterCallback) can be invoked from C.
 *
 *   run           creates a gtk_file_chooser_dialog_new, applies the
 *                 initial folder and filename, installs the filter and
 *                 the multiple-selection mode, sets the position, runs
 *                 the GTK main loop on the calling thread until the
 *                 user accepts or cancels, then calls back into Java
 *                 with setFileInternal.
 *
 *   quit          posts an event that breaks the GTK main loop started
 *                 by run(), so setVisible(false) and dispose() can
 *                 close the dialog from another thread.
 *
 *   toFront       raises the dialog to the top of the window stack.
 *
 *   setBounds     repositions and resizes the dialog window.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no GTK linkage, no X11 connection, and no display.
 * GraphicsEnvironment.isHeadless() is true on every reachable path, so
 * FileDialog instances and their peers are constructed but never
 * actually shown to a user: there is no window manager to raise a
 * window with, no display to draw pixels on, and no GTK to run a main
 * loop in.
 *
 * Every native here is therefore a strict no-op or a defaulted return:
 *
 *   initIDs    — nothing to cache (no JNI field IDs are consulted by
 *                this runtime; every field is accessed through the
 *                offset computed by LlvmGlobalEmitter.getFieldOffset).
 *
 *   run        — returns immediately. On HotSpot this would block
 *                until the user closes the dialog; here it must not
 *                block, because there is no user. The Java-side
 *                caller in setVisible() runs this on a dedicated
 *                "ShowDialog" thread and, on return, calls
 *                fd.setVisible(false). With the immediate return the
 *                dialog is created and torn down in a single instant,
 *                which is exactly the correct behaviour for a headless
 *                runtime: the FileDialog exists as a Java object, its
 *                API is well-formed, and showing it produces no
 *                observable effect.
 *
 *   quit       — no-op. Because run() never enters a main loop, there
 *                is no loop to break out of. setVisible(false) and
 *                dispose() both call this from arbitrary threads, and
 *                a no-op is safe from any of them.
 *
 *   toFront    — no-op. There is no window to raise.
 *
 *   setBounds  — no-op. There is no window to move or resize.
 *
 * These are genuine no-ops, not stubs that abort. Making them abort
 * would break every program that constructs a FileDialog or asks a
 * GtkFileDialogPeer about its state — including the many JDK classes
 * that walk the ComponentPeer hierarchy during construction and
 * disposal — even though those programs cannot observe any of the
 * effects a real GTK dialog would have produced.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * Every native here is an instance method, so the receiver is the first
 * argument. The Java-level synchronization (XToolkit.awtLock /
 * awtUnlock in setVisible, dispose) is handled at the bytecode level by
 * __jnative_monitor_enter / _exit; nothing extra is required inside
 * these natives.
 *
 * The return type of toFront, quit, run, setBounds and initIDs is void
 * in every case, so no packing convention applies.
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
 *     class  "sun/awt/X11/GtkFileDialogPeer" -> "sun_awt_X11_GtkFileDialogPeer"
 *     method "initIDs"                       -> "initIDs"
 *     method "run"                           -> "run"
 *     method "quit"                          -> "quit"
 *     method "toFront"                       -> "toFront"
 *     method "setBounds"                     -> "setBounds"
 *
 *     initIDs       desc "()V"                                        -> "___V"
 *     run           desc "(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/io/FilenameFilter;ZII)V"
 *                                                                    -> "_Ljava_lang_String_ILjava_lang_String_Ljava_lang_String_Ljava_io_FilenameFilter_ZII_V"
 *     quit          desc "()V"                                        -> "___V"
 *     toFront       desc "()V"                                        -> "___V"
 *     setBounds     desc "(IIIII)V"                                   -> "__IIIII_V"
 *
 * The triple underscore in "quit___V" is the method-to-descriptor
 * separator, the sanitised '(' and the sanitised ')' — exactly as the
 * linker diagnostic above shows.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/X11/GtkFileDialogPeer") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/awt/X11/GtkFileDialogPeer.c". That is the
 * path this file must live at for Compiler.extractSystemNativeSources
 * to find it and compile it into the executable.
 */

/*
 * private static native void initIDs();
 *
 * Called from GtkFileDialogPeer.<clinit>. On HotSpot this hook caches
 * the JNI field IDs and method IDs the native callbacks need in order
 * to invoke setFileInternal and filenameFilterCallback from C. This
 * runtime accesses every field through its LLVM-computed byte offset
 * and every method through the mangled symbols emitted by the LLVM
 * backend, so there is nothing to cache.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_awt_X11_GtkFileDialogPeer_initIDs___V(void) {
}

/*
 * private native void run(String title, int mode, String dir,
 *                         String file, FilenameFilter filter,
 *                         boolean isMultipleMode, int x, int y);
 *
 * Called from the ShowDialog thread spawned by setVisible(true). On
 * HotSpot this blocks until the user closes the GTK dialog and then
 * invokes setFileInternal with the selected paths. Here it returns
 * immediately: there is no user, no dialog, and no main loop to run.
 *
 * See the file header for why the immediate return is the correct
 * behaviour and not a shortcut. All eight arguments (plus the receiver)
 * are captured and discarded.
 */
void __jnative_fn_sun_awt_X11_GtkFileDialogPeer_run__Ljava_lang_String_ILjava_lang_String_Ljava_lang_String_Ljava_io_FilenameFilter_ZII_V(
        void* this_peer,
        void* title,
        int32_t mode,
        void* dir,
        void* file,
        void* filter,
        int32_t is_multiple_mode,
        int32_t x,
        int32_t y)
{
    (void)this_peer;
    (void)title;
    (void)mode;
    (void)dir;
    (void)file;
    (void)filter;
    (void)is_multiple_mode;
    (void)x;
    (void)y;
}

/*
 * private native void quit();
 *
 * Called from setVisible(false) and dispose() to break the GTK main
 * loop started by run(). Because run() never enters a main loop, there
 * is no loop to break out of, and the call is a no-op. Invoking it is
 * safe from any thread: it neither blocks nor touches shared state.
 */
void __jnative_fn_sun_awt_X11_GtkFileDialogPeer_quit___V(void* this_peer) {
    (void)this_peer;
}

/*
 * @Override
 * public native void toFront();
 *
 * Inherited contract from java.awt.peer.DialogPeer: raise the peer's
 * window above all other windows of the same application. There is no
 * window in a headless runtime, so the call is a no-op.
 *
 * The override is declared native in this class specifically because
 * the GTK implementation does not go through the ordinary X11
 * toFront path — it raises the GTK dialog's own window directly. In
 * this runtime both the ordinary path and the GTK path are absent, so
 * either way the effect is nothing.
 */
void __jnative_fn_sun_awt_X11_GtkFileDialogPeer_toFront___V(void* this_peer) {
    (void)this_peer;
}

/*
 * @Override
 * public native void setBounds(int x, int y, int width, int height,
 *                              int op);
 *
 * Inherited contract from java.awt.peer.ComponentPeer: reposition and
 * resize the peer's window. The `op` argument is the bitmask of
 * ComponentPeer.SET_LOCATION / SET_SIZE / SET_BOUNDS / SET_CLIENT_SIZE
 * that identifies which of the geometry fields actually changed.
 *
 * This override is declared native because the GTK file dialog is not
 * a normal X11 child window: it is a top-level GTK dialog whose
 * geometry is managed by the GTK window manager rather than by the
 * ordinary XDialogPeer.setBounds. On a headless runtime there is
 * neither a GTK window manager nor an X11 one to talk to, so the call
 * is a no-op.
 */
void __jnative_fn_sun_awt_X11_GtkFileDialogPeer_setBounds__IIIII_V(
        void* this_peer,
        int32_t x,
        int32_t y,
        int32_t width,
        int32_t height,
        int32_t op)
{
    (void)this_peer;
    (void)x;
    (void)y;
    (void)width;
    (void)height;
    (void)op;
}
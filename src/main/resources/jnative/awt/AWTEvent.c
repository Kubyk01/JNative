#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * java.awt.AWTEvent — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares two native methods. Both are called from bytecode
 * that the reachability walk pulls into the compiled image:
 *
 *   initIDs          — from <clinit>, unconditionally, on every path
 *                      that touches AWTEvent (the class is reachable
 *                      whenever any AWT event subclass is reachable,
 *                      which the JDK's own deserialization and event-
 *                      dispatch support classes pull in);
 *
 *   nativeSetSource  — from setSource(Object), which is reachable
 *                      whenever the retargeting API is reachable.
 *
 * The LLVM emitter records a call site for each in the generated IR.
 * Without a definition in the linked executable, clang rejects
 * java/awt/AWTEvent.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_java_awt_AWTEvent_nativeSetSource__Ljava_awt_peer_ComponentPeer__V'
 *
 * and the build stops. This file provides the definitions.
 *
 * ============================================================================
 * Behaviour in a headless runtime
 * ============================================================================
 *
 * This runtime has no AWT peer hierarchy: no X11 connection, no Wayland
 * compositor, no framebuffer, no font renderer. GraphicsEnvironment.
 * isHeadless() is true on every reachable path, and therefore no
 * Component ever acquires a peer. The two natives have nothing to do:
 *
 *   initIDs
 *       On HotSpot, this hook caches the JNI field IDs of AWTEvent's
 *       serial-persistent fields (id, bdata, consumed, isSystemGenerated,
 *       focusManagerIsDispatching, isPosted, acc) so the native event
 *       dispatch code can read them without repeated lookups. This
 *       runtime accesses every field through the byte offset computed
 *       by LlvmGlobalEmitter.getFieldOffset and never consults JNI field
 *       IDs, so there is nothing to cache.
 *
 *   nativeSetSource
 *       On HotSpot, this hook writes the new source's native peer into
 *       the event's persistent JNI-side state, so that when the event
 *       is later re-dispatched into the peer's native handler the peer
 *       already knows which component the event belongs to. With no
 *       peers in this runtime, there is no state to update: the Java-
 *       level setSource() has already replaced the event's source field
 *       before invoking this native, and every subsequent Java-level
 *       read of the source goes through that field.
 *
 * Both are therefore strict no-ops.
 *
 * ============================================================================
 * Why the signatures below match the mangled names exactly
 * ============================================================================
 *
 * LlvmRuntime.mangleMethod applies
 *     "fn_" + sanitize(class) + "_" + sanitize(method) + "_" + sanitize(desc)
 * where sanitize replaces every character outside [a-zA-Z0-9_] with a
 * single underscore. The emitter prefixes the result with "__jnative_"
 * when it emits the declaration.
 *
 *   initIDs         static, ()V
 *       class   "java/awt/AWTEvent"           -> "java_awt_AWTEvent"
 *       method  "initIDs"                     -> "initIDs"
 *       desc    "()V"                         -> "__V"
 *       full    "__jnative_fn_java_awt_AWTEvent_initIDs___V"
 *
 *   nativeSetSource instance, (Ljava/awt/peer/ComponentPeer;)V
 *       class   "java/awt/AWTEvent"           -> "java_awt_AWTEvent"
 *       method  "nativeSetSource"             -> "nativeSetSource"
 *       desc    "(Ljava/awt/peer/ComponentPeer;)V"
 *                                             -> "_Ljava_awt_peer_ComponentPeer__V"
 *       full    "__jnative_fn_java_awt_AWTEvent_nativeSetSource__Ljava_awt_peer_ComponentPeer__V"
 *
 * The second is the symbol the build reported as undefined; the first
 * is emitted as well and would produce the same diagnostic on the next
 * compile of AWTEvent.ll if it were left out. Both are defined here so
 * that the module links in one pass.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("java/awt/AWTEvent") strips the leading
 * "java/" and prepends "jnative/", producing "jnative/awt/AWTEvent.c".
 * That is the path this file must live at for Compiler.extractSystem
 * NativeSources to find it and compile it into the executable. The same
 * rule that produces "jnative/lang/Object.c" for java.lang.Object and
 * "jnative/io/Console.c" for java.io.Console.
 */

/*
 * private static native void initIDs();
 *
 * Called from AWTEvent.<clinit> after Toolkit.loadLibraries(). The
 * loadLibraries() call itself is a no-op in this runtime (see the
 * corresponding native in java.awt.Toolkit), and the isHeadless() guard
 * that surrounds this call is true on every reachable path, so on most
 * builds this native is never executed. It is still emitted into the
 * IR because the class-file bytecode contains the call site, and the
 * linker therefore requires the symbol.
 *
 * No arguments are captured: the Java method is static and takes none.
 */
void __jnative_fn_java_awt_AWTEvent_initIDs___V(void) {
}

/*
 * private native void nativeSetSource(ComponentPeer peer);
 *
 * Called from AWTEvent.setSource(Object) immediately after the event's
 * `source` field has been updated and before the synchronized block
 * exits. The peer argument is the native peer of the nearest
 * heavyweight ancestor of the new source, as computed by the loop in
 * setSource itself:
 *
 *     Component comp = null;
 *     if (newSource instanceof Component) {
 *         comp = (Component) newSource;
 *         while (comp != null && (comp.peer instanceof LightweightPeer)) {
 *             comp = comp.parent;
 *         }
 *     }
 *     ...
 *     ComponentPeer peer = comp.peer;
 *     if (peer != null) {
 *         nativeSetSource(peer);
 *     }
 *
 * The `peer != null` guard upstream means this function is only invoked
 * with a real ComponentPeer. In this runtime there are no ComponentPeers
 * to produce, so the guard is always false and the native is never
 * called at run time. It is emitted into the IR regardless, because the
 * call site exists in the bytecode, and the linker requires the symbol.
 *
 * The receiver argument (%param_0) is the AWTEvent instance. The second
 * argument is the ComponentPeer. Neither is consulted: this runtime has
 * no native event-dispatch state attached to an AWTEvent, so there is
 * nothing to update.
 */
void __jnative_fn_java_awt_AWTEvent_nativeSetSource__Ljava_awt_peer_ComponentPeer__V(
        void* this_event, void* peer)
{
    (void)this_event;
    (void)peer;
}
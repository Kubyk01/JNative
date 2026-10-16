#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.opengl.OGLRenderQueue — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares one native method:
 *
 *     private native void flushBuffer(long buf, int limit);
 *
 * It is called from the private flushBuffer() wrapper, which is itself
 * invoked from the QueueFlusher daemon thread's run loop on every flush
 * cycle (either triggered by an explicit flushNow() call or by the
 * periodic 100 ms timeout). The LLVM emitter records a call site for
 * the native in the generated IR, so without a definition in the
 * linked executable clang rejects sun/java2d/opengl/OGLRenderQueue.ll
 * with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_opengl_OGLRenderQueue_flushBuffer__JI_V'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, OGLRenderQueue is the command buffer between the AWT's
 * Java-level drawing operations and the native OpenGL library. Every
 * Graphics2D operation that ends up on the OpenGL pipeline appends a
 * fixed-layout command record into the shared RenderBuffer, and the
 * native side of flushBuffer runs once per queue-drain cycle on the
 * dedicated QueueFlusher thread, executing each record against the
 * current OpenGL context:
 *
 *   - each record begins with a 4-byte opcode from
 *     sun.java2d.pipe.BufferedOpCodes (DRAW_GLYPH_LIST, DRAW_LINE,
 *     DISPOSE_CONFIG, SYNC, …);
 *
 *   - flushBuffer walks the buffer from offset 0 to `limit`, dispatching
 *     each opcode to its corresponding C handler in
 *     src/java.desktop/share/native/libawt/opengl/OGLBufFlip.c,
 *     OGLTextRenderer.c, OGLRenderer.c and their siblings;
 *
 *   - after all records have been processed, the OpenGL command stream
 *     is flushed to the driver.
 *
 * The two arguments are:
 *
 *   buf    — the native address of the first byte of the RenderBuffer's
 *            backing storage (the buffer's `address` field, obtained
 *            from the Java-side `buf.getAddress()` call);
 *
 *   limit  — the number of valid bytes in the buffer, obtained from
 *            `buf.position()` just before the call. flushBuffer is not
 *            required to inspect the Java-side position field itself;
 *            everything it needs is in the two arguments.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no OpenGL library, no GLX extension, no X display,
 * and no GPU. GraphicsEnvironment.isHeadless() is true on every
 * reachable path, and X11GraphicsEnvironment.isGLXAvailable() returns
 * false, so:
 *
 *   - No OGLContext is ever constructed; OGLContext's static
 *     initializer would fail before this class is used.
 *
 *   - No OGLSurfaceData is ever created; GLXGraphicsConfig.getConfig
 *     returns null before reaching its constructor.
 *
 *   - No Java2D operation ever appends a record to the OGLRenderQueue's
 *     buffer. The buffer's position stays at 0 for the lifetime of the
 *     process.
 *
 * flushBuffer is nevertheless emitted into the module because the call
 * site exists in the QueueFlusher thread's run method, and the thread
 * itself can in principle be started (see below). The implementation
 * is a strict no-op:
 *
 *   - There are no command records to execute: the buffer position is
 *     always 0, and the caller's own guard `if (limit > 0)` prevents
 *     the native from being invoked at all when the buffer is empty.
 *
 *   - Even if a future revision reached this native with a non-zero
 *     limit, there would still be nothing to execute — the opcodes are
 *     handled by C functions in libawt_opengl that this runtime does
 *     not link.
 *
 * Note on the QueueFlusher thread: the OGLRenderQueue constructor
 * spawns a daemon thread unconditionally. But the constructor is only
 * reached through OGLRenderQueue.getInstance(), which is only called
 * from code paths that are themselves guarded on GLX availability, so
 * the thread is not actually started in a headless runtime. The
 * call site still needs a definition because the bytecode emits the
 * call regardless of whether it is reached at run time.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * flushBuffer is an INSTANCE native: the receiver is the first
 * argument, followed by the two Java-level parameters. The Java
 * declaration is
 *
 *     private native void flushBuffer(long buf, int limit);
 *
 * so the C signature takes the OGLRenderQueue receiver, then an
 * int64_t (the buffer address), then an int32_t (the limit). Both
 * numeric parameters are deliberately unused: with no buffer contents
 * there is nothing to flush.
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
 *   class  "sun/java2d/opengl/OGLRenderQueue"
 *       -> "sun_java2d_opengl_OGLRenderQueue"
 *   method "flushBuffer"
 *       -> "flushBuffer"
 *   desc   "(JI)V"
 *       -> "_JI_V"
 *
 * giving the symbol the linker reported as undefined:
 *
 *     __jnative_fn_sun_java2d_opengl_OGLRenderQueue_flushBuffer__JI_V
 *
 * The double underscore after the method name is the method-to-
 * descriptor separator followed by the sanitised `(`.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/opengl/OGLRenderQueue")
 * does not strip a leading "java/" — the class is in the sun.*
 * namespace — so it returns
 * "jnative/sun/java2d/opengl/OGLRenderQueue.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/*
 * private native void flushBuffer(long buf, int limit);
 *
 * No-op. See the file header for the full rationale: the runtime has
 * no OpenGL library and no GLX context, so the command records that
 * this native would execute are never written into the buffer in the
 * first place. The caller's own guard (`if (limit > 0)`) skips the
 * native entirely when the buffer is empty, which it always is.
 *
 * The two arguments are captured and discarded:
 *
 *   buf    — the address of the buffer's backing storage; would be
 *            passed to per-opcode C handlers in libawt_opengl if any
 *            records existed.
 *
 *   limit  — the number of valid bytes in the buffer; used by the
 *            native loop as its iteration bound.
 */
void __jnative_fn_sun_java2d_opengl_OGLRenderQueue_flushBuffer__JI_V(
        void* this_queue, int64_t buf, int32_t limit)
{
    (void)this_queue;
    (void)buf;
    (void)limit;
}
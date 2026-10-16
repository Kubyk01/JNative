#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.pipe.BufferedRenderPipe — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares one native method:
 *
 *     private native int fillSpans(RenderQueue rq, long buf,
 *                                  int pos, int limit,
 *                                  SpanIterator si, long iterator,
 *                                  int transx, int transy);
 *
 * It is called from the protected fillSpans(SunGraphics2D, SpanIterator,
 * int, int) wrapper, which runs on every shape fill that goes through
 * the buffered-pipe path — i.e. every draw(Shape) and fill(Shape) that
 * ends up on a hardware-accelerated renderer (X11, XRender, OpenGL,
 * D3D). The LLVM emitter records a call site for the native in the
 * generated IR, so without a definition in the linked executable clang
 * rejects sun/java2d/pipe/BufferedRenderPipe.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_pipe_BufferedRenderPipe_fillSpans__Lsun_java2d_pipe_RenderQueue_JIILsun_java2d_pipe_SpanIterator_JII_I'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, BufferedRenderPipe is the abstract base class of every
 * Java2D renderer that batches drawing operations into a shared
 * RenderQueue before flushing them to the native library. Its native
 * side links against the AWT's own software and hardware pipelines and
 * drives the span-producing side of the shape rasteriser.
 *
 * fillSpans is the native that walks a SpanIterator and writes the
 * resulting horizontal span runs into the RenderQueue's buffer. Each
 * span record is 16 bytes: {x1, x2, y, padding}. The function:
 *
 *   - walks the SpanIterator's native function block (the `iterator`
 *     argument is the address returned by si.getNativeIterator());
 *
 *   - for each call to the block's nextSpan(iterator, spanbox) entry
 *     point that returns true, the spanbox is filled with the four
 *     coordinates of one horizontal run;
 *
 *   - the native writes the run's 16-byte record into the buffer
 *     starting at offset `pos`, translating by (transx, transy) as it
 *     goes;
 *
 *   - the loop stops when nextSpan returns false or the buffer's
 *     `limit` is reached;
 *
 *   - the return value is the new buffer position (the initial `pos`
 *     plus the number of bytes written), which the Java caller assigns
 *     back to `buf.position(newpos)`.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no XRender extension, no OpenGL
 * library, no GPU, and no hardware-accelerated SurfaceData. The
 * BufferedRenderPipe class is only reachable in the compiled image
 * because the X11 / XRender / OpenGL / D3D pipelines reference it in
 * their class hierarchies, and those pipelines are themselves
 * reachable because SunGraphics2D's validatePipe() consults the
 * primitive tables on every draw.
 *
 * But every accelerated pipeline selection in this runtime is short-
 * circuited before a BufferedRenderPipe subclass can be instantiated:
 *
 *   - X11GraphicsEnvironment.isGLXAvailable() returns false, so the
 *     OpenGL pipeline is never selected.
 *
 *   - X11GraphicsEnvironment.isXRenderAvailable() returns false, so
 *     the XRender pipeline is never selected.
 *
 *   - The X11 pipeline's own isAccelerationEnabled() returns false,
 *     so the X11 accelerated primitives are never installed.
 *
 * Consequently the accelerated drawing pipes are never built, and the
 * fillSpans native is never invoked from a reachable path. It is
 * nevertheless emitted into the module because the Java bytecode of
 * fillSpans(SunGraphics2D, SpanIterator, int, int) unconditionally
 * calls the native.
 *
 * The implementation returns `pos` unchanged. That is the correct
 * no-op answer for a runtime in which:
 *
 *   - `iterator` is 0 (the runtime's ShapeSpanIterator returns 0 from
 *     getNativeIterator() — see jnative/sun/java2d/pipe/
 *     ShapeSpanIterator.c — because there is no native span-producing
 *     function block to point at). Calling through a null function
 *     pointer would crash the process; the guard below prevents that.
 *
 *   - The RenderQueue's buffer would receive no span records even if
 *     an iterator existed, because the runtime has no native renderer
 *     that could consume them.
 *
 *   - Returning `pos` (the initial position) is exactly what the
 *     reference implementation returns when the SpanIterator produces
 *     zero spans; the caller's `buf.position(newpos)` call then leaves
 *     the buffer position unchanged, and no downstream consumer sees
 *     a difference between "no spans produced" and "no spans produced
 *     because the runtime has no rasteriser".
 *
 * Aborting would break every program that draws a Shape through a
 * Graphics2D, because SunGraphics2D.validatePipe() may consult the
 * primitive tables on the first draw even when the selected pipeline
 * is software — the primitive registration code walks the accelerated
 * pipelines' class hierarchies during class initialisation and would
 * trip the abort before the software path could take over.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * fillSpans is an INSTANCE native: the receiver is the first argument,
 * followed by the eight Java-level parameters. The Java declaration is
 *
 *     private native int fillSpans(RenderQueue rq, long buf,
 *                                  int pos, int limit,
 *                                  SpanIterator si, long iterator,
 *                                  int transx, int transy);
 *
 * so the C signature takes the BufferedRenderPipe receiver, then a
 * RenderQueue reference, an int64_t (buffer address), two int32_t
 * buffer positions, a SpanIterator reference, an int64_t (the native
 * iterator address), and two int32_t translation offsets. The return
 * value is an int32_t (the new buffer position).
 *
 * The diagnostic above shows the shape:
 *
 *     i8* %param_0,        // receiver
 *     i8* %tmp_...,        // RenderQueue
 *     i64 %aux_slot_load_.., // buf address
 *     i32 %aux_slot_load_.., // pos
 *     i32 %aux_slot_load_.., // limit
 *     i8* %param_2,        // SpanIterator
 *     i64 %aux_slot_load_.., // iterator address
 *     i32 %param_3,        // transx
 *     i32 %param_4         // transy
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
 *   class  "sun/java2d/pipe/BufferedRenderPipe"
 *       -> "sun_java2d_pipe_BufferedRenderPipe"
 *   method "fillSpans"
 *       -> "fillSpans"
 *   desc   "(Lsun/java2d/pipe/RenderQueue;JIILsun/java2d/pipe/SpanIterator;JII)I"
 *       -> "_Lsun_java2d_pipe_RenderQueue_JIILsun_java2d_pipe_SpanIterator_JII_I"
 *
 * giving the symbol the linker reported as undefined:
 *
 *     __jnative_fn_sun_java2d_pipe_BufferedRenderPipe_fillSpans__Lsun_java2d_pipe_RenderQueue_JIILsun_java2d_pipe_SpanIterator_JII_I
 *
 * The double underscore after the method name is the method-to-
 * descriptor separator followed by the sanitised `(`. Each reference
 * parameter's `/` and `;` characters sanitize to `_`, giving the two
 * `L…_` tokens separated by single underscores. The two `J` tokens are
 * the long parameters (buffer address and native iterator address),
 * the four `I` tokens are the two int buffer positions and the two int
 * translation offsets, and the trailing `_I` is the sanitised `)` plus
 * the int return type.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/pipe/BufferedRenderPipe")
 * does not strip a leading "java/" — the class is in the sun.*
 * namespace — so it returns
 * "jnative/sun/java2d/pipe/BufferedRenderPipe.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/*
 * private native int fillSpans(RenderQueue rq, long buf,
 *                              int pos, int limit,
 *                              SpanIterator si, long iterator,
 *                              int transx, int transy);
 *
 * Returns `pos` unchanged. See the file header for the full rationale:
 * the runtime has no accelerated pipeline that could produce spans, the
 * ShapeSpanIterator's native iterator address is always 0, and calling
 * through a null function pointer would crash the process. Returning
 * the initial buffer position is exactly what the reference
 * implementation returns when no spans are produced; the caller's
 * `buf.position(newpos)` then leaves the position unchanged.
 *
 * Every argument is captured and discarded.
 */
int32_t __jnative_fn_sun_java2d_pipe_BufferedRenderPipe_fillSpans__Lsun_java2d_pipe_RenderQueue_JIILsun_java2d_pipe_SpanIterator_JII_I(
        void* this_pipe,
        void* rq,
        int64_t buf,
        int32_t pos,
        int32_t limit,
        void* si,
        int64_t iterator,
        int32_t transx,
        int32_t transy)
{
    (void)this_pipe;
    (void)rq;
    (void)buf;
    (void)limit;
    (void)si;
    (void)iterator;
    (void)transx;
    (void)transy;

    /*
     * The buffer position is returned unchanged: no span records are
     * written, because there is no native iterator to walk and no
     * renderer downstream to consume the records. The reference
     * implementation returns the same value when the iterator produces
     * zero spans, so callers observe identical behaviour.
     */
    return pos;
}
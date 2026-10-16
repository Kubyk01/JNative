#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.pipe.BufferedMaskBlit — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares one native method:
 *
 *     private native int enqueueTile(long buf, int bpos,
 *                                    SurfaceData srcData,
 *                                    long pSrcOps, int srcType,
 *                                    byte[] mask, int masklen,
 *                                    int maskoff, int maskscan,
 *                                    int srcx, int srcy,
 *                                    int dstx, int dsty,
 *                                    int width, int height);
 *
 * It is called from MaskBlit(SurfaceData, SurfaceData, Composite,
 * Region, int, int, int, int, int, int, byte[], int, int), the
 * primitive that any buffered hardware-accelerated pipeline (OpenGL,
 * Direct3D) invokes when it needs to blend a source surface through a
 * coverage mask into a destination surface. The LLVM emitter records a
 * call site for the native in the generated IR, so without a definition
 * in the linked executable clang rejects
 * sun/java2d/pipe/BufferedMaskBlit.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_pipe_BufferedMaskBlit_enqueueTile__JILsun_java2d_SurfaceData_JI_BIIIIIIIII_I'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, BufferedMaskBlit is the base class of the OpenGL and D3D
 * MaskBlit primitives. Its native side links against libawt_opengl /
 * libawt_d3d and drives the pipeline-specific tile serializer:
 *
 *   enqueueTile
 *       serialises one MaskBlit operation into the given RenderQueue's
 *       buffer. The format is a fixed 20-byte header followed by the
 *       mask's pixel data:
 *
 *           [0..4]   opcode: MASK_BLIT / MASK_BLIT_AA (from
 *                    BufferedOpCodes)
 *           [4..8]   srcOps address (the source surface's native
 *                    pipeline ops struct)
 *           [8..12]  srcType (the ST_* code identifying IntArgb,
 *                    IntArgbPre, IntRgb, IntBgr)
 *           [12..16] srcx
 *           [16..20] srcy
 *           [20..24] dstx
 *           [24..28] dsty
 *           [28..32] width
 *           [32..36] height
 *           [36..]   the mask byte[] payload (width * height bytes)
 *
 *       (The exact byte layout is defined in OGLMaskBlit.c and
 *       D3DMaskBlit.c, and is consumed by the corresponding
 *       flushBuffer implementation of the pipeline's RenderQueue.)
 *
 *   The return value is the new buffer position (the initial `bpos`
 *   plus the number of bytes written), which the Java caller assigns
 *   back to `buf.position(newpos)`.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no OpenGL library, no Direct3D, no GPU, and no
 * hardware-accelerated SurfaceData. The BufferedMaskBlit class is only
 * reachable in the compiled image because the OpenGL and D3D pipelines
 * reference it in their class hierarchies, and those pipelines are
 * themselves reachable because SunGraphics2D's validatePipe() consults
 * the primitive tables on every draw. But every accelerated pipeline
 * selection in this runtime is short-circuited before a
 * BufferedMaskBlit subclass can be instantiated:
 *
 *   - X11GraphicsEnvironment.isGLXAvailable() returns false, so the
 *     OpenGL pipeline is never selected.
 *
 *   - Direct3D is a Windows-only pipeline and is not linked into a
 *     Linux/macOS build.
 *
 * Consequently the accelerated MaskBlit primitive is never built, and
 * enqueueTile is never invoked from a reachable path. It is
 * nevertheless emitted into the module because the bytecode of
 * MaskBlit(...) unconditionally calls it on the path where a non-null
 * mask is present.
 *
 * The implementation returns `bpos` unchanged. That is the correct
 * no-op answer for a runtime in which:
 *
 *   - no tile serializer exists that could consume the buffer record;
 *
 *   - the downstream flushBuffer of any accelerated pipeline is itself
 *     a no-op (see jnative/sun/java2d/opengl/OGLRenderQueue.c), so
 *     writing the record would only delay the queue's eventual drain
 *     by the length of a record that nothing will read;
 *
 *   - returning `bpos` (the initial position) is exactly what the
 *     reference implementation returns when `width <= 0` or
 *     `height <= 0`; the caller's `buf.position(newpos)` then leaves
 *     the buffer position unchanged and no downstream consumer sees a
 *     difference.
 *
 * Aborting would break every program that constructs a Graphics2D and
 * enables antialiasing — a purely in-memory operation that does not
 * need a GPU — because SunGraphics2D's pipeline-selection code walks
 * the accelerated primitive tables during class initialisation and
 * would trip the abort before the software path could take over.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * enqueueTile is an INSTANCE native: the receiver is the first
 * argument, followed by the fifteen Java-level parameters. The Java
 * declaration is
 *
 *     private native int enqueueTile(long buf, int bpos,
 *                                    SurfaceData srcData,
 *                                    long pSrcOps, int srcType,
 *                                    byte[] mask, int masklen,
 *                                    int maskoff, int maskscan,
 *                                    int srcx, int srcy,
 *                                    int dstx, int dsty,
 *                                    int width, int height);
 *
 * so the C signature takes the BufferedMaskBlit receiver, then a long,
 * an int, a SurfaceData reference, a long, an int, a byte[] reference,
 * and nine ints. The return value is an int32_t (the new buffer
 * position).
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
 *   class  "sun/java2d/pipe/BufferedMaskBlit"
 *       -> "sun_java2d_pipe_BufferedMaskBlit"
 *   method "enqueueTile"
 *       -> "enqueueTile"
 *   desc   "(JILsun/java2d/SurfaceData;JI[BIIIIIIIII)I"
 *       -> "_JILsun_java2d_SurfaceData_JI_BIIIIIIIII_I"
 *
 * giving the symbol the linker reported as undefined:
 *
 *     __jnative_fn_sun_java2d_pipe_BufferedMaskBlit_enqueueTile__JILsun_java2d_SurfaceData_JI_BIIIIIIIII_I
 *
 * The double underscore after the method name is the method-to-
 * descriptor separator followed by the sanitised `(`. Then `J` for the
 * long buffer address, `I` for the int position, `Lsun_java2d_SurfaceData_`
 * for the SurfaceData reference (whose `/` and `;` sanitize to `_`),
 * `J` and `I` for the long ops address and the int source-type code,
 * `_B` for the byte[] mask (the `[` sanitizes to `_`, then `B`), and
 * the nine `I`s for the remaining int parameters. The trailing `_I` is
 * the sanitised `)` plus the int return type.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/pipe/BufferedMaskBlit")
 * does not strip a leading "java/" — the class is in the sun.*
 * namespace — so it returns
 * "jnative/sun/java2d/pipe/BufferedMaskBlit.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/*
 * private native int enqueueTile(long buf, int bpos,
 *                                SurfaceData srcData,
 *                                long pSrcOps, int srcType,
 *                                byte[] mask, int masklen,
 *                                int maskoff, int maskscan,
 *                                int srcx, int srcy,
 *                                int dstx, int dsty,
 *                                int width, int height);
 *
 * Returns `bpos` unchanged. See the file header for the full
 * rationale: the runtime has no accelerated MaskBlit pipeline, the
 * downstream flushBuffer of any accelerated RenderQueue is itself a
 * no-op, and returning the initial buffer position is exactly what the
 * reference implementation returns when the operation is a no-op. The
 * caller's `buf.position(newpos)` then leaves the position unchanged.
 *
 * Every argument is captured and discarded.
 */
int32_t __jnative_fn_sun_java2d_pipe_BufferedMaskBlit_enqueueTile__JILsun_java2d_SurfaceData_JI_BIIIIIIIII_I(
        void* this_blit,
        int64_t buf,
        int32_t bpos,
        void* src_data,
        int64_t p_src_ops,
        int32_t src_type,
        void* mask,
        int32_t masklen,
        int32_t maskoff,
        int32_t maskscan,
        int32_t srcx,
        int32_t srcy,
        int32_t dstx,
        int32_t dsty,
        int32_t width,
        int32_t height)
{
    (void)this_blit;
    (void)buf;
    (void)src_data;
    (void)p_src_ops;
    (void)src_type;
    (void)mask;
    (void)masklen;
    (void)maskoff;
    (void)maskscan;
    (void)srcx;
    (void)srcy;
    (void)dstx;
    (void)dsty;
    (void)width;
    (void)height;

    /*
     * The buffer position is returned unchanged: no record is written
     * into the queue, because there is no native tile serializer to
     * produce one and no downstream flushBuffer that would consume it.
     * The reference implementation returns the same value when the
     * operation is a no-op, so callers observe identical behaviour.
     */
    return bpos;
}
#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.TransformHelper — native entry point.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * TransformHelper is one of the java2d GraphicsPrimitive subclasses. It
 * represents the "transform-and-blit" operation: take a rectangular
 * region of pixels from a source surface, apply an AffineTransform to
 * them, composite the result according to the graphics state's
 * Composite, and write the result into a destination surface, all
 * clipped against the supplied Region.
 *
 * The class declares a single native:
 *
 *     public native void Transform(MaskBlit output,
 *                                  SurfaceData src, SurfaceData dst,
 *                                  Composite comp, Region clip,
 *                                  AffineTransform itx, int txtype,
 *                                  int sx1, int sy1, int sx2, int sy2,
 *                                  int dx1, int dy1, int dx2, int dy2,
 *                                  int[] edges, int dxoff, int dyoff);
 *
 * which the graphics pipeline reaches through the java2d DrawImage
 * machinery. That is why the diagnostic names
 * sun/java2d/pipe/DrawImage.ll rather than TransformHelper.ll: the
 * call site lives in DrawImage's bytecode, but the symbol being
 * resolved is TransformHelper's own native, and the class file whose
 * native needs a definition is TransformHelper itself.
 *
 * The class is reachable in the compiled image because
 * GraphicsPrimitiveMgr.locate walks the entire primitive table during
 * SunGraphics2D.validatePipe() on the first drawing operation, and
 * that walk touches every registered primitive's class. The call site
 * is emitted into the module regardless of which concrete primitive
 * subclass is instantiated at run time.
 *
 * Without a definition in the linked executable, clang rejects
 * whichever translation unit emits the call — DrawImage.ll in the
 * current build — with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_loops_TransformHelper_Transform__Lsun_java2d_loops_MaskBlit_Lsun_java2d_SurfaceData_Lsun_java2d_SurfaceData_Ljava_awt_Composite_Lsun_java2d_pipe_Region_Ljava_awt_geom_AffineTransform_IIIIIIIII_III_V'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot with an accelerated pipeline selected, Transform runs the
 * native transform-and-blit loop. The arguments describe one rectangle
 * of pixels in each coordinate space plus the mapping between them:
 *
 *   output     the MaskBlit primitive used to composite pixels into
 *              the destination; Transform does not write into dst
 *              directly, it hands each transformed pixel run to the
 *              output MaskBlit
 *
 *   src, dst   the source and destination surfaces
 *
 *   comp       the Composite that governs how source pixels are
 *              blended with destination pixels
 *
 *   clip       the Region against which every output pixel is clipped
 *
 *   itx        the inverse of the user-space-to-device-space transform
 *              in effect for the DrawImage operation, applied by the
 *              native loop to map destination coordinates back to
 *              source coordinates
 *
 *   txtype     the transform-type code (TX_TRANSLATE, TX_SCALE, …)
 *              that selects the fast path inside the native
 *
 *   sx1..sy2    the source rectangle (inclusive-exclusive half-open
 *              form: the source pixels selected are [sx1, sx2) by
 *              [sy1, sy2))
 *
 *   dx1..dy2    the destination rectangle
 *
 *   edges       a flattened representation of the transformed source
 *              rectangle's edges, used by the native loop to walk the
 *              destination rectangle row by row and compute the
 *              corresponding source span
 *
 *   dxoff,dyoff the destination rectangle's offset within the
 *              flattened edges array
 *
 * The native loop reads a span of source pixels, transforms each
 * span's endpoints through itx, and hands the resulting destination
 * span to output.MaskBlit, which performs the actual pixel write.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no XRender extension, no OpenGL
 * library, no GPU, and no accelerated SurfaceData. GraphicsEnvironment
 * .isHeadless() is true on every reachable path, so:
 *
 *   - No X11GraphicsConfig, XRSurfaceData or GLXSurfaceData is ever
 *     constructed for a real destination surface.
 *
 *   - The software pipeline operates directly on BufferedImage-backed
 *     rasters through the JDK's own Java-level transform-and-blit
 *     loops (TransformHelper's sibling classes in the same package),
 *     not through this native.
 *
 *   - The only path that could reach Transform is the accelerated
 *     pipeline-selection machinery in GraphicsPrimitiveMgr, and every
 *     accelerated pipeline in this runtime is short-circuited by
 *     isAccelerationEnabled / isGLXAvailable / isXRenderAvailable all
 *     returning false (see the sibling files under jnative/sun/awt/
 *     and jnative/sun/java2d/).
 *
 * The native is nevertheless emitted into the module because the
 * bytecode of DrawImage and of the GraphicsPrimitiveMgr registration
 * tables contains the call site. The implementation is a strict
 * no-op:
 *
 *   - There is no accelerated destination surface to write to.
 *
 *   - There is no transform pipeline that could map source pixels
 *     through itx.
 *
 *   - The `output` MaskBlit primitive is not invoked, because there is
 *     no destination surface it could composite against.
 *
 *   - No observable effect — neither in the program's output nor in
 *     any subsequent read of the destination surface — would be
 *     produced by transforming pixels in a runtime that cannot
 *     display them, and no reachable caller would observe the
 *     difference.
 *
 * This is a genuine "no accelerated pipeline" answer, not a stub that
 * aborts. Aborting would break every program that constructs a
 * Graphics2D and draws an image with a non-identity transform — a
 * purely in-memory operation that does not need a display — because
 * SunGraphics2D's pipeline-selection code walks the accelerated
 * primitive tables during initialisation and would trip the abort
 * before the software path could take over.
 *
 * The remaining natives of the sibling GraphicsPrimitive subclasses
 * (DrawGlyphListLCD, BufferedRenderPipe.fillSpans, BufferedMaskBlit
 * .enqueueTile, FillSpans.FillSpans) all follow the same pattern:
 * reachable from the pipeline tables, never invoked from a live path,
 * implemented as a no-op or a defaulted return. This file continues
 * that pattern.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * Transform is an INSTANCE native: the receiver is the first argument,
 * followed by the nineteen Java-level parameters. The C signature
 * takes, in order:
 *
 *     this_helper     FillSpans/TransformHelper receiver
 *     output          MaskBlit reference
 *     src             SurfaceData reference (source)
 *     dst             SurfaceData reference (destination)
 *     comp            Composite reference
 *     clip            Region reference
 *     itx             AffineTransform reference (inverse transform)
 *     txtype          int32_t transform-type code
 *     sx1..sy2        four int32_t source-rectangle coordinates
 *     dx1..dy2        four int32_t destination-rectangle coordinates
 *     edges           int[] reference (flattened edge array)
 *     dxoff           int32_t destination x offset within `edges`
 *     dyoff           int32_t destination y offset within `edges`
 *
 * The method returns void.
 *
 * Every argument is captured and discarded: there is no destination
 * surface to write to, no transform to apply, and no MaskBlit whose
 * primitive could be invoked.
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
 *   class  "sun/java2d/loops/TransformHelper"
 *       -> "sun_java2d_loops_TransformHelper"
 *   method "Transform"
 *       -> "Transform"
 *   desc   "(Lsun/java2d/loops/MaskBlit;"
 *            "Lsun/java2d/SurfaceData;Lsun/java2d/SurfaceData;"
 *            "Ljava/awt/Composite;Lsun/java2d/pipe/Region;"
 *            "Ljava/awt/geom/AffineTransform;"
 *            "IIIIIIIII[III)V"
 *       -> "_Lsun_java2d_loops_MaskBlit_"
 *          "Lsun_java2d_SurfaceData_Lsun_java2d_SurfaceData_"
 *          "Ljava_awt_Composite_Lsun_java2d_pipe_Region_"
 *          "Ljava_awt_geom_AffineTransform_"
 *          "IIIIIIIII_III_V"
 *
 * giving the symbol the linker reported as undefined:
 *
 *     __jnative_fn_sun_java2d_loops_TransformHelper_Transform__Lsun_java2d_loops_MaskBlit_Lsun_java2d_SurfaceData_Lsun_java2d_SurfaceData_Ljava_awt_Composite_Lsun_java2d_pipe_Region_Ljava_awt_geom_AffineTransform_IIIIIIIII_III_V
 *
 * The double underscore after the method name is the method-to-
 * descriptor separator followed by the sanitised `(`. Each reference
 * parameter's `/` and `;` characters sanitize to `_`, giving the
 * `L…_` tokens. The nine `I`s are the sanitised `txtype` plus the
 * eight int-rectangle coordinates; the `_III` after them is the
 * sanitised `[` of the int-array descriptor followed by the array's
 * element type `I`, then the two int offsets `dxoff` and `dyoff`; the
 * trailing `_V` is the sanitised `)` plus the void return type.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/loops/TransformHelper")
 * does not strip a leading "java/" — the class is in the sun.*
 * namespace — so it returns
 * "jnative/sun/java2d/loops/TransformHelper.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find
 * it and compile it into the executable.
 */

/*
 * public native void Transform(MaskBlit output,
 *                              SurfaceData src, SurfaceData dst,
 *                              Composite comp, Region clip,
 *                              AffineTransform itx, int txtype,
 *                              int sx1, int sy1, int sx2, int sy2,
 *                              int dx1, int dy1, int dx2, int dy2,
 *                              int[] edges, int dxoff, int dyoff);
 *
 * No-op. See the file header for the full rationale: the runtime has
 * no accelerated java2d pipeline that could invoke this primitive, and
 * every caller in this runtime has a well-defined software fallback
 * path that operates on the destination raster's Java-side data
 * instead. The nineteen arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_loops_TransformHelper_Transform__Lsun_java2d_loops_MaskBlit_Lsun_java2d_SurfaceData_Lsun_java2d_SurfaceData_Ljava_awt_Composite_Lsun_java2d_pipe_Region_Ljava_awt_geom_AffineTransform_IIIIIIIII_III_V(
        void* this_helper,
        void* output,
        void* src,
        void* dst,
        void* comp,
        void* clip,
        void* itx,
        int32_t txtype,
        int32_t sx1, int32_t sy1, int32_t sx2, int32_t sy2,
        int32_t dx1, int32_t dy1, int32_t dx2, int32_t dy2,
        void* edges,
        int32_t dxoff, int32_t dyoff)
{
    (void)this_helper;
    (void)output;
    (void)src;
    (void)dst;
    (void)comp;
    (void)clip;
    (void)itx;
    (void)txtype;
    (void)sx1; (void)sy1; (void)sx2; (void)sy2;
    (void)dx1; (void)dy1; (void)dx2; (void)dy2;
    (void)edges;
    (void)dxoff; (void)dyoff;
}
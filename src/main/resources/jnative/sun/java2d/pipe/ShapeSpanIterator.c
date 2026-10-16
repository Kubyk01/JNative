#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.pipe.ShapeSpanIterator — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares nineteen native methods. Every one of them is
 * called from some reachable method of the class or of its callers
 * (XRRenderer, XRSurfaceData, the X11 java2d pipeline), so the LLVM
 * emitter records a call site for each in the generated IR. Without
 * definitions in the linked executable, clang rejects
 * sun/java2d/xr/XRRenderer.ll (and its siblings) with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_pipe_ShapeSpanIterator_dispose___V'
 *
 * and stops. Providing only the symbol in the diagnostic would move the
 * error to the next one; all nineteen are defined here so the module
 * links in one pass.
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, ShapeSpanIterator is the AWT's scan-conversion bridge:
 * it flattens an incoming java.awt.Shape path, invokes the native
 * polygon tracer in libawt to produce horizontal span runs, and hands
 * those spans to the active renderer (X11, XRender, D3D, OpenGL, or
 * the software rasteriser).
 *
 * The native side (sun/java2d/pipe/ShapeSpanIterator.c and its helpers
 * in libawt) maintains a C-side data structure (the "SpanIterator"
 * record) whose fields track:
 *
 *   - the accumulated path elements,
 *   - the winding rule,
 *   - the output-area rectangle,
 *   - the normalisation flag,
 *   - the current scanline's span list.
 *
 * Each native in this class either stores one of those fields, reads
 * one, or advances the scan-conversion state machine.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no XRender surface, no X11
 * java2d pipeline, and no libawt scan-conversion library. The
 * ShapeSpanIterator class is only reachable in the compiled image
 * because the XRRenderer and X11 renderer classes that consume it are
 * reachable, and those classes are themselves reachable because the
 * AWT's image pipeline references them from the SurfaceData hierarchy.
 * None of them ever produces a live shape scan at run time in a
 * headless runtime.
 *
 * All nineteen natives are therefore strict no-ops or defaulted
 * returns:
 *
 *   initIDs                no-op (no JNI field IDs cached).
 *
 *   appendPoly             no-op. Records nothing; the class has no
 *                          live native state to append to.
 *
 *   setNormalize           no-op.
 *
 *   setOutputAreaXYXY      no-op.
 *
 *   setRule                no-op.
 *
 *   addSegment             no-op.
 *
 *   getPathBox             no-op. The caller's int[] stays at whatever
 *                          values it had on entry (typically zeros).
 *
 *   intersectClipBox       no-op.
 *
 *   nextSpan               returns false. There are no spans to return;
 *                          every iteration loop terminates immediately.
 *
 *   skipDownTo             no-op.
 *
 *   getNativeIterator      returns 0. There is no native iterator
 *                          struct; the Java-side caller's null check
 *                          treats 0 as "no native fast path" and
 *                          falls back to the Java pathDone loop.
 *
 *   dispose                no-op. There is no native memory to release.
 *
 *   moveTo / lineTo /      no-ops. These are PathConsumer2D methods
 *   quadTo / curveTo /     that would build up the path; with no
 *   closePath / pathDone   native path state there is nothing to
 *                          build.
 *
 *   getNativeConsumer      returns 0, for the same reason as
 *                          getNativeIterator.
 *
 * These are genuine "no shape scan-conversion" answers, not stubs that
 * abort. Aborting would break every program that constructs a
 * Graphics2D whose destination happens to be an XRender-backed
 * SurfaceData — the construction itself queries these natives during
 * pipeline selection. Returning the "no spans" / "no native iterator"
 * answers lets the pipeline fall through to the Java-side
 * software-rasteriser path that the runtime actually supports.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * initIDs is a STATIC native: no receiver argument.
 *
 * The other eighteen are INSTANCE natives: the receiver is the first
 * argument.
 *
 * The boolean parameter of setNormalize and the boolean return of
 * nextSpan are represented as int32_t at the C ABI level, matching
 * every other boolean native in this runtime.
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
 *   class "sun/java2d/pipe/ShapeSpanIterator"
 *       -> "sun_java2d_pipe_ShapeSpanIterator"
 *
 * Symbol names, one per Java declaration:
 *
 *   static initIDs()V
 *   appendPoly([I[IIII)V
 *   setNormalize(Z)V
 *   setOutputAreaXYXY(IIII)V
 *   setRule(I)V
 *   addSegment(I[F)V
 *   getPathBox([I)V
 *   intersectClipBox(IIII)V
 *   nextSpan([I)Z
 *   skipDownTo(I)V
 *   getNativeIterator()J
 *   dispose()V
 *   moveTo(FF)V
 *   lineTo(FF)V
 *   quadTo(FFFF)V
 *   curveTo(FFFFFF)V
 *   closePath()V
 *   pathDone()V
 *   getNativeConsumer()J
 *
 * The dispose name is the one the linker reported as undefined; the
 * others are emitted as well and would produce the same diagnostic on
 * the next rebuild if left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/pipe/ShapeSpanIterator")
 * does not strip a leading "java/" — the class is in the sun.*
 * namespace — so it returns
 * "jnative/sun/java2d/pipe/ShapeSpanIterator.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * public static native void initIDs();
 *
 * Called from ShapeSpanIterator.<clinit>. On HotSpot this hook caches
 * the JNI field IDs used by the other natives. This runtime accesses
 * every field through its LLVM-computed byte offset and never consults
 * JNI field IDs, so there is nothing to cache.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_initIDs___V(void) {
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * public native void appendPoly(int[] xPoints, int[] yPoints,
 *                               int nPoints, int xoff, int yoff);
 *
 * Descriptor ([I[IIII)V — two int arrays and three int parameters.
 * The sanitizer produces __I_IIII_V; the previous version of this
 * file contained an extra _I_ (descriptor (I[I[IIII)V) and the
 * linker could not find the symbol.
 */
void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_appendPoly__I_IIII_V(
        void* this_iter,
        void* x_points, void* y_points,
        int32_t n_points, int32_t x_off, int32_t y_off)
{
    (void)this_iter;
    (void)x_points;
    (void)y_points;
    (void)n_points;
    (void)x_off;
    (void)y_off;
}

/*
 * private native void setNormalize(boolean adjust);
 *
 * No-op. On HotSpot this sets the flag that snaps incoming coordinates
 * to the nearest (0.25, 0.25) subpixel position before scan
 * conversion. There is no native state to configure.
 */
void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_setNormalize__Z_V(
        void* this_iter, int32_t adjust)
{
    (void)this_iter;
    (void)adjust;
}

/*
 * public native void setOutputAreaXYXY(int lox, int loy,
 *                                      int hix, int hiy);
 *
 * No-op. On HotSpot this stores the output-area rectangle that the
 * scan converter clips its generated spans against. There is no
 * native state to configure.
 */
void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_setOutputAreaXYXY__IIII_V(
        void* this_iter,
        int32_t lox, int32_t loy, int32_t hix, int32_t hiy)
{
    (void)this_iter;
    (void)lox;
    (void)loy;
    (void)hix;
    (void)hiy;
}

/*
 * public native void setRule(int rule);
 *
 * No-op. On HotSpot this stores the winding rule (WIND_EVEN_ODD or
 * WIND_NON_ZERO) that the scan converter applies to the path. There
 * is no native state to configure.
 */
void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_setRule__I_V(
        void* this_iter, int32_t rule)
{
    (void)this_iter;
    (void)rule;
}

/*
 * public native void addSegment(int type, float[] coords);
 *
 * No-op. On HotSpot this appends a single PathIterator segment
 * (MOVETO / LINETO / QUADTO / CUBICTO / CLOSE) to the native
 * path-elements list. There is no native list in this runtime.
 */
void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_addSegment__I_F_V(
        void* this_iter, int32_t type, void* coords)
{
    (void)this_iter;
    (void)type;
    (void)coords;
}

/*
 * public native void getPathBox(int[] pathbox);
 *
 * No-op. On HotSpot this writes the bounding box of the accumulated
 * path into the caller's four-element int[]. The caller's array
 * retains whatever values it had on entry (typically zeros), which is
 * a safe degenerate answer for a path that has no elements.
 */
void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_getPathBox___I_V(
        void* this_iter, void* path_box)
{
    (void)this_iter;
    (void)path_box;
}

/*
 * public native void intersectClipBox(int lox, int loy,
 *                                     int hix, int hiy);
 *
 * No-op. On HotSpot this narrows the output-area rectangle to the
 * intersection of itself and the given box. There is no native state
 * to configure.
 */
void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_intersectClipBox__IIII_V(
        void* this_iter,
        int32_t lox, int32_t loy, int32_t hix, int32_t hiy)
{
    (void)this_iter;
    (void)lox;
    (void)loy;
    (void)hix;
    (void)hiy;
}

/*
 * public native boolean nextSpan(int[] spanbox);
 *
 * Returns false. On HotSpot this advances the native scan-conversion
 * state machine and writes the next horizontal span into the caller's
 * four-element int[] (x, y, x2, y2). With no native scan converter in
 * this runtime, the iteration loop terminates on the very first call
 * and no spans are produced. That is the correct answer for a runtime
 * with no shape rasteriser — every downstream renderer treats "no
 * spans" as "nothing to draw".
 */
int32_t __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_nextSpan___I_Z(
        void* this_iter, void* span_box)
{
    (void)this_iter;
    (void)span_box;
    return 0;
}

/*
 * public native void skipDownTo(int y);
 *
 * No-op. On HotSpot this tells the native scan converter that it may
 * skip every span whose Y range lies entirely above the given
 * coordinate. There is no native state to advance.
 */
void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_skipDownTo__I_V(
        void* this_iter, int32_t y)
{
    (void)this_iter;
    (void)y;
}

/*
 * public native long getNativeIterator();
 *
 * Returns 0. On HotSpot this returns the address of a native
 * SpanIterator function-block that downstream C code uses to iterate
 * spans without upcalling into Java. With no native scan converter and
 * no struct to return, 0 is the sentinel the Java-side caller checks
 * against ("no native fast path"); it then falls back to the Java
 * pathDone / nextSpan loop, which in turn sees nextSpan return false
 * and terminates immediately.
 */
int64_t __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_getNativeIterator___J(
        void* this_iter)
{
    (void)this_iter;
    return 0;
}

/*
 * public native void dispose();
 *
 * No-op. On HotSpot this releases the C-side SpanIterator struct
 * allocated by the constructor. This runtime allocated nothing, so
 * there is nothing to release. The method is nevertheless reachable
 * from the caller's `finally { ssi.dispose(); }` block, and the
 * emission requires a definition.
 */
void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_dispose___V(
        void* this_iter)
{
    (void)this_iter;
}

/*
 * The six PathConsumer2D methods below let the caller feed path
 * elements one at a time instead of via a PathIterator. On HotSpot
 * they append to the same native list that addSegment writes to; in
 * this runtime there is no list and the calls are no-ops.
 */

void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_moveTo__FF_V(
        void* this_iter, float x, float y)
{
    (void)this_iter;
    (void)x;
    (void)y;
}

void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_lineTo__FF_V(
        void* this_iter, float x, float y)
{
    (void)this_iter;
    (void)x;
    (void)y;
}

void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_quadTo__FFFF_V(
        void* this_iter,
        float x1, float y1, float x2, float y2)
{
    (void)this_iter;
    (void)x1;
    (void)y1;
    (void)x2;
    (void)y2;
}

void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_curveTo__FFFFFF_V(
        void* this_iter,
        float x1, float y1, float x2, float y2, float x3, float y3)
{
    (void)this_iter;
    (void)x1;
    (void)y1;
    (void)x2;
    (void)y2;
    (void)x3;
    (void)y3;
}

void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_closePath___V(
        void* this_iter)
{
    (void)this_iter;
}

void __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_pathDone___V(
        void* this_iter)
{
    (void)this_iter;
}

/*
 * public native long getNativeConsumer();
 *
 * Returns 0. On HotSpot this returns the address of a
 * PathConsumer2D function-block that native code can use to feed path
 * elements back into the Java object without upcalling. Same reasoning
 * as getNativeIterator: 0 is the "no native fast path" sentinel.
 */
int64_t __jnative_fn_sun_java2d_pipe_ShapeSpanIterator_getNativeConsumer___J(
        void* this_iter)
{
    (void)this_iter;
    return 0;
}
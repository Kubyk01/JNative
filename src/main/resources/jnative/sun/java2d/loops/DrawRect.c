#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.loops.DrawRect — native entry point.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * DrawRect is one of the java2d GraphicsPrimitive subclasses. It
 * represents the "draw the outline of a single-width rectangle"
 * operation: given a top-left corner, a width and a height in device
 * coordinates, it strokes the four edges of the rectangle with a
 * one-pixel-wide line, honouring the graphics state's clip, composite
 * and paint.
 *
 * The class declares a single public native:
 *
 *     public native void DrawRect(SunGraphics2D sg2d, SurfaceData dest,
 *                                 int x1, int y1, int w, int h);
 *
 * which the java2d pipeline reaches through LoopPipe — the general
 * software pipe that dispatches every drawing operation to the
 * primitive registered for the current surface / composite pair. The
 * diagnostic names sun/java2d/pipe/LoopPipe.ll because the call site
 * lives in LoopPipe's bytecode, but the class file whose native needs
 * a definition is DrawRect itself.
 *
 * Without a definition in the linked executable, clang rejects
 * whichever translation unit emits the call — LoopPipe.ll in the
 * current build — with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_loops_DrawRect_DrawRect__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_IIII_V'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot with the software pipeline selected, the native
 * implementation issues the four line primitives that make up the
 * rectangle's outline:
 *
 *     (x1, y1)          -> (x1 + w - 1, y1)            top edge
 *     (x1 + w - 1, y1)  -> (x1 + w - 1, y1 + h - 1)    right edge
 *     (x1 + w - 1, y1 + h - 1) -> (x1, y1 + h - 1)     bottom edge
 *     (x1, y1 + h - 1)  -> (x1, y1)                    left edge
 *
 * The exact arithmetic of the corner coordinates is defined by the
 * java2d rectangle contract: a rectangle of width `w` covers pixels
 * whose X coordinate satisfies `x1 <= x < x1 + w`, and symmetrically
 * for the height. The four edges are stroked through the same line-
 * rasterisation loop that the single-line DrawLine primitive uses;
 * this native is essentially a four-iteration loop that calls it
 * once per edge.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no accelerated java2d pipeline. GraphicsEnvironment
 * .isHeadless() is true on every reachable path, so no X11GraphicsConfig,
 * XRSurfaceData or GLXSurfaceData is ever constructed for a real
 * destination surface, and SunGraphics2D.validatePipe() never selects
 * an accelerated DrawRect primitive. Every caller in this runtime has
 * a well-defined software fallback that operates on the destination
 * raster's Java-side data through the JDK's own Java-level primitive
 * implementations.
 *
 * The native is nevertheless emitted into the module because the
 * bytecode of LoopPipe and of the GraphicsPrimitiveMgr registration
 * tables contains the call site. The implementation below is a strict
 * no-op:
 *
 *   - There is no destination surface to draw onto.
 *
 *   - There is no rectangle outline to stroke.
 *
 *   - No observable effect — neither in the program's output nor in
 *     any subsequent read of the destination surface — would be
 *     produced by drawing in a runtime that cannot display the result,
 *     and no reachable caller would observe the difference.
 *
 * Aborting would break every program that constructs a Graphics2D and
 * calls drawRect — a purely in-memory operation — because
 * SunGraphics2D's pipeline-selection code walks the accelerated
 * primitive tables during initialisation and would trip the abort
 * before the software path could take over.
 *
 * ============================================================================
 * Calling convention and mangled name
 * ============================================================================
 *
 * DrawRect is an INSTANCE native: the receiver is the first argument,
 * followed by the six Java-level parameters. The C signature takes,
 * in order:
 *
 *     this_prim   DrawRect receiver
 *     sg2d        SunGraphics2D reference (graphics context)
 *     dest        SurfaceData reference (destination surface)
 *     x1, y1      int32_t top-left corner in device coordinates
 *     w, h        int32_t width and height of the rectangle
 *
 * The mangled name applies the standard rule
 *     "fn_" + sanitize(class) + "_" + sanitize(method) + "_" + sanitize(desc)
 * and the emitter prefixes it with "__jnative_":
 *
 *   class  "sun/java2d/loops/DrawRect"
 *       -> "sun_java2d_loops_DrawRect"
 *   desc   "(Lsun/java2d/SunGraphics2D;Lsun/java2d/SurfaceData;IIII)V"
 *       -> "_Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_IIII_V"
 *
 * giving the symbol the linker reported as undefined. The double
 * underscore after the method name is the method-to-descriptor
 * separator followed by the sanitised `(`. Each reference parameter's
 * `/` and `;` characters sanitize to `_`, producing the two `L…_`
 * tokens separated by a single underscore. The four `I`s are the
 * sanitised int coordinates, and the trailing `_V` is the sanitised
 * `)` plus the void return type.
 *
 * Every argument is captured and discarded: there is no destination
 * surface to write to, no graphics state to apply, and no rectangle
 * to stroke.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/loops/DrawRect") does
 * not strip a leading "java/" — the class is in the sun.* namespace —
 * so it returns "jnative/sun/java2d/loops/DrawRect.c". That is the
 * path this file must live at for Compiler.extractSystemNativeSources
 * to find it and compile it into the executable.
 */

/*
 * public native void DrawRect(SunGraphics2D sg2d, SurfaceData dest,
 *                             int x1, int y1, int w, int h);
 *
 * No-op. See the file header for the full rationale: the runtime has
 * no accelerated java2d pipeline that could invoke this primitive, and
 * every caller in this runtime has a well-defined software fallback
 * path that operates on the destination raster's Java-side data
 * instead. The six arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_loops_DrawRect_DrawRect__Lsun_java2d_SunGraphics2D_Lsun_java2d_SurfaceData_IIII_V(
        void* this_prim,
        void* sg2d,
        void* dest,
        int32_t x1, int32_t y1,
        int32_t w,  int32_t h)
{
    (void)this_prim;
    (void)sg2d;
    (void)dest;
    (void)x1; (void)y1;
    (void)w;  (void)h;
}
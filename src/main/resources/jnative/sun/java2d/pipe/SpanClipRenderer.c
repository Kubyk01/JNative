#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.pipe.SpanClipRenderer — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * SpanClipRenderer is the CompositePipe wrapper that the java2d
 * software pipeline installs when the graphics context's clip is a
 * non-rectangular Region. A Region is a union of axis-aligned
 * rectangles; rendering a shape through it requires intersecting the
 * shape's rasterised span tile with each rectangle in turn. That is
 * what this class does: it holds a RegionIterator, and for every tile
 * the pipeline produces, it clips the tile's alpha values down to the
 * part of the tile that lies inside the current Region.
 *
 * The class declares three natives:
 *
 *   static native void initIDs(Class<?> rc, Class<?> ric);
 *
 *     Called from the class's static initializer. On HotSpot this
 *     hook caches the JNI field IDs of Region and RegionIterator so
 *     the two instance natives can reach into those objects without
 *     repeated lookups.
 *
 *   public native void fillTile(RegionIterator ri,
 *                               byte[] alpha, int offset, int tsize,
 *                               int[] band);
 *
 *     Initialises the alpha tile for a "fill" operation. On HotSpot
 *     this walks the RegionIterator's clip rectangles and fills the
 *     corresponding regions of the alpha array with 0xFF (fully
 *     covered), leaving the parts of the band that fall outside the
 *     Region at 0x00 (fully transparent). Called from
 *     renderPathTile when the incoming tile is null.
 *
 *   public native void eraseTile(RegionIterator ri,
 *                                byte[] alpha, int offset, int tsize,
 *                                int[] band);
 *
 *     Zeroes out the parts of the alpha tile that fall outside the
 *     current Region. Called from renderPathTile when the incoming
 *     tile is non-null, i.e. when the pipeline has already produced a
 *     full-coverage tile and needs the parts outside the clip
 *     removed.
 *
 * The `band` argument is the four-element rectangle
 * {x, y, x+w, y+h} that identifies the region of the destination that
 * the tile covers. `alpha` is the byte[] holding the tile's coverage
 * values. `offset` is the tile's position within `alpha`, and `tsize`
 * is the row stride in bytes.
 *
 * The LLVM emitter records a call site for each native in the
 * generated IR. Without definitions in the linked executable, clang
 * rejects sun/java2d/pipe/SpanClipRenderer.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_pipe_SpanClipRenderer_<method>_<desc>'
 *
 * The diagnostic that motivated this file names eraseTile, which is
 * simply the first undefined symbol clang happened to reach; the other
 * two would surface on the next rebuild if left out.
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot with the software pipeline selected, both instance
 * natives drive the same inner loop:
 *
 *   1. The RegionIterator is queried for its native SpanIterator
 *      function block, whose `nextSpan(iterator, spanbox)` entry
 *      point yields one axis-aligned clip rectangle at a time.
 *
 *   2. For each clip rectangle, the native intersects it with the
 *      tile's `band` rectangle.
 *
 *   3. When the intersection is non-empty, the alpha array bytes for
 *      the intersection are written: 0xFF for fillTile, 0x00 for
 *      eraseTile. The tile's row stride (`tsize`) and its base offset
 *      within `alpha` are used to compute the byte range directly.
 *
 * The loop terminates when the SpanIterator's nextSpan returns 0
 * (no more clip rectangles), which is the same iteration protocol
 * every SpanIterator in this runtime uses.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no display, no accelerated java2d pipeline, and no
 * live path that renders a Shape through a non-rectangular Region.
 * GraphicsEnvironment.isHeadless() is true on every reachable path,
 * and every reachable program that touches java2d draws into a
 * BufferedImage-backed Graphics2D whose clip is either the identity
 * Region (in which case getCompositePipe returns the bare outpipe and
 * SpanClipRenderer is never instantiated) or a plain rectangular
 * Region (in which case the rectangular fast path handles the clip
 * without SpanClipRenderer as well).
 *
 * The class is nevertheless emitted into the module because SoftPipe's
 * own getCompositePipe method references it unconditionally, and its
 * bytecode contains the call sites for both instance natives. Without
 * definitions, clang rejects SpanClipRenderer.ll as soon as it tries
 * to resolve the symbols.
 *
 * The three implementations below provide the answers that keep the
 * class consistent without requiring a real RegionIterator to walk:
 *
 *   initIDs   no-op. This runtime accesses every field through its
 *             LLVM-computed byte offset and never consults JNI field
 *             IDs, so there is nothing to cache. The symbol must exist
 *             because the class's static initializer emits a native
 *             call to it.
 *
 *   fillTile  no-op. On the paths where the tile is initialised by
 *             renderPathTile's own Java-side allocation
 *             (`atile = new byte[size]`), the tile arrives already
 *             zeroed; leaving it zeroed is the same state the
 *             reference implementation produces for a Region whose
 *             every clip rectangle lies outside the tile's band. The
 *             downstream outpipe.renderPathTile therefore composites
 *             "nothing drawn," which is the correct answer for a
 *             runtime that cannot produce visible output.
 *
 *   eraseTile no-op. A tile reaching eraseTile is one that was filled
 *             by a previous fillTile call. Since fillTile leaves the
 *             tile zeroed, eraseTile operating on a zeroed tile is
 *             itself a no-op: zeroing bytes that are already zero has
 *             no observable effect. The two natives are therefore
 *             mutually consistent — together they leave the tile in
 *             the same state the reference implementation produces for
 *             an empty clip Region.
 *
 * These are genuine "no non-rectangular clip" answers, not stubs that
 * abort. Aborting would break the initialisation of SoftPipe itself,
 * because the class's getCompositePipe method references
 * SpanClipRenderer in its own bytecode and any program that constructs
 * a Graphics2D would trip the abort before the software path could
 * take over. Returning silently keeps every downstream consumer on a
 * path it already handles.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * initIDs is a STATIC native: no receiver argument. It takes the two
 * Class objects the static initializer supplies (Region.class and
 * RegionIterator.class) and returns void.
 *
 * fillTile and eraseTile are INSTANCE natives: the receiver is the
 * first argument. Both take the same five Java-level parameters:
 *
 *     RegionIterator ri    — the clip iterator
 *     byte[] alpha         — the tile's coverage array
 *     int offset           — the tile's byte offset within `alpha`
 *     int tsize            — the tile's row stride in bytes
 *     int[] band           — the four-element {x, y, x+w, y+h} tile
 *                            rectangle in device coordinates
 *
 * Every argument is captured and discarded. `ri` would be queried for
 * its SpanIterator function block, `alpha` would be written, and
 * `band` would bound the region; in a runtime with no non-rectangular
 * clip to service, none of the three can affect the outcome.
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
 *   class "sun/java2d/pipe/SpanClipRenderer"
 *       -> "sun_java2d_pipe_SpanClipRenderer"
 *
 *   initIDs   desc "(Ljava/lang/Class;Ljava/lang/Class;)V"
 *       -> "__jnative_fn_sun_java2d_pipe_SpanClipRenderer_initIDs__Ljava_lang_Class_Ljava_lang_Class__V"
 *
 *   fillTile  desc "(Lsun/java2d/pipe/RegionIterator;[BII[I)V"
 *       -> "__jnative_fn_sun_java2d_pipe_SpanClipRenderer_fillTile__Lsun_java2d_pipe_RegionIterator__BII_I_V"
 *
 *   eraseTile desc "(Lsun/java2d/pipe/RegionIterator;[BII[I)V"
 *       -> "__jnative_fn_sun_java2d_pipe_SpanClipRenderer_eraseTile__Lsun_java2d_pipe_RegionIterator__BII_I_V"
 *
 * The eraseTile name is the one the linker reported as undefined; the
 * other two are emitted as well and would produce the same diagnostic
 * on the next rebuild if left out. The double underscore after the
 * method name is the method-to-descriptor separator followed by the
 * sanitised `(`. Each reference parameter's `/` and `;` characters
 * sanitize to `_`, the `[B` and `[I` array descriptors contribute
 * their leading `[` as `_`, and the trailing `_V` is the sanitised `)`
 * plus the void return type.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/pipe/SpanClipRenderer")
 * does not strip a leading "java/" — the class is in the sun.*
 * namespace — so it returns
 * "jnative/sun/java2d/pipe/SpanClipRenderer.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find
 * it and compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * static native void initIDs(Class<?> rc, Class<?> ric);
 *
 * Called from SpanClipRenderer.<clinit>. On HotSpot this hook caches
 * the JNI field IDs of Region and RegionIterator so the two instance
 * natives can reach into those objects without repeated lookups. This
 * runtime accesses every field through its LLVM-computed byte offset
 * and never consults JNI field IDs, so there is nothing to cache. The
 * symbol must exist because the class's static initializer emits a
 * native call to it.
 *
 * STATIC native: no receiver argument.
 */
void __jnative_fn_sun_java2d_pipe_SpanClipRenderer_initIDs__Ljava_lang_Class_Ljava_lang_Class__V(
        void* region_class,
        void* region_iterator_class)
{
    (void)region_class;
    (void)region_iterator_class;
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * public native void fillTile(RegionIterator ri,
 *                             byte[] alpha, int offset, int tsize,
 *                             int[] band);
 *
 * No-op. See the file header for the full rationale: the runtime has
 * no non-rectangular Region clip to service, and the tile that
 * renderPathTile allocates before invoking this native arrives
 * already zeroed, so leaving it zeroed preserves the mutually
 * consistent state established with eraseTile below.
 *
 * The five arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_pipe_SpanClipRenderer_fillTile__Lsun_java2d_pipe_RegionIterator__BII_I_V(
        void* this_renderer,
        void* ri,
        void* alpha,
        int32_t offset,
        int32_t tsize,
        void* band)
{
    (void)this_renderer;
    (void)ri;
    (void)alpha;
    (void)offset;
    (void)tsize;
    (void)band;
}

/*
 * public native void eraseTile(RegionIterator ri,
 *                              byte[] alpha, int offset, int tsize,
 *                              int[] band);
 *
 * No-op. On HotSpot this zeroes the parts of the alpha tile that fall
 * outside the Region's clip rectangles. Since fillTile above leaves
 * the tile zeroed, eraseTile operating on a zeroed tile has no
 * observable effect: zeroing bytes that are already zero produces the
 * same state the reference implementation would produce for an empty
 * clip Region. The five arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_pipe_SpanClipRenderer_eraseTile__Lsun_java2d_pipe_RegionIterator__BII_I_V(
        void* this_renderer,
        void* ri,
        void* alpha,
        int32_t offset,
        int32_t tsize,
        void* band)
{
    (void)this_renderer;
    (void)ri;
    (void)alpha;
    (void)offset;
    (void)tsize;
    (void)band;
}
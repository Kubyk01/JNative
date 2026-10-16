#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.opengl.GLXSurfaceData — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares one native method:
 *
 *     private native void initOps(OGLGraphicsConfig gc,
 *                                 X11ComponentPeer peer,
 *                                 long aData);
 *
 * It is called from the class's protected constructor, which runs on
 * every GLXSurfaceData subclass creation. The reachability walk pulls
 * the constructor into the compiled image whenever GLXSurfaceData (or
 * any of its inner subclasses) is reachable, and the bytecode then
 * emits a call site for the native. Without a definition in the linked
 * executable, clang rejects sun/java2d/opengl/GLXSurfaceData.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_opengl_GLXSurfaceData_initOps__Lsun_java2d_opengl_OGLGraphicsConfig_Lsun_awt_X11ComponentPeer_J_V'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, GLXSurfaceData is the abstract base class of every
 * GLX-backed java2d.SurfaceData. Its native side links against libGL
 * and libX11 and stores, in each surface's native OGLSurfaceData
 * struct:
 *
 *   initOps
 *       combines the OpenGL configuration information (held in the
 *       native AwtGraphicsConfigData struct whose address is `aData`)
 *       with the drawable id of the X11 component peer, and writes
 *       both into the surface's native state. Subsequent GLX rendering
 *       operations read the drawable and the visual's GLX attributes
 *       from that struct without consulting the Java side again.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no GLX extension, no OpenGL
 * library, and no GPU. The GLXSurfaceData class is only reachable in
 * the compiled image because GLXGraphicsConfig references it, and
 * GLXGraphicsConfig itself is only reachable because X11GraphicsConfig
 * references it in the pipeline-selection code. In practice no
 * GLXSurfaceData instance is ever constructed, because:
 *
 *   - X11GraphicsEnvironment.isGLXAvailable() returns false on every
 *     reachable path (see the sibling X11GraphicsEnvironment
 *     implementation), and
 *
 *   - GLXGraphicsConfig.getConfig(device, visualnum) returns null
 *     before constructing a GLXSurfaceData.
 *
 * The native is nevertheless emitted into the module because the
 * constructor's call site exists in the bytecode. Its implementation
 * is a strict no-op:
 *
 *   - There is no native OGLSurfaceData struct to allocate or
 *     populate. The three private fields the class inherits (pData,
 *     etc.) stay at their zero-initialised values, which is what a
 *     surface with no native backing looks like.
 *
 *   - The `aData` argument would be 0 in every path that could
 *     possibly reach this native, because GLXGraphicsConfig's
 *     underlying init() is itself a no-op (see
 *     jnative/sun/awt/X11GraphicsConfig.c). Passing 0 to a native that
 *     would dereference it as a pointer is undefined behaviour on the
 *     reference implementation; the no-op is the only truthful and
 *     safe behaviour.
 *
 * This is a genuine "no GLX" answer, not a stub that aborts. Aborting
 * would break every program that constructs a
 * GraphicsEnvironment-derived GraphicsConfiguration hierarchy, since
 * some of those construction paths probe the GLX config class even
 * when the caller never asks for an OpenGL-backed surface. The no-op
 * keeps those callers on the paths they already handle.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * initOps is an INSTANCE native: the receiver is the first argument,
 * followed by the three Java-level parameters. The Java declaration is
 *
 *     private native void initOps(OGLGraphicsConfig gc,
 *                                 X11ComponentPeer peer,
 *                                 long aData);
 *
 * so the C signature takes the GLXSurfaceData receiver, then the
 * OGLGraphicsConfig reference, then the X11ComponentPeer reference,
 * then the long. The two reference parameters arrive as i8* in LLVM
 * terms; the long arrives as int64_t.
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
 *   class "sun/java2d/opengl/GLXSurfaceData"
 *       -> "sun_java2d_opengl_GLXSurfaceData"
 *   method "initOps" -> "initOps"
 *   desc "(Lsun/java2d/opengl/OGLGraphicsConfig;
 *          Lsun/awt/X11ComponentPeer;J)V"
 *       -> "_Lsun_java2d_opengl_OGLGraphicsConfig_Lsun_awt_X11ComponentPeer_J_V"
 *
 * Full symbol:
 *
 *     __jnative_fn_sun_java2d_opengl_GLXSurfaceData_initOps__Lsun_java2d_opengl_OGLGraphicsConfig_Lsun_awt_X11ComponentPeer_J_V
 *
 * which matches the name the linker reported as undefined. The leading
 * `__` after `initOps` is the method-name separator followed by the
 * sanitised `(`. The two reference parameters sanitize their `/` and
 * `;` characters to `_`, giving the two `L…_` tokens separated by a
 * single underscore. The trailing `_J_V` is the sanitised `)` and the
 * long parameter, followed by the void return.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/opengl/GLXSurfaceData")
 * does not strip a leading "java/" — the class is in the sun.*
 * namespace — so it returns
 * "jnative/sun/java2d/opengl/GLXSurfaceData.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/*
 * private native void initOps(OGLGraphicsConfig gc,
 *                             X11ComponentPeer peer,
 *                             long aData);
 *
 * No-op. See the file header for the full rationale: this runtime has
 * no X11 connection, no GLX extension, and no OpenGL library, so no
 * GLXSurfaceData instance is ever constructed through the normal path,
 * and even if one were, there is no native state to initialise.
 *
 * The three arguments are captured and discarded. All three are
 * objects/values that would be needed only by a native OpenGL
 * implementation that does not exist in this runtime.
 */
void __jnative_fn_sun_java2d_opengl_GLXSurfaceData_initOps__Lsun_java2d_opengl_OGLGraphicsConfig_Lsun_awt_X11ComponentPeer_J_V(
        void* this_surface,
        void* gc,
        void* peer,
        int64_t a_data)
{
    (void)this_surface;
    (void)gc;
    (void)peer;
    (void)a_data;
}
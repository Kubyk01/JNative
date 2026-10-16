#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.opengl.GLXGraphicsConfig — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares three native methods. Every one of them is called
 * from some reachable method of the class, so the LLVM emitter records
 * a call site for each in the generated IR. Without definitions in the
 * linked executable, clang rejects
 * sun/java2d/opengl/GLXGraphicsConfig.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_opengl_GLXGraphicsConfig_initConfig__JJ_V'
 *
 * and stops. Providing only the symbol in the diagnostic would move the
 * error to the next one; all three are defined here so the module links
 * in one pass:
 *
 *   getGLXConfigInfo    — static, queries GLX for a visual's config
 *   getOGLCapabilities  — static, reads capability bits from a config
 *   initConfig          — instance, primes per-config native state
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, GLXGraphicsConfig is the GraphicsConfiguration for a
 * visual that is backed by an OpenGL-capable X server (via the GLX
 * extension). Its native side links against libGL and libX11 and drives
 * the GLX configuration machinery:
 *
 *   getGLXConfigInfo
 *       calls glXGetConfig for the given screen and visual, reads the
 *       visual's double-buffer capability, RGBA sizes, depth, stencil,
 *       and accumulation-buffer parameters, and packs them into a
 *       single long ("configInfo") that the Java side can inspect
 *       without making further native calls.
 *
 *   getOGLCapabilities
 *       reads the packed configInfo and returns a bitmask of the
 *       OGLContext.CAPS_* flags (CAPS_DOUBLEBUFFERED,
 *       CAPS_EXT_FBOBJECT, CAPS_STORED_ALPHA, …). The Java side wraps
 *       the result in an OGLContextCaps instance.
 *
 *   initConfig
 *       stores the native configInfo into the surface's per-config
 *       state, so that subsequent surface creation calls (GLXSurfaceData)
 *       can look up the visual's GLX attributes without re-querying
 *       the server.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no X server, no GLX extension, no
 * OpenGL library, and no GPU. GraphicsEnvironment.isHeadless() is true
 * on every reachable path, and X11GraphicsEnvironment.isGLXAvailable()
 * returns false (see the sibling X11GraphicsEnvironment implementation),
 * so GLXGraphicsConfig.getConfig() returns null before ever reaching
 * the native methods.
 *
 * Every native is nevertheless emitted into the module because the call
 * sites exist in the bytecode. The three implementations below provide
 * the answers that keep a runtime without GLX internally consistent:
 *
 *   getGLXConfigInfo    returns 0. The Java-side caller
 *                       (getConfig) checks for a non-zero return
 *                       before proceeding to construct a
 *                       GLXGraphicsConfig; 0 is the "no GLX config
 *                       available for this visual" sentinel that
 *                       steers the caller to its null fallback.
 *
 *   getOGLCapabilities  returns 0. With no GLX, no OpenGL capabilities
 *                       are present; 0 is the truthful empty bitmask
 *                       and matches what the reference implementation
 *                       returns for a visual that supports none of the
 *                       optional extensions.
 *
 *   initConfig          no-op. There is no native per-config struct to
 *                       allocate or populate; the Java-side
 *                       pConfigInfo field has already been set to 0 by
 *                       the constructor (which only runs when
 *                       getGLXConfigInfo returned non-zero, which it
 *                       never does here).
 *
 * These are genuine "no GLX" answers, not stubs that abort. Aborting
 * would break every program that asks GraphicsEnvironment whether
 * OpenGL acceleration is available — including the AWT's own pipeline
 * selection code, which probes GLX support during initialisation even
 * on programs that never call any OpenGL API. Returning 0 keeps those
 * probes on the documented "GLX unavailable" branch that every caller
 * is written to handle.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * getGLXConfigInfo and getOGLCapabilities are STATIC natives: no
 * receiver argument.
 *
 * initConfig is an INSTANCE native: the receiver is the first argument.
 *
 * The long return types of getGLXConfigInfo and getOGLCapabilities are
 * i64 at the IR level, which the C ABI represents as int64_t. The int
 * return of getOGLCapabilities is i32 at the IR level, which the C ABI
 * represents as int32_t.
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
 *   class "sun/java2d/opengl/GLXGraphicsConfig"
 *       -> "sun_java2d_opengl_GLXGraphicsConfig"
 *
 *   getGLXConfigInfo   desc "(II)J"
 *       -> "__jnative_fn_sun_java2d_opengl_GLXGraphicsConfig_getGLXConfigInfo__II_J"
 *   getOGLCapabilities desc "(J)I"
 *       -> "__jnative_fn_sun_java2d_opengl_GLXGraphicsConfig_getOGLCapabilities__J_I"
 *   initConfig         desc "(JJ)V"
 *       -> "__jnative_fn_sun_java2d_opengl_GLXGraphicsConfig_initConfig__JJ_V"
 *
 * The initConfig name is the one the linker reported as undefined; the
 * other two are emitted as well and would produce the same diagnostic
 * on the next rebuild if left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/opengl/GLXGraphicsConfig")
 * does not strip a leading "java/" — the class is in the sun.*
 * namespace — so it returns
 * "jnative/sun/java2d/opengl/GLXGraphicsConfig.c". That is the path
 * this file must live at for Compiler.extractSystemNativeSources to
 * find it and compile it into the executable.
 */

/* ============================================================================
 *  Static natives
 * ========================================================================== */

/*
 * private static native long getGLXConfigInfo(int screennum,
 *                                             int visualnum);
 *
 * Returns 0. On HotSpot this calls glXGetConfig for the given screen
 * and visual, packs the visual's GLX capabilities into a single long,
 * and returns it. The Java-side caller (getConfig) checks for a zero
 * return before constructing a GLXGraphicsConfig; 0 is the "no GLX
 * config available" sentinel that steers the caller to its null
 * fallback — which is exactly the correct behaviour when the runtime
 * has no GLX extension to query.
 *
 * STATIC native: no receiver argument.
 */
int64_t __jnative_fn_sun_java2d_opengl_GLXGraphicsConfig_getGLXConfigInfo__II_J(
        int32_t screen_num, int32_t visual_num)
{
    (void)screen_num;
    (void)visual_num;
    return 0;
}

/*
 * private static native int getOGLCapabilities(long configInfo);
 *
 * Returns 0. On HotSpot this reads the packed configInfo and returns a
 * bitmask of OGLContext.CAPS_* flags (double buffered, FBO object,
 * stored alpha, etc.). With no GLX, no OpenGL capabilities are
 * available; 0 is the truthful empty bitmask and matches what the
 * reference implementation returns for a visual that supports none of
 * the optional extensions.
 *
 * STATIC native: no receiver argument.
 */
int32_t __jnative_fn_sun_java2d_opengl_GLXGraphicsConfig_getOGLCapabilities__J_I(
        int64_t config_info)
{
    (void)config_info;
    return 0;
}

/* ============================================================================
 *  Instance natives
 * ========================================================================== */

/*
 * private native void initConfig(long aData, long ctxinfo);
 *
 * No-op. On HotSpot this stores the native GLX configInfo into the
 * per-config state associated with the AwtGraphicsConfigData struct
 * whose address the Java side passes as `aData`. This runtime has no
 * AwtGraphicsConfigData struct — the parent class's init() is a no-op
 * (see jnative/sun/awt/X11GraphicsConfig.c), so aData is always 0 —
 * and no per-config GLX state to install. The Java-side pConfigInfo
 * field was already set to 0 by the constructor path (which only runs
 * when getGLXConfigInfo returned non-zero, which it never does here).
 *
 * The two arguments are captured and discarded.
 */
void __jnative_fn_sun_java2d_opengl_GLXGraphicsConfig_initConfig__JJ_V(
        void* this_config, int64_t a_data, int64_t ctxinfo)
{
    (void)this_config;
    (void)a_data;
    (void)ctxinfo;
}
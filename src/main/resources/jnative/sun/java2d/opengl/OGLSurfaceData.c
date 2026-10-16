#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.java2d.opengl.OGLSurfaceData — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * OGLSurfaceData is the abstract base of every OpenGL-backed
 * java2d.SurfaceData (OGLWindowSurfaceData, OGLTextureData,
 * OGLFBOSurfaceData, OGLOffscreenSurfaceData). It declares five
 * natives, all of them instance methods that reach into the OpenGL
 * context owned by the surface's OGLGraphicsConfig:
 *
 *   initTexture        create a GL_TEXTURE_2D (or
 *                      GL_TEXTURE_RECTANGLE_ARB) texture object of the
 *                      requested dimensions and bind it to the
 *                      surface's native state.
 *
 *   initFBObject       create a framebuffer object (FBO) with an
 *                      attached colour texture and, optionally, a
 *                      depth/stencil renderbuffer.
 *
 *   initFlipBackbuffer attach the surface to the window system's
 *                      flip-chain back buffer (the legacy
 *                      GLX_SGI_swap_control / WGL_BUFFER_SWAP model
 *                      used on platforms without FBO support).
 *
 *   getTextureTarget   return GL_TEXTURE_2D or GL_TEXTURE_RECTANGLE_ARB,
 *                      matching the target that initTexture or
 *                      initFBObject selected.
 *
 *   getTextureID       return the GL object name of the texture
 *                      created by initTexture or initFBObject.
 *
 * The LLVM emitter records a call site for each in the generated IR —
 * the bytecode of OGLSurfaceData contains the calls regardless of
 * which concrete subclass is instantiated at run time — so every one
 * of them must resolve at link time. Without a definition, clang
 * rejects sun/java2d/opengl/OGLSurfaceData.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_java2d_opengl_OGLSurfaceData_<method>_<desc>'
 *
 * The diagnostic that motivated this file names getTextureID, which is
 * simply the first undefined symbol clang happened to reach; the other
 * four would surface on the next rebuild if left out.
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot with a GLX-capable X server and an OpenGL driver, the
 * native side of OGLSurfaceData drives the surface lifecycle through
 * the GLX context owned by OGLGraphicsConfig:
 *
 *   initTexture
 *       calls glGenTextures / glBindTexture / glTexImage2D to allocate
 *       a texture object of the requested dimensions and stores its
 *       GL object name in the surface's native OGLSurfaceData struct.
 *       The `isOpaque`, `texNonPow2` and `texRect` flags select the
 *       internal format and the target (GL_TEXTURE_2D vs.
 *       GL_TEXTURE_RECTANGLE_ARB).
 *
 *   initFBObject
 *       calls glGenFramebuffersEXT / glBindFramebufferEXT, creates a
 *       colour attachment texture via glGenTextures, attaches it with
 *       glFramebufferTexture2DEXT, and verifies the FBO is complete
 *       with glCheckFramebufferStatusEXT.
 *
 *   initFlipBackbuffer
 *       binds the surface directly to the X server's flip-chain back
 *       buffer (the non-FBO fallback path used when the
 *       GL_EXT_framebuffer_object extension is unavailable).
 *
 *   getTextureTarget / getTextureID
 *       read the target and object name from the surface's native
 *       struct, so that downstream code (OGLBlitLoops, OGLTextRenderer,
 *       OGLDrawImage) can bind the texture for a subsequent operation.
 *
 * All five are declared `native` in the class, so the emitter emits
 * them regardless of which build path the runtime takes.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no X11 connection, no GLX extension, no OpenGL
 * library, no GPU, and no OpenGL context of any kind. Every
 * precondition that would allow a surface of this type to exist is
 * false on every reachable path:
 *
 *   - X11GraphicsEnvironment.isGLXAvailable() returns false (see
 *     jnative/sun/awt/X11GraphicsEnvironment.c), so the OpenGL
 *     pipeline is never selected by SunGraphics2D.validatePipe().
 *
 *   - GLXGraphicsConfig.getConfig() returns 0 from
 *     getGLXConfigInfo (see jnative/sun/java2d/opengl/GLXGraphicsConfig.c)
 *     for every visual, so no GLXGraphicsConfig is ever constructed.
 *
 *   - The static initializer of OGLSurfaceData itself is guarded by
 *     `if (!GraphicsEnvironment.isHeadless())`, which is false in
 *     every reachable path of this runtime; the OGLRenderQueue,
 *     OGLTextRenderer, OGLRenderer and PixelToParallelogramConverter
 *     fields are therefore never populated.
 *
 * No OGLSurfaceData instance can be constructed, and no native method
 * can be invoked from a live path. The five implementations below
 * provide the "no OpenGL" answers that keep the module linkable and
 * internally consistent:
 *
 *   initTexture        returns false. The Java-side caller
 *                      (OGLSurfaceData.initSurfaceNow) treats a false
 *                      return as "surface creation failed" and raises
 *                      `OutOfMemoryError("can't create offscreen
 *                      surface")`, which is exactly the diagnostic
 *                      HotSpot produces on a system whose OpenGL
 *                      driver refuses to allocate a texture.
 *
 *   initFBObject       returns false, for the same reason and with the
 *                      same consequence.
 *
 *   initFlipBackbuffer returns false, for the same reason and with the
 *                      same consequence.
 *
 *   getTextureTarget   returns 0. The Java-side getTextureTarget()
 *                      method treats a zero return as "this surface is
 *                      not backed by a texture object", which is the
 *                      truthful state for a surface that was never
 *                      initialised.
 *
 *   getTextureID       returns 0. Same reasoning: 0 is the "no texture
 *                      object" sentinel that every downstream GL
 *                      consumer treats as "nothing to bind".
 *
 * These are genuine "no OpenGL" answers, not stubs that abort.
 * Aborting would break the construction of the OGLSurfaceData class
 * hierarchy itself — which GraphicsPrimitiveMgr walks during
 * SunGraphics2D.validatePipe() — even on programs that never draw
 * anything and never construct a Graphics2D at all. Returning false or
 * 0 keeps every caller on the documented "no OpenGL" branch that
 * HotSpot itself exercises on a system without GLX support.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * Every native here is an INSTANCE method, so the receiver is the first
 * argument. The Java-level signatures are:
 *
 *   protected native boolean initTexture(long pData,
 *                                        boolean isOpaque,
 *                                        boolean texNonPow2,
 *                                        boolean texRect,
 *                                        int width, int height);
 *
 *   protected native boolean initFBObject(long pData,
 *                                         boolean isOpaque,
 *                                         boolean texNonPow2,
 *                                         boolean texRect,
 *                                         int width, int height);
 *
 *   protected native boolean initFlipBackbuffer(long pData);
 *
 *   private native int getTextureTarget(long pData);
 *   private native int getTextureID(long pData);
 *
 * The `pData` argument is the address of the native OGLSurfaceData
 * struct that the reference implementation uses to hold per-surface
 * GL state. In this runtime pData is always 0 — there is no struct to
 * allocate and no context to bind it into — so the native bodies do
 * not consult it.
 *
 * The boolean parameters and the boolean return types are represented
 * as int32_t at the C ABI level, matching every other boolean native in
 * this runtime (Thread.isAlive, Net.isIPv6Available0, Deflater.init, …).
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
 *   class "sun/java2d/opengl/OGLSurfaceData"
 *       -> "sun_java2d_opengl_OGLSurfaceData"
 *
 * Symbol names, one per Java declaration:
 *
 *   __jnative_fn_sun_java2d_opengl_OGLSurfaceData_initTexture__JZZZII_Z
 *   __jnative_fn_sun_java2d_opengl_OGLSurfaceData_initFBObject__JZZZII_Z
 *   __jnative_fn_sun_java2d_opengl_OGLSurfaceData_initFlipBackbuffer__J_Z
 *   __jnative_fn_sun_java2d_opengl_OGLSurfaceData_getTextureTarget__J_I
 *   __jnative_fn_sun_java2d_opengl_OGLSurfaceData_getTextureID__J_I
 *
 * The getTextureID name is the one the linker reported as undefined;
 * the other four are emitted as well and would produce the same
 * diagnostic on the next rebuild if left out.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/java2d/opengl/OGLSurfaceData")
 * does not strip a leading "java/" — the class is in the sun.*
 * namespace — so it returns
 * "jnative/sun/java2d/opengl/OGLSurfaceData.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find
 * it and compile it into the executable.
 */

/* ============================================================================
 *  Instance natives — surface initialisation
 * ========================================================================== */

/*
 * protected native boolean initTexture(long pData,
 *                                      boolean isOpaque,
 *                                      boolean texNonPow2,
 *                                      boolean texRect,
 *                                      int width, int height);
 *
 * Returns false. On HotSpot this creates a GL texture object of the
 * requested dimensions and stores its GL name and target in the
 * surface's native state. There is no GL context in this runtime to
 * create the texture in; the Java-side caller treats the false return
 * as "surface creation failed" and raises an OutOfMemoryError whose
 * message matches the reference implementation's diagnostic for a
 * failed texture allocation.
 */
int32_t __jnative_fn_sun_java2d_opengl_OGLSurfaceData_initTexture__JZZZII_Z(
        void* this_surface,
        int64_t p_data,
        int32_t is_opaque,
        int32_t tex_non_pow2,
        int32_t tex_rect,
        int32_t width,
        int32_t height)
{
    (void)this_surface;
    (void)p_data;
    (void)is_opaque;
    (void)tex_non_pow2;
    (void)tex_rect;
    (void)width;
    (void)height;
    return 0;
}

/*
 * protected native boolean initFBObject(long pData,
 *                                       boolean isOpaque,
 *                                       boolean texNonPow2,
 *                                       boolean texRect,
 *                                       int width, int height);
 *
 * Returns false. On HotSpot this creates a framebuffer object with an
 * attached colour texture and verifies that the FBO is complete. There
 * is no GL context in this runtime to create the FBO in; the Java-side
 * caller treats the false return as "surface creation failed" and
 * raises the same OutOfMemoryError that a real driver produces on a
 * system whose FBO extension is unavailable or broken.
 */
int32_t __jnative_fn_sun_java2d_opengl_OGLSurfaceData_initFBObject__JZZZII_Z(
        void* this_surface,
        int64_t p_data,
        int32_t is_opaque,
        int32_t tex_non_pow2,
        int32_t tex_rect,
        int32_t width,
        int32_t height)
{
    (void)this_surface;
    (void)p_data;
    (void)is_opaque;
    (void)tex_non_pow2;
    (void)tex_rect;
    (void)width;
    (void)height;
    return 0;
}

/*
 * protected native boolean initFlipBackbuffer(long pData);
 *
 * Returns false. On HotSpot this binds the surface to the X server's
 * flip-chain back buffer for the legacy (non-FBO) rendering path.
 * There is no X drawable and no flip chain in this runtime; the
 * Java-side caller treats the false return as "surface creation
 * failed" and raises the same OutOfMemoryError as the two variants
 * above.
 */
int32_t __jnative_fn_sun_java2d_opengl_OGLSurfaceData_initFlipBackbuffer__J_Z(
        void* this_surface, int64_t p_data)
{
    (void)this_surface;
    (void)p_data;
    return 0;
}

/* ============================================================================
 *  Instance natives — texture introspection
 * ========================================================================== */

/*
 * private native int getTextureTarget(long pData);
 *
 * Returns 0. On HotSpot this reads the target (GL_TEXTURE_2D or
 * GL_TEXTURE_RECTANGLE_ARB) that initTexture or initFBObject stored in
 * the surface's native state. The Java-side public getTextureTarget()
 * treats a zero return as "this surface is not backed by a texture
 * object", which is the truthful state for a surface that was never
 * initialised. Downstream GL consumers (OGLBlitLoops, OGLTextRenderer,
 * OGLDrawImage) check the value and skip any operation that would
 * require a bound texture.
 */
int32_t __jnative_fn_sun_java2d_opengl_OGLSurfaceData_getTextureTarget__J_I(
        void* this_surface, int64_t p_data)
{
    (void)this_surface;
    (void)p_data;
    return 0;
}

/*
 * private native int getTextureID(long pData);
 *
 * Returns 0. On HotSpot this reads the GL object name that initTexture
 * or initFBObject stored in the surface's native state. 0 is the "no
 * texture object" sentinel that every downstream GL consumer treats as
 * "nothing to bind"; the public getTextureID() method returns it
 * unchanged, and the inherited AccelSurface.getNativeResource(TEXTURE)
 * surface converts it into a 0L return for the same reason.
 */
int32_t __jnative_fn_sun_java2d_opengl_OGLSurfaceData_getTextureID__J_I(
        void* this_surface, int64_t p_data)
{
    (void)this_surface;
    (void)p_data;
    return 0;
}
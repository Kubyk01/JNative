#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.awt.FcFontManager — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares one native method:
 *
 *     native String getFontPathNative(boolean noType1Fonts,
 *                                     boolean isX11GE);
 *
 * Its Java-level wrapper getFontPath(boolean) is called from
 * SunFontManager.initializeFonts() (via the platform's
 * font-manager-specific override) and, through inheritance, from
 * X11FontManager's own font-scanning setup. Because X11FontManager
 * extends FcFontManager and inherits this method, the reachability walk
 * pulls the call site into the compiled image whenever X11FontManager
 * is reachable.
 *
 * The LLVM emitter records a call site for the native and prefixes the
 * mangled symbol with the class where the method is declared —
 * sun/awt/FcFontManager, not X11FontManager. Without a definition in
 * the linked executable, clang rejects sun/awt/X11FontManager.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_awt_FcFontManager_getFontPathNative__ZZ_Ljava_lang_String_'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, FcFontManager is the Linux/Unix FontManager that uses
 * fontconfig (libfontconfig) to discover the system's installed fonts.
 * Its native side links against libfontconfig and drives a C-side
 * font-set enumeration:
 *
 *   getFontPathNative
 *       queries fontconfig for the list of directories that contain
 *       font files, filters out Type 1 fonts when `noType1Fonts` is
 *       true, and returns the result as a single String with each
 *       directory separated by the platform's File.pathSeparator
 *       (":" on Unix). The `isX11GE` argument selects a variant used
 *       by the X11GE / XAWT font manager on Solaris-derived systems.
 *
 * The Java-side SunFontManager.initializeFonts() splits the returned
 * string on File.pathSeparator and scans each directory for font files.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no fontconfig linkage, no system font database, and
 * no need to scan font directories: the reachability walk and the LLVM
 * emitter work at the bytecode level and never load a font file
 * themselves. Font loading at run time goes through the JDK's own
 * SunFontManager and PhysicalFont machinery, which reads .ttf files
 * from disk on demand — those code paths are entirely Java-side and
 * never consult this native.
 *
 * The correct answer for getFontPathNative in a runtime that has no
 * fontconfig is an empty string. Every piece of the downstream
 * machinery that consumes the result is built to handle it:
 *
 *   - SunFontManager.initializeFonts() splits the return value on
 *     File.pathSeparator; an empty string splits to a single empty
 *     segment, which the loop filters out because a zero-length
 *     directory is not a valid path.
 *
 *   - The font-scanning code that opens each directory handles ENOENT
 *     and EACCES by logging and continuing; an empty path list simply
 *     produces zero such attempts.
 *
 *   - Any Java-side code that later asks for a system font (through
 *     Font.getFont or PhysicalFont.getFont2D) resolves it through the
 *     JDK's built-in family tables and the "Dialog"/"SansSerif"/
 *     "Monospaced" logical fonts, none of which require the directory
 *     list this native would have produced.
 *
 * Returning an empty string is therefore the honest "no fontconfig"
 * answer, and it is the same value the reference implementation would
 * return on a system whose fontconfig configuration had no
 * directories. Returning null would be wrong: the Java-side caller
 * does not null-check the result before splitting it on
 * File.pathSeparator, so a null return would NPE
 * SunFontManager.initializeFonts() and abort AWT initialisation for
 * every program that touches fonts.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * The method is an INSTANCE native (no `static` in the Java
 * declaration), so the receiver is the first argument followed by the
 * two boolean parameters. The two boolean parameters are represented
 * as int32_t at the C ABI level, matching every other boolean
 * parameter of every other native in this runtime
 * (Thread.setDaemon, Net.bind0, Deflater.init, …).
 *
 * The return value is a java.lang.String reference — an i8* in LLVM
 * terms — which in this runtime is produced by __jnative_make_string_obj.
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
 *   class  "sun/awt/FcFontManager" -> "sun_awt_FcFontManager"
 *   method "getFontPathNative"     -> "getFontPathNative"
 *   desc   "(ZZ)Ljava/lang/String;" -> "__ZZ_Ljava_lang_String_"
 *
 * giving the symbol the linker reported as undefined:
 *
 *     __jnative_fn_sun_awt_FcFontManager_getFontPathNative__ZZ_Ljava_lang_String_
 *
 * The double underscore before "ZZ" is the method-to-descriptor
 * separator followed by the sanitised '('; the trailing
 * "_Ljava_lang_String_" is the sanitised 'L...;' return type followed
 * by the sanitised ')'.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/awt/FcFontManager") does not
 * strip a leading "java/" — the class is in the sun.* namespace — so
 * it returns "jnative/sun/awt/FcFontManager.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/*
 * native String getFontPathNative(boolean noType1Fonts,
 *                                 boolean isX11GE);
 *
 * Returns an empty String. See the file header for the full rationale:
 * the runtime has no fontconfig and no font-directory scan is needed
 * for the LLVM backend, so an empty path list is both truthful and
 * safe. The two boolean arguments are captured and discarded — there
 * is no fontconfig query to filter against.
 */
void* __jnative_fn_sun_awt_FcFontManager_getFontPathNative__ZZ_Ljava_lang_String_(
        void* this_manager, int32_t no_type1_fonts, int32_t is_x11ge)
{
    (void)this_manager;
    (void)no_type1_fonts;
    (void)is_x11ge;

    /*
     * An empty string is a valid Java String (length 0) and is what
     * SunFontManager.initializeFonts() produces when no font
     * directories are configured. It splits to a single empty segment
     * on File.pathSeparator, and the downstream loop skips segments
     * that do not name a real directory. No further escaping or
     * joining is required.
     */
    return __jnative_make_string_obj("", 0);
}
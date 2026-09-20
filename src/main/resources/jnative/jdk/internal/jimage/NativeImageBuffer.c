#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

/*
 * jdk.internal.jimage.NativeImageBuffer
 *
 * The JDK's jimage reader opens the modular runtime image (the compressed
 * archive at $JAVA_HOME/lib/modules that holds every java.base class and
 * resource) through this native. BasicImageReader's constructor calls it
 * once, receives a direct ByteBuffer that maps the whole image into the
 * process address space, and parses the jimage format out of that buffer
 * for the rest of the process lifetime.
 *
 * This runtime does not use a modular runtime image at all: every class
 * that reaches the LLVM emitter is compiled directly into the executable,
 * every resource is either embedded in the executable or absent, and no
 * Java code path ever consults jimage for anything. The jimage classes
 * themselves are reachable (BasicImageReader's constructor is pulled in
 * by the reachability analysis of unrelated JDK code that imports the
 * package), so the linker sees a direct call to this native, but no
 * caller ever reaches it at run time.
 *
 * Returning NULL is the documented "image not available" answer. Every
 * Java-side caller of getNativeMap checks for null and falls through to
 * one of two well-defined behaviours:
 *
 *   - BasicImageReader's constructor throws IOException, which the
 *     enclosing ImageReaderFactory.open catches and converts into the
 *     standard "cannot open image" failure that jlink / jpackage
 *     already handle.
 *
 *   - The `jimage` tool's own entry points detect the absence of a
 *     readable image and print a diagnostic instead of dereferencing a
 *     null buffer.
 *
 * Neither path is exercised in a compiled image, so returning NULL is
 * both truthful and safe.
 */

/* -------------------------------------------------------------------------
 * private static native ByteBuffer getNativeMap(String imagePath);
 *
 * The imagePath argument is ignored: there is no image to open and no
 * alternative location to try. The function is deliberately not
 * annotated noreturn — it is an ordinary "return null for not-found"
 * accessor, matching the contract every caller expects.
 * ----------------------------------------------------------------------- */
void* __jnative_fn_jdk_internal_jimage_NativeImageBuffer_getNativeMap__Ljava_lang_String__Ljava_nio_ByteBuffer_(
        void* image_path)
{
    (void)image_path;
    return NULL;
}
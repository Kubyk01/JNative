/*
 * jdk.internal.loader.NativeLibrary — the single native entry point of
 * the NativeLibraryImpl symbol-lookup path.
 *
 * The class declares exactly one native:
 *
 *     static native long findEntry0(NativeLibraryImpl lib, String name);
 *
 * which resolves the address of a native symbol by name. In HotSpot the
 * body is a dlsym(3) call against the handle stored in lib->handle,
 * and the returned address is stored into the java.lang.reflect.Field
 * or Method that will subsequently be invoked through it.
 *
 * This runtime never resolves a symbol at run time: every native
 * method has a statically-linked __jnative_fn_* symbol emitted by the
 * LLVM backend, and the linkage between a Java-level method and its C
 * body is made at code-generation time. Nevertheless, the reachability
 * walk pulls in NativeLibrary.find because the JDK's own reflective
 * accessors reference it, and the linker needs a body.
 *
 * Returning 0 is the documented "no such symbol" answer: the
 * Java-side caller (NativeLibraryImpl.find / NativeLibraries.find)
 * treats a 0 result as "not found" and raises UnsatisfiedLinkError
 * with the symbol name attached. That is the truthful behaviour for a
 * runtime that has no dynamic symbol table to look up in, and it
 * produces a diagnostic that names the missing symbol rather than
 * silently dereferencing address 0.
 *
 * ---------------------------------------------------------------------
 * Why this file exists separately from NativeLibraries.c
 * ---------------------------------------------------------------------
 *
 * The implementation used to live in NativeLibraries.c (plural),
 * alongside NativeLibraries.load / .unload / .findBuiltinLib, because
 * all four are conceptually "the native loader machinery" and read
 * more naturally as one file.
 *
 * That organisation conflicts with BytecodeToIr.findJNativeFileOwner
 * and Compiler.hasNativeSupport, both of which decide whether a native
 * method has a JNative implementation by probing for a C file whose
 * name matches the declaring class:
 *
 *     jnative/<package>/<SimpleClassName>.c
 *
 * For NativeLibrary.findEntry0 the declaring class is NativeLibrary
 * (singular), so the probe looks for NativeLibrary.c. The file
 * NativeLibraries.c (plural) does not match, both helpers conclude
 * "no JNative implementation exists", and BytecodeToIr falls back to
 * the JNI symbol naming rule:
 *
 *     Java_jdk_internal_loader_NativeLibrary_findEntry0
 *
 * which nothing provides. The link fails with:
 *
 *     ld.lld: error: undefined symbol:
 *         Java_jdk_internal_loader_NativeLibrary_findEntry0
 *
 * Keeping the implementation in a file whose name matches the
 * declaring class — as this file does — satisfies the probe without
 * changing the probe itself, and is the smallest change that makes
 * the link succeed.
 */

#define _GNU_SOURCE
#include <stdint.h>

#include "jnative_runtime.h"

/*
 * static native long findEntry0(NativeLibraryImpl lib, String name);
 *
 * Returns the address of the named native symbol, or 0 if the symbol
 * is not available. Because every native method this runtime could
 * ever dispatch to has a statically-linked __jnative_fn_* symbol
 * emitted by the LLVM backend, and no reachable path actually invokes
 * findEntry0 at run time (the reflective-accessor machinery does not
 * reach the JNI entry point), the truthful answer is 0.
 *
 * A 0 result is caught by the Java-side caller
 * (NativeLibraryImpl.find / NativeLibraries.find), which translates it
 * into UnsatisfiedLinkError with the symbol name attached. That is
 * strictly better than attempting dlsym against the process image and
 * returning a JNative C implementation's address: the reflective
 * caller has no way to invoke an arbitrary C function through the
 * address it receives (that would require matching the JNI calling
 * convention of a function that was never designed for it), so a
 * non-zero return would send it into undefined behaviour.
 */
int64_t __jnative_fn_jdk_internal_loader_NativeLibrary_findEntry0__JLjava_lang_String__J(
        int64_t handle, void* name_str)
{
    (void)handle;
    (void)name_str;
    return 0;
}
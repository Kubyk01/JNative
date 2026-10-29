/*
 * jdk.internal.loader.NativeLibrary -- real symbol lookup.
 *
 * ==========================================================================
 * What the class declares
 * ==========================================================================
 *
 *     static native long findEntry0(NativeLibraryImpl lib, String name);
 *
 * The `lib.handle` field (offset 16 of the NativeLibraryImpl object) is
 * set by NativeLibraries.load to the process-image handle
 * (jn_dlsym_default). findEntry0 resolves `name` against that handle and
 * returns the raw address, or 0 if the symbol is absent.
 *
 * ==========================================================================
 * Why a real implementation (not `return 0`)
 * ==========================================================================
 *
 * Returning 0 unconditionally would report every symbol as missing,
 * even though the runtime *does* export real symbols for both JNative
 * natives (__jnative_fn_*) and JNI fallbacks (Java_*). The mapping from
 * a Java native method to its symbol is established statically at
 * codegen time, but the JDK's reflective-access machinery still walks
 * through this entry point when it needs to build a MethodHandle for a
 * native method. Providing the real address lets those paths continue
 * instead of failing with a misleading UnsatisfiedLinkError.
 *
 * If the symbol is genuinely absent, the function returns 0 -- the
 * documented "not found" sentinel -- and the caller raises
 * UnsatisfiedLinkError with the requested name attached.
 *
 * ==========================================================================
 * File name
 * ==========================================================================
 *
 * BytecodeToIr.findJNativeFileOwner and Compiler.hasNativeSupport probe
 * for `jnative/<package>/<SimpleClassName>.c`. The class is
 * NativeLibrary (singular); keeping the body in NativeLibrary.c means
 * both probes succeed and the linker sees the mangled __jnative_fn_*
 * symbol emitted by the LLVM backend.
 */

#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <string.h>

#include "jnative_runtime.h"

int64_t __jnative_fn_jdk_internal_loader_NativeLibrary_findEntry0__JLjava_lang_String__J(
        int64_t handle, void* name_str)
{
    if (name_str == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    /*
     * Native symbol names are ASCII identifiers; the runtime does not
     * assume a NUL terminator inside the Java String's payload (it has
     * none by construction for strings built by Arrays.copyOf etc.).
     * Copy into a NUL-terminated stack buffer.
     */
    int32_t len = 0;
    const char* name = __jnative_read_string_bytes(name_str, &len);
    if (name == NULL || len <= 0) return 0;
    if (len >= 512) return 0;

    char buf[512];
    memcpy(buf, name, (size_t)len);
    buf[len] = '\0';

    void* sym = NULL;

    /*
     * The handle is either the process-image handle stored by
     * NativeLibraries.load, or (defensively) some other dlopen /
     * LoadLibrary handle. `dlsym` on both platforms resolves the
     * symbol against the given handle.
     */
    if (handle != 0 && handle != (int64_t)-1) {
        sym = dlsym((void*)(intptr_t)handle, buf);
    }

    /* Fall back to the process image if the handle did not yield a
     * symbol. This covers the (currently unreachable) case where the
     * handle was invalidated between load and findEntry0. */
    if (sym == NULL) {
        sym = jn_dlsym_default(buf);
    }

    return (int64_t)(intptr_t)sym;
}
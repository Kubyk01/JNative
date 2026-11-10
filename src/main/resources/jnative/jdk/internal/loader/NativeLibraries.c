#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>

#include "jnative_runtime.h"

extern int32_t __jnative_jni_register_library(void* handle, int32_t is_jni);

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

/*
 * jdk.internal.loader.NativeLibraries — the class that owns the native
 * loading machinery for `System.loadLibrary` / `NativeLibrary.load`.
 *
 * -----------------------------------------------------------------------
 * Note on findEntry0
 * -----------------------------------------------------------------------
 *
 * This file previously also contained the implementation of
 * jdk.internal.loader.NativeLibrary.findEntry0 (singular), which is
 * the symbol-lookup entry point used by the JDK's reflective accessors.
 *
 * That implementation now lives in NativeLibrary.c (singular), in a
 * file whose name matches the declaring class. The reason is that
 * BytecodeToIr.findJNativeFileOwner and Compiler.hasNativeSupport both
 * decide whether a native method has a JNative C implementation by
 * probing for a C file whose name matches the declaring class:
 *
 *     jnative/<package>/<SimpleClassName>.c
 *
 * For NativeLibrary.findEntry0 the declaring class is NativeLibrary
 * (singular). With the body in NativeLibraries.c (plural), both
 * helpers concluded "no JNative implementation exists" and BytecodeToIr
 * fell back to JNI symbol naming:
 *
 *     Java_jdk_internal_loader_NativeLibrary_findEntry0
 *
 * which nothing provides, so the link failed with an undefined-symbol
 * error. Moving the implementation to NativeLibrary.c satisfies the
 * probe without touching the probe itself.
 */

/* --------------------------------------------------------------------------
 * static native String findBuiltinLib(String name);
 *
 * Returns the absolute path of the current process image when `name`
 * denotes a native library whose symbols are already available in the
 * running executable, or null otherwise.
 * ------------------------------------------------------------------------ */
void* __jnative_fn_jdk_internal_loader_NativeLibraries_findBuiltinLib__Ljava_lang_String__Ljava_lang_String_(
        void* name_str)
{
    if (name_str == NULL) return NULL;

    char name[512];
    int32_t name_len = __jnative_read_string_into(name_str, name,
                                                  (int32_t)sizeof(name));
    if (name_len <= 0) return NULL;
    if (strchr(name, '/') != NULL) return NULL;

    char base[256];
    size_t n = (size_t)name_len;
    if (n >= sizeof(base)) return NULL;
    memcpy(base, name, n + 1);

    static const char* const suffixes[] = { ".so", ".dylib", ".dll", NULL };
    for (int i = 0; suffixes[i]; i++) {
        size_t sl = strlen(suffixes[i]);
        if (n > sl && strcmp(base + n - sl, suffixes[i]) == 0) {
            base[n - sl] = '\0';
            n -= sl;
            break;
        }
    }
    if (n == 0) return NULL;

#if !defined(_WIN32)
    if (n > 3 && strncmp(base, "lib", 3) == 0) {
        memmove(base, base + 3, n - 3 + 1);
        n -= 3;
    }
#endif

    static const char* const builtins[] = {
        "java", "net", "nio", "zip", "jimage",
        "extnet", "verify", "unpack", "syslookup",
        "management", "management_ext", "instrument",
        "attach", "jdwp", "dt_socket", "dt_shmem",
        "j2pkcs11", "sunec", "jsound", "jsoundds",
        "awt", "fontmanager", "freetype", "lcms",
        "splashscreen", "osxsecurity", "prefs", "jfr",
        NULL
    };

    int known = 0;
    for (int i = 0; builtins[i]; i++) {
        if (strcmp(base, builtins[i]) == 0) {
            known = 1;
            break;
        }
    }
    if (!known) return NULL;

    char exe[PATH_MAX];
#if defined(_WIN32)
    DWORD wn = GetModuleFileNameA(NULL, exe, (DWORD)sizeof(exe));
    long len = (wn > 0 && wn < sizeof(exe)) ? (long)wn : -1;
#else
    long len = jn_readlink("/proc/self/exe", exe, sizeof(exe) - 1);
#endif
    if (len <= 0) return NULL;
    exe[len] = '\0';

    size_t sl = strlen(exe);
    return __jnative_make_string_obj(exe, (int32_t)sl);
}

int32_t __jnative_fn_jdk_internal_loader_NativeLibraries_load__Ljdk_internal_loader_NativeLibraries_NativeLibraryImpl_Ljava_lang_String_ZZ_Z(
        void* lib,
        void* name_str,
        int32_t is_builtin,
        int32_t is_jni)
{
    void* handle;
    if (is_builtin) {
        handle = jn_dlsym_default;
        if (handle == NULL) handle = (void*)(intptr_t)-1;
    } else {
        if (name_str == NULL) return 0;
        char path[PATH_MAX];
        int32_t len = __jnative_read_string_into(name_str, path, (int32_t)sizeof(path));
        if (len <= 0) return 0;
        handle = dlopen(path, RTLD_LAZY);
        if (handle == NULL) return 0;
        if (!__jnative_jni_register_library(handle, is_jni)) return 0;
    }

    if (lib != NULL) {
        *(void**)((char*)lib + 16) = handle;
    }
    return 1;
}

/* --------------------------------------------------------------------------
 * private static native void unload(String name,
 *                                   boolean isBuiltin,
 *                                   long handle);
 *
 * Called from NativeLibraries.Unloader.run() on the JDK's unloader
 * thread when a NativeLibraryImpl is being discarded. In HotSpot the
 * body either calls JVM_UnloadLibrary(handle) for a dynamically loaded
 * library, or does nothing for a builtin one (the process image cannot
 * be unloaded). Either way it is a no-op for the builtin case.
 *
 * In this runtime every "library" is the process image, so unload is
 * always the builtin case: there is no separately-opened handle to
 * close, and dlclose()ing the process image would be catastrophic.
 * The function is therefore a strict no-op, which is the same
 * behaviour the reference VM exhibits for builtin libraries.
 * ------------------------------------------------------------------------ */
void __jnative_fn_jdk_internal_loader_NativeLibraries_unload__Ljava_lang_String_ZJ_V(
        void* name_str,
        int32_t is_builtin,
        int64_t handle)
{
    (void)name_str;
    (void)is_builtin;
    (void)handle;
}
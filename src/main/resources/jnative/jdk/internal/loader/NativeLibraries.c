#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include <unistd.h>
#include <dlfcn.h>

#include "jnative_runtime.h"

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

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

    int32_t nameLen = 0;
    const char* name = __jnative_read_string_bytes(name_str, &nameLen);
    if (name[0] == '\0' || nameLen <= 0) return NULL;

    if (strchr(name, '/') != NULL) return NULL;

    char base[256];
    size_t n = (size_t)nameLen;
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
    ssize_t len = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (len <= 0) return NULL;
    exe[len] = '\0';

    size_t sl = strlen(exe);
    return __jnative_make_string_obj(exe, (int32_t)sl);
}

/* --------------------------------------------------------------------------
 * private static native boolean load(NativeLibraryImpl lib,
 *                                    String name,
 *                                    boolean isBuiltin,
 *                                    boolean isJNI);
 *
 * The JDK's NativeLibraryImpl.open() invokes this native to actually
 * acquire the library. In HotSpot the body calls JVM_LoadLibrary /
 * JVM_FindLibraryEntry to dlopen the shared object, stores the
 * resulting process handle into the lib's `handle` field, performs
 * JNI_OnLoad-style registration when isJNI is true, and returns
 * JNI_TRUE on success. The Java-side wrapper then sets `loaded = true`
 * on the lib object.
 *
 * In this runtime there is no dynamic loading step: every native
 * symbol the compiled image needs is already linked into the process
 * image, and the JNI registration path is never taken (natives are
 * resolved statically by the LLVM backend). The one piece of state
 * that still matters is the lib's `handle` field, because any
 * subsequent JNI-style `findEntry0(handle, symbol)` call would use it
 * as the first argument to dlsym. We therefore store a handle to the
 * running process image (dlopen(NULL, RTLD_LAZY)) in that field, which
 * is exactly what the reference implementation uses for the builtin
 * (non-isJNI) case.
 *
 * Field layout of NativeLibraryImpl as computed by
 * LlvmGlobalEmitter.getFieldOffset, given the JDK's declaration order
 * (name, handle, loaded) and the runtime's alignment rules:
 *
 *     offset  0 : i8*     vtable
 *     offset  8 : String  name
 *     offset 16 : long    handle
 *     offset 24 : boolean loaded     (single byte, no padding needed)
 *
 * Only the handle field is written; the Java wrapper is responsible
 * for `loaded`, matching the reference VM's division of labour.
 * ------------------------------------------------------------------------ */
int32_t __jnative_fn_jdk_internal_loader_NativeLibraries_load__Ljdk_internal_loader_NativeLibraries_NativeLibraryImpl_Ljava_lang_String_ZZ_Z(
        void* lib,
        void* name_str,
        int32_t is_builtin,
        int32_t is_jni)
{
    (void)name_str;
    (void)is_builtin;
    (void)is_jni;

    if (lib != NULL) {
        void* handle = dlopen(NULL, RTLD_LAZY);
        if (handle == NULL) {
            /* The process image handle is the only handle this runtime
             * ever needs; failure here means the process is already in
             * a state where nothing else can work. Use a sentinel so the
             * field is non-zero and dlsym() lookups surface as "not
             * found" rather than crashing on a null handle. */
            handle = (void*)(intptr_t)-1;
        }
        *(void**)((char*)lib + 16) = handle;
    }

    /* Report success. Every symbol the Java layer will subsequently try
     * to look up is present in the process image, so there is no
     * scenario in which the library "failed to load" here. */
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
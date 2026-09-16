#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include <unistd.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

/*
 * static native String findBuiltinLib(String name);
 *
 * Returns the absolute path of the current process image when `name`
 * denotes a native library whose symbols are already available in the
 * running executable, or null otherwise.
 *
 * In this runtime every JDK native library's C implementation is
 * compiled and linked directly into the executable together with the
 * LLVM-generated code (see Analyzer.compileAndLink and the -rdynamic
 * linker flag it passes on Linux). There are no separate .so files on
 * disk for names such as "net", "nio" or "zip": all their symbols
 * already live in the executable and are reachable through
 * dlopen(NULL)/dlsym. The correct contract for the loader is therefore
 * to treat those names as built-in and hand back the executable path;
 * the loader's subsequent dlopen of that path succeeds and returns a
 * handle whose symbol table contains everything the JNI machinery
 * looks for.
 *
 * Any name that contains a path separator, an empty base after
 * stripping the platform suffix, or a name that does not match a
 * known JDK library is not built-in; the caller falls through to the
 * platform loader for those.
 */
void* __jnative_fn_jdk_internal_loader_NativeLibraries_findBuiltinLib__Ljava_lang_String__Ljava_lang_String_(
        void* this_libs, void* name_str)
{
    (void)this_libs;

    if (name_str == NULL) return NULL;

    const char* name = (const char*)name_str;
    if (name[0] == '\0') return NULL;

    if (strchr(name, '/') != NULL) return NULL;

    char base[256];
    size_t n = strlen(name);
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
    char* result = (char*)malloc(sl + 1);
    if (!result) return NULL;
    memcpy(result, exe, sl + 1);
    return result;
}
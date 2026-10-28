#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <limits.h>

#include "jnative_runtime.h"

/*
 * Optional helper: the path of the running executable.
 *
 * Used here only by the documentation comment above; it is also the
 * entry point a future revision would call if it decided to build a real
 * CodeSource from the process image instead of returning null. Both
 * branches are portable and both are correct on every target the runtime
 * supports today:
 *
 *   POSIX  -> /proc/self/exe    (Linux, /proc mounted)  or readlink target
 *   macOS  -> /proc/self/exe    does not exist; the runtime's own
 *                               jn_readlink falls back through the
 *                               portability layer
 *   _WIN32 -> GetModuleFileNameA(NULL, ...)
 *
 * __attribute__((unused)) keeps -Werror builds honest on toolchains that
 * enable unused-function warnings.
 */
__attribute__((unused))
static const char* running_executable_path(void) {
    static char path[PATH_MAX];
    static int  initialized = 0;
    if (initialized) return path;
    initialized = 1;

#if defined(_WIN32)
    DWORD n = GetModuleFileNameA(NULL, path, (DWORD)sizeof(path));
    if (n == 0 || n >= sizeof(path)) path[0] = '\0';
#else
    ssize_t n = jn_readlink("/proc/self/exe", path, sizeof(path) - 1);
    if (n <= 0) path[0] = '\0';
    else        path[n] = '\0';
#endif
    return path;
}

/* ============================================================================
 *  Public natives
 * ========================================================================== */

/*
 * static native ProtectionDomain getProtectionDomain0(Class<?> cl);
 *
 * Returns null. See the class-level comment above: without a per-class
 * PD store the only truthful answer is "no explicit PD for this class",
 * and Class.getProtectionDomain() transparently substitutes the
 * AllPermission domain in that case. Returning null is therefore
 * behaviourally identical to returning the domain the JDK would have
 * built, without constructing Java objects from C.
 */
void* __jnative_fn_java_security_ProtectionDomain_getProtectionDomain0__Ljava_lang_Class__Ljava_security_ProtectionDomain_(
        void* clazz) {
    (void)clazz;
    return NULL;
}

/*
 * static native ProtectionDomain getProtectionDomain(Class<?> cl);
 *
 * Same contract as getProtectionDomain0. The two entry points exist as
 * separate natives because the JDK's own Class.java declares both; both
 * may be reached from different reflective paths, and both must behave
 * identically.
 */
void* __jnative_fn_java_security_ProtectionDomain_getProtectionDomain__Ljava_lang_Class__Ljava_security_ProtectionDomain_(
        void* clazz) {
    (void)clazz;
    return NULL;
}

/*
 * private static native void setProtectionDomain0(ProtectionDomain pd,
 *                                                 Class<?> cl);
 *
 * Called by ProtectionDomain.install() when a class loader installs a
 * PD for a freshly-loaded class. There is no per-class PD store in this
 * runtime, and no code path that would install one: every class is
 * compiled into the image at build time. A no-op is therefore the
 * correct implementation — the call cannot fail and cannot change
 * observable behaviour, because the field it would have written does not
 * exist.
 */
void __jnative_fn_java_security_ProtectionDomain_setProtectionDomain0__Ljava_security_ProtectionDomain_Ljava_lang_Class__V(
        void* pd, void* clazz) {
    (void)pd;
    (void)clazz;
}
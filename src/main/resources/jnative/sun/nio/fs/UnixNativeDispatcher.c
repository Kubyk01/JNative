#include <stdint.h>

/* static native int init();  — called from UnixNativeDispatcher.<clinit>.
 * Returns the st_mode of the current file-system's ... this runtime does not
 * emulate UnixFileAttributes, so returning -1 (unsupported) makes the Java
 * layer fall back to the pure-Java path. */
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_init___I(void) {
    return -1;
}
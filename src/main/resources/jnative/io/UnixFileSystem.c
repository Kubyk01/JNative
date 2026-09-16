#include <stdint.h>

/*
 * private static native void initIDs();
 *
 * Called from the static initializer of java.io.UnixFileSystem to cache
 * the JNI field IDs of the class's instance fields (slash, colon,
 * javaHome, userDir, cache, ...). This runtime does not use JNI field
 * IDs anywhere: instance fields are accessed directly through their
 * LLVM-computed byte offsets, so there is nothing to cache. The symbol
 * must exist because the class's <clinit> emits a native call to it.
 */
void __jnative_fn_java_io_UnixFileSystem_initIDs___V(void) {
}
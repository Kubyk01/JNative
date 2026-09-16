#include <stdint.h>

/*
 * static native boolean isPreviewEnabled();
 *
 * Returns true iff the running VM was started with preview features
 * enabled (--enable-preview). Preview features are a HotSpot mechanism
 * tied to the class-file version check performed at class load time
 * and to the Java-level state of PreviewFeatures itself. This runtime
 * neither recognises the --enable-preview flag nor enforces the
 * class-file version constraint it guards: every class is loaded from
 * its own .class bytes and executed as-is. The correct answer is
 * therefore false, which makes Java callers treat all preview-flagged
 * classes as unavailable for use, matching the actual capability of
 * the runtime.
 */
int32_t __jnative_fn_jdk_internal_misc_PreviewFeatures_isPreviewEnabled___Z(void) {
    return 0;
}
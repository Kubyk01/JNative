#include <stdint.h>

/*
 * static void initializeFromArchive(Class<?> c);
 *
 * Called from <clinit> of classes that participate in the class-data
 * sharing archive (sun.util.locale.BaseLocale, java.lang.Character,
 * java.lang.Integer, java.time.*, etc.). In a HotSpot build with an
 * archive mapped at startup, this hook copies the archived static field
 * values into the freshly-initialised class. This runtime has no CDS
 * archive and no archive-mapped heap: the static initializer of each
 * class has already produced every value the class needs, so there is
 * nothing to overwrite. The parameter is therefore unused, and the
 * function exists only so the call from <clinit> links.
 */
void __jnative_fn_jdk_internal_misc_CDS_initializeFromArchive__Ljava_lang_Class__V(
        void* clazz)
{
    (void)clazz;
}

/*
 * static native boolean isDumpingClassList0();
 *
 * True iff the current process is running in the class-list dumping
 * mode (-Xshare:dump with -XX:DumpLoadedClassList). This runtime never
 * dumps a class list, so the answer is always false. The Java caller
 * uses the result to decide whether to walk the loaded-class set and
 * write it to a file; returning false suppresses that path entirely,
 * which is the correct behaviour for an image that has no archive to
 * build.
 */
int32_t __jnative_fn_jdk_internal_misc_CDS_isDumpingClassList0___Z(void) {
    return 0;
}

/*
 * static native boolean isDumpingArchive0();
 *
 * True iff the current process is running in archive-dumping mode
 * (-Xshare:dump). This runtime has no archive to produce, so the
 * answer is always false. The Java caller uses the result to decide
 * whether to add each loaded class to the archive-in-progress; with
 * false, the archive-assembly bookkeeping is skipped entirely.
 */
int32_t __jnative_fn_jdk_internal_misc_CDS_isDumpingArchive0___Z(void) {
    return 0;
}

/*
 * static native boolean isSharingEnabled0();
 *
 * True iff the VM has a mapped CDS archive from which class data can
 * be shared. This runtime loads every class directly from its .class
 * bytes and never consults a shared archive, so the answer is always
 * false. The Java caller uses the result to decide whether to look up
 * a class's archived image before loading it; with false, it takes the
 * ordinary loading path.
 */
int32_t __jnative_fn_jdk_internal_misc_CDS_isSharingEnabled0___Z(void) {
    return 0;
}
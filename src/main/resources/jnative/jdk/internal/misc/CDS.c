#include <stdint.h>
#include <stddef.h>
#include <time.h>
#include <unistd.h>

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

/*
 * static native long getRandomSeedForDumping();
 *
 * Returns the random seed that the CDS dumping process uses to
 * reproduce a deterministic archive across runs. When the VM is
 * started with -Xshare:dump and given a fixed seed (via the
 * jdk.internal.cds.randomSeed system property, which is set by
 * -XX:SharedArchiveFile=... plus a `-D` override), the archive's
 * contents — including the hash codes it embeds for shared objects —
 * are reproducible. Otherwise the VM draws a fresh seed from the
 * platform's entropy source.
 *
 * This runtime never dumps a CDS archive: isDumpingArchive0() always
 * returns false, so the Java layer's dumping path is not taken and
 * this native is normally unreachable. It is nevertheless invoked
 * unconditionally from the static initializer of
 * java.util.ImmutableCollections (and a handful of other collection
 * classes that are eligible for CDS-style sharing of their EMPTY_*
 * singleton instances), where the result is stored into a package-
 * private field for use by the copy-on-write machinery during class
 * data sharing.
 *
 * Because no archive is ever produced, the exact value is irrelevant
 * — no caller can observe a difference between any two seeds. We
 * return a fixed non-zero value (42) rather than attempting to read
 * entropy, which keeps the call cheap and, more importantly, keeps the
 * result stable across runs. A stable seed is the safer choice for a
 * runtime that has no archive to build: it means that if any code path
 * ever did compare two seeds for consistency, the comparison would
 * succeed rather than fail on a per-run basis. The reference VM's
 * "no archive" state has the same property, since isDumpingArchive0()
 * short-circuits every consumer before the seed is consulted.
 *
 * Return type is jlong (Java long), i.e. int64_t in this ABI.
 */
int64_t __jnative_fn_jdk_internal_misc_CDS_getRandomSeedForDumping___J(void) {
    return (int64_t)42;
}

/*
 * static native void logLambdaFormInvoker(String line);
 *
 * A CDS-tracing hook that writes a single line to the lambda-form
 * invoker log when the VM has been started with
 * -XX:+ShowHiddenFrames or one of the other lambda-form tracing
 * options. The Java layer routes every trace event through this native
 * so the log file's lifetime and flushing are managed by the VM.
 *
 * This runtime has no lambda-form interpreter (see MethodHandle.c) and
 * never creates the log file that this hook would append to, so there
 * is nothing to write and no file to open. The parameter is
 * deliberately unused. The function must nevertheless exist because
 * two distinct call sites emit a direct native call to it:
 *
 *   - jdk.internal.misc.CDS.traceSpeciesType(String, String), which
 *     logs the species type whenever a BoundMethodHandle species class
 *     is generated or loaded;
 *
 *   - java.lang.invoke.ClassSpecializer$Factory.loadSpecies(...),
 *     which logs the same information from the class-specializer side
 *     when a species class is first defined.
 *
 * Both call sites are unconditionally reached while a MethodHandle is
 * being constructed, so the symbol has to be present at link time
 * regardless of whether the tracing option is set. Making it a no-op
 * is the only correct behaviour: there is no log to write to and no
 * observer that would consume the line.
 */
void __jnative_fn_jdk_internal_misc_CDS_logLambdaFormInvoker__Ljava_lang_String__V(
        void* line)
{
    (void)line;
}

/*
 * static void defineArchivedModules(ClassLoader platformLoader,
 *                                   ClassLoader appLoader);
 *
 * Called once from jdk.internal.module.ModuleBootstrap.boot during VM
 * startup, after the module system has installed the boot layer's
 * resolver. In a HotSpot build with a mapped CDS archive it walks the
 * archived module graph and registers each archived module descriptor
 * with the platform or application class loader, so that
 * ClassLoader.getDefinedModule and the module-aware access checks can
 * find them without a fresh round of parsing.
 *
 * This runtime has no CDS archive: the module graph that the boot layer
 * exposes is built entirely from the constant ModuleDescriptor objects
 * that ModuleBootstrap synthesises in Java, and there are no archived
 * module descriptors to register with anything. Both class loader
 * arguments are therefore ignored, and the function is a strict no-op.
 *
 * The symbol must nevertheless exist because ModuleBootstrap.boot emits
 * a direct native call to it — that call site is unconditional in the
 * reference implementation, regardless of whether CDS is enabled, so
 * the linker needs the definition on every target.
 */
void __jnative_fn_jdk_internal_misc_CDS_defineArchivedModules__Ljava_lang_ClassLoader_Ljava_lang_ClassLoader__V(
        void* platform_loader, void* app_loader)
{
    (void)platform_loader;
    (void)app_loader;
}
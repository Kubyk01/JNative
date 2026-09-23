#include <stdint.h>

#include "jnative_runtime.h"

/*
 * java.lang.Module — native methods.
 *
 * This runtime has a single, flat universe of classes: the reachability
 * analysis pulls every class that the entry point can observe into one
 * compiled image, and there is no user-defined module layer, no module
 * path, and no resolver that could partition those classes into
 * distinct named modules. Every class belongs to the unnamed module of
 * the bootstrap loader, and every package is open to every other
 * package.
 *
 * The natives below are the VM-level hooks that java.lang.Module uses to
 * install module descriptors during startup. In the reference JDK they
 * populate the VM's module graph — exports maps, opens maps, reads
 * edges, and the module's own identity (name, version, location, and
 * whether it is open). In this runtime there is no such graph: access
 * checks in java.lang.reflect and sun.invoke.util.VerifyAccess are
 * driven entirely by class-level flags (see the getClassAccessFlags /
 * areNestMates implementations in jdk/internal/reflect/Reflection.c),
 * so the module graph is never consulted.
 *
 * Every entry point below is therefore a no-op. This is exactly the
 * behaviour the reference JDK exhibits for the unnamed module, and it
 * is the only well-defined behaviour available for a runtime that has
 * no module graph to populate. Keeping the symbols present lets the
 * Module class's own methods link, and lets the JDK's startup
 * sequence run to completion without touching uninitialised VM state.
 */

/*
 * private static native void defineModule0(Module module,
 *                                          boolean isOpen,
 *                                          String version,
 *                                          String location,
 *                                          Object[] pkgNames);
 *
 * Called from Module's private constructor to install the module's
 * descriptor on the VM side. The reference implementation stashes the
 * version/location strings and registers the module in the VM's module
 * table; this runtime has no module table, and the Module object itself
 * already carries everything Java code can observe (name, descriptor,
 * and the exports/opens/reads maps that initExports/initReads filled in
 * from the descriptor at construction time). Nothing to install.
 */
void __jnative_fn_java_lang_Module_defineModule0__Ljava_lang_Module_ZLjava_lang_String_Ljava_lang_String__Ljava_lang_Object__V(
        void* module,
        int32_t is_open,
        void* version,
        void* location,
        void* pkg_names)
{
    (void)module;
    (void)is_open;
    (void)version;
    (void)location;
    (void)pkg_names;
}

/*
 * private static native void addReads0(Module from, Module to);
 *
 * Installs a reads edge from `from` to `to` in the VM's module graph.
 * The graph is not consulted anywhere in this runtime, so the edge is
 * not recorded. Java-side bookkeeping (Module.implAddReads) already
 * updates the Module object's own reads set before calling this native,
 * so callers that query the reads set at the Java level observe the
 * correct answer regardless.
 */
void __jnative_fn_java_lang_Module_addReads0__Ljava_lang_Module_Ljava_lang_Module__V(
        void* from,
        void* to)
{
    (void)from;
    (void)to;
}

/*
 * private static native void addExports0(Module from,
 *                                        String pn,
 *                                        Module to);
 *
 * Adds a qualified export: package `pn` of module `from` is exported to
 * module `to`. There is no package-visibility restriction in this
 * runtime, so no per-(from, pn, to) entry needs to be recorded. The
 * Java-level Module object already tracks the export map that
 * java.lang.Module's own observers (Module.getExports / isExported)
 * consult; this native only feeds the VM-side mirror of that map, which
 * nothing reads.
 */
void __jnative_fn_java_lang_Module_addExports0__Ljava_lang_Module_Ljava_lang_String_Ljava_lang_Module__V(
        void* from,
        void* pn,
        void* to)
{
    (void)from;
    (void)pn;
    (void)to;
}

/*
 * private static native void addExportsToAll0(Module from, String pn);
 *
 * Unqualified counterpart of addExports0: package `pn` of module `from`
 * is exported to every module. Same rationale — there is no VM-side
 * package-visibility gate that would need the entry, so the call is a
 * no-op. The Java-level export map is updated by the caller before the
 * native is invoked.
 */
void __jnative_fn_java_lang_Module_addExportsToAll0__Ljava_lang_Module_Ljava_lang_String__V(
        void* from,
        void* pn)
{
    (void)from;
    (void)pn;
}

/*
 * private static native void addExportsToAllUnnamed0(Module from, String pn);
 *
 * The third member of the addExports family: package `pn` of module
 * `from` is exported to every *unnamed* module — that is, to every
 * class loaded by a class loader that has no named module associated
 * with it.
 *
 * This is the variant the JDK uses most heavily during the transition
 * to the module system: `Module.implAddExportsOrOpens` is called with
 * `syncVM = true` from the reflective-access paths and from
 * `Module.implAddExportsToAllUnnamed` when the caller wants to grant
 * access to the entire unnamed module rather than to one specific
 * target module. It is the corresponding API to the
 * `--add-exports` / `--add-opens` command-line options.
 *
 * In this runtime there is no module graph at all: every class lives
 * in the unnamed module of the bootstrap loader, every package is
 * already visible to every other package, and the Java-level map that
 * the caller maintains (`implAddExportsOrOpens` updates it before
 * invoking this native) is the authoritative record of what has been
 * exported. The native call exists only to keep the VM's mirror of
 * that map in sync, and there is no VM-side mirror to update. The call
 * is therefore a strict no-op, exactly like addExports0 and
 * addExportsToAll0.
 *
 * The arguments are captured but not consulted. `from` is the source
 * module, `pn` is the package name in internal form (slashes, not
 * dots). Neither participates in any state this runtime maintains.
 */
void __jnative_fn_java_lang_Module_addExportsToAllUnnamed0__Ljava_lang_Module_Ljava_lang_String__V(
        void* from,
        void* pn)
{
    (void)from;
    (void)pn;
}
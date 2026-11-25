/*
 * javax.crypto.JceSecurity — native overrides.
 *
 * The Java-level implementation of JceSecurity.<clinit> reads the
 * jurisdiction policy files — $JAVA_HOME/conf/security/default.policy
 * and $JAVA_HOME/conf/security/exempt.policy (or their JDK 8
 * equivalents under lib/security/) — and installs the parsed
 * permissions into the two private static fields
 *
 *     private static CryptoPermissions defaultPolicy;
 *     private static CryptoPermissions exemptPolicy;
 *
 * Every JceSecurity consumer reads those fields through
 * getDefaultPolicy() and getExemptPolicy(). In a plain HotSpot
 * installation the files are present and the static initializer
 * completes without error.
 *
 * In a compiled JNative image two things are wrong with that flow:
 *
 *   1. The image runs on machines that may have no JDK installed at
 *      all, so $JAVA_HOME/conf/security may not exist. The call chain
 *
 *          JceSecurity.<clinit>
 *            -> AccessController.doPrivileged
 *            -> JceSecurity$1.run
 *            -> JceSecurity.setupJurisdictionPolicies
 *            -> Files.newInputStream(path)
 *            -> FileChannel.open
 *            -> openat0
 *            -> open(2) -> ENOENT
 *            -> __jnative_throw_exception(NULL)
 *
 *      raises a bare native exception whose object is NULL.
 *
 *   2. The try/catch region that would convert that exception into a
 *      SecurityException — the Java-level
 *
 *          try { ... }
 *          catch (Exception e) {
 *              throw new SecurityException(
 *                  "Couldn't parse jurisdiction policy files in: " + path, e);
 *          }
 *
 *      is emitted by the LLVM backend as a single common handler that
 *      tests catch (Throwable) first, pops both setjmp frames, and
 *      rethrows. The catch (Exception) branch is unreachable in the
 *      generated code (this is the secondary dispatch defect reported
 *      separately). The result is that the raw diagnostic Throwable
 *      from the NULL-throwing native propagates all the way out of
 *      JceSecurityManager.<clinit> and terminates the program.
 *
 * Both failure modes disappear if setupJurisdictionPolicies is not
 * allowed to run. The method's only observable side effect on the rest
 * of the runtime is the assignment of the two static fields above; the
 * override below performs those two assignments directly, with two
 * freshly constructed empty CryptoPermissions instances, and returns.
 *
 * An empty CryptoPermissions is indistinguishable, from the point of
 * view of every reachable caller, from a CryptoPermissions whose
 * load() has never been invoked with any file. Its internal
 * per-package map is non-null (it was initialised by the constructor)
 * and iterates zero times, so CryptoPermissions.getPermission(...)
 * returns null, CryptoPermissions.get(...) returns null, and the
 * jurisdiction-check machinery of JceSecurity treats all algorithms as
 * unrestricted. That is exactly the intended state of the runtime: it
 * has no SecurityManager, so no crypto jurisdiction restriction is
 * enforceable.
 *
 * The two static fields whose values are being replaced were assigned
 * by the *unmodified* JceSecurity.<clinit> path in the reference JDK
 * and are read from the *unmodified* JceSecurity.getDefaultPolicy() /
 * getExemptPolicy() bodies in the compiled image. Their global symbols
 * are emitted by LlvmGlobalEmitter.generateStaticFields() exactly
 * because those read sites are reachable, so the weak externs below
 * will always resolve to real storage in the final executable.
 *
 * The two symbols are:
 *
 *     gv_javax_crypto_JceSecurity_defaultPolicy
 *     gv_javax_crypto_JceSecurity_exemptPolicy
 *
 * derived from LlvmTypeMapper.sanitizeIdentifier applied to
 * "javax/crypto/JceSecurity.<field>".
 *
 * ----------------------------------------------------------------------
 * Native override registration
 * ----------------------------------------------------------------------
 *
 * The symbol name follows NativeOverrideScanner's convention:
 *
 *     __jnative_override_<mangled-class>_<method>
 *
 * with the mangled class using '_' in place of '/'. For
 * javax.crypto.JceSecurity that is javax_crypto_JceSecurity, giving:
 *
 *     __jnative_override_javax_crypto_JceSecurity_setupJurisdictionPolicies
 *
 * No descriptor suffix is attached: the descriptor argument of the
 * scanner's NativeOverride is left null, which makes the override match
 * every overload. setupJurisdictionPolicies is declared as
 *
 *     private static void setupJurisdictionPolicies() throws Exception
 *
 * so the Java-level descriptor is "()V" and the mangled alias that
 * BytecodeToIr registers onto this C body is
 *
 *     fn_javax_crypto_JceSecurity_setupJurisdictionPolicies___V
 *
 * ----------------------------------------------------------------------
 * Calling convention note
 * ----------------------------------------------------------------------
 *
 * The parameter list is written as empty parentheses, not as (void).
 * NativeOverrideScanner treats the raw string between '(' and ')' as a
 * comma-separated list of C parameter declarations; the single token
 * "void" would be parsed as one parameter of type void and would
 * produce an invalid LLVM function type ("void @f(void)"). Empty
 * parentheses produce zero parameters, which is what a static method
 * override requires.
 */

#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>

#include "jnative_runtime.h"

/*
 * Look up a symbol in the running process image. Used to call the
 * CryptoPermissions no-argument constructor without forcing a
 * compile-time dependency on its mangled name. Returns NULL when the
 * symbol is absent, which is not an error: the caller then falls back
 * to a manually initialised internal map.
 */
static void* lookup_symbol(const char* name) {
    void* handle = jn_dlsym_default;
    if (handle == NULL) return NULL;
    void* sym = jn_dlsym_default( name);
    return sym;
}

/*
 * Weak externs for the two static fields the original
 * setupJurisdictionPolicies would have assigned. The addresses are
 * checked before every write so that a build in which the emitter did
 * not materialise one of them does not corrupt the process image.
 */
extern void* gv_javax_crypto_JceSecurity_defaultPolicy __attribute__((weak));
extern void* gv_javax_crypto_JceSecurity_exemptPolicy  __attribute__((weak));

/*
 * Build a fresh empty CryptoPermissions instance.
 *
 * The object is allocated through jnative_alloc_object(), which uses
 * the ReflectionClass's object_size and writes the correct vtable into
 * word 0 — the same allocation path the generated NEW instruction would
 * have used. The no-argument constructor is then invoked directly
 * through its mangled symbol, which is present in every image that
 * contains JceSecurityManager.<clinit> because that method also
 * constructs a CryptoPermissions (CACHE_NULL_MARK).
 *
 * If the constructor symbol cannot be resolved, its only observable
 * effect — the initialisation of the internal per-package map — is
 * performed here by hand, with the field located and typed through the
 * reflection metadata.
 */
static void init_crypto_permissions_map(ReflectionClass* cp_cls, void* cp) {
    ReflectionField* perms = NULL;
    for (ReflectionClass* c = cp_cls; c != NULL && perms == NULL; c = c->superclass) {
        for (int i = 0; c->fields != NULL && c->fields[i] != NULL; i++) {
            if (strcmp((const char*)c->fields[i]->name, "perms") == 0) {
                perms = c->fields[i];
                break;
            }
        }
    }
    const char* desc = perms != NULL ? (const char*)perms->descriptor : NULL;
    size_t len = desc != NULL ? strlen(desc) : 0;
    char map_class[256];
    char ctor_symbol[320];
    if (len < 3 || desc[0] != 'L' || desc[len - 1] != ';' || len - 2 >= sizeof(map_class)) {
        __jnative_throw_exception(__jnative_construct_exception("vtable_java_lang_InternalError",
            "CryptoPermissions.perms is missing from the reflection metadata"));
    }
    memcpy(map_class, desc + 1, len - 2);
    map_class[len - 2] = '\0';
    int n = snprintf(ctor_symbol, sizeof(ctor_symbol), "fn_%s__init____V", map_class);
    for (int i = 3; i < n; i++) {
        char ch = ctor_symbol[i];
        if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_')) {
            ctor_symbol[i] = '_';
        }
    }
    ReflectionClass* map_cls = jnative_class_by_name(map_class);
    void (*map_ctor)(void*) = (void (*)(void*))lookup_symbol(ctor_symbol);
    void* map = map_cls != NULL ? jnative_alloc_object(map_cls) : NULL;
    if (map == NULL || map_ctor == NULL) {
        __jnative_throw_exception(__jnative_construct_exception("vtable_java_lang_InternalError",
            "CryptoPermissions.perms cannot be constructed"));
    }
    map_ctor(map);
    void* old_map = *(void**)((char*)cp + perms->offset);
    __jnative_rc_inc(map);
    *(void**)((char*)cp + perms->offset) = map;
    __jnative_rc_dec(old_map);
}

static void* make_empty_crypto_permissions(void) {
    ReflectionClass* cp_cls =
        jnative_class_by_name("javax/crypto/CryptoPermissions");
    if (cp_cls == NULL) {
        __jnative_throw_exception(NULL);
        return NULL;
    }

    void* cp = jnative_alloc_object(cp_cls);
    if (cp == NULL) {
        __jnative_throw_out_of_memory_error_ctx(
            "JceSecurity.setupJurisdictionPolicies");
        return NULL;
    }

    typedef void (*noarg_ctor_t)(void*);
    noarg_ctor_t ctor = (noarg_ctor_t)lookup_symbol(
        "fn_javax_crypto_CryptoPermissions__init____V");

    if (ctor != NULL) {
        ctor(cp);
    } else {
        init_crypto_permissions_map(cp_cls, cp);
    }

    return cp;
}

/*
 * Replacement for javax.crypto.JceSecurity.setupJurisdictionPolicies().
 *
 * Installs two empty CryptoPermissions instances into the two static
 * fields that every other part of JceSecurity reads, and returns
 * immediately. No file is opened, no stream is created, no decoder is
 * initialised, and no policy parser runs.
 */
void __jnative_override_javax_crypto_JceSecurity_setupJurisdictionPolicies() {
    void* default_policy = make_empty_crypto_permissions();
    void* exempt_policy  = make_empty_crypto_permissions();

    if (&gv_javax_crypto_JceSecurity_defaultPolicy != NULL) {
        void* old_default = gv_javax_crypto_JceSecurity_defaultPolicy;
        __jnative_rc_inc(default_policy);
        gv_javax_crypto_JceSecurity_defaultPolicy = default_policy;
        __jnative_rc_dec(old_default);
    }
    if (&gv_javax_crypto_JceSecurity_exemptPolicy != NULL) {
        void* old_exempt = gv_javax_crypto_JceSecurity_exemptPolicy;
        __jnative_rc_inc(exempt_policy);
        gv_javax_crypto_JceSecurity_exemptPolicy  = exempt_policy;
        __jnative_rc_dec(old_exempt);
    }
}
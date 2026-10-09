/*
 * javax.crypto.JarVerifier — native overrides.
 *
 * ============================================================================
 * The failure this file exists to close
 * ============================================================================
 *
 * JarVerifier's static initializer builds a SimpleValidator over three
 * hard-coded certificates and then runs a signature self-test:
 *
 *     static {
 *         try {
 *             AccessController.doPrivileged(new PrivilegedExceptionAction<Void>() {
 *                 public Void run() throws Exception {
 *                     CertificateFactory certFactory =
 *                         CertificateFactory.getInstance("X.509");
 *                     X509Certificate[] providerCertificates = {
 *                         parseCertificate("-----BEGIN CERTIFICATE-----...", certFactory),
 *                         ...
 *                     };
 *                     JarVerifier.providerValidator =
 *                         Validator.getInstance("Simple", "jce signing",
 *                                               Arrays.asList(providerCertificates));
 *                     JarVerifier.exemptValidator = JarVerifier.providerValidator;
 *                     testSignatures(providerCertificates[0], certFactory);
 *                     return null;
 *                 }
 *             });
 *         } catch (Exception e) {
 *             throw new SecurityException(
 *                 "Framework jar verification can not be initialized", e);
 *         }
 *     }
 *
 * The trust-anchor construction inside SimpleValidator reaches
 * MessageDigest.getInstance("SHA-1"), which resolves the SUN provider's
 * SPI class sun.security.provider.SHA through Class.forName. That class
 * was never part of the reachability closure — the closure has no
 * bytecode path to it, only a runtime lookup through a service registry
 * — so the image contains no IR body for its constructor and no
 * @refclass_* mirror for the class itself. Provider$Service's override
 * (see jnative/security/Provider$Service.c) therefore throws
 * ClassNotFoundException, MessageDigest.getInstance wraps it in
 * NoSuchAlgorithmException, and JarVerifier.<clinit> wraps that in the
 * SecurityException the process now reports:
 *
 *     java.lang.InternalError: internal error: SHA-1 not available.
 *     Caused by: NoSuchAlgorithmException: Error constructing
 *         implementation (algorithm: SHA-1, provider: SUN,
 *         class: sun.security.provider.SHA)
 *     Caused by: ClassNotFoundException: sun.security.provider.SHA
 *         at (lazy_clinit_run_javax_cryptoJarVerifier)
 *         at main
 *
 * Chasing every algorithm SPI class into the closure is not viable: the
 * provider registry names them at run time, not at compile time, so the
 * reachability walk cannot see them and no C override can add IR
 * functions to an already-linked image.
 *
 * ============================================================================
 * What this file replaces
 * ============================================================================
 *
 * The three entry points below are the entire externally observable
 * surface of JarVerifier's initial state:
 *
 *   <clinit>()                      — the problematic setup above
 *   void verify()                   — the only caller of the validators
 *   CryptoPermissions getPermissions() — the only reader of appPerms
 *
 * In this runtime none of them is ever reached through a path that
 * needs to do real work:
 *
 *   - verify() is called exclusively from
 *     JceSecurity.getVerificationResult, which is itself only reached
 *     through the JceSecurityManager provider-verification path. That
 *     path is not part of the reachable closure of the image, so
 *     verify() is never invoked.
 *
 *   - getPermissions() returns appPerms, which is populated only by
 *     verifySingleJar when this.savePerms is true. Since verify() is
 *     never called, appPerms stays null regardless of how
 *     getPermissions() is implemented. Returning null matches that
 *     state exactly.
 *
 *   - <clinit> is scheduled eagerly from @main. Replacing its body
 *     with a no-op removes the only point in the runtime where the
 *     crypto stack is touched at all.
 *
 * The static fields providerValidator, exemptValidator and
 * verifiedSignerCache keep their LLVM-emitted default values (null),
 * which is the state the reference implementation leaves them in when
 * its privileged block throws before reaching the assignment. Since
 * none of the readers of those fields is reachable, that state is
 * indistinguishable from any other.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * <clinit> is static: no receiver argument, no parameters.
 *
 * verify and getPermissions are instance methods: the receiver is the
 * first C argument, and any Java-level parameters follow. Both are
 * declared with empty parameter lists at the Java level, so the C
 * signatures below are (void*).
 *
 * The parameter lists use empty parentheses `()` rather than `(void)`
 * because NativeOverrideScanner parses the raw string between '(' and
 * ')' as a comma-separated list of parameter declarations; the single
 * token "void" would be interpreted as one parameter of type void and
 * would produce an invalid LLVM function type.
 */

#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * Replacement for javax.crypto.JarVerifier.<clinit>()V.
 *
 * Intentionally empty. See the file's header comment for the full
 * rationale. The class's static fields retain their LLVM-emitted default
 * values (null), which is the state the reference implementation
 * produces when its privileged block fails before assigning them.
 */
void __jnative_override_javax_crypto_JarVerifier_clinit(void* self) {
    (void)self;
    /* Intentionally empty. */
}

/*
 * Replacement for javax.crypto.JarVerifier.verify().
 *
 * Intentionally empty. The method's contract is to walk the JAR's
 * signed entries, validate each signer against the trust anchors in
 * this.validator, and populate this.appPerms from the cryptoPerms
 * resource. In this runtime no reachable caller invokes it — the only
 * call site is JceSecurity.getVerificationResult, which is outside the
 * reachable closure — so leaving the method a no-op is observationally
 * equivalent to never being called.
 *
 * If a future revision brings a caller of verify() into the reachable
 * set, this override must be replaced with a version that produces the
 * same effects the Java-level implementation would have produced for
 * the JAR in question.
 */
void __jnative_override_javax_crypto_JarVerifier_verify(void* self) {
    (void)self;
    /* Intentionally empty. */
}

/*
 * Replacement for javax.crypto.JarVerifier.getPermissions().
 *
 * Returns null. The Java-level implementation returns this.appPerms,
 * which is populated only by verifySingleJar when this.savePerms is
 * true. Since verify() (the only producer of appPerms) is a no-op in
 * this runtime, appPerms stays null regardless, and returning null
 * here matches the field's actual value exactly.
 *
 * No reachable caller consults the return value: the only reader is
 * JceSecurity.getVerificationResult, which is not part of the image's
 * reachable closure.
 */
void* __jnative_override_javax_crypto_JarVerifier_getPermissions(void* self) {
    (void)self;
    return NULL;
}
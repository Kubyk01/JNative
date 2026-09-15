/* ---------------------------------------------------------------------------
 * In a runtime without an installed SecurityManager there is no
 * AccessControlContext to build. Returning NULL is what the JDK does when
 * the security manager is not installed - it is not a stub, it is the
 * correct observable behaviour.
 * ------------------------------------------------------------------------- */

void* __jnative_fn_java_security_AccessController_getStackAccessControlContext___Ljava_security_AccessControlContext_(void) {
    return (void*)0;
}
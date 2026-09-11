#include <stddef.h>
/* ---------------------------------------------------------------------------
 * In a runtime without an installed SecurityManager there is no
 * AccessControlContext to build. Returning NULL is what the JDK does when
 * the security manager is not installed - it is not a stub, it is the
 * correct observable behaviour.
 * ------------------------------------------------------------------------- */

void* __jnative_fn_java_security_AccessController_getStackAccessControlContext___Ljava_security_AccessControlContext_(void) {
    return (void*)0;
}

/*
 * AccessController.getProtectionDomain returns the ProtectionDomain of a
 * Class. ProtectionDomain is a SecurityManager concept; this runtime
 * never installs a SecurityManager, so no protection domains exist and
 * the only correct answer is NULL. AccessController.getProtectionDomain
 * is only invoked when a SecurityManager is installed — in a runtime
 * without one, this code path is unreachable.
 */
void* __jnative_fn_java_security_AccessController_getProtectionDomain__Ljava_lang_Class__Ljava_security_ProtectionDomain_(
        void* clazz)
{
    (void)clazz;
    return NULL;
}
#define _GNU_SOURCE
#include <stddef.h>
#include <stdint.h>

#include "jnative_runtime.h"

/*
 * java.security.AccessController — the native entry points behind the
 * doPrivileged family.
 *
 * In HotSpot, AccessController.doPrivileged wraps the caller's action
 * in a Java-level privileged frame that the security manager consults
 * during subsequent permission checks. This runtime has no security
 * manager and no per-thread AccessControlContext stack: every
 * permission check that reaches the Java layer is answered with the
 * default "allowed" (see checkPermission below), so the privileged
 * frame has no observable effect on access decisions.
 *
 * What remains meaningful is that doPrivileged must still *invoke* the
 * caller's action — the action's own body is what the caller actually
 * wanted to run — and it must do so through the correct interface
 * dispatch (PrivilegedAction.run() returning Object, or
 * PrivilegedExceptionAction.run() returning Object). Both of those are
 * ordinary interface calls, and the runtime has a canonical itable
 * lookup for that (__jnative_lookup_itable in jnative_runtime.c).
 *
 * The four iface_id/run_slot globals below are emitted by
 * LlvmGenerator.generateMain as @__jnative_privilegedaction_iface_id,
 * @__jnative_privilegedaction_run_slot, and their
 * PrivilegedExceptionAction counterparts. They carry the numeric
 * interface id and method slot that the runtime's interface-dispatch
 * machinery needs to resolve the action's run() method.
 */

extern const int32_t __jnative_privilegedaction_iface_id;
extern const int32_t __jnative_privilegedaction_run_slot;
extern const int32_t __jnative_privilegedexceptionaction_iface_id;
extern const int32_t __jnative_privilegedexceptionaction_run_slot;

/*
 * Common dispatch helper: resolve the action's run() method through the
 * runtime's interface-table lookup and invoke it. Returns whatever the
 * action's run() returns.
 *
 * A NULL action, a NULL vtable, a missing itable for the given
 * interface id, or a NULL entry in the resolved slot are all treated
 * as NullPointerException. Each of them is a genuine programming error
 * at the Java level — the caller either passed null where a non-null
 * action was required, or the action's class does not actually
 * implement the interface it was declared to implement — and none of
 * them has a more specific recovery path.
 */
static void* invoke_action_run(void* action, int32_t iface_id, int32_t slot) {
    if (action == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    JNativeVTable* vt = *(JNativeVTable**)action;
    if (vt == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    void** itable = __jnative_lookup_itable(vt->ifacemap, iface_id);
    if (itable == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    void* entry = itable[slot];
    if (entry == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    void* (*run)(void*) = (void*)entry;
    return run(action);
}

void* __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedAction__Ljava_lang_Object_(
        void* action) {
    return invoke_action_run(action,
        __jnative_privilegedaction_iface_id,
        __jnative_privilegedaction_run_slot);
}

void* __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedExceptionAction__Ljava_lang_Object_(
        void* action) {
    return invoke_action_run(action,
        __jnative_privilegedexceptionaction_iface_id,
        __jnative_privilegedexceptionaction_run_slot);
}

void* __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedAction_Ljava_security_AccessControlContext__Ljava_lang_Object_(
        void* action, void* ctx) {
    (void)ctx;
    return invoke_action_run(action,
        __jnative_privilegedaction_iface_id,
        __jnative_privilegedaction_run_slot);
}

void* __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedExceptionAction_Ljava_security_AccessControlContext__Ljava_lang_Object_(
        void* action, void* ctx) {
    (void)ctx;
    return invoke_action_run(action,
        __jnative_privilegedexceptionaction_iface_id,
        __jnative_privilegedexceptionaction_run_slot);
}

void* __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedAction_Ljava_security_AccessControlContext_Ljava_security_Permission__Ljava_lang_Object_(
        void* action, void* ctx, void* perm) {
    (void)ctx; (void)perm;
    return invoke_action_run(action,
        __jnative_privilegedaction_iface_id,
        __jnative_privilegedaction_run_slot);
}

void* __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedExceptionAction_Ljava_security_AccessControlContext_Ljava_security_Permission__Ljava_lang_Object_(
        void* action, void* ctx, void* perm) {
    (void)ctx; (void)perm;
    return invoke_action_run(action,
        __jnative_privilegedexceptionaction_iface_id,
        __jnative_privilegedexceptionaction_run_slot);
}

void* __jnative_fn_java_security_AccessController_getStackAccessControlContext___Ljava_security_AccessControlContext_(void) {
    return NULL;
}

void* __jnative_fn_java_security_AccessController_getContext___Ljava_security_AccessControlContext_(void) {
    return NULL;
}

void* __jnative_fn_java_security_AccessController_getInheritedAccessControlContext___Ljava_security_AccessControlContext_(void) {
    return NULL;
}

void* __jnative_fn_java_security_AccessController_getProtectionDomain__Ljava_lang_Class__Ljava_security_ProtectionDomain_(
        void* clazz) {
    (void)clazz;
    return NULL;
}

void* __jnative_fn_java_security_AccessController_createWrapper__Ljava_security_DomainCombiner_Ljava_lang_Class_Ljava_security_AccessControlContext_Ljava_security_AccessControlContext__Ljava_security_Permission__Ljava_security_AccessControlContext_(
        void* combiner, void* clazz, void* acc, void* parent, void* perm) {
    (void)combiner; (void)clazz; (void)acc; (void)parent; (void)perm;
    return NULL;
}

void __jnative_fn_java_security_AccessController_checkPermission__Ljava_security_Permission__V(
        void* perm) {
    (void)perm;
}

void __jnative_fn_java_security_AccessController_ensureMaterializedForStackWalk__Ljava_lang_Object__V(
        void* value) {
    (void)value;
}
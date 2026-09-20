#define _GNU_SOURCE
#include <stddef.h>
#include <stdint.h>

struct JNativeIfaceMapEntry {
    int32_t id;
    void** itable;
};

struct JNativeIfaceMap {
    int32_t count;
    struct JNativeIfaceMapEntry* entries;
};

struct JNativeVTable {
    void** methods;
    struct JNativeIfaceMap* ifacemap;
    const char* name;
};

extern void** __jnative_lookup_itable(struct JNativeIfaceMap* ifacemap, int32_t iface_id);
extern const int32_t __jnative_privilegedaction_iface_id;
extern const int32_t __jnative_privilegedaction_run_slot;
extern const int32_t __jnative_privilegedexceptionaction_iface_id;
extern const int32_t __jnative_privilegedexceptionaction_run_slot;

__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);

static void* invoke_action_run(void* action, int32_t iface_id, int32_t slot) {
    if (action == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    struct JNativeVTable* vt = *(struct JNativeVTable**)action;
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
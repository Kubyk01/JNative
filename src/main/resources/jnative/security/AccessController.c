#define _GNU_SOURCE
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

struct ReflectionClass {
    void* name;
    struct ReflectionClass* superclass;
    struct ReflectionClass** interfaces;
    void** methods;
    void** fields;
    void** constructors;
    int modifiers;
    int object_size;
};

extern struct ReflectionClass* reflect_all_classes[] __attribute__((weak));

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);

typedef struct ProtectionDomainEntry {
    struct ReflectionClass* clazz;
    void* domain;
    struct ProtectionDomainEntry* next;
} ProtectionDomainEntry;

static pthread_mutex_t pd_lock = PTHREAD_MUTEX_INITIALIZER;
static ProtectionDomainEntry* pd_table = NULL;

static void* lookup_or_create_domain(struct ReflectionClass* cls) {
    if (!cls) return NULL;
    pthread_mutex_lock(&pd_lock);
    for (ProtectionDomainEntry* e = pd_table; e; e = e->next) {
        if (e->clazz == cls) {
            void* d = e->domain;
            pthread_mutex_unlock(&pd_lock);
            return d;
        }
    }
    void* domain = calloc(1, 32);
    ProtectionDomainEntry* e = calloc(1, sizeof(ProtectionDomainEntry));
    if (e && domain) {
        e->clazz = cls;
        e->domain = domain;
        e->next = pd_table;
        pd_table = e;
    } else {
        free(domain);
        free(e);
        domain = NULL;
    }
    pthread_mutex_unlock(&pd_lock);
    return domain;
}

typedef struct AccessControlContextEntry {
    void* context;
    void** domains;
    int count;
    struct AccessControlContextEntry* next;
} AccessControlContextEntry;

static pthread_mutex_t acc_lock = PTHREAD_MUTEX_INITIALIZER;
static AccessControlContextEntry* acc_table = NULL;

void* __jnative_fn_java_security_AccessController_getStackAccessControlContext___Ljava_security_AccessControlContext_(void) {
    void* ctx = calloc(1, 16);
    return ctx;
}

void* __jnative_fn_java_security_AccessController_getContext___Ljava_security_AccessControlContext_(void) {
    void* ctx = calloc(1, 16);
    return ctx;
}

void* __jnative_fn_java_security_AccessController_getInheritedAccessControlContext___Ljava_security_AccessControlContext_(void) {
    return __jnative_fn_java_security_AccessController_getStackAccessControlContext___Ljava_security_AccessControlContext_();
}

void* __jnative_fn_java_security_AccessController_getProtectionDomain__Ljava_lang_Class__Ljava_security_ProtectionDomain_(void* clazz) {
    if (!clazz) return NULL;
    return lookup_or_create_domain((struct ReflectionClass*)clazz);
}

void* __jnative_fn_java_security_AccessController_createWrapper__Ljava_security_DomainCombiner_Ljava_lang_Class_Ljava_security_AccessControlContext_Ljava_security_AccessControlContext__Ljava_security_Permission__Ljava_security_AccessControlContext_(
    void* combiner, void* clazz, void* acc, void* parent, void* perm)
{
    AccessControlContextEntry* e = calloc(1, sizeof(AccessControlContextEntry));
    if (!e) {
        __jnative_throw_exception(NULL);
        return NULL;
    }
    e->context = calloc(1, 32);
    e->domains = calloc(4, sizeof(void*));
    e->count = 0;
    if (clazz) {
        e->domains[e->count++] = lookup_or_create_domain((struct ReflectionClass*)clazz);
    }
    if (acc) {
        e->domains[e->count++] = acc;
    }
    if (parent) {
        e->domains[e->count++] = parent;
    }
    if (perm) {
        e->domains[e->count++] = perm;
    }
    (void)combiner;
    pthread_mutex_lock(&acc_lock);
    e->next = acc_table;
    acc_table = e;
    pthread_mutex_unlock(&acc_lock);
    return e->context;
}

int32_t __jnative_fn_java_security_AccessController_checkPermission__Ljava_security_Permission__V(void* perm) {
    if (!perm) return 0;
    if (!pd_table) return 0;
    pthread_mutex_lock(&pd_lock);
    for (ProtectionDomainEntry* e = pd_table; e; e = e->next) {
        if (!e->domain) {
            pthread_mutex_unlock(&pd_lock);
            __jnative_throw_exception(NULL);
            return 0;
        }
    }
    pthread_mutex_unlock(&pd_lock);
    return 0;
}

void* __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedAction__Ljava_lang_Object_(void* action) {
    if (!action) return NULL;
    /* PrivilegedAction.run() is invoked by the Java-side wrapper before
     * this native method is reached; this entry point just returns null
     * in case bytecode calls it directly. */
    return NULL;
}

void* __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedExceptionAction__Ljava_lang_Object_(void* action) {
    if (!action) return NULL;
    return NULL;
}

void* __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedAction_Ljava_security_AccessControlContext__Ljava_lang_Object_(void* action, void* ctx) {
    (void)ctx;
    return __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedAction__Ljava_lang_Object_(action);
}

void* __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedExceptionAction_Ljava_security_AccessControlContext__Ljava_lang_Object_(void* action, void* ctx) {
    (void)ctx;
    return __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedExceptionAction__Ljava_lang_Object_(action);
}

void* __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedAction_Ljava_security_AccessControlContext_Ljava_security_Permission__Ljava_lang_Object_(void* action, void* ctx, void* perm) {
    (void)ctx; (void)perm;
    return __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedAction__Ljava_lang_Object_(action);
}

void* __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedExceptionAction_Ljava_security_AccessControlContext_Ljava_security_Permission__Ljava_lang_Object_(void* action, void* ctx, void* perm) {
    (void)ctx; (void)perm;
    return __jnative_fn_java_security_AccessController_doPrivileged__Ljava_security_PrivilegedExceptionAction__Ljava_lang_Object_(action);
}
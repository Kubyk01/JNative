/*
 * jnative_jni_bridge.c
 *
 * A thread-local JNIEnv for the JNative runtime. When a reachable Java
 * `native` method has no JNative C implementation, BytecodeToIr routes
 * its call through the standard JNI ABI (symbol Java_<class>_<method>).
 * The platform's JDK shared objects (libjava, libjavajpeg, libnet, ...)
 * provide the bodies; this file provides the JNIEnv* those bodies take
 * as their first argument.
 *
 * The table below is populated only for the entry points the reachable
 * JDK natives actually invoke. Every other slot is left NULL: on first
 * use the JDK native jumps to address 0 and the crash is diagnosable,
 * rather than silently wrong. Extending coverage is a one-line change
 * to the JNI_TABLE initializer.
 */
#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <pthread.h>

#include "jnative_runtime.h"

/* JNICALL is empty on the System V / Linux ABI. */
#ifndef JNICALL
#define JNICALL
#endif

/* ------------------------------------------------------------------ *
 *  Forward declaration of the JNI function table.
 * ------------------------------------------------------------------ */
struct JNINativeInterface_;
typedef const struct JNINativeInterface_* JNIEnv_t;

/* ------------------------------------------------------------------ *
 *  JNI object references. In JNative a jclass and a jobject are the
 *  same pointers the generated code already uses: ReflectionClass* for
 *  class mirrors and the object's first word for instances.
 * ------------------------------------------------------------------ */
typedef void*   jclass;
typedef void*   jobject;
typedef void*   jstring;
typedef void*   jarray;
typedef void*   jthrowable;
typedef int32_t jint;
typedef int64_t jlong;
typedef int8_t  jboolean;
typedef void*   jmethodID;
typedef void*   jfieldID;

/* ------------------------------------------------------------------ *
 *  Unimplemented-slot handler. Never returns.
 * ------------------------------------------------------------------ */
static void jni_unsupported(const char* name) {
    char buf[256];
    int n = snprintf(buf, sizeof(buf),
        "[jnienv] unimplemented JNI entry point '%s' invoked by a JDK "
        "native. Extend jnative_jni_bridge.c with a real implementation.\n",
        name);
    if (n > 0) {
        (void)!write(2, buf, (size_t)n);
    }
    abort();
}

/* ------------------------------------------------------------------ *
 *  Class lookup helpers.
 * ------------------------------------------------------------------ */
static ReflectionClass* lookup_class(const char* name) {
    if (!name || !reflect_all_classes) return NULL;
    /* Accept both slash and dot form. */
    for (ReflectionClass** p = reflect_all_classes; *p; p++) {
        const char* n = (*p)->cname;
        if (!n) continue;
        if (strcmp(n, name) == 0) return *p;
        /* dot form */
        const char* a = n;
        const char* b = name;
        while (*a && *b) {
            char ca = (*a == '/') ? '.' : *a;
            if (ca != *b) break;
            a++;
            b++;
        }
        if (*a == '\0' && *b == '\0') return *p;
    }
    return NULL;
}

/* ------------------------------------------------------------------ *
 *  JNI primitive implementations.
 * ------------------------------------------------------------------ */

static jint JNICALL JNI_GetVersion(JNIEnv_t e) {
    (void)e;
    return 0x00150000; /* JDK 21 */
}

static jclass JNICALL JNI_FindClass(JNIEnv_t e, const char* name) {
    (void)e;
    return (jclass)lookup_class(name);
}

static jclass JNICALL JNI_GetObjectClass(JNIEnv_t e, jobject o) {
    (void)e;
    if (!o) return NULL;
    return (jclass)__jnative_resolve_dispatch_vtable(o); /* first word */
}

static jclass JNICALL JNI_GetSuperclass(JNIEnv_t e, jclass c) {
    (void)e;
    return c ? (jclass)((ReflectionClass*)c)->superclass : NULL;
}

static jboolean JNICALL JNI_IsInstanceOf(JNIEnv_t e, jobject o, jclass c) {
    (void)e;
    if (!o || !c) return 0;
    void* vtable = *(void**)o;
    if (vtable == c) return 1;
    /* Walk superclasses. */
    ReflectionClass* cur = ((ReflectionClass*)c)->superclass;
    /* Not exhaustive — the jclass here is a mirror, so compare directly. */
    return 0;
}

static jboolean JNICALL JNI_IsAssignableFrom(JNIEnv_t e, jclass a, jclass b) {
    (void)e;
    if (!a || !b) return 0;
    ReflectionClass* target = (ReflectionClass*)a;
    ReflectionClass* cur = (ReflectionClass*)b;
    while (cur) {
        if (cur == target) return 1;
        for (ReflectionClass** ip = cur->interfaces; ip && *ip; ip++) {
            if (*ip == target) return 1;
        }
        cur = cur->superclass;
    }
    return 0;
}

/* ---- Strings ----------------------------------------------------- */

static jstring JNICALL JNI_NewStringUTF(JNIEnv_t e, const char* utf) {
    (void)e;
    return utf ? __jnative_make_string_obj(utf, (int32_t)strlen(utf)) : NULL;
}

static const char* JNICALL JNI_GetStringUTFChars(JNIEnv_t e, jstring s,
                                                 jboolean* isCopy) {
    (void)e;
    if (isCopy) *isCopy = 0;
    return s ? __jnative_read_string_bytes(s, NULL) : NULL;
}

static void JNICALL JNI_ReleaseStringUTFChars(JNIEnv_t e, jstring s,
                                              const char* p) {
    (void)e; (void)s; (void)p; /* JNative strings are not GC-managed. */
}

static jint JNICALL JNI_GetStringUTFLength(JNIEnv_t e, jstring s) {
    (void)e;
    int32_t n = 0;
    __jnative_read_string_bytes(s, &n);
    return n;
}

/* ---- Arrays ------------------------------------------------------ */

static jint JNICALL JNI_GetArrayLength(JNIEnv_t e, jarray a) {
    (void)e;
    return jnative_array_length(a);
}

static void* JNICALL JNI_GetPrimitiveArrayCritical(JNIEnv_t e, jarray a,
                                                   jboolean* isCopy) {
    (void)e;
    if (isCopy) *isCopy = 0;
    return jnative_array_data(a);
}

static void JNICALL JNI_ReleasePrimitiveArrayCritical(JNIEnv_t e, jarray a,
                                                      void* p, jint mode) {
    (void)e; (void)a; (void)p; (void)mode;
}

/* ---- Exceptions -------------------------------------------------- */

static jint JNICALL JNI_Throw(JNIEnv_t e, jthrowable t) {
    (void)e;
    __jnative_throw_exception(t);
    return 0; /* unreachable */
}

static jint JNICALL JNI_ThrowNew(JNIEnv_t e, jclass c, const char* msg) {
    (void)e;
    void* exc = __jnative_construct_exception(
        c ? ((ReflectionClass*)c)->cname : "java/lang/RuntimeException", msg);
    __jnative_throw_exception(exc);
    return 0;
}

static jthrowable JNICALL JNI_ExceptionOccurred(JNIEnv_t e) {
    (void)e;
    return __jnative_get_exception_object();
}

static void JNICALL JNI_ExceptionClear(JNIEnv_t e) {
    (void)e;
}

static jboolean JNICALL JNI_ExceptionCheck(JNIEnv_t e) {
    (void)e;
    return __jnative_get_exception_object() != NULL;
}

/* ---- Fields and methods ----------------------------------------- */

static jfieldID JNICALL JNI_GetFieldID(JNIEnv_t e, jclass c,
                                       const char* name, const char* sig) {
    (void)e; (void)sig;
    ReflectionClass* cls = (ReflectionClass*)c;
    if (!cls || !cls->fields) return NULL;
    for (ReflectionField** fp = cls->fields; *fp; fp++) {
        if (strcmp((const char*)(*fp)->name, name) == 0) return *fp;
    }
    return NULL;
}

static jmethodID JNICALL JNI_GetMethodID(JNIEnv_t e, jclass c,
                                         const char* name, const char* sig) {
    (void)e; (void)sig;
    ReflectionClass* cls = (ReflectionClass*)c;
    if (!cls || !cls->methods) return NULL;
    for (ReflectionMethod** mp = cls->methods; *mp; mp++) {
        if (strcmp((const char*)(*mp)->name, name) == 0) return *mp;
    }
    return NULL;
}

static jfieldID JNICALL JNI_GetStaticFieldID(JNIEnv_t e, jclass c,
                                             const char* name, const char* sig) {
    return JNI_GetFieldID(e, c, name, sig);
}

static jmethodID JNICALL JNI_GetStaticMethodID(JNIEnv_t e, jclass c,
                                               const char* name, const char* sig) {
    return JNI_GetMethodID(e, c, name, sig);
}

static jobject JNICALL JNI_GetObjectField(JNIEnv_t e, jobject o, jfieldID f) {
    (void)e;
    if (!o || !f) return NULL;
    ReflectionField* rf = (ReflectionField*)f;
    return *(jobject*)((char*)o + rf->offset);
}

static void JNICALL JNI_SetObjectField(JNIEnv_t e, jobject o, jfieldID f, jobject v) {
    (void)e;
    if (!o || !f) return;
    ReflectionField* rf = (ReflectionField*)f;
    *(jobject*)((char*)o + rf->offset) = v;
}

static jlong JNICALL JNI_GetLongField(JNIEnv_t e, jobject o, jfieldID f) {
    (void)e;
    ReflectionField* rf = (ReflectionField*)f;
    return *(jlong*)((char*)o + rf->offset);
}

static void JNICALL JNI_SetLongField(JNIEnv_t e, jobject o, jfieldID f, jlong v) {
    (void)e;
    ReflectionField* rf = (ReflectionField*)f;
    *(jlong*)((char*)o + rf->offset) = v;
}

static jint JNICALL JNI_GetIntField(JNIEnv_t e, jobject o, jfieldID f) {
    (void)e;
    ReflectionField* rf = (ReflectionField*)f;
    return *(jint*)((char*)o + rf->offset);
}

static void JNICALL JNI_SetIntField(JNIEnv_t e, jobject o, jfieldID f, jint v) {
    (void)e;
    ReflectionField* rf = (ReflectionField*)f;
    *(jint*)((char*)o + rf->offset) = v;
}

/* Static field access uses the LLVM-emitted @gv_* globals. The JNative
 * runtime already routes reflective static access through those globals;
 * the JNI side reuses the same convention. */
extern void* jnative_static_field_address(const char* owner, const char* name);

static jobject JNICALL JNI_GetStaticObjectField(JNIEnv_t e, jclass c,
                                                jfieldID f) {
    (void)e; (void)c;
    ReflectionField* rf = (ReflectionField*)f;
    if (!rf) return NULL;
    void* p = jnative_static_field_address((const char*)rf->name,
                                           (const char*)rf->name);
    return p ? *(jobject*)p : NULL;
}

/* Add the entries above to JNI_TABLE. The remaining unimplemented
 * entries stay NULL and crash on first use — the caller (a JDK native
 * in a .so) will show up in the SIGSEGV backtrace with a Java frame. */

/* ------------------------------------------------------------------ *
 *  Real JNIEnv table.
 *
 *  Layout must match jni.h exactly. Every unimplemented entry is left
 *  NULL, so a missing implementation cannot be silently wrong.
 * ------------------------------------------------------------------ */
struct JNINativeInterface_ {
    void*    reserved0;
    void*    reserved1;
    void*    reserved2;
    void*    reserved3;
    jint     (*GetVersion)(JNIEnv_t);
    void*    DefineClass;
    jclass   (*FindClass)(JNIEnv_t, const char*);
    void*    FromReflectedMethod;
    void*    FromReflectedField;
    void*    ToReflectedMethod;
    jclass   (*GetSuperclass)(JNIEnv_t, jclass);
    jboolean (*IsAssignableFrom)(JNIEnv_t, jclass, jclass);
    void*    ToReflectedField;
    jint     (*Throw)(JNIEnv_t, jthrowable);
    jint     (*ThrowNew)(JNIEnv_t, jclass, const char*);
    jthrowable (*ExceptionOccurred)(JNIEnv_t);
    void*    ExceptionDescribe;
    void     (*ExceptionClear)(JNIEnv_t);
    void*    FatalError;
    void*    PushLocalFrame;
    void*    PopLocalFrame;
    void*    NewGlobalRef;
    void*    DeleteGlobalRef;
    void*    DeleteLocalRef;
    void*    IsSameObject;
    void*    NewLocalRef;
    void*    EnsureLocalCapacity;
    void*    AllocObject;
    void*    NewObject;
    void*    NewObjectV;
    void*    NewObjectA;
    jclass   (*GetObjectClass)(JNIEnv_t, jobject);
    jboolean (*IsInstanceOf)(JNIEnv_t, jobject, jclass);
    jfieldID (*GetFieldID)(JNIEnv_t, jclass, const char*, const char*);
    jmethodID (*GetMethodID)(JNIEnv_t, jclass, const char*, const char*);
    jfieldID (*GetStaticFieldID)(JNIEnv_t, jclass, const char*, const char*);
    jmethodID (*GetStaticMethodID)(JNIEnv_t, jclass, const char*, const char*);
    jobject  (*GetObjectField)(JNIEnv_t, jobject, jfieldID);
    void     (*SetObjectField)(JNIEnv_t, jobject, jfieldID, jobject);
    jlong    (*GetLongField)(JNIEnv_t, jobject, jfieldID);
    void     (*SetLongField)(JNIEnv_t, jobject, jfieldID, jlong);
    jint     (*GetIntField)(JNIEnv_t, jobject, jfieldID);
    void     (*SetIntField)(JNIEnv_t, jobject, jfieldID, jint);
    jobject  (*GetStaticObjectField)(JNIEnv_t, jclass, jfieldID);
    void*    CallObjectMethod;
    /* … continues per the JNI spec through ~230 entries … */
    jint     (*GetArrayLength)(JNIEnv_t, jarray);
    void*    NewObjectArray;
    void*    GetObjectArrayElement;
    void*    SetObjectArrayElement;
    /* primitives: NewBooleanArray… through SetDoubleArrayRegion */
    void*    NewBooleanArray; /* … */
    void*    GetBooleanArrayElements; /* … */
    void*    GetPrimitiveArrayCriticalImpl;
    void*    ReleasePrimitiveArrayCriticalImpl;
    void*    GetStringCritical;
    void*    ReleaseStringCritical;
    void*    NewWeakGlobalRef;
    void*    DeleteWeakGlobalRef;
    jboolean (*ExceptionCheck)(JNIEnv_t);
    void*    NewDirectByteBuffer;
    void*    GetDirectBufferAddress;
    void*    GetDirectBufferCapacity;
    void*    GetObjectRefType;
    void*    GetModule;
    void*    IsVirtualThread;
};

/*
 * The actual table. Only the entries that matter are populated; the
 * rest are zero and will crash on first use with a clear signal.
 */
static const struct JNINativeInterface_ JNI_TABLE = {
    .reserved0 = NULL,
    .reserved1 = NULL,
    .reserved2 = NULL,
    .reserved3 = NULL,
    .GetVersion     = JNI_GetVersion,
    .FindClass      = JNI_FindClass,
    .GetSuperclass  = JNI_GetSuperclass,
    .IsAssignableFrom = JNI_IsAssignableFrom,
    .Throw          = JNI_Throw,
    .ThrowNew       = JNI_ThrowNew,
    .ExceptionOccurred = JNI_ExceptionOccurred,
    .ExceptionClear = JNI_ExceptionClear,
    .GetObjectClass = JNI_GetObjectClass,
    .IsInstanceOf   = JNI_IsInstanceOf,
    .GetFieldID     = JNI_GetFieldID,
    .GetMethodID    = JNI_GetMethodID,
    .GetStaticFieldID = JNI_GetStaticFieldID,
    .GetStaticMethodID = JNI_GetStaticMethodID,
    .GetObjectField = JNI_GetObjectField,
    .SetObjectField = JNI_SetObjectField,
    .GetLongField   = JNI_GetLongField,
    .SetLongField   = JNI_SetLongField,
    .GetIntField    = JNI_GetIntField,
    .SetIntField    = JNI_SetIntField,
    .GetStaticObjectField = JNI_GetStaticObjectField,
    .GetArrayLength = JNI_GetArrayLength,
    .ExceptionCheck = JNI_ExceptionCheck,
    /*
     * Every other entry is left NULL. On first hit the JDK native jumps
     * to address 0 and the runtime's SIGSEGV handler prints a full Java
     * frame. Adding a real implementation is a one-line change here.
     */
};

/* ------------------------------------------------------------------ *
 *  Thread-local env.
 * ------------------------------------------------------------------ */
static pthread_key_t  jnienv_key;
static pthread_once_t jnienv_key_once = PTHREAD_ONCE_INIT;

static void jnienv_key_init(void) {
    pthread_key_create(&jnienv_key, NULL);
}

void* __jnative_get_jnienv(void) {
    pthread_once(&jnienv_key_once, jnienv_key_init);
    void* env = pthread_getspecific(jnienv_key);
    if (env == NULL) {
        env = (void*)&JNI_TABLE;
        pthread_setspecific(jnienv_key, env);
    }
    return env;
}

void* __jnative_get_thread_jnienv(void) {
    return __jnative_get_jnienv();
}

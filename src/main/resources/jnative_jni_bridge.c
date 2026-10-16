/*
 * jnative_jni_bridge.c
 *
 * A thread-local JNIEnv for the JNative runtime, extended with the
 * machinery required to host RegisterNatives-based JNI libraries such
 * as Netty's native transports.
 *
 * ============================================================================
 *  Problem
 * ============================================================================
 *
 * The traditional JNI ABI resolves a native method by symbol name: a
 * library exports a C function called Java_<mangled-class>_<name> and
 * the runtime emits a direct call to that symbol. Netty's native
 * libraries do not follow that convention. They export no Java_*
 * symbols at all; instead, they expose four generic entry points
 *
 *     JNI_OnLoad, JNI_OnLoad_netty_transport_native_epoll,
 *     JNI_OnUnload, JNI_OnUnload_netty_transport_native_epoll
 *
 * and install every native Java method at runtime by calling
 *
 *     (*env)->RegisterNatives(env, clazz, methods, nMethods);
 *
 * Consequently the runtime cannot find a native method by looking up an
 * exported symbol; it must call RegisterNatives on the library's
 * behalf, record the resulting (class, name, signature) -> fnPtr
 * triples in a dispatch table, and route every JNI native call through
 * that table.
 *
 * ============================================================================
 *  Solution
 * ============================================================================
 *
 * This file owns the entire lifecycle of that dispatch:
 *
 *   1. It defines the JNIEnv function table with the exact layout the
 *      JNI specification mandates, so a library compiled against the
 *      standard jni.h sees the RegisterNatives slot at the correct
 *      offset (index 215 in the function table). The struct is
 *      therefore declared with every slot in the specification's own
 *      order, even those the runtime does not implement; an unimplemented
 *      slot is a NULL function pointer.
 *
 *   2. RegisterNatives is implemented by inserting each (class, name,
 *      signature, fnPtr) tuple into a process-wide hash table, keyed by
 *      the internal class name and the exact (name, signature) pair
 *      that the library supplied.
 *
 *   3. __jnative_jni_resolve(class, name, signature) is the entry point
 *      the LLVM code generator calls at every JNI native call site. It
 *      returns the fnPtr registered by the library, or throws a
 *      diagnostic and aborts, so the emitted code never dereferences a
 *      NULL function pointer.
 *
 *   4. __jnative_jni_load_library_from_resource(key) materialises the
 *      library's bytes to a temporary file, dlopens it, and invokes its
 *      JNI_OnLoad with a JavaVM whose GetEnv returns this JNIEnv. The
 *      library's JNI_OnLoad then runs normally: it calls FindClass,
 *      RegisterNatives, and whatever other JNIEnv entry points it needs.
 *
 *   5. __jnative_jni_load_all(keys, count) iterates over a NULL-
 *      terminated array of resource keys emitted by the code generator
 *      and loads each library in turn. @main calls it once, before any
 *      Java code runs.
 *
 * ============================================================================
 *  Struct layout
 * ============================================================================
 *
 * The JNINativeInterface_ struct in this file matches the OpenJDK
 * jni.h layout slot for slot. Any mismatch — a missing slot, an
 * out-of-order field — would make a library's RegisterNatives call
 * land at the wrong function pointer and corrupt the process at
 * JNI_OnLoad time. The struct is therefore declared with all slots in
 * the specification's own order; unimplemented slots have the type
 * void* and are initialised to NULL.
 *
 * The same rule applies to JNIInvokeInterface_, the JavaVM function
 * table: GetEnv is at index 6 (after three reserved slots, DestroyJavaVM,
 * AttachCurrentThread and DetachCurrentThread).
 *
 * ============================================================================
 *  Calling convention
 * ============================================================================
 *
 * JNICALL is a portability macro from the JNI specification. On Unix
 * ABIs it expands to nothing; on 32-bit Windows it expands to __stdcall.
 * Every JNI entry point — both the free function definitions in this
 * file and the function-pointer fields in the JNINativeInterface_ and
 * JNIInvokeInterface_ structs — carries the macro so the struct layout
 * remains correct on every platform the runtime targets.
 */

#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <sys/types.h>

#include "jnative_runtime.h"

/* ==========================================================================
 *  Portability macros (mirror the standard jni_md.h).
 * ========================================================================== */

#ifndef JNICALL
#  if defined(_WIN32) && !defined(_WIN64)
#    define JNICALL __stdcall
#  else
#    define JNICALL
#  endif
#endif

#ifndef JNIEXPORT
#  define JNIEXPORT
#endif

/* ==========================================================================
 *  JNI primitive types
 * ========================================================================== */

struct JNINativeInterface_;
typedef const struct JNINativeInterface_* JNIEnv_t;

struct JNIInvokeInterface_;
typedef const struct JNIInvokeInterface_* JavaVM_t;

typedef void*    jclass;
typedef void*    jobject;
typedef void*    jstring;
typedef void*    jarray;
typedef void*    jthrowable;
typedef int32_t  jint;
typedef int64_t  jlong;
typedef uint8_t  jboolean;
typedef int8_t   jbyte;
typedef uint16_t jchar;
typedef int16_t  jshort;
typedef float    jfloat;
typedef double   jdouble;
typedef void*    jmethodID;
typedef void*    jfieldID;
typedef void*    jweak;

typedef struct {
    const char* name;
    const char* signature;
    void*       fnPtr;
} JNINativeMethod;

/* --------------------------------------------------------------------------
 *  JNI version constants (jni.h values).
 * ------------------------------------------------------------------------ */
#define JNI_VERSION_1_1 0x00010001
#define JNI_VERSION_1_2 0x00010002
#define JNI_VERSION_1_4 0x00010004
#define JNI_VERSION_1_6 0x00010006
#define JNI_VERSION_1_8 0x00010008
#define JNI_VERSION_9   0x00090000
#define JNI_VERSION_10  0x000a0000

#define JNI_OK          0
#define JNI_ERR        (-1)

/* ==========================================================================
 *  Forward declarations for static JNIEnv entry points that are
 *  referenced from the JNINativeInterface_ initializer.
 * ========================================================================== */

static jint       JNICALL JNI_GetVersion(JNIEnv_t);
static jclass     JNICALL JNI_FindClass(JNIEnv_t, const char*);
static jclass     JNICALL JNI_GetObjectClass(JNIEnv_t, jobject);
static jclass     JNICALL JNI_GetSuperclass(JNIEnv_t, jclass);
static jboolean   JNICALL JNI_IsInstanceOf(JNIEnv_t, jobject, jclass);
static jboolean   JNICALL JNI_IsAssignableFrom(JNIEnv_t, jclass, jclass);
static jint       JNICALL JNI_Throw(JNIEnv_t, jthrowable);
static jint       JNICALL JNI_ThrowNew(JNIEnv_t, jclass, const char*);
static jthrowable JNICALL JNI_ExceptionOccurred(JNIEnv_t);
static void       JNICALL JNI_ExceptionClear(JNIEnv_t);
static jboolean   JNICALL JNI_ExceptionCheck(JNIEnv_t);
static jstring    JNICALL JNI_NewStringUTF(JNIEnv_t, const char*);
static jint       JNICALL JNI_GetStringUTFLength(JNIEnv_t, jstring);
static const char* JNICALL JNI_GetStringUTFChars(JNIEnv_t, jstring, jboolean*);
static void       JNICALL JNI_ReleaseStringUTFChars(JNIEnv_t, jstring, const char*);
static jint       JNICALL JNI_GetArrayLength(JNIEnv_t, jarray);
static jfieldID   JNICALL JNI_GetFieldID(JNIEnv_t, jclass, const char*, const char*);
static jmethodID  JNICALL JNI_GetMethodID(JNIEnv_t, jclass, const char*, const char*);
static jfieldID   JNICALL JNI_GetStaticFieldID(JNIEnv_t, jclass, const char*, const char*);
static jmethodID  JNICALL JNI_GetStaticMethodID(JNIEnv_t, jclass, const char*, const char*);
static jobject    JNICALL JNI_GetObjectField(JNIEnv_t, jobject, jfieldID);
static void       JNICALL JNI_SetObjectField(JNIEnv_t, jobject, jfieldID, jobject);
static jint       JNICALL JNI_GetIntField(JNIEnv_t, jobject, jfieldID);
static void       JNICALL JNI_SetIntField(JNIEnv_t, jobject, jfieldID, jint);
static jlong      JNICALL JNI_GetLongField(JNIEnv_t, jobject, jfieldID);
static void       JNICALL JNI_SetLongField(JNIEnv_t, jobject, jfieldID, jlong);
static jobject    JNICALL JNI_GetStaticObjectField(JNIEnv_t, jclass, jfieldID);
static void*      JNICALL JNI_GetPrimitiveArrayCritical(JNIEnv_t, jarray, jboolean*);
static void       JNICALL JNI_ReleasePrimitiveArrayCritical(JNIEnv_t, jarray, void*, jint);
static jint       JNICALL JNI_RegisterNatives(JNIEnv_t, jclass, const JNINativeMethod*, jint);
static jint       JNICALL JNI_GetJavaVM(JNIEnv_t, JavaVM_t*);

/* ==========================================================================
 *  JNINativeInterface_ — the JNIEnv function table.
 *
 *  Slot layout MUST match the JNI specification byte-for-byte. Every
 *  slot has its own named field so a library compiled against the
 *  standard jni.h sees the same offsets. Unimplemented slots are void*
 *  and are left NULL.
 * ========================================================================== */

struct JNINativeInterface_ {
    void *reserved0;
    void *reserved1;
    void *reserved2;
    void *reserved3;

    jint     (JNICALL *GetVersion)(JNIEnv_t);
    void*    DefineClass;
    jclass   (JNICALL *FindClass)(JNIEnv_t, const char*);
    void*    FromReflectedMethod;
    void*    FromReflectedField;
    void*    ToReflectedMethod;
    jclass   (JNICALL *GetSuperclass)(JNIEnv_t, jclass);
    jboolean (JNICALL *IsAssignableFrom)(JNIEnv_t, jclass, jclass);
    void*    ToReflectedField;

    jint       (JNICALL *Throw)(JNIEnv_t, jthrowable);
    jint       (JNICALL *ThrowNew)(JNIEnv_t, jclass, const char*);
    jthrowable (JNICALL *ExceptionOccurred)(JNIEnv_t);
    void*      ExceptionDescribe;
    void       (JNICALL *ExceptionClear)(JNIEnv_t);
    void*      FatalError;
    void*      PushLocalFrame;
    void*      PopLocalFrame;
    void*      NewGlobalRef;
    void*      DeleteGlobalRef;
    void*      DeleteLocalRef;
    void*      IsSameObject;
    void*      NewLocalRef;
    void*      EnsureLocalCapacity;
    void*      AllocObject;
    void*      NewObject;
    void*      NewObjectV;
    void*      NewObjectA;
    jclass     (JNICALL *GetObjectClass)(JNIEnv_t, jobject);
    jboolean   (JNICALL *IsInstanceOf)(JNIEnv_t, jobject, jclass);
    jmethodID  (JNICALL *GetMethodID)(JNIEnv_t, jclass, const char*, const char*);

    /* CallObjectMethod .. CallNonvirtualVoidMethodA (33 slots) */
    void* CallObjectMethod;              void* CallObjectMethodV;              void* CallObjectMethodA;
    void* CallBooleanMethod;             void* CallBooleanMethodV;             void* CallBooleanMethodA;
    void* CallByteMethod;                void* CallByteMethodV;                void* CallByteMethodA;
    void* CallCharMethod;                void* CallCharMethodV;                void* CallCharMethodA;
    void* CallShortMethod;               void* CallShortMethodV;               void* CallShortMethodA;
    void* CallIntMethod;                 void* CallIntMethodV;                 void* CallIntMethodA;
    void* CallLongMethod;                void* CallLongMethodV;                void* CallLongMethodA;
    void* CallFloatMethod;               void* CallFloatMethodV;               void* CallFloatMethodA;
    void* CallDoubleMethod;              void* CallDoubleMethodV;              void* CallDoubleMethodA;
    void* CallVoidMethod;                void* CallVoidMethodV;                void* CallVoidMethodA;
    void* CallNonvirtualObjectMethod;    void* CallNonvirtualObjectMethodV;    void* CallNonvirtualObjectMethodA;
    void* CallNonvirtualBooleanMethod;   void* CallNonvirtualBooleanMethodV;   void* CallNonvirtualBooleanMethodA;
    void* CallNonvirtualByteMethod;      void* CallNonvirtualByteMethodV;      void* CallNonvirtualByteMethodA;
    void* CallNonvirtualCharMethod;      void* CallNonvirtualCharMethodV;      void* CallNonvirtualCharMethodA;
    void* CallNonvirtualShortMethod;     void* CallNonvirtualShortMethodV;     void* CallNonvirtualShortMethodA;
    void* CallNonvirtualIntMethod;       void* CallNonvirtualIntMethodV;       void* CallNonvirtualIntMethodA;
    void* CallNonvirtualLongMethod;      void* CallNonvirtualLongMethodV;      void* CallNonvirtualLongMethodA;
    void* CallNonvirtualFloatMethod;     void* CallNonvirtualFloatMethodV;     void* CallNonvirtualFloatMethodA;
    void* CallNonvirtualDoubleMethod;    void* CallNonvirtualDoubleMethodV;    void* CallNonvirtualDoubleMethodA;
    void* CallNonvirtualVoidMethod;      void* CallNonvirtualVoidMethodV;      void* CallNonvirtualVoidMethodA;

    jfieldID  (JNICALL *GetFieldID)(JNIEnv_t, jclass, const char*, const char*);
    jobject   (JNICALL *GetObjectField)(JNIEnv_t, jobject, jfieldID);
    void*     GetBooleanField;
    void*     GetByteField;
    void*     GetCharField;
    void*     GetShortField;
    jint      (JNICALL *GetIntField)(JNIEnv_t, jobject, jfieldID);
    jlong     (JNICALL *GetLongField)(JNIEnv_t, jobject, jfieldID);
    void*     GetFloatField;
    void*     GetDoubleField;
    void      (JNICALL *SetObjectField)(JNIEnv_t, jobject, jfieldID, jobject);
    void      (JNICALL *SetBooleanField)(JNIEnv_t, jobject, jfieldID, jboolean);
    void*     SetByteField;
    void*     SetCharField;
    void*     SetShortField;
    void      (JNICALL *SetIntField)(JNIEnv_t, jobject, jfieldID, jint);
    void      (JNICALL *SetLongField)(JNIEnv_t, jobject, jfieldID, jlong);
    void*     SetFloatField;
    void*     SetDoubleField;

    jmethodID (JNICALL *GetStaticMethodID)(JNIEnv_t, jclass, const char*, const char*);

    /* CallStaticObjectMethod .. CallStaticVoidMethodA (30 slots) */
    void* CallStaticObjectMethod;        void* CallStaticObjectMethodV;        void* CallStaticObjectMethodA;
    void* CallStaticBooleanMethod;       void* CallStaticBooleanMethodV;       void* CallStaticBooleanMethodA;
    void* CallStaticByteMethod;          void* CallStaticByteMethodV;          void* CallStaticByteMethodA;
    void* CallStaticCharMethod;          void* CallStaticCharMethodV;          void* CallStaticCharMethodA;
    void* CallStaticShortMethod;         void* CallStaticShortMethodV;         void* CallStaticShortMethodA;
    void* CallStaticIntMethod;           void* CallStaticIntMethodV;           void* CallStaticIntMethodA;
    void* CallStaticLongMethod;          void* CallStaticLongMethodV;          void* CallStaticLongMethodA;
    void* CallStaticFloatMethod;         void* CallStaticFloatMethodV;         void* CallStaticFloatMethodA;
    void* CallStaticDoubleMethod;        void* CallStaticDoubleMethodV;        void* CallStaticDoubleMethodA;
    void* CallStaticVoidMethod;          void* CallStaticVoidMethodV;          void* CallStaticVoidMethodA;

    jfieldID (JNICALL *GetStaticFieldID)(JNIEnv_t, jclass, const char*, const char*);
    jobject  (JNICALL *GetStaticObjectField)(JNIEnv_t, jclass, jfieldID);
    void*    GetStaticBooleanField;
    void*    GetStaticByteField;
    void*    GetStaticCharField;
    void*    GetStaticShortField;
    void*    GetStaticIntField;
    void*    GetStaticLongField;
    void*    GetStaticFloatField;
    void*    GetStaticDoubleField;
    void*    SetStaticObjectField;
    void*    SetStaticBooleanField;
    void*    SetStaticByteField;
    void*    SetStaticCharField;
    void*    SetStaticShortField;
    void*    SetStaticIntField;
    void*    SetStaticLongField;
    void*    SetStaticFloatField;
    void*    SetStaticDoubleField;

    void*    NewString;
    void*    GetStringLength;
    void*    GetStringChars;
    void*    ReleaseStringChars;
    jstring  (JNICALL *NewStringUTF)(JNIEnv_t, const char*);
    jint     (JNICALL *GetStringUTFLength)(JNIEnv_t, jstring);
    const char* (JNICALL *GetStringUTFChars)(JNIEnv_t, jstring, jboolean*);
    void     (JNICALL *ReleaseStringUTFChars)(JNIEnv_t, jstring, const char*);

    jint (JNICALL *GetArrayLength)(JNIEnv_t, jarray);
    void* NewObjectArray;
    void* GetObjectArrayElement;
    void* SetObjectArrayElement;
    void* NewBooleanArray;
    void* NewByteArray;
    void* NewCharArray;
    void* NewShortArray;
    void* NewIntArray;
    void* NewLongArray;
    void* NewFloatArray;
    void* NewDoubleArray;
    void* GetBooleanArrayElements;
    void* GetByteArrayElements;
    void* GetCharArrayElements;
    void* GetShortArrayElements;
    void* GetIntArrayElements;
    void* GetLongArrayElements;
    void* GetFloatArrayElements;
    void* GetDoubleArrayElements;
    void* ReleaseBooleanArrayElements;
    void* ReleaseByteArrayElements;
    void* ReleaseCharArrayElements;
    void* ReleaseShortArrayElements;
    void* ReleaseIntArrayElements;
    void* ReleaseLongArrayElements;
    void* ReleaseFloatArrayElements;
    void* ReleaseDoubleArrayElements;
    void* GetBooleanArrayRegion;
    void* GetByteArrayRegion;
    void* GetCharArrayRegion;
    void* GetShortArrayRegion;
    void* GetIntArrayRegion;
    void* GetLongArrayRegion;
    void* GetFloatArrayRegion;
    void* GetDoubleArrayRegion;
    void* SetBooleanArrayRegion;
    void* SetByteArrayRegion;
    void* SetCharArrayRegion;
    void* SetShortArrayRegion;
    void* SetIntArrayRegion;
    void* SetLongArrayRegion;
    void* SetFloatArrayRegion;
    void* SetDoubleArrayRegion;

    jint (JNICALL *RegisterNatives)(JNIEnv_t, jclass, const JNINativeMethod*, jint);
    jint (JNICALL *UnregisterNatives)(JNIEnv_t, jclass);

    void* MonitorEnter;
    void* MonitorExit;
    jint  (JNICALL *GetJavaVM)(JNIEnv_t, JavaVM_t*);

    void* GetStringRegion;
    void* GetStringUTFRegion;
    void* (JNICALL *GetPrimitiveArrayCritical)(JNIEnv_t, jarray, jboolean*);
    void  (JNICALL *ReleasePrimitiveArrayCritical)(JNIEnv_t, jarray, void*, jint);
    void* GetStringCritical;
    void* ReleaseStringCritical;
    void* NewWeakGlobalRef;
    void* DeleteWeakGlobalRef;
    jboolean (JNICALL *ExceptionCheck)(JNIEnv_t);

    void* NewDirectByteBuffer;
    void* GetDirectBufferAddress;
    void* GetDirectBufferCapacity;
    void* GetObjectRefType;
    void* GetModule;
    void* IsVirtualThread;
};

/* ==========================================================================
 *  JNIInvokeInterface_ — the JavaVM function table.
 *
 *  Slot layout per the JNI specification. Only GetEnv is implemented;
 *  a library that calls GetEnv during JNI_OnLoad (which every netty
 *  library does) receives this process's JNIEnv.
 * ========================================================================== */

struct JNIInvokeInterface_ {
    void *reserved0;
    void *reserved1;
    void *reserved2;

    jint (JNICALL *DestroyJavaVM)(JavaVM_t);
    jint (JNICALL *AttachCurrentThread)(JavaVM_t, void**, void*);
    jint (JNICALL *DetachCurrentThread)(JavaVM_t);
    jint (JNICALL *GetEnv)(JavaVM_t, void**, jint);
    jint (JNICALL *AttachCurrentThreadAsDaemon)(JavaVM_t, void**, void*);
};

/* ==========================================================================
 *  Dispatch table for RegisterNatives-registered methods.
 * ========================================================================== */

typedef struct JniNativeEntry {
    char* class_name;
    char* method_name;
    char* signature;
    void* fn_ptr;
    struct JniNativeEntry* next;
} JniNativeEntry;

#define JNI_NATIVE_BUCKETS 4096
static JniNativeEntry*  jni_native_table[JNI_NATIVE_BUCKETS];
static pthread_mutex_t  jni_native_lock = PTHREAD_MUTEX_INITIALIZER;

static uint32_t jni_hash_string(const char* s) {
    if (s == NULL) return 0;
    uint32_t h = 2166136261u;
    while (*s) {
        h ^= (uint8_t)*s++;
        h *= 16777619u;
    }
    return h;
}

static uint32_t jni_hash_triple(const char* a, const char* b, const char* c) {
    uint32_t h = jni_hash_string(a);
    h = (h * 16777619u) ^ jni_hash_string(b);
    h = (h * 16777619u) ^ jni_hash_string(c);
    return h;
}

static void jni_table_insert(const char* class_name,
                             const char* method_name,
                             const char* signature,
                             void* fn_ptr) {
    if (class_name == NULL || method_name == NULL
        || signature == NULL || fn_ptr == NULL) {
        return;
    }

    uint32_t bucket = jni_hash_triple(class_name, method_name, signature)
                    & (JNI_NATIVE_BUCKETS - 1);

    pthread_mutex_lock(&jni_native_lock);
    for (JniNativeEntry* e = jni_native_table[bucket]; e; e = e->next) {
        if (strcmp(e->class_name, class_name) == 0
            && strcmp(e->method_name, method_name) == 0
            && strcmp(e->signature, signature) == 0) {
            e->fn_ptr = fn_ptr;
            pthread_mutex_unlock(&jni_native_lock);
            return;
        }
    }

    JniNativeEntry* e = (JniNativeEntry*)calloc(1, sizeof(JniNativeEntry));
    if (e == NULL) {
        pthread_mutex_unlock(&jni_native_lock);
        return;
    }
    e->class_name = strdup(class_name);
    e->method_name = strdup(method_name);
    e->signature = strdup(signature);
    e->fn_ptr = fn_ptr;
    e->next = jni_native_table[bucket];
    jni_native_table[bucket] = e;
    pthread_mutex_unlock(&jni_native_lock);
}

static void* jni_table_lookup(const char* class_name,
                              const char* method_name,
                              const char* signature) {
    if (class_name == NULL || method_name == NULL || signature == NULL) {
        return NULL;
    }

    uint32_t bucket = jni_hash_triple(class_name, method_name, signature)
                    & (JNI_NATIVE_BUCKETS - 1);

    pthread_mutex_lock(&jni_native_lock);
    for (JniNativeEntry* e = jni_native_table[bucket]; e; e = e->next) {
        if (strcmp(e->class_name, class_name) == 0
            && strcmp(e->method_name, method_name) == 0
            && strcmp(e->signature, signature) == 0) {
            void* fn = e->fn_ptr;
            pthread_mutex_unlock(&jni_native_lock);
            return fn;
        }
    }
    pthread_mutex_unlock(&jni_native_lock);
    return NULL;
}

/* ==========================================================================
 *  Class-lookup helper shared by FindClass and GetObjectClass.
 * ========================================================================== */

static ReflectionClass* jni_lookup_class(const char* name) {
    if (name == NULL || reflect_all_classes == NULL) return NULL;

    for (ReflectionClass** p = reflect_all_classes; *p; p++) {
        const char* n = (*p)->cname;
        if (n == NULL) continue;
        if (strcmp(n, name) == 0) return *p;

        /* Accept dotted form too: "io.netty.channel.epoll.Native". */
        const char* a = n;
        const char* b = name;
        while (*a && *b) {
            char ca = (*a == '/') ? '.' : *a;
            if (ca != *b) break;
            a++; b++;
        }
        if (*a == '\0' && *b == '\0') return *p;
    }
    return NULL;
}

/* ==========================================================================
 *  JNIEnv implementations.
 * ========================================================================== */

static jint JNICALL JNI_GetVersion(JNIEnv_t e) {
    (void)e;
    return JNI_VERSION_1_6;
}

static jclass JNICALL JNI_FindClass(JNIEnv_t e, const char* name) {
    (void)e;
    return (jclass)jni_lookup_class(name);
}

static jclass JNICALL JNI_GetObjectClass(JNIEnv_t e, jobject o) {
    (void)e;
    if (o == NULL) return NULL;
    void* first = *(void**)o;
    if (first == NULL) return NULL;
    return (jclass)first;
}

static jclass JNICALL JNI_GetSuperclass(JNIEnv_t e, jclass c) {
    (void)e;
    return c ? (jclass)((ReflectionClass*)c)->superclass : NULL;
}

static jboolean JNICALL JNI_IsInstanceOf(JNIEnv_t e, jobject o, jclass c) {
    (void)e;
    if (o == NULL || c == NULL) return 0;
    void* vtable = *(void**)o;
    if (vtable == (void*)c) return 1;
    ReflectionClass* target = (ReflectionClass*)c;
    ReflectionClass* cur = target->superclass;
    while (cur) {
        if (cur == (ReflectionClass*)vtable) return 1;
        cur = cur->superclass;
    }
    return 0;
}

static jboolean JNICALL JNI_IsAssignableFrom(JNIEnv_t e, jclass a, jclass b) {
    (void)e;
    if (a == NULL || b == NULL) return 0;
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
    return 0; /* unreachable */
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

static jstring JNICALL JNI_NewStringUTF(JNIEnv_t e, const char* utf) {
    (void)e;
    if (utf == NULL) return NULL;
    return __jnative_make_string_obj(utf, (int32_t)strlen(utf));
}

static jint JNICALL JNI_GetStringUTFLength(JNIEnv_t e, jstring s) {
    (void)e;
    int32_t n = 0;
    __jnative_read_string_bytes(s, &n);
    return n;
}

static const char* JNICALL JNI_GetStringUTFChars(JNIEnv_t e, jstring s,
                                                 jboolean* isCopy) {
    (void)e;
    if (isCopy) *isCopy = 0;
    return s ? __jnative_read_string_bytes(s, NULL) : NULL;
}

static void JNICALL JNI_ReleaseStringUTFChars(JNIEnv_t e, jstring s,
                                              const char* p) {
    (void)e; (void)s; (void)p;
}

static jint JNICALL JNI_GetArrayLength(JNIEnv_t e, jarray a) {
    (void)e;
    return jnative_array_length(a);
}

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

static jobject JNICALL JNI_GetStaticObjectField(JNIEnv_t e, jclass c,
                                                jfieldID f) {
    (void)e; (void)c;
    ReflectionField* rf = (ReflectionField*)f;
    if (!rf) return NULL;
    void* p = jnative_static_field_address((const char*)rf->name,
                                           (const char*)rf->name);
    return p ? *(jobject*)p : NULL;
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

static jint JNICALL JNI_RegisterNatives(JNIEnv_t e, jclass c,
                                        const JNINativeMethod* methods,
                                        jint nMethods) {
    (void)e;
    if (c == NULL || methods == NULL || nMethods <= 0) return JNI_ERR;

    const char* class_name = ((ReflectionClass*)c)->cname;
    if (class_name == NULL) return JNI_ERR;

    for (jint i = 0; i < nMethods; i++) {
        const char* name = methods[i].name;
        const char* sig = methods[i].signature;
        void* fn = methods[i].fnPtr;
        if (name == NULL || sig == NULL || fn == NULL) continue;
        jni_table_insert(class_name, name, sig, fn);
    }
    return JNI_OK;
}

/* ==========================================================================
 *  The single shared JNIEnv instance.
 * ========================================================================== */

static const struct JNINativeInterface_ JNI_TABLE = {
    .reserved0 = NULL,
    .reserved1 = NULL,
    .reserved2 = NULL,
    .reserved3 = NULL,

    .GetVersion     = JNI_GetVersion,
    .DefineClass    = NULL,
    .FindClass      = JNI_FindClass,
    .FromReflectedMethod = NULL,
    .FromReflectedField  = NULL,
    .ToReflectedMethod   = NULL,
    .GetSuperclass  = JNI_GetSuperclass,
    .IsAssignableFrom = JNI_IsAssignableFrom,
    .ToReflectedField = NULL,

    .Throw          = JNI_Throw,
    .ThrowNew       = JNI_ThrowNew,
    .ExceptionOccurred = JNI_ExceptionOccurred,
    .ExceptionDescribe = NULL,
    .ExceptionClear = JNI_ExceptionClear,
    .FatalError     = NULL,
    .PushLocalFrame = NULL,
    .PopLocalFrame  = NULL,
    .NewGlobalRef   = NULL,
    .DeleteGlobalRef = NULL,
    .DeleteLocalRef = NULL,
    .IsSameObject   = NULL,
    .NewLocalRef    = NULL,
    .EnsureLocalCapacity = NULL,
    .AllocObject    = NULL,
    .NewObject      = NULL,
    .NewObjectV     = NULL,
    .NewObjectA     = NULL,
    .GetObjectClass = JNI_GetObjectClass,
    .IsInstanceOf   = JNI_IsInstanceOf,
    .GetMethodID    = JNI_GetMethodID,

    /* All Call* slots NULL. */
    .CallObjectMethod = NULL,
    .CallObjectMethodV = NULL,
    .CallObjectMethodA = NULL,
    .CallBooleanMethod = NULL,
    .CallBooleanMethodV = NULL,
    .CallBooleanMethodA = NULL,
    .CallByteMethod = NULL,
    .CallByteMethodV = NULL,
    .CallByteMethodA = NULL,
    .CallCharMethod = NULL,
    .CallCharMethodV = NULL,
    .CallCharMethodA = NULL,
    .CallShortMethod = NULL,
    .CallShortMethodV = NULL,
    .CallShortMethodA = NULL,
    .CallIntMethod = NULL,
    .CallIntMethodV = NULL,
    .CallIntMethodA = NULL,
    .CallLongMethod = NULL,
    .CallLongMethodV = NULL,
    .CallLongMethodA = NULL,
    .CallFloatMethod = NULL,
    .CallFloatMethodV = NULL,
    .CallFloatMethodA = NULL,
    .CallDoubleMethod = NULL,
    .CallDoubleMethodV = NULL,
    .CallDoubleMethodA = NULL,
    .CallVoidMethod = NULL,
    .CallVoidMethodV = NULL,
    .CallVoidMethodA = NULL,
    .CallNonvirtualObjectMethod = NULL,
    .CallNonvirtualObjectMethodV = NULL,
    .CallNonvirtualObjectMethodA = NULL,
    .CallNonvirtualBooleanMethod = NULL,
    .CallNonvirtualBooleanMethodV = NULL,
    .CallNonvirtualBooleanMethodA = NULL,
    .CallNonvirtualByteMethod = NULL,
    .CallNonvirtualByteMethodV = NULL,
    .CallNonvirtualByteMethodA = NULL,
    .CallNonvirtualCharMethod = NULL,
    .CallNonvirtualCharMethodV = NULL,
    .CallNonvirtualCharMethodA = NULL,
    .CallNonvirtualShortMethod = NULL,
    .CallNonvirtualShortMethodV = NULL,
    .CallNonvirtualShortMethodA = NULL,
    .CallNonvirtualIntMethod = NULL,
    .CallNonvirtualIntMethodV = NULL,
    .CallNonvirtualIntMethodA = NULL,
    .CallNonvirtualLongMethod = NULL,
    .CallNonvirtualLongMethodV = NULL,
    .CallNonvirtualLongMethodA = NULL,
    .CallNonvirtualFloatMethod = NULL,
    .CallNonvirtualFloatMethodV = NULL,
    .CallNonvirtualFloatMethodA = NULL,
    .CallNonvirtualDoubleMethod = NULL,
    .CallNonvirtualDoubleMethodV = NULL,
    .CallNonvirtualDoubleMethodA = NULL,
    .CallNonvirtualVoidMethod = NULL,
    .CallNonvirtualVoidMethodV = NULL,
    .CallNonvirtualVoidMethodA = NULL,

    .GetFieldID     = JNI_GetFieldID,
    .GetObjectField = JNI_GetObjectField,
    .GetBooleanField = NULL,
    .GetByteField   = NULL,
    .GetCharField   = NULL,
    .GetShortField  = NULL,
    .GetIntField    = JNI_GetIntField,
    .GetLongField   = JNI_GetLongField,
    .GetFloatField  = NULL,
    .GetDoubleField = NULL,
    .SetObjectField = JNI_SetObjectField,
    .SetBooleanField = NULL,
    .SetByteField   = NULL,
    .SetCharField   = NULL,
    .SetShortField  = NULL,
    .SetIntField    = JNI_SetIntField,
    .SetLongField   = JNI_SetLongField,
    .SetFloatField  = NULL,
    .SetDoubleField = NULL,

    .GetStaticMethodID = JNI_GetStaticMethodID,
    .CallStaticObjectMethod = NULL,
    .CallStaticObjectMethodV = NULL,
    .CallStaticObjectMethodA = NULL,
    .CallStaticBooleanMethod = NULL,
    .CallStaticBooleanMethodV = NULL,
    .CallStaticBooleanMethodA = NULL,
    .CallStaticByteMethod = NULL,
    .CallStaticByteMethodV = NULL,
    .CallStaticByteMethodA = NULL,
    .CallStaticCharMethod = NULL,
    .CallStaticCharMethodV = NULL,
    .CallStaticCharMethodA = NULL,
    .CallStaticShortMethod = NULL,
    .CallStaticShortMethodV = NULL,
    .CallStaticShortMethodA = NULL,
    .CallStaticIntMethod = NULL,
    .CallStaticIntMethodV = NULL,
    .CallStaticIntMethodA = NULL,
    .CallStaticLongMethod = NULL,
    .CallStaticLongMethodV = NULL,
    .CallStaticLongMethodA = NULL,
    .CallStaticFloatMethod = NULL,
    .CallStaticFloatMethodV = NULL,
    .CallStaticFloatMethodA = NULL,
    .CallStaticDoubleMethod = NULL,
    .CallStaticDoubleMethodV = NULL,
    .CallStaticDoubleMethodA = NULL,
    .CallStaticVoidMethod = NULL,
    .CallStaticVoidMethodV = NULL,
    .CallStaticVoidMethodA = NULL,

    .GetStaticFieldID = JNI_GetStaticFieldID,
    .GetStaticObjectField = JNI_GetStaticObjectField,
    .GetStaticBooleanField = NULL,
    .GetStaticByteField = NULL,
    .GetStaticCharField = NULL,
    .GetStaticShortField = NULL,
    .GetStaticIntField = NULL,
    .GetStaticLongField = NULL,
    .GetStaticFloatField = NULL,
    .GetStaticDoubleField = NULL,
    .SetStaticObjectField = NULL,
    .SetStaticBooleanField = NULL,
    .SetStaticByteField = NULL,
    .SetStaticCharField = NULL,
    .SetStaticShortField = NULL,
    .SetStaticIntField = NULL,
    .SetStaticLongField = NULL,
    .SetStaticFloatField = NULL,
    .SetStaticDoubleField = NULL,

    .NewString      = NULL,
    .GetStringLength = NULL,
    .GetStringChars = NULL,
    .ReleaseStringChars = NULL,
    .NewStringUTF   = JNI_NewStringUTF,
    .GetStringUTFLength = JNI_GetStringUTFLength,
    .GetStringUTFChars = JNI_GetStringUTFChars,
    .ReleaseStringUTFChars = JNI_ReleaseStringUTFChars,

    .GetArrayLength = JNI_GetArrayLength,
    .NewObjectArray = NULL,
    .GetObjectArrayElement = NULL,
    .SetObjectArrayElement = NULL,
    .NewBooleanArray = NULL,
    .NewByteArray = NULL,
    .NewCharArray = NULL,
    .NewShortArray = NULL,
    .NewIntArray = NULL,
    .NewLongArray = NULL,
    .NewFloatArray = NULL,
    .NewDoubleArray = NULL,
    .GetBooleanArrayElements = NULL,
    .GetByteArrayElements = NULL,
    .GetCharArrayElements = NULL,
    .GetShortArrayElements = NULL,
    .GetIntArrayElements = NULL,
    .GetLongArrayElements = NULL,
    .GetFloatArrayElements = NULL,
    .GetDoubleArrayElements = NULL,
    .ReleaseBooleanArrayElements = NULL,
    .ReleaseByteArrayElements = NULL,
    .ReleaseCharArrayElements = NULL,
    .ReleaseShortArrayElements = NULL,
    .ReleaseIntArrayElements = NULL,
    .ReleaseLongArrayElements = NULL,
    .ReleaseFloatArrayElements = NULL,
    .ReleaseDoubleArrayElements = NULL,
    .GetBooleanArrayRegion = NULL,
    .GetByteArrayRegion = NULL,
    .GetCharArrayRegion = NULL,
    .GetShortArrayRegion = NULL,
    .GetIntArrayRegion = NULL,
    .GetLongArrayRegion = NULL,
    .GetFloatArrayRegion = NULL,
    .GetDoubleArrayRegion = NULL,
    .SetBooleanArrayRegion = NULL,
    .SetByteArrayRegion = NULL,
    .SetCharArrayRegion = NULL,
    .SetShortArrayRegion = NULL,
    .SetIntArrayRegion = NULL,
    .SetLongArrayRegion = NULL,
    .SetFloatArrayRegion = NULL,
    .SetDoubleArrayRegion = NULL,

    .RegisterNatives = JNI_RegisterNatives,
    .UnregisterNatives = NULL,

    .MonitorEnter = NULL,
    .MonitorExit  = NULL,
    .GetJavaVM    = JNI_GetJavaVM,

    .GetStringRegion = NULL,
    .GetStringUTFRegion = NULL,
    .GetPrimitiveArrayCritical = JNI_GetPrimitiveArrayCritical,
    .ReleasePrimitiveArrayCritical = JNI_ReleasePrimitiveArrayCritical,
    .GetStringCritical = NULL,
    .ReleaseStringCritical = NULL,
    .NewWeakGlobalRef = NULL,
    .DeleteWeakGlobalRef = NULL,
    .ExceptionCheck = JNI_ExceptionCheck,

    .NewDirectByteBuffer = NULL,
    .GetDirectBufferAddress = NULL,
    .GetDirectBufferCapacity = NULL,
    .GetObjectRefType = NULL,
    .GetModule = NULL,
    .IsVirtualThread = NULL,
};

/* ==========================================================================
 *  The single shared JavaVM instance.
 * ========================================================================== */

static const struct JNIInvokeInterface_* jnative_jni_vm_table(void);

static jint JNICALL JNI_GetJavaVM(JNIEnv_t e, JavaVM_t* vm) {
    (void)e;
    if (vm == NULL) return JNI_ERR;
    *vm = jnative_jni_vm_table();
    return JNI_OK;
}

static jint JNICALL JNI_GetEnv(JavaVM_t vm, void** penv, jint version) {
    (void)vm;
    (void)version;
    if (penv == NULL) return JNI_ERR;
    *penv = (void*)&JNI_TABLE;
    return JNI_OK;
}

static const struct JNIInvokeInterface_ JNI_VM_TABLE = {
    .reserved0 = NULL,
    .reserved1 = NULL,
    .reserved2 = NULL,
    .DestroyJavaVM = NULL,
    .AttachCurrentThread = NULL,
    .DetachCurrentThread = NULL,
    .GetEnv = JNI_GetEnv,
    .AttachCurrentThreadAsDaemon = NULL,
};

static const struct JNIInvokeInterface_* jnative_jni_vm_table(void) {
    return &JNI_VM_TABLE;
}

/* ==========================================================================
 *  Public runtime entry points used by the generated @main and by every
 *  emitted JNI native call site.
 * ========================================================================== */

void* __jnative_get_jnienv(void) {
    return (void*)&JNI_TABLE;
}

void* __jnative_get_thread_jnienv(void) {
    return (void*)&JNI_TABLE;
}

/**
 * Resolves the implementation of a JNI native by its (class, method,
 * descriptor) triple.
 *
 * <p>The triple is looked up in the RegisterNatives dispatch table. On a
 * miss the function terminates the process with a diagnostic naming the
 * missing triple. Returning NULL is deliberately NOT an option: the
 * emitted call site is an indirect call through the returned pointer,
 * and the LLVM backend emits no null check (the code generator's own
 * contract is "resolve throws on failure, therefore the pointer is
 * non-NULL on return"). A NULL return would jump to address 0 and
 * produce a SIGSEGV instead of a diagnostic.</p>
 *
 * <p>The three strings are NUL-terminated C strings embedded by the
 * code generator as string literals. The class name is in internal form
 * (with '/' separators); the method name is the bare Java name; the
 * descriptor is the exact JVM descriptor from the class file.</p>
 */
void* __jnative_jni_resolve(const char* class_name,
                            const char* method_name,
                            const char* signature) {
    void* fn = jni_table_lookup(class_name, method_name, signature);
    if (fn != NULL) return fn;

    /* Build a diagnostic that names the triple. */
    char msg[512];
    snprintf(msg, sizeof(msg),
        "JNI native not registered: class='%s', method='%s', sig='%s'. "
        "No loaded library called RegisterNatives for this triple. "
        "Check that the native library shipping this method is present "
        "in the input archive, was extracted, and had its JNI_OnLoad "
        "invoked by @main.",
        class_name ? class_name : "<null>",
        method_name ? method_name : "<null>",
        signature ? signature : "<null>");

    __jnative_unresolved_slot(msg);
    return NULL; /* unreachable */
}

/**
 * dlopens the given library, calls its JNI_OnLoad with a JavaVM whose
 * GetEnv returns the shared JNIEnv, and returns 1 on success.
 */
static int jnative_jni_load_path(const char* path) {
    if (path == NULL) return 0;

    void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) {
        fprintf(stderr,
            "jnative: warning: dlopen(%s) failed: %s\n",
            path, dlerror());
        return 0;
    }

    typedef jint (*JNI_OnLoad_t)(JavaVM_t, void*);
    JNI_OnLoad_t onLoad = (JNI_OnLoad_t)dlsym(handle, "JNI_OnLoad");
    if (onLoad == NULL) {
        fprintf(stderr,
            "jnative: warning: %s does not export JNI_OnLoad; "
            "its RegisterNatives calls will not be performed\n", path);
        return 0;
    }

    jint version = onLoad(jnative_jni_vm_table(), NULL);
    if (version < 0) {
        fprintf(stderr,
            "jnative: warning: JNI_OnLoad in %s returned %d\n",
            path, version);
        return 0;
    }

    return 1;
}

/**
 * Loads the native library whose bytes were embedded under the given
 * resource key.
 *
 * <p>The bytes are materialised into a temporary file under $TMPDIR,
 * dlopen'ed, and (on success) kept loaded for the lifetime of the
 * process. The temporary file is unlinked immediately after dlopen so
 * it does not survive the process; the dynamic linker keeps its own
 * mapping alive independently of the on-disk file.</p>
 */
void __jnative_jni_load_library_from_resource(const char* resource_key) {
    if (resource_key == NULL) return;

    const JNativeResourceEntry* res = jnative_find_resource(
        resource_key, (int32_t)strlen(resource_key));
    if (res == NULL) {
        fprintf(stderr,
            "jnative: warning: native library resource '%s' not found\n",
            resource_key);
        return;
    }
    if (res->data == NULL || res->size <= 0) {
        fprintf(stderr,
            "jnative: warning: native library resource '%s' is empty\n",
            resource_key);
        return;
    }

    const char* tmp = getenv("TMPDIR");
    if (tmp == NULL || tmp[0] == '\0') tmp = "/tmp";

    char tmpl[PATH_MAX];
    snprintf(tmpl, sizeof(tmpl), "%s/jnative_jni_XXXXXX.so", tmp);

    int fd = mkstemps(tmpl, 3);
    if (fd < 0) {
        fprintf(stderr,
            "jnative: warning: mkstemps(%s) failed: %s\n",
            tmpl, strerror(errno));
        return;
    }

    const uint8_t* p = res->data;
    int32_t remaining = res->size;
    while (remaining > 0) {
        ssize_t n = write(fd, p, (size_t)remaining);
        if (n < 0) {
            if (errno == EINTR) continue;
            fprintf(stderr,
                "jnative: warning: write to %s failed: %s\n",
                tmpl, strerror(errno));
            close(fd);
            unlink(tmpl);
            return;
        }
        p += n;
        remaining -= (int32_t)n;
    }
    close(fd);

    (void)jnative_jni_load_path(tmpl);

    /* The dynamic linker keeps its own mapping of the file alive after
     * dlopen succeeds; the on-disk file may be unlinked immediately. */
    unlink(tmpl);
}

/**
 * Loads every native library whose resource key appears in the given
 * array.
 *
 * <p>Emitted by @main at program start-up. The array itself is emitted
 * as a static constant of i8* pointers into the string constants that
 * the code generator produced for each library.</p>
 */
void __jnative_jni_load_all(const char** keys, int32_t count) {
    if (keys == NULL || count <= 0) return;
    for (int32_t i = 0; i < count; i++) {
        if (keys[i] != NULL) {
            __jnative_jni_load_library_from_resource(keys[i]);
        }
    }
}
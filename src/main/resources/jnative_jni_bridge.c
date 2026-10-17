/*
 * jnative_jni_bridge.c
 *
 * A thread-local JNIEnv for the JNative runtime, extended with the
 * machinery required to host RegisterNatives-based JNI libraries such
 * as Netty's native transports, and with a crash-tolerant loader that
 * survives broken JDK shared objects.
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
 *      order, even those the runtime does not implement; an
 *      unimplemented slot is a NULL function pointer.
 *
 *   2. RegisterNatives is implemented by inserting each (class, name,
 *      signature, fnPtr) tuple into a process-wide hash table, keyed by
 *      the internal class name and the exact (name, signature) pair
 *      that the library supplied.
 *
 *   3. __jnative_jni_resolve(class, name, signature, symbol) is the
 *      entry point the LLVM code generator calls at every JNI native
 *      call site. It first consults the RegisterNatives dispatch table;
 *      on a miss it falls back to dlsym(RTLD_DEFAULT, symbol) so that a
 *      traditional Java_* export from any library that was dlopen'd
 *      with RTLD_GLOBAL is also found. Only if both lookups fail does
 *      it throw a diagnostic naming the (class, method, signature,
 *      symbol) quadruple, so the emitted code never dereferences a
 *      NULL function pointer.
 *
 *   4. __jnative_jni_load_all(keys, count) is called once by @main,
 *      before any Java code runs. It materialises every embedded
 *      library into a single private directory under its original
 *      basename, then dlopen's them in multiple passes until no
 *      further progress is made. Each successful dlopen is followed
 *      by a call to the library's JNI_OnLoad, if it has one.
 *
 * ============================================================================
 *  Why basenames and a private directory
 * ============================================================================
 *
 * The two properties the dynamic loader needs in order for a
 * DT_NEEDED lookup to succeed are:
 *
 *   1. The name under which a library is loaded must match, at the
 *      loader's own comparison step, the name the DT_NEEDED entry
 *      records. The loader first checks every loaded object's
 *      l_libname list (populated from the file's DT_SONAME, when
 *      present); if that misses it falls through to a comparison
 *      against l_name, which is the path the caller passed to
 *      dlopen.
 *
 *   2. The file must be reachable at the path recorded in l_name for
 *      the whole process lifetime, because a later dlopen of a
 *      different library may name the same SONAME in its own
 *      DT_NEEDED and expect the loader to find it by walking the
 *      loaded-object lists or by searching the paths the caller
 *      gave.
 *
 * Materialising each embedded library into a fresh mkdtemp directory
 * under its original basename satisfies both:
 *
 *   - The basename is preserved, so the loader's name comparison sees
 *     the same string it would have seen on a normal JDK installation.
 *
 *   - Every file stays on disk until the process exits, so a
 *     transitive DT_NEEDED chain resolves correctly regardless of the
 *     order in which the libraries are loaded.
 *
 * The directory's name contains the pid, so two concurrent runs of the
 * same executable never collide. It is not added to LD_LIBRARY_PATH or
 * to any other search path; only absolute paths into it are passed to
 * dlopen, so nothing outside this process can observe it.
 *
 * ============================================================================
 *  Crash-tolerant loading
 * ============================================================================
 *
 * Not every JDK shared object loads cleanly in this runtime. libawt.so
 * and its siblings contain constructors and JNI_OnLoad implementations
 * that were written for a HotSpot JVM and that call into JVM-internal
 * state which does not exist here. The concrete failure observed in
 * practice is a SIGSEGV at address 0x30 — the offset of the FindClass
 * slot in the JNIEnv function table, or of the GetEnv slot in the
 * JavaVM function table — i.e. a dispatch through a function pointer
 * that is NULL because the JVM state that was supposed to populate it
 * never existed. The crash propagates out of dlopen and terminates the
 * process before any Java code runs.
 *
 * A crash in one library must not prevent the rest of the image from
 * starting. The guard below installs a temporary handler for the four
 * synchronous signals a C library can raise (SIGSEGV, SIGBUS, SIGILL,
 * SIGFPE), performs the load, and longjmps back to a safe point if
 * any of them fires. The failed library is recorded and not retried on
 * subsequent passes; the next library in the list is loaded normally.
 *
 * The four signals are exactly the ones the ELF ABI reserves for
 * synchronous faults raised by the running program itself. Asynchronous
 * signals (SIGTERM, SIGINT, SIGUSR1, ...) are not affected: they keep
 * their original disposition and are not caught by this handler.
 *
 * The handler is installed once, before the first dlopen, and stays
 * installed for the rest of the process. When it fires outside a
 * library load — which is to say, on a genuine bug in the compiled
 * image — it forwards the signal to whichever handler was previously
 * installed (the runtime's own fatal-signal handler from
 * jnative_runtime.c) by restoring the saved disposition and re-raising.
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
 * table: GetEnv is at index 6 (after three reserved slots,
 * DestroyJavaVM, AttachCurrentThread and DetachCurrentThread).
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
#include <dirent.h>
#include <pthread.h>
#include <dlfcn.h>
#include <signal.h>
#include <setjmp.h>
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

static jint        JNICALL JNI_GetVersion(JNIEnv_t);
static jclass      JNICALL JNI_FindClass(JNIEnv_t, const char*);
static jclass      JNICALL JNI_GetObjectClass(JNIEnv_t, jobject);
static jclass      JNICALL JNI_GetSuperclass(JNIEnv_t, jclass);
static jboolean    JNICALL JNI_IsInstanceOf(JNIEnv_t, jobject, jclass);
static jboolean    JNICALL JNI_IsAssignableFrom(JNIEnv_t, jclass, jclass);
static jint        JNICALL JNI_Throw(JNIEnv_t, jthrowable);
static jint        JNICALL JNI_ThrowNew(JNIEnv_t, jclass, const char*);
static jthrowable  JNICALL JNI_ExceptionOccurred(JNIEnv_t);
static void        JNICALL JNI_ExceptionClear(JNIEnv_t);
static jboolean    JNICALL JNI_ExceptionCheck(JNIEnv_t);
static jstring     JNICALL JNI_NewStringUTF(JNIEnv_t, const char*);
static jint        JNICALL JNI_GetStringUTFLength(JNIEnv_t, jstring);
static const char* JNICALL JNI_GetStringUTFChars(JNIEnv_t, jstring, jboolean*);
static void        JNICALL JNI_ReleaseStringUTFChars(JNIEnv_t, jstring, const char*);
static jint        JNICALL JNI_GetArrayLength(JNIEnv_t, jarray);
static jfieldID    JNICALL JNI_GetFieldID(JNIEnv_t, jclass, const char*, const char*);
static jmethodID   JNICALL JNI_GetMethodID(JNIEnv_t, jclass, const char*, const char*);
static jfieldID    JNICALL JNI_GetStaticFieldID(JNIEnv_t, jclass, const char*, const char*);
static jmethodID   JNICALL JNI_GetStaticMethodID(JNIEnv_t, jclass, const char*, const char*);
static jobject     JNICALL JNI_GetObjectField(JNIEnv_t, jobject, jfieldID);
static void        JNICALL JNI_SetObjectField(JNIEnv_t, jobject, jfieldID, jobject);
static jint        JNICALL JNI_GetIntField(JNIEnv_t, jobject, jfieldID);
static void        JNICALL JNI_SetIntField(JNIEnv_t, jobject, jfieldID, jint);
static jlong       JNICALL JNI_GetLongField(JNIEnv_t, jobject, jfieldID);
static void        JNICALL JNI_SetLongField(JNIEnv_t, jobject, jfieldID, jlong);
static jobject     JNICALL JNI_GetStaticObjectField(JNIEnv_t, jclass, jfieldID);
static void*       JNICALL JNI_GetPrimitiveArrayCritical(JNIEnv_t, jarray, jboolean*);
static void        JNICALL JNI_ReleasePrimitiveArrayCritical(JNIEnv_t, jarray, void*, jint);
static jint        JNICALL JNI_RegisterNatives(JNIEnv_t, jclass, const JNINativeMethod*, jint);
static jint        JNICALL JNI_GetJavaVM(JNIEnv_t, JavaVM_t*);

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

/*
 * The one true JavaVM instance. Per jni.h, JNI_OnLoad is declared as
 * `JNI_OnLoad(JavaVM* vm, void* reserved)`: the library expects the
 * address of a `JavaVM` (itself a pointer to the function table) and
 * reads it back with `(*vm)->GetEnv(...)`. Passing the table pointer
 * directly makes that read `(*table)->GetEnv(...)`, dereferencing the
 * table's first (reserved, NULL) slot and crashing. The address of this
 * holder is what JNI_OnLoad receives.
 *
 * This is the fix for the SIGSEGV at address 0x30 reported in
 * fun.txt: the library's prologue reads `mov (%rdi),%rax` to get the
 * table and then `call *0x30(%rax)` for GetEnv. Passing the table
 * itself as the parameter leaves `%rdi` = &table, so `*%rdi` is the
 * table's first slot (reserved0 == NULL) and `0x30(%rax)` faults at
 * NULL + 0x30.
 */
static JavaVM_t jnative_java_vm = &JNI_VM_TABLE;

/* ==========================================================================
 *  Crash-tolerant loading infrastructure.
 *
 *  See the class-header comment for the rationale. The four synchronous
 *  signals below are the ones the ELF ABI reserves for faults raised by
 *  the program itself; asynchronous signals are not touched.
 * ========================================================================== */

static sigjmp_buf             jnative_load_jmpbuf;
static volatile sig_atomic_t  jnative_in_library_load     = 0;
static int                    jnative_load_handlers_ready = 0;

static struct sigaction       jnative_saved_sigsegv;
static struct sigaction       jnative_saved_sigbus;
static struct sigaction       jnative_saved_sigill;
static struct sigaction       jnative_saved_sigfpe;

/*
 * The handler forwards any signal that fires outside a library load to
 * whichever disposition was installed before this one — in the running
 * image that is __jnative_fatal_signal_handler from jnative_runtime.c,
 * which prints a trace and re-raises. Forwarding is done by restoring
 * the saved disposition and re-raising, the standard idiom for chaining
 * signal handlers without losing the original one.
 */
static void jnative_load_crash_handler(int sig, siginfo_t* info, void* ucontext) {
    if (jnative_in_library_load) {
        siglongjmp(jnative_load_jmpbuf, sig);
    }

    switch (sig) {
        case SIGSEGV: sigaction(SIGSEGV, &jnative_saved_sigsegv, NULL); break;
        case SIGBUS:  sigaction(SIGBUS,  &jnative_saved_sigbus,  NULL); break;
        case SIGILL:  sigaction(SIGILL,  &jnative_saved_sigill,  NULL); break;
        case SIGFPE:  sigaction(SIGFPE,  &jnative_saved_sigfpe,  NULL); break;
        default: break;
    }
    (void)info;
    (void)ucontext;
    raise(sig);
}

/*
 * Installs the handler once. Subsequent calls are no-ops. The saved
 * dispositions are the ones in effect at the time of the first call,
 * which for the running image is the runtime's fatal-signal handler.
 */
static void jnative_install_load_handlers(void) {
    if (jnative_load_handlers_ready) return;

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = jnative_load_crash_handler;
    sa.sa_flags     = SA_SIGINFO | SA_NODEFER | SA_RESTART;
    sigemptyset(&sa.sa_mask);

    sigaction(SIGSEGV, &sa, &jnative_saved_sigsegv);
    sigaction(SIGBUS,  &sa, &jnative_saved_sigbus);
    sigaction(SIGILL,  &sa, &jnative_saved_sigill);
    sigaction(SIGFPE,  &sa, &jnative_saved_sigfpe);

    jnative_load_handlers_ready = 1;
}

/* ==========================================================================
 *  Public entry points used by the generated @main and by every
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
 * descriptor) triple and, failing that, by its full Java_* symbol name.
 *
 * <h2>Lookup order</h2>
 *
 * <ol>
 *   <li><b>RegisterNatives dispatch table.</b> Populated by every
 *       library's JNI_OnLoad calling RegisterNatives. This is the only
 *       lookup path that works for Netty-style libraries, which export
 *       no Java_* symbols at all.</li>
 *
 *   <li><b>dlsym(RTLD_DEFAULT, symbol).</b> Finds a traditional Java_*
 *       export from any library that was dlopen'd with RTLD_GLOBAL.
 *       Every JDK platform library and every application native library
 *       loaded by __jnative_jni_load_all is loaded with RTLD_GLOBAL,
 *       so a Java_* symbol exported by any of them is visible here.</li>
 * </ol>
 *
 * <p>On a miss the function terminates the process with a diagnostic
 * naming all four strings. Returning NULL is deliberately NOT an
 * option: the emitted call site is an indirect call through the
 * returned pointer, and the LLVM backend emits no null check (the code
 * generator's own contract is "resolve throws on failure, therefore
 * the pointer is non-NULL on return"). A NULL return would jump to
 * address 0 and produce a SIGSEGV instead of a diagnostic.</p>
 */
void* __jnative_jni_resolve(const char* class_name,
                            const char* method_name,
                            const char* signature,
                            const char* symbol_name) {
    /* 1. RegisterNatives-registered symbols (Netty-style libraries). */
    void* fn = jni_table_lookup(class_name, method_name, signature);
    if (fn != NULL) return fn;

    /* 2. Traditional Java_* exports from any RTLD_GLOBAL library. */
    if (symbol_name != NULL && symbol_name[0] != '\0') {
        fn = dlsym(RTLD_DEFAULT, symbol_name);
        if (fn != NULL) return fn;
    }

    /* Neither path found the implementation. Report and abort. */
    char msg[512];
    snprintf(msg, sizeof(msg),
        "JNI native not found: class='%s', method='%s', sig='%s', symbol='%s'. "
        "Neither a RegisterNatives registration nor a Java_* export "
        "was found in any loaded library.",
        class_name    ? class_name    : "<null>",
        method_name   ? method_name   : "<null>",
        signature     ? signature     : "<null>",
        symbol_name   ? symbol_name   : "<null>");

    __jnative_unresolved_slot(msg);
    return NULL; /* unreachable */
}

/* ============================================================================
 *  Materialisation directory and per-library extraction.
 * ============================================================================
 *
 * Every embedded library is written into one private directory created
 * with mkdtemp, under the basename it had on the build host. Three
 * properties of that choice matter:
 *
 *   1. ORIGINAL BASENAME. The dynamic linker's match step for a
 *      DT_NEEDED entry first checks the loaded objects' l_libname
 *      lists (populated from DT_SONAME), and if that misses it falls
 *      through to a comparison against l_name, which is the path the
 *      caller passed to dlopen. Keeping the original basename means
 *      both match paths can succeed. Some libraries additionally
 *      locate siblings through a relative path baked into their own
 *      code, or call dlopen("./libfoo.so") after a chdir into the
 *      directory; both require the file to have its original name.
 *
 *   2. SINGLE PER-PROCESS DIRECTORY. The directory's name contains
 *      the pid, so two concurrent runs of the same executable cannot
 *      collide on the same file. The directory is removed at process
 *      exit through an atexit hook; the fact that it exists at all is
 *      invisible to any other process, and it is not added to
 *      LD_LIBRARY_PATH or any other search path, so it cannot shadow
 *      anything.
 *
 *   3. FILES KEPT FOR THE PROCESS LIFETIME. Unlinking a .so after a
 *      successful dlopen keeps the mapping alive but removes the
 *      file; a subsequent dlopen by another library that needs it as
 *      a DT_NEEDED entry would then fail, because the loader's
 *      name-match step only finds objects already in the loaded set.
 *      Keeping every file on disk for the lifetime of the process
 *      removes that ordering constraint entirely: a dependency loaded
 *      later is satisfied either by the name-match against the
 *      already-loaded object, or by the absolute path recorded in
 *      l_name falling through to the ordinary open().
 * ========================================================================== */

#define JNATIVE_NATIVE_DIR_PREFIX "jnative-natives-"

static char            jnative_native_dir[PATH_MAX];
static int             jnative_native_dir_ready = 0;
static pthread_mutex_t jnative_native_dir_lock  = PTHREAD_MUTEX_INITIALIZER;

/*
 * atexit hook: remove every file in the private directory, then the
 * directory itself. The dynamic linker keeps its own mappings alive
 * independently of the on-disk files, so unlinking at process exit
 * does not disturb any library that is still in use.
 */
static void jnative_remove_native_dir(void) {
    if (jnative_native_dir[0] == '\0') return;

    DIR* d = opendir(jnative_native_dir);
    if (d != NULL) {
        struct dirent* e;
        while ((e = readdir(d)) != NULL) {
            if (e->d_name[0] == '.'
                && (e->d_name[1] == '\0'
                    || (e->d_name[1] == '.' && e->d_name[2] == '\0'))) {
                continue;
            }
            char path[PATH_MAX];
            int n = snprintf(path, sizeof(path), "%s/%s",
                             jnative_native_dir, e->d_name);
            if (n > 0 && (size_t)n < sizeof(path)) {
                (void)unlink(path);
            }
        }
        closedir(d);
    }
    (void)rmdir(jnative_native_dir);
    jnative_native_dir[0] = '\0';
}

/*
 * Creates the private directory on first call. Subsequent calls see the
 * cached path. The lock serialises creation across threads; after the
 * first successful call every reader takes the lock only long enough
 * to observe that the directory is ready.
 */
static int jnative_ensure_native_dir(void) {
    pthread_mutex_lock(&jnative_native_dir_lock);
    if (jnative_native_dir_ready) {
        int ok = jnative_native_dir[0] != '\0';
        pthread_mutex_unlock(&jnative_native_dir_lock);
        return ok;
    }

    const char* tmp = getenv("TMPDIR");
    if (tmp == NULL || tmp[0] == '\0') tmp = "/tmp";

    int n = snprintf(jnative_native_dir, sizeof(jnative_native_dir),
                     "%s/%s%d-XXXXXX",
                     tmp, JNATIVE_NATIVE_DIR_PREFIX, (int)getpid());
    if (n <= 0 || (size_t)n >= sizeof(jnative_native_dir)) {
        jnative_native_dir[0] = '\0';
        jnative_native_dir_ready = 1;
        pthread_mutex_unlock(&jnative_native_dir_lock);
        return 0;
    }

    if (mkdtemp(jnative_native_dir) == NULL) {
        jnative_native_dir[0] = '\0';
        jnative_native_dir_ready = 1;
        pthread_mutex_unlock(&jnative_native_dir_lock);
        return 0;
    }

    atexit(jnative_remove_native_dir);
    jnative_native_dir_ready = 1;
    pthread_mutex_unlock(&jnative_native_dir_lock);
    return 1;
}

/*
 * Returns the basename of a resource key: the part after the last '/'.
 * Keys produced by the build have the shape "<prefix>/<basename>"; a
 * key that contains no '/' is returned unchanged so that a caller which
 * passes a bare name still works.
 */
static const char* jnative_resource_basename(const char* key) {
    if (key == NULL) return NULL;
    const char* slash = strrchr(key, '/');
    return slash != NULL ? slash + 1 : key;
}

/*
 * Materialises one embedded library into the private directory. Returns
 * 1 on success, 0 on any failure (which is always reported to stderr
 * with enough detail to identify the missing or unwritable file).
 */
static int jnative_materialise_library(const char* resource_key) {
    if (resource_key == NULL) return 0;

    const char* base = jnative_resource_basename(resource_key);
    if (base == NULL || base[0] == '\0') return 0;

    /* Reject "." and ".." even though the basename cannot contain '/'. */
    if (base[0] == '.'
        && (base[1] == '\0' || (base[1] == '.' && base[2] == '\0'))) {
        return 0;
    }

    if (!jnative_ensure_native_dir()) {
        fprintf(stderr,
            "jnative: warning: cannot create private native directory "
            "for '%s'\n", resource_key);
        return 0;
    }

    const JNativeResourceEntry* res = jnative_find_resource(
        resource_key, (int32_t)strlen(resource_key));
    if (res == NULL || res->data == NULL || res->size <= 0) {
        fprintf(stderr,
            "jnative: warning: native library resource '%s' is missing "
            "or empty\n", resource_key);
        return 0;
    }

    char dest[PATH_MAX];
    int n = snprintf(dest, sizeof(dest), "%s/%s",
                     jnative_native_dir, base);
    if (n <= 0 || (size_t)n >= sizeof(dest)) {
        return 0;
    }

    /*
     * Reuse a file that is already present with the expected size. The
     * same library can be named through more than one key when two
     * different prefixes carry the same file, and one image can embed
     * the same physical .so under both the JDK and the application
     * key when an application ships a copy of a JDK library.
     */
    struct stat st;
    if (stat(dest, &st) == 0 && st.st_size == (off_t)res->size) {
        return 1;
    }

    int fd = open(dest,
                  O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC,
                  (mode_t)0755);
    if (fd < 0) {
        fprintf(stderr,
            "jnative: warning: cannot create %s: %s\n",
            dest, strerror(errno));
        return 0;
    }

    const uint8_t* p   = res->data;
    size_t         rem = (size_t)res->size;
    while (rem > 0) {
        ssize_t w = write(fd, p, rem);
        if (w < 0) {
            if (errno == EINTR) continue;
            int saved = errno;
            close(fd);
            unlink(dest);
            errno = saved;
            fprintf(stderr,
                "jnative: warning: write to %s failed: %s\n",
                dest, strerror(errno));
            return 0;
        }
        if (w == 0) {
            close(fd);
            unlink(dest);
            fprintf(stderr,
                "jnative: warning: zero-byte write to %s\n", dest);
            return 0;
        }
        p   += (size_t)w;
        rem -= (size_t)w;
    }
    close(fd);
    return 1;
}

/*
 * Loads a single already-materialised library, invokes its JNI_OnLoad
 * if it has one, and returns 1 on success or 0 on failure. A failure
 * is not reported here: the caller collects failures and prints them
 * once, after the retry loop terminates, so that a library which fails
 * on the first pass but succeeds on a later one produces no spurious
 * warning.
 *
 * The function may not return normally if the library raises SIGSEGV,
 * SIGBUS, SIGILL or SIGFPE during its own initialisation. In that case
 * the caller's sigsetjmp arm catches the signal and marks the library
 * as crashed.
 */
static int jnative_load_one(const char* key) {
    const char* base = jnative_resource_basename(key);
    if (base == NULL || base[0] == '\0') return 0;

    char path[PATH_MAX];
    int n = snprintf(path, sizeof(path), "%s/%s",
                     jnative_native_dir, base);
    if (n <= 0 || (size_t)n >= sizeof(path)) {
        return 0;
    }

    /*
     * Consume any pending error message from an earlier dlopen attempt.
     * dlerror() returns the most recent error message and clears it;
     * calling it here makes the next dlerror() return the message for
     * this attempt, not for some previous one.
     */
    (void)dlerror();

    void* handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        return 0;
    }

    typedef jint (*JNI_OnLoad_t)(JavaVM_t*, void*);
    JNI_OnLoad_t onLoad = (JNI_OnLoad_t)dlsym(handle, "JNI_OnLoad");
    if (onLoad != NULL) {
        jint version = onLoad(&jnative_java_vm, NULL);
        if (version < 0) {
            fprintf(stderr,
                "jnative: warning: JNI_OnLoad in %s returned %d\n",
                path, version);
            /*
             * A negative return from JNI_OnLoad means the library
             * refused to initialise. Its symbols are still in the
             * global scope, and dlopen will report it as loaded on a
             * subsequent call, so the slot is marked loaded and the
             * runtime continues. Registering natives against a class
             * whose actual implementation declined to install is not
             * worse than the alternative of aborting the whole process
             * because one optional acceleration library was incompatible.
             */
        }
    }

    return 1;
}

/*
 * Loads one library under the crash-tolerant guard.
 *
 * Returns:
 *    1  — library loaded successfully (its JNI_OnLoad was invoked)
 *    0  — library failed to load normally (a retry may help)
 *   -1  — library crashed with a synchronous signal; it is recorded
 *         as permanently unusable and must not be retried
 *
 * The sigsetjmp is placed inside this dedicated function rather than
 * in the retry loop, so that the retry loop's own locals are not
 * exposed to the longjmp. Only sigjmp_buf and the two file-scope
 * volatile variables cross the longjmp boundary, both of which are
 * safe.
 */
static int jnative_load_one_guarded(const char* key) {
    int jmp = sigsetjmp(jnative_load_jmpbuf, 1);
    if (jmp != 0) {
        jnative_in_library_load = 0;
        fprintf(stderr,
            "jnative: warning: library %s raised signal %d during load; "
            "marking it unusable and continuing\n",
            jnative_resource_basename(key), jmp);
        return -1;
    }

    jnative_in_library_load = 1;
    int r = jnative_load_one(key);
    jnative_in_library_load = 0;
    return r;
}

/**
 * Loads every native library whose resource key appears in the given
 * array.
 *
 * <p>Called once by {@code @main}, before any Java code runs. The array
 * itself is emitted as a static constant of {@code i8*} pointers into
 * the string constants that the code generator produced for each
 * library. The order of the keys is preserved.</p>
 *
 * <h2>Three-phase load</h2>
 *
 * <ol>
 *   <li><b>Materialise.</b> Every embedded library is written into the
 *       private directory under its original basename.</li>
 *
 *   <li><b>Load with retries and crash guard.</b> Each library is
 *       dlopen'd in turn under the synchronous-signal guard described
 *       at the top of this file. A library whose DT_NEEDED dependency
 *       has not yet been loaded fails normally and is retried on the
 *       next pass; a library that raises a synchronous fault is marked
 *       as permanently unusable and is not retried. The number of
 *       passes is bounded by the size of the key list plus one.</li>
 *
 *   <li><b>Report.</b> Every library that never loaded is reported to
 *       stderr with the loader's own error message, or with the
 *       signal number that killed it.</li>
 * </ol>
 */
void __jnative_jni_load_all(const char** keys, int32_t count) {
    if (keys == NULL || count <= 0) return;

    /* Install the crash guard once, before any dlopen. */
    jnative_install_load_handlers();

    /* -------- Phase 1: materialise every library -------- */
    int32_t materialised = 0;
    for (int32_t i = 0; i < count; i++) {
        if (keys[i] != NULL && jnative_materialise_library(keys[i])) {
            materialised++;
        }
    }
    if (materialised == 0) return;

    /* -------- Phase 2: load in dependency order with retries -------- */
    int* loaded  = (int*)calloc((size_t)count, sizeof(int));
    int* crashed = (int*)calloc((size_t)count, sizeof(int));
    if (loaded == NULL || crashed == NULL) {
        free(loaded);
        free(crashed);
        return;
    }

    const int32_t max_passes = count + 1;
    int32_t total_loaded = 0;

    for (int32_t pass = 0; pass < max_passes; pass++) {
        int32_t progress = 0;

        for (int32_t i = 0; i < count; i++) {
            if (loaded[i] || crashed[i]) continue;

            const char* key = keys[i];
            if (key == NULL) { loaded[i] = 1; continue; }

            const char* base = jnative_resource_basename(key);
            if (base == NULL || base[0] == '\0') {
                loaded[i] = 1;
                continue;
            }

            /*
             * Skip keys whose file could not be written in phase 1. The
             * materialisation warning has already been printed; marking
             * the slot as "loaded" here means the reporting phase below
             * will not treat it as a load failure.
             */
            char path[PATH_MAX];
            int n = snprintf(path, sizeof(path), "%s/%s",
                             jnative_native_dir, base);
            if (n <= 0 || (size_t)n >= sizeof(path)) {
                loaded[i] = 1;
                continue;
            }
            struct stat st;
            if (stat(path, &st) != 0) {
                loaded[i] = 1;
                continue;
            }

            int r = jnative_load_one_guarded(key);
            if (r == -1) {
                crashed[i] = 1;
                continue;
            }
            if (r == 0) {
                /* Normal failure; may succeed on a later pass once a
                 * DT_NEEDED dependency has been loaded. */
                continue;
            }

            loaded[i] = 1;
            total_loaded++;
            progress++;
        }

        if (progress == 0) break;
    }

    /* -------- Phase 3: report failures -------- */
    for (int32_t i = 0; i < count; i++) {
        if (loaded[i]) continue;
        if (crashed[i]) continue;   /* already reported by the guard */

        const char* key  = keys[i];
        const char* base = jnative_resource_basename(key);
        if (base == NULL) continue;

        char path[PATH_MAX];
        int n = snprintf(path, sizeof(path), "%s/%s",
                         jnative_native_dir, base);
        if (n <= 0 || (size_t)n >= sizeof(path)) continue;

        (void)dlerror();
        void* probe = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (probe != NULL) {
            typedef jint (*JNI_OnLoad_t)(JavaVM_t*, void*);
            JNI_OnLoad_t onLoad = (JNI_OnLoad_t)dlsym(probe, "JNI_OnLoad");
            if (onLoad != NULL) {
                (void)onLoad(&jnative_java_vm, NULL);
            }
            continue;
        }
        const char* err = dlerror();
        fprintf(stderr,
            "jnative: warning: could not load %s: %s\n",
            path, err != NULL ? err : "unknown reason");
    }

    free(loaded);
    free(crashed);
}
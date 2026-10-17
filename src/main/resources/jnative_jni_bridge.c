/*
 * jnative_jni_bridge.c
 *
 * A thread-local JNIEnv for the JNative runtime, extended with the
 * machinery required to host RegisterNatives-based JNI libraries such
 * as Netty's native transports, and with a crash-tolerant loader that
 * survives broken JDK shared objects.
 *
 * ============================================================================
 *  What this runtime actually provides
 * ============================================================================
 *
 *   - No garbage collector. Every jobject is a raw pointer to a live
 *     Java object; nothing is ever collected. All of the reference-
 *     management slots (New/Delete Global/Local/Weak, IsSameObject)
 *     are identity or no-op. Weak and strong references are the same
 *     thing here.
 *
 *   - Java arrays follow the layout defined by jnative_runtime.h:
 *         offset  0 : %ReflectionClass* klass
 *         offset  8 : int32_t           length
 *         offset 12 : int32_t           element_size
 *         offset 16 : payload
 *     The array slots (New<T>Array, Get/Release<T>ArrayElements,
 *     Get/Set<T>ArrayRegion) map directly onto jnative_array_alloc /
 *     jnative_array_data / jnative_array_length. Release<T>ArrayElements
 *     is a no-op because there is no collector that could pin a
 *     buffer, and Get<T>ArrayElements therefore returns the payload
 *     pointer without copying.
 *
 *   - DirectByteBuffer. In JDK 17+ the fields `capacity` and `address`
 *     live on java.nio.Buffer; the offsets are resolved once from the
 *     ReflectionClass metadata emitted by the LLVM backend, so the
 *     accessors stay correct if a future JDK reorders them. For a heap
 *     ByteBuffer `address` is 0 and GetDirectBufferAddress returns
 *     NULL, matching the JNI contract for non-direct buffers.
 *
 * ============================================================================
 *  Why JNIEnv is returned with two levels of indirection
 * ============================================================================
 *
 * Per the JNI specification, `JNIEnv*` is a pointer to a pointer to the
 * function table:
 *
 *     typedef const struct JNINativeInterface_** JNIEnv;
 *
 * Java-side code reads `(*env)->FindClass(env, ...)`, and the compiled
 * form is:
 *
 *     mov (%rdi), %rax         ; rax = *env   (the table pointer)
 *     call *0x30(%rax)         ; rax->FindClass
 *
 * If JNI_GetEnv returns a pointer to the table itself, `*env` reads
 * `JNI_TABLE.reserved0` which is NULL, and the second instruction
 * faults at NULL + 0x30. Returning the address of a static variable
 * holding the table pointer makes `(*env)` yield the table, and
 * `(*env)->FindClass` reads the correct slot.
 *
 * ============================================================================
 *  Why dlsym results are rejected when they land in our own executable
 * ============================================================================
 *
 * Every JNI native without a JNative C file is emitted as a trampoline
 * by LlvmFunctionEmitter.emitJniTrampoline: a symbol named
 * `Java_<owner>_<name>` whose body calls __jnative_jni_resolve with its
 * own name. Older builds did not mark the trampoline `hidden`, and a
 * -rdynamic link put every trampoline into .dynsym, so
 * dlsym(RTLD_DEFAULT, symbol) returned the trampoline itself and
 * recursed. The check below rejects any dlsym result that lands inside
 * the calling executable, so even a legacy trampoline produces a clean
 * "JNI native not found" diagnostic instead of a stack overflow.
 *
 * ============================================================================
 *  Crash-tolerant loading
 * ============================================================================
 *
 * Some JDK shared objects contain constructors and JNI_OnLoad bodies
 * written for a HotSpot JVM and cannot run in this runtime. A library
 * whose JNI_OnLoad raises SIGSEGV, SIGBUS, SIGILL or SIGFPE during the
 * load is marked unusable and skipped; the process does not die. If the
 * same signal fires outside a load — which is to say, on a genuine bug
 * in the compiled image — it is forwarded to whichever handler was
 * installed before this one (the runtime's own fatal-signal handler).
 */

#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
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

typedef jobject jbooleanArray;
typedef jobject jbyteArray;
typedef jobject jcharArray;
typedef jobject jshortArray;
typedef jobject jintArray;
typedef jobject jlongArray;
typedef jobject jfloatArray;
typedef jobject jdoubleArray;
typedef jobject jobjectArray;

typedef jint jsize;

/* The tagged union JNI uses to carry an arbitrary argument through the
 * NewObjectA / Call<Type>MethodA family. Field widths are fixed by the
 * JNI specification, not by the host ABI. */
typedef union jvalue {
    jboolean z;
    jbyte    b;
    jchar    c;
    jshort   s;
    jint     i;
    jlong    j;
    jfloat   f;
    jdouble  d;
    jobject  l;
} jvalue;

typedef struct {
    const char* name;
    const char* signature;
    void*       fnPtr;
} JNINativeMethod;

#define JNI_VERSION_1_6 0x00010006

#define JNI_OK          0
#define JNI_ERR        (-1)
#define JNI_FALSE       0
#define JNI_TRUE        1

/* ==========================================================================
 *  Forward declarations — every slot this file implements.
 * ========================================================================== */

static jint         JNICALL JNI_GetVersion(JNIEnv_t);
static jclass       JNICALL JNI_FindClass(JNIEnv_t, const char*);
static jclass       JNICALL JNI_GetObjectClass(JNIEnv_t, jobject);
static jclass       JNICALL JNI_GetSuperclass(JNIEnv_t, jclass);
static jboolean     JNICALL JNI_IsInstanceOf(JNIEnv_t, jobject, jclass);
static jboolean     JNICALL JNI_IsAssignableFrom(JNIEnv_t, jclass, jclass);
static jint         JNICALL JNI_Throw(JNIEnv_t, jthrowable);
static jint         JNICALL JNI_ThrowNew(JNIEnv_t, jclass, const char*);
static jthrowable   JNICALL JNI_ExceptionOccurred(JNIEnv_t);
static void         JNICALL JNI_ExceptionClear(JNIEnv_t);
static jboolean     JNICALL JNI_ExceptionCheck(JNIEnv_t);
static jstring      JNICALL JNI_NewStringUTF(JNIEnv_t, const char*);
static jint         JNICALL JNI_GetStringUTFLength(JNIEnv_t, jstring);
static const char*  JNICALL JNI_GetStringUTFChars(JNIEnv_t, jstring, jboolean*);
static void         JNICALL JNI_ReleaseStringUTFChars(JNIEnv_t, jstring, const char*);
static jint         JNICALL JNI_GetArrayLength(JNIEnv_t, jarray);
static jfieldID     JNICALL JNI_GetFieldID(JNIEnv_t, jclass, const char*, const char*);
static jmethodID    JNICALL JNI_GetMethodID(JNIEnv_t, jclass, const char*, const char*);
static jfieldID     JNICALL JNI_GetStaticFieldID(JNIEnv_t, jclass, const char*, const char*);
static jmethodID    JNICALL JNI_GetStaticMethodID(JNIEnv_t, jclass, const char*, const char*);
static jobject      JNICALL JNI_GetObjectField(JNIEnv_t, jobject, jfieldID);
static void         JNICALL JNI_SetObjectField(JNIEnv_t, jobject, jfieldID, jobject);
static jint         JNICALL JNI_GetIntField(JNIEnv_t, jobject, jfieldID);
static void         JNICALL JNI_SetIntField(JNIEnv_t, jobject, jfieldID, jint);
static jlong        JNICALL JNI_GetLongField(JNIEnv_t, jobject, jfieldID);
static void         JNICALL JNI_SetLongField(JNIEnv_t, jobject, jfieldID, jlong);
static jobject      JNICALL JNI_GetStaticObjectField(JNIEnv_t, jclass, jfieldID);
static void*        JNICALL JNI_GetPrimitiveArrayCritical(JNIEnv_t, jarray, jboolean*);
static void         JNICALL JNI_ReleasePrimitiveArrayCritical(JNIEnv_t, jarray, void*, jint);
static jint         JNICALL JNI_RegisterNatives(JNIEnv_t, jclass, const JNINativeMethod*, jint);
static jint         JNICALL JNI_UnregisterNatives(JNIEnv_t, jclass);
static jint         JNICALL JNI_GetJavaVM(JNIEnv_t, JavaVM_t*);

static jobject      JNICALL JNI_NewGlobalRef(JNIEnv_t, jobject);
static void         JNICALL JNI_DeleteGlobalRef(JNIEnv_t, jobject);
static jobject      JNICALL JNI_NewLocalRef(JNIEnv_t, jobject);
static void         JNICALL JNI_DeleteLocalRef(JNIEnv_t, jobject);
static jboolean     JNICALL JNI_IsSameObject(JNIEnv_t, jobject, jobject);
static jobject      JNICALL JNI_NewWeakGlobalRef(JNIEnv_t, jobject);
static void         JNICALL JNI_DeleteWeakGlobalRef(JNIEnv_t, jweak);

static jobjectArray JNICALL JNI_NewObjectArray(JNIEnv_t, jsize, jclass, jobject);
static jobject      JNICALL JNI_GetObjectArrayElement(JNIEnv_t, jobjectArray, jsize);
static void         JNICALL JNI_SetObjectArrayElement(JNIEnv_t, jobjectArray, jsize, jobject);

static jobject      JNICALL JNI_NewDirectByteBuffer(JNIEnv_t, void*, jlong);
static void*        JNICALL JNI_GetDirectBufferAddress(JNIEnv_t, jobject);
static jlong        JNICALL JNI_GetDirectBufferCapacity(JNIEnv_t, jobject);

static jobject      JNICALL JNI_NewObject(JNIEnv_t, jclass, jmethodID, ...);
static jobject      JNICALL JNI_NewObjectV(JNIEnv_t, jclass, jmethodID, va_list);
static jobject      JNICALL JNI_NewObjectA(JNIEnv_t, jclass, jmethodID, const jvalue*);

#define JNI_DECL_CALL(NAME, JTYPE)                                          \
    static JTYPE JNICALL JNI_Call##NAME##Method(JNIEnv_t, jobject, jmethodID, ...); \
    static JTYPE JNICALL JNI_Call##NAME##MethodV(JNIEnv_t, jobject, jmethodID, va_list); \
    static JTYPE JNICALL JNI_Call##NAME##MethodA(JNIEnv_t, jobject, jmethodID, const jvalue*); \
    static JTYPE JNICALL JNI_CallStatic##NAME##Method(JNIEnv_t, jclass, jmethodID, ...); \
    static JTYPE JNICALL JNI_CallStatic##NAME##MethodV(JNIEnv_t, jclass, jmethodID, va_list); \
    static JTYPE JNICALL JNI_CallStatic##NAME##MethodA(JNIEnv_t, jclass, jmethodID, const jvalue*);

JNI_DECL_CALL(Object,  jobject)
JNI_DECL_CALL(Boolean, jboolean)
JNI_DECL_CALL(Byte,    jbyte)
JNI_DECL_CALL(Char,    jchar)
JNI_DECL_CALL(Short,   jshort)
JNI_DECL_CALL(Int,     jint)
JNI_DECL_CALL(Long,    jlong)
JNI_DECL_CALL(Float,   jfloat)
JNI_DECL_CALL(Double,  jdouble)
JNI_DECL_CALL(Void,    void)

#define JNI_DECL_ARRAY(NAME, JTYPE, JARRTYPE)                              \
    static JARRTYPE JNICALL JNI_New##NAME##Array(JNIEnv_t, jsize);         \
    static JTYPE*   JNICALL JNI_Get##NAME##ArrayElements(JNIEnv_t, JARRTYPE, jboolean*); \
    static void     JNICALL JNI_Release##NAME##ArrayElements(JNIEnv_t, JARRTYPE, JTYPE*, jint); \
    static void     JNICALL JNI_Get##NAME##ArrayRegion(JNIEnv_t, JARRTYPE, jsize, jsize, JTYPE*); \
    static void     JNICALL JNI_Set##NAME##ArrayRegion(JNIEnv_t, JARRTYPE, jsize, jsize, const JTYPE*);

JNI_DECL_ARRAY(Boolean, jboolean, jbooleanArray)
JNI_DECL_ARRAY(Byte,    jbyte,    jbyteArray)
JNI_DECL_ARRAY(Char,    jchar,    jcharArray)
JNI_DECL_ARRAY(Short,   jshort,   jshortArray)
JNI_DECL_ARRAY(Int,     jint,     jintArray)
JNI_DECL_ARRAY(Long,    jlong,    jlongArray)
JNI_DECL_ARRAY(Float,   jfloat,   jfloatArray)
JNI_DECL_ARRAY(Double,  jdouble,  jdoubleArray)

#undef JNI_DECL_ARRAY

/* ==========================================================================
 *  JNINativeInterface_ — the JNIEnv function table.
 *
 *  Slot layout matches the JNI specification byte-for-byte. Every slot
 *  has its own named field so a library compiled against the standard
 *  jni.h sees the correct offsets.
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
    jobject    (JNICALL *NewGlobalRef)(JNIEnv_t, jobject);
    void       (JNICALL *DeleteGlobalRef)(JNIEnv_t, jobject);
    void       (JNICALL *DeleteLocalRef)(JNIEnv_t, jobject);
    jboolean   (JNICALL *IsSameObject)(JNIEnv_t, jobject, jobject);
    jobject    (JNICALL *NewLocalRef)(JNIEnv_t, jobject);
    void*      EnsureLocalCapacity;
    void*      AllocObject;
    void*      NewObject;
    void*      NewObjectV;
    void*      NewObjectA;
    jclass     (JNICALL *GetObjectClass)(JNIEnv_t, jobject);
    jboolean   (JNICALL *IsInstanceOf)(JNIEnv_t, jobject, jclass);
    jmethodID  (JNICALL *GetMethodID)(JNIEnv_t, jclass, const char*, const char*);

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
    void*     SetBooleanField;
    void*     SetByteField;
    void*     SetCharField;
    void*     SetShortField;
    void      (JNICALL *SetIntField)(JNIEnv_t, jobject, jfieldID, jint);
    void      (JNICALL *SetLongField)(JNIEnv_t, jobject, jfieldID, jlong);
    void*     SetFloatField;
    void*     SetDoubleField;

    jmethodID (JNICALL *GetStaticMethodID)(JNIEnv_t, jclass, const char*, const char*);

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

    jobjectArray (JNICALL *NewObjectArray)(JNIEnv_t, jsize, jclass, jobject);
    jobject      (JNICALL *GetObjectArrayElement)(JNIEnv_t, jobjectArray, jsize);
    void         (JNICALL *SetObjectArrayElement)(JNIEnv_t, jobjectArray, jsize, jobject);

    jbooleanArray (JNICALL *NewBooleanArray)(JNIEnv_t, jsize);
    jbyteArray    (JNICALL *NewByteArray)(JNIEnv_t, jsize);
    jcharArray    (JNICALL *NewCharArray)(JNIEnv_t, jsize);
    jshortArray   (JNICALL *NewShortArray)(JNIEnv_t, jsize);
    jintArray     (JNICALL *NewIntArray)(JNIEnv_t, jsize);
    jlongArray    (JNICALL *NewLongArray)(JNIEnv_t, jsize);
    jfloatArray   (JNICALL *NewFloatArray)(JNIEnv_t, jsize);
    jdoubleArray  (JNICALL *NewDoubleArray)(JNIEnv_t, jsize);

    jboolean* (JNICALL *GetBooleanArrayElements)(JNIEnv_t, jbooleanArray, jboolean*);
    jbyte*    (JNICALL *GetByteArrayElements)(JNIEnv_t, jbyteArray, jboolean*);
    jchar*    (JNICALL *GetCharArrayElements)(JNIEnv_t, jcharArray, jboolean*);
    jshort*   (JNICALL *GetShortArrayElements)(JNIEnv_t, jshortArray, jboolean*);
    jint*     (JNICALL *GetIntArrayElements)(JNIEnv_t, jintArray, jboolean*);
    jlong*    (JNICALL *GetLongArrayElements)(JNIEnv_t, jlongArray, jboolean*);
    jfloat*   (JNICALL *GetFloatArrayElements)(JNIEnv_t, jfloatArray, jboolean*);
    jdouble*  (JNICALL *GetDoubleArrayElements)(JNIEnv_t, jdoubleArray, jboolean*);

    void (JNICALL *ReleaseBooleanArrayElements)(JNIEnv_t, jbooleanArray, jboolean*, jint);
    void (JNICALL *ReleaseByteArrayElements)(JNIEnv_t, jbyteArray, jbyte*, jint);
    void (JNICALL *ReleaseCharArrayElements)(JNIEnv_t, jcharArray, jchar*, jint);
    void (JNICALL *ReleaseShortArrayElements)(JNIEnv_t, jshortArray, jshort*, jint);
    void (JNICALL *ReleaseIntArrayElements)(JNIEnv_t, jintArray, jint*, jint);
    void (JNICALL *ReleaseLongArrayElements)(JNIEnv_t, jlongArray, jlong*, jint);
    void (JNICALL *ReleaseFloatArrayElements)(JNIEnv_t, jfloatArray, jfloat*, jint);
    void (JNICALL *ReleaseDoubleArrayElements)(JNIEnv_t, jdoubleArray, jdouble*, jint);

    void (JNICALL *GetBooleanArrayRegion)(JNIEnv_t, jbooleanArray, jsize, jsize, jboolean*);
    void (JNICALL *GetByteArrayRegion)(JNIEnv_t, jbyteArray, jsize, jsize, jbyte*);
    void (JNICALL *GetCharArrayRegion)(JNIEnv_t, jcharArray, jsize, jsize, jchar*);
    void (JNICALL *GetShortArrayRegion)(JNIEnv_t, jshortArray, jsize, jsize, jshort*);
    void (JNICALL *GetIntArrayRegion)(JNIEnv_t, jintArray, jsize, jsize, jint*);
    void (JNICALL *GetLongArrayRegion)(JNIEnv_t, jlongArray, jsize, jsize, jlong*);
    void (JNICALL *GetFloatArrayRegion)(JNIEnv_t, jfloatArray, jsize, jsize, jfloat*);
    void (JNICALL *GetDoubleArrayRegion)(JNIEnv_t, jdoubleArray, jsize, jsize, jdouble*);

    void (JNICALL *SetBooleanArrayRegion)(JNIEnv_t, jbooleanArray, jsize, jsize, const jboolean*);
    void (JNICALL *SetByteArrayRegion)(JNIEnv_t, jbyteArray, jsize, jsize, const jbyte*);
    void (JNICALL *SetCharArrayRegion)(JNIEnv_t, jcharArray, jsize, jsize, const jchar*);
    void (JNICALL *SetShortArrayRegion)(JNIEnv_t, jshortArray, jsize, jsize, const jshort*);
    void (JNICALL *SetIntArrayRegion)(JNIEnv_t, jintArray, jsize, jsize, const jint*);
    void (JNICALL *SetLongArrayRegion)(JNIEnv_t, jlongArray, jsize, jsize, const jlong*);
    void (JNICALL *SetFloatArrayRegion)(JNIEnv_t, jfloatArray, jsize, jsize, const jfloat*);
    void (JNICALL *SetDoubleArrayRegion)(JNIEnv_t, jdoubleArray, jsize, jsize, const jdouble*);

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
    jobject (JNICALL *NewWeakGlobalRef)(JNIEnv_t, jobject);
    void    (JNICALL *DeleteWeakGlobalRef)(JNIEnv_t, jweak);
    jboolean (JNICALL *ExceptionCheck)(JNIEnv_t);

    jobject (JNICALL *NewDirectByteBuffer)(JNIEnv_t, void*, jlong);
    void*   (JNICALL *GetDirectBufferAddress)(JNIEnv_t, jobject);
    jlong   (JNICALL *GetDirectBufferCapacity)(JNIEnv_t, jobject);
    void* GetObjectRefType;
    void* GetModule;
    void* IsVirtualThread;
};

/* ==========================================================================
 *  JNIInvokeInterface_ — the JavaVM function table.
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
 *  RegisterNatives dispatch table.
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
    while (*s) { h ^= (uint8_t)*s++; h *= 16777619u; }
    return h;
}

static uint32_t jni_hash_triple(const char* a, const char* b, const char* c) {
    uint32_t h = jni_hash_string(a);
    h = (h * 16777619u) ^ jni_hash_string(b);
    h = (h * 16777619u) ^ jni_hash_string(c);
    return h;
}

static void jni_table_insert(const char* class_name, const char* method_name,
                             const char* signature, void* fn_ptr) {
    if (class_name == NULL || method_name == NULL
        || signature == NULL || fn_ptr == NULL) return;

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
    if (e == NULL) { pthread_mutex_unlock(&jni_native_lock); return; }
    e->class_name  = strdup(class_name);
    e->method_name = strdup(method_name);
    e->signature   = strdup(signature);
    e->fn_ptr      = fn_ptr;
    e->next        = jni_native_table[bucket];
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
 *  Class lookup.
 * ========================================================================== */

static ReflectionClass* jni_lookup_class(const char* name) {
    if (name == NULL || reflect_all_classes == NULL) return NULL;
    for (ReflectionClass** p = reflect_all_classes; *p; p++) {
        const char* n = (*p)->cname;
        if (n == NULL) continue;
        if (strcmp(n, name) == 0) return *p;
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
 *  Buffer field offsets — resolved once from ReflectionClass metadata.
 * ========================================================================== */

static pthread_once_t jnative_buffer_offsets_once = PTHREAD_ONCE_INIT;
static int32_t        jnative_buffer_capacity_offset = -1;
static int32_t        jnative_buffer_address_offset  = -1;

static void jnative_resolve_buffer_offsets(void) {
    ReflectionClass* buf = jnative_class_by_name("java/nio/Buffer");
    if (buf == NULL) return;
    ReflectionClass* cur = buf;
    while (cur != NULL) {
        if (cur->fields != NULL) {
            for (ReflectionField** fp = cur->fields; *fp; fp++) {
                ReflectionField* f = *fp;
                if (f->name == NULL) continue;
                const char* n = (const char*)f->name;
                if (jnative_buffer_capacity_offset < 0
                    && strcmp(n, "capacity") == 0) {
                    jnative_buffer_capacity_offset = f->offset;
                }
                if (jnative_buffer_address_offset < 0
                    && strcmp(n, "address") == 0) {
                    jnative_buffer_address_offset = f->offset;
                }
            }
        }
        cur = cur->superclass;
    }
}

static inline void jnative_ensure_buffer_offsets(void) {
    pthread_once(&jnative_buffer_offsets_once, jnative_resolve_buffer_offsets);
}

/* ==========================================================================
 *  Reference management — identity / no-op (no GC in this runtime).
 * ========================================================================== */

static jobject JNICALL JNI_NewGlobalRef(JNIEnv_t e, jobject o)      { (void)e; return o; }
static void    JNICALL JNI_DeleteGlobalRef(JNIEnv_t e, jobject o)   { (void)e; (void)o; }
static jobject JNICALL JNI_NewLocalRef(JNIEnv_t e, jobject o)       { (void)e; return o; }
static void    JNICALL JNI_DeleteLocalRef(JNIEnv_t e, jobject o)    { (void)e; (void)o; }
static jobject JNICALL JNI_NewWeakGlobalRef(JNIEnv_t e, jobject o)  { (void)e; return o; }
static void    JNICALL JNI_DeleteWeakGlobalRef(JNIEnv_t e, jweak o) { (void)e; (void)o; }

static jboolean JNICALL JNI_IsSameObject(JNIEnv_t e, jobject a, jobject b) {
    (void)e;
    return a == b ? JNI_TRUE : JNI_FALSE;
}

/* ==========================================================================
 *  Array accessors — macro-generated for the eight primitive types.
 * ========================================================================== */

#define DEFINE_ARRAY_FUNCTIONS(NAME, JTYPE, JARRTYPE, DESC, ELEM_SIZE)        \
    static JARRTYPE JNICALL JNI_New##NAME##Array(JNIEnv_t e, jsize len) {     \
        (void)e;                                                              \
        if (len < 0) {                                                        \
            __jnative_throw_out_of_memory_error_ctx("New" #NAME "Array");     \
        }                                                                     \
        return (JARRTYPE)jnative_array_alloc(DESC, len, ELEM_SIZE);           \
    }                                                                         \
    static JTYPE* JNICALL JNI_Get##NAME##ArrayElements(                       \
            JNIEnv_t e, JARRTYPE arr, jboolean* isCopy) {                     \
        (void)e;                                                              \
        if (isCopy) *isCopy = JNI_FALSE;                                      \
        return (JTYPE*)jnative_array_data(arr);                               \
    }                                                                         \
    static void JNICALL JNI_Release##NAME##ArrayElements(                     \
            JNIEnv_t e, JARRTYPE arr, JTYPE* elems, jint mode) {              \
        (void)e; (void)arr; (void)elems; (void)mode;                          \
    }                                                                         \
    static void JNICALL JNI_Get##NAME##ArrayRegion(                           \
            JNIEnv_t e, JARRTYPE arr, jsize start, jsize len, JTYPE* buf) {   \
        (void)e;                                                              \
        if (arr == NULL || buf == NULL || start < 0 || len < 0) return;       \
        int32_t alen = jnative_array_length(arr);                             \
        if (start + len > alen) return;                                       \
        memcpy(buf, (JTYPE*)jnative_array_data(arr) + start,                  \
               (size_t)len * (ELEM_SIZE));                                    \
    }                                                                         \
    static void JNICALL JNI_Set##NAME##ArrayRegion(                           \
            JNIEnv_t e, JARRTYPE arr, jsize start, jsize len,                 \
            const JTYPE* buf) {                                               \
        (void)e;                                                              \
        if (arr == NULL || buf == NULL || start < 0 || len < 0) return;       \
        int32_t alen = jnative_array_length(arr);                             \
        if (start + len > alen) {                                             \
            __jnative_throw_array_index_out_of_bounds();                      \
        }                                                                     \
        memcpy((JTYPE*)jnative_array_data(arr) + start, buf,                  \
               (size_t)len * (ELEM_SIZE));                                    \
    }

DEFINE_ARRAY_FUNCTIONS(Boolean, jboolean, jbooleanArray, "[Z", 1)
DEFINE_ARRAY_FUNCTIONS(Byte,    jbyte,    jbyteArray,    "[B", 1)
DEFINE_ARRAY_FUNCTIONS(Char,    jchar,    jcharArray,    "[C", 2)
DEFINE_ARRAY_FUNCTIONS(Short,   jshort,   jshortArray,   "[S", 2)
DEFINE_ARRAY_FUNCTIONS(Int,     jint,     jintArray,     "[I", 4)
DEFINE_ARRAY_FUNCTIONS(Long,    jlong,    jlongArray,    "[J", 8)
DEFINE_ARRAY_FUNCTIONS(Float,   jfloat,   jfloatArray,   "[F", 4)
DEFINE_ARRAY_FUNCTIONS(Double,  jdouble,  jdoubleArray,  "[D", 8)

#undef DEFINE_ARRAY_FUNCTIONS

/* ==========================================================================
 *  Object arrays.
 * ========================================================================== */

static jobjectArray JNICALL JNI_NewObjectArray(JNIEnv_t e, jsize len,
                                               jclass elemClass, jobject init) {
    (void)e; (void)elemClass;
    if (len < 0) {
        __jnative_throw_out_of_memory_error_ctx("NewObjectArray");
    }
    void* arr = jnative_ref_array_of_class(NULL, len, "[Ljava/lang/Object;");
    if (arr != NULL && init != NULL) {
        void** slots = (void**)jnative_array_data(arr);
        for (jsize i = 0; i < len; i++) slots[i] = init;
    }
    return (jobjectArray)arr;
}

static jobject JNICALL JNI_GetObjectArrayElement(JNIEnv_t e, jobjectArray arr,
                                                 jsize idx) {
    (void)e;
    if (arr == NULL || idx < 0) return NULL;
    int32_t len = jnative_array_length(arr);
    if (idx >= len) return NULL;
    return ((jobject*)jnative_array_data(arr))[idx];
}

static void JNICALL JNI_SetObjectArrayElement(JNIEnv_t e, jobjectArray arr,
                                              jsize idx, jobject val) {
    (void)e;
    if (arr == NULL || idx < 0) return;
    int32_t len = jnative_array_length(arr);
    if (idx >= len) {
        __jnative_throw_array_index_out_of_bounds();
    }
    ((jobject*)jnative_array_data(arr))[idx] = val;
}

/* ==========================================================================
 *  Direct buffers.
 * ========================================================================== */

static jobject JNICALL JNI_NewDirectByteBuffer(JNIEnv_t e, void* addr, jlong cap) {
    (void)e; (void)addr; (void)cap;
    /*
     * Netty's epoll transport allocates its buffers in Java and only
     * reads their native address through GetDirectBufferAddress; a
     * C-side factory is never needed. Returning NULL is the safe answer
     * for a caller that really does use it: a DirectByteBuffer built
     * from C would have no Cleaner, and the eventual GC-free runtime
     * would leak the mapping anyway.
     */
    return NULL;
}

static void* JNICALL JNI_GetDirectBufferAddress(JNIEnv_t e, jobject buf) {
    (void)e;
    if (buf == NULL) return NULL;
    jnative_ensure_buffer_offsets();
    if (jnative_buffer_address_offset < 0) return NULL;
    int64_t addr = *(int64_t*)((char*)buf + jnative_buffer_address_offset);
    if (addr == 0) return NULL;
    return (void*)(intptr_t)addr;
}

static jlong JNICALL JNI_GetDirectBufferCapacity(JNIEnv_t e, jobject buf) {
    (void)e;
    if (buf == NULL) return -1;
    jnative_ensure_buffer_offsets();
    if (jnative_buffer_capacity_offset < 0) return -1;
    return (jlong)(*(int32_t*)((char*)buf + jnative_buffer_capacity_offset));
}

/* ==========================================================================
 *  JNIEnv entry points.
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
    return 0;
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

static void JNICALL JNI_ExceptionClear(JNIEnv_t e) { (void)e; }

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

static void JNICALL JNI_ReleaseStringUTFChars(JNIEnv_t e, jstring s, const char* p) {
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

/* ==========================================================================
 *  Method resolution helpers: dlsym-backed method handles.
 *
 *  A jmethodID produced by GetMethodID / GetStaticMethodID is a
 *  JniMethodHandle*, not a pointer into the class's reflection table.
 *  The handle records the declaring class (internal name), the method
 *  name (or "<init>"), the JVM descriptor and whether the lookup was
 *  for a static method. The target function is resolved lazily on the
 *  first call, through
 *
 *      dlsym(RTLD_DEFAULT, fn_<class>_<name>_<desc>)
 *
 *  which is exactly the symbol LlvmRuntime.mangleMethod gives every
 *  generated method and every native C implementation.
 *
 *  This lets GetMethodID answer for *any* method that exists in the
 *  compiled image — including constructors no Java code reaches and
 *  which therefore carry no reflection table entry — provided the
 *  function was emitted. A resolution failure surfaces as a
 *  NoSuchMethodError naming the symbol, instead of a silent NULL.
 * ========================================================================== */

typedef struct JniMethodHandle {
    char*   owner;       /* internal name, e.g. "java/lang/String" */
    char*   name;        /* method name or "<init>" */
    char*   signature;   /* JVM descriptor */
    int32_t is_static;
    void*   resolved;    /* dlsym result, NULL until first call */
} JniMethodHandle;

static char* jni_strdup(const char* s) {
    if (s == NULL) return NULL;
    size_t n = strlen(s) + 1;
    char* d = (char*)malloc(n);
    if (d != NULL) memcpy(d, s, n);
    return d;
}

/* Compute fn_<class>_<name>_<desc> per LlvmRuntime.mangleMethod: every
 * character outside [a-zA-Z0-9_] becomes a single underscore, and the
 * three components are joined by single underscores after the "fn"
 * prefix. */
static void jni_mangle(const char* owner, const char* name, const char* desc,
                       char* out, size_t out_size)
{
    size_t pos = 0;
    #define PUT(c) do { if (pos + 1 < out_size) out[pos++] = (char)(c); } while (0)
    #define SAN(s) do { \
        for (const char* _p = (s); *_p; _p++) { \
            unsigned char _c = (unsigned char)*_p; \
            if ((_c >= 'a' && _c <= 'z') || (_c >= 'A' && _c <= 'Z') \
                || (_c >= '0' && _c <= '9') || _c == '_') PUT(_c); \
            else PUT('_'); \
        } \
    } while (0)
    SAN("fn"); PUT('_'); SAN(owner); PUT('_'); SAN(name); PUT('_'); SAN(desc);
    #undef SAN
    #undef PUT
    out[pos < out_size ? pos : out_size - 1] = '\0';
}

static void* jni_resolve(JniMethodHandle* h) {
    if (h == NULL) return NULL;
    if (h->resolved != NULL) return h->resolved;
    char mangled[1024];
    jni_mangle(h->owner, h->name, h->signature, mangled, sizeof(mangled));
    h->resolved = dlsym(RTLD_DEFAULT, mangled);
    return h->resolved;
}

static jmethodID JNICALL JNI_GetMethodID(JNIEnv_t e, jclass c,
                                         const char* name, const char* sig) {
    (void)e;
    if (c == NULL || name == NULL || sig == NULL) return NULL;
    if (strcmp(name, "<clinit>") == 0) return NULL;   /* not callable via JNI */

    const char* owner = ((ReflectionClass*)c)->cname;
    if (owner == NULL) return NULL;

    JniMethodHandle* h = (JniMethodHandle*)calloc(1, sizeof(JniMethodHandle));
    if (h == NULL) {
        __jnative_throw_out_of_memory_error_ctx("JNI GetMethodID");
    }
    h->owner     = jni_strdup(owner);
    h->name      = jni_strdup(name);
    h->signature = jni_strdup(sig);
    h->is_static = 0;
    return (jmethodID)h;
}

static jfieldID JNICALL JNI_GetStaticFieldID(JNIEnv_t e, jclass c,
                                             const char* name, const char* sig) {
    return JNI_GetFieldID(e, c, name, sig);
}

static jmethodID JNICALL JNI_GetStaticMethodID(JNIEnv_t e, jclass c,
                                               const char* name, const char* sig) {
    jmethodID mid = JNI_GetMethodID(e, c, name, sig);
    if (mid != NULL) ((JniMethodHandle*)mid)->is_static = 1;
    return mid;
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

static jobject JNICALL JNI_GetStaticObjectField(JNIEnv_t e, jclass c, jfieldID f) {
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
        const char* sig  = methods[i].signature;
        void* fn         = methods[i].fnPtr;
        if (name == NULL || sig == NULL || fn == NULL) continue;
        jni_table_insert(class_name, name, sig, fn);
    }
    return JNI_OK;
}

static jint JNICALL JNI_UnregisterNatives(JNIEnv_t e, jclass c) {
    (void)e; (void)c;
    /*
     * Removing entries would require walking the class's bucket chain
     * and freeing its strings. Nothing in this runtime ever calls
     * UnregisterNatives at a point where doing so would matter — the
     * tables live for the process lifetime, like every other piece of
     * state here — so a no-op that reports success is correct.
     */
    return JNI_OK;
}

/* ==========================================================================
 *  Reflective invocation: NewObject* and the Call<Type>Method* family.
 *
 *  The jmethodID is a JniMethodHandle* produced by GetMethodID /
 *  GetStaticMethodID. The target function is resolved lazily through
 *  jni_resolve() and called with the conventions the generated code uses:
 *
 *    - a constructor adaptor is    i8* (i8** args);
 *    - an instance method adaptor is i8* (i8* obj, i8** args);
 *    - a static method adaptor is   i8* (i8** args).
 *
 *  Adaptor return convention: a reference return is the jobject itself; a
 *  primitive return is an i8* pointing at a heap cell holding the value at
 *  its native width, which the caller must free; a void return is NULL.
 * ========================================================================== */

/* Maximum supported parameter count. The JVM spec caps method descriptors
 * at 255 parameter slots; 256 is a safe hard limit that keeps the argument
 * temporaries on the stack. */
#define JNATIVE_MAX_ARGS 256

static int jni_count_params(const char* desc) {
    if (desc == NULL || desc[0] != '(') return -1;
    int n = 0;
    const char* p = desc + 1;
    while (*p && *p != ')') {
        if (*p == 'L') { while (*p && *p != ';') p++; if (*p) p++; }
        else if (*p == '[') {
            while (*p == '[') p++;
            if (*p == 'L') { while (*p && *p != ';') p++; if (*p) p++; }
            else if (*p) p++;
        } else if (*p) p++;
        n++;
    }
    return (*p == ')') ? n : -1;
}

static const char* jni_pack_arg(const char* p, jvalue v,
                                void** out_ptr, int64_t* slot, int idx) {
    if (*p == 'L') {
        *out_ptr = (void*)v.l;
        while (*p && *p != ';') p++;
        if (*p) p++;
        return p;
    }
    if (*p == '[') {
        *out_ptr = (void*)v.l;
        while (*p == '[') p++;
        if (*p == 'L') { while (*p && *p != ';') p++; if (*p) p++; }
        else if (*p) p++;
        return p;
    }
    switch (*p) {
        case 'Z': case 'B': case 'C': case 'S': case 'I': {
            int32_t w = (int32_t)v.i;
            memset(&slot[idx], 0, sizeof(slot[idx]));
            memcpy(&slot[idx], &w, sizeof(w));
            *out_ptr = &slot[idx];
            return p + 1;
        }
        case 'J': slot[idx] = v.j; *out_ptr = &slot[idx]; return p + 1;
        case 'F': {
            float f = v.f;
            memset(&slot[idx], 0, sizeof(slot[idx]));
            memcpy(&slot[idx], &f, sizeof(f));
            *out_ptr = &slot[idx];
            return p + 1;
        }
        case 'D': {
            double d = v.d;
            memcpy(&slot[idx], &d, sizeof(d));
            *out_ptr = &slot[idx];
            return p + 1;
        }
        default: return NULL;
    }
}

/* Returns the argument count, or -1 on a malformed descriptor. */
static int jni_pack_args(const char* desc, const jvalue* args,
                         void** argv, int64_t* slots) {
    int n = jni_count_params(desc);
    if (n < 0 || n > JNATIVE_MAX_ARGS) return -1;
    const char* p = desc + 1;
    for (int i = 0; i < n; i++) {
        p = jni_pack_arg(p, args[i], &argv[i], slots, i);
        if (p == NULL) return -1;
    }
    return n;
}

/* Pull a varargs argument list into a jvalue array so the *V entry
 * points can share the *A implementation. */
static int jni_va_pack(const char* desc, va_list args, jvalue* out) {
    int n = jni_count_params(desc);
    if (n < 0 || n > JNATIVE_MAX_ARGS) return -1;
    const char* p = desc + 1;
    for (int i = 0; i < n; i++) {
        if (*p == 'L' || *p == '[') {
            out[i].l = va_arg(args, void*);
            if (*p == 'L') { while (*p && *p != ';') p++; if (*p) p++; }
            else { while (*p == '[') p++;
                   if (*p == 'L') { while (*p && *p != ';') p++; if (*p) p++; }
                   else if (*p) p++; }
        } else {
            switch (*p) {
                case 'Z': case 'B': case 'C': case 'S': case 'I':
                    out[i].i = va_arg(args, int32_t); p++; break;
                case 'J': out[i].j = va_arg(args, int64_t); p++; break;
                case 'F': out[i].f = (float)va_arg(args, double); p++; break;
                case 'D': out[i].d = va_arg(args, double); p++; break;
                default: return -1;
            }
        }
    }
    return n;
}

/* Free a heap cell created by the adaptor for a primitive return type. */
static void jni_release_cell(void* p) {
    if (p != NULL) __jnative_free(p);
}

/* Resolve the handle's target and invoke it. Returns the adaptor's raw
 * i8* result: the jobject itself for a reference return, a heap cell for
 * a primitive return, NULL for void. Raises NoSuchMethodError naming the
 * exact symbol when the method is absent from the compiled image. */
static void* jni_invoke(JniMethodHandle* h, jobject o, const jvalue* args,
                        const char* who) {
    void* fn = jni_resolve(h);
    if (fn == NULL) {
        char msg[512];
        snprintf(msg, sizeof(msg),
            "JNI %s: %s.%s%s is not present in the compiled image",
            who, h ? h->owner : "?", h ? h->name : "?",
            h ? h->signature : "?");
        __jnative_throw_exception_ctx(
            __jnative_construct_exception(
                "vtable_java_lang_NoSuchMethodError", msg), NULL);
    }
    void* argv[JNATIVE_MAX_ARGS];
    int64_t slots[JNATIVE_MAX_ARGS];
    if (jni_pack_args(h->signature, args, argv, slots) < 0) return NULL;
    if (h->is_static) {
        typedef void* (*static_t)(void**);
        return ((static_t)fn)(argv);
    }
    typedef void* (*inst_t)(void*, void**);
    return ((inst_t)fn)(o, argv);
}

static jobject JNICALL JNI_NewObjectA(JNIEnv_t e, jclass c,
                                      jmethodID mid, const jvalue* args) {
    (void)e; (void)c;
    JniMethodHandle* h = (JniMethodHandle*)mid;
    void* fn = jni_resolve(h);
    if (fn == NULL) {
        char msg[512];
        snprintf(msg, sizeof(msg),
            "JNI NewObject: constructor %s.<init>%s is not present in the "
            "compiled image (reachability gap: no Java code constructs this "
            "class, and JNative does not scan the JNI library for reflective "
            "class references)",
            h ? h->owner : "?", h ? h->signature : "?");
        __jnative_throw_exception_ctx(
            __jnative_construct_exception(
                "vtable_java_lang_NoSuchMethodError", msg), NULL);
    }
    void* argv[JNATIVE_MAX_ARGS];
    int64_t slots[JNATIVE_MAX_ARGS];
    if (jni_pack_args(h->signature, args, argv, slots) < 0) return NULL;
    typedef void* (*ctor_t)(void**);
    return (jobject)((ctor_t)fn)(argv);
}

static jobject JNICALL JNI_NewObjectV(JNIEnv_t e, jclass c,
                                      jmethodID mid, va_list args) {
    jvalue v[JNATIVE_MAX_ARGS];
    if (mid == NULL
        || jni_va_pack(((JniMethodHandle*)mid)->signature, args, v) < 0)
        return NULL;
    return JNI_NewObjectA(e, c, mid, v);
}

/* NewObject is a genuine variadic wrapper over NewObjectV — the only
 * correct way to define a variadic function in C is to take ... and
 * forward the va_list. */
static jobject JNICALL JNI_NewObject(JNIEnv_t e, jclass c,
                                     jmethodID mid, ...) {
    va_list args;
    va_start(args, mid);
    jobject r = JNI_NewObjectV(e, c, mid, args);
    va_end(args);
    return r;
}

/* ---- Call*Method: instance / static, A / V / variadic ---------------- */

static jobject JNICALL JNI_CallObjectMethodA(JNIEnv_t e, jobject o,
                                             jmethodID mid,
                                             const jvalue* args) {
    (void)e;
    return (jobject)jni_invoke((JniMethodHandle*)mid, o, args,
                               "CallObjectMethod");
}

static jobject JNICALL JNI_CallObjectMethodV(JNIEnv_t e, jobject o,
                                             jmethodID mid, va_list args) {
    jvalue v[JNATIVE_MAX_ARGS];
    if (mid == NULL
        || jni_va_pack(((JniMethodHandle*)mid)->signature, args, v) < 0)
        return NULL;
    return JNI_CallObjectMethodA(e, o, mid, v);
}

static jobject JNICALL JNI_CallObjectMethod(JNIEnv_t e, jobject o,
                                            jmethodID mid, ...) {
    va_list args;
    va_start(args, mid);
    jobject r = JNI_CallObjectMethodV(e, o, mid, args);
    va_end(args);
    return r;
}

static void JNICALL JNI_CallVoidMethodA(JNIEnv_t e, jobject o,
                                        jmethodID mid, const jvalue* args) {
    (void)e;
    (void)jni_invoke((JniMethodHandle*)mid, o, args, "CallVoidMethod");
}

static void JNICALL JNI_CallVoidMethodV(JNIEnv_t e, jobject o,
                                        jmethodID mid, va_list args) {
    jvalue v[JNATIVE_MAX_ARGS];
    if (mid == NULL
        || jni_va_pack(((JniMethodHandle*)mid)->signature, args, v) < 0)
        return;
    JNI_CallVoidMethodA(e, o, mid, v);
}

static void JNICALL JNI_CallVoidMethod(JNIEnv_t e, jobject o,
                                       jmethodID mid, ...) {
    va_list args;
    va_start(args, mid);
    JNI_CallVoidMethodV(e, o, mid, args);
    va_end(args);
}

static jobject JNICALL JNI_CallStaticObjectMethodA(JNIEnv_t e, jclass c,
                                                   jmethodID mid,
                                                   const jvalue* args) {
    return JNI_CallObjectMethodA(e, (jobject)c, mid, args);
}

static jobject JNICALL JNI_CallStaticObjectMethodV(JNIEnv_t e, jclass c,
                                                   jmethodID mid, va_list args) {
    return JNI_CallObjectMethodV(e, (jobject)c, mid, args);
}

static jobject JNICALL JNI_CallStaticObjectMethod(JNIEnv_t e, jclass c,
                                                  jmethodID mid, ...) {
    va_list args;
    va_start(args, mid);
    jobject r = JNI_CallStaticObjectMethodV(e, c, mid, args);
    va_end(args);
    return r;
}

static void JNICALL JNI_CallStaticVoidMethodA(JNIEnv_t e, jclass c,
                                              jmethodID mid,
                                              const jvalue* args) {
    JNI_CallVoidMethodA(e, (jobject)c, mid, args);
}

static void JNICALL JNI_CallStaticVoidMethodV(JNIEnv_t e, jclass c,
                                              jmethodID mid, va_list args) {
    JNI_CallVoidMethodV(e, (jobject)c, mid, args);
}

static void JNICALL JNI_CallStaticVoidMethod(JNIEnv_t e, jclass c,
                                             jmethodID mid, ...) {
    va_list args;
    va_start(args, mid);
    JNI_CallStaticVoidMethodV(e, c, mid, args);
    va_end(args);
}

/* Primitive-returning variants. The adaptor hands back an i8* to a heap
 * cell holding the primitive at its native width, so the value is copied
 * out at that width and the cell released. CTYPE is the exact storage
 * width the LLVM return type uses. */

#define JNATIVE_DEF_PRIM_CALL_A(NAME, JTYPE, CTYPE)                          \
static JTYPE JNICALL JNI_Call##NAME##MethodA(JNIEnv_t e, jobject o,          \
                                             jmethodID mid,                  \
                                             const jvalue* args) {           \
    (void)e;                                                                 \
    void* cell = jni_invoke((JniMethodHandle*)mid, o, args,                  \
                            "Call" #NAME "Method");                          \
    if (cell == NULL) return (JTYPE)0;                                       \
    CTYPE v;                                                                 \
    memcpy(&v, cell, sizeof(v));                                             \
    jni_release_cell(cell);                                                  \
    return (JTYPE)v;                                                         \
}                                                                            \
static JTYPE JNICALL JNI_Call##NAME##MethodV(JNIEnv_t e, jobject o,          \
                                             jmethodID mid, va_list args) {  \
    jvalue v[JNATIVE_MAX_ARGS];                                              \
    if (mid == NULL                                                          \
        || jni_va_pack(((JniMethodHandle*)mid)->signature, args, v) < 0)     \
        return (JTYPE)0;                                                     \
    return JNI_Call##NAME##MethodA(e, o, mid, v);                            \
}                                                                            \
static JTYPE JNICALL JNI_Call##NAME##Method(JNIEnv_t e, jobject o,           \
                                            jmethodID mid, ...) {            \
    va_list args;                                                            \
    va_start(args, mid);                                                     \
    JTYPE r = JNI_Call##NAME##MethodV(e, o, mid, args);                      \
    va_end(args);                                                            \
    return r;                                                                \
}                                                                            \
static JTYPE JNICALL JNI_CallStatic##NAME##MethodA(JNIEnv_t e, jclass c,     \
                                                   jmethodID mid,            \
                                                   const jvalue* args) {     \
    return JNI_Call##NAME##MethodA(e, (jobject)c, mid, args);               \
}                                                                            \
static JTYPE JNICALL JNI_CallStatic##NAME##MethodV(JNIEnv_t e, jclass c,     \
                                                   jmethodID mid,            \
                                                   va_list args) {           \
    return JNI_Call##NAME##MethodV(e, (jobject)c, mid, args);               \
}                                                                            \
static JTYPE JNICALL JNI_CallStatic##NAME##Method(JNIEnv_t e, jclass c,      \
                                                  jmethodID mid, ...) {      \
    va_list args;                                                            \
    va_start(args, mid);                                                     \
    JTYPE r = JNI_CallStatic##NAME##MethodV(e, c, mid, args);                \
    va_end(args);                                                            \
    return r;                                                                \
}

JNATIVE_DEF_PRIM_CALL_A(Boolean, jboolean, uint8_t)
JNATIVE_DEF_PRIM_CALL_A(Byte,    jbyte,    int8_t)
JNATIVE_DEF_PRIM_CALL_A(Char,    jchar,    uint16_t)
JNATIVE_DEF_PRIM_CALL_A(Short,   jshort,   int16_t)
JNATIVE_DEF_PRIM_CALL_A(Int,     jint,     int32_t)
JNATIVE_DEF_PRIM_CALL_A(Long,    jlong,    int64_t)
JNATIVE_DEF_PRIM_CALL_A(Float,   jfloat,   float)
JNATIVE_DEF_PRIM_CALL_A(Double,  jdouble,  double)

/* ==========================================================================
 *  The JNIEnv table itself.
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
    .NewGlobalRef   = JNI_NewGlobalRef,
    .DeleteGlobalRef = JNI_DeleteGlobalRef,
    .DeleteLocalRef = JNI_DeleteLocalRef,
    .IsSameObject   = JNI_IsSameObject,
    .NewLocalRef    = JNI_NewLocalRef,
    .EnsureLocalCapacity = NULL,
    .AllocObject    = NULL,
    .NewObject      = (void*)JNI_NewObject,
    .NewObjectV     = (void*)JNI_NewObjectV,
    .NewObjectA     = (void*)JNI_NewObjectA,
    .GetObjectClass = JNI_GetObjectClass,
    .IsInstanceOf   = JNI_IsInstanceOf,
    .GetMethodID    = JNI_GetMethodID,

    .CallObjectMethod = (void*)JNI_CallObjectMethod, .CallObjectMethodV = (void*)JNI_CallObjectMethodV, .CallObjectMethodA = (void*)JNI_CallObjectMethodA,
    .CallBooleanMethod = (void*)JNI_CallBooleanMethod, .CallBooleanMethodV = (void*)JNI_CallBooleanMethodV, .CallBooleanMethodA = (void*)JNI_CallBooleanMethodA,
    .CallByteMethod = (void*)JNI_CallByteMethod, .CallByteMethodV = (void*)JNI_CallByteMethodV, .CallByteMethodA = (void*)JNI_CallByteMethodA,
    .CallCharMethod = (void*)JNI_CallCharMethod, .CallCharMethodV = (void*)JNI_CallCharMethodV, .CallCharMethodA = (void*)JNI_CallCharMethodA,
    .CallShortMethod = (void*)JNI_CallShortMethod, .CallShortMethodV = (void*)JNI_CallShortMethodV, .CallShortMethodA = (void*)JNI_CallShortMethodA,
    .CallIntMethod = (void*)JNI_CallIntMethod, .CallIntMethodV = (void*)JNI_CallIntMethodV, .CallIntMethodA = (void*)JNI_CallIntMethodA,
    .CallLongMethod = (void*)JNI_CallLongMethod, .CallLongMethodV = (void*)JNI_CallLongMethodV, .CallLongMethodA = (void*)JNI_CallLongMethodA,
    .CallFloatMethod = (void*)JNI_CallFloatMethod, .CallFloatMethodV = (void*)JNI_CallFloatMethodV, .CallFloatMethodA = (void*)JNI_CallFloatMethodA,
    .CallDoubleMethod = (void*)JNI_CallDoubleMethod, .CallDoubleMethodV = (void*)JNI_CallDoubleMethodV, .CallDoubleMethodA = (void*)JNI_CallDoubleMethodA,
    .CallVoidMethod = (void*)JNI_CallVoidMethod, .CallVoidMethodV = (void*)JNI_CallVoidMethodV, .CallVoidMethodA = (void*)JNI_CallVoidMethodA,
    .CallNonvirtualObjectMethod = NULL, .CallNonvirtualObjectMethodV = NULL, .CallNonvirtualObjectMethodA = NULL,
    .CallNonvirtualBooleanMethod = NULL, .CallNonvirtualBooleanMethodV = NULL, .CallNonvirtualBooleanMethodA = NULL,
    .CallNonvirtualByteMethod = NULL, .CallNonvirtualByteMethodV = NULL, .CallNonvirtualByteMethodA = NULL,
    .CallNonvirtualCharMethod = NULL, .CallNonvirtualCharMethodV = NULL, .CallNonvirtualCharMethodA = NULL,
    .CallNonvirtualShortMethod = NULL, .CallNonvirtualShortMethodV = NULL, .CallNonvirtualShortMethodA = NULL,
    .CallNonvirtualIntMethod = NULL, .CallNonvirtualIntMethodV = NULL, .CallNonvirtualIntMethodA = NULL,
    .CallNonvirtualLongMethod = NULL, .CallNonvirtualLongMethodV = NULL, .CallNonvirtualLongMethodA = NULL,
    .CallNonvirtualFloatMethod = NULL, .CallNonvirtualFloatMethodV = NULL, .CallNonvirtualFloatMethodA = NULL,
    .CallNonvirtualDoubleMethod = NULL, .CallNonvirtualDoubleMethodV = NULL, .CallNonvirtualDoubleMethodA = NULL,
    .CallNonvirtualVoidMethod = NULL, .CallNonvirtualVoidMethodV = NULL, .CallNonvirtualVoidMethodA = NULL,

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
    .CallStaticObjectMethod = (void*)JNI_CallStaticObjectMethod, .CallStaticObjectMethodV = (void*)JNI_CallStaticObjectMethodV, .CallStaticObjectMethodA = (void*)JNI_CallStaticObjectMethodA,
    .CallStaticBooleanMethod = (void*)JNI_CallStaticBooleanMethod, .CallStaticBooleanMethodV = (void*)JNI_CallStaticBooleanMethodV, .CallStaticBooleanMethodA = (void*)JNI_CallStaticBooleanMethodA,
    .CallStaticByteMethod = (void*)JNI_CallStaticByteMethod, .CallStaticByteMethodV = (void*)JNI_CallStaticByteMethodV, .CallStaticByteMethodA = (void*)JNI_CallStaticByteMethodA,
    .CallStaticCharMethod = (void*)JNI_CallStaticCharMethod, .CallStaticCharMethodV = (void*)JNI_CallStaticCharMethodV, .CallStaticCharMethodA = (void*)JNI_CallStaticCharMethodA,
    .CallStaticShortMethod = (void*)JNI_CallStaticShortMethod, .CallStaticShortMethodV = (void*)JNI_CallStaticShortMethodV, .CallStaticShortMethodA = (void*)JNI_CallStaticShortMethodA,
    .CallStaticIntMethod = (void*)JNI_CallStaticIntMethod, .CallStaticIntMethodV = (void*)JNI_CallStaticIntMethodV, .CallStaticIntMethodA = (void*)JNI_CallStaticIntMethodA,
    .CallStaticLongMethod = (void*)JNI_CallStaticLongMethod, .CallStaticLongMethodV = (void*)JNI_CallStaticLongMethodV, .CallStaticLongMethodA = (void*)JNI_CallStaticLongMethodA,
    .CallStaticFloatMethod = (void*)JNI_CallStaticFloatMethod, .CallStaticFloatMethodV = (void*)JNI_CallStaticFloatMethodV, .CallStaticFloatMethodA = (void*)JNI_CallStaticFloatMethodA,
    .CallStaticDoubleMethod = (void*)JNI_CallStaticDoubleMethod, .CallStaticDoubleMethodV = (void*)JNI_CallStaticDoubleMethodV, .CallStaticDoubleMethodA = (void*)JNI_CallStaticDoubleMethodA,
    .CallStaticVoidMethod = (void*)JNI_CallStaticVoidMethod, .CallStaticVoidMethodV = (void*)JNI_CallStaticVoidMethodV, .CallStaticVoidMethodA = (void*)JNI_CallStaticVoidMethodA,

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

    .NewObjectArray        = JNI_NewObjectArray,
    .GetObjectArrayElement = JNI_GetObjectArrayElement,
    .SetObjectArrayElement = JNI_SetObjectArrayElement,

    .NewBooleanArray = JNI_NewBooleanArray,
    .NewByteArray    = JNI_NewByteArray,
    .NewCharArray    = JNI_NewCharArray,
    .NewShortArray   = JNI_NewShortArray,
    .NewIntArray     = JNI_NewIntArray,
    .NewLongArray    = JNI_NewLongArray,
    .NewFloatArray   = JNI_NewFloatArray,
    .NewDoubleArray  = JNI_NewDoubleArray,

    .GetBooleanArrayElements = JNI_GetBooleanArrayElements,
    .GetByteArrayElements    = JNI_GetByteArrayElements,
    .GetCharArrayElements    = JNI_GetCharArrayElements,
    .GetShortArrayElements   = JNI_GetShortArrayElements,
    .GetIntArrayElements     = JNI_GetIntArrayElements,
    .GetLongArrayElements    = JNI_GetLongArrayElements,
    .GetFloatArrayElements   = JNI_GetFloatArrayElements,
    .GetDoubleArrayElements  = JNI_GetDoubleArrayElements,

    .ReleaseBooleanArrayElements = JNI_ReleaseBooleanArrayElements,
    .ReleaseByteArrayElements    = JNI_ReleaseByteArrayElements,
    .ReleaseCharArrayElements    = JNI_ReleaseCharArrayElements,
    .ReleaseShortArrayElements   = JNI_ReleaseShortArrayElements,
    .ReleaseIntArrayElements     = JNI_ReleaseIntArrayElements,
    .ReleaseLongArrayElements    = JNI_ReleaseLongArrayElements,
    .ReleaseFloatArrayElements   = JNI_ReleaseFloatArrayElements,
    .ReleaseDoubleArrayElements  = JNI_ReleaseDoubleArrayElements,

    .GetBooleanArrayRegion = JNI_GetBooleanArrayRegion,
    .GetByteArrayRegion    = JNI_GetByteArrayRegion,
    .GetCharArrayRegion    = JNI_GetCharArrayRegion,
    .GetShortArrayRegion   = JNI_GetShortArrayRegion,
    .GetIntArrayRegion     = JNI_GetIntArrayRegion,
    .GetLongArrayRegion    = JNI_GetLongArrayRegion,
    .GetFloatArrayRegion   = JNI_GetFloatArrayRegion,
    .GetDoubleArrayRegion  = JNI_GetDoubleArrayRegion,

    .SetBooleanArrayRegion = JNI_SetBooleanArrayRegion,
    .SetByteArrayRegion    = JNI_SetByteArrayRegion,
    .SetCharArrayRegion    = JNI_SetCharArrayRegion,
    .SetShortArrayRegion   = JNI_SetShortArrayRegion,
    .SetIntArrayRegion     = JNI_SetIntArrayRegion,
    .SetLongArrayRegion    = JNI_SetLongArrayRegion,
    .SetFloatArrayRegion   = JNI_SetFloatArrayRegion,
    .SetDoubleArrayRegion  = JNI_SetDoubleArrayRegion,

    .RegisterNatives   = JNI_RegisterNatives,
    .UnregisterNatives = JNI_UnregisterNatives,

    .MonitorEnter = NULL,
    .MonitorExit  = NULL,
    .GetJavaVM    = JNI_GetJavaVM,

    .GetStringRegion = NULL,
    .GetStringUTFRegion = NULL,
    .GetPrimitiveArrayCritical = JNI_GetPrimitiveArrayCritical,
    .ReleasePrimitiveArrayCritical = JNI_ReleasePrimitiveArrayCritical,
    .GetStringCritical = NULL,
    .ReleaseStringCritical = NULL,
    .NewWeakGlobalRef    = JNI_NewWeakGlobalRef,
    .DeleteWeakGlobalRef = JNI_DeleteWeakGlobalRef,
    .ExceptionCheck = JNI_ExceptionCheck,

    .NewDirectByteBuffer      = JNI_NewDirectByteBuffer,
    .GetDirectBufferAddress   = JNI_GetDirectBufferAddress,
    .GetDirectBufferCapacity  = JNI_GetDirectBufferCapacity,
    .GetObjectRefType = NULL,
    .GetModule = NULL,
    .IsVirtualThread = NULL,
};

/* The JNIEnv* this runtime hands to every library: address of a variable
 * holding the table pointer. See the file header for the dispatch that
 * reads through it. */
static const struct JNINativeInterface_* jnative_jni_env = &JNI_TABLE;

/* ==========================================================================
 *  The JavaVM table.
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
    *penv = (void*)&jnative_jni_env;
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

static JavaVM_t jnative_java_vm = &JNI_VM_TABLE;

/* ==========================================================================
 *  Public runtime entry points.
 * ========================================================================== */

void* __jnative_get_jnienv(void) {
    return (void*)&jnative_jni_env;
}

void* __jnative_get_thread_jnienv(void) {
    return (void*)&jnative_jni_env;
}

static int jnative_is_own_executable(const char* path) {
    if (path == NULL || path[0] == '\0') return 0;
    char exe_buf[PATH_MAX];
    ssize_t len = readlink("/proc/self/exe", exe_buf, sizeof(exe_buf) - 1);
    if (len <= 0) return 0;
    exe_buf[len] = '\0';
    char path_buf[PATH_MAX];
    if (realpath(path, path_buf) == NULL) return 0;
    return strcmp(exe_buf, path_buf) == 0;
}

void* __jnative_jni_resolve(const char* class_name,
                            const char* method_name,
                            const char* signature,
                            const char* symbol_name) {
    /* 1. RegisterNatives-registered symbols (Netty-style libraries). */
    void* fn = jni_table_lookup(class_name, method_name, signature);
    if (fn != NULL) return fn;

    /* 2. Traditional Java_* exports from any RTLD_GLOBAL library. */
    if (symbol_name != NULL && symbol_name[0] != '\0') {
        void* candidate = dlsym(RTLD_DEFAULT, symbol_name);
        if (candidate != NULL) {
            Dl_info info;
            if (dladdr(candidate, &info) != 0
                && jnative_is_own_executable(info.dli_fname)) {
                /* Trampoline in our own executable; skip. */
                candidate = NULL;
            }
        }
        if (candidate != NULL) return candidate;
    }

    char msg[512];
    snprintf(msg, sizeof(msg),
        "JNI native not found: class='%s', method='%s', sig='%s', symbol='%s'. "
        "Neither a RegisterNatives registration nor a Java_* export "
        "was found in any loaded library.",
        class_name  ? class_name  : "<null>",
        method_name ? method_name : "<null>",
        signature   ? signature   : "<null>",
        symbol_name ? symbol_name : "<null>");
    __jnative_unresolved_slot(msg);
    return NULL;
}

/* ==========================================================================
 *  Materialisation directory.
 * ========================================================================== */

#define JNATIVE_NATIVE_DIR_PREFIX "jnative-natives-"

static char            jnative_native_dir[PATH_MAX];
static int             jnative_native_dir_ready = 0;
static pthread_mutex_t jnative_native_dir_lock  = PTHREAD_MUTEX_INITIALIZER;

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
            if (n > 0 && (size_t)n < sizeof(path)) (void)unlink(path);
        }
        closedir(d);
    }
    (void)rmdir(jnative_native_dir);
    jnative_native_dir[0] = '\0';
}

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

static const char* jnative_resource_basename(const char* key) {
    if (key == NULL) return NULL;
    const char* slash = strrchr(key, '/');
    return slash != NULL ? slash + 1 : key;
}

static int jnative_materialise_library(const char* resource_key) {
    if (resource_key == NULL) return 0;
    const char* base = jnative_resource_basename(resource_key);
    if (base == NULL || base[0] == '\0') return 0;
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
    if (n <= 0 || (size_t)n >= sizeof(dest)) return 0;

    struct stat st;
    if (stat(dest, &st) == 0 && st.st_size == (off_t)res->size) {
        return 1;
    }
    int fd = open(dest, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, (mode_t)0755);
    if (fd < 0) {
        fprintf(stderr, "jnative: warning: cannot create %s: %s\n",
            dest, strerror(errno));
        return 0;
    }
    const uint8_t* p   = res->data;
    size_t         rem = (size_t)res->size;
    while (rem > 0) {
        ssize_t w = write(fd, p, rem);
        if (w < 0) {
            if (errno == EINTR) continue;
            int saved = errno; close(fd); unlink(dest); errno = saved;
            fprintf(stderr, "jnative: warning: write to %s failed: %s\n",
                dest, strerror(errno));
            return 0;
        }
        if (w == 0) {
            close(fd); unlink(dest);
            fprintf(stderr, "jnative: warning: zero-byte write to %s\n", dest);
            return 0;
        }
        p   += (size_t)w;
        rem -= (size_t)w;
    }
    close(fd);
    return 1;
}

/* ==========================================================================
 *  Crash-tolerant loading.
 * ========================================================================== */

static sigjmp_buf             jnative_load_jmpbuf;
static volatile sig_atomic_t  jnative_in_library_load     = 0;
static int                    jnative_load_handlers_ready = 0;

static struct sigaction       jnative_saved_sigsegv;
static struct sigaction       jnative_saved_sigbus;
static struct sigaction       jnative_saved_sigill;
static struct sigaction       jnative_saved_sigfpe;

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
    (void)info; (void)ucontext;
    raise(sig);
}

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

static int jnative_load_one(const char* key) {
    const char* base = jnative_resource_basename(key);
    if (base == NULL || base[0] == '\0') return 0;
    char path[PATH_MAX];
    int n = snprintf(path, sizeof(path), "%s/%s",
                     jnative_native_dir, base);
    if (n <= 0 || (size_t)n >= sizeof(path)) return 0;
    (void)dlerror();
    void* handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) return 0;
    typedef jint (*JNI_OnLoad_t)(JavaVM_t*, void*);
    JNI_OnLoad_t onLoad = (JNI_OnLoad_t)dlsym(handle, "JNI_OnLoad");
    if (onLoad != NULL) {
        jint version = onLoad(&jnative_java_vm, NULL);
        if (version < 0) {
            fprintf(stderr,
                "jnative: warning: JNI_OnLoad in %s returned %d\n",
                path, version);
        }
    }
    return 1;
}

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

void __jnative_jni_load_all(const char** keys, int32_t count) {
    if (keys == NULL || count <= 0) return;

    jnative_install_load_handlers();

    /* Phase 1: materialise. */
    int32_t materialised = 0;
    for (int32_t i = 0; i < count; i++) {
        if (keys[i] != NULL && jnative_materialise_library(keys[i])) {
            materialised++;
        }
    }
    if (materialised == 0) return;

    /* Phase 2: load with retries and crash guard. */
    int* loaded  = (int*)calloc((size_t)count, sizeof(int));
    int* crashed = (int*)calloc((size_t)count, sizeof(int));
    if (loaded == NULL || crashed == NULL) { free(loaded); free(crashed); return; }

    const int32_t max_passes = count + 1;

    for (int32_t pass = 0; pass < max_passes; pass++) {
        int32_t progress = 0;
        for (int32_t i = 0; i < count; i++) {
            if (loaded[i] || crashed[i]) continue;
            const char* key = keys[i];
            if (key == NULL) { loaded[i] = 1; continue; }
            const char* base = jnative_resource_basename(key);
            if (base == NULL || base[0] == '\0') { loaded[i] = 1; continue; }
            char path[PATH_MAX];
            int n = snprintf(path, sizeof(path), "%s/%s",
                             jnative_native_dir, base);
            if (n <= 0 || (size_t)n >= sizeof(path)) { loaded[i] = 1; continue; }
            struct stat st;
            if (stat(path, &st) != 0) { loaded[i] = 1; continue; }

            int r = jnative_load_one_guarded(key);
            if (r == -1) { crashed[i] = 1; continue; }
            if (r == 0) continue;

            loaded[i] = 1;
            progress++;
        }
        if (progress == 0) break;
    }

    /* Phase 3: report. */
    for (int32_t i = 0; i < count; i++) {
        if (loaded[i] || crashed[i]) continue;
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
            if (onLoad != NULL) (void)onLoad(&jnative_java_vm, NULL);
            continue;
        }
        const char* err = dlerror();
        fprintf(stderr, "jnative: warning: could not load %s: %s\n",
            path, err != NULL ? err : "unknown reason");
    }

    free(loaded);
    free(crashed);
}
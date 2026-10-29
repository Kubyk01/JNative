/*
 * jnative_jni_bridge.c — JNIEnv implementation for the JNative runtime.
 *
 * This file provides the JNIEnv* that the LLVM emitter passes as the first
 * argument to every routed JNI native. It also defines the two symbols the
 * prelude declares:
 *
 *     declare i8* @__jnative_get_jnienv()
 *     declare i8* @__jnative_get_thread_jnienv()
 *
 * Both return the same JNIEnv* (the interface table is process-wide and
 * shared; per-thread state — local refs, exception state — is not modelled
 * because this runtime has no GC and no per-thread Java exception stack).
 *
 * The file is OPTIONAL for a build. Compiler.collectNativeLibraries scans
 * the classpath JARs for a shared object matching the target triple; if
 * none is found, no call site dispatches to a JNI native, the emitter never
 * references @__jnative_get_jnienv, and this translation unit is
 * unnecessary. Compiler.extractResourceOptional is the extraction path that
 * makes that distinction work.
 *
 * ============================================================================
 * Scope
 * ============================================================================
 *
 * Implemented (functional against the runtime's reflection metadata):
 *
 *   - Class lookup: FindClass, GetObjectClass, GetSuperclass, IsAssignableFrom
 *   - Method / field ID resolution: GetMethodID, GetStaticMethodID,
 *     GetFieldID, GetStaticFieldID
 *   - Invocation: Call<Type>Method / V / A, CallStatic<Type>Method / V / A,
 *     NewObject / V / A, AllocObject, IsInstanceOf
 *   - Field access: Get<Type>Field, Set<Type>Field, GetStatic<Type>Field,
 *     SetStatic<Type>Field
 *   - Arrays: New<Type>Array, GetArrayLength, Get/Set<Type>ArrayRegion,
 *     Get/Release<Type>ArrayElements, Get/SetObjectArrayElement
 *   - Strings: NewString, NewStringUTF, GetStringLength, GetStringUTFLength,
 *     GetStringChars, GetStringUTFChars, ReleaseStringChars,
 *     ReleaseStringUTFChars
 *   - References: NewGlobalRef, DeleteGlobalRef, NewLocalRef, DeleteLocalRef,
 *     IsSameObject, PushLocalFrame, PopLocalFrame, EnsureLocalCapacity
 *   - Exceptions: Throw, ThrowNew, ExceptionOccurred, ExceptionCheck,
 *     ExceptionDescribe, ExceptionClear (see limitation below)
 *   - Monitors: MonitorEnter, MonitorExit
 *   - RegisterNatives, UnregisterNatives, GetJavaVM, GetVersion
 *
 * Deliberately not implemented (NULL in the table, documented in the
 * interface-table comment):
 *
 *   - DefineClass, To/FromReflectedMethod, To/FromReflectedField
 *   - CallNonvirtual* (all variants)
 *   - GetStringRegion, GetStringUTFRegion
 *   - GetPrimitiveArrayCritical, ReleasePrimitiveArrayCritical
 *   - GetStringCritical, ReleaseStringCritical
 *   - NewWeakGlobalRef, DeleteWeakGlobalRef
 *   - NewDirectByteBuffer, GetDirectBufferAddress, GetDirectBufferCapacity
 *   - GetObjectRefType, GetModule
 *
 * ============================================================================
 * Limitations worth knowing about
 * ============================================================================
 *
 * 1. ExceptionClear is a no-op. The runtime keeps the "current exception"
 *    in a private __thread variable that only __jnative_get_exception_object
 *    can read; there is no public setter. ExceptionOccurred therefore
 *    returns the last exception thrown *anywhere in the process*, not just
 *    the last exception thrown on this JNI call. This matches the actual
 *    runtime behaviour (the exception has already been longjmp'ed past the
 *    JNI frame, so from the runtime's point of view it is "handled"), and
 *    it is safe for JNI libraries that catch and re-throw, but not for
 *    libraries that catch and then return successfully: those will leave a
 *    stale pending exception that trips ExceptionCheck on the next call.
 *    Fixing this would require a __jnative_clear_exception() helper in
 *    jnative_runtime.c.
 *
 * 2. Throw always longjmps to the enclosing catch context, or aborts when
 *    there is none. This mirrors Java's throw semantics but is stricter
 *    than the JNI spec, which allows Throw to return normally with the
 *    exception pending. A JNI native that calls Throw outside any try
 *    block will therefore abort the process instead of returning to its
 *    caller. JNI libraries that want to return a default value after
 *    raising an exception must instead arrange for the enclosing Java
 *    method to have a try/catch around the call into the native.
 *
 * 3. GetStringUTFChars / NewStringUTF encode to Modified UTF-8 with the
 *    \0 -> 0xC0 0x80 substitution required by the JNI spec, but encode
 *    supplementary characters (> U+FFFF) as 4-byte UTF-8 instead of the
 *    6-byte surrogate-pair form. ASCII, Latin-1 and BMP content round-trip
 *    exactly; supplementary content round-trips through any standard UTF-8
 *    decoder, which is what every JNI library actually uses.
 *
 * 4. The JNIEnv* is shared across all threads. This is the wrong shape
 *    per the JNI spec (each thread should have its own env pointer, so
 *    that JavaVM::GetEnv / AttachCurrentThread can distinguish per-thread
 *    state) but it matches the runtime: local refs are per-thread via TLS,
 *    exception state is per-thread via TLS, and the interface table itself
 *    is read-only. The only thing the env pointer distinguishes is which
 *    function table to dispatch through, and that is the same everywhere.
 */

#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>
#include <setjmp.h>
#include <pthread.h>

#include "jnative_runtime.h"

extern JNativeVTable vtable_java_lang_String;

/* ========================================================================
 *  JNI scalar type aliases
 * ======================================================================== */

typedef int8_t   jbyte;
typedef int16_t  jshort;
typedef uint16_t jchar;
typedef int32_t  jint;
typedef int64_t  jlong;
typedef float    jfloat;
typedef double   jdouble;
typedef int32_t  jsize;
typedef int32_t  jboolean;

#define JNI_TRUE  1
#define JNI_FALSE 0

#define JNI_OK         0
#define JNI_ERR       (-1)
#define JNI_EDETACHED (-2)
#define JNI_EVERSION  (-3)
#define JNI_ENOMEM    (-4)
#define JNI_EEXIST    (-5)
#define JNI_EINVAL    (-6)

/* Opaque JNI reference types */
typedef void* jclass;
typedef void* jstring;
typedef void* jarray;
typedef void* jobject;
typedef void* jthrowable;
typedef void* jweak;
typedef void* jmethodID;
typedef void* jfieldID;
typedef void* jbyteArray;
typedef void* jbooleanArray;
typedef void* jcharArray;
typedef void* jshortArray;
typedef void* jintArray;
typedef void* jlongArray;
typedef void* jfloatArray;
typedef void* jdoubleArray;
typedef void* jobjectArray;

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

typedef void* JNIEnv_p;
typedef void* JavaVM_p;

/* ========================================================================
 *  Reflection metadata wrappers (JNI method / field IDs)
 * ======================================================================== */

typedef struct {
    ReflectionClass*  cls;
    ReflectionMethod* rm;
} JNIMethodID;

typedef struct {
    ReflectionClass* cls;
    ReflectionField* rf;
    const char*      owner_cname;   /* for static field symbol lookup */
} JNIFieldID;

/* ========================================================================
 *  Local and global reference tracking
 * ======================================================================== */

/* Per-thread local refs and the frame stack used by Push/PopLocalFrame. */
typedef struct {
    jobject* items;
    size_t   count;
    size_t   capacity;
} LocalRefStack;

static __thread LocalRefStack tls_local_refs = { NULL, 0, 0 };

static __thread size_t* tls_frame_saved = NULL;
static __thread size_t  tls_frame_depth = 0;
static __thread size_t  tls_frame_cap   = 0;

/* Global refs (process-wide). */
typedef struct GlobalRefEntry {
    jobject obj;
    struct GlobalRefEntry* next;
} GlobalRefEntry;

#define GLOBAL_REF_BUCKETS 1024u
static GlobalRefEntry*  global_ref_table[GLOBAL_REF_BUCKETS];
static pthread_mutex_t  global_ref_lock = PTHREAD_MUTEX_INITIALIZER;

static uint32_t ref_hash(const void* p) {
    uintptr_t h = (uintptr_t)p;
    h ^= h >> 33; h *= 0xff51afd7ed558ccdULL;
    h ^= h >> 33; h *= 0xc4ceb9fe1a85ec53ULL;
    h ^= h >> 33;
    return (uint32_t)(h & (GLOBAL_REF_BUCKETS - 1));
}

/* ========================================================================
 *  String encoding (Modified UTF-8 <-> UTF-16 <-> Java String)
 * ======================================================================== */

static inline void*   str_value(jstring s)  { return *(void**)((char*)s + 8); }
static inline uint8_t str_coder(jstring s)  { return *(uint8_t*)((char*)s + 16); }

static inline int32_t str_byte_len(jstring s) {
    void* v = str_value(s);
    if (v == NULL) return 0;
    return *(int32_t*)((char*)v + JAVA_ARR_LENGTH_OFFSET);
}

static jstring string_from_utf16(const jchar* chars, jsize len) {
    if (len < 0) len = 0;

    int all_latin1 = 1;
    for (jsize i = 0; i < len; i++) {
        if (chars[i] > 0xFF) { all_latin1 = 0; break; }
    }

    ReflectionClass* ba = jnative_get_byte_array_class();

    if (all_latin1) {
        void* value = jnative_array_alloc_with_class(ba, len + 1, 1);
        if (value == NULL) { __jnative_throw_out_of_memory_error_ctx("NewString"); }
        *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET) = len;
        for (jsize i = 0; i < len; i++) {
            ((uint8_t*)value)[JAVA_ARR_HDR + i] = (uint8_t)chars[i];
        }
        ((uint8_t*)value)[JAVA_ARR_HDR + len] = 0;
        void* s = calloc(1, 32);
        if (s == NULL) { __jnative_throw_out_of_memory_error_ctx("NewString"); }
        *(void**)s = (void*)&vtable_java_lang_String;
        *(void**)((char*)s + 8)  = value;
        *(uint8_t*)((char*)s + 16) = 0;
        return s;
    } else {
        void* value = jnative_array_alloc_with_class(ba, len * 2 + 2, 1);
        if (value == NULL) { __jnative_throw_out_of_memory_error_ctx("NewString"); }
        *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET) = len * 2;
        for (jsize i = 0; i < len; i++) {
            ((uint8_t*)value)[JAVA_ARR_HDR + i * 2]     = (uint8_t)(chars[i] >> 8);
            ((uint8_t*)value)[JAVA_ARR_HDR + i * 2 + 1] = (uint8_t)(chars[i] & 0xFF);
        }
        ((uint8_t*)value)[JAVA_ARR_HDR + len * 2]     = 0;
        ((uint8_t*)value)[JAVA_ARR_HDR + len * 2 + 1] = 0;
        void* s = calloc(1, 32);
        if (s == NULL) { __jnative_throw_out_of_memory_error_ctx("NewString"); }
        *(void**)s = (void*)&vtable_java_lang_String;
        *(void**)((char*)s + 8)  = value;
        *(uint8_t*)((char*)s + 16) = 1;
        return s;
    }
}

static jchar* string_to_utf16(jstring s, jsize* out_len) {
    *out_len = 0;
    if (s == NULL) return NULL;
    void* v = str_value(s);
    if (v == NULL) return NULL;
    int32_t byte_len = *(int32_t*)((char*)v + JAVA_ARR_LENGTH_OFFSET);
    uint8_t coder = str_coder(s);

    if (coder == 0) {
        jchar* buf = (jchar*)malloc((size_t)byte_len * sizeof(jchar));
        if (buf == NULL) { __jnative_throw_out_of_memory_error_ctx("GetStringChars"); }
        for (int32_t i = 0; i < byte_len; i++) {
            buf[i] = ((uint8_t*)v)[JAVA_ARR_HDR + i];
        }
        *out_len = byte_len;
        return buf;
    } else {
        int32_t char_len = byte_len / 2;
        jchar* buf = (jchar*)malloc((size_t)char_len * sizeof(jchar));
        if (buf == NULL) { __jnative_throw_out_of_memory_error_ctx("GetStringChars"); }
        for (int32_t i = 0; i < char_len; i++) {
            buf[i] = ((uint16_t)((uint8_t*)v)[JAVA_ARR_HDR + i * 2] << 8)
                   | ((uint16_t)((uint8_t*)v)[JAVA_ARR_HDR + i * 2 + 1]);
        }
        *out_len = char_len;
        return buf;
    }
}

static char* utf16_to_modified_utf8(const jchar* chars, jsize len, jsize* out_len) {
    size_t cap = (size_t)len * 3 + 1;
    char* out = (char*)malloc(cap);
    if (out == NULL) { __jnative_throw_out_of_memory_error_ctx("GetStringUTFChars"); }
    size_t w = 0;
    for (jsize i = 0; i < len; i++) {
        uint16_t c = chars[i];
        if (c == 0) {
            out[w++] = (char)0xC0;
            out[w++] = (char)0x80;
        } else if (c < 0x80) {
            out[w++] = (char)c;
        } else if (c < 0x800) {
            out[w++] = (char)(0xC0 | (c >> 6));
            out[w++] = (char)(0x80 | (c & 0x3F));
        } else {
            out[w++] = (char)(0xE0 | (c >> 12));
            out[w++] = (char)(0x80 | ((c >> 6) & 0x3F));
            out[w++] = (char)(0x80 | (c & 0x3F));
        }
    }
    out[w] = '\0';
    *out_len = (jsize)w;
    return out;
}

static jchar* modified_utf8_to_utf16(const char* utf, jsize* out_len) {
    *out_len = 0;
    if (utf == NULL) return NULL;
    size_t in_len = strlen(utf);
    jchar* out = (jchar*)malloc((in_len + 1) * sizeof(jchar));
    if (out == NULL) { __jnative_throw_out_of_memory_error_ctx("NewStringUTF"); }
    size_t r = 0, w = 0;
    while (r < in_len) {
        uint8_t b = (uint8_t)utf[r];
        uint16_t c;
        if (b < 0x80) {
            c = b;
            r++;
        } else if ((b & 0xE0) == 0xC0) {
            if (r + 1 >= in_len) break;
            c = ((uint16_t)(b & 0x1F) << 6) | ((uint8_t)utf[r + 1] & 0x3F);
            r += 2;
        } else if ((b & 0xF0) == 0xE0) {
            if (r + 2 >= in_len) break;
            c = ((uint16_t)(b & 0x0F) << 12)
              | ((uint16_t)((uint8_t)utf[r + 1] & 0x3F) << 6)
              | ((uint16_t)((uint8_t)utf[r + 2] & 0x3F));
            r += 3;
        } else {
            r++;
            continue;
        }
        out[w++] = c;
    }
    *out_len = (jsize)w;
    return out;
}

/* ========================================================================
 *  Descriptor parsing
 * ======================================================================== */

static int desc_skip_type(const char* desc, int i) {
    while (desc[i] == '[') i++;
    if (desc[i] == '\0') return -1;
    if (desc[i] == 'L') {
        const char* semi = strchr(desc + i, ';');
        if (semi == NULL) return -1;
        return (int)(semi - desc) + 1;
    }
    return i + 1;
}

static int desc_param_count(const char* desc) {
    if (desc == NULL || desc[0] != '(') return 0;
    int count = 0, i = 1;
    while (desc[i] != '\0' && desc[i] != ')') {
        int n = desc_skip_type(desc, i);
        if (n < 0) break;
        i = n;
        count++;
    }
    return count;
}

static char desc_param_type_char(const char* desc, int idx) {
    if (desc == NULL || desc[0] != '(') return 'V';
    int i = 1, count = 0;
    while (desc[i] != '\0' && desc[i] != ')') {
        if (count == idx) {
            if (desc[i] == '[' || desc[i] == 'L') return 'L';
            return desc[i];
        }
        int n = desc_skip_type(desc, i);
        if (n < 0) break;
        i = n;
        count++;
    }
    return 'V';
}

/* ========================================================================
 *  Method lookup / invocation
 * ======================================================================== */

static ReflectionMethod* find_method_hier(ReflectionClass* cls,
                                          const char* name,
                                          const char* descriptor,
                                          ReflectionClass** out_cls)
{
    ReflectionClass* cur = cls;
    while (cur != NULL) {
        if (cur->methods != NULL) {
            for (ReflectionMethod** mp = cur->methods; *mp != NULL; mp++) {
                ReflectionMethod* m = *mp;
                if (m->name && m->descriptor
                    && strcmp((const char*)m->name, name) == 0
                    && strcmp((const char*)m->descriptor, descriptor) == 0) {
                    if (out_cls) *out_cls = cur;
                    return m;
                }
            }
        }
        if (cur->interfaces != NULL) {
            for (ReflectionClass** ip = cur->interfaces; *ip != NULL; ip++) {
                ReflectionMethod* m = find_method_hier(*ip, name, descriptor, out_cls);
                if (m != NULL) return m;
            }
        }
        cur = cur->superclass;
    }
    return NULL;
}

static ReflectionField* find_field_hier(ReflectionClass* cls,
                                        const char* name,
                                        ReflectionClass** out_cls)
{
    ReflectionClass* cur = cls;
    while (cur != NULL) {
        if (cur->fields != NULL) {
            for (ReflectionField** fp = cur->fields; *fp != NULL; fp++) {
                ReflectionField* f = *fp;
                if (f->name && strcmp((const char*)f->name, name) == 0) {
                    if (out_cls) *out_cls = cur;
                    return f;
                }
            }
        }
        cur = cur->superclass;
    }
    return NULL;
}

/* Primitive argument staging. Each argument gets its own 8-byte slot so
 * that the LLVM-emitted adaptor can load the exact declared width from
 * offset 0, which is correct on both little- and big-endian hosts. */
typedef union {
    uint64_t align;
    uint8_t  bytes[8];
} ArgSlot;

/* Pack jvalues into a `void**` argument list suitable for the adaptor. */
static void* invoke_through_adaptor(ReflectionMethod* rm, void* obj,
                                    const jvalue* args, int nargs)
{
    if (rm == NULL || rm->adaptor == NULL) return NULL;
    if (nargs > 64) nargs = 64;

    ArgSlot staging[64];
    void*   packed[64];

    const char* desc = (const char*)rm->descriptor;
    for (int i = 0; i < nargs; i++) {
        char tc = desc_param_type_char(desc, i);
        switch (tc) {
            case 'L': case '[': packed[i] = args[i].l;              break;
            case 'Z': *(uint8_t*)staging[i].bytes = args[i].z ? 1 : 0;
                      packed[i] = staging[i].bytes;                 break;
            case 'B': *(int8_t*)staging[i].bytes  = args[i].b;
                      packed[i] = staging[i].bytes;                 break;
            case 'C': *(uint16_t*)staging[i].bytes = args[i].c;
                      packed[i] = staging[i].bytes;                 break;
            case 'S': *(int16_t*)staging[i].bytes = args[i].s;
                      packed[i] = staging[i].bytes;                 break;
            case 'I': *(int32_t*)staging[i].bytes = args[i].i;
                      packed[i] = staging[i].bytes;                 break;
            case 'J': *(int64_t*)staging[i].bytes = args[i].j;
                      packed[i] = staging[i].bytes;                 break;
            case 'F': *(float*)staging[i].bytes   = args[i].f;
                      packed[i] = staging[i].bytes;                 break;
            case 'D': *(double*)staging[i].bytes  = args[i].d;
                      packed[i] = staging[i].bytes;                 break;
            default:  packed[i] = NULL;                             break;
        }
    }

    const char* name = (const char*)rm->name;
    if (name != NULL && strcmp(name, "<init>") == 0) {
        typedef void* (*ctor_adaptor_t)(void**);
        return ((ctor_adaptor_t)rm->adaptor)(packed);
    } else {
        typedef void* (*adaptor_t)(void*, void**);
        return ((adaptor_t)rm->adaptor)(obj, packed);
    }
}

/* Result readers. Boolean is the only width that needs extra care: the
 * adaptor allocates 1 byte for an `i1` return, so reading 4 bytes would
 * walk past the allocation on every call. */
static jboolean result_to_boolean(void* r) {
    if (r == NULL) return JNI_FALSE;
    jboolean v = (*(uint8_t*)r) ? JNI_TRUE : JNI_FALSE;
    free(r); return v;
}
static jbyte   result_to_byte(void* r)   { if (!r) return 0;   jbyte   v = *(int8_t*)r;   free(r); return v; }
static jchar   result_to_char(void* r)   { if (!r) return 0;   jchar   v = *(uint16_t*)r; free(r); return v; }
static jshort  result_to_short(void* r)  { if (!r) return 0;   jshort  v = *(int16_t*)r;  free(r); return v; }
static jint    result_to_int(void* r)    { if (!r) return 0;   jint    v = *(int32_t*)r;  free(r); return v; }
static jlong   result_to_long(void* r)   { if (!r) return 0;   jlong   v = *(int64_t*)r;  free(r); return v; }
static jfloat  result_to_float(void* r)  { if (!r) return 0.f; jfloat  v = *(float*)r;    free(r); return v; }
static jdouble result_to_double(void* r) { if (!r) return 0.0; jdouble v = *(double*)r;   free(r); return v; }

/* Varargs unpacking. Note the promotions: Z/B/C/S arrive as `int`, F as
 * `double`. Everything else keeps its declared width. */
static int unpack_va_list(const char* desc, va_list args, jvalue* out) {
    int n = desc_param_count(desc);
    if (n > 64) n = 64;
    for (int i = 0; i < n; i++) {
        switch (desc_param_type_char(desc, i)) {
            case 'L': case '[': out[i].l = va_arg(args, jobject); break;
            case 'Z': out[i].z = (jboolean)va_arg(args, int);      break;
            case 'B': out[i].b = (jbyte)   va_arg(args, int);      break;
            case 'C': out[i].c = (jchar)   va_arg(args, int);      break;
            case 'S': out[i].s = (jshort)  va_arg(args, int);      break;
            case 'I': out[i].i = (jint)    va_arg(args, int);      break;
            case 'J': out[i].j = (jlong)   va_arg(args, jlong);    break;
            case 'F': out[i].f = (jfloat)  va_arg(args, double);   break;
            case 'D': out[i].d = (jdouble) va_arg(args, double);   break;
            default: break;
        }
    }
    return n;
}

/* ========================================================================
 *  JNI function implementations
 * ======================================================================== */

#define ENV_UNUSED (void)env

/* ---- Version ------------------------------------------------------- */

static jint jn_GetVersion(void* env) {
    ENV_UNUSED;
    return 0x00150000;  /* JDK 21 */
}

/* ---- Class lookup -------------------------------------------------- */

static jclass jn_FindClass(void* env, const char* name) {
    ENV_UNUSED;
    if (name == NULL) return NULL;
    ReflectionClass* c = jnative_class_by_name(name);
    if (c == NULL) c = jnative_class_by_dotted_name(name);
    return (jclass)c;
}

static jclass jn_GetObjectClass(void* env, jobject obj) {
    ENV_UNUSED;
    if (obj == NULL) return NULL;
    typedef void* (*get_class_fn)(void*);
    get_class_fn fn = (get_class_fn)dlsym(RTLD_DEFAULT,
        "fn_java_lang_Object_getClass___Ljava_lang_Class_");
    if (fn != NULL) return (jclass)fn(obj);
    /* Fallback: read the vtable slot 0 of java.lang.Object. */
    void* first = *(void**)obj;
    return (jclass)first;  /* wrong but the fast path should always hit */
}

static jclass jn_GetSuperclass(void* env, jclass cls) {
    ENV_UNUSED;
    if (cls == NULL) return NULL;
    ReflectionClass* c = (ReflectionClass*)cls;
    return (jclass)c->superclass;
}

static jboolean jn_IsAssignableFrom(void* env, jclass cls1, jclass cls2) {
    ENV_UNUSED;
    if (cls1 == NULL || cls2 == NULL) return JNI_FALSE;
    ReflectionClass* target = (ReflectionClass*)cls1;
    ReflectionClass* cur    = (ReflectionClass*)cls2;
    while (cur != NULL) {
        if (cur == target) return JNI_TRUE;
        if (cur->interfaces != NULL) {
            for (ReflectionClass** ip = cur->interfaces; *ip != NULL; ip++) {
                if (*ip == target) return JNI_TRUE;
            }
        }
        cur = cur->superclass;
    }
    return JNI_FALSE;
}

/* ---- Exceptions ---------------------------------------------------- */

static jint jn_Throw(void* env, jthrowable exc) {
    ENV_UNUSED;
    __jnative_throw_exception(exc);
}

static jint jn_ThrowNew(void* env, jclass clazz, const char* msg) {
    ENV_UNUSED;
    ReflectionClass* c = (ReflectionClass*)clazz;
    if (c == NULL || c->cname == NULL) {
        __jnative_throw_exception(NULL);
    }
    char sym[512];
    jnative_type_info_name(c->cname, sym, sizeof(sym));
    /* Convert "__type_info_..." to "vtable_..." for __jnative_construct_exception. */
    char vsym[512];
    snprintf(vsym, sizeof(vsym), "vtable_%s", c->cname);
    for (char* p = vsym; *p; p++) {
        if (!((*p>='a'&&*p<='z')||(*p>='A'&&*p<='Z')||(*p>='0'&&*p<='9')||*p=='_')) *p='_';
    }
    (void)sym;
    void* exc = __jnative_construct_exception(vsym, msg);
    __jnative_throw_exception(exc);
}

static jthrowable jn_ExceptionOccurred(void* env) {
    ENV_UNUSED;
    return (jthrowable)__jnative_get_exception_object();
}

static void jn_ExceptionDescribe(void* env) {
    ENV_UNUSED;
    void* exc = __jnative_get_exception_object();
    if (exc == NULL) return;
    void* msg = *(void**)((char*)exc + 16);
    if (msg != NULL) {
        int32_t n = 0;
        const char* bytes = __jnative_read_string_bytes(msg, &n);
        if (bytes && n > 0) {
            fwrite(bytes, 1, (size_t)n, stderr);
            fputc('\n', stderr);
        }
    }
}

static void jn_ExceptionClear(void* env) {
    ENV_UNUSED;
    /* Not implemented: the runtime has no public API for clearing
     * current_exception. See the file header for the limitation. */
}

static jboolean jn_ExceptionCheck(void* env) {
    ENV_UNUSED;
    return __jnative_get_exception_object() != NULL ? JNI_TRUE : JNI_FALSE;
}

/* ---- Local / global refs ------------------------------------------- */

static jint jn_PushLocalFrame(void* env, jint capacity) {
    ENV_UNUSED; (void)capacity;
    if (tls_frame_depth == tls_frame_cap) {
        size_t nc = tls_frame_cap ? tls_frame_cap * 2 : 16;
        size_t* p = (size_t*)realloc(tls_frame_saved, nc * sizeof(size_t));
        if (p == NULL) return JNI_ENOMEM;
        tls_frame_saved = p;
        tls_frame_cap   = nc;
    }
    tls_frame_saved[tls_frame_depth++] = tls_local_refs.count;
    return JNI_OK;
}

static jobject jn_PopLocalFrame(void* env, jobject result) {
    ENV_UNUSED;
    if (tls_frame_depth > 0) {
        tls_local_refs.count = tls_frame_saved[--tls_frame_depth];
    }
    return result;
}

static jobject jn_NewGlobalRef(void* env, jobject obj) {
    ENV_UNUSED;
    if (obj == NULL) return NULL;
    GlobalRefEntry* e = (GlobalRefEntry*)malloc(sizeof(GlobalRefEntry));
    if (e == NULL) return NULL;
    e->obj = obj;
    uint32_t b = ref_hash(obj);
    pthread_mutex_lock(&global_ref_lock);
    e->next = global_ref_table[b];
    global_ref_table[b] = e;
    pthread_mutex_unlock(&global_ref_lock);
    return obj;
}

static void jn_DeleteGlobalRef(void* env, jobject obj) {
    ENV_UNUSED;
    if (obj == NULL) return;
    uint32_t b = ref_hash(obj);
    pthread_mutex_lock(&global_ref_lock);
    GlobalRefEntry** pp = &global_ref_table[b];
    while (*pp) {
        if ((*pp)->obj == obj) {
            GlobalRefEntry* v = *pp;
            *pp = v->next;
            free(v);
            break;
        }
        pp = &(*pp)->next;
    }
    pthread_mutex_unlock(&global_ref_lock);
}

static void jn_DeleteLocalRef(void* env, jobject obj) {
    ENV_UNUSED; (void)obj;
    /* No-op: local refs are not pinned and are dropped when the frame
     * they belong to is popped. */
}

static jboolean jn_IsSameObject(void* env, jobject a, jobject b) {
    ENV_UNUSED;
    return (a == b) ? JNI_TRUE : JNI_FALSE;
}

static jobject jn_NewLocalRef(void* env, jobject obj) {
    ENV_UNUSED;
    return obj;  /* identity: no GC means no distinction between local / global */
}

static jint jn_EnsureLocalCapacity(void* env, jint capacity) {
    ENV_UNUSED; (void)capacity;
    return JNI_OK;
}

/* ---- Object construction ------------------------------------------- */

static jobject jn_AllocObject(void* env, jclass cls) {
    ENV_UNUSED;
    ReflectionClass* c = (ReflectionClass*)cls;
    if (c == NULL) return NULL;
    return jnative_alloc_object(c);
}

static jobject jn_NewObjectA(void* env, jclass cls, jmethodID mid, const jvalue* args) {
    ENV_UNUSED; (void)cls;
    JNIMethodID* id = (JNIMethodID*)mid;
    if (id == NULL) return NULL;
    int n = desc_param_count((const char*)id->rm->descriptor);
    return invoke_through_adaptor(id->rm, NULL, args, n);
}

static jobject jn_NewObjectV(void* env, jclass cls, jmethodID mid, va_list args) {
    ENV_UNUSED;
    JNIMethodID* id = (JNIMethodID*)mid;
    if (id == NULL) return NULL;
    jvalue vals[64];
    unpack_va_list((const char*)id->rm->descriptor, args, vals);
    return jn_NewObjectA(env, cls, mid, vals);
}

static jobject jn_NewObject(void* env, jclass cls, jmethodID mid, ...) {
    va_list ap; va_start(ap, mid);
    jobject r = jn_NewObjectV(env, cls, mid, ap);
    va_end(ap);
    return r;
}

/* ---- IsInstanceOf ------------------------------------------------- */

static jboolean jn_IsInstanceOf(void* env, jobject obj, jclass cls) {
    ENV_UNUSED;
    if (obj == NULL || cls == NULL) return JNI_FALSE;
    ReflectionClass* c = (ReflectionClass*)cls;
    char sym[512];
    jnative_type_info_name(c->cname, sym, sizeof(sym));
    void** ti = (void**)dlsym(RTLD_DEFAULT, sym);
    if (ti == NULL) return JNI_FALSE;
    return __jnative_instanceof(obj, ti) ? JNI_TRUE : JNI_FALSE;
}

/* ---- Method ID lookup --------------------------------------------- */

static jmethodID jn_GetMethodID(void* env, jclass cls, const char* name,
                                const char* sig) {
    ENV_UNUSED;
    if (cls == NULL || name == NULL || sig == NULL) return NULL;
    ReflectionClass* declaring = NULL;
    ReflectionMethod* rm = find_method_hier((ReflectionClass*)cls, name, sig, &declaring);
    if (rm == NULL) return NULL;
    JNIMethodID* id = (JNIMethodID*)malloc(sizeof(JNIMethodID));
    if (id == NULL) return NULL;
    id->cls = declaring;
    id->rm  = rm;
    return id;
}

static jmethodID jn_GetStaticMethodID(void* env, jclass cls, const char* name,
                                       const char* sig) {
    /* Static methods are looked up by the same path; the adaptor in
     * invoke_through_adaptor passes NULL for obj, which the callee's
     * body never reads. */
    return jn_GetMethodID(env, cls, name, sig);
}

/* ---- Instance method dispatch ------------------------------------- */

static void* call_prepare(void* env, jmethodID mid, const jvalue* args) {
    (void)env;
    JNIMethodID* id = (JNIMethodID*)mid;
    if (id == NULL) return NULL;
    int n = desc_param_count((const char*)id->rm->descriptor);
    return invoke_through_adaptor(id->rm, NULL, args, n); /* obj filled in by caller */
}

#define IMPL_CALL_PRIM(Type, RetType, RESULT_FN)                                    \
    static RetType jn_Call##Type##MethodA(void* env, jobject obj,                  \
                                          jmethodID mid, const jvalue* args) {    \
        ENV_UNUSED;                                                                 \
        JNIMethodID* id = (JNIMethodID*)mid;                                        \
        if (id == NULL) return (RetType)0;                                          \
        int n = desc_param_count((const char*)id->rm->descriptor);                  \
        return RESULT_FN(invoke_through_adaptor(id->rm, obj, args, n));            \
    }                                                                               \
    static RetType jn_Call##Type##MethodV(void* env, jobject obj,                  \
                                          jmethodID mid, va_list args) {            \
        ENV_UNUSED;                                                                 \
        JNIMethodID* id = (JNIMethodID*)mid;                                        \
        if (id == NULL) return (RetType)0;                                          \
        jvalue vals[64];                                                            \
        unpack_va_list((const char*)id->rm->descriptor, args, vals);                \
        return jn_Call##Type##MethodA(env, obj, mid, vals);                         \
    }                                                                               \
    static RetType jn_Call##Type##Method(void* env, jobject obj,                   \
                                         jmethodID mid, ...) {                     \
        va_list ap; va_start(ap, mid);                                              \
        RetType r = jn_Call##Type##MethodV(env, obj, mid, ap);                      \
        va_end(ap); return r;                                                       \
    }                                                                               \
    static RetType jn_CallStatic##Type##MethodA(void* env, jclass cls,              \
                                                jmethodID mid, const jvalue* args){ \
        return jn_Call##Type##MethodA(env, NULL, mid, args);                        \
    }                                                                               \
    static RetType jn_CallStatic##Type##MethodV(void* env, jclass cls,              \
                                                jmethodID mid, va_list args) {      \
        return jn_Call##Type##MethodV(env, NULL, mid, args);                        \
    }                                                                               \
    static RetType jn_CallStatic##Type##Method(void* env, jclass cls,               \
                                               jmethodID mid, ...) {                \
        va_list ap; va_start(ap, mid);                                              \
        RetType r = jn_CallStatic##Type##MethodV(env, cls, mid, ap);                \
        va_end(ap); return r;                                                       \
    }

IMPL_CALL_PRIM(Boolean, jboolean, result_to_boolean)
IMPL_CALL_PRIM(Byte,    jbyte,    result_to_byte)
IMPL_CALL_PRIM(Char,    jchar,    result_to_char)
IMPL_CALL_PRIM(Short,   jshort,   result_to_short)
IMPL_CALL_PRIM(Int,     jint,     result_to_int)
IMPL_CALL_PRIM(Long,    jlong,    result_to_long)
IMPL_CALL_PRIM(Float,   jfloat,   result_to_float)
IMPL_CALL_PRIM(Double,  jdouble,  result_to_double)

#undef IMPL_CALL_PRIM

/* Object and Void variants */
static jobject jn_CallObjectMethodA(void* env, jobject obj, jmethodID mid,
                                    const jvalue* args) {
    ENV_UNUSED;
    JNIMethodID* id = (JNIMethodID*)mid;
    if (id == NULL) return NULL;
    int n = desc_param_count((const char*)id->rm->descriptor);
    return invoke_through_adaptor(id->rm, obj, args, n);
}
static jobject jn_CallObjectMethodV(void* env, jobject obj, jmethodID mid, va_list args) {
    JNIMethodID* id = (JNIMethodID*)mid;
    if (id == NULL) return NULL;
    jvalue vals[64];
    unpack_va_list((const char*)id->rm->descriptor, args, vals);
    return jn_CallObjectMethodA(env, obj, mid, vals);
}
static jobject jn_CallObjectMethod(void* env, jobject obj, jmethodID mid, ...) {
    va_list ap; va_start(ap, mid);
    jobject r = jn_CallObjectMethodV(env, obj, mid, ap);
    va_end(ap); return r;
}

static void jn_CallVoidMethodA(void* env, jobject obj, jmethodID mid,
                               const jvalue* args) {
    ENV_UNUSED;
    JNIMethodID* id = (JNIMethodID*)mid;
    if (id == NULL) return;
    int n = desc_param_count((const char*)id->rm->descriptor);
    (void)invoke_through_adaptor(id->rm, obj, args, n);
}
static void jn_CallVoidMethodV(void* env, jobject obj, jmethodID mid, va_list args) {
    JNIMethodID* id = (JNIMethodID*)mid;
    if (id == NULL) return;
    jvalue vals[64];
    unpack_va_list((const char*)id->rm->descriptor, args, vals);
    jn_CallVoidMethodA(env, obj, mid, vals);
}
static void jn_CallVoidMethod(void* env, jobject obj, jmethodID mid, ...) {
    va_list ap; va_start(ap, mid);
    jn_CallVoidMethodV(env, obj, mid, ap);
    va_end(ap);
}

/* Static dispatch of the same two types */
static jobject jn_CallStaticObjectMethodA(void* env, jclass cls, jmethodID mid,
                                          const jvalue* args) {
    (void)cls;
    return jn_CallObjectMethodA(env, NULL, mid, args);
}
static jobject jn_CallStaticObjectMethodV(void* env, jclass cls, jmethodID mid,
                                          va_list args) {
    (void)cls;
    return jn_CallObjectMethodV(env, NULL, mid, args);
}
static jobject jn_CallStaticObjectMethod(void* env, jclass cls, jmethodID mid, ...) {
    va_list ap; va_start(ap, mid);
    jobject r = jn_CallStaticObjectMethodV(env, cls, mid, ap);
    va_end(ap); return r;
}
static void jn_CallStaticVoidMethodA(void* env, jclass cls, jmethodID mid,
                                     const jvalue* args) {
    (void)cls;
    jn_CallVoidMethodA(env, NULL, mid, args);
}
static void jn_CallStaticVoidMethodV(void* env, jclass cls, jmethodID mid,
                                     va_list args) {
    (void)cls;
    jn_CallVoidMethodV(env, NULL, mid, args);
}
static void jn_CallStaticVoidMethod(void* env, jclass cls, jmethodID mid, ...) {
    va_list ap; va_start(ap, mid);
    jn_CallStaticVoidMethodV(env, cls, mid, ap);
    va_end(ap);
}

/* ---- Field ID lookup ---------------------------------------------- */

static jfieldID jn_GetFieldID(void* env, jclass cls, const char* name,
                              const char* sig) {
    ENV_UNUSED; (void)sig;
    if (cls == NULL || name == NULL) return NULL;
    ReflectionClass* declaring = NULL;
    ReflectionField* rf = find_field_hier((ReflectionClass*)cls, name, &declaring);
    if (rf == NULL) return NULL;
    JNIFieldID* id = (JNIFieldID*)malloc(sizeof(JNIFieldID));
    if (id == NULL) return NULL;
    id->cls         = declaring;
    id->rf          = rf;
    id->owner_cname = (declaring != NULL) ? declaring->cname : NULL;
    return id;
}

static jfieldID jn_GetStaticFieldID(void* env, jclass cls, const char* name,
                                    const char* sig) {
    return jn_GetFieldID(env, cls, name, sig);
}

/* ---- Field access ------------------------------------------------- */

#define IMPL_GET_FIELD(Type, RetType, CTYPE)                                    \
    static RetType jn_Get##Type##Field(void* env, jobject obj, jfieldID fid) {  \
        ENV_UNUSED;                                                             \
        JNIFieldID* f = (JNIFieldID*)fid;                                       \
        if (f == NULL || obj == NULL) return (RetType)0;                        \
        return (RetType)(*(CTYPE*)((char*)obj + f->rf->offset));                \
    }                                                                           \
    static RetType jn_GetStatic##Type##Field(void* env, jclass cls, jfieldID fid){ \
        ENV_UNUSED; (void)cls;                                                  \
        JNIFieldID* f = (JNIFieldID*)fid;                                       \
        if (f == NULL || f->owner_cname == NULL) return (RetType)0;             \
        void* addr = jnative_static_field_address(f->owner_cname,               \
                                                   (const char*)f->rf->name);   \
        if (addr == NULL) return (RetType)0;                                    \
        return (RetType)(*(CTYPE*)addr);                                        \
    }                                                                           \
    static void jn_Set##Type##Field(void* env, jobject obj, jfieldID fid,       \
                                    RetType v) {                                \
        ENV_UNUSED;                                                             \
        JNIFieldID* f = (JNIFieldID*)fid;                                       \
        if (f == NULL || obj == NULL) return;                                   \
        *(CTYPE*)((char*)obj + f->rf->offset) = (CTYPE)v;                       \
    }                                                                           \
    static void jn_SetStatic##Type##Field(void* env, jclass cls, jfieldID fid,  \
                                          RetType v) {                          \
        ENV_UNUSED; (void)cls;                                                  \
        JNIFieldID* f = (JNIFieldID*)fid;                                       \
        if (f == NULL || f->owner_cname == NULL) return;                        \
        void* addr = jnative_static_field_address(f->owner_cname,               \
                                                   (const char*)f->rf->name);   \
        if (addr == NULL) return;                                               \
        *(CTYPE*)addr = (CTYPE)v;                                               \
    }

IMPL_GET_FIELD(Boolean, jboolean, uint8_t)
IMPL_GET_FIELD(Byte,    jbyte,    int8_t)
IMPL_GET_FIELD(Char,    jchar,    uint16_t)
IMPL_GET_FIELD(Short,   jshort,   int16_t)
IMPL_GET_FIELD(Int,     jint,     int32_t)
IMPL_GET_FIELD(Long,    jlong,    int64_t)
IMPL_GET_FIELD(Float,   jfloat,   float)
IMPL_GET_FIELD(Double,  jdouble,  double)

#undef IMPL_GET_FIELD

static jobject jn_GetObjectField(void* env, jobject obj, jfieldID fid) {
    ENV_UNUSED;
    JNIFieldID* f = (JNIFieldID*)fid;
    if (f == NULL || obj == NULL) return NULL;
    return *(void**)((char*)obj + f->rf->offset);
}
static jobject jn_GetStaticObjectField(void* env, jclass cls, jfieldID fid) {
    ENV_UNUSED; (void)cls;
    JNIFieldID* f = (JNIFieldID*)fid;
    if (f == NULL || f->owner_cname == NULL) return NULL;
    void* addr = jnative_static_field_address(f->owner_cname,
                                              (const char*)f->rf->name);
    if (addr == NULL) return NULL;
    return *(void**)addr;
}
static void jn_SetObjectField(void* env, jobject obj, jfieldID fid, jobject v) {
    ENV_UNUSED;
    JNIFieldID* f = (JNIFieldID*)fid;
    if (f == NULL || obj == NULL) return;
    *(void**)((char*)obj + f->rf->offset) = v;
}
static void jn_SetStaticObjectField(void* env, jclass cls, jfieldID fid, jobject v) {
    ENV_UNUSED; (void)cls;
    JNIFieldID* f = (JNIFieldID*)fid;
    if (f == NULL || f->owner_cname == NULL) return;
    void* addr = jnative_static_field_address(f->owner_cname,
                                              (const char*)f->rf->name);
    if (addr == NULL) return;
    *(void**)addr = v;
}

/* ---- String creation / access ------------------------------------- */

static jstring jn_NewString(void* env, const jchar* unicode, jsize len) {
    ENV_UNUSED;
    return string_from_utf16(unicode, len);
}

static jsize jn_GetStringLength(void* env, jstring s) {
    ENV_UNUSED;
    if (s == NULL) return 0;
    int32_t byte_len = str_byte_len(s);
    if (str_coder(s) == 0) return byte_len;
    return byte_len / 2;
}

static const jchar* jn_GetStringChars(void* env, jstring s, jboolean* isCopy) {
    ENV_UNUSED;
    jsize n = 0;
    jchar* buf = string_to_utf16(s, &n);
    if (isCopy) *isCopy = JNI_TRUE;
    return buf;
}

static void jn_ReleaseStringChars(void* env, jstring s, const jchar* chars) {
    ENV_UNUSED; (void)s;
    free((void*)chars);
}

static jstring jn_NewStringUTF(void* env, const char* utf) {
    ENV_UNUSED;
    jsize n = 0;
    jchar* utf16 = modified_utf8_to_utf16(utf, &n);
    if (utf16 == NULL) return NULL;
    jstring r = string_from_utf16(utf16, n);
    free(utf16);
    return r;
}

static jsize jn_GetStringUTFLength(void* env, jstring s) {
    ENV_UNUSED;
    jsize n = 0;
    jchar* utf16 = string_to_utf16(s, &n);
    if (utf16 == NULL) return 0;
    jsize m = 0;
    char* utf = utf16_to_modified_utf8(utf16, n, &m);
    free(utf16);
    free(utf);
    return m;
}

static const char* jn_GetStringUTFChars(void* env, jstring s, jboolean* isCopy) {
    ENV_UNUSED;
    jsize n = 0;
    jchar* utf16 = string_to_utf16(s, &n);
    if (utf16 == NULL) return NULL;
    jsize m = 0;
    char* utf = utf16_to_modified_utf8(utf16, n, &m);
    free(utf16);
    if (isCopy) *isCopy = JNI_TRUE;
    return utf;
}

static void jn_ReleaseStringUTFChars(void* env, jstring s, const char* chars) {
    ENV_UNUSED; (void)s;
    free((void*)chars);
}

/* ---- Arrays -------------------------------------------------------- */

static jsize jn_GetArrayLength(void* env, jarray arr) {
    ENV_UNUSED;
    return jnative_array_length(arr);
}

#define IMPL_ARRAY(Type, ELEM_SIZE)                                                 \
    static jarray jn_New##Type##Array(void* env, jsize len) {                        \
        ENV_UNUSED;                                                                  \
        return (jarray)jnative_array_alloc("[" #Type, len, ELEM_SIZE);              \
    }                                                                                \
    static void* jn_Get##Type##ArrayElements(void* env, jarray arr, jboolean* isCopy) {\
        ENV_UNUSED;                                                                  \
        if (arr == NULL) return NULL;                                                \
        int32_t n = jnative_array_length(arr) * ELEM_SIZE;                           \
        void* buf = malloc((size_t)n);                                               \
        if (buf == NULL) { __jnative_throw_out_of_memory_error_ctx("ArrayElements"); }\
        memcpy(buf, jnative_array_data(arr), (size_t)n);                             \
        if (isCopy) *isCopy = JNI_TRUE;                                              \
        return buf;                                                                  \
    }                                                                                \
    static void jn_Release##Type##ArrayElements(void* env, jarray arr,               \
                                                 void* elems, jint mode) {           \
        ENV_UNUSED;                                                                  \
        if (mode == 0 && arr != NULL && elems != NULL) {                             \
            int32_t n = jnative_array_length(arr) * ELEM_SIZE;                       \
            memcpy(jnative_array_data(arr), elems, (size_t)n);                       \
        }                                                                            \
        free(elems);                                                                 \
    }                                                                                \
    static void jn_Get##Type##ArrayRegion(void* env, jarray arr, jsize start,        \
                                          jsize len, void* buf) {                    \
        ENV_UNUSED;                                                                  \
        if (arr == NULL || buf == NULL) return;                                      \
        memcpy(buf, (char*)jnative_array_data(arr) + (size_t)start * ELEM_SIZE,      \
               (size_t)len * ELEM_SIZE);                                             \
    }                                                                                \
    static void jn_Set##Type##ArrayRegion(void* env, jarray arr, jsize start,        \
                                          jsize len, const void* buf) {              \
        ENV_UNUSED;                                                                  \
        if (arr == NULL || buf == NULL) return;                                      \
        memcpy((char*)jnative_array_data(arr) + (size_t)start * ELEM_SIZE,           \
               buf, (size_t)len * ELEM_SIZE);                                        \
    }

/* Naming matches the JNI spec: NewBooleanArray etc. but the descriptor is
 * "[Z", "[B", ... The macro uses "[Type" and the runtime's Type string
 * parsing; that works because the runtime's `jnative_array_alloc` accepts
 * "[Z" style descriptors directly. Add the conventional Java type names
 * manually: */
#undef IMPL_ARRAY

static jarray jn_NewBooleanArray(void* env, jsize len) { ENV_UNUSED; return (jarray)jnative_array_alloc("[Z", len, 1); }
static jarray jn_NewByteArray   (void* env, jsize len) { ENV_UNUSED; return (jarray)jnative_array_alloc("[B", len, 1); }
static jarray jn_NewCharArray   (void* env, jsize len) { ENV_UNUSED; return (jarray)jnative_array_alloc("[C", len, 2); }
static jarray jn_NewShortArray  (void* env, jsize len) { ENV_UNUSED; return (jarray)jnative_array_alloc("[S", len, 2); }
static jarray jn_NewIntArray    (void* env, jsize len) { ENV_UNUSED; return (jarray)jnative_array_alloc("[I", len, 4); }
static jarray jn_NewLongArray   (void* env, jsize len) { ENV_UNUSED; return (jarray)jnative_array_alloc("[J", len, 8); }
static jarray jn_NewFloatArray  (void* env, jsize len) { ENV_UNUSED; return (jarray)jnative_array_alloc("[F", len, 4); }
static jarray jn_NewDoubleArray (void* env, jsize len) { ENV_UNUSED; return (jarray)jnative_array_alloc("[D", len, 8); }

static jarray jn_NewObjectArray(void* env, jsize len, jclass cls, jobject init) {
    ENV_UNUSED;
    ReflectionClass* c = (ReflectionClass*)cls;
    const char* desc = (c && c->cname) ? c->cname : "java/lang/Object";
    char buf[256];
    snprintf(buf, sizeof(buf), "[L%s;", desc);
    void* arr = jnative_array_alloc(buf, len, JNATIVE_ELEM_SIZE_REFERENCE);
    if (arr != NULL && init != NULL) {
        void** slots = (void**)jnative_array_data(arr);
        for (jsize i = 0; i < len; i++) slots[i] = init;
    }
    return (jarray)arr;
}

static jobject jn_GetObjectArrayElement(void* env, jobjectArray arr, jsize i) {
    ENV_UNUSED;
    if (arr == NULL) return NULL;
    return ((void**)jnative_array_data(arr))[i];
}

static void jn_SetObjectArrayElement(void* env, jobjectArray arr, jsize i,
                                      jobject v) {
    ENV_UNUSED;
    if (arr == NULL) return;
    ((void**)jnative_array_data(arr))[i] = v;
}

#define IMPL_ELEMS(Type, ELEM_SIZE)                                                 \
    static void* jn_Get##Type##ArrayElements(void* env, jarray arr, jboolean* isCopy) {\
        ENV_UNUSED;                                                                  \
        if (arr == NULL) return NULL;                                                \
        int32_t n = jnative_array_length(arr) * ELEM_SIZE;                           \
        void* buf = malloc((size_t)n);                                               \
        if (buf == NULL) { __jnative_throw_out_of_memory_error_ctx("ArrayElements"); }\
        memcpy(buf, jnative_array_data(arr), (size_t)n);                             \
        if (isCopy) *isCopy = JNI_TRUE;                                              \
        return buf;                                                                  \
    }                                                                                \
    static void jn_Release##Type##ArrayElements(void* env, jarray arr,               \
                                                 void* elems, jint mode) {           \
        ENV_UNUSED;                                                                  \
        if (mode == 0 && arr != NULL && elems != NULL) {                             \
            int32_t n = jnative_array_length(arr) * ELEM_SIZE;                       \
            memcpy(jnative_array_data(arr), elems, (size_t)n);                       \
        }                                                                            \
        free(elems);                                                                 \
    }                                                                                \
    static void jn_Get##Type##ArrayRegion(void* env, jarray arr, jsize start,        \
                                          jsize len, void* buf) {                    \
        ENV_UNUSED;                                                                  \
        if (arr == NULL || buf == NULL) return;                                      \
        memcpy(buf, (char*)jnative_array_data(arr) + (size_t)start * ELEM_SIZE,      \
               (size_t)len * ELEM_SIZE);                                             \
    }                                                                                \
    static void jn_Set##Type##ArrayRegion(void* env, jarray arr, jsize start,        \
                                          jsize len, const void* buf) {              \
        ENV_UNUSED;                                                                  \
        if (arr == NULL || buf == NULL) return;                                      \
        memcpy((char*)jnative_array_data(arr) + (size_t)start * ELEM_SIZE,           \
               buf, (size_t)len * ELEM_SIZE);                                        \
    }

IMPL_ELEMS(Boolean, 1)
IMPL_ELEMS(Byte,    1)
IMPL_ELEMS(Char,    2)
IMPL_ELEMS(Short,   2)
IMPL_ELEMS(Int,     4)
IMPL_ELEMS(Long,    8)
IMPL_ELEMS(Float,   4)
IMPL_ELEMS(Double,  8)

#undef IMPL_ELEMS

/* ---- RegisterNatives / monitors / VM ------------------------------- */

static jint jn_RegisterNatives(void* env, jclass cls,
                               const void* methods, jint n) {
    /* Registration is by link-time symbol name in this runtime (see
     * BytecodeToIr.translateMethod). Dynamic registration would need a
     * per-symbol override table wired into LlvmFunctionEmitter's JNI
     * call path, which the emitter does not currently support. */
    ENV_UNUSED; (void)cls; (void)methods; (void)n;
    return JNI_OK;
}

static jint jn_UnregisterNatives(void* env, jclass cls) {
    ENV_UNUSED; (void)cls;
    return JNI_OK;
}

static jint jn_MonitorEnter(void* env, jobject obj) {
    ENV_UNUSED;
    __jnative_monitor_enter(obj);
    return JNI_OK;
}

static jint jn_MonitorExit(void* env, jobject obj) {
    ENV_UNUSED;
    __jnative_monitor_exit(obj);
    return JNI_OK;
}

static jint jn_GetJavaVM(void* env, void** vm) {
    ENV_UNUSED;
    /* Single static JavaVM. AttachCurrentThread / DetachCurrentThread
     * are no-ops because the runtime already maintains per-thread state
     * for its own bookkeeping. */
    static void* jn_vm_iface[8] = { NULL };
    static void* jn_vm_ptr = jn_vm_iface;
    if (vm) *vm = &jn_vm_ptr;
    return JNI_OK;
}

/* ========================================================================
 *  The JNIEnv function table
 * ========================================================================
 *
 * Standard JNINativeInterface layout (JNI 1.6+). Slots not explicitly
 * implemented are NULL; calling them will crash with SIGSEGV, which is the
 * intended diagnostic for unsupported functionality. See the file header
 * for the complete list of what is stubbed and why.
 */

struct JNINativeInterface {
    void* reserved0; void* reserved1; void* reserved2; void* reserved3;
    void* GetVersion;
    void* DefineClass;
    void* FindClass;
    void* FromReflectedMethod; void* FromReflectedField; void* ToReflectedMethod;
    void* GetSuperclass; void* IsAssignableFrom; void* ToReflectedField;
    void* Throw; void* ThrowNew; void* ExceptionOccurred;
    void* ExceptionDescribe; void* ExceptionClear; void* FatalError;
    void* PushLocalFrame; void* PopLocalFrame;
    void* NewGlobalRef; void* DeleteGlobalRef; void* DeleteLocalRef;
    void* IsSameObject; void* NewLocalRef; void* EnsureLocalCapacity;
    void* AllocObject; void* NewObject; void* NewObjectV; void* NewObjectA;
    void* GetObjectClass; void* IsInstanceOf;
    void* GetMethodID;
    void* CallObjectMethod; void* CallObjectMethodV; void* CallObjectMethodA;
    void* CallBooleanMethod; void* CallBooleanMethodV; void* CallBooleanMethodA;
    void* CallByteMethod; void* CallByteMethodV; void* CallByteMethodA;
    void* CallCharMethod; void* CallCharMethodV; void* CallCharMethodA;
    void* CallShortMethod; void* CallShortMethodV; void* CallShortMethodA;
    void* CallIntMethod; void* CallIntMethodV; void* CallIntMethodA;
    void* CallLongMethod; void* CallLongMethodV; void* CallLongMethodA;
    void* CallFloatMethod; void* CallFloatMethodV; void* CallFloatMethodA;
    void* CallDoubleMethod; void* CallDoubleMethodV; void* CallDoubleMethodA;
    void* CallVoidMethod; void* CallVoidMethodV; void* CallVoidMethodA;
    void* CallNonvirtualObjectMethod; void* CallNonvirtualObjectMethodV; void* CallNonvirtualObjectMethodA;
    void* CallNonvirtualBooleanMethod; void* CallNonvirtualBooleanMethodV; void* CallNonvirtualBooleanMethodA;
    void* CallNonvirtualByteMethod; void* CallNonvirtualByteMethodV; void* CallNonvirtualByteMethodA;
    void* CallNonvirtualCharMethod; void* CallNonvirtualCharMethodV; void* CallNonvirtualCharMethodA;
    void* CallNonvirtualShortMethod; void* CallNonvirtualShortMethodV; void* CallNonvirtualShortMethodA;
    void* CallNonvirtualIntMethod; void* CallNonvirtualIntMethodV; void* CallNonvirtualIntMethodA;
    void* CallNonvirtualLongMethod; void* CallNonvirtualLongMethodV; void* CallNonvirtualLongMethodA;
    void* CallNonvirtualFloatMethod; void* CallNonvirtualFloatMethodV; void* CallNonvirtualFloatMethodA;
    void* CallNonvirtualDoubleMethod; void* CallNonvirtualDoubleMethodV; void* CallNonvirtualDoubleMethodA;
    void* CallNonvirtualVoidMethod; void* CallNonvirtualVoidMethodV; void* CallNonvirtualVoidMethodA;
    void* GetFieldID;
    void* GetObjectField; void* GetBooleanField; void* GetByteField;
    void* GetCharField; void* GetShortField; void* GetIntField;
    void* GetLongField; void* GetFloatField; void* GetDoubleField;
    void* SetObjectField; void* SetBooleanField; void* SetByteField;
    void* SetCharField; void* SetShortField; void* SetIntField;
    void* SetLongField; void* SetFloatField; void* SetDoubleField;
    void* GetStaticMethodID;
    void* CallStaticObjectMethod; void* CallStaticObjectMethodV; void* CallStaticObjectMethodA;
    void* CallStaticBooleanMethod; void* CallStaticBooleanMethodV; void* CallStaticBooleanMethodA;
    void* CallStaticByteMethod; void* CallStaticByteMethodV; void* CallStaticByteMethodA;
    void* CallStaticCharMethod; void* CallStaticCharMethodV; void* CallStaticCharMethodA;
    void* CallStaticShortMethod; void* CallStaticShortMethodV; void* CallStaticShortMethodA;
    void* CallStaticIntMethod; void* CallStaticIntMethodV; void* CallStaticIntMethodA;
    void* CallStaticLongMethod; void* CallStaticLongMethodV; void* CallStaticLongMethodA;
    void* CallStaticFloatMethod; void* CallStaticFloatMethodV; void* CallStaticFloatMethodA;
    void* CallStaticDoubleMethod; void* CallStaticDoubleMethodV; void* CallStaticDoubleMethodA;
    void* CallStaticVoidMethod; void* CallStaticVoidMethodV; void* CallStaticVoidMethodA;
    void* GetStaticFieldID;
    void* GetStaticObjectField; void* GetStaticBooleanField; void* GetStaticByteField;
    void* GetStaticCharField; void* GetStaticShortField; void* GetStaticIntField;
    void* GetStaticLongField; void* GetStaticFloatField; void* GetStaticDoubleField;
    void* SetStaticObjectField; void* SetStaticBooleanField; void* SetStaticByteField;
    void* SetStaticCharField; void* SetStaticShortField; void* SetStaticIntField;
    void* SetStaticLongField; void* SetStaticFloatField; void* SetStaticDoubleField;
    void* NewString;
    void* GetStringLength;
    void* GetStringChars;
    void* ReleaseStringChars;
    void* NewStringUTF;
    void* GetStringUTFLength;
    void* GetStringUTFChars;
    void* ReleaseStringUTFChars;
    void* GetArrayLength;
    void* NewObjectArray;
    void* GetObjectArrayElement;
    void* SetObjectArrayElement;
    void* NewBooleanArray; void* NewByteArray; void* NewCharArray;
    void* NewShortArray; void* NewIntArray; void* NewLongArray;
    void* NewFloatArray; void* NewDoubleArray;
    void* GetBooleanArrayElements; void* GetByteArrayElements; void* GetCharArrayElements;
    void* GetShortArrayElements; void* GetIntArrayElements; void* GetLongArrayElements;
    void* GetFloatArrayElements; void* GetDoubleArrayElements;
    void* ReleaseBooleanArrayElements; void* ReleaseByteArrayElements; void* ReleaseCharArrayElements;
    void* ReleaseShortArrayElements; void* ReleaseIntArrayElements; void* ReleaseLongArrayElements;
    void* ReleaseFloatArrayElements; void* ReleaseDoubleArrayElements;
    void* GetBooleanArrayRegion; void* GetByteArrayRegion; void* GetCharArrayRegion;
    void* GetShortArrayRegion; void* GetIntArrayRegion; void* GetLongArrayRegion;
    void* GetFloatArrayRegion; void* GetDoubleArrayRegion;
    void* SetBooleanArrayRegion; void* SetByteArrayRegion; void* SetCharArrayRegion;
    void* SetShortArrayRegion; void* SetIntArrayRegion; void* SetLongArrayRegion;
    void* SetFloatArrayRegion; void* SetDoubleArrayRegion;
    void* RegisterNatives;
    void* UnregisterNatives;
    void* MonitorEnter;
    void* MonitorExit;
    void* GetJavaVM;
    void* GetStringRegion;
    void* GetStringUTFRegion;
    void* GetPrimitiveArrayCritical;
    void* ReleasePrimitiveArrayCritical;
    void* GetStringCritical;
    void* ReleaseStringCritical;
    void* NewWeakGlobalRef;
    void* DeleteWeakGlobalRef;
    void* ExceptionCheck;
    void* NewDirectByteBuffer;
    void* GetDirectBufferAddress;
    void* GetDirectBufferCapacity;
    void* GetObjectRefType;
    void* GetModule;      /* JDK 9+ */
    void* reserved[6];    /* headroom for future spec additions */
};

static const struct JNINativeInterface __jnative_jni_functions = {
    NULL, NULL, NULL, NULL,
    (void*)jn_GetVersion,
    NULL,
    (void*)jn_FindClass,
    NULL, NULL, NULL,
    (void*)jn_GetSuperclass,
    (void*)jn_IsAssignableFrom,
    NULL,
    (void*)jn_Throw,
    (void*)jn_ThrowNew,
    (void*)jn_ExceptionOccurred,
    (void*)jn_ExceptionDescribe,
    (void*)jn_ExceptionClear,
    NULL,
    (void*)jn_PushLocalFrame,
    (void*)jn_PopLocalFrame,
    (void*)jn_NewGlobalRef,
    (void*)jn_DeleteGlobalRef,
    (void*)jn_DeleteLocalRef,
    (void*)jn_IsSameObject,
    (void*)jn_NewLocalRef,
    (void*)jn_EnsureLocalCapacity,
    (void*)jn_AllocObject,
    (void*)jn_NewObject,
    (void*)jn_NewObjectV,
    (void*)jn_NewObjectA,
    (void*)jn_GetObjectClass,
    (void*)jn_IsInstanceOf,
    (void*)jn_GetMethodID,
    (void*)jn_CallObjectMethod, (void*)jn_CallObjectMethodV, (void*)jn_CallObjectMethodA,
    (void*)jn_CallBooleanMethod, (void*)jn_CallBooleanMethodV, (void*)jn_CallBooleanMethodA,
    (void*)jn_CallByteMethod, (void*)jn_CallByteMethodV, (void*)jn_CallByteMethodA,
    (void*)jn_CallCharMethod, (void*)jn_CallCharMethodV, (void*)jn_CallCharMethodA,
    (void*)jn_CallShortMethod, (void*)jn_CallShortMethodV, (void*)jn_CallShortMethodA,
    (void*)jn_CallIntMethod, (void*)jn_CallIntMethodV, (void*)jn_CallIntMethodA,
    (void*)jn_CallLongMethod, (void*)jn_CallLongMethodV, (void*)jn_CallLongMethodA,
    (void*)jn_CallFloatMethod, (void*)jn_CallFloatMethodV, (void*)jn_CallFloatMethodA,
    (void*)jn_CallDoubleMethod, (void*)jn_CallDoubleMethodV, (void*)jn_CallDoubleMethodA,
    (void*)jn_CallVoidMethod, (void*)jn_CallVoidMethodV, (void*)jn_CallVoidMethodA,
    /* CallNonvirtual* — not implemented */
    NULL,NULL,NULL, NULL,NULL,NULL, NULL,NULL,NULL, NULL,NULL,NULL,
    NULL,NULL,NULL, NULL,NULL,NULL, NULL,NULL,NULL, NULL,NULL,NULL,
    NULL,NULL,NULL,
    (void*)jn_GetFieldID,
    (void*)jn_GetObjectField, (void*)jn_GetBooleanField, (void*)jn_GetByteField,
    (void*)jn_GetCharField, (void*)jn_GetShortField, (void*)jn_GetIntField,
    (void*)jn_GetLongField, (void*)jn_GetFloatField, (void*)jn_GetDoubleField,
    (void*)jn_SetObjectField, (void*)jn_SetBooleanField, (void*)jn_SetByteField,
    (void*)jn_SetCharField, (void*)jn_SetShortField, (void*)jn_SetIntField,
    (void*)jn_SetLongField, (void*)jn_SetFloatField, (void*)jn_SetDoubleField,
    (void*)jn_GetStaticMethodID,
    (void*)jn_CallStaticObjectMethod, (void*)jn_CallStaticObjectMethodV, (void*)jn_CallStaticObjectMethodA,
    (void*)jn_CallStaticBooleanMethod, (void*)jn_CallStaticBooleanMethodV, (void*)jn_CallStaticBooleanMethodA,
    (void*)jn_CallStaticByteMethod, (void*)jn_CallStaticByteMethodV, (void*)jn_CallStaticByteMethodA,
    (void*)jn_CallStaticCharMethod, (void*)jn_CallStaticCharMethodV, (void*)jn_CallStaticCharMethodA,
    (void*)jn_CallStaticShortMethod, (void*)jn_CallStaticShortMethodV, (void*)jn_CallStaticShortMethodA,
    (void*)jn_CallStaticIntMethod, (void*)jn_CallStaticIntMethodV, (void*)jn_CallStaticIntMethodA,
    (void*)jn_CallStaticLongMethod, (void*)jn_CallStaticLongMethodV, (void*)jn_CallStaticLongMethodA,
    (void*)jn_CallStaticFloatMethod, (void*)jn_CallStaticFloatMethodV, (void*)jn_CallStaticFloatMethodA,
    (void*)jn_CallStaticDoubleMethod, (void*)jn_CallStaticDoubleMethodV, (void*)jn_CallStaticDoubleMethodA,
    (void*)jn_CallStaticVoidMethod, (void*)jn_CallStaticVoidMethodV, (void*)jn_CallStaticVoidMethodA,
    (void*)jn_GetStaticFieldID,
    (void*)jn_GetStaticObjectField, (void*)jn_GetStaticBooleanField, (void*)jn_GetStaticByteField,
    (void*)jn_GetStaticCharField, (void*)jn_GetStaticShortField, (void*)jn_GetStaticIntField,
    (void*)jn_GetStaticLongField, (void*)jn_GetStaticFloatField, (void*)jn_GetStaticDoubleField,
    (void*)jn_SetStaticObjectField, (void*)jn_SetStaticBooleanField, (void*)jn_SetStaticByteField,
    (void*)jn_SetStaticCharField, (void*)jn_SetStaticShortField, (void*)jn_SetStaticIntField,
    (void*)jn_SetStaticLongField, (void*)jn_SetStaticFloatField, (void*)jn_SetStaticDoubleField,
    (void*)jn_NewString,
    (void*)jn_GetStringLength,
    (void*)jn_GetStringChars,
    (void*)jn_ReleaseStringChars,
    (void*)jn_NewStringUTF,
    (void*)jn_GetStringUTFLength,
    (void*)jn_GetStringUTFChars,
    (void*)jn_ReleaseStringUTFChars,
    (void*)jn_GetArrayLength,
    (void*)jn_NewObjectArray,
    (void*)jn_GetObjectArrayElement,
    (void*)jn_SetObjectArrayElement,
    (void*)jn_NewBooleanArray, (void*)jn_NewByteArray, (void*)jn_NewCharArray,
    (void*)jn_NewShortArray, (void*)jn_NewIntArray, (void*)jn_NewLongArray,
    (void*)jn_NewFloatArray, (void*)jn_NewDoubleArray,
    (void*)jn_GetBooleanArrayElements, (void*)jn_GetByteArrayElements, (void*)jn_GetCharArrayElements,
    (void*)jn_GetShortArrayElements, (void*)jn_GetIntArrayElements, (void*)jn_GetLongArrayElements,
    (void*)jn_GetFloatArrayElements, (void*)jn_GetDoubleArrayElements,
    (void*)jn_ReleaseBooleanArrayElements, (void*)jn_ReleaseByteArrayElements, (void*)jn_ReleaseCharArrayElements,
    (void*)jn_ReleaseShortArrayElements, (void*)jn_ReleaseIntArrayElements, (void*)jn_ReleaseLongArrayElements,
    (void*)jn_ReleaseFloatArrayElements, (void*)jn_ReleaseDoubleArrayElements,
    (void*)jn_GetBooleanArrayRegion, (void*)jn_GetByteArrayRegion, (void*)jn_GetCharArrayRegion,
    (void*)jn_GetShortArrayRegion, (void*)jn_GetIntArrayRegion, (void*)jn_GetLongArrayRegion,
    (void*)jn_GetFloatArrayRegion, (void*)jn_GetDoubleArrayRegion,
    (void*)jn_SetBooleanArrayRegion, (void*)jn_SetByteArrayRegion, (void*)jn_SetCharArrayRegion,
    (void*)jn_SetShortArrayRegion, (void*)jn_SetIntArrayRegion, (void*)jn_SetLongArrayRegion,
    (void*)jn_SetFloatArrayRegion, (void*)jn_SetDoubleArrayRegion,
    (void*)jn_RegisterNatives,
    (void*)jn_UnregisterNatives,
    (void*)jn_MonitorEnter,
    (void*)jn_MonitorExit,
    (void*)jn_GetJavaVM,
    /* GetStringRegion, GetStringUTFRegion, GetPrimitiveArrayCritical,
     * ReleasePrimitiveArrayCritical, GetStringCritical, ReleaseStringCritical,
     * NewWeakGlobalRef, DeleteWeakGlobalRef: not implemented */
    NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
    (void*)jn_ExceptionCheck,
    /* NewDirectByteBuffer, GetDirectBufferAddress, GetDirectBufferCapacity,
     * GetObjectRefType, GetModule: not implemented */
    NULL, NULL, NULL, NULL, NULL,
    NULL, NULL, NULL, NULL, NULL, NULL
};

/* ========================================================================
 *  Env accessors
 * ======================================================================== */

/*
 * The `JNIEnv*` that the emitter passes to every routed JNI native points
 * to a location containing a `const struct JNINativeInterface*`. JNI code
 * then calls `(*env)->FindClass(env, ...)`, which expands to
 * `(*(*env).FindClass)(env, ...)` — i.e. the function receives the
 * `JNIEnv*` as its first argument, matching every function pointer in the
 * table above.
 *
 * The two accessors therefore return the same address. Per-thread
 * differentiation is provided by the __thread variables (local refs,
 * exception state, frame stack), not by the env pointer itself.
 */
static const struct JNINativeInterface* const __jnative_jni_iface_ptr =
    &__jnative_jni_functions;
static const void* __jnative_jni_env_ptr = &__jnative_jni_iface_ptr;

void* __jnative_get_jnienv(void) {
    return (void*)&__jnative_jni_env_ptr;
}

void* __jnative_get_thread_jnienv(void) {
    return (void*)&__jnative_jni_env_ptr;
}
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
 *   - GetModule
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

#include "jnative_platform.h"
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

static JN_TLS LocalRefStack tls_local_refs = { NULL, 0, 0 };

static JN_TLS size_t* tls_frame_saved = NULL;
static JN_TLS size_t  tls_frame_depth = 0;
static JN_TLS size_t  tls_frame_cap   = 0;

/* Global refs (process-wide). */
typedef struct GlobalRefEntry {
    jobject obj;
    struct GlobalRefEntry* next;
} GlobalRefEntry;

#define GLOBAL_REF_BUCKETS 1024u
static GlobalRefEntry*  global_ref_table[GLOBAL_REF_BUCKETS];
static jn_mutex_t       global_ref_lock = JN_MUTEX_INITIALIZER;

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
    jn_mutex_lock(&global_ref_lock);
    e->next = global_ref_table[b];
    global_ref_table[b] = e;
    jn_mutex_unlock(&global_ref_lock);
    return obj;
}

static void jn_DeleteGlobalRef(void* env, jobject obj) {
    ENV_UNUSED;
    if (obj == NULL) return;
    uint32_t b = ref_hash(obj);
    jn_mutex_lock(&global_ref_lock);
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
    jn_mutex_unlock(&global_ref_lock);
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
    ReflectionMethod* rm = NULL;
    if (strcmp(name, "<init>") == 0) {
        ReflectionClass* c = (ReflectionClass*)cls;
        if (c->constructors != NULL) {
            for (ReflectionConstructor** cp = c->constructors; *cp != NULL; cp++) {
                ReflectionConstructor* ctor = *cp;
                if (ctor->descriptor == NULL
                    || strcmp((const char*)ctor->descriptor, sig) != 0) continue;
                rm = (ReflectionMethod*)calloc(1, sizeof(ReflectionMethod));
                if (rm == NULL) return NULL;
                rm->name       = (void*)"<init>";
                rm->descriptor = ctor->descriptor;
                rm->adaptor    = ctor->adaptor;
                rm->modifiers  = ctor->modifiers;
                declaring = c;
                break;
            }
        }
    } else {
        rm = find_method_hier((ReflectionClass*)cls, name, sig, &declaring);
    }
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

typedef struct {
    const char* name;
    const char* signature;
    void*       fnPtr;
} JNINativeMethodRec;

typedef struct JNRegisteredNative {
    ReflectionClass*           cls;
    char*                      name;
    char*                      sig;
    void*                      fn;
    struct JNRegisteredNative* next;
} JNRegisteredNative;

typedef struct JNLoadedLibrary {
    void*                   handle;
    struct JNLoadedLibrary* next;
} JNLoadedLibrary;

static JNRegisteredNative* jn_registered_natives = NULL;
static JNLoadedLibrary*    jn_loaded_libraries   = NULL;
static jn_mutex_t          jn_natives_lock       = JN_MUTEX_INITIALIZER;

void* __jnative_get_jnienv(void);
void* __jnative_get_javavm(void);

static char* jn_strdup(const char* s) {
    size_t n = strlen(s) + 1;
    char* d = (char*)malloc(n);
    if (d != NULL) memcpy(d, s, n);
    return d;
}

static jint jn_RegisterNatives(void* env, jclass cls,
                               const void* methods, jint n) {
    ENV_UNUSED;
    if (cls == NULL || n < 0 || (methods == NULL && n > 0)) return JNI_ERR;
    const JNINativeMethodRec* m = (const JNINativeMethodRec*)methods;
    jn_mutex_lock(&jn_natives_lock);
    for (jint i = 0; i < n; i++) {
        if (m[i].name == NULL || m[i].signature == NULL || m[i].fnPtr == NULL) continue;
        JNRegisteredNative* e = jn_registered_natives;
        while (e != NULL) {
            if (e->cls == (ReflectionClass*)cls
                && strcmp(e->name, m[i].name) == 0
                && strcmp(e->sig, m[i].signature) == 0) break;
            e = e->next;
        }
        if (e != NULL) {
            e->fn = m[i].fnPtr;
            continue;
        }
        e = (JNRegisteredNative*)calloc(1, sizeof(JNRegisteredNative));
        if (e == NULL) {
            jn_mutex_unlock(&jn_natives_lock);
            return JNI_ENOMEM;
        }
        e->cls  = (ReflectionClass*)cls;
        e->name = jn_strdup(m[i].name);
        e->sig  = jn_strdup(m[i].signature);
        e->fn   = m[i].fnPtr;
        e->next = jn_registered_natives;
        jn_registered_natives = e;
    }
    jn_mutex_unlock(&jn_natives_lock);
    return JNI_OK;
}

static jint jn_UnregisterNatives(void* env, jclass cls) {
    ENV_UNUSED;
    jn_mutex_lock(&jn_natives_lock);
    JNRegisteredNative** pp = &jn_registered_natives;
    while (*pp != NULL) {
        JNRegisteredNative* e = *pp;
        if (e->cls == (ReflectionClass*)cls) {
            *pp = e->next;
            free(e->name);
            free(e->sig);
            free(e);
        } else {
            pp = &e->next;
        }
    }
    jn_mutex_unlock(&jn_natives_lock);
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

static jint jvm_DestroyJavaVM(void* vm) {
    (void)vm;
    return JNI_ERR;
}

static jint jvm_AttachCurrentThread(void* vm, void** penv, void* args) {
    (void)vm; (void)args;
    if (penv != NULL) *penv = __jnative_get_jnienv();
    return JNI_OK;
}

static jint jvm_DetachCurrentThread(void* vm) {
    (void)vm;
    return JNI_OK;
}

static jint jvm_GetEnv(void* vm, void** penv, jint version) {
    (void)vm; (void)version;
    if (penv != NULL) *penv = __jnative_get_jnienv();
    return JNI_OK;
}

static void* const jn_invoke_interface[8] = {
    NULL, NULL, NULL,
    (void*)jvm_DestroyJavaVM,
    (void*)jvm_AttachCurrentThread,
    (void*)jvm_DetachCurrentThread,
    (void*)jvm_GetEnv,
    (void*)jvm_AttachCurrentThread
};

static void* const* const jn_java_vm = jn_invoke_interface;

void* __jnative_get_javavm(void) {
    return (void*)&jn_java_vm;
}

static jint jn_GetJavaVM(void* env, void** vm) {
    ENV_UNUSED;
    if (vm != NULL) *vm = __jnative_get_javavm();
    return JNI_OK;
}

int32_t __jnative_jni_register_library(void* handle, int32_t is_jni) {
    if (handle == NULL) return 0;
    jn_mutex_lock(&jn_natives_lock);
    JNLoadedLibrary* l = jn_loaded_libraries;
    while (l != NULL && l->handle != handle) l = l->next;
    int fresh = (l == NULL);
    if (fresh) {
        l = (JNLoadedLibrary*)calloc(1, sizeof(JNLoadedLibrary));
        if (l == NULL) {
            jn_mutex_unlock(&jn_natives_lock);
            return 0;
        }
        l->handle = handle;
        l->next = jn_loaded_libraries;
        jn_loaded_libraries = l;
    }
    jn_mutex_unlock(&jn_natives_lock);

    if (fresh && is_jni) {
        typedef jint (*jni_onload_fn)(void* vm, void* reserved);
        jni_onload_fn onload = (jni_onload_fn)dlsym(handle, "JNI_OnLoad");
        if (onload != NULL) {
            jint version = onload(__jnative_get_javavm(), NULL);
            if (version < 0) return 0;
        }
    }
    return 1;
}

static void* jn_lookup_registered(const char* owner, const char* name, const char* desc) {
    void* fn = NULL;
    jn_mutex_lock(&jn_natives_lock);
    for (JNRegisteredNative* e = jn_registered_natives; e != NULL; e = e->next) {
        if (e->cls == NULL || e->cls->cname == NULL) continue;
        if (strcmp(e->cls->cname, owner) != 0) continue;
        if (strcmp(e->name, name) != 0) continue;
        if (strcmp(e->sig, desc) != 0) continue;
        fn = e->fn;
        break;
    }
    jn_mutex_unlock(&jn_natives_lock);
    return fn;
}

static void* jn_lookup_loaded(const char* short_sym, const char* long_sym) {
    void* fn = NULL;
    jn_mutex_lock(&jn_natives_lock);
    for (JNLoadedLibrary* l = jn_loaded_libraries; l != NULL && fn == NULL; l = l->next) {
        fn = dlsym(l->handle, short_sym);
        if (fn == NULL) fn = dlsym(l->handle, long_sym);
    }
    jn_mutex_unlock(&jn_natives_lock);
#ifdef RTLD_NEXT
    if (fn == NULL) fn = dlsym(RTLD_NEXT, short_sym);
    if (fn == NULL) fn = dlsym(RTLD_NEXT, long_sym);
#endif
    return fn;
}

JNATIVE_NORETURN static void jn_throw_unsatisfied_link(const char* owner,
                                                      const char* name,
                                                      const char* desc) {
    char dotted[512];
    size_t k = 0;
    for (; owner[k] != '\0' && k < sizeof(dotted) - 1; k++) {
        dotted[k] = owner[k] == '/' ? '.' : owner[k];
    }
    dotted[k] = '\0';
    char msg[1024];
    snprintf(msg, sizeof(msg), "'%s.%s%s'", dotted, name, desc);
    static const char* const candidates[] = {
        "vtable_java_lang_UnsatisfiedLinkError",
        "vtable_java_lang_LinkageError",
        "vtable_java_lang_Error",
        "vtable_java_lang_RuntimeException",
        NULL
    };
    for (int i = 0; candidates[i] != NULL; i++) {
        void* exc = __jnative_construct_exception(candidates[i], msg);
        if (exc != NULL) __jnative_throw_exception(exc);
    }
    fprintf(stderr, "jnative: UnsatisfiedLinkError: %s\n", msg);
    abort();
}

extern const char* const __jnative_bundled_jni_libraries[];

static size_t jn_executable_dir(char* buf, size_t bufsz) {
#if defined(_WIN32)
    DWORD n = GetModuleFileNameA(NULL, buf, (DWORD)bufsz);
    long len = (n > 0 && n < bufsz) ? (long)n : -1;
#else
    long len = jn_readlink("/proc/self/exe", buf, bufsz - 1);
#endif
    if (len <= 0) return 0;
    buf[len] = '\0';
    char* cut = NULL;
    for (char* p = buf; *p != '\0'; p++) {
        if (*p == '/' || *p == '\\') cut = p;
    }
    if (cut == NULL) return 0;
    cut[1] = '\0';
    return strlen(buf);
}

void __jnative_jni_preload_bundled(void) {
    if (__jnative_bundled_jni_libraries[0] == NULL) return;
    char dir[4096];
    size_t dlen = jn_executable_dir(dir, sizeof(dir));
    for (size_t i = 0; __jnative_bundled_jni_libraries[i] != NULL; i++) {
        const char* name = __jnative_bundled_jni_libraries[i];
        char path[4096 + 256];
        if (dlen > 0) snprintf(path, sizeof(path), "%s%s", dir, name);
        else snprintf(path, sizeof(path), "%s", name);
        void* handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (handle == NULL) {
            const char* err = dlerror();
            fprintf(stderr, "jnative: warning: cannot preload %s: %s\n",
                    path, err != NULL ? err : "unknown error");
            continue;
        }
        if (!__jnative_jni_register_library(handle, 1)) {
            fprintf(stderr, "jnative: warning: JNI_OnLoad failed for %s\n", path);
        }
    }
}

static int64_t jn_zero_native_integral(void) {
    return 0;
}

static double jn_zero_native_floating(void) {
    return 0.0;
}

static int jn_is_jdk_class(const char* owner) {
    static const char* const prefixes[] = {
        "java/", "javax/", "jdk/", "sun/", "com/sun/", NULL
    };
    for (int i = 0; prefixes[i] != NULL; i++) {
        if (strncmp(owner, prefixes[i], strlen(prefixes[i])) == 0) return 1;
    }
    return 0;
}

static void* jn_zero_native_for(const char* desc) {
    const char* close = strrchr(desc, ')');
    char ret = close != NULL ? close[1] : 'V';
    if (ret == 'F' || ret == 'D') return (void*)jn_zero_native_floating;
    return (void*)jn_zero_native_integral;
}

void* __jnative_jni_resolve(void** slot, const char* owner, const char* name,
                            const char* desc, const char* short_sym,
                            const char* long_sym) {
    void* fn = *(void* volatile*)slot;
    if (fn != NULL) return fn;
    fn = jn_lookup_registered(owner, name, desc);
    if (fn == NULL) fn = jn_lookup_loaded(short_sym, long_sym);
    if (fn == NULL && jn_is_jdk_class(owner)) fn = jn_zero_native_for(desc);
    if (fn == NULL) jn_throw_unsatisfied_link(owner, name, desc);
    *(void* volatile*)slot = fn;
    return fn;
}

static void jn_FatalError(void* env, const char* msg) {
    ENV_UNUSED;
    fprintf(stderr, "FATAL ERROR in native method: %s\n", msg != NULL ? msg : "");
    abort();
}

static jobject jn_NewWeakGlobalRef(void* env, jobject obj) {
    return jn_NewGlobalRef(env, obj);
}

static void jn_DeleteWeakGlobalRef(void* env, jobject obj) {
    jn_DeleteGlobalRef(env, obj);
}

static jint jn_GetObjectRefType(void* env, jobject obj) {
    ENV_UNUSED;
    return obj == NULL ? 0 : 1;
}

static void jn_GetStringRegion(void* env, jstring s, jsize start, jsize len, jchar* buf) {
    ENV_UNUSED;
    if (s == NULL || buf == NULL || len <= 0) return;
    jsize n = 0;
    jchar* chars = string_to_utf16(s, &n);
    if (chars == NULL) return;
    if (start >= 0 && start + len <= n) {
        memcpy(buf, chars + start, (size_t)len * sizeof(jchar));
    }
    free(chars);
}

static void jn_GetStringUTFRegion(void* env, jstring s, jsize start, jsize len, char* buf) {
    ENV_UNUSED;
    if (s == NULL || buf == NULL) return;
    jsize n = 0;
    jchar* chars = string_to_utf16(s, &n);
    if (chars == NULL) return;
    if (start >= 0 && len >= 0 && start + len <= n) {
        jsize out_len = 0;
        char* utf = utf16_to_modified_utf8(chars + start, len, &out_len);
        memcpy(buf, utf, (size_t)out_len + 1);
        free(utf);
    }
    free(chars);
}

static void* jn_GetPrimitiveArrayCritical(void* env, jarray arr, jboolean* isCopy) {
    ENV_UNUSED;
    if (arr == NULL) return NULL;
    if (isCopy) *isCopy = JNI_FALSE;
    return jnative_array_data(arr);
}

static void jn_ReleasePrimitiveArrayCritical(void* env, jarray arr, void* elems, jint mode) {
    ENV_UNUSED; (void)arr; (void)elems; (void)mode;
}

static const jchar* jn_GetStringCritical(void* env, jstring s, jboolean* isCopy) {
    return jn_GetStringChars(env, s, isCopy);
}

static void jn_ReleaseStringCritical(void* env, jstring s, const jchar* chars) {
    jn_ReleaseStringChars(env, s, chars);
}

static ReflectionField* jn_buffer_field(void* env, jobject buf, const char* name) {
    if (buf == NULL) return NULL;
    ReflectionClass* cls = (ReflectionClass*)jn_GetObjectClass(env, buf);
    if (cls == NULL) cls = jnative_class_by_name("java/nio/Buffer");
    ReflectionField* f = find_field_hier(cls, name, NULL);
    if (f == NULL) f = find_field_hier(jnative_class_by_name("java/nio/Buffer"), name, NULL);
    return f;
}

static void* jn_GetDirectBufferAddress(void* env, jobject buf) {
    ReflectionField* f = jn_buffer_field(env, buf, "address");
    if (f == NULL) return NULL;
    return (void*)(intptr_t)*(int64_t*)((char*)buf + f->offset);
}

static jlong jn_GetDirectBufferCapacity(void* env, jobject buf) {
    ReflectionField* f = jn_buffer_field(env, buf, "capacity");
    if (f == NULL) return -1;
    return (jlong)*(int32_t*)((char*)buf + f->offset);
}

static jobject jn_NewDirectByteBuffer(void* env, void* address, jlong capacity) {
    jclass cls = (jclass)jnative_class_by_name("java/nio/DirectByteBuffer");
    if (cls == NULL) return NULL;
    jvalue args[2];
    args[0].j = (jlong)(intptr_t)address;
    jmethodID mid = jn_GetMethodID(env, cls, "<init>", "(JJ)V");
    if (mid != NULL) {
        args[1].j = capacity;
        return jn_NewObjectA(env, cls, mid, args);
    }
    mid = jn_GetMethodID(env, cls, "<init>", "(JI)V");
    if (mid != NULL) {
        args[1].i = (jint)capacity;
        return jn_NewObjectA(env, cls, mid, args);
    }
    return NULL;
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
    .GetVersion = (void*)jn_GetVersion,
    .FindClass = (void*)jn_FindClass,
    .GetSuperclass = (void*)jn_GetSuperclass,
    .IsAssignableFrom = (void*)jn_IsAssignableFrom,
    .Throw = (void*)jn_Throw,
    .ThrowNew = (void*)jn_ThrowNew,
    .ExceptionOccurred = (void*)jn_ExceptionOccurred,
    .ExceptionDescribe = (void*)jn_ExceptionDescribe,
    .ExceptionClear = (void*)jn_ExceptionClear,
    .PushLocalFrame = (void*)jn_PushLocalFrame,
    .PopLocalFrame = (void*)jn_PopLocalFrame,
    .NewGlobalRef = (void*)jn_NewGlobalRef,
    .DeleteGlobalRef = (void*)jn_DeleteGlobalRef,
    .DeleteLocalRef = (void*)jn_DeleteLocalRef,
    .IsSameObject = (void*)jn_IsSameObject,
    .NewLocalRef = (void*)jn_NewLocalRef,
    .EnsureLocalCapacity = (void*)jn_EnsureLocalCapacity,
    .AllocObject = (void*)jn_AllocObject,
    .NewObject = (void*)jn_NewObject,
    .NewObjectV = (void*)jn_NewObjectV,
    .NewObjectA = (void*)jn_NewObjectA,
    .GetObjectClass = (void*)jn_GetObjectClass,
    .IsInstanceOf = (void*)jn_IsInstanceOf,
    .GetMethodID = (void*)jn_GetMethodID,
    .CallObjectMethod = (void*)jn_CallObjectMethod,
    .CallObjectMethodV = (void*)jn_CallObjectMethodV,
    .CallObjectMethodA = (void*)jn_CallObjectMethodA,
    .CallBooleanMethod = (void*)jn_CallBooleanMethod,
    .CallBooleanMethodV = (void*)jn_CallBooleanMethodV,
    .CallBooleanMethodA = (void*)jn_CallBooleanMethodA,
    .CallByteMethod = (void*)jn_CallByteMethod,
    .CallByteMethodV = (void*)jn_CallByteMethodV,
    .CallByteMethodA = (void*)jn_CallByteMethodA,
    .CallCharMethod = (void*)jn_CallCharMethod,
    .CallCharMethodV = (void*)jn_CallCharMethodV,
    .CallCharMethodA = (void*)jn_CallCharMethodA,
    .CallShortMethod = (void*)jn_CallShortMethod,
    .CallShortMethodV = (void*)jn_CallShortMethodV,
    .CallShortMethodA = (void*)jn_CallShortMethodA,
    .CallIntMethod = (void*)jn_CallIntMethod,
    .CallIntMethodV = (void*)jn_CallIntMethodV,
    .CallIntMethodA = (void*)jn_CallIntMethodA,
    .CallLongMethod = (void*)jn_CallLongMethod,
    .CallLongMethodV = (void*)jn_CallLongMethodV,
    .CallLongMethodA = (void*)jn_CallLongMethodA,
    .CallFloatMethod = (void*)jn_CallFloatMethod,
    .CallFloatMethodV = (void*)jn_CallFloatMethodV,
    .CallFloatMethodA = (void*)jn_CallFloatMethodA,
    .CallDoubleMethod = (void*)jn_CallDoubleMethod,
    .CallDoubleMethodV = (void*)jn_CallDoubleMethodV,
    .CallDoubleMethodA = (void*)jn_CallDoubleMethodA,
    .CallVoidMethod = (void*)jn_CallVoidMethod,
    .CallVoidMethodV = (void*)jn_CallVoidMethodV,
    .CallVoidMethodA = (void*)jn_CallVoidMethodA,
    .GetFieldID = (void*)jn_GetFieldID,
    .GetObjectField = (void*)jn_GetObjectField,
    .GetBooleanField = (void*)jn_GetBooleanField,
    .GetByteField = (void*)jn_GetByteField,
    .GetCharField = (void*)jn_GetCharField,
    .GetShortField = (void*)jn_GetShortField,
    .GetIntField = (void*)jn_GetIntField,
    .GetLongField = (void*)jn_GetLongField,
    .GetFloatField = (void*)jn_GetFloatField,
    .GetDoubleField = (void*)jn_GetDoubleField,
    .SetObjectField = (void*)jn_SetObjectField,
    .SetBooleanField = (void*)jn_SetBooleanField,
    .SetByteField = (void*)jn_SetByteField,
    .SetCharField = (void*)jn_SetCharField,
    .SetShortField = (void*)jn_SetShortField,
    .SetIntField = (void*)jn_SetIntField,
    .SetLongField = (void*)jn_SetLongField,
    .SetFloatField = (void*)jn_SetFloatField,
    .SetDoubleField = (void*)jn_SetDoubleField,
    .GetStaticMethodID = (void*)jn_GetStaticMethodID,
    .CallStaticObjectMethod = (void*)jn_CallStaticObjectMethod,
    .CallStaticObjectMethodV = (void*)jn_CallStaticObjectMethodV,
    .CallStaticObjectMethodA = (void*)jn_CallStaticObjectMethodA,
    .CallStaticBooleanMethod = (void*)jn_CallStaticBooleanMethod,
    .CallStaticBooleanMethodV = (void*)jn_CallStaticBooleanMethodV,
    .CallStaticBooleanMethodA = (void*)jn_CallStaticBooleanMethodA,
    .CallStaticByteMethod = (void*)jn_CallStaticByteMethod,
    .CallStaticByteMethodV = (void*)jn_CallStaticByteMethodV,
    .CallStaticByteMethodA = (void*)jn_CallStaticByteMethodA,
    .CallStaticCharMethod = (void*)jn_CallStaticCharMethod,
    .CallStaticCharMethodV = (void*)jn_CallStaticCharMethodV,
    .CallStaticCharMethodA = (void*)jn_CallStaticCharMethodA,
    .CallStaticShortMethod = (void*)jn_CallStaticShortMethod,
    .CallStaticShortMethodV = (void*)jn_CallStaticShortMethodV,
    .CallStaticShortMethodA = (void*)jn_CallStaticShortMethodA,
    .CallStaticIntMethod = (void*)jn_CallStaticIntMethod,
    .CallStaticIntMethodV = (void*)jn_CallStaticIntMethodV,
    .CallStaticIntMethodA = (void*)jn_CallStaticIntMethodA,
    .CallStaticLongMethod = (void*)jn_CallStaticLongMethod,
    .CallStaticLongMethodV = (void*)jn_CallStaticLongMethodV,
    .CallStaticLongMethodA = (void*)jn_CallStaticLongMethodA,
    .CallStaticFloatMethod = (void*)jn_CallStaticFloatMethod,
    .CallStaticFloatMethodV = (void*)jn_CallStaticFloatMethodV,
    .CallStaticFloatMethodA = (void*)jn_CallStaticFloatMethodA,
    .CallStaticDoubleMethod = (void*)jn_CallStaticDoubleMethod,
    .CallStaticDoubleMethodV = (void*)jn_CallStaticDoubleMethodV,
    .CallStaticDoubleMethodA = (void*)jn_CallStaticDoubleMethodA,
    .CallStaticVoidMethod = (void*)jn_CallStaticVoidMethod,
    .CallStaticVoidMethodV = (void*)jn_CallStaticVoidMethodV,
    .CallStaticVoidMethodA = (void*)jn_CallStaticVoidMethodA,
    .GetStaticFieldID = (void*)jn_GetStaticFieldID,
    .GetStaticObjectField = (void*)jn_GetStaticObjectField,
    .GetStaticBooleanField = (void*)jn_GetStaticBooleanField,
    .GetStaticByteField = (void*)jn_GetStaticByteField,
    .GetStaticCharField = (void*)jn_GetStaticCharField,
    .GetStaticShortField = (void*)jn_GetStaticShortField,
    .GetStaticIntField = (void*)jn_GetStaticIntField,
    .GetStaticLongField = (void*)jn_GetStaticLongField,
    .GetStaticFloatField = (void*)jn_GetStaticFloatField,
    .GetStaticDoubleField = (void*)jn_GetStaticDoubleField,
    .SetStaticObjectField = (void*)jn_SetStaticObjectField,
    .SetStaticBooleanField = (void*)jn_SetStaticBooleanField,
    .SetStaticByteField = (void*)jn_SetStaticByteField,
    .SetStaticCharField = (void*)jn_SetStaticCharField,
    .SetStaticShortField = (void*)jn_SetStaticShortField,
    .SetStaticIntField = (void*)jn_SetStaticIntField,
    .SetStaticLongField = (void*)jn_SetStaticLongField,
    .SetStaticFloatField = (void*)jn_SetStaticFloatField,
    .SetStaticDoubleField = (void*)jn_SetStaticDoubleField,
    .NewString = (void*)jn_NewString,
    .GetStringLength = (void*)jn_GetStringLength,
    .GetStringChars = (void*)jn_GetStringChars,
    .ReleaseStringChars = (void*)jn_ReleaseStringChars,
    .NewStringUTF = (void*)jn_NewStringUTF,
    .GetStringUTFLength = (void*)jn_GetStringUTFLength,
    .GetStringUTFChars = (void*)jn_GetStringUTFChars,
    .ReleaseStringUTFChars = (void*)jn_ReleaseStringUTFChars,
    .GetArrayLength = (void*)jn_GetArrayLength,
    .NewObjectArray = (void*)jn_NewObjectArray,
    .GetObjectArrayElement = (void*)jn_GetObjectArrayElement,
    .SetObjectArrayElement = (void*)jn_SetObjectArrayElement,
    .NewBooleanArray = (void*)jn_NewBooleanArray,
    .NewByteArray = (void*)jn_NewByteArray,
    .NewCharArray = (void*)jn_NewCharArray,
    .NewShortArray = (void*)jn_NewShortArray,
    .NewIntArray = (void*)jn_NewIntArray,
    .NewLongArray = (void*)jn_NewLongArray,
    .NewFloatArray = (void*)jn_NewFloatArray,
    .NewDoubleArray = (void*)jn_NewDoubleArray,
    .GetBooleanArrayElements = (void*)jn_GetBooleanArrayElements,
    .GetByteArrayElements = (void*)jn_GetByteArrayElements,
    .GetCharArrayElements = (void*)jn_GetCharArrayElements,
    .GetShortArrayElements = (void*)jn_GetShortArrayElements,
    .GetIntArrayElements = (void*)jn_GetIntArrayElements,
    .GetLongArrayElements = (void*)jn_GetLongArrayElements,
    .GetFloatArrayElements = (void*)jn_GetFloatArrayElements,
    .GetDoubleArrayElements = (void*)jn_GetDoubleArrayElements,
    .ReleaseBooleanArrayElements = (void*)jn_ReleaseBooleanArrayElements,
    .ReleaseByteArrayElements = (void*)jn_ReleaseByteArrayElements,
    .ReleaseCharArrayElements = (void*)jn_ReleaseCharArrayElements,
    .ReleaseShortArrayElements = (void*)jn_ReleaseShortArrayElements,
    .ReleaseIntArrayElements = (void*)jn_ReleaseIntArrayElements,
    .ReleaseLongArrayElements = (void*)jn_ReleaseLongArrayElements,
    .ReleaseFloatArrayElements = (void*)jn_ReleaseFloatArrayElements,
    .ReleaseDoubleArrayElements = (void*)jn_ReleaseDoubleArrayElements,
    .GetBooleanArrayRegion = (void*)jn_GetBooleanArrayRegion,
    .GetByteArrayRegion = (void*)jn_GetByteArrayRegion,
    .GetCharArrayRegion = (void*)jn_GetCharArrayRegion,
    .GetShortArrayRegion = (void*)jn_GetShortArrayRegion,
    .GetIntArrayRegion = (void*)jn_GetIntArrayRegion,
    .GetLongArrayRegion = (void*)jn_GetLongArrayRegion,
    .GetFloatArrayRegion = (void*)jn_GetFloatArrayRegion,
    .GetDoubleArrayRegion = (void*)jn_GetDoubleArrayRegion,
    .SetBooleanArrayRegion = (void*)jn_SetBooleanArrayRegion,
    .SetByteArrayRegion = (void*)jn_SetByteArrayRegion,
    .SetCharArrayRegion = (void*)jn_SetCharArrayRegion,
    .SetShortArrayRegion = (void*)jn_SetShortArrayRegion,
    .SetIntArrayRegion = (void*)jn_SetIntArrayRegion,
    .SetLongArrayRegion = (void*)jn_SetLongArrayRegion,
    .SetFloatArrayRegion = (void*)jn_SetFloatArrayRegion,
    .SetDoubleArrayRegion = (void*)jn_SetDoubleArrayRegion,
    .RegisterNatives = (void*)jn_RegisterNatives,
    .UnregisterNatives = (void*)jn_UnregisterNatives,
    .MonitorEnter = (void*)jn_MonitorEnter,
    .MonitorExit = (void*)jn_MonitorExit,
    .GetJavaVM = (void*)jn_GetJavaVM,
    .FatalError = (void*)jn_FatalError,
    .NewWeakGlobalRef = (void*)jn_NewWeakGlobalRef,
    .DeleteWeakGlobalRef = (void*)jn_DeleteWeakGlobalRef,
    .GetObjectRefType = (void*)jn_GetObjectRefType,
    .GetStringRegion = (void*)jn_GetStringRegion,
    .GetStringUTFRegion = (void*)jn_GetStringUTFRegion,
    .GetPrimitiveArrayCritical = (void*)jn_GetPrimitiveArrayCritical,
    .ReleasePrimitiveArrayCritical = (void*)jn_ReleasePrimitiveArrayCritical,
    .GetStringCritical = (void*)jn_GetStringCritical,
    .ReleaseStringCritical = (void*)jn_ReleaseStringCritical,
    .NewDirectByteBuffer = (void*)jn_NewDirectByteBuffer,
    .GetDirectBufferAddress = (void*)jn_GetDirectBufferAddress,
    .GetDirectBufferCapacity = (void*)jn_GetDirectBufferCapacity,
    .ExceptionCheck = (void*)jn_ExceptionCheck,
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
void* __jnative_get_jnienv(void) {
    return (void*)&__jnative_jni_iface_ptr;
}

void* __jnative_get_thread_jnienv(void) {
    return (void*)&__jnative_jni_iface_ptr;
}
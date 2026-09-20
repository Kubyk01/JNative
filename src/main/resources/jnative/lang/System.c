#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
#include <time.h>

__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);
__attribute__((noreturn)) void __jnative_throw_array_index_out_of_bounds(void);
__attribute__((noreturn)) void __jnative_throw_exception(void* exc);

extern void* __jnative_make_string_obj(const char* bytes, int32_t len);
extern const char* __jnative_read_string_bytes(void* s, int32_t* out_len);

#define ARRAY_HEADER_SIZE 8

static inline int32_t array_length(void* arr) {
    if (arr == NULL) return -1;
    return *(int32_t*)arr;
}

static inline int32_t array_elem_size(void* arr) {
    if (arr == NULL) return -1;
    return *(int32_t*)((char*)arr + 4);
}

static inline void* array_data(void* arr) {
    return (char*)arr + ARRAY_HEADER_SIZE;
}

void __jnative_fn_java_lang_System_arraycopy__Ljava_lang_Object_ILjava_lang_Object_II_V(
    void* src, int32_t srcPos, void* dest, int32_t destPos, int32_t length)
{
    if (src == NULL || dest == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    if (length < 0) {
        __jnative_throw_array_index_out_of_bounds();
        return;
    }

    if (srcPos < 0 || destPos < 0) {
        __jnative_throw_array_index_out_of_bounds();
        return;
    }

    int32_t srcLen = array_length(src);
    int32_t dstLen = array_length(dest);
    if (srcLen < 0 || dstLen < 0) {
        __jnative_throw_array_index_out_of_bounds();
        return;
    }

    if (srcPos + length > srcLen || destPos + length > dstLen) {
        __jnative_throw_array_index_out_of_bounds();
        return;
    }

    int32_t srcElemSize = array_elem_size(src);
    int32_t dstElemSize = array_elem_size(dest);

    if (srcElemSize != dstElemSize) {
        __jnative_throw_exception(NULL);
        return;
    }

    char* srcPtr = (char*)array_data(src) + (size_t)srcPos * (size_t)srcElemSize;
    char* dstPtr = (char*)array_data(dest) + (size_t)destPos * (size_t)dstElemSize;
    size_t bytes = (size_t)length * (size_t)srcElemSize;

    memmove(dstPtr, srcPtr, bytes);
}

int32_t __jnative_fn_java_lang_System_identityHashCode__Ljava_lang_Object__I(void* obj) {
    if (obj == NULL) return 0;
    return (int32_t)((uintptr_t)obj);
}

int64_t __jnative_fn_java_lang_System_currentTimeMillis___J(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_REALTIME, &ts) != 0) {
        return (int64_t)0;
    }
    int64_t millis = (int64_t)ts.tv_sec * 1000LL + (int64_t)(ts.tv_nsec / 1000000LL);
    return millis;
}

extern void* gv_java_lang_System_in  __attribute__((weak));
extern void* gv_java_lang_System_out __attribute__((weak));
extern void* gv_java_lang_System_err __attribute__((weak));

void __jnative_fn_java_lang_System_setIn0__Ljava_io_InputStream__V(void* in) {
    if (&gv_java_lang_System_in != NULL) {
        gv_java_lang_System_in = in;
    }
}

void __jnative_fn_java_lang_System_setOut0__Ljava_io_PrintStream__V(void* out) {
    if (&gv_java_lang_System_out != NULL) {
        gv_java_lang_System_out = out;
    }
}

void __jnative_fn_java_lang_System_setErr0__Ljava_io_PrintStream__V(void* err) {
    if (&gv_java_lang_System_err != NULL) {
        gv_java_lang_System_err = err;
    }
}

void __jnative_fn_java_lang_System_initProperties__Ljava_util_Properties_(void* props) {
    (void)props;
}

/* --------------------------------------------------------------------------
 * private static native String mapLibraryName(String libname);
 *
 * The platform-specific translation from a Java library name (e.g.
 * "net", "awt", "zip") to the file name the OS loader expects. On
 * Linux and other ELF platforms the rule is: prepend "lib" and append
 * ".so", yielding "libnet.so", "libawt.so", "libzip.so". On macOS the
 * suffix is ".dylib"; on Windows the prefix is empty and the suffix is
 * ".dll".
 *
 * This runtime never actually loads a separate shared object — every
 * native library's C implementation is compiled and linked into the
 * process image (see Analyzer.compileAndLink and the findBuiltinLib
 * implementation in NativeLibraries.c). But mapLibraryName is still
 * reached from ClassLoader.loadLibrary and from the reflective
 * System.loadLibrary path, where the mapped name is used to build
 * diagnostic messages and to check whether the library is already
 * loaded. Returning the correct platform name keeps those code paths
 * behaving exactly as they would under HotSpot, which is important for
 * error messages that user code might match on.
 *
 * The mapping is purely textual: no file-system lookup, no
 * dlopen, no version-suffix resolution. Java's own System.loadLibrary
 * already calls mapLibraryName before handing the result to the
 * platform loader, so the contract is just "given a bare name, produce
 * the canonical file name for this platform".
 *
 * A NULL argument raises NullPointerException, matching the reference
 * VM: System.mapLibraryName(null) is specified to throw NPE, and the
 * Java-side callers do not pre-check for null.
 * ------------------------------------------------------------------------ */
void* __jnative_fn_java_lang_System_mapLibraryName__Ljava_lang_String__Ljava_lang_String_(
        void* libname_str)
{
    if (libname_str == NULL) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }

    int32_t name_len = 0;
    const char* name = __jnative_read_string_bytes(libname_str, &name_len);
    if (name == NULL) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }

    /* Pick the platform prefix/suffix pair at compile time. The
     * preprocessor cannot see the running OS, but the compiler can:
     * the target triple is fixed when this runtime is built. */
#if defined(_WIN32)
    const char* prefix = "";
    const char* suffix = ".dll";
#elif defined(__APPLE__)
    const char* prefix = "lib";
    const char* suffix = ".dylib";
#else
    /* Linux, FreeBSD, and every other ELF/Unix target. */
    const char* prefix = "lib";
    const char* suffix = ".so";
#endif

    size_t prefix_len = strlen(prefix);
    size_t suffix_len = strlen(suffix);
    size_t total = prefix_len + (size_t)name_len + suffix_len;

    char* buf = (char*)malloc(total + 1);
    if (buf == NULL) {
        __jnative_throw_exception(NULL);
        return NULL;
    }

    memcpy(buf, prefix, prefix_len);
    memcpy(buf + prefix_len, name, (size_t)name_len);
    memcpy(buf + prefix_len + (size_t)name_len, suffix, suffix_len);
    buf[total] = '\0';

    void* result = __jnative_make_string_obj(buf, (int32_t)total);
    free(buf);
    return result;
}

/*
 * private static native void registerNatives();
 *
 * Called from System.<clinit> to bind the class's native methods
 * (arraycopy, identityHashCode, currentTimeMillis, nanoTime,
 * setIn0/setOut0/setErr0, initProperties, mapLibraryName, ...) to
 * their C implementations inside the JVM. This runtime does not use
 * JNI registration: every native method has a statically-linked
 * __jnative_fn_<class>_<method>_<desc> symbol emitted by the LLVM
 * backend, and call sites resolve to it directly. The symbol must
 * exist because System.<clinit> emits a native call to it.
 */
void __jnative_fn_java_lang_System_registerNatives___V(void) {
}
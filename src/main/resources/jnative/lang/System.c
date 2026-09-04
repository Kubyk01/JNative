/* path: src/main/resources/jnative/lang/System.c */

#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>

// ---------------------------------------------------------------------------
// Runtime exceptions
// ---------------------------------------------------------------------------
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);
__attribute__((noreturn)) void __jnative_throw_array_index_out_of_bounds(void);
__attribute__((noreturn)) void __jnative_throw_exception(void* exc);

// ---------------------------------------------------------------------------
// Array layout: [length (4 bytes)] [elem_size (4 bytes)] [data]
// This matches the generation in LlvmFunctionEmitter (NEW_ARRAY / MULTI_NEW_ARRAY)
// ---------------------------------------------------------------------------
#define ARRAY_HEADER_SIZE 8

static inline int array_length(void* arr) {
    if (!arr) return -1;
    return *(int*)arr;
}

static inline int array_elem_size(void* arr) {
    if (!arr) return -1;
    return *(int*)((char*)arr + 4);
}

static inline void* array_data(void* arr) {
    return (char*)arr + ARRAY_HEADER_SIZE;
}

// ---------------------------------------------------------------------------
// public static native void arraycopy(Object src, int srcPos, Object dest,
//                                     int destPos, int length)
// ---------------------------------------------------------------------------
void __jnative_fn_java_lang_System_arraycopy__Ljava_lang_Object_ILjava_lang_Object_II_V(
    void* src, int srcPos, void* dest, int destPos, int length)
{
    // Null checks
    if (src == NULL || dest == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    // Negative length is not allowed
    if (length < 0) {
        __jnative_throw_array_index_out_of_bounds();
        return;
    }

    // Negative positions not allowed
    if (srcPos < 0 || destPos < 0) {
        __jnative_throw_array_index_out_of_bounds();
        return;
    }

    // Get array lengths and element sizes
    int srcLen = array_length(src);
    int dstLen = array_length(dest);
    if (srcLen < 0 || dstLen < 0) {
        // Not a valid array object – treat as error
        __jnative_throw_array_index_out_of_bounds();
        return;
    }

    // Check bounds
    if (srcPos + length > srcLen || destPos + length > dstLen) {
        __jnative_throw_array_index_out_of_bounds();
        return;
    }

    int srcElemSize = array_elem_size(src);
    int dstElemSize = array_elem_size(dest);

    // For primitive arrays, element sizes must match exactly.
    // For object arrays (elem size = sizeof(void*)), we allow copying even
    // if the destination is a superclass array (no runtime type check here).
    // In a full implementation we would check assignability, but we skip it
    // for simplicity – Java would throw ArrayStoreException if types mismatch.
    if (srcElemSize != dstElemSize) {
        // Incompatible types: throw ArrayStoreException (generic)
        __jnative_throw_exception(NULL);
        return;
    }

    // Perform the copy (safe for overlapping regions)
    char* srcPtr = (char*)array_data(src) + srcPos * srcElemSize;
    char* dstPtr = (char*)array_data(dest) + destPos * dstElemSize;
    size_t bytes = (size_t)length * srcElemSize;
    memmove(dstPtr, srcPtr, bytes);
}

// ---------------------------------------------------------------------------
// public static native int identityHashCode(Object x)
// ---------------------------------------------------------------------------
int __jnative_fn_java_lang_System_identityHashCode__Ljava_lang_Object__I(void* obj) {
    if (obj == NULL) return 0;
    // Use the object's address as a simple hash (like OpenJDK does in many cases)
    return (int)((uintptr_t)obj);
}

// ---------------------------------------------------------------------------
// Static fields for System.in, System.out, System.err
// ---------------------------------------------------------------------------
static void* system_in  = NULL;
static void* system_out = NULL;
static void* system_err = NULL;

void __jnative_fn_java_lang_System_setIn0__Ljava_io_InputStream_(void* in) {
    system_in = in;
}

void __jnative_fn_java_lang_System_setOut0__Ljava_io_PrintStream_(void* out) {
    system_out = out;
}

void __jnative_fn_java_lang_System_setErr0__Ljava_io_PrintStream_(void* err) {
    system_err = err;
}

// ---------------------------------------------------------------------------
// private static native void initProperties(Properties props)
// Stub – properties are usually read from the system, but we don't need them
// for a minimal runtime.
// ---------------------------------------------------------------------------
void __jnative_fn_java_lang_System_initProperties__Ljava_util_Properties_(void* props) {
    // No‑op
}
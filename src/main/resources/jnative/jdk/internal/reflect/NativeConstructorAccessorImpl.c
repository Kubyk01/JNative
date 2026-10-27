/*
 * jdk.internal.reflect.NativeConstructorAccessorImpl — forwarding shim.
 *
 * The class declares a single native:
 *
 *     private native Object newInstance0(Constructor<T> c, Object[] args);
 *
 * which is invoked by Constructor.newInstance when the reflection
 * machinery has selected this accessor (or, on newer JDKs, when a
 * DirectConstructorHandleAccessor delegates through it).
 *
 * ---------------------------------------------------------------------
 * Why this shim exists
 * ---------------------------------------------------------------------
 *
 * The real body lives in NativeMethodAccessorImpl.c alongside
 * NativeMethodAccessorImpl.invoke0, because the two share all of the
 * descriptor/lookup helpers:
 *
 *   - build_parameter_list
 *   - build_method_descriptor     (invoke0 only)
 *   - build_constructor_descriptor (newInstance0 only)
 *   - lookup_reflection_method    (invoke0 only)
 *   - lookup_reflection_constructor (newInstance0 only)
 *   - append_class_descriptor
 *
 * The reflect-mirror offsets they read (JNATIVE_METHOD_*_OFFSET,
 * JNATIVE_CTOR_*_OFFSET) are compile-time constants emitted into
 * _module.ll, so no per-JDK layout detection is needed any more.
 *
 * Splitting the helper code across two files would mean duplicating
 * ~150 lines of layout code, which is worse than this thin shim.
 *
 * ---------------------------------------------------------------------
 * Why the mangled symbol is defined here and not in
 * NativeMethodAccessorImpl.c
 * ---------------------------------------------------------------------
 *
 * BytecodeToIr.findJNativeFileOwner and Compiler.hasNativeSupport both
 * decide whether a native method has a JNative C implementation by
 * probing for a C file whose name matches the declaring class:
 *
 *     jnative/<package>/<SimpleClassName>.c
 *
 * NativeConstructorAccessorImpl.newInstance0 has its body in
 * NativeMethodAccessorImpl.c, a file named after a *sibling* class, so
 * the probe missed it, BytecodeToIr fell back to JNI symbol naming
 * (Java_jdk_internal_reflect_NativeConstructorAccessorImpl_newInstance0),
 * and the compiler's JNI-native discovery found no C file to satisfy
 * it, so the link step failed with:
 *
 *     ld.lld: error: undefined symbol:
 *         Java_jdk_internal_reflect_NativeConstructorAccessorImpl_newInstance0
 *
 * Keeping the real implementation in NativeMethodAccessorImpl.c (so it
 * can share the layout helpers) and declaring the mangled symbol here
 * — where the probe expects to find it — satisfies the probe with a
 * one-function stub. The mangled symbol is declared exactly once in
 * this file; NativeMethodAccessorImpl.c no longer defines it.
 *
 * The forwarding chain therefore becomes:
 *
 *     DirectConstructorHandleAccessor.NativeAccessor.newInstance0 (JNI-free)
 *         -> __jnative_fn_jdk_internal_reflect_
 *                NativeConstructorAccessorImpl_newInstance0__…       (this file)
 *             -> __jnative_impl_reflect_
 *                    NativeConstructorAccessorImpl_newInstance0__     (NativeMethodAccessorImpl.c)
 *                 -> shared descriptor/lookup helpers
 *                     -> ReflectionConstructor.adaptor(args)
 *
 * Each layer is a direct C call. No dlsym, no JNI registration, no
 * per-JDK version branch. The delegation works identically regardless
 * of which JDK generation emitted the original call site.
 */

#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>

#include "jnative_runtime.h"

/*
 * The renamed implementation lives in NativeMethodAccessorImpl.c. It
 * is not declared in jnative_runtime.h because nothing outside the
 * reflect package needs it; the declaration is local to this file's
 * translation unit and to NativeMethodAccessorImpl.c's own definition.
 */
extern void* __jnative_impl_reflect_NativeConstructorAccessorImpl_newInstance0__(
        void* constructor_obj, void* args_array);

void* __jnative_fn_jdk_internal_reflect_NativeConstructorAccessorImpl_newInstance0__Ljava_lang_reflect_Constructor__Ljava_lang_Object__Ljava_lang_Object_(
        void* constructor_obj, void* args_array)
{
    return __jnative_impl_reflect_NativeConstructorAccessorImpl_newInstance0__(
            constructor_obj, args_array);
}
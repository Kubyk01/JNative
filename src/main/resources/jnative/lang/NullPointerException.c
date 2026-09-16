#define _GNU_SOURCE
#include <stddef.h>
#include <stdint.h>

/*
 * java.lang.NullPointerException native methods.
 *
 * In HotSpot, `getExtendedNPEMessage` walks the bytecode of the method that
 * threw the NPE to reconstruct a detailed message such as
 * "Cannot invoke \"String.length()\" because \"<local1>\" is null". The
 * method is `private native String getExtendedNPEMessage()` and is guarded
 * by `-XX:+ShowCodeDetailsInExceptionMessages` (default on since JDK 15).
 *
 * This runtime throws NPEs through the __jnative_throw_null_pointer_exception*
 * helpers (see jnative_runtime.c) and never materialises an NPE object with
 * a `backtrace` field. There is therefore nothing to walk. Returning null is
 * the documented contract for "no detailed message available": the Java
 * layer in Throwable.getExtendedNPEMessage() checks for null and returns
 * the plain detail message instead.
 *
 * See https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/lang/NullPointerException.java
 */
void* __jnative_fn_java_lang_NullPointerException_getExtendedNPEMessage___Ljava_lang_String_(
        void* this_npe) {
    (void)this_npe;
    return NULL;
}
#include <stdint.h>
#include <stdlib.h>

/*
 * MethodHandle polymorphic entry points.
 *
 * The polymorphic dispatcher in LlvmFunctionEmitter passes the call-site
 * arguments to the native implementation, excluding the MethodHandle
 * receiver. For the descriptor shape (Ljava/lang/Object;)Ljava/lang/Object;
 * the runtime therefore receives exactly one Object — the invocation
 * argument — and no reference to the MethodHandle instance.
 *
 * MethodHandlePolyArg is a typedef that ParserC maps to Ljava/lang/Object;.
 * The name deliberately does not contain the substrings "void" and "*" that
 * would otherwise mark the parameter as the generic void** wrapper and
 * collapse the descriptor to ()Ljava/lang/Object;.
 */

typedef void* MethodHandlePolyArg;

void* __jnative_fn_java_lang_invoke_MethodHandle_invoke___Ljava_lang_Object__Ljava_lang_Object_(
        MethodHandlePolyArg arg) {
    return arg;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic___Ljava_lang_Object__Ljava_lang_Object_(
        MethodHandlePolyArg arg) {
    return arg;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeExact___Ljava_lang_Object__Ljava_lang_Object_(
        MethodHandlePolyArg arg) {
    return arg;
}
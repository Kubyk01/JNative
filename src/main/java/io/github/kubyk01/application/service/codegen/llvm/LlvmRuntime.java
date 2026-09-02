package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.domain.ir.Type;

import java.util.List;

public class LlvmRuntime {

    public static String getDeclarations() {
        return """
                declare i8* @malloc(i64)
                declare void @free(i8*)
                declare i32 @printf(i8*, ...)
                declare void @abort() noreturn
                declare i32 @atexit(void ()*)
                declare i64 @llvm.objectsize.i64.p0i8(i8*, i1)

                ; ----- pthread mutex functions -----
                declare i32 @pthread_mutex_lock(i8*)
                declare i32 @pthread_mutex_unlock(i8*)
                declare i32 @pthread_mutex_init(i8*, i8*)
                declare i32 @pthread_mutex_destroy(i8*)
                declare i32 @pthread_mutexattr_init(i8*)
                declare i32 @pthread_mutexattr_settype(i8*, i32)
                declare i32 @pthread_mutexattr_destroy(i8*)

                ; ----- setjmp / longjmp -----
                ; returns_twice is mandatory: it prevents the optimizer from caching
                ; values in registers across the setjmp point (the return happens twice)
                declare i32 @_setjmp(i8*) returns_twice
                declare void @longjmp(i8*, i32) noreturn

                ; ----- JNative runtime functions (implemented in jnative_runtime.c) -----
                declare i8* @__jnative_create_string_array(i32, i8**)
                declare void @__jnative_monitor_enter(i8*)
                declare void @__jnative_monitor_exit(i8*)
                declare i1 @__jnative_instanceof(i8*, i8**)
                declare void @__jnative_push_catch(i8*, i8**)
                declare void @__jnative_pop_catch()
                declare void @__jnative_throw_exception(i8*)
                declare i8* @__jnative_get_exception_object()
                declare i1 @__jnative_catch_matches(i8*, i8**)
                declare void @__jnative_throw_null_pointer_exception()
                declare void @__jnative_throw_array_index_out_of_bounds()
                declare void @__jnative_throw_class_cast_exception()
                declare void @__jnative_throw_arithmetic_exception()

                ; ----- String concatenation (implemented in jnative_runtime.c) -----
                declare i8* @__jnative_concat_strings(i32, ...)

                ; ----- Reflection runtime (implemented in jnative_runtime.c) -----
                declare i8* @__jnative_invoke_method(i8*, i8*, i8**)
                declare i8* @__jnative_new_instance(i8*, i8**)
                """;
    }

    /**
     * Name of the global string constant for a type name/descriptor.
     * Used both at definition (typeStringConstant) and when referenced from functions,
     * so the definition and the references always match.
     */
    public static String typeStringGlobalName(String s) {
        return "@.str." + s.replaceAll("[^a-zA-Z0-9]", "_") + "_" + Integer.toHexString(s.hashCode());
    }

    /**
     * Definition of a global string constant (without the \00 terminator,
     * the array length equals the string length).
     */
    public static String typeStringConstant(String s) {
        return typeStringGlobalName(s) + " = private unnamed_addr constant ["
                + s.length() + " x i8] c\"" + s + "\"\n";
    }

    public static String mangleFunction(String name) {
        int dotIdx = name.lastIndexOf('.');
        int parenIdx = name.indexOf('(');
        if (dotIdx > 0 && parenIdx > dotIdx) {
            String className = name.substring(0, dotIdx);
            String methodPart = name.substring(dotIdx + 1);
            int parenPos = methodPart.indexOf('(');
            if (parenPos > 0) {
                String methodName = methodPart.substring(0, parenPos);
                String descriptor = methodPart.substring(parenPos);
                return mangleMethod(className, methodName, descriptor);
            }
        }
        return "fn_" + name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    public static String mangleMethod(String className, String methodName, String descriptor) {
        String safeClass = className.replace('/', '_').replaceAll("[^a-zA-Z0-9_]", "_");
        String safeMethod = methodName.replaceAll("[^a-zA-Z0-9_]", "_");
        String safeDesc = descriptor.replaceAll("[^a-zA-Z0-9_]", "_");
        return "fn_" + safeClass + "_" + safeMethod + "_" + safeDesc;
    }

    /**
     * Parses a string of the form className.methodName(descriptor) and returns the mangled name.
     */
    public static String mangleCallable(String callableName) {
        // Check whether the name matches the "ClassName.methodName(descriptor)" format
        if (!callableName.matches("^[a-zA-Z0-9_/$]+\\.[a-zA-Z0-9_<>$]+\\([^)]*\\)[^)]*$")) {
            // Non-standard name – replace all invalid characters with underscores
            String mangled = callableName.replaceAll("[^a-zA-Z0-9_]", "_");
            return "fn_" + mangled;
        }
        // Standard path
        int dotIdx = callableName.lastIndexOf('.');
        if (dotIdx < 0) {
            return mangleFunction(callableName);
        }
        String methodPart = callableName.substring(dotIdx + 1);
        int parenIdx = methodPart.indexOf('(');
        if (parenIdx < 0) {
            return mangleFunction(callableName);
        }
        String className = callableName.substring(0, dotIdx);
        String methodName = methodPart.substring(0, parenIdx);
        String descriptor = methodPart.substring(parenIdx);
        return mangleMethod(className, methodName, descriptor);
    }

    /**
     * Returns the LLVM function type for a method by its signature (of the form "name(desc)").
     * For example: "toString()Ljava/lang/String;" -> "i8* (i8*)*"
     */
    public static String getFunctionType(String methodSig, LlvmTypeMapper typeMapper) {
        int paren = methodSig.indexOf('(');
        if (paren < 0) return "i8* (...) *"; // fallback
        String desc = methodSig.substring(paren);
        Type retType = TypeResolver.descToReturnType(desc);
        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);
        StringBuilder sb = new StringBuilder();
        sb.append(typeMapper.toLlvmType(retType)).append(" (");
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(typeMapper.toLlvmType(paramTypes.get(i)));
        }
        sb.append(")*");
        return sb.toString();
    }
}

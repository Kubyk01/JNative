package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.domain.ir.Type;

import java.nio.charset.StandardCharsets;
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
            declare i32 @_setjmp(i8*) returns_twice
            declare void @longjmp(i8*, i32) noreturn

            ; ----- JNative runtime functions (implemented in jnative_runtime.c) -----
            declare i8* @__jnative_create_string_array(i32, i8**)
            declare i8* @__jnative_new_multi_array(i8*, i32, i32*, i32)
            declare void @__jnative_monitor_enter(i8*)
            declare void @__jnative_monitor_exit(i8*)
            declare i1 @__jnative_instanceof(i8*, i8**)
            declare void @__jnative_push_catch(i8*, i8**)
            declare void @__jnative_pop_catch()
            declare void @__jnative_throw_exception(i8*)
            declare i8* @__jnative_get_exception_object()
            declare i1 @__jnative_catch_matches(i8*, i8**)

            ; ----- Sparse interface table lookup -----
            declare i8** @__jnative_lookup_itable(%JNativeIfaceMap*, i32)

            ; ----- Throw helpers without caller context -----
            declare void @__jnative_throw_null_pointer_exception()
            declare void @__jnative_throw_array_index_out_of_bounds()
            declare void @__jnative_throw_class_cast_exception()
            declare void @__jnative_throw_arithmetic_exception()

            ; ----- Throw helpers with caller context -----
            declare void @__jnative_throw_exception_ctx(i8*, i8*)
            declare void @__jnative_throw_null_pointer_exception_ctx(i8*)
            declare void @__jnative_throw_array_index_out_of_bounds_ctx(i8*)
            declare void @__jnative_throw_class_cast_exception_ctx(i8*)
            declare void @__jnative_throw_arithmetic_exception_ctx(i8*)

            ; ----- String concatenation -----
            declare i8* @__jnative_concat_strings(i32, ...)

            ; ----- Value to string conversion -----
            declare i8* @__jnative_value_to_string_int(i32)
            declare i8* @__jnative_value_to_string_long(i64)
            declare i8* @__jnative_value_to_string_float(float)
            declare i8* @__jnative_value_to_string_double(double)
            declare i8* @__jnative_value_to_string_boolean(i32)
            declare i8* @__jnative_value_to_string_char(i32)
            declare i8* @__jnative_value_to_string_byte(i32)
            declare i8* @__jnative_value_to_string_short(i32)
            declare i8* @__jnative_value_to_string_object(i8*)

            ; ----- Reflection runtime -----
            declare i8* @__jnative_invoke_method(i8*, i8*, i8**)
            declare i8* @__jnative_new_instance(i8*, i8**)
            """;
    }

    public static String getVtableTypeDefinition() {
        return "%JNativeIfaceMapEntry = type { i32, i8** }\n"
            + "%JNativeIfaceMap      = type { i32, %JNativeIfaceMapEntry* }\n"
            + "%JNativeVTable       = type { i8**, %JNativeIfaceMap*, i8* }\n";
    }

    public static int typeStringArrayLength(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length + 1;
    }

    public static String typeStringGlobalName(String s) {
        return "@.str." + s.replaceAll("[^a-zA-Z0-9]", "_") + "_" + Integer.toHexString(s.hashCode());
    }

    public static String typeStringConstant(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder escaped = new StringBuilder(bytes.length + 8);
        for (byte b : bytes) {
            int v = b & 0xFF;
            switch (v) {
                case '\\' -> escaped.append("\\5C");
                case '"'  -> escaped.append("\\22");
                case '\n' -> escaped.append("\\0A");
                case '\r' -> escaped.append("\\0D");
                case '\t' -> escaped.append("\\09");
                default -> {
                    if (v < 0x20 || v > 0x7E) {
                        escaped.append(String.format("\\%02X", v));
                    } else {
                        escaped.append((char) v);
                    }
                }
            }
        }
        return typeStringGlobalName(s) + " = private unnamed_addr constant ["
            + (bytes.length + 1) + " x i8] c\"" + escaped + "\\00\"\n";
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

    public static String mangleCallable(String callableName) {
        if (!callableName.matches("^[a-zA-Z0-9_/$]+\\.[a-zA-Z0-9_<>$]+\\([^)]*\\)[^)]*$")) {
            String mangled = callableName.replaceAll("[^a-zA-Z0-9_]", "_");
            return "fn_" + mangled;
        }
        int dotIdx = callableName.lastIndexOf('.');
        if (dotIdx < 0) return mangleFunction(callableName);
        String methodPart = callableName.substring(dotIdx + 1);
        int parenIdx = methodPart.indexOf('(');
        if (parenIdx < 0) return mangleFunction(callableName);
        String className = callableName.substring(0, dotIdx);
        String methodName = methodPart.substring(0, parenIdx);
        String descriptor = methodPart.substring(parenIdx);
        return mangleMethod(className, methodName, descriptor);
    }

    public static String getFunctionType(String owner, String methodSig) {
        int paren = methodSig.indexOf('(');
        if (paren < 0) return "i8* (...) *";
        String desc = methodSig.substring(paren);
        Type retType = TypeResolver.descToReturnType(desc);
        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);
        StringBuilder sb = new StringBuilder();
        sb.append(LlvmTypeMapper.toLlvmType(retType)).append(" (");
        sb.append(LlvmTypeMapper.toLlvmType(Type.reference(owner)));
        for (Type pt : paramTypes) {
            sb.append(", ");
            sb.append(LlvmTypeMapper.toLlvmType(pt));
        }
        sb.append(")*");
        return sb.toString();
    }

    public static String mangleBase(String className, String methodName) {
        String safeClass = className.replace('/', '_').replaceAll("[^a-zA-Z0-9_]", "_");
        String safeMethod = methodName.replaceAll("[^a-zA-Z0-9_]", "_");
        return "fn_" + safeClass + "_" + safeMethod;
    }
}
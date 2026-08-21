package io.github.kubyk01.application.service.codegen.llvm;

public class LlvmRuntime {

    public static String getDeclarations() {
        return """
                declare i8* @malloc(i64)
                declare void @free(i8*)
                declare i32 @printf(i8*, ...)
                declare void @abort() noreturn
                declare i32 @atexit(void ()*)
                declare i64 @llvm.objectsize.i64.p0i8(i8*, i1)
                declare void @__jnative_monitor_enter(i8*)
                declare void @__jnative_monitor_exit(i8*)
                declare i1 @__jnative_instanceof(i8*, i8*)
                """;
    }

    public static String mangleFunction(String name) {
        return "fn_" + name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    public static String mangleMethod(String className, String methodName, String descriptor) {
        return "fn_" + className.replace('/', '_') + "_" + methodName + "_" + descriptor.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}

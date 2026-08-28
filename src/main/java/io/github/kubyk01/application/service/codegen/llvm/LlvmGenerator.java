package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.ir.Function;
import io.github.kubyk01.domain.analyzer.ir.Module;
import io.github.kubyk01.domain.analyzer.ir.Parameter;
import io.github.kubyk01.domain.analyzer.reflection.ReflectInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class LlvmGenerator {

    private final Module module;
    private final String entryClass;
    private final String entryMethod;
    private final String entryDescriptor;

    private final LlvmGlobalEmitter globalEmitter;
    private final LlvmFunctionEmitter functionEmitter;
    private final LlvmTypeMapper typeMapper;

    public LlvmGenerator(Module module, DependencyResolver resolver,
                         AliasAnalysisResult aliasResult,
                         String entryClass, String entryMethod, String entryDescriptor,
                         ReflectInfo reflectInfo) {
        this.module = module;
        this.entryClass = entryClass;
        this.entryMethod = entryMethod;
        this.entryDescriptor = entryDescriptor;
        LlvmTypeMapper typeMapper = new LlvmTypeMapper();
        this.typeMapper = typeMapper;
        this.globalEmitter = new LlvmGlobalEmitter(module, resolver, aliasResult, typeMapper, reflectInfo);
        this.functionEmitter = new LlvmFunctionEmitter(module, typeMapper, globalEmitter);
    }

    public String generate() {
        StringBuilder sb = new StringBuilder();

        sb.append("target datalayout = \"e-m:e-p270:32:32-p271:64:64-i64:64-f80:128-n8:16:32:64-S128\"\n");
        sb.append("target triple = \"x86_64-pc-linux-gnu\"\n\n");

        // Runtime declarations
        sb.append(LlvmRuntime.getDeclarations());

        // Globals, structs, type info, etc.
        sb.append(globalEmitter.generateGlobals());

        // Only declare functions that have NO body.
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) {
                sb.append(emitDeclaration(func));
            }
        }

        // Define functions that have bodies.
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() != null) {
                sb.append(functionEmitter.emitFunction(func));
            }
        }

        // Native entry point
        sb.append(generateMain());

        return sb.toString();
    }

    private String emitDeclaration(Function func) {
        StringBuilder sb = new StringBuilder();
        sb.append("declare ").append(typeMapper.toLlvmType(func.getReturnType()))
            .append(" @").append(func.getName()).append("(");
        List<Parameter> params = func.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(typeMapper.toLlvmType(params.get(i).getType()));
        }
        sb.append(")\n");
        return sb.toString();
    }

    private String generateMain() {
        StringBuilder sb = new StringBuilder();
        sb.append("define i32 @main(i32 %argc, i8** %argv) {\n");
        // Call atexit for the shutdown function
        sb.append("  call i32 @atexit(void ()* @")
            .append(LlvmRuntime.mangleFunction("__jnative_shutdown"))
            .append(")\n");
        // Call the user main (static method)
        String mainFunc = LlvmRuntime.mangleMethod(entryClass, entryMethod, entryDescriptor);
        sb.append("  %args_array = call i8* @__jnative_create_string_array(i32 %argc, i8** %argv)\n");
        sb.append("  call void @").append(mainFunc).append("(i8* %args_array)\n");
        sb.append("  ret i32 0\n");
        sb.append("}\n\n");
        return sb.toString();
    }
}
package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.ir.Function;
import io.github.kubyk01.domain.analyzer.ir.Module;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LlvmGenerator {

    private final Module module;
    private final DependencyResolver resolver;
    private final AliasAnalysisResult aliasResult;
    private final EscapeAnalysisResult escapeResult;
    private final String entryClass;
    private final String entryMethod;
    private final String entryDescriptor;

    private final LlvmTypeMapper typeMapper = new LlvmTypeMapper();
    private final LlvmGlobalEmitter globalEmitter;
    private final LlvmFunctionEmitter functionEmitter;

    public LlvmGenerator(Module module, DependencyResolver resolver,
                         AliasAnalysisResult aliasResult,
                         EscapeAnalysisResult escapeResult,
                         String entryClass, String entryMethod, String entryDescriptor) {
        this.module = module;
        this.resolver = resolver;
        this.aliasResult = aliasResult;
        this.escapeResult = escapeResult;
        this.entryClass = entryClass;
        this.entryMethod = entryMethod;
        this.entryDescriptor = entryDescriptor;
        // Initialize manually: a field initializer runs before constructor
        // assignments and would capture null dependencies
        this.globalEmitter = new LlvmGlobalEmitter(module, resolver, aliasResult, typeMapper);
        this.functionEmitter = new LlvmFunctionEmitter(module, typeMapper, globalEmitter);
    }

    public String generate() {
        StringBuilder sb = new StringBuilder();

        // Module header
        sb.append("target datalayout = \"e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128\"\n");
        sb.append("target triple = \"x86_64-pc-linux-gnu\"\n\n");

        // External function declarations
        sb.append(LlvmRuntime.getDeclarations());

        // Global variables and structs
        sb.append(globalEmitter.generateGlobals());

        // Function definitions
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            sb.append(functionEmitter.emitFunction(func));
        }

        // Generate the main entry point that calls atexit and the user main
        sb.append(generateMain());

        return sb.toString();
    }

    private String generateMain() {
        StringBuilder sb = new StringBuilder();
        sb.append("define i32 @main(i32 %argc, i8** %argv) {\n");
        // Call atexit for the shutdown function
        sb.append("  call i32 @atexit(void ()* @__jnative_shutdown)\n");
        // Call the user main (static method)
        String mainFunc = LlvmRuntime.mangleMethod(entryClass, entryMethod, entryDescriptor);
        sb.append("  call void @").append(mainFunc).append("(i32 %argc, i8** %argv)\n");
        sb.append("  ret i32 0\n");
        sb.append("}\n\n");
        return sb.toString();
    }
}

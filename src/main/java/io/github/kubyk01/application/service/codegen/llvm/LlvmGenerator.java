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
        // Initialize manually: a field initializer runs before constructor
        // assignments and would capture null dependencies
        LlvmTypeMapper typeMapper = new LlvmTypeMapper();
        this.typeMapper = typeMapper;
        this.globalEmitter = new LlvmGlobalEmitter(module, resolver, aliasResult, typeMapper, reflectInfo);
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
            if (func.getEntryBlock() == null) {
                // Native/external methods have no IR body: their implementation
                // is provided by the runtime C sources, so only declare them
                sb.append(emitDeclaration(func));
                continue;
            }
            sb.append(functionEmitter.emitFunction(func));
        }

        // Generate the main entry point that calls atexit and the user main
        sb.append(generateMain());

        // Runtime stubs (definitions for functions declared above)
        sb.append(generateRuntimeStubs());
        sb.append(generateMultiArrayStubs());

        return sb.toString();
    }

    private String generateMultiArrayStubs() {
        return """
                ; ----- Multi-dimensional array creation (full implementation) -----
                define i8* @__jnative_new_multi_array(i8* %desc, i32 %dims, i32* %sizes, i32 %elem_size) {
                    ; Recursive helper receives the index of the last dimension (dims - 1)
                    %last_dim = sub i32 %dims, 1
                    %result = call i8* @__jnative_create_multi_array_rec(i8* %desc, i32 %last_dim, i32* %sizes, i32 0, i32 %elem_size)
                    ret i8* %result
                }

                define internal i8* @__jnative_create_multi_array_rec(i8* %desc, i32 %last_dim, i32* %sizes, i32 %current_dim, i32 %elem_size) {
                    ; Last dimension -> array of base elements, otherwise -> array of pointers
                    %is_last = icmp eq i32 %current_dim, %last_dim
                    br i1 %is_last, label %create_base, label %create_array

                create_array:
                    ; Length of the current dimension
                    %idx = getelementptr i32, i32* %sizes, i32 %current_dim
                    %length = load i32, i32* %idx
                    ; Size: 4 (length header) + length * 8 (each element is a pointer to a sub-array)
                    %ptr_size = mul i32 %length, 8
                    %total_size = add i32 %ptr_size, 4
                    %total_size64 = zext i32 %total_size to i64
                    %array = call i8* @malloc(i64 %total_size64)
                    ; Write the length into the header
                    %len_ptr = bitcast i8* %array to i32*
                    store i32 %length, i32* %len_ptr
                    ; Loop over the elements: each slot is filled with a recursively created sub-array
                    %i = alloca i32
                    store i32 0, i32* %i
                    br label %loop_cond

                loop_cond:
                    %i_val = load i32, i32* %i
                    %cmp = icmp slt i32 %i_val, %length
                    br i1 %cmp, label %loop_body, label %loop_end

                loop_body:
                    ; Element slot address: header (4) + i * 8
                    %offset = mul i32 %i_val, 8
                    %offset64 = zext i32 %offset to i64
                    %base_ptr = getelementptr i8, i8* %array, i64 %offset64
                    %elem_ptr = getelementptr i8, i8* %base_ptr, i64 %offset64
                    %elem_slot = bitcast i8* %elem_ptr to i8**
                    ; Recursive creation of the next dimension's sub-array
                    %next_dim = add i32 %current_dim, 1
                    %subarray = call i8* @__jnative_create_multi_array_rec(i8* %desc, i32 %last_dim, i32* %sizes, i32 %next_dim, i32 %elem_size)
                    store i8* %subarray, i8** %elem_slot
                    %new_i = add i32 %i_val, 1
                    store i32 %new_i, i32* %i
                    br label %loop_cond

                loop_end:
                    ret i8* %array

                create_base:
                    ; Last dimension: array of base elements (4 + length * elem_size)
                    %idx_base = getelementptr i32, i32* %sizes, i32 %current_dim
                    %length_base = load i32, i32* %idx_base
                    %base_total_size = mul i32 %length_base, %elem_size
                    %base_total_with_header = add i32 %base_total_size, 4
                    %base_total_size64 = zext i32 %base_total_with_header to i64
                    %base_array = call i8* @malloc(i64 %base_total_size64)
                    %base_len_ptr = bitcast i8* %base_array to i32*
                    store i32 %length_base, i32* %base_len_ptr
                    ret i8* %base_array
                }
                """;
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
        sb.append(")\n\n");
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

    private String generateRuntimeStubs() {
        return """
                ; ----- Runtime stub implementations -----
                ; (__jnative_monitor_enter/__jnative_monitor_exit, throw/push_catch/pop_catch/
                ;  catch_matches/get_exception_object/__jnative_instanceof are implemented in jnative_runtime.c)
                """;
    }
}
package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.InvokeDynamicInfo;
import io.github.kubyk01.domain.ir.IrBuilder;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ResolvedCall;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.domain.analyzer.reflection.ReflectInfo;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static io.github.kubyk01.util.LlvmUtil.getElementSizeOfType;

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

        sb.append(LlvmRuntime.getDeclarations());

        // First, generate all lambda adaptors and register their structs/vtables
        generateLambdaAdaptors();

        // Now emit the registered extra structs and vtables
        globalEmitter.emitExtraStructs(sb);
        globalEmitter.emitExtraVtables(sb);
        sb.append("\n");

        // Emit remaining globals (standard structs, static fields, vtables, type info, reflection data)
        sb.append(globalEmitter.generateGlobals());

        // Define functions with bodies - iterate over a copy to avoid modification
        List<Function> functionsCopy = new ArrayList<>(module.getFunctions());
        for (Function func : functionsCopy) {
            if (func.getEntryBlock() != null) {
                sb.append(functionEmitter.emitFunction(func));
            }
        }

        // Now declare all functions that still have no entry block (external functions)
        // This includes any new functions added by ensureFunctionDeclared during emission
        for (Function func : new ArrayList<>(module.getFunctions())) {
            if (func.getEntryBlock() == null) {
                sb.append(emitDeclaration(func));
            }
        }

        // Generate main entry point
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
        sb.append("  call i32 @atexit(void ()* @")
            .append(LlvmRuntime.mangleFunction("__jnative_shutdown"))
            .append(")\n");
        String mainFunc = LlvmRuntime.mangleMethod(entryClass, entryMethod, entryDescriptor);
        sb.append("  %args_array = call i8* @__jnative_create_string_array(i32 %argc, i8** %argv)\n");
        sb.append("  call void @").append(mainFunc).append("(i8* %args_array)\n");
        sb.append("  ret i32 0\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private void generateLambdaAdaptors() {
        List<Instruction> lambdaInsts = new ArrayList<>();
        for (Function func : module.getFunctions()) {
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst.getOpcode() == Opcode.INVOKEDYNAMIC) {
                        InvokeDynamicInfo info = (InvokeDynamicInfo) inst.getInvokedynamicData();
                        ResolvedCall call = info.resolvedCall();
                        if (call != null && call.getType() == ResolvedCall.Type.LAMBDA) {
                            lambdaInsts.add(inst);
                        }
                    }
                }
            }
        }
        for (Instruction inst : lambdaInsts) {
            InvokeDynamicInfo info = (InvokeDynamicInfo) inst.getInvokedynamicData();
            ResolvedCall call = info.resolvedCall();
            createLambdaAdaptor(call, info);
        }
    }

    private void createLambdaAdaptor(ResolvedCall call, InvokeDynamicInfo info) {
        String lambdaId = call.getLambdaStructName();
        String adaptorName = "adaptor_" + lambdaId;

        // 1. Check bootstrap arguments
        Object[] bsmArgs = info.bootstrapArgs();
        if (bsmArgs.length < 2) {
            return;
        }

        // 2. Check if adapter already exists
        if (module.getFunction(adaptorName) != null) {
            return;
        }

        // 3. Extract impl method
        Handle implHandle = (Handle) bsmArgs[1];
        boolean isStatic = (implHandle.getTag() == Opcodes.H_INVOKESTATIC);
        String implOwner = implHandle.getOwner();
        String implName = implHandle.getName();
        String implDesc = implHandle.getDesc();

        org.objectweb.asm.Type samType = (org.objectweb.asm.Type) bsmArgs[0];
        String interfaceSig = samType.getDescriptor();
        Type retType = TypeResolver.descToReturnType(interfaceSig);
        List<Type> paramTypes = TypeResolver.descToParamTypes(interfaceSig);

        IrBuilder builder = new IrBuilder(module);
        List<Type> allParamTypes = new ArrayList<>();
        allParamTypes.add(Type.reference("java/lang/Object")); // receiver
        allParamTypes.addAll(paramTypes);
        Function adaptorFunc = builder.createFunction(adaptorName, retType, allParamTypes);

        // 5. Register struct and vtable (after function creation)
        globalEmitter.registerLambdaStruct(lambdaId, call.getCapturedTypes());
        globalEmitter.registerLambdaVtable(lambdaId, adaptorName);

        // 6. Create entry block
        builder.createBlock(adaptorName + "_entry");
        BasicBlock entry = builder.currentBlock();
        adaptorFunc.setEntryBlock(entry);

        // 7. Load captured variables
        List<Type> capturedTypes = call.getCapturedTypes();
        List<Value> loadedCaptures = new ArrayList<>();
        int offset = 8; // offset after vtable
        Parameter lambdaObj = adaptorFunc.getParameters().getFirst();

        for (Type capType : capturedTypes) {
            Instruction getField = new Instruction(Opcode.GET_FIELD);
            getField.addOperand(lambdaObj);
            getField.addOperand(new Constant(Type.INT, offset));
            Temporary tmp = builder.newTemporary(capType);
            getField.setResult(tmp);
            tmp.setDefiningInstruction(getField);
            entry.addInstruction(getField);
            loadedCaptures.add(tmp);
            offset += getElementSizeOfType(capType);
        }

        // 8. Form arguments for impl method call
        List<Value> callArgs = new ArrayList<>();
        if (!isStatic) {
            if (!loadedCaptures.isEmpty()) {
                callArgs.add(loadedCaptures.removeFirst());
            } else {
                callArgs.add(new Constant(Type.NULL, null));
            }
        }
        callArgs.addAll(loadedCaptures);
        for (int i = 1; i < adaptorFunc.getParameters().size(); i++) {
            callArgs.add(adaptorFunc.getParameters().get(i));
        }

        // 9. Call impl method
        String calleeName = implOwner + "." + implName + implDesc;
        Instruction callInst = new Instruction(Opcode.STATIC_CALL);
        callInst.addOperand(new Constant(Type.reference(calleeName), calleeName));
        for (Value arg : callArgs) {
            callInst.addOperand(arg);
        }
        if (!retType.isVoid()) {
            Temporary result = builder.newTemporary(retType);
            callInst.setResult(result);
            result.setDefiningInstruction(callInst);
            entry.addInstruction(callInst);
            builder.createReturn(result);
        } else {
            entry.addInstruction(callInst);
            builder.createReturn(null);
        }
    }
}
package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.application.service.codegen.llvm.nativepolymorphicfunctionresolver.PolymorphicResolver;
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

    public LlvmGenerator(Module module, DependencyResolver resolver,
                         AliasAnalysisResult aliasResult,
                         String entryClass, String entryMethod, String entryDescriptor,
                         ReflectInfo reflectInfo,
                         PolymorphicResolver polymorphicResolver) {
        this.module = module;
        this.entryClass = entryClass;
        this.entryMethod = entryMethod;
        this.entryDescriptor = entryDescriptor;
        this.globalEmitter = new LlvmGlobalEmitter(module, resolver, aliasResult, reflectInfo);
        this.functionEmitter = new LlvmFunctionEmitter(module, globalEmitter, polymorphicResolver, resolver);
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
        sb.append("declare ").append(LlvmTypeMapper.toLlvmType(func.getReturnType()))
            .append(" @").append(func.getName()).append("(");
        List<Parameter> params = func.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(LlvmTypeMapper.toLlvmType(params.get(i).getType()));
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

        Object[] bsmArgs = info.bootstrapArgs();
        if (bsmArgs.length < 2) return;
        if (module.getFunction(adaptorName) != null) return;

        Handle implHandle = (Handle) bsmArgs[1];
        int implTag = implHandle.getTag();
        boolean isStatic      = (implTag == Opcodes.H_INVOKESTATIC);
        boolean isInterface   = (implTag == Opcodes.H_INVOKEINTERFACE);
        boolean isSpecial     = (implTag == Opcodes.H_INVOKESPECIAL);
        boolean isConstructor = (implTag == Opcodes.H_NEWINVOKESPECIAL);

        String implOwner = implHandle.getOwner();
        String implName  = implHandle.getName();
        String implDesc  = implHandle.getDesc();

        org.objectweb.asm.Type samType = (org.objectweb.asm.Type) bsmArgs[0];
        String interfaceSig      = samType.getDescriptor();
        Type   samReturnType     = TypeResolver.descToReturnType(interfaceSig);
        List<Type> samParamTypes = TypeResolver.descToParamTypes(interfaceSig);

        IrBuilder builder = new IrBuilder(module);
        List<Type> allParamTypes = new ArrayList<>();
        allParamTypes.add(Type.reference("java/lang/Object"));   // lambda object
        allParamTypes.addAll(samParamTypes);
        Function adaptorFunc = builder.createFunction(adaptorName, samReturnType, allParamTypes);

        globalEmitter.registerLambdaStruct(lambdaId, call.getCapturedTypes());
        globalEmitter.registerLambdaVtable(lambdaId, adaptorName);

        builder.createBlock(adaptorName + "_entry");
        BasicBlock entry = builder.currentBlock();
        adaptorFunc.setEntryBlock(entry);

        // ---- Load captured variables ----
        List<Type> capturedTypes = call.getCapturedTypes();
        List<Value> loadedCaptures = new ArrayList<>();
        int offset = 8;
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

        // ---- Build the argument list for the impl method ----
        // Note: constructor references are handled separately below; they take
        // no receiver from captures or SAM args.
        List<Value> callArgs = new ArrayList<>();
        int firstSamIdx = 1;
        if (!isStatic && !isConstructor) {
            if (!loadedCaptures.isEmpty()) {
                // Bound method reference: first capture is the receiver
                callArgs.add(loadedCaptures.removeFirst());
            } else if (adaptorFunc.getParameters().size() > 1) {
                // Unbound method reference: first SAM argument IS the receiver
                callArgs.add(adaptorFunc.getParameters().get(1));
                firstSamIdx = 2;
            } else {
                log.warn("Lambda adaptor {}: non-static impl {} with no captures and no SAM args",
                    adaptorName, implOwner + "." + implName + implDesc);
                callArgs.add(new Constant(Type.NULL, null));
            }
        }
        callArgs.addAll(loadedCaptures);
        for (int i = firstSamIdx; i < adaptorFunc.getParameters().size(); i++) {
            callArgs.add(adaptorFunc.getParameters().get(i));
        }

        String calleeName = implOwner + "." + implName + implDesc;

        // ---- Constructor reference (Foo::new) ----
        // Allocate the object with NEW, call <init> on it, and return the object.
        if (isConstructor) {
            Instruction newInst = new Instruction(Opcode.NEW);
            newInst.addOperand(new Constant(Type.reference(implOwner), implOwner));
            Temporary objTmp = builder.newTemporary(Type.reference(implOwner));
            newInst.setResult(objTmp);
            objTmp.setDefiningInstruction(newInst);
            entry.addInstruction(newInst);

            Instruction ctorCall = new Instruction(Opcode.SPECIAL_CALL);
            ctorCall.addOperand(objTmp);
            ctorCall.addOperand(new Constant(Type.reference(calleeName), calleeName));
            for (Value arg : callArgs) ctorCall.addOperand(arg);
            entry.addInstruction(ctorCall);

            if (samReturnType.isVoid()) {
                builder.createReturn(null);
            } else {
                builder.createReturn(objTmp);
            }
            return;
        }

        // ---- Choose the right opcode for non-constructor references ----
        Opcode callOpcode;
        if (isStatic)          callOpcode = Opcode.STATIC_CALL;
        else if (isInterface)  callOpcode = Opcode.INTERFACE_CALL;
        else if (isSpecial)    callOpcode = Opcode.SPECIAL_CALL;
        else                   callOpcode = Opcode.VIRTUAL_CALL;

        Type implRetType = TypeResolver.descToReturnType(implDesc);

        Instruction callInst = new Instruction(callOpcode);
        if (callOpcode == Opcode.STATIC_CALL) {
            // [callee, arg...]
            callInst.addOperand(new Constant(Type.reference(calleeName), calleeName));
            for (Value arg : callArgs) callInst.addOperand(arg);
        } else {
            // [receiver, callee, arg...]
            callInst.addOperand(callArgs.getFirst());
            callInst.addOperand(new Constant(Type.reference(calleeName), calleeName));
            for (int i = 1; i < callArgs.size(); i++) callInst.addOperand(callArgs.get(i));
        }

        if (!implRetType.isVoid()) {
            Temporary result = builder.newTemporary(implRetType);
            callInst.setResult(result);
            result.setDefiningInstruction(callInst);
        }
        entry.addInstruction(callInst);

        if (samReturnType.isVoid()) {
            builder.createReturn(null);
        } else {
            Value retVal = callInst.getResult();
            if (retVal == null) retVal = new Constant(Type.NULL, null);
            builder.createReturn(retVal);
        }
    }
}
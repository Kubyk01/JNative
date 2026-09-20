package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.application.service.codegen.llvm.nativepolymorphicfunctionresolver.PolymorphicResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldReference;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
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
import io.github.kubyk01.domain.analyzer.reflection.ReflectClassInfo;
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
    private final DependencyResolver resolver;
    private final ReflectInfo reflectInfo;
    private final String entryClass;
    private final String entryMethod;
    private final String entryDescriptor;

    private final LlvmGlobalEmitter globalEmitter;
    private final LlvmFunctionEmitter functionEmitter;
    private List<Function> clinitFunctions = new ArrayList<>();

    public void setClinitFunctions(List<Function> functions) {
        this.clinitFunctions = functions != null ? functions : new ArrayList<>();
    }

    public LlvmGenerator(Module module, DependencyResolver resolver,
                         AliasAnalysisResult aliasResult,
                         String entryClass, String entryMethod, String entryDescriptor,
                         ReflectInfo reflectInfo,
                         PolymorphicResolver polymorphicResolver) {
        this.module = module;
        this.resolver = resolver;
        this.entryClass = entryClass;
        this.entryMethod = entryMethod;
        this.entryDescriptor = entryDescriptor;
        this.reflectInfo = reflectInfo;
        this.globalEmitter = new LlvmGlobalEmitter(module, resolver, aliasResult, reflectInfo);
        this.functionEmitter = new LlvmFunctionEmitter(module, globalEmitter, polymorphicResolver, resolver);
    }

    public String generate() {
        ensureExternalDeclarations();

        StringBuilder sb = new StringBuilder();

        sb.append("target datalayout = \"e-m:e-p270:32:32-p271:64:64-i64:64-f80:128-n8:16:32:64-S128\"\n");
        sb.append("target triple = \"x86_64-pc-linux-gnu\"\n\n");

        sb.append(LlvmRuntime.getVtableTypeDefinition());
        sb.append("\n");

        sb.append(LlvmRuntime.getDeclarations());

        generateLambdaAdaptors();

        globalEmitter.emitExtraStructs(sb);

        sb.append(globalEmitter.emitLambdaVtables());
        sb.append("\n");

        sb.append(globalEmitter.generateGlobals());

        List<Function> functionsCopy = new ArrayList<>(module.getFunctions());
        for (Function func : functionsCopy) {
            if (func.getEntryBlock() != null) {
                sb.append(functionEmitter.emitFunction(func));
            }
        }

        for (Function func : new ArrayList<>(module.getFunctions())) {
            if (func.getEntryBlock() == null) {
                sb.append(emitDeclaration(func));
            }
        }

        sb.append(globalEmitter.generateDeferredStringConstants());

        sb.append(generateMain());

        return sb.toString();
    }

    private void ensureExternalDeclarations() {
        resolver.forceLoadSystemClass("java/lang/Object");
        resolver.forceLoadSystemClass("java/lang/Class");
        resolver.forceLoadSystemClass("java/util/Dictionary");
        resolver.forceLoadSystemClass("java/util/Hashtable");
        resolver.forceLoadSystemClass("java/util/Properties");
        resolver.forceLoadSystemClass("java/util/concurrent/ConcurrentHashMap");
        resolver.forceLoadSystemClass("java/util/concurrent/ConcurrentHashMap$Node");

        ensureExternalFunction(
            "__jnative_fn_jdk_internal_util_SystemProps_Raw_cmdProperties___Ljava_util_HashMap_",
            Type.reference("java/util/HashMap"));

        ensureReflectClassRegistered("java/util/HashMap");
        ensureReflectClassRegistered("java/util/Properties");
        ensureReflectClassRegistered("java/util/concurrent/ConcurrentHashMap");
    }

    private void ensureExternalFunction(String name, Type returnType, Type... paramTypes) {
        if (module.getFunction(name) != null) return;
        Function f = new Function(name, returnType);
        for (int i = 0; i < paramTypes.length; i++) {
            f.addParameter(new Parameter(paramTypes[i], i));
        }
        module.addFunction(f);
    }

    private void ensureReflectClassRegistered(String className) {
        if (reflectInfo == null) return;
        if (className == null || className.isEmpty()) return;

        ClassNode cn = resolver.getClassNode(className);
        if (cn == null || cn.isExternal() || resolver.getClassBytes(className) == null) {
            resolver.forceLoadSystemClass(className);
            cn = resolver.getClassNode(className);
        }
        if (cn == null) return;

        ReflectClassInfo info = reflectInfo.getOrCreateClassInfo(className);
        if (info.getSuperName() == null && cn.getSuperName() != null) {
            info.setSuperName(cn.getSuperName());
        }
        info.getInterfaces().addAll(cn.getInterfaces());

        for (FieldNode field : cn.getFields()) {
            if ((field.getAccess() & Opcodes.ACC_STATIC) == 0) {
                FieldReference ref = new FieldReference(
                    className, field.getName(), field.getDescriptor());
                info.addField(ref);
            }
        }

        for (MethodNode mn : cn.getMethods()) {
            if (mn.getName().equals("<init>")) {
                MethodReference ref = new MethodReference(
                    className, mn.getName(), mn.getDescriptor());
                info.addConstructor(ref);
            }
        }

        String superName = cn.getSuperName();
        if (superName != null && !superName.isEmpty()) {
            ensureReflectClassRegistered(superName);
        }
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

    private int safeFieldOffset(String owner, String field, int fallback) {
        try {
            return globalEmitter.getFieldOffset(owner, field);
        } catch (Exception e) {
            log.warn("Could not compute field offset for {}.{}: {}; falling back to {}",
                owner, field, e.getMessage(), fallback);
            return fallback;
        }
    }

    private String generateMain() {
        StringBuilder sb = new StringBuilder();
        sb.append("define i32 @main(i32 %argc, i8** %argv) {\n");
        sb.append("  call i32 @atexit(void ()* @")
            .append(LlvmRuntime.mangleFunction("__jnative_shutdown"))
            .append(")\n");

        int poolSize = globalEmitter.getLiteralPoolSize();
        sb.append("  call void @__jnative_init_string_pool(i8** getelementptr inbounds ([")
            .append(poolSize).append(" x i8*], [").append(poolSize)
            .append(" x i8*]* @__jnative_literal_pool, i32 0, i32 0), i32 ")
            .append(poolSize).append(")\n");

        String stringClinitName = "fn_java_lang_String__clinit____V";
        Function stringClinit = module.getFunction(stringClinitName);
        if (stringClinit != null && stringClinit.getEntryBlock() != null) {
            emitDebugClinitCall(sb, stringClinitName);
            sb.append("  call void @").append(stringClinitName).append("()\n");
        } else {
            log.warn("java.lang.String.<clinit> is missing from the module; "
                + "COMPACT_STRINGS will default to false and every String.length() "
                + "will be halved. Check that ReachabilityAnalysis triggers "
                + "triggerClinit(\"java/lang/String\", ...) and that the class has "
                + "bytecode available.");
        }

        String systemClinitName = "fn_java_lang_System__clinit____V";
        String propsClinitName  = "fn_java_util_Properties__clinit____V";
        String unsafeClinitName = "fn_jdk_internal_misc_Unsafe__clinit____V";
        String chmClinitName    = "fn_java_util_concurrent_ConcurrentHashMap__clinit____V";
        String arraysClinitName = "fn_jdk_internal_util_ArraysSupport__clinit____V";
        String setJlaName       = "fn_java_lang_System_setJavaLangAccess___V";

        Function systemClinit = module.getFunction(systemClinitName);
        if (systemClinit != null && systemClinit.getEntryBlock() != null) {
            emitDebugClinitCall(sb, systemClinitName);
            sb.append("  call void @").append(systemClinitName).append("()\n");
        }

        Function setJlaFn = module.getFunction(setJlaName);
        if (setJlaFn != null && setJlaFn.getEntryBlock() != null) {
            sb.append("  call void @").append(setJlaName).append("()\n");
        }

        String[] bootstrapPrereqClinits = {
            unsafeClinitName,
            chmClinitName,
            propsClinitName,
            arraysClinitName,
        };

        for (String prereq : bootstrapPrereqClinits) {
            Function f = module.getFunction(prereq);
            if (f == null || f.getEntryBlock() == null) continue;
            emitDebugClinitCall(sb, prereq);
            sb.append("  call void @").append(prereq).append("()\n");
        }

        int propsTableOff      = safeFieldOffset("java/util/Properties", "table",       8);
        int propsCountOff      = safeFieldOffset("java/util/Properties", "count",      16);
        int propsThresholdOff  = safeFieldOffset("java/util/Properties", "threshold",  20);
        int propsLoadFactorOff = safeFieldOffset("java/util/Properties", "loadFactor", 24);
        int propsModCountOff   = safeFieldOffset("java/util/Properties", "modCount",   28);
        int propsDefaultsOff   = safeFieldOffset("java/util/Properties", "defaults",   32);
        int propsMapOff        = safeFieldOffset("java/util/Properties", "map",        40);
        int chmTableOff        = safeFieldOffset("java/util/concurrent/ConcurrentHashMap", "table",     24);
        int chmBaseCountOff    = safeFieldOffset("java/util/concurrent/ConcurrentHashMap", "baseCount", 40);
        int chmSizeCtlOff      = safeFieldOffset("java/util/concurrent/ConcurrentHashMap", "sizeCtl",   48);

        String offsetArgs =
            "i32 " + propsTableOff      + ", " +
                "i32 " + propsCountOff      + ", " +
                "i32 " + propsThresholdOff  + ", " +
                "i32 " + propsLoadFactorOff + ", " +
                "i32 " + propsModCountOff   + ", " +
                "i32 " + propsDefaultsOff   + ", " +
                "i32 " + propsMapOff        + ", " +
                "i32 " + chmTableOff        + ", " +
                "i32 " + chmBaseCountOff    + ", " +
                "i32 " + chmSizeCtlOff;

        sb.append("  %bootstrap_props = call i8* @__jnative_make_bootstrap_props(")
            .append(offsetArgs).append(")\n");
        sb.append("  store i8* %bootstrap_props, i8** @gv_java_lang_System_props\n");

        for (Function clinit : clinitFunctions) {
            if (clinit.getEntryBlock() == null) continue;
            String name = clinit.getName();
            if (name.equals(stringClinitName))  continue;
            if (name.equals(systemClinitName))  continue;
            if (name.equals(unsafeClinitName))  continue;
            if (name.equals(chmClinitName))     continue;
            if (name.equals(propsClinitName))   continue;
            if (name.equals(arraysClinitName))  continue;

            emitDebugClinitCall(sb, name);
            sb.append("  call void @").append(name).append("()\n");
        }

        Function initPhase1 = module.getFunction("fn_java_lang_System_initPhase1___V");
        if (initPhase1 != null && initPhase1.getEntryBlock() != null) {
            sb.append("  call void @fn_java_lang_System_initPhase1___V()\n");
        }

        Function initPhase2 = module.getFunction("fn_java_lang_System_initPhase2__ZZ_I");
        if (initPhase2 != null && initPhase2.getEntryBlock() != null) {
            sb.append("  call i32 @fn_java_lang_System_initPhase2__ZZ_I(i1 false, i1 false)\n");
        }

        Function initPhase3 = module.getFunction("fn_java_lang_System_initPhase3___V");
        if (initPhase3 != null && initPhase3.getEntryBlock() != null) {
            sb.append("  call void @fn_java_lang_System_initPhase3___V()\n");
        }

        sb.append("  %final_props = call i8* @__jnative_make_bootstrap_props(")
            .append(offsetArgs).append(")\n");
        sb.append("  store i8* %final_props, i8** @gv_java_lang_System_props\n");

        String mainFunc = LlvmRuntime.mangleMethod(entryClass, entryMethod, entryDescriptor);
        sb.append("  %args_array = call i8* @__jnative_create_string_array(i32 %argc, i8** %argv)\n");
        sb.append("  call void @").append(mainFunc).append("(i8* %args_array)\n");
        sb.append("  ret i32 0\n");
        sb.append("}\n\n");

        int ifaceId  = globalEmitter.getInterfaceId("java/lang/Runnable");
        int runSlot  = globalEmitter.getInterfaceMethodSlot("java/lang/Runnable", "run", "()V");
        sb.append("@__jnative_runnable_iface_id = constant i32 ").append(ifaceId).append("\n");
        sb.append("@__jnative_run_method_slot   = constant i32 ").append(runSlot).append("\n");

        int toStringSlot = globalEmitter.getVirtualSlot(
            "java/lang/Object", "toString", "()Ljava/lang/String;");
        sb.append("@__jnative_tostring_slot    = constant i32 ")
            .append(toStringSlot).append("\n\n");

        int privActionIfaceId = globalEmitter.getInterfaceId("java/security/PrivilegedAction");
        int privActionRunSlot = globalEmitter.getInterfaceMethodSlot(
            "java/security/PrivilegedAction", "run", "()Ljava/lang/Object;");
        sb.append("@__jnative_privilegedaction_iface_id = constant i32 ")
            .append(privActionIfaceId).append("\n");
        sb.append("@__jnative_privilegedaction_run_slot = constant i32 ")
            .append(privActionRunSlot).append("\n");

        int privExcActionIfaceId = globalEmitter.getInterfaceId("java/security/PrivilegedExceptionAction");
        int privExcActionRunSlot = globalEmitter.getInterfaceMethodSlot(
            "java/security/PrivilegedExceptionAction", "run", "()Ljava/lang/Object;");
        sb.append("@__jnative_privilegedexceptionaction_iface_id = constant i32 ")
            .append(privExcActionIfaceId).append("\n");
        sb.append("@__jnative_privilegedexceptionaction_run_slot = constant i32 ")
            .append(privExcActionRunSlot).append("\n\n");

        return sb.toString();
    }

    private void emitDebugClinitCall(StringBuilder sb, String clinitName) {
        int len = LlvmRuntime.typeStringArrayLength(clinitName);
        String g = LlvmRuntime.typeStringGlobalName(clinitName);
        sb.append("  call void @__jnative_debug_clinit(i8* getelementptr inbounds ([")
            .append(len).append(" x i8], [").append(len)
            .append(" x i8]* ").append(g).append(", i32 0, i32 0))\n");
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
        int handleTag = implHandle.getTag();
        Opcode callOpcode = switch (handleTag) {
            case Opcodes.H_INVOKESTATIC -> Opcode.STATIC_CALL;
            case Opcodes.H_INVOKEINTERFACE -> Opcode.INTERFACE_CALL;
            case Opcodes.H_INVOKEVIRTUAL -> Opcode.VIRTUAL_CALL;
            case Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> Opcode.SPECIAL_CALL;
            default -> Opcode.STATIC_CALL;
        };
        boolean isStatic = (handleTag == Opcodes.H_INVOKESTATIC);

        String implOwner = implHandle.getOwner();
        String implName  = implHandle.getName();
        String implDesc  = implHandle.getDesc();

        if (!(bsmArgs[0] instanceof org.objectweb.asm.Type samType)) return;
        if (samType.getSort() != org.objectweb.asm.Type.METHOD) return;

        String samDescriptor = samType.getDescriptor();
        String samSig = info.name() + samDescriptor;

        String dynDescriptor = info.descriptor();
        org.objectweb.asm.Type dynReturnType = org.objectweb.asm.Type.getReturnType(dynDescriptor);
        if (dynReturnType.getSort() != org.objectweb.asm.Type.OBJECT) return;
        String samInterface = dynReturnType.getInternalName();

        Type retType = TypeResolver.descToReturnType(samDescriptor);
        List<Type> paramTypes = TypeResolver.descToParamTypes(samDescriptor);

        IrBuilder builder = new IrBuilder(module);
        List<Type> allParamTypes = new ArrayList<>();
        allParamTypes.add(Type.reference("java/lang/Object"));
        allParamTypes.addAll(paramTypes);
        Function adaptorFunc = builder.createFunction(adaptorName, retType, allParamTypes);

        globalEmitter.registerLambdaClass(
            lambdaId, samInterface, samSig, adaptorName, call.getCapturedTypes());

        builder.createBlock(adaptorName + "_entry");
        BasicBlock entry = builder.currentBlock();
        adaptorFunc.setEntryBlock(entry);

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

        String calleeName = implOwner + "." + implName + implDesc;

        if (callOpcode == Opcode.INTERFACE_CALL) {
            globalEmitter.ensureInterfaceRegistered(implOwner);
        } else if (callOpcode == Opcode.VIRTUAL_CALL) {
            globalEmitter.ensureClassLayoutBuilt(implOwner);
        }

        Instruction callInst = new Instruction(callOpcode);
        if (isStatic) {
            callInst.addOperand(new Constant(Type.reference(calleeName), calleeName));
            for (Value arg : callArgs) callInst.addOperand(arg);
        } else {
            Value receiver = callArgs.isEmpty()
                ? new Constant(Type.NULL, null)
                : callArgs.removeFirst();
            callInst.addOperand(receiver);
            callInst.addOperand(new Constant(Type.reference(calleeName), calleeName));
            for (Value arg : callArgs) callInst.addOperand(arg);
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
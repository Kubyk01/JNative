package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldReference;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.analyzer.reflection.ReflectClassInfo;
import io.github.kubyk01.domain.analyzer.reflection.ReflectInfo;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.InvokeDynamicInfo;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.objectweb.asm.Opcodes;

import java.util.*;

import static io.github.kubyk01.util.LlvmUtil.getElementSizeOfType;

@RequiredArgsConstructor
public class LlvmGlobalEmitter {

    public static final int OBJECT_HEADER_SIZE = 8;

    private final Module module;
    private final DependencyResolver resolver;
    private final AliasAnalysisResult aliasResult;
    private final ReflectInfo reflectInfo;

    private final Map<String, String> structNames = new HashMap<>();
    private final Map<String, Integer> fieldOffsets = new HashMap<>();
    private final Set<String> emittedStringConstants = new HashSet<>();

    private final Map<String, Integer> methodIndex = new HashMap<>();
    private final Map<String, String> vtableNames = new HashMap<>();
    @Getter
    private int totalMethods = 0;
    private boolean methodIndexBuilt = false;

    private final Map<String, String> typeInfoNames = new HashMap<>();

    private final Map<String, String> extraStructs = new LinkedHashMap<>();
    private final Map<String, String> extraVtables = new LinkedHashMap<>();

    public void addExtraStruct(String name, String definition) {
        extraStructs.put(name, definition);
    }

    public void addExtraVtable(String name, String content) {
        extraVtables.put(name, content);
    }

    public String registerLambdaStruct(String lambdaId, List<Type> capturedTypes) {
        String structName = "%struct.lambda_" + lambdaId;
        if (extraStructs.containsKey(structName)) return structName;

        StringBuilder fields = new StringBuilder("{ i8*");
        for (Type t : capturedTypes) {
            fields.append(", ").append(LlvmTypeMapper.toLlvmType(t));
        }
        fields.append(" }");

        extraStructs.put(structName, structName + " = type " + fields);
        return structName;
    }

    public String registerLambdaVtable(String lambdaId, String adaptorName, String samSig) {
        ensureMethodIndex();

        String vtableName = "@vtable_lambda_" + lambdaId;
        if (extraVtables.containsKey(vtableName)) return vtableName;

        int idx = resolveSamIndex(samSig);
        if (idx < 0) idx = 0;

        int length = Math.max(totalMethods, idx + 1);

        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i > 0) entries.append(", ");
            if (i == idx) {
                entries.append("i8* bitcast (void (i8*, ...)* @")
                    .append(adaptorName).append(" to i8*)");
            } else {
                entries.append("i8* null");
            }
        }

        String content = vtableName + " = constant [" + length + " x i8*] [" + entries + "]";
        extraVtables.put(vtableName, content);
        return vtableName;
    }

    private int resolveSamIndex(String samSig) {
        if (samSig == null) return -1;
        int paren = samSig.indexOf('(');
        if (paren > 0) {
            return getMethodIndex(samSig);
        }
        if (paren == 0) {
            for (Map.Entry<String, Integer> e : methodIndex.entrySet()) {
                if (e.getKey().endsWith(samSig)) return e.getValue();
            }
        }
        return -1;
    }

    public void emitExtraStructs(StringBuilder sb) {
        for (String def : extraStructs.values()) {
            sb.append(def).append("\n");
        }
    }

    public void emitExtraVtables(StringBuilder sb) {
        for (String def : extraVtables.values()) {
            sb.append(def).append("\n");
        }
    }

    public String generateGlobals() {
        return generateStructs()
            + generateStaticFields()
            + generateTypeStringConstants()
            + generateVtables()
            + generateTypeInfo()
            + generateReflectionData();
    }

    private String generateTypeStringConstants() {
        Set<String> names = new LinkedHashSet<>();
        names.add("java/lang/Object");

        for (Function func : module.getFunctions()) {
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {

                    if (inst.getOpcode() == Opcode.INSTANCEOF
                        || inst.getOpcode() == Opcode.CHECKCAST
                        || inst.getOpcode() == Opcode.MULTI_NEW_ARRAY) {
                        for (Value v : inst.getOperands()) {
                            if (v instanceof Constant c && c.getType().isReference()) {
                                names.add(c.getValue().toString());
                            }
                        }
                    }

                    for (Value v : inst.getOperands()) {
                        if (v instanceof Constant c
                            && c.getType().isReference()
                            && "java/lang/String".equals(c.getType().getClassName())
                            && c.getValue() instanceof String s) {
                            names.add(s);
                        }
                    }

                    if (inst.getOpcode() == Opcode.INVOKEDYNAMIC
                        && inst.getInvokedynamicData() instanceof InvokeDynamicInfo dynInfo
                        && dynInfo.bootstrapMethod() != null
                        && "java/lang/invoke/StringConcatFactory".equals(dynInfo.bootstrapMethod().getOwner())
                        && dynInfo.bootstrapArgs().length >= 1
                        && dynInfo.bootstrapArgs()[0] instanceof String recipe) {

                        StringBuilder seg = new StringBuilder();
                        for (int i = 0; i < recipe.length(); i++) {
                            char c = recipe.charAt(i);
                            if (c == '\u0001' || c == '\u0002') {
                                if (!seg.isEmpty()) {
                                    names.add(seg.toString());
                                    seg.setLength(0);
                                }
                            } else {
                                seg.append(c);
                            }
                        }
                        if (!seg.isEmpty()) {
                            names.add(seg.toString());
                        }

                        for (int i = 1; i < dynInfo.bootstrapArgs().length; i++) {
                            Object a = dynInfo.bootstrapArgs()[i];
                            if (a != null) {
                                names.add(String.valueOf(a));
                            }
                        }
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder("\n; ----- String / type-name constants -----\n");
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        for (String name : sorted) {
            emittedStringConstants.add(name);
            sb.append(LlvmRuntime.typeStringConstant(name));
        }
        sb.append("\n");
        return sb.toString();
    }

    private String generateStructs() {
        Set<String> newClassNames = new HashSet<>();
        for (Function func : module.getFunctions()) {
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst.getOpcode() == Opcode.NEW && !inst.getOperands().isEmpty()) {
                        Value v = inst.getOperands().getFirst();
                        if (v instanceof Constant c && c.getType().isReference()) {
                            newClassNames.add(c.getValue().toString());
                        }
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();

        String objStruct = LlvmTypeMapper.toLlvmStruct("java/lang/Object");
        if (!structNames.containsKey("java/lang/Object")) {
            sb.append(objStruct).append(" = type { }\n");
            structNames.put("java/lang/Object", objStruct);
        }

        List<ClassNode> allClasses = new ArrayList<>(resolver.getClassMap().values());
        for (ClassNode cls : allClasses) {
            if (cls.isExternal()) continue;
            if (structNames.containsKey(cls.getName())) continue;

            String structName = LlvmTypeMapper.toLlvmStruct(cls.getName());
            sb.append(structName).append(" = type { ");

            List<FieldNode> allFields = collectAllFields(cls);
            List<String> fieldTypes = new ArrayList<>();
            for (FieldNode field : allFields) {
                fieldTypes.add(LlvmTypeMapper.toLlvmType(field.getType()));
            }
            sb.append(String.join(", ", fieldTypes));
            sb.append(" }\n");
            structNames.put(cls.getName(), structName);
        }

        for (String className : newClassNames) {
            if (!structNames.containsKey(className)) {
                String structName = LlvmTypeMapper.toLlvmStruct(className);
                sb.append(structName).append(" = type { i8* }\n");
                structNames.put(className, structName);
            }
        }

        sb.append("\n");
        return sb.toString();
    }

    private List<FieldNode> collectAllFields(ClassNode cls) {
        List<FieldNode> result = new ArrayList<>();
        if (cls.getSuperName() != null && !cls.getSuperName().equals("java/lang/Object")) {
            ClassNode superNode = resolver.getClassNode(cls.getSuperName());
            if (superNode != null && !superNode.isExternal()) {
                result.addAll(collectAllFields(superNode));
            }
        }
        result.addAll(cls.getFields());
        return result;
    }

    private String generateStaticFields() {
        StringBuilder sb = new StringBuilder();
        Map<String, PointsToSet> staticFields = aliasResult.getGraph().getStaticFieldPointsToMap();
        for (Map.Entry<String, PointsToSet> entry : staticFields.entrySet()) {
            String fullName = entry.getKey();
            int dot = fullName.lastIndexOf('.');
            String owner = fullName.substring(0, dot);
            String fieldName = fullName.substring(dot + 1);
            Type fieldType = getFieldType(owner, fieldName);
            if (fieldType == null) fieldType = Type.UNKNOWN;
            String llvmType = LlvmTypeMapper.toLlvmType(fieldType);

            String init;
            if (fieldType.isReference() || fieldType.isArray()
                || fieldType.isNull() || fieldType.isUnknown()) {
                init = "null";
            } else if (fieldType == Type.FLOAT || fieldType == Type.DOUBLE) {
                init = "0.0";
            } else {
                init = "0";
            }

            String globalName = "gv_" + LlvmTypeMapper.sanitizeIdentifier(fullName);
            sb.append("@").append(globalName).append(" = global ").append(llvmType)
                .append(" ").append(init).append(", align 8\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    public void ensureMethodIndex() {
        if (methodIndexBuilt) return;
        buildMethodIndex();
        methodIndexBuilt = true;
    }

    private void buildMethodIndex() {
        Set<String> signatures = new HashSet<>();

        List<ClassNode> allClasses = new ArrayList<>(resolver.getClassMap().values());
        for (ClassNode cls : allClasses) {
            if (cls.isExternal()) continue;
            for (MethodNode mn : cls.getMethods()) {
                if (isVirtual(mn)) {
                    signatures.add(mn.getName() + mn.getDescriptor());
                }
            }
        }

        List<String> sortedSigs = new ArrayList<>(signatures);
        Collections.sort(sortedSigs);

        for (int i = 0; i < sortedSigs.size(); i++) {
            methodIndex.put(sortedSigs.get(i), i);
        }
        totalMethods = sortedSigs.size();
    }

    private String generateVtables() {
        ensureMethodIndex();

        StringBuilder sb = new StringBuilder();
        sb.append("\n; ----- Vtables (global method indices: ")
            .append(totalMethods).append(") -----\n");

        int runIdx = methodIndex.getOrDefault("run()V", -1);
        sb.append("@__jnative_run_method_index = constant i32 ")
            .append(runIdx).append("\n");

        if (totalMethods == 0) {
            sb.append("\n");
            return sb.toString();
        }

        List<String> sortedSigs = new ArrayList<>(methodIndex.keySet());
        sortedSigs.sort(Comparator.comparingInt(methodIndex::get));

        List<ClassNode> allClasses = new ArrayList<>(resolver.getClassMap().values());
        for (ClassNode cls : allClasses) {
            if (cls.isExternal()) continue;

            String className = cls.getName();
            Map<String, MethodNode> methodMap = new HashMap<>();
            collectMethods(cls, methodMap);

            List<String> entries = new ArrayList<>();
            for (String sig : sortedSigs) {
                MethodNode mn = methodMap.get(sig);
                if (mn == null || mn.isAbstract()) {
                    entries.add("i8* null");
                    continue;
                }
                String[] declOwner = new String[1];
                resolver.findMethodInHierarchy(className, mn.getName(), mn.getDescriptor(), declOwner);
                String owner = declOwner[0] != null ? declOwner[0] : className;
                boolean isNative = mn.isNative();
                String funcName = (isNative ? "__jnative_" : "")
                    + LlvmRuntime.mangleMethod(owner, mn.getName(), mn.getDescriptor());

                Function fn = module.getFunction(funcName);
                if (fn == null) {
                    if (isNative) {
                        ensureNativeFunctionDeclared(funcName, mn, owner);
                    } else {
                        entries.add("i8* null");
                        continue;
                    }
                }
                String ret = LlvmTypeMapper.toLlvmType(mn.getReturnType());
                String params = buildParamTypes(mn);
                entries.add("i8* bitcast (" + ret + " (" + params + ")* @"
                    + funcName + " to i8*)");
            }

            String vtableName = "@vtable_" + LlvmTypeMapper.sanitizeIdentifier(className);
            sb.append(vtableName).append(" = constant [")
                .append(totalMethods).append(" x i8*] [");
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(entries.get(i));
            }
            sb.append("]\n");
            vtableNames.put(className, vtableName);
        }
        return sb.toString();
    }

    public String getStructName(String className) {
        return structNames.getOrDefault(className, LlvmTypeMapper.toLlvmStruct(className));
    }

    public int getFieldOffset(String className, String fieldName) {
        String key = className + "." + fieldName;
        Integer cached = fieldOffsets.get(key);
        if (cached != null) return cached;

        ClassNode cls = resolver.getClassNode(className);
        if (cls == null) {
            throw new IllegalStateException(
                "Cannot resolve class for field offset: " + className + "." + fieldName);
        }
        if (cls.isExternal()) {
            throw new IllegalStateException(
                "Cannot compute field offset for external class: " + className + "." + fieldName);
        }

        List<FieldNode> allFields = collectAllFields(cls);
        int offset = OBJECT_HEADER_SIZE;
        int foundOffset = -1;
        for (FieldNode f : allFields) {
            if (f.getName().equals(fieldName)) foundOffset = offset;
            offset += fieldSize(f.getType());
        }

        if (foundOffset < 0) {
            throw new IllegalStateException(
                "Field not found in class hierarchy: " + className + "." + fieldName);
        }
        fieldOffsets.put(key, foundOffset);
        return foundOffset;
    }

    private static int fieldSize(Type ft) {
        if (ft == null) return 8;
        if (ft.isReference() || ft.isArray()) return 8;
        if (ft == Type.LONG || ft == Type.DOUBLE) return 8;
        if (ft == Type.INT || ft == Type.FLOAT) return 4;
        if (ft == Type.SHORT || ft == Type.CHAR) return 2;
        if (ft == Type.BYTE || ft == Type.BOOLEAN) return 1;
        return 8;
    }

    private String generateTypeInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n; ----- Type info tables -----\n");

        List<ClassNode> allClasses = new ArrayList<>(resolver.getClassMap().values());
        for (ClassNode cls : allClasses) {
            if (cls.isExternal()) continue;
            String className = cls.getName();
            Set<String> allParents = new LinkedHashSet<>();
            collectSuperclasses(cls, allParents);
            collectInterfaces(cls, allParents);

            List<String> entries = new ArrayList<>();
            for (String parent : allParents) {
                String vtableName = getVtableName(parent);
                if (vtableName == null) {
                    entries.add("i8* null");
                } else {
                    entries.add("i8* bitcast ([" + totalMethods + " x i8*]* "
                        + vtableName + " to i8*)");
                }
            }
            entries.add("i8* null");

            String typeInfoName = "@__type_info_" + LlvmTypeMapper.sanitizeIdentifier(className);
            typeInfoNames.put(className, typeInfoName);
            sb.append(typeInfoName).append(" = private constant [")
                .append(entries.size()).append(" x i8*] [");
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(entries.get(i));
            }
            sb.append("]\n");
        }
        return sb.toString();
    }

    private void collectSuperclasses(ClassNode cls, Set<String> accumulator) {
        accumulator.add(cls.getName());
        if (cls.getSuperName() != null && !cls.getSuperName().equals("java/lang/Object")) {
            ClassNode superNode = resolver.getClassNode(cls.getSuperName());
            if (superNode != null && !superNode.isExternal()) {
                collectSuperclasses(superNode, accumulator);
            } else {
                accumulator.add("java/lang/Object");
            }
        } else {
            accumulator.add("java/lang/Object");
        }
    }

    private void collectInterfaces(ClassNode cls, Set<String> accumulator) {
        for (String iface : cls.getInterfaces()) {
            accumulator.add(iface);
            ClassNode ifaceNode = resolver.getClassNode(iface);
            if (ifaceNode != null && !ifaceNode.isExternal()) {
                collectInterfaces(ifaceNode, accumulator);
            }
        }
    }

    public String getTypeInfoName(String className) {
        return typeInfoNames.get(className);
    }

    private boolean isVirtual(MethodNode mn) {
        int access = mn.getAccess();
        if ((access & Opcodes.ACC_STATIC) != 0) return false;
        if ((access & Opcodes.ACC_PRIVATE) != 0) return false;
        if (mn.getName().equals("<init>")) return false;
        return !mn.getName().equals("<clinit>");
    }

    private void collectMethods(ClassNode cls, Map<String, MethodNode> methodMap) {
        if (cls.getSuperName() != null && !cls.getSuperName().equals("java/lang/Object")) {
            ClassNode superNode = resolver.getClassNode(cls.getSuperName());
            if (superNode != null && !superNode.isExternal()) {
                collectMethods(superNode, methodMap);
            }
        }
        for (MethodNode mn : cls.getMethods()) {
            methodMap.put(mn.getName() + mn.getDescriptor(), mn);
        }
    }

    private String buildParamTypes(MethodNode mn) {
        List<Type> params = mn.getParameterTypes();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(LlvmTypeMapper.toLlvmType(params.get(i)));
        }
        return sb.toString();
    }

    private void ensureNativeFunctionDeclared(String funcName, MethodNode mn, String owner) {
        if (module.getFunction(funcName) != null) return;

        Type retType = mn.getReturnType();
        List<Type> allParams = new ArrayList<>();
        if (!mn.isStatic()) {
            allParams.add(Type.reference(owner));
        }
        allParams.addAll(mn.getParameterTypes());

        Function func = new Function(funcName, retType);
        for (int i = 0; i < allParams.size(); i++) {
            func.addParameter(new Parameter(allParams.get(i), i));
        }
        module.addFunction(func);
    }

    public int getMethodIndex(String sig) {
        return methodIndex.getOrDefault(sig, -1);
    }

    public String getVtableName(String className) {
        return vtableNames.get(className);
    }

    private String generateReflectionData() {
        StringBuilder sb = new StringBuilder();
        StringBuilder strConsts = new StringBuilder();
        sb.append("\n; ----- Reflection data -----\n");

        sb.append("%ReflectionMethod = type { i8*, i8*, i8*, i32 }\n");
        sb.append("%ReflectionField = type { i8*, i8*, i32, i32 }\n");
        sb.append("%ReflectionConstructor = type { i8*, i8*, i32 }\n");
        sb.append("%ReflectionClass = type { i8*, %ReflectionClass*, %ReflectionClass**, "
            + "%ReflectionMethod**, %ReflectionField**, %ReflectionConstructor**, i32, i32 }\n");

        if (reflectInfo == null || reflectInfo.getAllClasses().isEmpty()) {
            sb.append("@reflect_all_classes = constant [1 x %ReflectionClass*] "
                + "[%ReflectionClass* null]\n");
            return sb.toString();
        }

        List<String> classNames = new ArrayList<>(reflectInfo.getAllClasses());
        Collections.sort(classNames);

        Map<String, String> classVarNames = new HashMap<>();
        for (String className : classNames) {
            ClassNode classNode = resolver.getClassNode(className);
            if (classNode == null || classNode.isExternal()) continue;
            classVarNames.put(className,
                "@refclass_" + LlvmTypeMapper.sanitizeIdentifier(className));
        }

        List<String> classPtrs = new ArrayList<>();
        for (String className : classNames) {
            ReflectClassInfo info = reflectInfo.getOrCreateClassInfo(className);
            ClassNode classNode = resolver.getClassNode(className);
            if (classNode == null || classNode.isExternal()) continue;

            String cleanClassName = LlvmTypeMapper.sanitizeIdentifier(className);
            String classVarName = "@refclass_" + cleanClassName;

            int objectSize = OBJECT_HEADER_SIZE;
            for (FieldNode f : collectAllFields(classNode)) {
                objectSize += getElementSizeOfType(f.getType());
            }

            List<MethodReference> sortedMethods = new ArrayList<>(info.getMethods());
            sortedMethods.sort(Comparator.comparing(MethodReference::toString));
            List<String> methodPtrs = new ArrayList<>();
            for (MethodReference method : sortedMethods) {
                String methodName = method.getName();
                String desc = method.getDescriptor();
                String mangledSuffix = methodName + "_"
                    + desc.replaceAll("[^a-zA-Z0-9_]", "_");
                String adaptorName = "__reflect_adaptor_" + cleanClassName + "_" + mangledSuffix;

                Function impl = module.getFunction(
                    LlvmRuntime.mangleMethod(className, methodName, desc));
                String adaptorPtr = "i8* null";
                if (impl != null && impl.getEntryBlock() != null) {
                    sb.append(emitAdaptorForMethod(className, method));
                    adaptorPtr = "i8* bitcast (i8* (i8*, i8**)* @"
                        + adaptorName + " to i8*)";
                }

                int modifiers = 0;
                MethodNode mn = findMethod(classNode, methodName, desc);
                if (mn != null) modifiers = mn.getAccess();

                String methodVar = "@refmethod_" + cleanClassName + "_" + mangledSuffix;
                sb.append(methodVar).append(" = constant %ReflectionMethod { i8* ")
                    .append(ensureStringConstant(strConsts, methodName))
                    .append(", i8* ").append(ensureStringConstant(strConsts, desc))
                    .append(", ").append(adaptorPtr)
                    .append(", i32 ").append(modifiers).append(" }\n");
                methodPtrs.add("i8* bitcast (%ReflectionMethod* " + methodVar + " to i8*)");
            }
            String methodsArray = "@refmethods_" + cleanClassName;
            appendNullTerminatedPtrArray(sb, methodsArray, methodPtrs);

            List<FieldReference> sortedFields = new ArrayList<>(info.getFields());
            sortedFields.sort(Comparator.comparing(FieldReference::toString));
            List<String> fieldPtrs = new ArrayList<>();
            for (FieldReference field : sortedFields) {
                String fieldName = field.getName();
                String desc = field.getDescriptor();
                int offset = 0;
                int modifiers = 0;
                FieldNode fn = resolver.getField(className, fieldName);
                if (fn != null) {
                    modifiers = fn.getAccess();
                    if (desc == null) desc = fn.getDescriptor();
                }
                if (desc != null) offset = getFieldOffset(className, fieldName);

                String fieldVar = "@reffield_" + cleanClassName + "_" + fieldName;
                sb.append(fieldVar).append(" = constant %ReflectionField { i8* ")
                    .append(ensureStringConstant(strConsts, fieldName))
                    .append(", i8* ")
                    .append(desc != null ? ensureStringConstant(strConsts, desc) : "null")
                    .append(", i32 ").append(offset)
                    .append(", i32 ").append(modifiers).append(" }\n");
                fieldPtrs.add("i8* bitcast (%ReflectionField* " + fieldVar + " to i8*)");
            }
            String fieldsArray = "@reffields_" + cleanClassName;
            appendNullTerminatedPtrArray(sb, fieldsArray, fieldPtrs);

            List<MethodReference> sortedCtors = new ArrayList<>(info.getConstructors());
            sortedCtors.sort(Comparator.comparing(MethodReference::toString));
            List<String> ctorPtrs = new ArrayList<>();
            for (MethodReference ctor : sortedCtors) {
                String desc = ctor.getDescriptor();
                String mangledDesc = desc.replaceAll("[^a-zA-Z0-9_]", "_");
                String adaptorName = "__reflect_adaptor_ctor_"
                    + cleanClassName + "_" + mangledDesc;

                Function impl = module.getFunction(
                    LlvmRuntime.mangleMethod(className, "<init>", desc));
                String adaptorPtr = "i8* null";
                if (impl != null && impl.getEntryBlock() != null) {
                    sb.append(emitAdaptorForConstructor(className, ctor, objectSize));
                    adaptorPtr = "i8* bitcast (i8* (i8**)* @"
                        + adaptorName + " to i8*)";
                }

                int modifiers = 0;
                MethodNode mn = findMethod(classNode, "<init>", desc);
                if (mn != null) modifiers = mn.getAccess();

                String ctorVar = "@refctor_" + cleanClassName + "_" + mangledDesc;
                sb.append(ctorVar).append(" = constant %ReflectionConstructor { i8* ")
                    .append(ensureStringConstant(strConsts, desc))
                    .append(", ").append(adaptorPtr)
                    .append(", i32 ").append(modifiers).append(" }\n");
                ctorPtrs.add("i8* bitcast (%ReflectionConstructor* " + ctorVar + " to i8*)");
            }
            String ctorsArray = "@refctors_" + cleanClassName;
            appendNullTerminatedPtrArray(sb, ctorsArray, ctorPtrs);

            String superClassPtr = "null";
            if (info.getSuperName() != null && !info.getSuperName().equals("java/lang/Object")) {
                String superVar = classVarNames.get(info.getSuperName());
                if (superVar != null) superClassPtr = superVar;
            }
            List<String> ifacePtrs = new ArrayList<>();
            List<String> sortedIfaces = new ArrayList<>(info.getInterfaces());
            Collections.sort(sortedIfaces);
            for (String iface : sortedIfaces) {
                String ifaceVar = classVarNames.get(iface);
                if (ifaceVar != null) ifacePtrs.add(ifaceVar);
            }
            String ifacesArray = "@refifaces_" + cleanClassName;
            sb.append(ifacesArray).append(" = constant [")
                .append(ifacePtrs.size() + 1).append(" x %ReflectionClass*] [");
            for (String p : ifacePtrs) sb.append("%ReflectionClass* ").append(p).append(", ");
            sb.append("%ReflectionClass* null]\n");

            sb.append(classVarName).append(" = constant %ReflectionClass { i8* ")
                .append(ensureStringConstant(strConsts, className))
                .append(", %ReflectionClass* ").append(superClassPtr)
                .append(", %ReflectionClass** ").append(ifacesArray)
                .append(", %ReflectionMethod** ").append(methodsArray)
                .append(", %ReflectionField** ").append(fieldsArray)
                .append(", %ReflectionConstructor** ").append(ctorsArray)
                .append(", i32 ").append(classNode.getAccess())
                .append(", i32 ").append(objectSize).append(" }\n");
            classPtrs.add("%ReflectionClass* " + classVarName);
        }

        sb.append("@reflect_all_classes = constant [")
            .append(classPtrs.size() + 1).append(" x %ReflectionClass*] [");
        for (String p : classPtrs) sb.append(p).append(", ");
        sb.append("%ReflectionClass* null]\n");

        sb.append(strConsts);
        return sb.toString();
    }

    private void appendNullTerminatedPtrArray(StringBuilder sb, String arrayName, List<String> ptrs) {
        sb.append(arrayName).append(" = constant [")
            .append(ptrs.size() + 1).append(" x i8*] [");
        for (String ptr : ptrs) sb.append(ptr).append(", ");
        sb.append("i8* null]\n");
    }

    private String ensureStringConstant(StringBuilder defsBuffer, String s) {
        if (s == null) return "null";
        if (emittedStringConstants.add(s)) {
            defsBuffer.append(LlvmRuntime.typeStringConstant(s));
        }
        return LlvmRuntime.typeStringGlobalName(s);
    }

    private String emitAdaptorForMethod(String className, MethodReference method) {
        String methodName = method.getName();
        String desc = method.getDescriptor();
        String origFuncName = LlvmRuntime.mangleMethod(className, methodName, desc);
        String adaptorName = "__reflect_adaptor_"
            + LlvmTypeMapper.sanitizeIdentifier(className) + "_"
            + methodName + "_" + desc.replaceAll("[^a-zA-Z0-9_]", "_");

        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);
        Type retType = TypeResolver.descToReturnType(desc);

        MethodNode mn = findMethod(resolver.getClassNode(className), methodName, desc);
        boolean isStatic = mn != null && mn.isStatic();

        StringBuilder sb = new StringBuilder();
        sb.append("define i8* @").append(adaptorName).append("(i8* %obj, i8** %args) {\n");

        List<String> argLoads = new ArrayList<>();
        for (int i = 0; i < paramTypes.size(); i++) {
            Type pt = paramTypes.get(i);
            String ptLlvm = LlvmTypeMapper.toLlvmType(pt);
            String addr = "%arg" + i + "_addr";
            String val = "%arg" + i + "_val";

            sb.append("  ").append(addr)
                .append(" = getelementptr i8*, i8** %args, i32 ").append(i).append("\n");

            if (pt.isReference() || pt.isArray() || pt.isNull() || pt.isBlock()) {
                sb.append("  ").append(val)
                    .append(" = load i8*, i8** ").append(addr).append("\n");
            } else {
                String ptr = "%arg" + i + "_ptr";
                sb.append("  ").append(ptr).append(" = bitcast i8** ")
                    .append(addr).append(" to ").append(ptLlvm).append("*\n");
                sb.append("  ").append(val).append(" = load ")
                    .append(ptLlvm).append(", ").append(ptLlvm).append("* ")
                    .append(ptr).append("\n");
            }
            argLoads.add(val);
        }

        StringBuilder argsCsv = new StringBuilder();
        boolean firstArg = true;
        if (!isStatic) {
            argsCsv.append("i8* %obj");
            firstArg = false;
        }
        for (int i = 0; i < argLoads.size(); i++) {
            if (!firstArg) argsCsv.append(", ");
            argsCsv.append(LlvmTypeMapper.toLlvmType(paramTypes.get(i)))
                .append(" ").append(argLoads.get(i));
            firstArg = false;
        }

        String retLlvm = LlvmTypeMapper.toLlvmType(retType);
        if (retType.isVoid()) {
            sb.append("  call void @").append(origFuncName)
                .append("(").append(argsCsv).append(")\n");
            sb.append("  ret i8* null\n");
        } else {
            sb.append("  %result = call ").append(retLlvm).append(" @")
                .append(origFuncName).append("(").append(argsCsv).append(")\n");
            if (retType.isReference() || retType.isArray()
                || retType.isNull() || retType.isUnknown()) {
                sb.append("  %ret_ptr = bitcast ").append(retLlvm)
                    .append(" %result to i8*\n");
                sb.append("  ret i8* %ret_ptr\n");
            } else {
                sb.append("  %mem = call i8* @malloc(i64 ")
                    .append(getElementSizeOfType(retType)).append(")\n");
                sb.append("  %cast = bitcast i8* %mem to ").append(retLlvm).append("*\n");
                sb.append("  store ").append(retLlvm).append(" %result, ")
                    .append(retLlvm).append("* %cast\n");
                sb.append("  ret i8* %mem\n");
            }
        }
        sb.append("}\n\n");
        return sb.toString();
    }

    private String emitAdaptorForConstructor(String className, MethodReference ctor, int objectSize) {
        String desc = ctor.getDescriptor();
        String origFuncName = LlvmRuntime.mangleMethod(className, "<init>", desc);
        String adaptorName = "__reflect_adaptor_ctor_"
            + LlvmTypeMapper.sanitizeIdentifier(className) + "_"
            + desc.replaceAll("[^a-zA-Z0-9_]", "_");

        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);

        StringBuilder sb = new StringBuilder();
        sb.append("define i8* @").append(adaptorName).append("(i8** %args) {\n");
        sb.append("  %obj = call i8* @malloc(i64 ").append(objectSize).append(")\n");

        String vtableName = getVtableName(className);
        if (vtableName != null) {
            sb.append("  %vtable = bitcast [").append(totalMethods)
                .append(" x i8*]* ").append(vtableName).append(" to i8*\n");
            sb.append("  %vtable_slot = bitcast i8* %obj to i8**\n");
            sb.append("  store i8* %vtable, i8** %vtable_slot\n");
        }

        List<String> argLoads = new ArrayList<>();
        for (int i = 0; i < paramTypes.size(); i++) {
            Type pt = paramTypes.get(i);
            String ptLlvm = LlvmTypeMapper.toLlvmType(pt);
            String addr = "%arg" + i + "_addr";
            String val = "%arg" + i + "_val";
            sb.append("  ").append(addr)
                .append(" = getelementptr i8*, i8** %args, i32 ").append(i).append("\n");
            if (pt.isReference() || pt.isArray() || pt.isNull() || pt.isBlock()) {
                sb.append("  ").append(val).append(" = load i8*, i8** ")
                    .append(addr).append("\n");
            } else {
                String ptr = "%arg" + i + "_ptr";
                sb.append("  ").append(ptr).append(" = bitcast i8** ")
                    .append(addr).append(" to ").append(ptLlvm).append("*\n");
                sb.append("  ").append(val).append(" = load ").append(ptLlvm)
                    .append(", ").append(ptLlvm).append("* ").append(ptr).append("\n");
            }
            argLoads.add(val);
        }

        StringBuilder argsCsv = new StringBuilder();
        argsCsv.append("i8* %obj");
        for (String a : argLoads) {
            argsCsv.append(", ").append(a);
        }
        sb.append("  call void @").append(origFuncName)
            .append("(").append(argsCsv).append(")\n");
        sb.append("  ret i8* %obj\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private MethodNode findMethod(ClassNode classNode, String name, String descriptor) {
        if (classNode == null || classNode.isExternal()) return null;
        for (MethodNode m : classNode.getMethods()) {
            if (m.getName().equals(name) && m.getDescriptor().equals(descriptor)) return m;
        }
        if (classNode.getSuperName() != null && !classNode.getSuperName().equals("java/lang/Object")) {
            ClassNode superNode = resolver.getClassNode(classNode.getSuperName());
            if (superNode != null && !superNode.isExternal()) {
                return findMethod(superNode, name, descriptor);
            }
        }
        return null;
    }

    public Type getFieldType(String className, String fieldName) {
        FieldNode fn = resolver.getField(className, fieldName);
        return fn != null ? fn.getType() : null;
    }
}
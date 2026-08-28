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
import io.github.kubyk01.domain.analyzer.ir.BasicBlock;
import io.github.kubyk01.domain.analyzer.ir.Constant;
import io.github.kubyk01.domain.analyzer.ir.Function;
import io.github.kubyk01.domain.analyzer.ir.Instruction;
import io.github.kubyk01.domain.analyzer.ir.Module;
import io.github.kubyk01.domain.analyzer.ir.Opcode;
import io.github.kubyk01.domain.analyzer.ir.Type;
import io.github.kubyk01.domain.analyzer.ir.Value;
import io.github.kubyk01.domain.analyzer.reflection.ReflectClassInfo;
import io.github.kubyk01.domain.analyzer.reflection.ReflectInfo;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
public class LlvmGlobalEmitter {

    private final Module module;
    private final DependencyResolver resolver;
    private final AliasAnalysisResult aliasResult;
    private final LlvmTypeMapper typeMapper;
    private final ReflectInfo reflectInfo;

    private final Map<String, String> structNames = new HashMap<>();
    private final Map<String, Integer> fieldOffsets = new HashMap<>();
    private final Set<String> emittedStringConstants = new HashSet<>();

    // --- vtable support ---
    private final Map<String, Integer> methodIndex = new HashMap<>();
    private final Map<String, String> vtableNames = new HashMap<>();
    @Getter
    private int totalMethods = 0;

    // --- type info support ---
    private final Map<String, String> typeInfoNames = new HashMap<>();

    public String generateGlobals() {
        return generateStructs() +
            generateStaticFields() +
            generateTypeStringConstants() +
            generateVtables() +
            generateTypeInfo() +
            generateReflectionData();
    }

    private String generateTypeStringConstants() {
        Set<String> names = new HashSet<>();
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
                }
            }
        }
        StringBuilder sb = new StringBuilder("\n; ----- Type name strings -----\n");
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
        StringBuilder sb = new StringBuilder();

        // Define java/lang/Object only if not already defined (e.g., by loop below)
        String objStruct = typeMapper.toLlvmStruct("java/lang/Object");
        if (!structNames.containsKey("java/lang/Object")) {
            sb.append(objStruct).append(" = type { }\n");
            structNames.put("java/lang/Object", objStruct);
        }

        List<ClassNode> allClasses = new ArrayList<>(resolver.getClassMap().values());
        for (ClassNode cls : allClasses) {
            if (cls.isExternal()) continue;
            String structName = typeMapper.toLlvmStruct(cls.getName());
            // Skip if this class already has a struct definition (e.g., Object)
            if (structNames.containsKey(cls.getName())) continue;

            sb.append(structName).append(" = type { ");

            List<FieldNode> allFields = collectAllFields(cls);
            List<String> fieldTypes = new ArrayList<>();
            for (FieldNode field : allFields) {
                Type ft = field.getType();
                fieldTypes.add(typeMapper.toLlvmType(ft));
            }
            sb.append(String.join(", ", fieldTypes));
            sb.append(" }\n");
            structNames.put(cls.getName(), structName);
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
            if (fieldType == null) {
                fieldType = Type.UNKNOWN;
            }
            String llvmType = typeMapper.toLlvmType(fieldType);

            String init;
            if (fieldType.isReference() || fieldType.isArray() || fieldType.isNull() || fieldType.isUnknown()) {
                init = "null";
            } else if (fieldType == Type.FLOAT || fieldType == Type.DOUBLE) {
                init = "0.0";
            } else {
                init = "0";
            }

            String globalName = "gv_" + fullName.replace('.', '_').replace('/', '_');
            sb.append("@").append(globalName).append(" = global ").append(llvmType)
                .append(" ").append(init).append(", align 8\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    public String getStructName(String className) {
        return structNames.getOrDefault(className, typeMapper.toLlvmStruct(className));
    }

    public int getFieldOffset(String className, String fieldName) {
        String key = className + "." + fieldName;
        return fieldOffsets.computeIfAbsent(key, k -> {
            ClassNode cls = resolver.getClassNode(className);
            if (cls == null || cls.isExternal()) {
                return 0;
            }
            List<FieldNode> allFields = collectAllFields(cls);
            int offset = OBJECT_HEADER_SIZE;
            for (FieldNode f : allFields) {
                if (f.getName().equals(fieldName)) {
                    break;
                }
                Type ft = f.getType();
                if (ft.isReference() || ft.isArray()) {
                    offset += 8;
                } else if (ft == Type.LONG || ft == Type.DOUBLE) {
                    offset += 8;
                } else if (ft == Type.INT || ft == Type.FLOAT) {
                    offset += 4;
                } else if (ft == Type.SHORT || ft == Type.CHAR) {
                    offset += 2;
                } else if (ft == Type.BYTE || ft == Type.BOOLEAN) {
                    offset += 1;
                } else {
                    offset += 8;
                }
            }
            return offset;
        });
    }

    public static final int OBJECT_HEADER_SIZE = 8;

    /**
     * Determines whether the class is a system one (JDK or a library)
     * for which vtables are not generated.
     */
    private boolean isSystemClass(String name) {
        return name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("sun/") ||
            name.startsWith("jdk/") || name.startsWith("org/objectweb/asm/") || name.startsWith("picocli/") ||
            name.startsWith("reactor/") || name.startsWith("org/slf4j/") || name.startsWith("org/reactivestreams/") ||
            name.startsWith("io/micrometer/") || name.startsWith("org/junit/") || name.startsWith("com/fasterxml/");
    }

    private String generateVtables() {
        Set<String> signatures = new HashSet<>();
        List<ClassNode> allClasses = new ArrayList<>(resolver.getClassMap().values());
        for (ClassNode cls : allClasses) {
            if (cls.isExternal() || isSystemClass(cls.getName())) continue;
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

        if (totalMethods == 0) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        for (ClassNode cls : allClasses) {
            if (cls.isExternal() || isSystemClass(cls.getName())) continue;
            String className = cls.getName();
            Map<String, MethodNode> methodMap = new HashMap<>();
            collectMethods(cls, methodMap);
            List<String> entries = new ArrayList<>();
            for (String sig : sortedSigs) {
                MethodNode mn = methodMap.get(sig);
                if (mn != null && !mn.isAbstract() && !mn.isNative()) {
                    String funcName = LlvmRuntime.mangleMethod(className, mn.getName(), mn.getDescriptor());
                    String retType = typeMapper.toLlvmType(mn.getReturnType());
                    String paramTypes = buildParamTypes(mn);
                    entries.add("i8* bitcast (" + retType + " (" + paramTypes + ")* @" + funcName + " to i8*)");
                } else {
                    entries.add("i8* null");
                }
            }
            String vtableName = "@vtable_" + className.replace('/', '_');
            sb.append(vtableName).append(" = constant [").append(totalMethods).append(" x i8*] [");
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(entries.get(i));
            }
            sb.append("]\n");
            vtableNames.put(className, vtableName);
        }
        return sb.toString();
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
                    entries.add("i8* bitcast ([" + totalMethods + " x i8*]* " + vtableName + " to i8*)");
                }
            }
            entries.add("i8* null");

            String typeInfoName = "@__type_info_" + className.replace('/', '_');
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
        String name = cls.getName();
        accumulator.add(name);
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
            String sig = mn.getName() + mn.getDescriptor();
            methodMap.put(sig, mn);
        }
    }

    private String buildParamTypes(MethodNode mn) {
        List<Type> params = mn.getParameterTypes();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(typeMapper.toLlvmType(params.get(i)));
        }
        return sb.toString();
    }

    public int getMethodIndex(String sig) {
        return methodIndex.getOrDefault(sig, -1);
    }

    public String getVtableName(String className) {
        return vtableNames.get(className);
    }

    // ------------------------------------------------------------------
    // --------------------- Reflection data support --------------------
    // ------------------------------------------------------------------

    private String generateReflectionData() {
        if (reflectInfo == null || reflectInfo.getAllClasses().isEmpty()) {
            return "; No reflection data\n";
        }

        StringBuilder sb = new StringBuilder();
        StringBuilder strConsts = new StringBuilder();
        sb.append("\n; ----- Reflection data -----\n");

        sb.append("%ReflectionMethod = type { i8*, i8*, i8*, i32 }\n");
        sb.append("%ReflectionField = type { i8*, i8*, i32, i32 }\n");
        sb.append("%ReflectionConstructor = type { i8*, i8*, i32 }\n");
        sb.append("%ReflectionClass = type { i8*, %ReflectionClass*, %ReflectionClass**, %ReflectionMethod**, %ReflectionField**, %ReflectionConstructor**, i32, i32 }\n");

        List<String> classNames = new ArrayList<>(reflectInfo.getAllClasses());
        Collections.sort(classNames);

        Map<String, String> classVarNames = new HashMap<>();
        for (String className : classNames) {
            ClassNode classNode = resolver.getClassNode(className);
            if (classNode == null || classNode.isExternal()) continue;
            classVarNames.put(className, "@refclass_" + className.replace('/', '_'));
        }

        List<String> classPtrs = new ArrayList<>();
        for (String className : classNames) {
            ReflectClassInfo info = reflectInfo.getOrCreateClassInfo(className);
            ClassNode classNode = resolver.getClassNode(className);
            if (classNode == null || classNode.isExternal()) continue;

            String cleanClassName = className.replace('/', '_');
            String classVarName = "@refclass_" + cleanClassName;

            int objectSize = OBJECT_HEADER_SIZE;
            for (FieldNode f : collectAllFields(classNode)) {
                objectSize += sizeOfType(f.getType());
            }

            // --- Methods ---
            List<MethodReference> sortedMethods = new ArrayList<>(info.getMethods());
            sortedMethods.sort(Comparator.comparing(MethodReference::toString));
            List<String> methodPtrs = new ArrayList<>();
            for (MethodReference method : sortedMethods) {
                String methodName = method.getName();
                String desc = method.getDescriptor();
                String mangledSuffix = methodName + "_" + desc.replaceAll("[^a-zA-Z0-9_]", "_");
                String adaptorName = "__reflect_adaptor_" + cleanClassName + "_" + mangledSuffix;

                Function impl = module.getFunction(LlvmRuntime.mangleMethod(className, methodName, desc));
                String adaptorPtr = "i8* null";
                if (impl != null && impl.getEntryBlock() != null) {
                    sb.append(emitAdaptorForMethod(className, method, typeMapper));
                    adaptorPtr = "i8* bitcast (i8* (i8*, i8**)* @" + adaptorName + " to i8*)";
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

            // --- Fields ---
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
                if (desc != null) {
                    offset = getFieldOffset(className, fieldName);
                }

                String fieldVar = "@reffield_" + cleanClassName + "_" + fieldName;
                sb.append(fieldVar).append(" = constant %ReflectionField { i8* ")
                    .append(ensureStringConstant(strConsts, fieldName))
                    .append(", i8* ").append(desc != null ? ensureStringConstant(strConsts, desc) : "null")
                    .append(", i32 ").append(offset)
                    .append(", i32 ").append(modifiers).append(" }\n");
                fieldPtrs.add("i8* bitcast (%ReflectionField* " + fieldVar + " to i8*)");
            }
            String fieldsArray = "@reffields_" + cleanClassName;
            appendNullTerminatedPtrArray(sb, fieldsArray, fieldPtrs);

            // --- Constructors ---
            List<MethodReference> sortedCtors = new ArrayList<>(info.getConstructors());
            sortedCtors.sort(Comparator.comparing(MethodReference::toString));
            List<String> ctorPtrs = new ArrayList<>();
            for (MethodReference ctor : sortedCtors) {
                String desc = ctor.getDescriptor();
                String mangledDesc = desc.replaceAll("[^a-zA-Z0-9_]", "_");
                String adaptorName = "__reflect_adaptor_ctor_" + cleanClassName + "_" + mangledDesc;

                Function impl = module.getFunction(LlvmRuntime.mangleMethod(className, "<init>", desc));
                String adaptorPtr = "i8* null";
                if (impl != null && impl.getEntryBlock() != null) {
                    sb.append(emitAdaptorForConstructor(className, ctor, typeMapper, objectSize));
                    adaptorPtr = "i8* bitcast (i8* (i8**)* @" + adaptorName + " to i8*)";
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

            // --- Superclass and interfaces ---
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
            sb.append(ifacesArray).append(" = constant [").append(ifacePtrs.size() + 1).append(" x %ReflectionClass*] [");
            for (String p : ifacePtrs) {
                sb.append("%ReflectionClass* ").append(p).append(", ");
            }
            sb.append("%ReflectionClass* null]\n");

            // --- The class itself ---
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

        sb.append("@reflect_all_classes = constant [").append(classPtrs.size() + 1).append(" x %ReflectionClass*] [");
        for (String p : classPtrs) {
            sb.append(p).append(", ");
        }
        sb.append("%ReflectionClass* null]\n");

        sb.append(strConsts);
        return sb.toString();
    }

    private void appendNullTerminatedPtrArray(StringBuilder sb, String arrayName, List<String> ptrs) {
        sb.append(arrayName).append(" = constant [").append(ptrs.size() + 1).append(" x i8*] [");
        for (String ptr : ptrs) {
            sb.append(ptr).append(", ");
        }
        sb.append("i8* null]\n");
    }

    private String ensureStringConstant(StringBuilder defsBuffer, String s) {
        if (s == null) return "null";
        if (emittedStringConstants.add(s)) {
            defsBuffer.append(LlvmRuntime.typeStringConstant(s));
        }
        return LlvmRuntime.typeStringGlobalName(s);
    }

    private String emitAdaptorForMethod(String className, MethodReference method, LlvmTypeMapper typeMapper) {
        String methodName = method.getName();
        String desc = method.getDescriptor();
        String origFuncName = LlvmRuntime.mangleMethod(className, methodName, desc);
        String adaptorName = "__reflect_adaptor_" + className.replace('/', '_') + "_"
            + methodName + "_" + desc.replaceAll("[^a-zA-Z0-9_]", "_");

        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);
        Type retType = TypeResolver.descToReturnType(desc);

        // Determine if method is static
        MethodNode mn = findMethod(resolver.getClassNode(className), methodName, desc);
        boolean isStatic = mn != null && mn.isStatic();

        StringBuilder sb = new StringBuilder();
        sb.append("define i8* @").append(adaptorName).append("(i8* %obj, i8** %args) {\n");

        List<String> argLoads = new ArrayList<>();

        for (int i = 0; i < paramTypes.size(); i++) {
            Type pt = paramTypes.get(i);
            String ptLlvm = typeMapper.toLlvmType(pt);

            String addr = "%arg" + i + "_addr";
            String val = "%arg" + i + "_val";

            sb.append("  ")
                .append(addr)
                .append(" = getelementptr i8*, i8** %args, i32 ")
                .append(i)
                .append("\n");

            if (pt.isReference() || pt.isArray() || pt.isNull() || pt.isBlock()) {
                sb.append("  ")
                    .append(val)
                    .append(" = load i8*, i8** ")
                    .append(addr)
                    .append("\n");
            } else {
                String ptr = "%arg" + i + "_ptr";

                sb.append("  ")
                    .append(ptr)
                    .append(" = bitcast i8** ")
                    .append(addr)
                    .append(" to ")
                    .append(ptLlvm)
                    .append("*\n");

                sb.append("  ")
                    .append(val)
                    .append(" = load ")
                    .append(ptLlvm)
                    .append(", ")
                    .append(ptLlvm)
                    .append("* ")
                    .append(ptr)
                    .append("\n");
            }

            argLoads.add(val);
        }

        // Build LLVM call arguments, including their types.
        StringBuilder argsCsv = new StringBuilder();
        boolean firstArg = true;

        if (!isStatic) {
            argsCsv.append("i8* %obj");
            firstArg = false;
        }

        for (int i = 0; i < argLoads.size(); i++) {
            if (!firstArg) {
                argsCsv.append(", ");
            }

            argsCsv.append(typeMapper.toLlvmType(paramTypes.get(i)))
                .append(" ")
                .append(argLoads.get(i));

            firstArg = false;
        }

        String retLlvm = typeMapper.toLlvmType(retType);
        if (retType.isVoid()) {
            sb.append("  call void @").append(origFuncName).append("(").append(argsCsv).append(")\n");
            sb.append("  ret i8* null\n");
        } else {
            sb.append("  %result = call ").append(retLlvm).append(" @").append(origFuncName)
                .append("(").append(argsCsv).append(")\n");
            if (retType.isReference() || retType.isArray() || retType.isNull() || retType.isUnknown()) {
                sb.append("  %ret_ptr = bitcast ").append(retLlvm).append(" %result to i8*\n");
                sb.append("  ret i8* %ret_ptr\n");
            } else {
                sb.append("  %mem = call i8* @malloc(i64 ").append(sizeOfType(retType)).append(")\n");
                sb.append("  %cast = bitcast i8* %mem to ").append(retLlvm).append("*\n");
                sb.append("  store ").append(retLlvm).append(" %result, ").append(retLlvm).append("* %cast\n");
                sb.append("  ret i8* %mem\n");
            }
        }
        sb.append("}\n\n");
        return sb.toString();
    }

    private String emitAdaptorForConstructor(String className, MethodReference ctor, LlvmTypeMapper typeMapper, int objectSize) {
        String desc = ctor.getDescriptor();
        String origFuncName = LlvmRuntime.mangleMethod(className, "<init>", desc);
        String adaptorName = "__reflect_adaptor_ctor_" + className.replace('/', '_') + "_"
            + desc.replaceAll("[^a-zA-Z0-9_]", "_");

        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);

        StringBuilder sb = new StringBuilder();
        sb.append("define i8* @").append(adaptorName).append("(i8** %args) {\n");

        sb.append("  %obj = call i8* @malloc(i64 ").append(objectSize).append(")\n");

        String vtableName = getVtableName(className);
        if (vtableName != null) {
            sb.append("  %vtable = bitcast [").append(totalMethods).append(" x i8*]* ")
                .append(vtableName).append(" to i8*\n");
            sb.append("  %vtable_slot = bitcast i8* %obj to i8**\n");
            sb.append("  store i8* %vtable, i8** %vtable_slot\n");
        }

        List<String> argLoads = new ArrayList<>();
        for (int i = 0; i < paramTypes.size(); i++) {
            Type pt = paramTypes.get(i);
            String ptLlvm = typeMapper.toLlvmType(pt);
            String addr = "%arg" + i + "_addr";
            String val = "%arg" + i + "_val";
            sb.append("  ").append(addr).append(" = getelementptr i8*, i8** %args, i32 ").append(i).append("\n");

            // Analogous correct load
            if (pt.isReference() || pt.isArray() || pt.isNull() || pt.isBlock()) {
                sb.append("  ").append(val).append(" = load i8*, i8** ").append(addr).append("\n");
            } else {
                String ptr = "%arg" + i + "_ptr";
                sb.append("  ").append(ptr).append(" = bitcast i8** ").append(addr).append(" to ").append(ptLlvm).append("*\n");
                sb.append("  ").append(val).append(" = load ").append(ptLlvm).append(", ").append(ptLlvm).append("* ").append(ptr).append("\n");
            }
            argLoads.add(val);
        }

        // Build argument list: prepend %obj
        StringBuilder argsCsv = new StringBuilder();
        argsCsv.append("i8* %obj");
        for (String a : argLoads) {
            argsCsv.append(", ");
            argsCsv.append(a);
        }
        sb.append("  call void @").append(origFuncName).append("(").append(argsCsv).append(")\n");

        sb.append("  ret i8* %obj\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private int sizeOfType(Type type) {
        if (type == Type.BOOLEAN || type == Type.BYTE) return 1;
        if (type == Type.SHORT || type == Type.CHAR) return 2;
        if (type == Type.INT || type == Type.FLOAT) return 4;
        if (type == Type.LONG || type == Type.DOUBLE) return 8;
        return 8;
    }

    private MethodNode findMethod(ClassNode classNode, String name, String descriptor) {
        if (classNode == null || classNode.isExternal()) {
            return null;
        }
        for (MethodNode m : classNode.getMethods()) {
            if (m.getName().equals(name) && m.getDescriptor().equals(descriptor)) {
                return m;
            }
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
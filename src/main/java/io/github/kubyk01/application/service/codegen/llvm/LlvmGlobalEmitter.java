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
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.IndirectBranchTerminator;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.InvokeDynamicInfo;
import io.github.kubyk01.domain.ir.LookupSwitchTerminator;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.TableSwitchTerminator;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import lombok.RequiredArgsConstructor;
import org.objectweb.asm.Opcodes;

import java.util.*;
import java.nio.charset.StandardCharsets;

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
    private final List<String> stringLiteralPool = new ArrayList<>();
    private final Set<String> deferredStrings = new LinkedHashSet<>();

    private final Set<String> emittedVtableThunks = new HashSet<>();

    public int getLiteralPoolSize() {
        return stringLiteralPool.size();
    }

    public static final class VtableLayout {
        public final List<String> slots = new ArrayList<>();
        public final Map<String, Integer> slotBySignature = new HashMap<>();
    }

    private final Map<String, VtableLayout> classLayouts = new HashMap<>();
    private final Map<String, VtableLayout> interfaceLayouts = new HashMap<>();
    private final Set<String> classLayoutInProgress = new HashSet<>();
    private final Set<String> ifaceLayoutInProgress = new HashSet<>();

    private final Map<String, Integer> interfaceIds = new HashMap<>();
    private int nextInterfaceId = 0;
    private int totalInterfaces = 0;
    private boolean layoutsBuilt = false;

    private final Map<String, String> vtableNames = new HashMap<>();
    private final Map<String, Integer> vtableLengths = new HashMap<>();
    private final Map<String, Integer> lambdaVtableLengths = new HashMap<>();

    private final Map<String, String> typeInfoNames = new HashMap<>();

    private final Map<String, String> extraStructs = new LinkedHashMap<>();
    private final Map<String, String> extraVtables = new LinkedHashMap<>();

    private static final class LambdaInfo {
        String lambdaId;
        String lambdaClassName;
        String samInterface;
        String samSig;
        String adaptorName;
        List<Type> capturedTypes;
    }

    private final Map<String, LambdaInfo> lambdaRegistry = new LinkedHashMap<>();

    public void addExtraStruct(String name, String definition) {
        extraStructs.put(name, definition);
    }

    public void addExtraVtable(String name, String content) {
        extraVtables.put(name, content);
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

    public void prepareLayouts() {
        resolver.forceLoadSystemClass("java/lang/String");

        if (layoutsBuilt) return;

        List<ClassNode> all = new ArrayList<>(resolver.getClassMap().values());
        all.sort(Comparator.comparing(ClassNode::getName));

        for (ClassNode cn : all) {
            if (cn.isExternal()) continue;
            if (cn.isInterface()) continue;
            getOrBuildClassLayout(cn.getName());
        }
        getOrBuildClassLayout("java/lang/Object");

        Set<String> needed = new LinkedHashSet<>();

        for (ClassNode cn : all) {
            if (cn.isExternal()) continue;
            if (cn.isInterface()) continue;
            collectAllInterfacesRec(cn, needed);
        }

        for (Function func : module.getFunctions()) {
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst.getOpcode() != Opcode.INTERFACE_CALL) continue;
                    if (inst.getOperands().size() < 2) continue;
                    Value callee = inst.getOperands().get(1);
                    if (!(callee instanceof Constant c)) continue;
                    if (!c.getType().isReference()) continue;
                    String full = c.getValue().toString();
                    int dotIdx = full.lastIndexOf('.');
                    if (dotIdx <= 0) continue;
                    String owner = full.substring(0, dotIdx);

                    ClassNode ownerNode = resolver.getClassNode(owner);
                    if (ownerNode == null || !ownerNode.isInterface()) continue;
                    needed.add(owner);
                }
            }
        }

        needed.addAll(interfaceIds.keySet());

        List<String> ifaceNames = new ArrayList<>(needed);
        Collections.sort(ifaceNames);
        interfaceIds.clear();
        nextInterfaceId = 0;
        for (String name : ifaceNames) {
            ClassNode cn = resolver.getClassNode(name);
            if (cn == null || !cn.isInterface()) continue;
            interfaceIds.put(name, nextInterfaceId++);
            getOrBuildInterfaceLayout(name);
        }
        totalInterfaces = nextInterfaceId;
        layoutsBuilt = true;
    }

    public void prepareMethodIndex() {
        prepareLayouts();
    }

    private VtableLayout getOrBuildClassLayout(String className) {
        VtableLayout cached = classLayouts.get(className);
        if (cached != null) return cached;

        if (classLayoutInProgress.contains(className)) {
            return new VtableLayout();
        }
        classLayoutInProgress.add(className);

        VtableLayout layout = new VtableLayout();
        classLayouts.put(className, layout);

        ClassNode cn = resolver.getClassNode(className);
        if (cn == null) {
            classLayoutInProgress.remove(className);
            return layout;
        }

        if (!"java/lang/Object".equals(className)) {
            String superName = cn.getSuperName();
            if (superName == null || superName.equals(className)) superName = "java/lang/Object";
            VtableLayout parent = getOrBuildClassLayout(superName);
            layout.slots.addAll(parent.slots);
            layout.slotBySignature.putAll(parent.slotBySignature);
        }

        if (!cn.isExternal()) {
            for (MethodNode mn : cn.getMethods()) {
                if (!isVtableVirtual(mn)) continue;
                String sig = mn.getName() + mn.getDescriptor();
                if (!layout.slotBySignature.containsKey(sig)) {
                    layout.slotBySignature.put(sig, layout.slots.size());
                    layout.slots.add(sig);
                }
            }
        }

        classLayoutInProgress.remove(className);
        return layout;
    }

    private VtableLayout getOrBuildInterfaceLayout(String ifaceName) {
        VtableLayout cached = interfaceLayouts.get(ifaceName);
        if (cached != null) return cached;

        if (ifaceLayoutInProgress.contains(ifaceName)) {
            return new VtableLayout();
        }

        ClassNode cn = resolver.getClassNode(ifaceName);
        if (cn == null || !cn.isInterface()) {
            return new VtableLayout();
        }

        ifaceLayoutInProgress.add(ifaceName);

        VtableLayout layout = new VtableLayout();
        interfaceLayouts.put(ifaceName, layout);

        for (String parent : cn.getInterfaces()) {
            if (parent.equals(ifaceName)) continue;
            VtableLayout parentLayout = getOrBuildInterfaceLayout(parent);
            for (String sig : parentLayout.slots) {
                if (!layout.slotBySignature.containsKey(sig)) {
                    layout.slotBySignature.put(sig, layout.slots.size());
                    layout.slots.add(sig);
                }
            }
        }

        if (!cn.isExternal()) {
            for (MethodNode mn : cn.getMethods()) {
                if (!isVtableVirtual(mn)) continue;
                String sig = mn.getName() + mn.getDescriptor();
                if (!layout.slotBySignature.containsKey(sig)) {
                    layout.slotBySignature.put(sig, layout.slots.size());
                    layout.slots.add(sig);
                }
            }
        }

        ifaceLayoutInProgress.remove(ifaceName);
        return layout;
    }

    private boolean isVtableVirtual(MethodNode mn) {
        int access = mn.getAccess();
        if ((access & Opcodes.ACC_STATIC) != 0) return false;
        if ((access & Opcodes.ACC_PRIVATE) != 0) return false;
        String n = mn.getName();
        return !n.equals("<init>") && !n.equals("<clinit>");
    }

    public int getVirtualSlot(String owner, String name, String desc) {
        prepareLayouts();
        VtableLayout layout = getOrBuildClassLayout(owner);
        Integer slot = layout.slotBySignature.get(name + desc);
        return slot != null ? slot : -1;
    }

    public int getInterfaceMethodSlot(String iface, String name, String desc) {
        prepareLayouts();
        if (!interfaceIds.containsKey(iface)) return -1;
        VtableLayout layout = getOrBuildInterfaceLayout(iface);
        Integer slot = layout.slotBySignature.get(name + desc);
        return slot != null ? slot : -1;
    }

    public int getInterfaceId(String iface) {
        prepareLayouts();
        Integer id = interfaceIds.get(iface);
        return id != null ? id : -1;
    }

    public int getTotalInterfaces() {
        prepareLayouts();
        return totalInterfaces;
    }

    public String getVtableName(String className) {
        return vtableNames.get(className);
    }

    public int getVtableLength(String className) {
        return vtableLengths.getOrDefault(className, -1);
    }

    public int getLambdaVtableLength(String lambdaId) {
        return lambdaVtableLengths.getOrDefault(lambdaId, -1);
    }

    public String getStructName(String className) {
        return structNames.getOrDefault(className, LlvmTypeMapper.toLlvmStruct(className));
    }

    public String getTypeInfoName(String className) {
        return typeInfoNames.get(className);
    }

    public String generateGlobals() {
        prepareLayouts();
        return generateStructs()
            + generateStaticFields()
            + generateVtables()
            + generateTypeStringConstants()
            + generateStringLiterals()
            + generateTypeInfo()
            + generateReflectionData();
    }

    private static final class ResolvedFn {
        final String name;
        final String type;

        ResolvedFn(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    private ResolvedFn resolveVtableEntry(String className, String sig) {
        int parenIdx = sig.indexOf('(');
        if (parenIdx <= 0) return null;
        String name = sig.substring(0, parenIdx);
        String desc = sig.substring(parenIdx);

        String[] foundOwner = new String[1];
        MethodNode mn = resolver.findMethodInHierarchy(className, name, desc, foundOwner);
        if (mn == null || mn.isAbstract()) return null;

        String owner = foundOwner[0] != null ? foundOwner[0] : className;
        String baseName = LlvmRuntime.mangleMethod(owner, name, desc);
        String nativeName = "__jnative_" + baseName;

        String funcName;
        if (module.getFunction(baseName) != null) {
            funcName = baseName;
        } else if (module.getFunction(nativeName) != null) {
            funcName = nativeName;
        } else if (mn.isNative()) {
            Type retType = mn.getReturnType();
            List<Type> paramTypes = mn.getParameterTypes();

            List<Type> allParams = new ArrayList<>();
            allParams.add(Type.reference(owner));
            allParams.addAll(paramTypes);

            Function nf = new Function(nativeName, retType);
            for (int i = 0; i < allParams.size(); i++) {
                nf.addParameter(new Parameter(allParams.get(i), i));
            }
            module.addFunction(nf);
            funcName = nativeName;
        } else {
            return null;
        }

        String ret = LlvmTypeMapper.toLlvmType(mn.getReturnType());
        StringBuilder params = new StringBuilder();
        params.append(LlvmTypeMapper.toLlvmType(Type.reference(owner)));
        for (Type pt : mn.getParameterTypes()) {
            params.append(", ").append(LlvmTypeMapper.toLlvmType(pt));
        }
        return new ResolvedFn(funcName, ret + " (" + params + ")*");
    }

    private String vtableSlotEntry(String className,
                                   String ifaceOrNull,
                                   String sig,
                                   ResolvedFn fn,
                                   StringBuilder sb) {
        if (fn != null) {
            return "i8* bitcast (" + fn.type + " @" + fn.name + " to i8*)";
        }

        String slotId = LlvmTypeMapper.sanitizeIdentifier(className)
            + (ifaceOrNull == null
            ? ""
            : "_" + LlvmTypeMapper.sanitizeIdentifier(ifaceOrNull))
            + "_" + LlvmTypeMapper.sanitizeIdentifier(sig);

        String thunkName = "__jnative_vtable_missing_" + slotId;

        if (!emittedVtableThunks.add(thunkName)) {
            return "i8* bitcast (i8* (...)* @" + thunkName + " to i8*)";
        }

        String message;
        if (ifaceOrNull == null) {
            message = "class '" + className + "', slot '" + sig + "'";
        } else {
            message = "interface '" + ifaceOrNull
                + "' implemented by '" + className
                + "', slot '" + sig + "'";
        }

        String msgRef = ensureStringConstantPtr(sb, message);

        sb.append("define i8* @").append(thunkName).append("(...) {\n")
            .append("entry:\n")
            .append("  call void @__jnative_unresolved_slot(i8* ")
            .append(msgRef).append(")\n")
            .append("  unreachable\n")
            .append("}\n");

        return "i8* bitcast (i8* (...)* @" + thunkName + " to i8*)";
    }

    private String generateVtables() {
        prepareLayouts();
        StringBuilder sb = new StringBuilder();
        sb.append("\n; ----- Vtables (hierarchy-local slots) -----\n");

        List<ClassNode> allClasses = new ArrayList<>(resolver.getClassMap().values());
        allClasses.sort(Comparator.comparing(ClassNode::getName));

        for (String iface : interfaceLayouts.keySet()) {
            String vtableName = "@vtable_" + LlvmTypeMapper.sanitizeIdentifier(iface);
            vtableNames.put(iface, vtableName);
            String ifaceNameRef = ensureStringConstantPtr(sb, iface);
            sb.append(vtableName)
                .append(" = constant %JNativeVTable { i8** null, %JNativeIfaceMap* null, i8* ")
                .append(ifaceNameRef).append(" }\n");
        }

        for (ClassNode cls : allClasses) {
            if (cls.isExternal()) continue;
            if (cls.isInterface()) continue;

            String className = cls.getName();
            VtableLayout layout = getOrBuildClassLayout(className);
            int length = Math.max(layout.slots.size(), 1);

            List<String> entries = new ArrayList<>(length);
            for (int i = 0; i < length; i++) entries.add("i8* null");

            for (int i = 0; i < layout.slots.size(); i++) {
                String sig = layout.slots.get(i);
                ResolvedFn fn = resolveVtableEntry(className, sig);
                entries.set(i, vtableSlotEntry(className, null, sig, fn, sb));
            }

            String methodsName = "@vtable_methods_" + LlvmTypeMapper.sanitizeIdentifier(className);
            sb.append(methodsName).append(" = private constant [")
                .append(length).append(" x i8*] [");
            for (int i = 0; i < length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(entries.get(i));
            }
            sb.append("]\n");

            vtableLengths.put(className, length);
        }

        for (ClassNode cls : allClasses) {
            if (cls.isExternal() || cls.isInterface()) continue;
            String className = cls.getName();

            Set<String> ifaces = collectAllInterfaces(cls);
            Map<String, String> itableNames = new LinkedHashMap<>();
            Map<String, Integer> itableLens = new LinkedHashMap<>();
            Map<String, Integer> itableIds = new LinkedHashMap<>();

            for (String iface : ifaces) {
                Integer ifaceId = interfaceIds.get(iface);
                if (ifaceId == null) continue;

                VtableLayout ifaceLayout = getOrBuildInterfaceLayout(iface);
                int len = Math.max(ifaceLayout.slots.size(), 1);

                List<String> entries = new ArrayList<>(len);
                for (int i = 0; i < len; i++) entries.add("i8* null");

                for (int i = 0; i < ifaceLayout.slots.size(); i++) {
                    String sig = ifaceLayout.slots.get(i);
                    ResolvedFn fn = resolveVtableEntry(className, sig);
                    entries.set(i, vtableSlotEntry(className, iface, sig, fn, sb));
                }

                String itableName = "@itable_"
                    + LlvmTypeMapper.sanitizeIdentifier(className) + "_"
                    + LlvmTypeMapper.sanitizeIdentifier(iface);
                sb.append(itableName).append(" = private constant [")
                    .append(len).append(" x i8*] [");
                for (int i = 0; i < len; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(entries.get(i));
                }
                sb.append("]\n");

                itableNames.put(iface, itableName);
                itableLens.put(iface, len);
                itableIds.put(iface, ifaceId);
            }

            List<String> sortedIfaces = new ArrayList<>(itableIds.keySet());
            sortedIfaces.sort(Comparator.comparingInt(itableIds::get));

            String ifacemapName = "@ifacemap_" + LlvmTypeMapper.sanitizeIdentifier(className);

            if (!sortedIfaces.isEmpty()) {
                String entriesArrayName = "@ifacemap_entries_"
                    + LlvmTypeMapper.sanitizeIdentifier(className);
                sb.append(entriesArrayName).append(" = private constant [")
                    .append(sortedIfaces.size()).append(" x %JNativeIfaceMapEntry] [");
                for (int i = 0; i < sortedIfaces.size(); i++) {
                    if (i > 0) sb.append(", ");
                    String iface = sortedIfaces.get(i);
                    sb.append("%JNativeIfaceMapEntry { i32 ").append(itableIds.get(iface))
                        .append(", i8** bitcast ([").append(itableLens.get(iface))
                        .append(" x i8*]* ").append(itableNames.get(iface))
                        .append(" to i8**) }");
                }
                sb.append("]\n");

                sb.append(ifacemapName).append(" = constant %JNativeIfaceMap { i32 ")
                    .append(sortedIfaces.size()).append(", %JNativeIfaceMapEntry* ")
                    .append(entriesArrayName).append(" }\n");
            } else {
                sb.append(ifacemapName)
                    .append(" = constant %JNativeIfaceMap { i32 0, %JNativeIfaceMapEntry* null }\n");
            }

            int methodLen = vtableLengths.getOrDefault(className, 1);
            String methodsName = "@vtable_methods_" + LlvmTypeMapper.sanitizeIdentifier(className);

            String vtableName = "@vtable_" + LlvmTypeMapper.sanitizeIdentifier(className);
            String classNameRef = ensureStringConstantPtr(sb, className);
            sb.append(vtableName).append(" = constant %JNativeVTable {\n")
                .append("  i8** bitcast ([").append(methodLen).append(" x i8*]* ")
                .append(methodsName).append(" to i8**),\n")
                .append("  %JNativeIfaceMap* ").append(ifacemapName).append(",\n")
                .append("  i8* ").append(classNameRef).append("\n")
                .append("}\n");

            vtableNames.put(className, vtableName);
        }

        return sb.toString();
    }

    private Set<String> collectAllInterfaces(ClassNode cls) {
        Set<String> result = new LinkedHashSet<>();
        if (cls != null) collectAllInterfacesRec(cls, result);
        return result;
    }

    private void collectAllInterfacesRec(ClassNode cls, Set<String> out) {
        if (cls == null) return;
        for (String i : cls.getInterfaces()) {
            if (out.add(i)) {
                ClassNode in = resolver.getClassNode(i);
                if (in != null) collectAllInterfacesRec(in, out);
            }
        }
        String superName = cls.getSuperName();
        if (superName != null && !superName.equals(cls.getName())) {
            ClassNode sn = resolver.getClassNode(superName);
            if (sn != null) collectAllInterfacesRec(sn, out);
        }
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

    public String registerLambdaClass(String lambdaId,
                                      String samInterface,
                                      String samSig,
                                      String adaptorName,
                                      List<Type> capturedTypes) {
        prepareLayouts();

        if (samInterface == null
            || samInterface.isEmpty()
            || samInterface.indexOf('(') >= 0
            || samInterface.indexOf(')') >= 0
            || samInterface.charAt(0) == '[') {
            throw new IllegalStateException(
                "registerLambdaClass called with an invalid SAM interface name '"
                    + samInterface + "' for lambda " + lambdaId);
        }

        ClassNode samNode = resolver.getClassNode(samInterface);
        if (samNode != null && !samNode.isExternal() && !samNode.isInterface()) {
            throw new IllegalStateException(
                "registerLambdaClass: SAM '" + samInterface
                    + "' resolves to a class, not an interface (lambdaId="
                    + lambdaId + ")");
        }

        String lambdaClassName = "__Lambda_" + lambdaId;
        if (lambdaRegistry.containsKey(lambdaId)) {
            return vtableNames.get(lambdaClassName);
        }

        if (!interfaceIds.containsKey(samInterface)) {
            interfaceIds.put(samInterface, nextInterfaceId++);
            totalInterfaces = nextInterfaceId;
        }
        getOrBuildInterfaceLayout(samInterface);

        registerLambdaStruct(lambdaId, capturedTypes);

        LambdaInfo info = new LambdaInfo();
        info.lambdaId = lambdaId;
        info.lambdaClassName = lambdaClassName;
        info.samInterface = samInterface;
        info.samSig = samSig;
        info.adaptorName = adaptorName;
        info.capturedTypes = new ArrayList<>(capturedTypes);
        lambdaRegistry.put(lambdaId, info);

        String vtableName = "@vtable_" + LlvmTypeMapper.sanitizeIdentifier(lambdaClassName);
        vtableNames.put(lambdaClassName, vtableName);
        return vtableName;
    }

    public String emitLambdaVtables() {
        prepareLayouts();
        StringBuilder sb = new StringBuilder();
        sb.append("\n; ----- Lambda vtables -----\n");

        VtableLayout objectLayout = getOrBuildClassLayout("java/lang/Object");
        int methodLen = Math.max(objectLayout.slots.size(), 1);

        for (LambdaInfo info : lambdaRegistry.values()) {
            String lambdaClassName = info.lambdaClassName;
            String samInterface = info.samInterface;

            List<String> methodEntries = new ArrayList<>(methodLen);
            for (int i = 0; i < methodLen; i++) methodEntries.add("i8* null");
            for (int i = 0; i < objectLayout.slots.size(); i++) {
                String sig = objectLayout.slots.get(i);
                ResolvedFn fn = resolveVtableEntry("java/lang/Object", sig);
                methodEntries.set(i, vtableSlotEntry("java/lang/Object", null, sig, fn, sb));
            }

            String methodsName = "@vtable_methods_"
                + LlvmTypeMapper.sanitizeIdentifier(lambdaClassName);
            sb.append(methodsName).append(" = private constant [")
                .append(methodLen).append(" x i8*] [");
            for (int i = 0; i < methodLen; i++) {
                if (i > 0) sb.append(", ");
                sb.append(methodEntries.get(i));
            }
            sb.append("]\n");

            VtableLayout samLayout = getOrBuildInterfaceLayout(samInterface);
            int samLen = Math.max(samLayout.slots.size(), 1);
            Integer samSlotObj = samLayout.slotBySignature.get(info.samSig);
            if (samSlotObj == null) samSlotObj = 0;
            int samSlot = samSlotObj;

            List<String> itableEntries = new ArrayList<>(samLen);
            for (int i = 0; i < samLen; i++) {
                String sig = i < samLayout.slots.size()
                    ? samLayout.slots.get(i)
                    : "<unknown>";
                itableEntries.add(vtableSlotEntry(lambdaClassName, samInterface, sig, null, sb));
            }
            itableEntries.set(samSlot,
                "i8* bitcast (i8* (i8*, ...)* @" + info.adaptorName + " to i8*)");

            String itableName = "@itable_"
                + LlvmTypeMapper.sanitizeIdentifier(lambdaClassName) + "_"
                + LlvmTypeMapper.sanitizeIdentifier(samInterface);
            sb.append(itableName).append(" = private constant [")
                .append(samLen).append(" x i8*] [");
            for (int i = 0; i < samLen; i++) {
                if (i > 0) sb.append(", ");
                sb.append(itableEntries.get(i));
            }
            sb.append("]\n");

            Integer samIfaceId = interfaceIds.get(samInterface);
            if (samIfaceId == null) {
                throw new IllegalStateException(
                    "Lambda '" + info.lambdaId + "' SAM interface '" + samInterface
                        + "' has no global interface id");
            }

            String ifacemapEntriesName = "@ifacemap_entries_"
                + LlvmTypeMapper.sanitizeIdentifier(lambdaClassName);
            String ifacemapName = "@ifacemap_"
                + LlvmTypeMapper.sanitizeIdentifier(lambdaClassName);

            sb.append(ifacemapEntriesName)
                .append(" = private constant [1 x %JNativeIfaceMapEntry] [")
                .append("%JNativeIfaceMapEntry { i32 ").append(samIfaceId)
                .append(", i8** bitcast ([").append(samLen).append(" x i8*]* ")
                .append(itableName).append(" to i8**) }]\n");

            sb.append(ifacemapName)
                .append(" = constant %JNativeIfaceMap { i32 1, %JNativeIfaceMapEntry* ")
                .append(ifacemapEntriesName).append(" }\n");

            String vtableName = vtableNames.get(lambdaClassName);
            String lambdaNameRef = ensureStringConstantPtr(sb, lambdaClassName);
            sb.append(vtableName).append(" = constant %JNativeVTable {\n")
                .append("  i8** bitcast ([").append(methodLen).append(" x i8*]* ")
                .append(methodsName).append(" to i8**),\n")
                .append("  %JNativeIfaceMap* ").append(ifacemapName).append(",\n")
                .append("  i8* ").append(lambdaNameRef).append("\n")
                .append("}\n");

            vtableLengths.put(lambdaClassName, methodLen);
            lambdaVtableLengths.put(info.lambdaId, methodLen);
        }
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
            sb.append(objStruct).append(" = type { i8* }\n");
            structNames.put("java/lang/Object", objStruct);
        }

        List<ClassNode> allClasses = new ArrayList<>(resolver.getClassMap().values());
        allClasses.sort(Comparator.comparing(ClassNode::getName));
        for (ClassNode cls : allClasses) {
            if (cls.isExternal()) continue;
            if (structNames.containsKey(cls.getName())) continue;

            String structName = LlvmTypeMapper.toLlvmStruct(cls.getName());
            sb.append(structName).append(" = type { i8*");

            List<FieldNode> allFields = collectInstanceFields(cls);
            for (FieldNode field : allFields) {
                sb.append(", ").append(LlvmTypeMapper.toLlvmType(field.getType()));
            }
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

    private List<FieldNode> collectInstanceFields(ClassNode cls) {
        List<FieldNode> result = new ArrayList<>();
        if (cls.getSuperName() != null && !cls.getSuperName().equals("java/lang/Object")) {
            ClassNode superNode = resolver.getClassNode(cls.getSuperName());
            if (superNode != null && !superNode.isExternal()) {
                result.addAll(collectInstanceFields(superNode));
            }
        }
        for (FieldNode f : cls.getFields()) {
            if ((f.getAccess() & Opcodes.ACC_STATIC) == 0) {
                result.add(f);
            }
        }
        return result;
    }

    private String generateStaticFields() {
        StringBuilder sb = new StringBuilder();
        Map<String, PointsToSet> staticFields = aliasResult.getGraph().getStaticFieldPointsToMap();
        List<String> sorted = new ArrayList<>(staticFields.keySet());
        Collections.sort(sorted);
        for (String fullName : sorted) {
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

    private String generateTypeStringConstants() {
        Set<String> names = new LinkedHashSet<>();
        names.add("java/lang/Object");

        for (ClassNode cn : resolver.getClassMap().values()) {
            if (cn.isExternal()) continue;
            names.add(cn.getName());
        }

        for (Function func : module.getFunctions()) {
            String fn = func.getName();
            if (fn != null && !fn.isEmpty()) names.add(fn);
        }

        for (Function func : module.getFunctions()) {
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    collectStringConstantsFromInstruction(inst, names);
                }
                Terminator term = block.getTerminator();
                if (term != null) collectStringConstantsFromTerminator(term, names);
            }
        }

        StringBuilder sb = new StringBuilder("\n; ----- String / type-name constants -----\n");
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        for (String name : sorted) {
            if (emittedStringConstants.add(name)) {
                sb.append(LlvmRuntime.typeStringConstant(name));
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    public void registerDeferredString(String s) {
        if (s != null && !s.isEmpty()) {
            deferredStrings.add(s);
        }
    }

    public String generateDeferredStringConstants() {
        if (deferredStrings.isEmpty()) return "";

        List<String> sorted = new ArrayList<>(deferredStrings);
        Collections.sort(sorted);

        StringBuilder body = new StringBuilder();
        boolean any = false;
        for (String s : sorted) {
            if (emittedStringConstants.add(s)) {
                body.append(LlvmRuntime.typeStringConstant(s));
                any = true;
            }
        }
        if (!any) return "";

        return "\n; ----- Deferred string constants (NPE register names) -----\n"
            + body
            + "\n";
    }

    private void collectStringConstantsFromInstruction(Instruction inst, Set<String> names) {
        Opcode op = inst.getOpcode();
        for (Value v : inst.getOperands()) {
            if (v instanceof Constant c
                && c.getType().isReference()
                && c.getValue() instanceof String s) {
                names.add(s);
            }
        }
        if (op == Opcode.INVOKEDYNAMIC
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
            if (!seg.isEmpty()) names.add(seg.toString());
            for (int i = 1; i < dynInfo.bootstrapArgs().length; i++) {
                Object a = dynInfo.bootstrapArgs()[i];
                if (a != null) names.add(String.valueOf(a));
            }
        }
    }

    private void addIfStringConstant(Value v, Set<String> names) {
        if (v instanceof Constant c
            && c.getType().isReference()
            && c.getValue() instanceof String s) {
            names.add(s);
        }
    }

    private void collectStringConstantsFromTerminator(Terminator term, Set<String> names) {
        if (term instanceof ReturnTerminator rt) addIfStringConstant(rt.getValue(), names);
        else if (term instanceof ThrowTerminator tt) addIfStringConstant(tt.getException(), names);
        else if (term instanceof CondBranchTerminator cbt) addIfStringConstant(cbt.getCondition(), names);
        else if (term instanceof LookupSwitchTerminator lst) addIfStringConstant(lst.getKey(), names);
        else if (term instanceof TableSwitchTerminator tst) addIfStringConstant(tst.getKey(), names);
        else if (term instanceof IndirectBranchTerminator ibt) addIfStringConstant(ibt.getTargetBlock(), names);
    }

    private Set<String> collectJavaStringLiterals() {
        Set<String> result = new LinkedHashSet<>();
        for (Function func : module.getFunctions()) {
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    for (Value v : inst.getOperands()) {
                        if (v instanceof Constant c
                            && c.getType().isReference()
                            && "java/lang/String".equals(c.getType().getClassName())
                            && c.getValue() instanceof String s) {
                            result.add(s);
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
                            char ch = recipe.charAt(i);
                            if (ch == '\u0001' || ch == '\u0002') {
                                if (!seg.isEmpty()) {
                                    result.add(seg.toString());
                                    seg.setLength(0);
                                }
                            } else {
                                seg.append(ch);
                            }
                        }
                        if (!seg.isEmpty()) result.add(seg.toString());
                        for (int i = 1; i < dynInfo.bootstrapArgs().length; i++) {
                            Object a = dynInfo.bootstrapArgs()[i];
                            if (a instanceof String s) result.add(s);
                        }
                    }
                }
                Terminator term = block.getTerminator();
                if (term != null) {
                    for (Value v : terminatorOperands(term)) {
                        if (v instanceof Constant c
                            && c.getType().isReference()
                            && "java/lang/String".equals(c.getType().getClassName())
                            && c.getValue() instanceof String s) {
                            result.add(s);
                        }
                    }
                }
            }
        }
        result.add("");
        return result;
    }

    private static List<Value> terminatorOperands(Terminator term) {
        List<Value> out = new ArrayList<>();
        if (term instanceof ReturnTerminator rt && rt.getValue() != null) out.add(rt.getValue());
        else if (term instanceof ThrowTerminator tt && tt.getException() != null) out.add(tt.getException());
        else if (term instanceof CondBranchTerminator cbt) out.add(cbt.getCondition());
        else if (term instanceof LookupSwitchTerminator lst) out.add(lst.getKey());
        else if (term instanceof TableSwitchTerminator tst) out.add(tst.getKey());
        else if (term instanceof IndirectBranchTerminator ibt) out.add(ibt.getTargetBlock());
        return out;
    }

    private String generateStringLiterals() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n; ----- String literal objects -----\n");
        stringLiteralPool.clear();

        String vtableName = vtableNames.get("java/lang/String");
        if (vtableName == null) {
            throw new IllegalStateException(
                "vtable_java_lang_String must be emitted before string literals");
        }

        List<String> literals = new ArrayList<>(collectJavaStringLiterals());
        Collections.sort(literals);

        for (String s : literals) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            int len = bytes.length;
            int totalLen = 8 + len + 1;

            StringBuilder escaped = new StringBuilder();
            escaped.append(String.format("\\%02X\\%02X\\%02X\\%02X",
                len & 0xFF, (len >> 8) & 0xFF, (len >> 16) & 0xFF, (len >> 24) & 0xFF));
            escaped.append("\\01\\00\\00\\00");
            for (byte b : bytes) {
                int v = b & 0xFF;
                switch (v) {
                    case '\\' -> escaped.append("\\5C");
                    case '"' -> escaped.append("\\22");
                    case '\n' -> escaped.append("\\0A");
                    case '\r' -> escaped.append("\\0D");
                    case '\t' -> escaped.append("\\09");
                    default -> {
                        if (v < 0x20 || v > 0x7E) escaped.append(String.format("\\%02X", v));
                        else escaped.append((char) v);
                    }
                }
            }
            escaped.append("\\00");

            String safe = LlvmRuntime.stringIdSuffix(s);
            String bytesGlobal = "strbytes_" + safe;
            String objGlobal = "jstr_" + safe;

            sb.append("@").append(bytesGlobal)
                .append(" = private unnamed_addr constant [").append(totalLen)
                .append(" x i8] c\"").append(escaped).append("\", align 4\n");

            sb.append("@").append(objGlobal)
                .append(" = global %struct.java_lang_String {\n")
                .append("  i8* bitcast (%JNativeVTable* ").append(vtableName).append(" to i8*),\n")
                .append("  i8* bitcast ([").append(totalLen).append(" x i8]* @").append(bytesGlobal).append(" to i8*),\n")
                .append("  i8 0,\n")
                .append("  i32 0,\n")
                .append("  i1 false\n")
                .append("}, align 8\n");

            stringLiteralPool.add(objGlobal);
        }

        int n = stringLiteralPool.size();
        sb.append("@__jnative_literal_pool = constant [").append(n).append(" x i8*] [");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append("i8* bitcast (%struct.java_lang_String* @").append(stringLiteralPool.get(i)).append(" to i8*)");
        }
        sb.append("], align 8\n");
        sb.append("@__jnative_literal_pool_size = constant i32 ").append(n).append("\n");
        return sb.toString();
    }

    private String generateTypeInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n; ----- Type info tables -----\n");

        List<ClassNode> allClasses = new ArrayList<>(resolver.getClassMap().values());
        allClasses.sort(Comparator.comparing(ClassNode::getName));

        Map<String, Set<String>> instancesOf = new HashMap<>();

        for (ClassNode cls : allClasses) {
            if (cls.isExternal()) continue;
            if (cls.isInterface()) continue;

            String className = cls.getName();
            String classVtable = vtableNames.get(className);
            if (classVtable == null) continue;

            Set<String> supertypes = new LinkedHashSet<>();
            collectSuperclasses(cls, supertypes);
            collectInterfaces(cls, supertypes);

            for (String supertype : supertypes) {
                instancesOf.computeIfAbsent(supertype, k -> new LinkedHashSet<>())
                    .add(classVtable);
            }
        }

        for (ClassNode cls : allClasses) {
            if (cls.isExternal()) continue;
            if (cls.getName().startsWith("[")) continue;

            String className = cls.getName();
            String typeInfoName = "@__type_info_" + LlvmTypeMapper.sanitizeIdentifier(className);
            typeInfoNames.put(className, typeInfoName);

            Set<String> entries = instancesOf.getOrDefault(className, Collections.emptySet());
            List<String> sorted = new ArrayList<>(entries);
            Collections.sort(sorted);

            sb.append(typeInfoName).append(" = constant [")
                .append(sorted.size() + 1).append(" x i8*] [");
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("i8* bitcast (%JNativeVTable* ")
                    .append(sorted.get(i)).append(" to i8*)");
            }
            if (!sorted.isEmpty()) sb.append(", ");
            sb.append("i8* null]\n");
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
        if (cls == null) return;

        for (String iface : cls.getInterfaces()) {
            if (accumulator.add(iface)) {
                ClassNode ifaceNode = resolver.getClassNode(iface);
                if (ifaceNode != null && !ifaceNode.isExternal()) {
                    collectInterfaces(ifaceNode, accumulator);
                }
            }
        }

        String superName = cls.getSuperName();
        if (superName != null
            && !superName.equals(cls.getName())
            && !superName.equals("java/lang/Object")) {
            ClassNode superNode = resolver.getClassNode(superName);
            if (superNode != null && !superNode.isExternal()) {
                collectInterfaces(superNode, accumulator);
            }
        }
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

        List<FieldNode> allFields = collectInstanceFields(cls);
        int offset = OBJECT_HEADER_SIZE;
        int foundOffset = -1;
        for (FieldNode f : allFields) {
            Type ft = f.getType();
            int align = fieldAlignment(ft);
            offset = (offset + align - 1) & -align;
            if (f.getName().equals(fieldName)) {
                foundOffset = offset;
            }
            offset += fieldSize(ft);
        }

        if (foundOffset < 0) {
            throw new IllegalStateException(
                "Field not found in class hierarchy: " + className + "." + fieldName);
        }
        fieldOffsets.put(key, foundOffset);
        return foundOffset;
    }

    private static int fieldAlignment(Type ft) {
        if (ft == null) return 8;
        if (ft.isReference() || ft.isArray()) return 8;
        if (ft == Type.LONG || ft == Type.DOUBLE) return 8;
        if (ft == Type.INT || ft == Type.FLOAT) return 4;
        if (ft == Type.SHORT || ft == Type.CHAR) return 2;
        if (ft == Type.BYTE || ft == Type.BOOLEAN) return 1;
        return 8;
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

    public Type getFieldType(String className, String fieldName) {
        FieldNode fn = resolver.getField(className, fieldName);
        return fn != null ? fn.getType() : null;
    }

    private String generateReflectionData() {
        StringBuilder sb = new StringBuilder();
        StringBuilder strConsts = new StringBuilder();
        sb.append("\n; ----- Reflection data -----\n");

        sb.append("%ReflectionMethod = type { i8*, i8*, i8*, i32 }\n");
        sb.append("%ReflectionField = type { i8*, i8*, i32, i32 }\n");
        sb.append("%ReflectionConstructor = type { i8*, i8*, i32 }\n");
        sb.append("%ReflectionClass = type { %JNativeVTable*, i8*, %ReflectionClass*, %ReflectionClass**, "
            + "%ReflectionMethod**, %ReflectionField**, %ReflectionConstructor**, i32, i32 }\n");

        String classVtableRef = vtableNames.get("java/lang/Class");
        if (classVtableRef == null) {
            throw new IllegalStateException(
                "vtable_java_lang_Class must be emitted before reflection data "
                    + "(Class literal objects need a valid %JNativeVTable* in word[0])");
        }

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
            if (classNode == null) continue;
            classVarNames.put(className,
                "@refclass_" + LlvmTypeMapper.sanitizeIdentifier(className));
        }

        List<String> classPtrs = new ArrayList<>();
        for (String className : classNames) {
            ReflectClassInfo info = reflectInfo.getOrCreateClassInfo(className);
            ClassNode classNode = resolver.getClassNode(className);
            if (classNode == null) continue;

            if (classNode.isExternal() || resolver.getClassBytes(className) == null) {
                resolver.forceLoadSystemClass(className);
                classNode = resolver.getClassNode(className);
                if (classNode == null) continue;
            }

            if (!classNode.isExternal()) {
                Map<String, FieldNode> mostDerivedByName = new LinkedHashMap<>();
                for (FieldNode f : collectInstanceFields(classNode)) {
                    if ((f.getAccess() & Opcodes.ACC_STATIC) != 0) continue;
                    mostDerivedByName.put(f.getName(), f);
                }
                for (FieldNode f : mostDerivedByName.values()) {
                    info.addField(new FieldReference(
                        className, f.getName(), f.getDescriptor()));
                }
            }

            String cleanClassName = LlvmTypeMapper.sanitizeIdentifier(className);
            String classVarName = "@refclass_" + cleanClassName;

            int objectSize = OBJECT_HEADER_SIZE;
            for (FieldNode f : collectInstanceFields(classNode)) {
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
            {
                Map<String, FieldReference> byName = new LinkedHashMap<>();
                for (FieldReference fr : sortedFields) {
                    FieldReference existing = byName.get(fr.getName());
                    if (existing == null) {
                        byName.put(fr.getName(), fr);
                        continue;
                    }
                    FieldNode mostDerived = resolver.getField(className, fr.getName());
                    if (mostDerived != null
                        && mostDerived.getDescriptor() != null
                        && mostDerived.getDescriptor().equals(fr.getDescriptor())) {
                        byName.put(fr.getName(), fr);
                    }
                }
                sortedFields = new ArrayList<>(byName.values());
            }
            sortedFields.sort(Comparator.comparing(FieldReference::toString));
            List<String> fieldPtrs = new ArrayList<>();
            for (FieldReference field : sortedFields) {
                String fieldName = field.getName();
                String desc = field.getDescriptor();
                int modifiers = 0;
                FieldNode fn = resolver.getField(className, fieldName);
                if (fn != null) {
                    modifiers = fn.getAccess();
                    if (desc == null) desc = fn.getDescriptor();
                }

                int offset = 0;
                if (fn != null && desc != null && (modifiers & Opcodes.ACC_STATIC) == 0) {
                    offset = getFieldOffset(className, fieldName);
                }

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

            sb.append(classVarName).append(" = constant %ReflectionClass { %JNativeVTable* ")
                .append(classVtableRef)
                .append(", i8* ")
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

    private String ensureStringConstantPtr(StringBuilder defsBuffer, String s) {
        if (s == null) return "null";
        if (emittedStringConstants.add(s)) {
            defsBuffer.append(LlvmRuntime.typeStringConstant(s));
        }
        int len = LlvmRuntime.typeStringArrayLength(s);
        String g = LlvmRuntime.typeStringGlobalName(s);
        return "getelementptr inbounds ([" + len + " x i8], [" + len + " x i8]* "
            + g + ", i32 0, i32 0)";
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
                sb.append("  %mem = call i8* @calloc(i64 1, i64 ")
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
        sb.append("  %obj = call i8* @calloc(i64 1, i64 ").append(objectSize).append(")\n");

        String vtableName = getVtableName(className);
        if (vtableName != null) {
            sb.append("  %vtable = bitcast %JNativeVTable* ").append(vtableName)
                .append(" to i8*\n");
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
        for (int i = 0; i < argLoads.size(); i++) {
            argsCsv.append(", ")
                .append(LlvmTypeMapper.toLlvmType(paramTypes.get(i)))
                .append(" ").append(argLoads.get(i));
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

    public void ensureInterfaceRegistered(String ifaceName) {
        if (ifaceName == null || ifaceName.isEmpty()) return;

        ClassNode cn = resolver.getClassNode(ifaceName);
        if (cn == null || cn.isExternal() || cn.getMethods().isEmpty()) {
            resolver.forceLoadSystemClass(ifaceName);
            cn = resolver.getClassNode(ifaceName);
        }
        if (cn == null || !cn.isInterface()) return;

        prepareLayouts();
        interfaceLayouts.remove(ifaceName);

        if (!interfaceIds.containsKey(ifaceName)) {
            interfaceIds.put(ifaceName, nextInterfaceId++);
            totalInterfaces = nextInterfaceId;
        }
        getOrBuildInterfaceLayout(ifaceName);
    }

    public void ensureClassLayoutBuilt(String className) {
        if (className == null || className.isEmpty()) return;

        ClassNode cn = resolver.getClassNode(className);
        if (cn == null || cn.isExternal()) {
            resolver.forceLoadSystemClass(className);
            cn = resolver.getClassNode(className);
        }
        if (cn == null || cn.isInterface()) return;

        prepareLayouts();
        classLayouts.remove(className);
        getOrBuildClassLayout(className);
    }
}
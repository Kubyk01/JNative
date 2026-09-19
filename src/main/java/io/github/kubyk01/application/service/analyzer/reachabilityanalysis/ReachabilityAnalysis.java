package io.github.kubyk01.application.service.analyzer.reachabilityanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldReference;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.analyzer.reachability.ReachabilityMetadata;
import io.github.kubyk01.domain.analyzer.reflection.ReflectClassInfo;
import io.github.kubyk01.domain.analyzer.reflection.ReflectInfo;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.IndirectBranchTerminator;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.LookupSwitchTerminator;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.TableSwitchTerminator;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.Value;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
@RequiredArgsConstructor
public class ReachabilityAnalysis {

    private final DependencyResolver resolver;

    @Getter
    private final Set<String> reachableClasses = new HashSet<>();

    private final Set<String> userReachedClasses = new HashSet<>();

    private final Set<String> clinitProcessed = new HashSet<>();

    @Getter
    private final Set<MethodReference> reachableMethods = new HashSet<>();

    @Getter
    private final ReflectInfo reflectInfo = new ReflectInfo();

    private final Deque<MethodReference> worklist = new ArrayDeque<>();

    private final Set<MethodReference> userReachableMethods = new HashSet<>();

    @Getter
    private final Map<MethodReference, Set<MethodReference>> callGraph = new HashMap<>();

    @Getter
    private final Set<String> instantiatedClasses = new HashSet<>();

    public void addInstantiatedClass(String className, boolean fromUser) {
        if (className == null || className.isEmpty()) return;
        instantiatedClasses.add(className);
        addClassWithInit(className, fromUser);
    }

    public void addClassLiteral(String className, boolean fromUser) {
        if (className == null || className.isEmpty()) return;
        reflectInfo.getOrCreateClassInfo(className);
        addClass(className, fromUser);
    }

    public void registerClassLiterals(Module module) {
        if (module == null) return;
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    for (Value op : inst.getOperands()) {
                        registerClassLiteralFromValue(op);
                    }
                }
                Terminator term = block.getTerminator();
                if (term != null) {
                    for (Value op : terminatorOperandsOf(term)) {
                        registerClassLiteralFromValue(op);
                    }
                }
            }
        }
    }

    private void registerClassLiteralFromValue(Value v) {
        if (!(v instanceof Constant c)) return;
        if (!c.getType().isReference()) return;
        if (!"java/lang/Class".equals(c.getType().getClassName())) return;
        Object val = c.getValue();
        if (!(val instanceof String name) || name.isEmpty()) return;
        // No `fromUser` flag here: the IR no longer carries the "user-reachable"
        // distinction, and by the time this method is called the reflectInfo
        // set is what matters for the emitter. Class literals are always
        // passive with respect to <clinit>, so `false` is correct.
        addClassLiteral(name, false);
    }

    private static List<Value> terminatorOperandsOf(Terminator term) {
        List<Value> out = new ArrayList<>();
        if (term instanceof ReturnTerminator rt && rt.getValue() != null) out.add(rt.getValue());
        else if (term instanceof ThrowTerminator tt && tt.getException() != null) out.add(tt.getException());
        else if (term instanceof CondBranchTerminator cbt) out.add(cbt.getCondition());
        else if (term instanceof LookupSwitchTerminator lst) out.add(lst.getKey());
        else if (term instanceof TableSwitchTerminator tst) out.add(tst.getKey());
        else if (term instanceof IndirectBranchTerminator ibt) out.add(ibt.getTargetBlock());
        return out;
    }

    public void applyMetadata(ReachabilityMetadata metadata) {
        if (metadata == null) return;

        for (ReachabilityMetadata.ReflectClass rc : metadata.getReflectClasses()) {
            String className = rc.getName().replace('.', '/');
            addClassWithInit(className, true);
            ReflectClassInfo info = reflectInfo.getOrCreateClassInfo(className);
            ClassNode classNode = resolver.getClassNode(className);
            if (classNode != null) {
                info.setSuperName(classNode.getSuperName());
                info.getInterfaces().addAll(classNode.getInterfaces());
            }
            if (classNode != null && !classNode.isExternal()) {
                if (rc.isAllDeclaredMethods() || rc.isAllPublicMethods()) {
                    for (MethodNode m : classNode.getMethods()) {
                        if (!m.getName().equals("<init>") && !m.getName().equals("<clinit>")) {
                            MethodReference ref = new MethodReference(className, m.getName(), m.getDescriptor());
                            info.addMethod(ref);
                            addMethod(ref, true);
                        }
                    }
                } else {
                    for (String methodSig : rc.getMethods()) {
                        int parenIdx = methodSig.indexOf('(');
                        String methodName = parenIdx > 0 ? methodSig.substring(0, parenIdx) : methodSig;
                        for (MethodNode m : classNode.getMethods()) {
                            if (m.getName().equals(methodName) && !m.getName().equals("<init>")) {
                                MethodReference ref = new MethodReference(className, m.getName(), m.getDescriptor());
                                info.addMethod(ref);
                                addMethod(ref, true);
                            }
                        }
                    }
                }
                if (rc.isAllDeclaredFields() || rc.isAllPublicFields()) {
                    for (io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode f : classNode.getFields()) {
                        FieldReference ref = new FieldReference(className, f.getName(), f.getDescriptor());
                        info.addField(ref);
                    }
                } else {
                    for (String fieldName : rc.getFields()) {
                        FieldReference ref = new FieldReference(className, fieldName, null);
                        info.addField(ref);
                    }
                }
                if (rc.isAllDeclaredConstructors() || rc.isAllPublicConstructors()) {
                    for (MethodNode m : classNode.getMethods()) {
                        if (m.getName().equals("<init>")) {
                            MethodReference ref = new MethodReference(className, "<init>", m.getDescriptor());
                            info.addConstructor(ref);
                            addMethod(ref, true);
                        }
                    }
                } else {
                    for (String ctorSig : rc.getConstructors()) {
                        for (MethodNode m : classNode.getMethods()) {
                            if (m.getName().equals("<init>") && m.getDescriptor().equals(ctorSig)) {
                                MethodReference ref = new MethodReference(className, "<init>", m.getDescriptor());
                                info.addConstructor(ref);
                                addMethod(ref, true);
                            }
                        }
                    }
                }
            }
        }
        for (ReachabilityMetadata.ProxyInterface pi : metadata.getProxyInterfaces()) {
            for (String iface : pi.getInterfaces()) {
                String className = iface.replace('.', '/');
                addClassWithInit(className, true);
                ClassNode classNode = resolver.getClassNode(className);
                if (classNode != null && !classNode.isExternal()) {
                    for (MethodNode m : classNode.getMethods()) {
                        addMethod(new MethodReference(className, m.getName(), m.getDescriptor()), true);
                    }
                }
            }
        }
        for (ReachabilityMetadata.JniClass jc : metadata.getJniClasses()) {
            String className = jc.getName().replace('.', '/');
            addClassWithInit(className, true);
        }
        log.info("Applied reachability metadata: {} reflect classes, {} proxy interfaces, {} jni classes",
            metadata.getReflectClasses().size(),
            metadata.getProxyInterfaces().size(),
            metadata.getJniClasses().size());
    }

    public void analyzeFromEntry(String entryClass, String entryMethod, String entryDescriptor) {
        String internalClass = entryClass.replace('.', '/');
        if (entryDescriptor == null) {
            entryDescriptor = "([Ljava/lang/String;)V";
        }
        MethodReference entryPoint = new MethodReference(internalClass, entryMethod, entryDescriptor);
        addMethod(entryPoint, true);

        while (!worklist.isEmpty()) {
            MethodReference current = worklist.poll();
            processMethod(current);
        }

        log.info("Reachability analysis complete. Reachable classes: {}, methods: {}",
            reachableClasses.size(), reachableMethods.size());
        log.info("User-reached classes: {}, user-reachable methods: {}",
            userReachedClasses.size(), userReachableMethods.size());
        log.info("Instantiated classes: {}", instantiatedClasses.size());
    }

    private MethodNode findMethodInHierarchy(ClassNode classNode, String name, String desc, String[] foundClassName) {
        if (classNode == null) {
            return null;
        }

        for (MethodNode m : classNode.getMethods()) {
            if (m.getName().equals(name) && m.getDescriptor().equals(desc)) {
                if (foundClassName != null) foundClassName[0] = classNode.getName();
                return m;
            }
        }

        for (MethodNode m : classNode.getMethods()) {
            if (m.getName().equals(name) && m.isPolymorphicSignature()) {
                if (foundClassName != null) foundClassName[0] = classNode.getName();
                return m;
            }
        }

        String superName = classNode.getSuperName();
        if (superName != null && !superName.equals(classNode.getName())) {
            ClassNode superNode = resolver.getClassNode(superName);
            if (superNode != null && superNode != classNode) {
                MethodNode result = findMethodInHierarchy(superNode, name, desc, foundClassName);
                if (result != null) return result;
            }
        } else if (superName == null && !"java/lang/Object".equals(classNode.getName())) {
            ClassNode objectNode = resolver.getClassNode("java/lang/Object");
            if (objectNode != null && objectNode != classNode) {
                MethodNode result = findMethodInHierarchy(objectNode, name, desc, foundClassName);
                if (result != null) return result;
            }
        }

        for (String iface : classNode.getInterfaces()) {
            if (iface.equals(classNode.getName())) continue;
            ClassNode ifaceNode = resolver.getClassNode(iface);
            if (ifaceNode != null && ifaceNode != classNode) {
                MethodNode result = findMethodInHierarchy(ifaceNode, name, desc, foundClassName);
                if (result != null) return result;
            }
        }

        return null;
    }

    private void processMethod(MethodReference ref) {
        String owner = ref.getOwner();
        String name = ref.getName();
        String desc = ref.getDescriptor();

        boolean userReachable = userReachableMethods.contains(ref);
        addClass(owner, userReachable);

        ClassNode classNode = resolver.getClassNode(owner);
        if (classNode.isExternal()) {
            resolver.forceLoadSystemClass(owner);
            classNode = resolver.getClassNode(owner);
        }

        String[] actualOwnerHolder = new String[1];
        MethodNode method = findMethodInHierarchy(classNode, name, desc, actualOwnerHolder);

        if (method == null) {
            log.warn("Method not found in class {}: {}{}", owner, name, desc);
            return;
        }

        String actualOwner = actualOwnerHolder[0];
        if (!actualOwner.equals(owner)) {
            MethodReference actualRef = new MethodReference(actualOwner, name, desc);
            if (!reachableMethods.contains(actualRef)) {
                boolean isUser = userReachableMethods.contains(ref);
                addMethod(actualRef, isUser);
            }
            return;
        }

        if (method.isAbstract() || method.isNative()) {
            return;
        }

        boolean reachableFromUser = userReachableMethods.contains(ref);
        parseBytecode(actualOwner, name, desc, reachableFromUser, ref);
    }

    private void parseBytecode(String owner, String name, String desc,
                               boolean reachableFromUser, MethodReference caller) {
        byte[] bytes = resolver.getClassBytes(owner);
        if (bytes == null) {
            log.warn("No bytecode available for class {}", owner);
            return;
        }

        MethodReference currentMethod = new MethodReference(owner, name, desc);
        MethodBytecodeVisitor visitor = new MethodBytecodeVisitor(
            resolver,
            reachableClasses,
            reflectInfo,
            this,
            currentMethod,
            reachableFromUser,
            caller
        );
        visitor.parse(bytes);
    }

    void addClass(String className, boolean fromUser) {
        if (className == null || className.isEmpty()) return;
        reachableClasses.add(className);
        if (fromUser) {
            userReachedClasses.add(className);
        }
    }

    /**
     * Marks a class as initialized. Enqueues its {@code <clinit>} for
     * analysis when the class actually declares one and its bytecode is
     * available. No class — user, system, or external — is skipped on the
     * basis of being "external": the reachability walker needs to see
     * every instruction that can affect program state, including the
     * static initializer bodies of library classes.
     */
    void addClassWithInit(String className, boolean fromUser) {
        if (className == null || className.isEmpty()) return;

        reachableClasses.add(className);
        if (fromUser) {
            userReachedClasses.add(className);
        }

        if (clinitProcessed.contains(className)) return;
        clinitProcessed.add(className);

        ClassNode cn = resolver.getClassNode(className);
        if (cn == null || cn.isInterface()) return;

        if (cn.isExternal()) {
            resolver.forceLoadSystemClass(className);
            cn = resolver.getClassNode(className);
            if (cn == null || cn.isInterface()) return;
        }

        boolean hasClinit = false;
        for (MethodNode mn : cn.getMethods()) {
            if (mn.getName().equals("<clinit>")) {
                hasClinit = true;
                break;
            }
        }
        if (!hasClinit) return;

        // Without bytecode we can neither analyse nor codegen <clinit>.
        // Reflection-loaded classes have method signatures but no
        // instruction stream; such classes must not be enqueued.
        if (resolver.getClassBytes(className) == null) return;

        addMethod(new MethodReference(className, "<clinit>", "()V"), fromUser);
    }

    public void triggerClinit(String className, boolean fromUser) {
        addClassWithInit(className, fromUser);
    }

    boolean isSystemClassName(String className) {
        String dot = className.replace('/', '.');
        return dot.startsWith("java.") ||
            dot.startsWith("javax.") ||
            dot.startsWith("sun.") ||
            dot.startsWith("jdk.") ||
            dot.startsWith("org.objectweb.asm.") ||
            dot.startsWith("picocli.") ||
            dot.startsWith("reactor.") ||
            dot.startsWith("org.slf4j.") ||
            dot.startsWith("org.reactivestreams.") ||
            dot.startsWith("io.micrometer.") ||
            dot.startsWith("org.junit.") ||
            dot.startsWith("com.fasterxml.");
    }

    void addMethod(MethodReference ref, boolean isUser) {
        boolean isSystem = isSystemClassName(ref.getOwner());

        if (!isUser && isSystem) {
            addClass(ref.getOwner(), false);
            return;
        }

        if (reachableMethods.add(ref)) {
            worklist.add(ref);
            if (isUser) {
                userReachableMethods.add(ref);
            }
            addClass(ref.getOwner(), isUser);
        } else if (isUser && !userReachableMethods.contains(ref)) {
            userReachableMethods.add(ref);
            addClass(ref.getOwner(), true);
        }
    }

    void addMethodWithContext(MethodReference ref, boolean isUser, MethodReference caller) {
        if (caller != null) {
            callGraph.computeIfAbsent(caller, k -> new HashSet<>()).add(ref);
        }
        addMethod(ref, isUser);
    }

    void addTypeFromDescriptor(String desc, boolean fromUser) {
        if (desc == null) return;
        if (desc.startsWith("L") && desc.endsWith(";")) {
            addClass(desc.substring(1, desc.length() - 1), fromUser);
        } else if (desc.startsWith("[")) {
            String elem = desc;
            while (elem.startsWith("[")) elem = elem.substring(1);
            if (elem.startsWith("L") && elem.endsWith(";")) {
                addClass(elem.substring(1, elem.length() - 1), fromUser);
            }
        }
    }
}
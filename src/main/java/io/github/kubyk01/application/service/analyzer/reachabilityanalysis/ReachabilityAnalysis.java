package io.github.kubyk01.application.service.analyzer.reachabilityanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldReference;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.analyzer.reachability.ReachabilityMetadata;
import io.github.kubyk01.domain.analyzer.reflection.ReflectClassInfo;
import io.github.kubyk01.domain.analyzer.reflection.ReflectInfo;
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

    public void addInstantiatedClass(String className) {
        if (className != null && !className.isEmpty()) {
            if (instantiatedClasses.add(className)) {
                addClassWithInit(className);
            }
        }
    }

    public void applyMetadata(ReachabilityMetadata metadata) {
        if (metadata == null) return;
        for (ReachabilityMetadata.ReflectClass rc : metadata.getReflectClasses()) {
            String className = rc.getName().replace('.', '/');
            addClassWithInit(className);
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
                addClassWithInit(className);
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
            addClassWithInit(className);
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
        log.info("User-reachable methods: {}", userReachableMethods.size());
        log.info("Instantiated classes: {}", instantiatedClasses.size());
    }

    private void processMethod(MethodReference ref) {
        String owner = ref.getOwner();
        String name = ref.getName();
        String desc = ref.getDescriptor();

        addClassWithInit(owner);

        ClassNode classNode = resolver.getClassNode(owner);
        if (classNode.isExternal()) {
            log.debug("External method: {} – skipping body analysis", ref);
            return;
        }

        MethodNode method = findMethod(classNode, name, desc);
        if (method == null) {
            log.warn("Method not found in class {}: {}{}", owner, name, desc);
            return;
        }

        if (method.isAbstract() || method.isNative()) {
            return;
        }

        boolean reachableFromUser = userReachableMethods.contains(ref) || !isSystemClassName(owner);
        parseBytecode(owner, name, desc, reachableFromUser, ref);
    }

    private MethodNode findMethod(ClassNode classNode, String name, String desc) {
        for (MethodNode m : classNode.getMethods()) {
            if (m.getName().equals(name) && m.getDescriptor().equals(desc)) {
                return m;
            }
        }
        if (classNode.getSuperName() != null) {
            ClassNode superNode = resolver.getClassNode(classNode.getSuperName());
            if (superNode != null && !superNode.isExternal()) {
                return findMethod(superNode, name, desc);
            }
        }
        return null;
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

    void addClassWithInit(String className) {
        if (className == null || className.isEmpty()) return;
        if (reachableClasses.add(className)) {
            ClassNode cn = resolver.getClassNode(className);
            if (cn != null && !cn.isExternal() && !cn.isInterface()) {
                if (!isSystemClassName(className)) {
                    for (MethodNode mn : cn.getMethods()) {
                        if (mn.getName().equals("<clinit>")) {
                            addMethod(new MethodReference(className, "<clinit>", "()V"), false);
                            break;
                        }
                    }
                }
            }
        }
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
        if (reachableMethods.add(ref)) {
            worklist.add(ref);
            if (isUser) {
                userReachableMethods.add(ref);
            }
            addClassWithInit(ref.getOwner());
        }
    }

    void addMethodWithContext(MethodReference ref, boolean isUser, MethodReference caller) {
        if (caller != null) {
            callGraph.computeIfAbsent(caller, k -> new HashSet<>()).add(ref);
        }
        if (isUser) {
            addMethod(ref, true);
        } else {
            if (reachableMethods.add(ref)) {
                worklist.add(ref);
                addClassWithInit(ref.getOwner());
            }
        }
    }

    void addTypeFromDescriptor(String desc) {
        if (desc == null) return;
        if (desc.startsWith("L") && desc.endsWith(";")) {
            addClassWithInit(desc.substring(1, desc.length() - 1));
        } else if (desc.startsWith("[")) {
            String elem = desc;
            while (elem.startsWith("[")) elem = elem.substring(1);
            if (elem.startsWith("L") && elem.endsWith(";")) {
                addClassWithInit(elem.substring(1, elem.length() - 1));
            }
        }
    }
}
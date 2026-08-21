package io.github.kubyk01.application.service.analyzer.reachabilityanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.analyzer.reachability.ReachabilityMetadata;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.*;

import java.util.*;

@Slf4j
@RequiredArgsConstructor
public class ReachabilityAnalysis {

    private final DependencyResolver resolver;
    @Getter
    private final Set<String> reachableClasses = new HashSet<>();
    @Getter
    private final Set<MethodReference> reachableMethods = new HashSet<>();
    private final Deque<MethodReference> worklist = new ArrayDeque<>();

    public void applyMetadata(ReachabilityMetadata metadata) {
        if (metadata == null) return;

        for (ReachabilityMetadata.ReflectClass rc : metadata.getReflectClasses()) {
            String className = rc.getName().replace('.', '/');
            reachableClasses.add(className);
            ClassNode classNode = resolver.getClassNode(className);
            if (classNode != null && !classNode.isExternal()) {
                if (rc.isAllDeclaredMethods() || rc.isAllPublicMethods()) {
                    for (MethodNode m : classNode.getMethods()) {
                        addMethod(new MethodReference(className, m.getName(), m.getDescriptor()));
                    }
                } else {
                    for (String methodSig : rc.getMethods()) {
                        int parenIdx = methodSig.indexOf('(');
                        String methodName = parenIdx > 0 ? methodSig.substring(0, parenIdx) : methodSig;
                        for (MethodNode m : classNode.getMethods()) {
                            if (m.getName().equals(methodName)) {
                                addMethod(new MethodReference(className, m.getName(), m.getDescriptor()));
                            }
                        }
                    }
                }
            }
        }
        for (ReachabilityMetadata.ProxyInterface pi : metadata.getProxyInterfaces()) {
            for (String iface : pi.getInterfaces()) {
                String className = iface.replace('.', '/');
                reachableClasses.add(className);
                ClassNode classNode = resolver.getClassNode(className);
                if (classNode != null && !classNode.isExternal()) {
                    for (MethodNode m : classNode.getMethods()) {
                        addMethod(new MethodReference(className, m.getName(), m.getDescriptor()));
                    }
                }
            }
        }
        // JNI classes are also reachable via native calls
        for (ReachabilityMetadata.JniClass jc : metadata.getJniClasses()) {
            String className = jc.getName().replace('.', '/');
            reachableClasses.add(className);
        }
        // Resources do not affect class reachability

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
        MethodReference start = new MethodReference(internalClass, entryMethod, entryDescriptor);
        addMethod(start);

        while (!worklist.isEmpty()) {
            MethodReference current = worklist.poll();
            processMethod(current);
        }

        log.info("Reachability analysis complete. Reachable classes: {}, methods: {}",
            reachableClasses.size(), reachableMethods.size());
    }

    private void processMethod(MethodReference ref) {
        String owner = ref.getOwner();
        String name = ref.getName();
        String desc = ref.getDescriptor();

        reachableClasses.add(owner);

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

        parseBytecode(owner, name, desc);
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

    private void parseBytecode(String owner, String name, String desc) {
        byte[] bytes = resolver.getClassBytes(owner);
        if (bytes == null) {
            log.warn("No bytecode available for class {}", owner);
            return;
        }
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String mName, String mDesc,
                                             String signature, String[] exceptions) {
                if (mName.equals(name) && mDesc.equals(desc)) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mName,
                                                    String mDesc, boolean isInterface) {
                            if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE) {
                                Set<String> targets = new HashSet<>();
                                targets.add(owner);
                                targets.addAll(resolver.getSubclasses(owner));
                                for (String target : targets) {
                                    addMethod(new MethodReference(target, mName, mDesc));
                                }
                            } else {
                                addMethod(new MethodReference(owner, mName, mDesc));
                            }
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String fName,
                                                   String fDesc) {
                            reachableClasses.add(owner);
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            reachableClasses.add(type);
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof Type t) {
                                if (t.getSort() == Type.OBJECT) {
                                    reachableClasses.add(t.getInternalName());
                                }
                            }
                        }

                        @Override
                        public void visitMultiANewArrayInsn(String desc, int dims) {
                            Type t = Type.getType(desc);
                            if (t.getSort() == Type.OBJECT) {
                                reachableClasses.add(t.getInternalName());
                            }
                        }

                        @Override
                        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                            if (type != null) {
                                reachableClasses.add(type);
                            }
                        }
                    };
                }
                return null;
            }
        }, ClassReader.SKIP_DEBUG);
    }

    public void addMethod(MethodReference ref) {
        if (reachableMethods.add(ref)) {
            worklist.add(ref);
            reachableClasses.add(ref.getOwner());
        }
    }

}
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
    @Getter
    private final ReflectInfo reflectInfo = new ReflectInfo();
    private final Deque<MethodReference> worklist = new ArrayDeque<>();

    // Methods reachable from user code (including system ones called from it)
    private final Set<MethodReference> userReachableMethods = new HashSet<>();

    // ------------------------------------------------------------------------
    //  Metadata application (unchanged)
    // ------------------------------------------------------------------------

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

    // ------------------------------------------------------------------------
    //  Reachability analysis from the entry point
    // ------------------------------------------------------------------------

    public void analyzeFromEntry(String entryClass, String entryMethod, String entryDescriptor) {
        String internalClass = entryClass.replace('.', '/');
        if (entryDescriptor == null) {
            entryDescriptor = "([Ljava/lang/String;)V";
        }
        MethodReference entryPoint = new MethodReference(internalClass, entryMethod, entryDescriptor);
        addMethod(entryPoint, true); // the entry point is always user code

        while (!worklist.isEmpty()) {
            MethodReference current = worklist.poll();
            processMethod(current);
        }

        log.info("Reachability analysis complete. Reachable classes: {}, methods: {}",
            reachableClasses.size(), reachableMethods.size());
        log.info("User-reachable methods: {}", userReachableMethods.size());
    }

    // ------------------------------------------------------------------------
    //  Processing a single method
    // ------------------------------------------------------------------------

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

        // Determine whether the method is reachable from user code
        boolean reachableFromUser = userReachableMethods.contains(ref) || !isSystemClassName(owner);
        parseBytecode(owner, name, desc, reachableFromUser);
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

    // ------------------------------------------------------------------------
    //  Parsing bytecode taking user-code reachability into account
    // ------------------------------------------------------------------------

    private void parseBytecode(String owner, String name, String desc, boolean reachableFromUser) {
        byte[] bytes = resolver.getClassBytes(owner);
        if (bytes == null) {
            log.warn("No bytecode available for class {}", owner);
            return;
        }
        ClassReader reader = new ClassReader(bytes);

        // For reflective calls
        final String[] lastLdcString = {null};
        final String[] lastLoadedClass = {null};

        reader.accept(new ClassVisitor(Opcodes.ASM9) {

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                addTypeFromDescriptor(descriptor);
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if (value instanceof Type t) {
                            addClassWithInit(t.getInternalName());
                        }
                    }
                    @Override
                    public void visitEnum(String name, String descriptor, String value) {
                        addTypeFromDescriptor(descriptor);
                    }
                    @Override
                    public AnnotationVisitor visitArray(String name) { return this; }
                    @Override
                    public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                        addTypeFromDescriptor(descriptor);
                        return this;
                    }
                };
            }

            @Override
            public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
                addTypeFromDescriptor(descriptor);
                return super.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        addTypeFromDescriptor(descriptor);
                        return super.visitAnnotation(descriptor, visible);
                    }
                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
                        addTypeFromDescriptor(descriptor);
                        return super.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String mName, String mDesc,
                                             String signature, String[] exceptions) {
                if (mName.equals(name) && mDesc.equals(desc)) {
                    // Create a type analyzer for this method
                    TypeSimulator simulator = new TypeSimulator();
                    final boolean currentReachableFromUser = reachableFromUser;

                    return new MethodVisitor(Opcodes.ASM9) {

                        @Override
                        public void visitCode() {
                            lastLdcString[0] = null;
                            lastLoadedClass[0] = null;
                            super.visitCode();
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            simulator.visitInsn(opcode);
                            super.visitInsn(opcode);
                        }

                        @Override
                        public void visitIntInsn(int opcode, int operand) {
                            simulator.visitIntInsn(opcode, operand);
                            super.visitIntInsn(opcode, operand);
                        }

                        @Override
                        public void visitVarInsn(int opcode, int var) {
                            simulator.visitVarInsn(opcode, var);
                            super.visitVarInsn(opcode, var);
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            simulator.visitTypeInsn(opcode, type);
                            super.visitTypeInsn(opcode, type);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            simulator.visitFieldInsn(opcode, descriptor);
                            super.visitFieldInsn(opcode, owner, name, descriptor);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mName,
                                                    String mDesc, boolean isInterface) {
                            // 1. Get the receiver type from the simulator (if available)
                            String receiverType = simulator.getReceiverType(opcode, mDesc);

                            // 2. Handle the call
                            if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE) {
                                Set<String> targets = new HashSet<>();
                                if (receiverType != null && isConcreteClass(receiverType)) {
                                    targets.add(receiverType);
                                } else {
                                    if (receiverType != null) {
                                        targets.addAll(resolver.getSubclasses(receiverType));
                                    } else {
                                        targets.add(owner);
                                        targets.addAll(resolver.getSubclasses(owner));
                                    }
                                    if (receiverType != null) {
                                        targets.add(receiverType);
                                    }
                                }
                                for (String target : targets) {
                                    MethodReference ref = new MethodReference(target, mName, mDesc);
                                    addMethodWithContext(ref, currentReachableFromUser);
                                }
                            } else {
                                MethodReference ref = new MethodReference(owner, mName, mDesc);
                                addMethodWithContext(ref, currentReachableFromUser);
                            }

                            // 3. Reflective calls
                            handleReflectiveCall(owner, mName, mDesc, currentReachableFromUser);

                            // 4. Update the simulator
                            simulator.visitMethodInsn(opcode, mDesc);

                            super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                        }

                        // Helper method for adding with context awareness
                        private void addMethodWithContext(MethodReference ref, boolean currentReachableFromUser) {
                            // If the current method is not reachable from user code, ignore all calls
                            if (!currentReachableFromUser) {
                                return;
                            }

                            // Determine whether the target method is a system one
                            boolean targetIsSystem = isSystemClassName(ref.getOwner());

                            // Add the method to the reachability graph
                            // If the target method is a user method -> isUser = true
                            // If system -> isUser = false, but it must be marked as reachable from user code
                            if (reachableMethods.add(ref)) {
                                worklist.add(ref);
                                // If the method is a system one but we are adding it from a chain reachable from user code,
                                // we still mark it as reachable from user so its body gets analyzed.
                                if (targetIsSystem) {
                                    userReachableMethods.add(ref);
                                } else {
                                    // The user method also goes into userReachableMethods (it would end up there anyway)
                                    userReachableMethods.add(ref);
                                }
                                addClassWithInit(ref.getOwner());
                            } else {
                                // If the method was already added but we want to ensure it is marked as reachable
                                // from user code when it is a system method and the current method is user-reachable.
                                if (targetIsSystem) {
                                    userReachableMethods.add(ref);
                                }
                            }
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof String) {
                                lastLdcString[0] = (String) value;
                            } else if (value instanceof Type t) {
                                if (t.getSort() == Type.OBJECT) {
                                    lastLoadedClass[0] = t.getInternalName();
                                    addClassWithInit(t.getInternalName());
                                }
                            }
                            simulator.visitLdcInsn(value);
                            super.visitLdcInsn(value);
                        }

                        @Override
                        public void visitJumpInsn(int opcode, Label label) {
                            simulator.visitJumpInsn(opcode);
                            super.visitJumpInsn(opcode, label);
                        }

                        @Override
                        public void visitLabel(Label label) {
                            super.visitLabel(label);
                        }

                        @Override
                        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                            if (type != null) {
                                addClassWithInit(type);
                            }
                            super.visitTryCatchBlock(start, end, handler, type);
                        }

                        @Override
                        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                            addTypeFromDescriptor(descriptor);
                            return super.visitParameterAnnotation(parameter, descriptor, visible);
                        }

                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            addTypeFromDescriptor(descriptor);
                            return super.visitAnnotation(descriptor, visible);
                        }

                        @Override
                        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
                            addTypeFromDescriptor(descriptor);
                            return super.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
                        }

                        // ---- reflective call handling (context-aware) ----
                        private void handleReflectiveCall(String owner, String mName, String mDesc, boolean currentReachableFromUser) {
                            if (!currentReachableFromUser) return; // ignore reflection from unreachable system code

                            if (owner.equals("java/lang/Class") && mName.equals("forName")
                                && mDesc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
                                if (lastLdcString[0] != null) {
                                    String className = lastLdcString[0].replace('.', '/');
                                    addClassWithInit(className);
                                }
                                return;
                            }
                            if (owner.equals("java/lang/ClassLoader") && mName.equals("loadClass")
                                && mDesc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
                                if (lastLdcString[0] != null) {
                                    String className = lastLdcString[0].replace('.', '/');
                                    addClassWithInit(className);
                                }
                                return;
                            }
                            if (owner.equals("java/lang/Class") && (mName.equals("getMethod") || mName.equals("getDeclaredMethod"))
                                && mDesc.startsWith("(Ljava/lang/String;")) {
                                if (lastLoadedClass[0] != null && lastLdcString[0] != null) {
                                    String targetClass = lastLoadedClass[0];
                                    String methodName = lastLdcString[0];
                                    ClassNode cn = resolver.getClassNode(targetClass);
                                    if (cn != null && !cn.isExternal()) {
                                        for (MethodNode mn : cn.getMethods()) {
                                            if (mn.getName().equals(methodName) && !mn.getName().equals("<init>")) {
                                                MethodReference ref = new MethodReference(targetClass, mn.getName(), mn.getDescriptor());
                                                reflectInfo.addMethod(targetClass, ref);
                                                addMethodWithContext(ref, true);
                                            }
                                        }
                                    }
                                }
                                return;
                            }
                            if (owner.equals("java/lang/Class") && (mName.equals("getField") || mName.equals("getDeclaredField"))
                                && mDesc.startsWith("(Ljava/lang/String;)")) {
                                if (lastLoadedClass[0] != null && lastLdcString[0] != null) {
                                    String targetClass = lastLoadedClass[0];
                                    String fieldName = lastLdcString[0];
                                    FieldReference ref = new FieldReference(targetClass, fieldName, null);
                                    reflectInfo.addField(targetClass, ref);
                                    addClassWithInit(targetClass);
                                }
                                return;
                            }
                            if (owner.equals("java/lang/reflect/Method") && mName.equals("invoke")
                                && mDesc.equals("(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;")) {
                                for (String cls : reachableClasses) {
                                    ClassNode cn = resolver.getClassNode(cls);
                                    if (cn != null && !cn.isExternal()) {
                                        for (MethodNode mn : cn.getMethods()) {
                                            if (!mn.getName().equals("<init>") && !mn.getName().equals("<clinit>")) {
                                                MethodReference ref = new MethodReference(cls, mn.getName(), mn.getDescriptor());
                                                reflectInfo.addMethod(cls, ref);
                                                addMethodWithContext(ref, true);
                                            }
                                        }
                                    }
                                }
                                return;
                            }
                            if (owner.equals("java/lang/reflect/Constructor") && mName.equals("newInstance")
                                && mDesc.equals("([Ljava/lang/Object;)Ljava/lang/Object;")) {
                                for (String cls : reachableClasses) {
                                    ClassNode cn = resolver.getClassNode(cls);
                                    if (cn != null && !cn.isExternal()) {
                                        for (MethodNode mn : cn.getMethods()) {
                                            if (mn.getName().equals("<init>")) {
                                                MethodReference ref = new MethodReference(cls, "<init>", mn.getDescriptor());
                                                reflectInfo.addConstructor(cls, ref);
                                                addMethodWithContext(ref, true);
                                            }
                                        }
                                    }
                                }
                                return;
                            }
                            if (owner.equals("java/lang/Class") && mName.equals("newInstance")
                                && mDesc.equals("()Ljava/lang/Object;")) {
                                if (lastLoadedClass[0] != null) {
                                    String targetClass = lastLoadedClass[0];
                                    ClassNode cn = resolver.getClassNode(targetClass);
                                    if (cn != null && !cn.isExternal()) {
                                        for (MethodNode mn : cn.getMethods()) {
                                            if (mn.getName().equals("<init>") && mn.getDescriptor().equals("()V")) {
                                                MethodReference ref = new MethodReference(targetClass, "<init>", "()V");
                                                reflectInfo.addConstructor(targetClass, ref);
                                                addMethodWithContext(ref, true);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    };
                }
                return null;
            }
        }, ClassReader.SKIP_DEBUG);
    }

    // ------------------------------------------------------------------------
    //  Helper methods
    // ------------------------------------------------------------------------

    /**
     * Adds the class to the reachable set and, if the class is not a system one, adds its <clinit>.
     * For system classes <clinit> is not added to avoid unnecessary dependencies.
     */
    private void addClassWithInit(String className) {
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

    /**
     * Determines whether the class is a system one (JDK, library, etc.).
     */
    private static boolean isSystemClassName(String className) {
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

    /**
     * Adds a method to the reachability graph.
     * @param isUser true if the method belongs to a user class (not a system one)
     */
    public void addMethod(MethodReference ref, boolean isUser) {
        if (reachableMethods.add(ref)) {
            worklist.add(ref);
            if (isUser) {
                userReachableMethods.add(ref);
            }
            addClassWithInit(ref.getOwner());
        }
    }

    private void addTypeFromDescriptor(String desc) {
        if (desc == null) return;
        if (desc.startsWith("L") && desc.endsWith(";")) {
            String type = desc.substring(1, desc.length() - 1);
            addClassWithInit(type);
        } else if (desc.startsWith("[")) {
            String elem = desc;
            while (elem.startsWith("[")) elem = elem.substring(1);
            if (elem.startsWith("L") && elem.endsWith(";")) {
                String type = elem.substring(1, elem.length() - 1);
                addClassWithInit(type);
            }
        }
    }

    /**
     * Checks whether the class is concrete (not an interface, not abstract).
     */
    private boolean isConcreteClass(String className) {
        ClassNode cn = resolver.getClassNode(className);
        if (cn == null || cn.isExternal()) return false;
        if (cn.isInterface()) return false;
        return (cn.getAccess() & Opcodes.ACC_ABSTRACT) == 0;
    }

    // ------------------------------------------------------------------------
    //  Internal class – stack and local variable simulator (full implementation)
    // ------------------------------------------------------------------------

    private static class TypeSimulator {
        private final Deque<io.github.kubyk01.domain.analyzer.ir.Type> stack = new ArrayDeque<>();
        private final Map<Integer, io.github.kubyk01.domain.analyzer.ir.Type> locals = new HashMap<>();

        TypeSimulator() {}

        private void push(io.github.kubyk01.domain.analyzer.ir.Type type) {
            stack.push(type);
        }

        private io.github.kubyk01.domain.analyzer.ir.Type pop() {
            return stack.isEmpty() ? io.github.kubyk01.domain.analyzer.ir.Type.UNKNOWN : stack.pop();
        }

        private io.github.kubyk01.domain.analyzer.ir.Type peek() {
            return stack.isEmpty() ? io.github.kubyk01.domain.analyzer.ir.Type.UNKNOWN : stack.peek();
        }

        private void storeLocal(int index, io.github.kubyk01.domain.analyzer.ir.Type type) {
            locals.put(index, type);
        }

        private io.github.kubyk01.domain.analyzer.ir.Type loadLocal(int index) {
            return locals.getOrDefault(index, io.github.kubyk01.domain.analyzer.ir.Type.UNKNOWN);
        }

        void visitInsn(int opcode) {
            switch (opcode) {
                case Opcodes.ACONST_NULL:
                    push(io.github.kubyk01.domain.analyzer.ir.Type.NULL);
                    break;
                case Opcodes.ICONST_M1:
                case Opcodes.ICONST_0:
                case Opcodes.ICONST_1:
                case Opcodes.ICONST_2:
                case Opcodes.ICONST_3:
                case Opcodes.ICONST_4:
                case Opcodes.ICONST_5:
                    push(io.github.kubyk01.domain.analyzer.ir.Type.INT);
                    break;
                case Opcodes.LCONST_0:
                case Opcodes.LCONST_1:
                    push(io.github.kubyk01.domain.analyzer.ir.Type.LONG);
                    break;
                case Opcodes.FCONST_0:
                case Opcodes.FCONST_1:
                case Opcodes.FCONST_2:
                    push(io.github.kubyk01.domain.analyzer.ir.Type.FLOAT);
                    break;
                case Opcodes.DCONST_0:
                case Opcodes.DCONST_1:
                    push(io.github.kubyk01.domain.analyzer.ir.Type.DOUBLE);
                    break;
                case Opcodes.IADD: case Opcodes.ISUB: case Opcodes.IMUL:
                case Opcodes.IDIV: case Opcodes.IREM: case Opcodes.INEG:
                case Opcodes.ISHL: case Opcodes.ISHR: case Opcodes.IUSHR:
                case Opcodes.IAND: case Opcodes.IOR: case Opcodes.IXOR:
                    pop(); pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.INT);
                    break;
                case Opcodes.LADD: case Opcodes.LSUB: case Opcodes.LMUL:
                case Opcodes.LDIV: case Opcodes.LREM: case Opcodes.LNEG:
                case Opcodes.LSHL: case Opcodes.LSHR: case Opcodes.LUSHR:
                case Opcodes.LAND: case Opcodes.LOR: case Opcodes.LXOR:
                    pop(); pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.LONG);
                    break;
                case Opcodes.FADD: case Opcodes.FSUB: case Opcodes.FMUL:
                case Opcodes.FDIV: case Opcodes.FREM: case Opcodes.FNEG:
                    pop(); pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.FLOAT);
                    break;
                case Opcodes.DADD: case Opcodes.DSUB: case Opcodes.DMUL:
                case Opcodes.DDIV: case Opcodes.DREM: case Opcodes.DNEG:
                    pop(); pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.DOUBLE);
                    break;
                case Opcodes.LCMP: case Opcodes.FCMPL: case Opcodes.FCMPG:
                case Opcodes.DCMPL: case Opcodes.DCMPG:
                    pop(); pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.INT);
                    break;
                case Opcodes.POP:
                    pop();
                    break;
                case Opcodes.POP2:
                    pop(); pop();
                    break;
                case Opcodes.DUP:
                    push(peek());
                    break;
                case Opcodes.DUP_X1: {
                    io.github.kubyk01.domain.analyzer.ir.Type v1 = pop();
                    io.github.kubyk01.domain.analyzer.ir.Type v2 = pop();
                    push(v1); push(v2); push(v1);
                    break;
                }
                case Opcodes.DUP_X2: {
                    io.github.kubyk01.domain.analyzer.ir.Type v1 = pop();
                    io.github.kubyk01.domain.analyzer.ir.Type v2 = pop();
                    io.github.kubyk01.domain.analyzer.ir.Type v3 = pop();
                    push(v1); push(v3); push(v2); push(v1);
                    break;
                }
                case Opcodes.DUP2: {
                    io.github.kubyk01.domain.analyzer.ir.Type v1 = pop();
                    io.github.kubyk01.domain.analyzer.ir.Type v2 = pop();
                    push(v2); push(v1); push(v2); push(v1);
                    break;
                }
                case Opcodes.SWAP: {
                    io.github.kubyk01.domain.analyzer.ir.Type v1 = pop();
                    io.github.kubyk01.domain.analyzer.ir.Type v2 = pop();
                    push(v1); push(v2);
                    break;
                }
                case Opcodes.I2L: pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.LONG); break;
                case Opcodes.I2F: pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.FLOAT); break;
                case Opcodes.I2D: pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.DOUBLE); break;
                case Opcodes.L2I: pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.INT); break;
                case Opcodes.L2F: pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.FLOAT); break;
                case Opcodes.L2D: pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.DOUBLE); break;
                case Opcodes.F2I: pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.INT); break;
                case Opcodes.F2L: pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.LONG); break;
                case Opcodes.F2D: pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.DOUBLE); break;
                case Opcodes.D2I: pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.INT); break;
                case Opcodes.D2L: pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.LONG); break;
                case Opcodes.D2F: pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.FLOAT); break;
                case Opcodes.I2B: case Opcodes.I2C: case Opcodes.I2S:
                    pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.INT); break;
                case Opcodes.IRETURN: case Opcodes.LRETURN: case Opcodes.FRETURN:
                case Opcodes.DRETURN: case Opcodes.ARETURN: case Opcodes.RETURN:
                    stack.clear();
                    break;
                case Opcodes.ARRAYLENGTH:
                    pop(); push(io.github.kubyk01.domain.analyzer.ir.Type.INT);
                    break;
                case Opcodes.AALOAD: {
                    pop(); // index
                    io.github.kubyk01.domain.analyzer.ir.Type arrayType = pop();
                    io.github.kubyk01.domain.analyzer.ir.Type elem = arrayType.isArray() ? arrayType.getElementType() : io.github.kubyk01.domain.analyzer.ir.Type.UNKNOWN;
                    push(elem);
                    break;
                }
                case Opcodes.AASTORE: {
                    pop(); // value
                    pop(); // index
                    pop(); // array
                    break;
                }
                case Opcodes.ATHROW:
                    stack.clear();
                    break;
                case Opcodes.MONITORENTER:
                case Opcodes.MONITOREXIT:
                    pop(); // object
                    break;
                default:
                    // unknown instruction – do nothing
                    break;
            }
        }

        void visitIntInsn(int opcode, int operand) {
            if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                push(io.github.kubyk01.domain.analyzer.ir.Type.INT);
            } else if (opcode == Opcodes.NEWARRAY) {
                pop(); // size
                io.github.kubyk01.domain.analyzer.ir.Type elemType = primitiveArrayType(operand);
                push(io.github.kubyk01.domain.analyzer.ir.Type.array(elemType));
            }
        }

        void visitVarInsn(int opcode, int var) {
            switch (opcode) {
                case Opcodes.ILOAD: push(loadLocal(var)); break;
                case Opcodes.LLOAD: push(io.github.kubyk01.domain.analyzer.ir.Type.LONG); break;
                case Opcodes.FLOAD: push(io.github.kubyk01.domain.analyzer.ir.Type.FLOAT); break;
                case Opcodes.DLOAD: push(io.github.kubyk01.domain.analyzer.ir.Type.DOUBLE); break;
                case Opcodes.ALOAD: push(loadLocal(var)); break;
                case Opcodes.ISTORE: storeLocal(var, pop()); break;
                case Opcodes.LSTORE: storeLocal(var, io.github.kubyk01.domain.analyzer.ir.Type.LONG); pop(); break;
                case Opcodes.FSTORE: storeLocal(var, io.github.kubyk01.domain.analyzer.ir.Type.FLOAT); pop(); break;
                case Opcodes.DSTORE: storeLocal(var, io.github.kubyk01.domain.analyzer.ir.Type.DOUBLE); pop(); break;
                case Opcodes.ASTORE: storeLocal(var, pop()); break;
                case Opcodes.RET:
                    // return from subroutine – ignored
                    break;
                default: break;
            }
        }

        void visitTypeInsn(int opcode, String type) {
            switch (opcode) {
                case Opcodes.NEW:
                    push(io.github.kubyk01.domain.analyzer.ir.Type.reference(type));
                    break;
                case Opcodes.ANEWARRAY:
                    pop(); // size
                    push(io.github.kubyk01.domain.analyzer.ir.Type.array(io.github.kubyk01.domain.analyzer.ir.Type.reference(type)));
                    break;
                case Opcodes.CHECKCAST:
                    pop();
                    push(io.github.kubyk01.domain.analyzer.ir.Type.reference(type));
                    break;
                case Opcodes.INSTANCEOF:
                    pop(); // object
                    push(io.github.kubyk01.domain.analyzer.ir.Type.INT);
                    break;
                default: break;
            }
        }

        void visitFieldInsn(int opcode, String descriptor) {
            io.github.kubyk01.domain.analyzer.ir.Type fieldType = io.github.kubyk01.domain.analyzer.ir.Type.fromDescriptor(descriptor);
            switch (opcode) {
                case Opcodes.GETFIELD:
                    pop(); // receiver
                    push(fieldType);
                    break;
                case Opcodes.PUTFIELD:
                    pop(); // value
                    pop(); // receiver
                    break;
                case Opcodes.GETSTATIC:
                    push(fieldType);
                    break;
                case Opcodes.PUTSTATIC:
                    pop(); // value
                    break;
                default: break;
            }
        }

        void visitMethodInsn(int opcode, String desc) {
            int argCount = countArguments(desc);
            io.github.kubyk01.domain.analyzer.ir.Type retType = io.github.kubyk01.domain.analyzer.ir.Type.fromDescriptor(
                desc.substring(desc.lastIndexOf(')') + 1)
            );

            for (int i = 0; i < argCount; i++) {
                pop();
            }

            if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE
                || opcode == Opcodes.INVOKESPECIAL) {
                pop(); // receiver
            }

            if (!retType.isVoid()) {
                push(retType);
            }
        }

        void visitLdcInsn(Object value) {
            if (value instanceof Integer) {
                push(io.github.kubyk01.domain.analyzer.ir.Type.INT);
            } else if (value instanceof Long) {
                push(io.github.kubyk01.domain.analyzer.ir.Type.LONG);
            } else if (value instanceof Float) {
                push(io.github.kubyk01.domain.analyzer.ir.Type.FLOAT);
            } else if (value instanceof Double) {
                push(io.github.kubyk01.domain.analyzer.ir.Type.DOUBLE);
            } else if (value instanceof String || value instanceof org.objectweb.asm.Type) {
                push(io.github.kubyk01.domain.analyzer.ir.Type.reference("java/lang/String"));
            } else {
                push(io.github.kubyk01.domain.analyzer.ir.Type.UNKNOWN);
            }
        }

        void visitJumpInsn(int opcode) {
            switch (opcode) {
                case Opcodes.IFEQ: case Opcodes.IFNE: case Opcodes.IFLT:
                case Opcodes.IFGE: case Opcodes.IFGT: case Opcodes.IFLE:
                case Opcodes.IFNULL: case Opcodes.IFNONNULL:
                    pop();
                    break;
                case Opcodes.IF_ICMPEQ: case Opcodes.IF_ICMPNE:
                case Opcodes.IF_ICMPLT: case Opcodes.IF_ICMPGE:
                case Opcodes.IF_ICMPGT: case Opcodes.IF_ICMPLE:
                case Opcodes.IF_ACMPEQ: case Opcodes.IF_ACMPNE:
                    pop(); pop();
                    break;
                case Opcodes.GOTO:
                    break;
                case Opcodes.JSR:
                    push(io.github.kubyk01.domain.analyzer.ir.Type.BLOCK);
                    break;
                default: break;
            }
        }

        private int countArguments(String desc) {
            int count = 0;
            int i = 1;
            while (i < desc.length()) {
                char c = desc.charAt(i);
                if (c == ')') break;
                if (c == 'L') {
                    i = desc.indexOf(';', i) + 1;
                } else if (c == '[') {
                    while (i < desc.length() && desc.charAt(i) == '[') i++;
                    if (i < desc.length() && desc.charAt(i) == 'L') {
                        i = desc.indexOf(';', i) + 1;
                    } else {
                        i++;
                    }
                } else {
                    i++;
                }
                count++;
            }
            return count;
        }

        private io.github.kubyk01.domain.analyzer.ir.Type primitiveArrayType(int atype) {
            return switch (atype) {
                case Opcodes.T_BOOLEAN -> io.github.kubyk01.domain.analyzer.ir.Type.BOOLEAN;
                case Opcodes.T_BYTE    -> io.github.kubyk01.domain.analyzer.ir.Type.BYTE;
                case Opcodes.T_CHAR    -> io.github.kubyk01.domain.analyzer.ir.Type.CHAR;
                case Opcodes.T_SHORT   -> io.github.kubyk01.domain.analyzer.ir.Type.SHORT;
                case Opcodes.T_INT     -> io.github.kubyk01.domain.analyzer.ir.Type.INT;
                case Opcodes.T_LONG    -> io.github.kubyk01.domain.analyzer.ir.Type.LONG;
                case Opcodes.T_FLOAT   -> io.github.kubyk01.domain.analyzer.ir.Type.FLOAT;
                case Opcodes.T_DOUBLE  -> io.github.kubyk01.domain.analyzer.ir.Type.DOUBLE;
                default -> io.github.kubyk01.domain.analyzer.ir.Type.UNKNOWN;
            };
        }

        /**
         * Returns the receiver type for a virtual call.
         * Returns null if the type cannot be determined.
         */
        String getReceiverType(int opcode, String desc) {
            if (opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKEINTERFACE) {
                return null;
            }
            int argCount = countArguments(desc);
            if (stack.size() < argCount + 1) {
                return null;
            }
            io.github.kubyk01.domain.analyzer.ir.Type receiverType = null;
            int idx = 0;
            for (io.github.kubyk01.domain.analyzer.ir.Type t : stack) {
                if (idx == argCount) {
                    receiverType = t;
                    break;
                }
                idx++;
            }
            if (receiverType == null || !receiverType.isReference()) {
                return null;
            }
            String className = receiverType.getClassName();
            if (className.equals("java/lang/Object") || className.equals("java/lang/Cloneable")
                || className.equals("java/io/Serializable") || receiverType.isUnknown()) {
                return null;
            }
            return className;
        }
    }
}
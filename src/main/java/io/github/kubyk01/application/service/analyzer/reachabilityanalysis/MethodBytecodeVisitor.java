package io.github.kubyk01.application.service.analyzer.reachabilityanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldReference;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.analyzer.reachability.TypedValue;
import io.github.kubyk01.domain.analyzer.reflection.ReflectClassInfo;
import io.github.kubyk01.domain.analyzer.reflection.ReflectInfo;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.*;

import java.util.*;

import static io.github.kubyk01.domain.analyzer.ir.Type.*;

@Slf4j
public class MethodBytecodeVisitor extends ClassVisitor {

    private final DependencyResolver resolver;
    private final Set<String> reachableClasses;
    private final Set<MethodReference> reachableMethods;
    private final Deque<MethodReference> worklist;
    private final Map<MethodReference, Set<MethodReference>> callGraph;
    private final ReflectInfo reflectInfo;
    private final Set<MethodReference> userReachableMethods;
    private final ReachabilityAnalysis analysis;

    private final MethodReference currentMethod;
    private final boolean reachableFromUser;
    private final MethodReference caller;

    private final Set<String> instantiatedClasses = new HashSet<>();
    private String lastLoadedClass = null;

    public MethodBytecodeVisitor(DependencyResolver resolver,
                                 Set<String> reachableClasses,
                                 Set<MethodReference> reachableMethods,
                                 Deque<MethodReference> worklist,
                                 Map<MethodReference, Set<MethodReference>> callGraph,
                                 ReflectInfo reflectInfo,
                                 Set<MethodReference> userReachableMethods,
                                 ReachabilityAnalysis analysis,
                                 MethodReference currentMethod,
                                 boolean reachableFromUser,
                                 MethodReference caller) {
        super(Opcodes.ASM9);
        this.resolver = resolver;
        this.reachableClasses = reachableClasses;
        this.reachableMethods = reachableMethods;
        this.worklist = worklist;
        this.callGraph = callGraph;
        this.reflectInfo = reflectInfo;
        this.userReachableMethods = userReachableMethods;
        this.analysis = analysis;
        this.currentMethod = currentMethod;
        this.reachableFromUser = reachableFromUser;
        this.caller = caller;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        if (name.equals(currentMethod.getName()) && descriptor.equals(currentMethod.getDescriptor())) {
            return new MethodVisitorImpl();
        }
        return null;
    }

    public void parse(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        reader.accept(this, ClassReader.SKIP_DEBUG);
    }

    private void addClassWithInit(String className) {
        analysis.addClassWithInit(className);
    }

    private void addMethodWithContext(MethodReference ref, boolean user) {
        analysis.addMethodWithContext(ref, user, caller);
    }

    private void addTypeFromDescriptor(String desc) {
        analysis.addTypeFromDescriptor(desc);
    }

    private boolean isSystemClassName(String className) {
        return analysis.isSystemClassName(className);
    }

    private class MethodVisitorImpl extends MethodVisitor {

        private final TypeSimulator simulator = new TypeSimulator();

        public MethodVisitorImpl() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visitCode() {
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
            if (opcode == Opcodes.NEW || opcode == Opcodes.ANEWARRAY || opcode == Opcodes.MULTIANEWARRAY) {
                analysis.addInstantiatedClass(type);
            }
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
            boolean reflective = isReflectiveCall(owner, mName, mDesc);

            if (reflective) {
                int argCount = countArguments(mDesc);
                List<TypedValue> args = new ArrayList<>();
                for (int i = 0; i < argCount; i++) {
                    args.add(0, simulator.pop()); // reverse order
                }
                if (opcode != Opcodes.INVOKESTATIC) {
                    simulator.pop(); // receiver
                }
                handleReflectiveCall(owner, mName, mDesc, args);
                io.github.kubyk01.domain.analyzer.ir.Type retType = TypeResolver.descToReturnType(mDesc);
                if (!retType.isVoid()) {
                    simulator.push(TypedValue.fromType(retType));
                }
            } else {
                String receiverType = simulator.getReceiverType(opcode, mDesc);
                if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE) {
                    Set<String> targets = new HashSet<>();
                    Set<String> candidateTypes = new HashSet<>();

                    if (receiverType != null && isConcreteClass(receiverType)) {
                        candidateTypes.add(receiverType);
                    } else {
                        Set<String> subclasses = new HashSet<>();
                        if (receiverType != null) {
                            subclasses.addAll(resolver.getSubclasses(receiverType));
                        } else {
                            subclasses.addAll(resolver.getSubclasses(owner));
                        }
                        if (subclasses.isEmpty()) {
                            subclasses.add(owner);
                        }
                        candidateTypes.addAll(subclasses);
                        if (receiverType != null) {
                            candidateTypes.add(receiverType);
                        }
                    }

                    for (String cls : candidateTypes) {
                        if (analysis.getInstantiatedClasses().contains(cls) && isConcreteClass(cls)) {
                            targets.add(cls);
                        }
                    }
                    if (targets.isEmpty() && receiverType != null && isConcreteClass(receiverType)) {
                        targets.add(receiverType);
                    }
                    if (targets.isEmpty()) {
                        targets.addAll(candidateTypes);
                    }

                    for (String target : targets) {
                        MethodReference ref = new MethodReference(target, mName, mDesc);
                        addMethodWithContext(ref, reachableFromUser);
                    }
                } else {
                    MethodReference ref = new MethodReference(owner, mName, mDesc);
                    addMethodWithContext(ref, reachableFromUser);
                }
                simulator.visitMethodInsn(opcode, mDesc);
            }
            super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof String) {
            } else if (value instanceof org.objectweb.asm.Type asmType) {
                if (asmType.getSort() == org.objectweb.asm.Type.OBJECT) {
                    lastLoadedClass = asmType.getInternalName();
                    addClassWithInit(asmType.getInternalName());
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

        private boolean isReflectiveCall(String owner, String name, String desc) {
            if (owner.equals("java/lang/Class") && name.equals("forName") && desc.equals("(Ljava/lang/String;)Ljava/lang/Class;"))
                return true;
            if (owner.equals("java/lang/ClassLoader") && name.equals("loadClass") && desc.equals("(Ljava/lang/String;)Ljava/lang/Class;"))
                return true;
            if (owner.equals("java/lang/Class") && (name.equals("getMethod") || name.equals("getDeclaredMethod"))
                && desc.startsWith("(Ljava/lang/String;")) {
                return true;
            }
            if (owner.equals("java/lang/Class") && (name.equals("getField") || name.equals("getDeclaredField"))
                && desc.startsWith("(Ljava/lang/String;)")) {
                return true;
            }
            if (owner.equals("java/lang/reflect/Method") && name.equals("invoke")
                && desc.equals("(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;")) {
                return true;
            }
            if (owner.equals("java/lang/reflect/Constructor") && name.equals("newInstance")
                && desc.equals("([Ljava/lang/Object;)Ljava/lang/Object;")) {
                return true;
            }
            if (owner.equals("java/lang/Class") && name.equals("newInstance")
                && desc.equals("()Ljava/lang/Object;")) {
                return true;
            }
            return false;
        }

        private void handleReflectiveCall(String owner, String mName, String mDesc,
                                          List<TypedValue> args) {
            if (!reachableFromUser) return;

            if (owner.equals("java/lang/Class") && mName.equals("forName")
                && mDesc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
                if (!args.isEmpty()) {
                    TypedValue arg = args.get(0);
                    if (arg.isConstant() && arg.getValue() instanceof String) {
                        String className = ((String) arg.getValue()).replace('.', '/');
                        addClassWithInit(className);
                        analysis.addInstantiatedClass(className);
                    }
                }
                return;
            }
            if (owner.equals("java/lang/ClassLoader") && mName.equals("loadClass")
                && mDesc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
                if (!args.isEmpty()) {
                    TypedValue arg = args.get(0);
                    if (arg.isConstant() && arg.getValue() instanceof String) {
                        String className = ((String) arg.getValue()).replace('.', '/');
                        addClassWithInit(className);
                        analysis.addInstantiatedClass(className);
                    }
                }
                return;
            }
            if (owner.equals("java/lang/Class") && (mName.equals("getMethod") || mName.equals("getDeclaredMethod"))
                && mDesc.startsWith("(Ljava/lang/String;")) {
                if (args.size() < 1) return;
                TypedValue nameArg = args.get(0);
                if (!nameArg.isConstant() || !(nameArg.getValue() instanceof String)) return;
                String methodName = (String) nameArg.getValue();
                List<String> paramClassNames = new ArrayList<>();
                for (int i = 1; i < args.size(); i++) {
                    TypedValue param = args.get(i);
                    if (param.isConstant() && param.getValue() instanceof String) {
                        paramClassNames.add((String) param.getValue());
                    } else if (param.isExact()) {
                        paramClassNames.add(param.getClassName());
                    } else if (param.getType().isReference()) {
                        String cls = param.getType().getClassName();
                        if (cls != null) paramClassNames.add(cls);
                    }
                }
                if (lastLoadedClass == null) return;
                String targetClass = lastLoadedClass;
                ClassNode cn = resolver.getClassNode(targetClass);
                if (cn == null || cn.isExternal()) return;
                for (MethodNode mn : cn.getMethods()) {
                    if (mn.getName().equals(methodName) && !mn.getName().equals("<init>")) {
                        List<io.github.kubyk01.domain.analyzer.ir.Type> paramTypes = mn.getParameterTypes();
                        if (paramTypes.size() == paramClassNames.size()) {
                            boolean match = true;
                            for (int i = 0; i < paramTypes.size(); i++) {
                                io.github.kubyk01.domain.analyzer.ir.Type pt = paramTypes.get(i);
                                String expected = paramClassNames.get(i);
                                if (!typeMatches(expected, pt)) {
                                    match = false;
                                    break;
                                }
                            }
                            if (match) {
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
                if (!args.isEmpty()) {
                    TypedValue arg = args.get(0);
                    if (arg.isConstant() && arg.getValue() instanceof String) {
                        String fieldName = (String) arg.getValue();
                        if (lastLoadedClass != null) {
                            String targetClass = lastLoadedClass;
                            FieldReference ref = new FieldReference(targetClass, fieldName, null);
                            reflectInfo.addField(targetClass, ref);
                            addClassWithInit(targetClass);
                        }
                    }
                }
                return;
            }
            if (owner.equals("java/lang/reflect/Method") && mName.equals("invoke")
                && mDesc.equals("(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;")) {
                for (String cls : reachableClasses) {
                    ReflectClassInfo info = reflectInfo.getOrCreateClassInfo(cls);
                    for (MethodReference method : info.getMethods()) {
                        addMethodWithContext(method, true);
                    }
                }
                return;
            }
            if (owner.equals("java/lang/reflect/Constructor") && mName.equals("newInstance")
                && mDesc.equals("([Ljava/lang/Object;)Ljava/lang/Object;")) {
                for (String cls : reachableClasses) {
                    ReflectClassInfo info = reflectInfo.getOrCreateClassInfo(cls);
                    for (MethodReference ctor : info.getConstructors()) {
                        addMethodWithContext(ctor, true);
                        analysis.addInstantiatedClass(cls);
                    }
                }
                return;
            }
            if (owner.equals("java/lang/Class") && mName.equals("newInstance")
                && mDesc.equals("()Ljava/lang/Object;")) {
                if (lastLoadedClass != null) {
                    String targetClass = lastLoadedClass;
                    ClassNode cn = resolver.getClassNode(targetClass);
                    if (cn != null && !cn.isExternal()) {
                        for (MethodNode mn : cn.getMethods()) {
                            if (mn.getName().equals("<init>") && mn.getDescriptor().equals("()V")) {
                                MethodReference ref = new MethodReference(targetClass, "<init>", "()V");
                                reflectInfo.addConstructor(targetClass, ref);
                                addMethodWithContext(ref, true);
                                analysis.addInstantiatedClass(targetClass);
                            }
                        }
                    }
                }
                return;
            }
        }

        private boolean typeMatches(String expected, io.github.kubyk01.domain.analyzer.ir.Type actual) {
            io.github.kubyk01.domain.analyzer.ir.Type expectedType =
                io.github.kubyk01.domain.analyzer.ir.Type.fromDescriptor(
                    expected.startsWith("[") ? expected : "L" + expected + ";"
                );
            return expectedType.equals(actual);
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

        private boolean isConcreteClass(String className) {
            ClassNode cn = resolver.getClassNode(className);
            if (cn == null || cn.isExternal()) return false;
            if (cn.isInterface()) return false;
            return (cn.getAccess() & Opcodes.ACC_ABSTRACT) == 0;
        }
    }

    private static class TypeSimulator {
        private final Deque<TypedValue> stack = new ArrayDeque<>();
        private final Map<Integer, TypedValue> locals = new HashMap<>();

        void push(TypedValue tv) { stack.push(tv); }
        TypedValue pop() { return stack.isEmpty() ? TypedValue.UNKNOWN : stack.pop(); }
        TypedValue peek() { return stack.isEmpty() ? TypedValue.UNKNOWN : stack.peek(); }
        void storeLocal(int idx, TypedValue tv) { locals.put(idx, tv); }
        TypedValue loadLocal(int idx) { return locals.getOrDefault(idx, TypedValue.UNKNOWN); }

        void visitInsn(int opcode) {
            switch (opcode) {
                case Opcodes.ACONST_NULL: push(TypedValue.NULL); break;
                case Opcodes.ICONST_M1:
                case Opcodes.ICONST_0:
                case Opcodes.ICONST_1:
                case Opcodes.ICONST_2:
                case Opcodes.ICONST_3:
                case Opcodes.ICONST_4:
                case Opcodes.ICONST_5:
                    push(TypedValue.INT); break;
                case Opcodes.LCONST_0:
                case Opcodes.LCONST_1:
                    push(TypedValue.LONG); break;
                case Opcodes.FCONST_0:
                case Opcodes.FCONST_1:
                case Opcodes.FCONST_2:
                    push(TypedValue.FLOAT); break;
                case Opcodes.DCONST_0:
                case Opcodes.DCONST_1:
                    push(TypedValue.DOUBLE); break;
                case Opcodes.IADD: case Opcodes.ISUB: case Opcodes.IMUL:
                case Opcodes.IDIV: case Opcodes.IREM: case Opcodes.INEG:
                case Opcodes.ISHL: case Opcodes.ISHR: case Opcodes.IUSHR:
                case Opcodes.IAND: case Opcodes.IOR: case Opcodes.IXOR:
                    pop(); pop(); push(TypedValue.INT); break;
                case Opcodes.LADD: case Opcodes.LSUB: case Opcodes.LMUL:
                case Opcodes.LDIV: case Opcodes.LREM: case Opcodes.LNEG:
                case Opcodes.LSHL: case Opcodes.LSHR: case Opcodes.LUSHR:
                case Opcodes.LAND: case Opcodes.LOR: case Opcodes.LXOR:
                    pop(); pop(); push(TypedValue.LONG); break;
                case Opcodes.FADD: case Opcodes.FSUB: case Opcodes.FMUL:
                case Opcodes.FDIV: case Opcodes.FREM: case Opcodes.FNEG:
                    pop(); pop(); push(TypedValue.FLOAT); break;
                case Opcodes.DADD: case Opcodes.DSUB: case Opcodes.DMUL:
                case Opcodes.DDIV: case Opcodes.DREM: case Opcodes.DNEG:
                    pop(); pop(); push(TypedValue.DOUBLE); break;
                case Opcodes.LCMP: case Opcodes.FCMPL: case Opcodes.FCMPG:
                case Opcodes.DCMPL: case Opcodes.DCMPG:
                    pop(); pop(); push(TypedValue.INT); break;
                case Opcodes.POP: pop(); break;
                case Opcodes.POP2: pop(); pop(); break;
                case Opcodes.DUP: push(peek()); break;
                case Opcodes.DUP_X1: {
                    TypedValue v1 = pop();
                    TypedValue v2 = pop();
                    push(v1); push(v2); push(v1);
                    break;
                }
                case Opcodes.DUP_X2: {
                    TypedValue v1 = pop();
                    TypedValue v2 = pop();
                    TypedValue v3 = pop();
                    push(v1); push(v3); push(v2); push(v1);
                    break;
                }
                case Opcodes.DUP2: {
                    TypedValue v1 = pop();
                    TypedValue v2 = pop();
                    push(v2); push(v1); push(v2); push(v1);
                    break;
                }
                case Opcodes.SWAP: {
                    TypedValue v1 = pop();
                    TypedValue v2 = pop();
                    push(v1); push(v2);
                    break;
                }
                case Opcodes.I2L: pop(); push(TypedValue.LONG); break;
                case Opcodes.I2F: pop(); push(TypedValue.FLOAT); break;
                case Opcodes.I2D: pop(); push(TypedValue.DOUBLE); break;
                case Opcodes.L2I: pop(); push(TypedValue.INT); break;
                case Opcodes.L2F: pop(); push(TypedValue.FLOAT); break;
                case Opcodes.L2D: pop(); push(TypedValue.DOUBLE); break;
                case Opcodes.F2I: pop(); push(TypedValue.INT); break;
                case Opcodes.F2L: pop(); push(TypedValue.LONG); break;
                case Opcodes.F2D: pop(); push(TypedValue.DOUBLE); break;
                case Opcodes.D2I: pop(); push(TypedValue.INT); break;
                case Opcodes.D2L: pop(); push(TypedValue.LONG); break;
                case Opcodes.D2F: pop(); push(TypedValue.FLOAT); break;
                case Opcodes.I2B: case Opcodes.I2C: case Opcodes.I2S:
                    pop(); push(TypedValue.INT); break;
                case Opcodes.IRETURN: case Opcodes.LRETURN: case Opcodes.FRETURN:
                case Opcodes.DRETURN: case Opcodes.ARETURN: case Opcodes.RETURN:
                    stack.clear(); break;
                case Opcodes.ARRAYLENGTH:
                    pop(); push(TypedValue.INT); break;
                case Opcodes.AALOAD: {
                    pop(); // index
                    TypedValue arrayTv = pop();
                    io.github.kubyk01.domain.analyzer.ir.Type elemType =
                        arrayTv.getType().isArray() ? arrayTv.getType().getElementType() : UNKNOWN;
                    push(TypedValue.fromType(elemType));
                    break;
                }
                case Opcodes.AASTORE: pop(); pop(); pop(); break;
                case Opcodes.ATHROW: stack.clear(); break;
                case Opcodes.MONITORENTER:
                case Opcodes.MONITOREXIT:
                    pop(); break;
                default: break;
            }
        }

        void visitIntInsn(int opcode, int operand) {
            if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                push(TypedValue.INT);
            } else if (opcode == Opcodes.NEWARRAY) {
                pop();
                io.github.kubyk01.domain.analyzer.ir.Type elemType = primitiveArrayType(operand);
                push(TypedValue.fromType(array(elemType)));
            }
        }

        void visitVarInsn(int opcode, int var) {
            switch (opcode) {
                case Opcodes.ILOAD: push(loadLocal(var)); break;
                case Opcodes.LLOAD: push(TypedValue.LONG); break;
                case Opcodes.FLOAD: push(TypedValue.FLOAT); break;
                case Opcodes.DLOAD: push(TypedValue.DOUBLE); break;
                case Opcodes.ALOAD: push(loadLocal(var)); break;
                case Opcodes.ISTORE: storeLocal(var, pop()); break;
                case Opcodes.LSTORE: storeLocal(var, TypedValue.LONG); pop(); break;
                case Opcodes.FSTORE: storeLocal(var, TypedValue.FLOAT); pop(); break;
                case Opcodes.DSTORE: storeLocal(var, TypedValue.DOUBLE); pop(); break;
                case Opcodes.ASTORE: storeLocal(var, pop()); break;
                case Opcodes.RET: break;
                default: break;
            }
        }

        void visitTypeInsn(int opcode, String type) {
            switch (opcode) {
                case Opcodes.NEW:
                    push(TypedValue.fromReference(type));
                    break;
                case Opcodes.ANEWARRAY:
                    pop();
                    push(TypedValue.fromType(array(reference(type))));
                    break;
                case Opcodes.CHECKCAST:
                    pop();
                    push(TypedValue.fromReference(type));
                    break;
                case Opcodes.INSTANCEOF:
                    pop();
                    push(TypedValue.INT);
                    break;
                default: break;
            }
        }

        void visitFieldInsn(int opcode, String descriptor) {
            io.github.kubyk01.domain.analyzer.ir.Type fieldType = fromDescriptor(descriptor);
            switch (opcode) {
                case Opcodes.GETFIELD:
                    pop();
                    push(TypedValue.fromType(fieldType));
                    break;
                case Opcodes.PUTFIELD:
                    pop(); pop();
                    break;
                case Opcodes.GETSTATIC:
                    push(TypedValue.fromType(fieldType));
                    break;
                case Opcodes.PUTSTATIC:
                    pop();
                    break;
                default: break;
            }
        }

        void visitMethodInsn(int opcode, String desc) {
            int argCount = countArguments(desc);
            io.github.kubyk01.domain.analyzer.ir.Type retType = fromDescriptor(desc.substring(desc.lastIndexOf(')') + 1));

            for (int i = 0; i < argCount; i++) {
                pop();
            }
            if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE
                || opcode == Opcodes.INVOKESPECIAL) {
                pop();
            }
            if (!retType.isVoid()) {
                push(TypedValue.fromType(retType));
            }
        }

        void visitLdcInsn(Object value) {
            if (value instanceof Integer) {
                push(TypedValue.fromConstant(io.github.kubyk01.domain.analyzer.ir.Type.INT, value));
            } else if (value instanceof Long) {
                push(TypedValue.fromConstant(io.github.kubyk01.domain.analyzer.ir.Type.LONG, value));
            } else if (value instanceof Float) {
                push(TypedValue.fromConstant(io.github.kubyk01.domain.analyzer.ir.Type.FLOAT, value));
            } else if (value instanceof Double) {
                push(TypedValue.fromConstant(io.github.kubyk01.domain.analyzer.ir.Type.DOUBLE, value));
            } else if (value instanceof String) {
                push(TypedValue.fromConstant(io.github.kubyk01.domain.analyzer.ir.Type.reference("java/lang/String"), value));
            } else if (value instanceof org.objectweb.asm.Type asmType) {
                if (asmType.getSort() == org.objectweb.asm.Type.OBJECT) {
                    push(TypedValue.fromConstant(io.github.kubyk01.domain.analyzer.ir.Type.reference(asmType.getInternalName()), asmType.getInternalName()));
                } else {
                    push(TypedValue.fromConstant(io.github.kubyk01.domain.analyzer.ir.Type.fromDescriptor(asmType.getDescriptor()), asmType.getDescriptor()));
                }
            } else {
                push(TypedValue.UNKNOWN);
            }
        }

        void visitJumpInsn(int opcode) {
            switch (opcode) {
                case Opcodes.IFEQ: case Opcodes.IFNE: case Opcodes.IFLT:
                case Opcodes.IFGE: case Opcodes.IFGT: case Opcodes.IFLE:
                case Opcodes.IFNULL: case Opcodes.IFNONNULL:
                    pop(); break;
                case Opcodes.IF_ICMPEQ: case Opcodes.IF_ICMPNE:
                case Opcodes.IF_ICMPLT: case Opcodes.IF_ICMPGE:
                case Opcodes.IF_ICMPGT: case Opcodes.IF_ICMPLE:
                case Opcodes.IF_ACMPEQ: case Opcodes.IF_ACMPNE:
                    pop(); pop(); break;
                case Opcodes.GOTO: break;
                case Opcodes.JSR:
                    push(TypedValue.BLOCK); break;
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
                case Opcodes.T_BOOLEAN -> BOOLEAN;
                case Opcodes.T_BYTE    -> BYTE;
                case Opcodes.T_CHAR    -> CHAR;
                case Opcodes.T_SHORT   -> SHORT;
                case Opcodes.T_INT     -> INT;
                case Opcodes.T_LONG    -> LONG;
                case Opcodes.T_FLOAT   -> FLOAT;
                case Opcodes.T_DOUBLE  -> DOUBLE;
                default -> UNKNOWN;
            };
        }

        String getReceiverType(int opcode, String desc) {
            if (opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKEINTERFACE) {
                return null;
            }
            int argCount = countArguments(desc);
            if (stack.size() < argCount + 1) return null;
            int idx = 0;
            TypedValue receiver = null;
            for (TypedValue tv : stack) {
                if (idx == argCount) {
                    receiver = tv;
                    break;
                }
                idx++;
            }
            if (receiver == null) return null;
            if (receiver.isExact()) {
                return receiver.getClassName();
            }
            if (receiver.getType().isReference()) {
                String cls = receiver.getType().getClassName();
                if (cls != null && !cls.equals("java/lang/Object")) {
                    return cls;
                }
            }
            return null;
        }
    }
}
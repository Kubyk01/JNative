package io.github.kubyk01.application.service.codegen.llvm.nativepolymorphicfunctionresolver;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.util.LlvmUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class VarHandleTargetResolver {

    public enum Kind { INSTANCE_FIELD, STATIC_FIELD, ARRAY_ELEMENT }

    public record Target(Kind kind, String owner, String fieldName, Type valueType) {}

    private record LookupIn(String lookupClass) {}

    private static final Object UNKNOWN = new Object();
    private static final Object CONFLICT = new Object();

    private static final String METHOD_HANDLES = "java/lang/invoke/MethodHandles";
    private static final String LOOKUP = "java/lang/invoke/MethodHandles$Lookup";
    private static final String MH_UTIL = "jdk/internal/invoke/MhUtil";
    private static final String VAR_HANDLE = "java/lang/invoke/VarHandle";

    private final DependencyResolver resolver;
    private final Map<String, Optional<Target>> cache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> clinitAssignments = new ConcurrentHashMap<>();

    public VarHandleTargetResolver(DependencyResolver resolver) {
        this.resolver = resolver;
    }

    public Target resolve(String holderClass, String handleField) {
        if (holderClass == null || handleField == null) return null;
        return cache.computeIfAbsent(holderClass + "." + handleField,
                k -> Optional.ofNullable(compute(holderClass, handleField))).orElse(null);
    }

    public Target resolveCallSite(Instruction call) {
        if (call == null || !call.isPolymorphicSignature() || call.getOperands().size() < 2) return null;
        if (!(call.getOperands().get(1) instanceof Constant c)
                || !c.getValue().toString().startsWith(VAR_HANDLE + ".")) {
            return null;
        }
        return resolveReceiver(call.getOperands().getFirst(), 0, new HashSet<>());
    }

    private Target resolveReceiver(Value receiver, int depth, Set<Instruction> visited) {
        if (depth >= 64 || !(receiver instanceof Temporary t)) return null;
        Instruction def = t.getDefiningInstruction();
        if (def == null || !visited.add(def)) return null;
        if (def.getOpcode() == Opcode.GET_STATIC) {
            String full = LlvmUtil.extractFieldName(def);
            int dot = full.lastIndexOf('.');
            return dot > 0 ? resolve(full.substring(0, dot), full.substring(dot + 1)) : null;
        }
        if (def.getOpcode() == Opcode.LOAD) {
            return resolveLocalSlot(def, depth, visited);
        }
        if (def.getOperands().isEmpty()) return null;
        if (def.getOpcode() == Opcode.CHECKCAST || def.getOpcode() == Opcode.CAST
                || def.getOpcode() == Opcode.STORE) {
            return resolveReceiver(def.getOperands().getFirst(), depth + 1, visited);
        }
        if (def.getOpcode() != Opcode.PHI) return null;
        return resolveAll(def.getOperands(), depth, visited);
    }

    private Target resolveLocalSlot(Instruction load, int depth, Set<Instruction> visited) {
        if (load.getParent() == null || load.getParent().getFunction() == null) return null;
        List<Value> stored = new ArrayList<>();
        for (BasicBlock block : load.getParent().getFunction().getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() == Opcode.STORE
                        && inst.getLocalIndex() == load.getLocalIndex()
                        && !inst.getOperands().isEmpty()) {
                    stored.add(inst.getOperands().getFirst());
                }
            }
        }
        return resolveAll(stored, depth, visited);
    }

    private Target resolveAll(List<Value> values, int depth, Set<Instruction> visited) {
        Target common = null;
        for (Value incoming : values) {
            Target target = resolveReceiver(incoming, depth + 1, new HashSet<>(visited));
            if (target == null || (common != null && !common.equals(target))) return null;
            common = target;
        }
        return common;
    }

    private Target compute(String holderClass, String handleField) {
        FieldNode handle = resolver.getField(holderClass, handleField);
        if (handle == null
                || (handle.getAccess() & Opcodes.ACC_STATIC) == 0
                || (handle.getAccess() & Opcodes.ACC_FINAL) == 0
                || !"Ljava/lang/invoke/VarHandle;".equals(handle.getDescriptor())) {
            return null;
        }
        String declaring = handle.getOwner() != null ? handle.getOwner() : holderClass;
        Object assigned = clinitAssignments.computeIfAbsent(declaring, this::scanClinit).get(handleField);
        if (!(assigned instanceof Target t)) return null;
        return validate(t);
    }

    private Target validate(Target t) {
        if (t.kind() == Kind.ARRAY_ELEMENT) return t;
        FieldNode f = resolver.getField(t.owner(), t.fieldName());
        if (f == null || f.getDescriptor() == null) return null;
        boolean isStatic = (f.getAccess() & Opcodes.ACC_STATIC) != 0;
        if (isStatic != (t.kind() == Kind.STATIC_FIELD)) return null;
        String typeDesc = t.valueType() == null ? null : descriptorOf(t.valueType());
        if (!f.getDescriptor().equals(typeDesc)) return null;
        String owner = f.getOwner() != null ? f.getOwner() : t.owner();
        return new Target(t.kind(), owner, t.fieldName(), Type.fromDescriptor(f.getDescriptor()));
    }

    private static String descriptorOf(Type t) {
        if (t == Type.BOOLEAN) return "Z";
        if (t == Type.BYTE) return "B";
        if (t == Type.SHORT) return "S";
        if (t == Type.CHAR) return "C";
        if (t == Type.INT) return "I";
        if (t == Type.LONG) return "J";
        if (t == Type.FLOAT) return "F";
        if (t == Type.DOUBLE) return "D";
        if (t.isArray()) return "[" + descriptorOf(t.getElementType());
        if (t.isReference()) return "L" + t.getClassName() + ";";
        return null;
    }

    private Map<String, Object> scanClinit(String className) {
        byte[] bytes = resolver.getClassBytes(className);
        if (bytes == null) {
            resolver.getClassNode(className);
            bytes = resolver.getClassBytes(className);
        }
        Map<String, Object> result = new HashMap<>();
        if (bytes == null) return result;
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!"<clinit>".equals(name)) return null;
                return new ClinitInterpreter(className, result);
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }

    private static final class ClinitInterpreter extends MethodVisitor {
        private final String className;
        private final Map<String, Object> assignments;
        private final Deque<Object> stack = new ArrayDeque<>();
        private final Map<Integer, Object> locals = new HashMap<>();

        ClinitInterpreter(String className, Map<String, Object> assignments) {
            super(Opcodes.ASM9);
            this.className = className;
            this.assignments = assignments;
        }

        private Object pop() {
            Object v = stack.pollFirst();
            return v == null ? UNKNOWN : v;
        }

        private void push(Object v) {
            stack.addFirst(v == null ? UNKNOWN : v);
        }

        private void reset() {
            stack.clear();
        }

        private void assign(String field, Object value) {
            Object previous = assignments.get(field);
            if (previous == null) {
                assignments.put(field, value);
            } else if (!Objects.equals(previous, value)) {
                assignments.put(field, CONFLICT);
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof String s) {
                push(s);
            } else if (value instanceof org.objectweb.asm.Type t
                    && (t.getSort() == org.objectweb.asm.Type.OBJECT
                    || t.getSort() == org.objectweb.asm.Type.ARRAY)) {
                push(Type.fromDescriptor(t.getDescriptor()));
            } else {
                push(UNKNOWN);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.GETSTATIC && "TYPE".equals(name)) {
                Type prim = switch (owner) {
                    case "java/lang/Boolean" -> Type.BOOLEAN;
                    case "java/lang/Byte" -> Type.BYTE;
                    case "java/lang/Short" -> Type.SHORT;
                    case "java/lang/Character" -> Type.CHAR;
                    case "java/lang/Integer" -> Type.INT;
                    case "java/lang/Long" -> Type.LONG;
                    case "java/lang/Float" -> Type.FLOAT;
                    case "java/lang/Double" -> Type.DOUBLE;
                    default -> null;
                };
                if (prim != null) {
                    push(prim);
                    return;
                }
            }
            if (opcode == Opcodes.PUTSTATIC && owner.equals(className)) {
                Object value = pop();
                if ("Ljava/lang/invoke/VarHandle;".equals(descriptor)) {
                    assign(name, value instanceof Target ? value : CONFLICT);
                }
            }
            reset();
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC && METHOD_HANDLES.equals(owner)) {
                switch (name + descriptor) {
                    case "lookup()Ljava/lang/invoke/MethodHandles$Lookup;" -> {
                        push(new LookupIn(className));
                        return;
                    }
                    case "privateLookupIn(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;" -> {
                        pop();
                        Object cls = pop();
                        push(cls instanceof Type t && t.isReference() ? new LookupIn(t.getClassName()) : UNKNOWN);
                        return;
                    }
                    case "arrayElementVarHandle(Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;" -> {
                        Object cls = pop();
                        push(cls instanceof Type t && t.isArray()
                                ? new Target(Kind.ARRAY_ELEMENT, null, null, t.getElementType())
                                : UNKNOWN);
                        return;
                    }
                    default -> { }
                }
            }
            if (opcode == Opcodes.INVOKESTATIC && MH_UTIL.equals(owner) && "findVarHandle".equals(name)) {
                if ("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;".equals(descriptor)) {
                    Object type = pop();
                    Object field = pop();
                    Object lookup = pop();
                    push(lookup instanceof LookupIn(String lookupClass)
                            ? fieldTarget(Kind.INSTANCE_FIELD, Type.reference(lookupClass), field, type)
                            : UNKNOWN);
                    return;
                }
                if ("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;".equals(descriptor)) {
                    Object type = pop();
                    Object field = pop();
                    Object recv = pop();
                    Object lookup = pop();
                    push(lookup instanceof LookupIn
                            ? fieldTarget(Kind.INSTANCE_FIELD, recv, field, type)
                            : UNKNOWN);
                    return;
                }
            }
            if (opcode == Opcodes.INVOKEVIRTUAL && LOOKUP.equals(owner)
                    && "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;".equals(descriptor)
                    && ("findVarHandle".equals(name) || "findStaticVarHandle".equals(name))) {
                Object type = pop();
                Object field = pop();
                Object recv = pop();
                Object lookup = pop();
                Kind kind = "findVarHandle".equals(name) ? Kind.INSTANCE_FIELD : Kind.STATIC_FIELD;
                push(lookup instanceof LookupIn ? fieldTarget(kind, recv, field, type) : UNKNOWN);
                return;
            }
            reset();
        }

        private static Object fieldTarget(Kind kind, Object recv, Object field, Object type) {
            if (recv instanceof Type r && r.isReference()
                    && field instanceof String f
                    && type instanceof Type t && !t.isUnknown() && !t.isVoid()) {
                return new Target(kind, r.getClassName(), f, t);
            }
            return UNKNOWN;
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            if (opcode == Opcodes.ASTORE) {
                locals.put(varIndex, pop());
                reset();
                return;
            }
            if (opcode == Opcodes.ALOAD) {
                push(locals.getOrDefault(varIndex, UNKNOWN));
                return;
            }
            if (opcode >= Opcodes.ISTORE) {
                locals.put(varIndex, UNKNOWN);
                if (opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE) {
                    locals.put(varIndex + 1, UNKNOWN);
                }
            }
            reset();
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.DUP) {
                Object top = pop();
                push(top);
                push(top);
                return;
            }
            reset();
        }

        @Override
        public void visitIntInsn(int opcode, int operand) { reset(); }

        @Override
        public void visitTypeInsn(int opcode, String type) { reset(); }

        @Override
        public void visitJumpInsn(int opcode, Label label) { reset(); }

        @Override
        public void visitIincInsn(int varIndex, int increment) { reset(); }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) { reset(); }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) { reset(); }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) { reset(); }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor,
                                           org.objectweb.asm.Handle bsm, Object... bsmArgs) { reset(); }
    }
}

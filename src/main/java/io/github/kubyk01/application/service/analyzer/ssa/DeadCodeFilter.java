package io.github.kubyk01.application.service.analyzer.ssa;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DeadCodeFilter extends MethodVisitor {

    private final Set<Integer> liveLabels;
    private boolean dead;
    private int labelIndex;

    private DeadCodeFilter(MethodVisitor delegate, Set<Integer> liveLabels) {
        super(Opcodes.ASM9, delegate);
        this.liveLabels = liveLabels;
    }

    static MethodVisitor wrap(MethodVisitor delegate, Set<Integer> liveLabels) {
        return liveLabels == null ? delegate : new DeadCodeFilter(delegate, liveLabels);
    }

    static Map<String, Set<Integer>> scan(byte[] classBytes) {
        Map<String, Set<Integer>> result = new HashMap<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new Scanner(name + descriptor, result);
            }
        }, ClassReader.SKIP_DEBUG);
        return result;
    }

    private static final class Scanner extends MethodVisitor {
        private final String key;
        private final Map<String, Set<Integer>> result;
        private final List<Label> targets = new ArrayList<>();
        private final List<Object> events = new ArrayList<>();
        private static final Object TERMINATOR = new Object();
        private static final Object INSN = new Object();

        Scanner(String key, Map<String, Set<Integer>> result) {
            super(Opcodes.ASM9);
            this.key = key;
            this.result = result;
        }

        @Override public void visitLabel(Label label) { events.add(label); }
        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) { targets.add(handler); }
        @Override public void visitInsn(int opcode) { events.add(isTerminator(opcode) ? TERMINATOR : INSN); }
        @Override public void visitIntInsn(int opcode, int operand) { events.add(INSN); }
        @Override public void visitVarInsn(int opcode, int var) { events.add(opcode == Opcodes.RET ? TERMINATOR : INSN); }
        @Override public void visitTypeInsn(int opcode, String type) { events.add(INSN); }
        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) { events.add(INSN); }
        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) { events.add(INSN); }
        @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... args) { events.add(INSN); }
        @Override public void visitLdcInsn(Object value) { events.add(INSN); }
        @Override public void visitIincInsn(int var, int increment) { events.add(INSN); }
        @Override public void visitMultiANewArrayInsn(String descriptor, int dims) { events.add(INSN); }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            targets.add(label);
            events.add(opcode == Opcodes.GOTO ? TERMINATOR : INSN);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            targets.add(dflt);
            targets.addAll(List.of(labels));
            events.add(TERMINATOR);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            targets.add(dflt);
            targets.addAll(List.of(labels));
            events.add(TERMINATOR);
        }

        @Override
        public void visitEnd() {
            Map<Label, Integer> index = new IdentityHashMap<>();
            for (Object e : events) {
                if (e instanceof Label l) index.putIfAbsent(l, index.size());
            }
            Set<Integer> live = new HashSet<>();
            for (Label l : targets) {
                Integer i = index.get(l);
                if (i != null) live.add(i);
            }
            boolean dead = false;
            boolean found = false;
            for (Object e : events) {
                if (e instanceof Label l) {
                    if (live.contains(index.get(l))) dead = false;
                } else if (dead) {
                    found = true;
                    break;
                } else if (e == TERMINATOR) {
                    dead = true;
                }
            }
            if (found) result.put(key, live);
        }

        private static boolean isTerminator(int opcode) {
            return (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW;
        }
    }

    private void after(boolean terminator) {
        if (terminator) dead = true;
    }

    @Override
    public void visitLabel(Label label) {
        int i = labelIndex++;
        if (dead && liveLabels.contains(i)) dead = false;
        if (!dead) super.visitLabel(label);
    }

    @Override
    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        if (!dead) super.visitFrame(type, numLocal, local, numStack, stack);
    }

    @Override
    public void visitInsn(int opcode) {
        if (dead) return;
        super.visitInsn(opcode);
        after((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        if (!dead) super.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        if (dead) return;
        super.visitVarInsn(opcode, var);
        after(opcode == Opcodes.RET);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        if (!dead) super.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        if (!dead) super.visitFieldInsn(opcode, owner, name, descriptor);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        if (!dead) super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... args) {
        if (!dead) super.visitInvokeDynamicInsn(name, descriptor, bsm, args);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        if (dead) return;
        super.visitJumpInsn(opcode, label);
        after(opcode == Opcodes.GOTO);
    }

    @Override
    public void visitLdcInsn(Object value) {
        if (!dead) super.visitLdcInsn(value);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        if (!dead) super.visitIincInsn(var, increment);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        if (dead) return;
        super.visitTableSwitchInsn(min, max, dflt, labels);
        after(true);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        if (dead) return;
        super.visitLookupSwitchInsn(dflt, keys, labels);
        after(true);
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        if (!dead) super.visitMultiANewArrayInsn(descriptor, numDimensions);
    }
}

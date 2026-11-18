package io.github.kubyk01.application.service.analyzer.dependencyresolver;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

final class SynchronizedMethodRewriter {

    private SynchronizedMethodRewriter() {}

    static boolean needsRewrite(int access, String name) {
        return (access & Opcodes.ACC_SYNCHRONIZED) != 0
            && (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
            && !name.equals("<init>") && !name.equals("<clinit>");
    }

    static byte[] rewrite(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            private String owner;
            private int version;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                this.owner = name;
                this.version = version;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor target = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!needsRewrite(access, name)) return target;
                return new MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions) {
                    @Override
                    public void visitEnd() {
                        wrap(this, owner, (version & 0xFFFF) >= Opcodes.V1_6);
                        accept(target);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void wrap(MethodNode mn, String owner, boolean frames) {
        if (mn.instructions.size() == 0) return;
        boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();

        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                InsnList exit = lock(owner, isStatic);
                exit.add(new InsnNode(Opcodes.MONITOREXIT));
                mn.instructions.insertBefore(insn, exit);
            }
        }

        InsnList prologue = lock(owner, isStatic);
        prologue.add(new InsnNode(Opcodes.MONITORENTER));
        prologue.add(start);
        mn.instructions.insert(prologue);

        InsnList epilogue = new InsnList();
        epilogue.add(end);
        epilogue.add(handler);
        if (frames) {
            Object[] locals = isStatic ? new Object[0] : new Object[] {owner};
            epilogue.add(new FrameNode(Opcodes.F_FULL, locals.length, locals, 1,
                new Object[] {"java/lang/Throwable"}));
        }
        epilogue.add(lock(owner, isStatic));
        epilogue.add(new InsnNode(Opcodes.MONITOREXIT));
        epilogue.add(new InsnNode(Opcodes.ATHROW));
        mn.instructions.add(epilogue);

        mn.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
    }

    private static InsnList lock(String owner, boolean isStatic) {
        InsnList list = new InsnList();
        list.add(isStatic ? new LdcInsnNode(Type.getObjectType(owner)) : new VarInsnNode(Opcodes.ALOAD, 0));
        return list;
    }
}

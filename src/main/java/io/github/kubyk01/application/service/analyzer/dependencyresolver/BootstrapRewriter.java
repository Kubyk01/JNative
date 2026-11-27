package io.github.kubyk01.application.service.analyzer.dependencyresolver;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class BootstrapRewriter {

    static final String OBJECT_METHODS = "java/lang/runtime/ObjectMethods";
    static final String SWITCH_BOOTSTRAPS = "java/lang/runtime/SwitchBootstraps";
    private static final String PREFIX = "$jnative$indy$";

    private BootstrapRewriter() {}

    record Helper(String kind, String desc, Type record, Handle[] getters, Object[] labels) {}

    static boolean needsRewrite(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        return text.contains(OBJECT_METHODS) || text.contains(SWITCH_BOOTSTRAPS);
    }

    static byte[] rewrite(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        Map<String, Helper> helpers = new LinkedHashMap<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            private String owner;
            private boolean isInterface;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                this.owner = name;
                this.isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDesc,
                                                       Handle bsm, Object... args) {
                        Helper helper = toHelper(indyName, indyDesc, bsm, args);
                        if (helper == null) {
                            super.visitInvokeDynamicInsn(indyName, indyDesc, bsm, args);
                            return;
                        }
                        String helperName = PREFIX + helper.kind() + "$" + helpers.size();
                        helpers.put(helperName, helper);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, helperName,
                                helper.desc(), isInterface);
                    }
                };
            }

            @Override
            public void visitEnd() {
                helpers.forEach((name, helper) -> emit(cv, name, helper));
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static Helper toHelper(String name, String desc, Handle bsm, Object[] args) {
        if (bsm == null) return null;
        if (OBJECT_METHODS.equals(bsm.getOwner()) && "bootstrap".equals(bsm.getName())) {
            return objectMethodsHelper(name, desc, args);
        }
        if (SWITCH_BOOTSTRAPS.equals(bsm.getOwner())
                && ("typeSwitch".equals(bsm.getName()) || "enumSwitch".equals(bsm.getName()))) {
            return switchHelper(bsm.getName(), desc, args);
        }
        return null;
    }

    private static Helper objectMethodsHelper(String name, String desc, Object[] args) {
        if (args.length < 2 || !(args[0] instanceof Type record)) return null;
        String expected = switch (name) {
            case "hashCode" -> "(" + record.getDescriptor() + ")I";
            case "equals" -> "(" + record.getDescriptor() + "Ljava/lang/Object;)Z";
            case "toString" -> "(" + record.getDescriptor() + ")Ljava/lang/String;";
            default -> null;
        };
        if (!desc.equals(expected)) return null;
        Handle[] getters = new Handle[args.length - 2];
        for (int i = 2; i < args.length; i++) {
            if (!(args[i] instanceof Handle h) || h.getTag() != Opcodes.H_GETFIELD) return null;
            getters[i - 2] = h;
        }
        return new Helper(name, desc, record, getters, null);
    }

    private static Helper switchHelper(String kind, String desc, Object[] labels) {
        Type[] params = Type.getArgumentTypes(desc);
        if (params.length != 2 || params[0].getSort() != Type.OBJECT || params[1] != Type.INT_TYPE
                || Type.getReturnType(desc) != Type.INT_TYPE) {
            return null;
        }
        for (Object label : labels) {
            boolean supported = label instanceof Type t && t.getSort() == Type.OBJECT
                    || label instanceof String
                    || label instanceof Integer && kind.equals("typeSwitch");
            if (!supported) return null;
        }
        return new Helper(kind, desc, null, null, labels);
    }

    private static void emit(ClassVisitor cv, String name, Helper helper) {
        MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, helper.desc(), null, null);
        mv.visitCode();
        switch (helper.kind()) {
            case "hashCode" -> emitHashCode(mv, helper);
            case "equals" -> emitEquals(mv, helper);
            case "toString" -> emitToString(mv, helper);
            default -> emitSwitch(mv, helper);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitSwitch(MethodVisitor mv, Helper helper) {
        boolean enumSwitch = helper.kind().equals("enumSwitch");
        Object[] labels = helper.labels();
        Label nonNull = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitJumpInsn(Opcodes.IFNONNULL, nonNull);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(nonNull);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        for (int i = 0; i < labels.length; i++) {
            Label next = new Label();
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitLdcInsn(i);
            mv.visitJumpInsn(Opcodes.IF_ICMPGT, next);
            Object label = labels[i];
            if (label instanceof Type type) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitTypeInsn(Opcodes.INSTANCEOF, type.getInternalName());
                mv.visitJumpInsn(Opcodes.IFEQ, next);
            } else if (label instanceof String s) {
                mv.visitLdcInsn(s);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                if (enumSwitch) {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Enum");
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "name", "()Ljava/lang/String;", false);
                }
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(Opcodes.IFEQ, next);
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/Integer");
                mv.visitJumpInsn(Opcodes.IFEQ, next);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                mv.visitLdcInsn(label);
                mv.visitJumpInsn(Opcodes.IF_ICMPNE, next);
            }
            mv.visitLdcInsn(i);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(next);
            mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }
        mv.visitLdcInsn(labels.length);
        mv.visitInsn(Opcodes.IRETURN);
    }

    private static void getField(MethodVisitor mv, int local, Handle getter) {
        mv.visitVarInsn(Opcodes.ALOAD, local);
        mv.visitFieldInsn(Opcodes.GETFIELD, getter.getOwner(), getter.getName(), getter.getDesc());
    }

    private static void emitHashCode(MethodVisitor mv, Helper helper) {
        mv.visitInsn(Opcodes.ICONST_0);
        for (Handle getter : helper.getters()) {
            mv.visitIntInsn(Opcodes.BIPUSH, 31);
            mv.visitInsn(Opcodes.IMUL);
            getField(mv, 0, getter);
            switch (getter.getDesc().charAt(0)) {
                case 'Z' -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "hashCode", "(Z)I", false);
                case 'B' -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "hashCode", "(B)I", false);
                case 'S' -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "hashCode", "(S)I", false);
                case 'C' -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "hashCode", "(C)I", false);
                case 'I' -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "hashCode", "(I)I", false);
                case 'J' -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "hashCode", "(J)I", false);
                case 'F' -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "hashCode", "(F)I", false);
                case 'D' -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "hashCode", "(D)I", false);
                default -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "hashCode",
                        "(Ljava/lang/Object;)I", false);
            }
            mv.visitInsn(Opcodes.IADD);
        }
        mv.visitInsn(Opcodes.IRETURN);
    }

    private static void emitEquals(MethodVisitor mv, Helper helper) {
        String record = helper.record().getInternalName();
        Label notSame = new Label();
        Label isRecord = new Label();
        Label differ = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, notSame);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(notSame);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, record);
        mv.visitJumpInsn(Opcodes.IFNE, isRecord);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(isRecord);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, record);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        for (Handle getter : helper.getters()) {
            getField(mv, 0, getter);
            getField(mv, 2, getter);
            switch (getter.getDesc().charAt(0)) {
                case 'Z', 'B', 'S', 'C', 'I' -> mv.visitJumpInsn(Opcodes.IF_ICMPNE, differ);
                case 'J' -> {
                    mv.visitInsn(Opcodes.LCMP);
                    mv.visitJumpInsn(Opcodes.IFNE, differ);
                }
                case 'F' -> {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "compare", "(FF)I", false);
                    mv.visitJumpInsn(Opcodes.IFNE, differ);
                }
                case 'D' -> {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false);
                    mv.visitJumpInsn(Opcodes.IFNE, differ);
                }
                default -> {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "equals",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                    mv.visitJumpInsn(Opcodes.IFEQ, differ);
                }
            }
        }
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(differ);
        mv.visitFrame(Opcodes.F_APPEND, 1, new Object[] {record}, 0, null);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
    }

    private static void emitToString(MethodVisitor mv, Helper helper) {
        String sb = "java/lang/StringBuilder";
        String internal = helper.record().getInternalName();
        String simple = internal.substring(Math.max(internal.lastIndexOf('/'), internal.lastIndexOf('$')) + 1);
        mv.visitTypeInsn(Opcodes.NEW, sb);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(simple + "[");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, sb, "<init>", "(Ljava/lang/String;)V", false);
        Handle[] getters = helper.getters();
        for (int i = 0; i < getters.length; i++) {
            mv.visitLdcInsn((i > 0 ? ", " : "") + getters[i].getName() + "=");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, sb, "append", "(Ljava/lang/String;)L" + sb + ";", false);
            getField(mv, 0, getters[i]);
            String arg = switch (getters[i].getDesc().charAt(0)) {
                case 'Z' -> "Z";
                case 'C' -> "C";
                case 'B', 'S', 'I' -> "I";
                case 'J' -> "J";
                case 'F' -> "F";
                case 'D' -> "D";
                default -> "Ljava/lang/Object;";
            };
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, sb, "append", "(" + arg + ")L" + sb + ";", false);
        }
        mv.visitLdcInsn("]");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, sb, "append", "(Ljava/lang/String;)L" + sb + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, sb, "toString", "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.ARETURN);
    }
}

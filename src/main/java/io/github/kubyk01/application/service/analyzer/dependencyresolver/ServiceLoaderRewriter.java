package io.github.kubyk01.application.service.analyzer.dependencyresolver;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ServiceLoaderRewriter {

    static final String ITERATOR = "java/util/ServiceLoader$LazyClassPathLookupIterator";

    private static final String LOOKUP_NAME = "jnative$providers";
    private static final String LOOKUP_DESC = "(Ljava/lang/String;)Ljava/lang/String;";
    private static final String ENUMERATION = "Ljava/util/Enumeration;";
    private static final String ITERATOR_DESC = "Ljava/util/Iterator;";
    private static final String OUTER_DESC = "Ljava/util/ServiceLoader;";

    private ServiceLoaderRewriter() {}

    static byte[] rewrite(byte[] bytes, Map<String, List<String>> providers) {
        ClassReader reader = new ClassReader(bytes);
        Set<String> fields = new HashSet<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                fields.add(name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!fields.contains("configs" + ENUMERATION) || !fields.contains("pending" + ITERATOR_DESC)
                || !fields.contains("this$0" + OUTER_DESC)) {
            return bytes;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (RuntimeException | LinkageError e) {
                    return "java/lang/Object";
                }
            }
        };
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("nextProviderClass") || !descriptor.equals("()Ljava/lang/Class;")) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        emitPrologue(this);
                    }
                };
            }

            @Override
            public void visitEnd() {
                emitLookup(cv, providers);
                super.visitEnd();
            }
        }, ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static void emitPrologue(MethodVisitor mv) {
        Label done = new Label();
        Label none = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, ITERATOR, "configs", ENUMERATION);
        mv.visitJumpInsn(Opcodes.IFNONNULL, done);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyEnumeration", "()" + ENUMERATION, false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, ITERATOR, "configs", ENUMERATION);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, ITERATOR, "this$0", OUTER_DESC);
        mv.visitFieldInsn(Opcodes.GETFIELD, "java/util/ServiceLoader", "service", "Ljava/lang/Class;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ITERATOR, LOOKUP_NAME, LOOKUP_DESC, false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, none);
        mv.visitLdcInsn("\n");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "split", "(Ljava/lang/String;)[Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()" + ITERATOR_DESC, true);
        mv.visitFieldInsn(Opcodes.PUTFIELD, ITERATOR, "pending", ITERATOR_DESC);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(none);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(done);
    }

    private static void emitLookup(ClassVisitor cv, Map<String, List<String>> providers) {
        MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                LOOKUP_NAME, LOOKUP_DESC, null, null);
        mv.visitCode();
        for (Map.Entry<String, List<String>> e : providers.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            Label next = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitLdcInsn(e.getKey());
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
            mv.visitJumpInsn(Opcodes.IFEQ, next);
            mv.visitLdcInsn(String.join("\n", e.getValue()));
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(next);
        }
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}

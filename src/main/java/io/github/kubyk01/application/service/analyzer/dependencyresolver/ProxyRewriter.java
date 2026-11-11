package io.github.kubyk01.application.service.analyzer.dependencyresolver;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class ProxyRewriter {

    static final String PROXY = "java/lang/reflect/Proxy";
    private static final String SUPPORT = "io/github/kubyk01/application/service/analyzer/reachabilityanalysis/annotation/runtime/JNativeProxySupport";
    private static final String GET_CTOR_DESC =
        "(Ljava/lang/Class;Ljava/lang/ClassLoader;[Ljava/lang/Class;)Ljava/lang/reflect/Constructor;";
    private static final String IS_PROXY_DESC = "(Ljava/lang/Class;)Z";

    private ProxyRewriter() {}

    static byte[] rewrite(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if ("getProxyConstructor".equals(name) && GET_CTOR_DESC.equals(descriptor)) {
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitVarInsn(Opcodes.ALOAD, 2);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "proxyConstructor",
                        "(Ljava/lang/ClassLoader;[Ljava/lang/Class;)Ljava/lang/reflect/Constructor;", false);
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                    return null;
                }
                if ("isProxyClass".equals(name) && IS_PROXY_DESC.equals(descriptor)) {
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "isProxyClass", IS_PROXY_DESC, false);
                    mv.visitInsn(Opcodes.IRETURN);
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                    return null;
                }
                return mv;
            }
        }, 0);
        return writer.toByteArray();
    }
}

package io.github.kubyk01.application.service.analyzer.classanalysis.annotation;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.application.service.analyzer.classanalysis.annotation.runtime.JNativeProxySupport;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProxyClassGenerator implements Opcodes {

    private static final String PROXY = "java/lang/reflect/Proxy";
    private static final String HANDLER = "java/lang/reflect/InvocationHandler";
    private static final String METHOD = "java/lang/reflect/Method";

    public record ProxyMethod(String owner, String name, String descriptor, List<String> exceptions) {}

    public record GeneratedProxy(String internalName, byte[] bytes, Set<String> interfaceClosure) {}

    private ProxyClassGenerator() {}

    public static String proxyInternalName(List<String> interfaceInternalNames) {
        String[] dotted = interfaceInternalNames.stream().map(n -> n.replace('/', '.')).toArray(String[]::new);
        return JNativeProxySupport.proxyClassName(dotted).replace('.', '/');
    }

    public static GeneratedProxy generate(List<String> interfaces, DependencyResolver resolver) {
        String name = proxyInternalName(interfaces);
        Map<String, ProxyMethod> methods = new LinkedHashMap<>();
        methods.put("hashCode()I", new ProxyMethod("java/lang/Object", "hashCode", "()I", List.of()));
        methods.put("equals(Ljava/lang/Object;)Z", new ProxyMethod("java/lang/Object", "equals", "(Ljava/lang/Object;)Z", List.of()));
        methods.put("toString()Ljava/lang/String;", new ProxyMethod("java/lang/Object", "toString", "()Ljava/lang/String;", List.of()));

        Set<String> closure = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(interfaces);
        while (!queue.isEmpty()) {
            String iface = queue.poll();
            if (!closure.add(iface)) continue;
            ClassNode cn = resolver.getClassNode(iface);
            if (cn == null || cn.isExternal()) {
                throw new IllegalStateException("proxy interface not available: " + iface);
            }
            for (MethodNode m : cn.getMethods()) {
                if (m.isStatic() || m.getName().startsWith("<")) continue;
                if ((m.getAccess() & ACC_PRIVATE) != 0) continue;
                String key = m.getName() + m.getDescriptor();
                ProxyMethod existing = methods.get(key);
                List<String> exc = m.getExceptions() == null ? List.of() : List.copyOf(m.getExceptions());
                if (existing == null) {
                    methods.put(key, new ProxyMethod(iface, m.getName(), m.getDescriptor(), exc));
                } else if (!existing.owner().equals("java/lang/Object")) {
                    List<String> merged = new ArrayList<>(existing.exceptions());
                    merged.retainAll(exc);
                    methods.put(key, new ProxyMethod(existing.owner(), existing.name(), existing.descriptor(), merged));
                }
            }
            if (cn.getInterfaces() != null) queue.addAll(cn.getInterfaces());
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Throwable";
            }
        };
        cw.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, PROXY, interfaces.toArray(new String[0]));

        List<ProxyMethod> list = new ArrayList<>(methods.values());
        for (int i = 0; i < list.size(); i++) {
            cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "m" + i, "L" + METHOD + ";", null, null).visitEnd();
        }

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + HANDLER + ";)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ALOAD, 1);
        ctor.visitMethodInsn(INVOKESPECIAL, PROXY, "<init>", "(L" + HANDLER + ";)V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        for (int i = 0; i < list.size(); i++) {
            emitMethod(cw, name, "m" + i, list.get(i));
        }
        emitClinit(cw, name, list);
        cw.visitEnd();
        return new GeneratedProxy(name, cw.toByteArray(), closure);
    }

    private static void emitMethod(ClassWriter cw, String owner, String field, ProxyMethod pm) {
        String[] exc = pm.exceptions().isEmpty() ? null : pm.exceptions().toArray(new String[0]);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_FINAL, pm.name(), pm.descriptor(), null, exc);
        mv.visitCode();
        Label start = new Label(), end = new Label(), rethrow = new Label(), wrap = new Label();
        mv.visitTryCatchBlock(start, end, rethrow, "java/lang/RuntimeException");
        mv.visitTryCatchBlock(start, end, rethrow, "java/lang/Error");
        for (String e : pm.exceptions()) {
            mv.visitTryCatchBlock(start, end, rethrow, e);
        }
        mv.visitTryCatchBlock(start, end, wrap, "java/lang/Throwable");

        mv.visitLabel(start);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, PROXY, "h", "L" + HANDLER + ";");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, owner, field, "L" + METHOD + ";");
        Type[] args = Type.getArgumentTypes(pm.descriptor());
        if (args.length == 0) {
            mv.visitInsn(ACONST_NULL);
        } else {
            pushInt(mv, args.length);
            mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            int slot = 1;
            for (int i = 0; i < args.length; i++) {
                mv.visitInsn(DUP);
                pushInt(mv, i);
                mv.visitVarInsn(args[i].getOpcode(ILOAD), slot);
                box(mv, args[i]);
                mv.visitInsn(AASTORE);
                slot += args[i].getSize();
            }
        }
        mv.visitMethodInsn(INVOKEINTERFACE, HANDLER, "invoke",
            "(Ljava/lang/Object;L" + METHOD + ";[Ljava/lang/Object;)Ljava/lang/Object;", true);
        Type ret = Type.getReturnType(pm.descriptor());
        unboxReturn(mv, ret);
        mv.visitLabel(end);

        mv.visitLabel(rethrow);
        mv.visitInsn(ATHROW);

        mv.visitLabel(wrap);
        mv.visitVarInsn(ASTORE, 1);
        mv.visitTypeInsn(NEW, "java/lang/reflect/UndeclaredThrowableException");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/reflect/UndeclaredThrowableException", "<init>",
            "(Ljava/lang/Throwable;)V", false);
        mv.visitInsn(ATHROW);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitClinit(ClassWriter cw, String owner, List<ProxyMethod> list) {
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        for (int i = 0; i < list.size(); i++) {
            ProxyMethod pm = list.get(i);
            mv.visitLdcInsn(Type.getObjectType(pm.owner()));
            mv.visitLdcInsn(pm.name());
            Type[] args = Type.getArgumentTypes(pm.descriptor());
            pushInt(mv, args.length);
            mv.visitTypeInsn(ANEWARRAY, "java/lang/Class");
            for (int a = 0; a < args.length; a++) {
                mv.visitInsn(DUP);
                pushInt(mv, a);
                pushClass(mv, args[a]);
                mv.visitInsn(AASTORE);
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)L" + METHOD + ";", false);
            mv.visitFieldInsn(PUTSTATIC, owner, "m" + i, "L" + METHOD + ";");
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void pushClass(MethodVisitor mv, Type t) {
        String wrapper = wrapperOf(t);
        if (wrapper != null) {
            mv.visitFieldInsn(GETSTATIC, wrapper, "TYPE", "Ljava/lang/Class;");
        } else {
            mv.visitLdcInsn(t);
        }
    }

    private static void box(MethodVisitor mv, Type t) {
        String wrapper = wrapperOf(t);
        if (wrapper == null) return;
        mv.visitMethodInsn(INVOKESTATIC, wrapper, "valueOf", "(" + t.getDescriptor() + ")L" + wrapper + ";", false);
    }

    private static void unboxReturn(MethodVisitor mv, Type ret) {
        if (ret.getSort() == Type.VOID) {
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
            return;
        }
        String wrapper = wrapperOf(ret);
        if (wrapper != null) {
            mv.visitTypeInsn(CHECKCAST, wrapper);
            mv.visitMethodInsn(INVOKEVIRTUAL, wrapper, ret.getClassName() + "Value", "()" + ret.getDescriptor(), false);
        } else if (!"java/lang/Object".equals(ret.getInternalName())) {
            mv.visitTypeInsn(CHECKCAST, ret.getSort() == Type.ARRAY ? ret.getDescriptor() : ret.getInternalName());
        }
        mv.visitInsn(ret.getOpcode(IRETURN));
    }

    private static String wrapperOf(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
    }

    private static void pushInt(MethodVisitor mv, int v) {
        if (v >= -1 && v <= 5) mv.visitInsn(ICONST_0 + v);
        else if (v <= Byte.MAX_VALUE) mv.visitIntInsn(BIPUSH, v);
        else mv.visitIntInsn(SIPUSH, v);
    }
}

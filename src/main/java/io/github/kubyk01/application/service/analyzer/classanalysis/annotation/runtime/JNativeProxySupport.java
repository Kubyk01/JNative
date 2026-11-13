package io.github.kubyk01.application.service.analyzer.classanalysis.annotation.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

public final class JNativeProxySupport {

    public static final String MARKER = "$JNativeProxy$";

    private JNativeProxySupport() {}

    public static String proxyClassName(String[] interfaceNames) {
        String first = interfaceNames[0];
        int dot = first.lastIndexOf('.');
        String pkg = dot < 0 ? "" : first.substring(0, dot + 1);
        String key = String.join(",", interfaceNames);
        return pkg + MARKER + Integer.toHexString(key.hashCode());
    }

    public static Constructor<?> proxyConstructor(ClassLoader loader, Class<?>[] interfaces) {
        if (interfaces.length == 0) {
            throw new IllegalArgumentException("JNative: proxy without interfaces is not supported");
        }
        String[] names = new String[interfaces.length];
        for (int i = 0; i < interfaces.length; i++) {
            names[i] = interfaces[i].getName();
        }
        String className = proxyClassName(names);
        Class<?> proxyClass;
        try {
            proxyClass = Class.forName(className, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("JNative: no precompiled proxy class for interfaces ["
                + String.join(", ", names) + "]; declare them in META-INF/native-image/proxy-config.json", e);
        }
        try {
            return proxyClass.getConstructor(InvocationHandler.class);
        } catch (NoSuchMethodException e) {
            throw new InternalError("JNative: proxy class " + className + " has no InvocationHandler constructor", e);
        }
    }

    public static boolean isProxyClass(Class<?> cl) {
        return cl != null && cl.getSuperclass() == Proxy.class && cl.getName().contains(MARKER);
    }
}

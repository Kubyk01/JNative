package io.github.kubyk01.application.service.analyzer.reachabilityanalysis.annotation.runtime;

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

}

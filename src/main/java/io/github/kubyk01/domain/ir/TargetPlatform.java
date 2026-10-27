/* path: src/main/java/io/github/kubyk01/domain/ir/TargetPlatform.java */
package io.github.kubyk01.domain.ir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class TargetPlatform {
    public static final String FALLBACK_DATALAYOUT =
        "e-m:e-p270:32:32-p271:64:64-i64:64-f80:128-n8:16:32:64-S128";

    private static final Map<String, String> KNOWN_DATALAYOUTS = buildKnownDataLayouts();

    private static Map<String, String> buildKnownDataLayouts() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("x86_64-pc-linux-gnu", FALLBACK_DATALAYOUT);
        m.put("x86_64-unknown-linux-gnu", FALLBACK_DATALAYOUT);
        m.put("x86_64-linux-gnu", FALLBACK_DATALAYOUT);

        // ---- Windows (MSVC) ----
        m.put("x86_64-pc-windows-msvc",
            "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");
        // ---- Windows (MinGW-w64 / GNU) ----
        m.put("x86_64-w64-windows-gnu",
            "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");
        m.put("x86_64-pc-windows-gnu",
            "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");
        m.put("x86_64-w64-mingw32",
            "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");

        m.put("x86_64-apple-darwin",
            "e-m:o-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");

        m.put("aarch64-unknown-linux-gnu",
            "e-m:e-i8:8:32-i16:16:32-i64:64-i128:128-n32:64-S128");
        m.put("aarch64-pc-linux-gnu",
            "e-m:e-i8:8:32-i16:16:32-i64:64-i128:128-n32:64-S128");
        m.put("aarch64-pc-windows-msvc",
            "e-m:w-i64:64-i128:128-n32:64-S128");
        m.put("aarch64-w64-windows-gnu",
            "e-m:w-i64:64-i128:128-n32:64-S128");
        m.put("aarch64-w64-mingw32",
            "e-m:w-i64:64-i128:128-n32:64-S128");
        m.put("arm64-apple-darwin",
            "e-m:o-i64:64-i128:128-n32:64-S128");

        m.put("i686-pc-windows-msvc",
            "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");
        m.put("i686-w64-windows-gnu",
            "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");
        m.put("i686-w64-mingw32",
            "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");
        m.put("i686-pc-linux-gnu", FALLBACK_DATALAYOUT);

        m.put("riscv64-unknown-linux-gnu",
            "e-m:e-p:64:64-i64:64-i128:128-n32:64-S128");
        m.put("powerpc64le-unknown-linux-gnu", "e-m:e-i64:64-n32:64-S128");
        m.put("powerpc64-unknown-linux-gnu", "E-m:e-i64:64-n32:64-S128");
        m.put("s390x-ibm-linux-gnu", "E-m:e-i64:64-f80:128-n8:16:32:64-S128");
        m.put("armv7-unknown-linux-gnueabihf",
            "e-m:e-p:32:32-i64:64-v128:64:128-a:0:32-n32-S64");

        return Collections.unmodifiableMap(m);
    }

    private final String triple;
    private final String dataLayout;

    private TargetPlatform(String triple, String dataLayout) {
        this.triple = triple;
        this.dataLayout = dataLayout;
    }

    public static TargetPlatform resolve(String targetTriple,
                                         String targetDataLayout) {
        boolean hasTriple =
            targetTriple != null && !targetTriple.isBlank();
        boolean hasLayout =
            targetDataLayout != null && !targetDataLayout.isBlank();

        if (!hasTriple && !hasLayout) {
            return detectFromBuildHost();
        }

        String triple = hasTriple ? targetTriple.trim() : null;
        if (triple != null && isWindowsMsvcTriple(triple) && !isWindowsHost()) {
            String allowMsvc = System.getenv("JNATIVE_ALLOW_MSVC_CROSS");
            if (allowMsvc == null || allowMsvc.isEmpty() || "0".equals(allowMsvc)) {
                String mingw = substituteMingwForMsvc(triple);
                System.err.println(
                    "WARNING: target triple '" + triple + "' selects the MSVC ABI. "
                        + "The Microsoft CRT is not available on a non-Windows host; "
                        + "cross-compiling to it from this machine is impossible. "
                        + "Substituting '" + mingw + "' (MinGW-w64). "
                        + "Install 'mingw-w64' (e.g. `sudo apt install mingw-w64`) "
                        + "if clang complains that the MinGW headers are missing. "
                        + "Set JNATIVE_ALLOW_MSVC_CROSS=1 to disable this substitution.");
                triple = mingw;
            }
        }

        if (triple != null && !hasLayout) {
            return ofTriple(triple);
        }

        TargetPlatform detected = detectFromBuildHost();
        String resolvedTriple = hasTriple ? triple : detected.triple;
        String layout = targetDataLayout.trim();
        return new TargetPlatform(resolvedTriple, layout);
    }

    private static boolean isWindowsMsvcTriple(String triple) {
        String t = triple.toLowerCase(Locale.ROOT);
        return t.contains("-windows-msvc") || t.endsWith("-windows-msvc");
    }

    private static boolean isWindowsHost() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private static String substituteMingwForMsvc(String msvcTriple) {
        String arch = msvcTriple.substring(0, msvcTriple.indexOf('-'));
        return switch (arch) {
            case "x86_64" -> "x86_64-w64-windows-gnu";
            case "aarch64", "arm64" -> "aarch64-w64-windows-gnu";
            case "i686", "i386", "i586" -> "i686-w64-windows-gnu";
            default -> arch + "-w64-windows-gnu";
        };
    }

    public static TargetPlatform of(String triple, String dataLayout) {
        TargetPlatform detected = detectFromBuildHost();
        String t = (triple != null && !triple.isBlank())
            ? triple.trim()
            : detected.triple;
        String d;
        if (dataLayout != null && !dataLayout.isBlank()) {
            d = dataLayout.trim();
        } else if (t.equals(detected.triple)) {
            d = detected.dataLayout;
        } else {
            d = lookupDataLayout(t);
        }
        return new TargetPlatform(t, d);
    }

    public static TargetPlatform ofTriple(String triple) {
        if (triple == null || triple.isBlank()) return detectFromBuildHost();
        String t = triple.trim();
        return new TargetPlatform(t, lookupDataLayout(t));
    }

    public static TargetPlatform detectFromBuildHost() {
        String osName = safeProperty("os.name");
        String osArch = safeProperty("os.arch");
        String arch = normalizeArch(osArch);
        String triple = detectTriple(osName, arch);
        return new TargetPlatform(triple, lookupDataLayout(triple));
    }

    private static String safeProperty(String key) {
        String v = System.getProperty(key);
        return (v == null) ? "" : v.toLowerCase(Locale.ROOT);
    }

    private static String normalizeArch(String arch) {
        return switch (arch) {
            case "amd64", "x86_64", "x64", "x86-64" -> "x86_64";
            case "x86", "i386", "i486", "i586", "i686", "x86-32" -> "i686";
            case "aarch64", "arm64" -> "aarch64";
            case "arm", "armv7", "armv7l", "armhf" -> "armv7";
            case "ppc64le", "powerpc64le" -> "powerpc64le";
            case "ppc64", "powerpc64" -> "powerpc64";
            case "s390x" -> "s390x";
            case "riscv64" -> "riscv64";
            default -> arch.isEmpty() ? "x86_64" : arch;
        };
    }

    private static String detectTriple(String osName, String arch) {
        boolean isWindows  = osName.contains("win");
        boolean isMac      = osName.contains("mac")
                          || osName.contains("darwin")
                          || osName.contains("osx");
        boolean isLinux    = osName.contains("linux")
                          || osName.contains("nux")
                          || osName.contains("nix")
                          || osName.isEmpty();
        boolean isFreeBSD  = osName.contains("freebsd");
        boolean isNetBSD   = osName.contains("netbsd");
        boolean isOpenBSD  = osName.contains("openbsd");
        boolean isSolaris  = osName.contains("sunos") || osName.contains("solaris");

        if (isWindows) return arch + "-pc-windows-msvc";
        if (isMac) {
            String appleArch = arch.equals("aarch64") ? "arm64" : arch;
            return appleArch + "-apple-darwin";
        }
        if (isLinux) return arch + "-pc-linux-gnu";
        if (isFreeBSD) return arch + "-unknown-freebsd";
        if (isNetBSD) return arch + "-unknown-netbsd";
        if (isOpenBSD) return arch + "-unknown-openbsd";
        if (isSolaris) return arch + "-sun-solaris";
        return arch + "-pc-linux-gnu";
    }

    private static String lookupDataLayout(String triple) {
        String exact = KNOWN_DATALAYOUTS.get(triple);
        if (exact != null) return exact;

        String candidate = triple;
        int lastDash = candidate.lastIndexOf('-');
        while (lastDash > 0) {
            candidate = candidate.substring(0, lastDash);
            String hit = KNOWN_DATALAYOUTS.get(candidate);
            if (hit != null) return hit;
            lastDash = candidate.lastIndexOf('-');
        }

        String arch = triple;
        int firstDash = arch.indexOf('-');
        if (firstDash > 0) arch = arch.substring(0, firstDash);
        return genericDataLayoutForArch(arch);
    }

    private static String genericDataLayoutForArch(String arch) {
        return switch (arch) {
            case "x86_64", "amd64" ->
                "e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128";
            case "i686", "i586", "i486", "i386", "x86" ->
                "e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128";
            case "aarch64", "arm64" ->
                "e-m:e-i8:8:32-i16:16:32-i64:64-i128:128-n32:64-S128";
            case "armv7", "arm" ->
                "e-m:e-p:32:32-i64:64-v128:64:128-a:0:32-n32-S64";
            case "riscv64" ->
                "e-m:e-p:64:64-i64:64-i128:128-n32:64-S128";
            case "powerpc64le" -> "e-m:e-i64:64-n32:64-S128";
            case "powerpc64" -> "E-m:e-i64:64-n32:64-S128";
            case "s390x" -> "E-m:e-i64:64-f80:128-n8:16:32:64-S128";
            default -> FALLBACK_DATALAYOUT;
        };
    }

    public String triple() { return triple; }
    public String dataLayout() { return dataLayout; }

    public boolean isWindows() {
        String t = triple.toLowerCase(Locale.ROOT);
        return t.contains("-windows-") || t.endsWith("-windows")
            || t.contains("-w64-windows-") || t.contains("-w64-mingw32");
    }

    public boolean isDarwin() {
        String t = triple.toLowerCase(Locale.ROOT);
        return t.contains("-darwin") || t.contains("-apple-") || t.contains("-macosx");
    }

    public String nativeLibrarySuffix() {
        if (isWindows()) return ".dll";
        if (isDarwin())  return ".dylib";
        return ".so";
    }

    public String executableSuffix() {
        return isWindows() ? ".exe" : "";
    }

    public String preamble() {
        return "target datalayout = \"" + dataLayout + "\"\n"
             + "target triple = \"" + triple + "\"\n\n";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TargetPlatform other)) return false;
        return triple.equals(other.triple) && dataLayout.equals(other.dataLayout);
    }

    @Override
    public int hashCode() { return 31 * triple.hashCode() + dataLayout.hashCode(); }

    @Override
    public String toString() {
        return "TargetPlatform{triple=" + triple + ", dataLayout=" + dataLayout + '}';
    }
}

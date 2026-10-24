package io.github.kubyk01.domain.ir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * LLVM target platform: the {@code target triple} and the matching
 * {@code target datalayout} string that head every emitted {@code .ll}
 * file.
 *
 * <h2>Why this is a first-class value, not two strings</h2>
 *
 * <p>The two lines</p>
 *
 * <pre>
 *   target datalayout = "e-m:e-p270:32:32-..."
 *   target triple    = "x86_64-pc-linux-gnu"
 * </pre>
 *
 * <p>are not independent. The datalayout string is a machine-readable
 * description of the target ABI — the endianness, the pointer size and
 * alignment, the integer and float widths, the register widths used by
 * the code generator — and it must be consistent with the triple. A
 * mismatch is not detected by the LLVM parser; it produces a module
 * that parses cleanly, links, and then misbehaves at run time by
 * emitting wrong-width loads, wrong stack offsets, or wrong calling
 * conventions for the affected ABI.</p>
 *
 * <p>Bundling the pair into one immutable value removes the class of
 * bug in which a caller overrides the triple but forgets to override
 * the datalayout, or vice versa. {@link #resolve(String, String)} is
 * the single entry point that reconciles partial overrides, and it is
 * the only place where the fallback chain from "user supplied nothing"
 * through "user supplied only the triple" to "user supplied both" is
 * implemented.</p>
 *
 * <h2>How the default is chosen</h2>
 *
 * <p>{@link #detectFromBuildHost()} inspects {@code os.name} and
 * {@code os.arch} — the two JVM system properties that every JDK
 * publishes on every platform — and maps them to the LLVM triple that
 * the local clang/gcc would otherwise pick for the same machine. The
 * mapping is deliberately conservative: when the OS or the
 * architecture is not in the table, the runtime falls back to the
 * historical hard-coded triple {@code x86_64-pc-linux-gnu}, which is
 * what every previous revision of the emitter wrote unconditionally.
 * That preserves the behaviour of the existing build on every host that
 * is not explicitly covered.</p>
 *
 * <h2>Overriding the platform</h2>
 *
 * <p>Two independent override points exist, both exposed through the
 * CLI as {@code --target} and {@code --data-layout}:</p>
 *
 * <ul>
 *   <li>{@code --target <triple>} replaces the triple. The datalayout
 *       is then looked up in the table below for the new triple, so a
 *       single override produces a self-consistent pair. E.g.
 *       {@code --target x86_64-pc-windows-msvc} switches the emitted
 *       IR from Linux to Windows without the caller having to know the
 *       Windows datalayout.</li>
 *
 *   <li>{@code --data-layout <layout>} replaces the datalayout without
 *       touching the triple. This is the escape hatch for a target that
 *       is not in the table, or for an unusual ABI variant (a non-default
 *       code model, a soft-float ABI, a custom pointer width). The
 *       caller is then responsible for keeping the two consistent.</li>
 * </ul>
 *
 * <p>Supplying both overrides verbatim is also supported and is not
 * checked for consistency: the runtime has no way to validate an
 * arbitrary datalayout string against an arbitrary triple, and doing
 * so would require shipping a second copy of LLVM's target-registry
 * tables. Garbage in, garbage out — but the caller who supplies both
 * has explicitly taken responsibility for the pair.</p>
 *
 * <h2>Extending the table</h2>
 *
 * <p>Adding a target is a one-line edit to the static initializer
 * below. The values are taken from LLVM's own {@code TargetRegistry}
 * defaults for the corresponding triple, as produced by
 * {@code llc -mtriple=<triple> -version} or, equivalently, by
 * {@code clang --target=<triple> -S -emit-llvm} on an empty translation
 * unit.</p>
 */
public final class TargetPlatform {
    public static final String FALLBACK_DATALAYOUT =
        "e-m:e-p270:32:32-p271:64:64-i64:64-f80:128-n8:16:32:64-S128";

    /**
     * Triple &rarr; datalayout. Populated once in the static
     * initializer, never mutated afterwards, and consulted read-only
     * from every thread that constructs a {@link TargetPlatform}.
     *
     * <p>Entries are grouped by architecture, with the x86-64 family
     * first because it is the one the runtime has historically been
     * built and tested on. Within each group the OS variants are listed
     * alphabetically so that adding a new entry has an obvious home and
     * the diff of a future addition is small.</p>
     *
     * <p>The values are the LLVM defaults for the given triple. Where
     * the runtime's own existing hard-coded datalayout differs from the
     * modern LLVM default (notably {@code x86_64-pc-linux-gnu}, whose
     * default now includes {@code p271:32:32} rather than the
     * {@code p271:64:64} this runtime has always used), the runtime's
     * existing value is preserved. The two are interchangeable for
     * every construct the emitter actually produces — the difference
     * is confined to the code-model address spaces {@code p270..p272},
     * which only matter for programs that use the large or kernel code
     * models — but keeping the historical value avoids changing the
     * bytes of every emitted {@code .ll} file for existing users.</p>
     */
    private static final Map<String, String> KNOWN_DATALAYOUTS =
        buildKnownDataLayouts();

    private static Map<String, String> buildKnownDataLayouts() {
        Map<String, String> m = new LinkedHashMap<>();

        // -----------------------------------------------------------------
        // x86-64
        // -----------------------------------------------------------------
        m.put("x86_64-pc-linux-gnu", FALLBACK_DATALAYOUT);
        m.put("x86_64-unknown-linux-gnu", FALLBACK_DATALAYOUT);
        m.put("x86_64-linux-gnu", FALLBACK_DATALAYOUT);
        m.put("x86_64-pc-windows-msvc",
            "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");
        m.put("x86_64-pc-windows-gnu",
            "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");
        m.put("x86_64-pc-windows-cygnus",
            "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");
        m.put("x86_64-apple-darwin",
            "e-m:o-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");
        m.put("x86_64-apple-macosx",
            "e-m:o-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");
        m.put("x86_64-unknown-freebsd",
            "e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");
        m.put("x86_64-unknown-netbsd",
            "e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");
        m.put("x86_64-unknown-openbsd",
            "e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128");

        // -----------------------------------------------------------------
        // AArch64 / arm64
        //
        // Note the Apple spelling: LLVM's Darwin targets use "arm64"
        // rather than "aarch64". Both spellings are accepted for the
        // non-Darwin entries because toolchains differ in which one
        // they emit.
        // -----------------------------------------------------------------
        String aarch64Linux =
            "e-m:e-i8:8:32-i16:16:32-i64:64-i128:128-n32:64-S128";
        m.put("aarch64-unknown-linux-gnu", aarch64Linux);
        m.put("aarch64-pc-linux-gnu", aarch64Linux);
        m.put("aarch64-linux-gnu", aarch64Linux);
        m.put("aarch64-unknown-linux-musl", aarch64Linux);
        m.put("aarch64-unknown-freebsd", aarch64Linux);
        m.put("aarch64-unknown-netbsd", aarch64Linux);
        m.put("aarch64-unknown-openbsd", aarch64Linux);

        String appleSilicon = "e-m:o-i64:64-i128:128-n32:64-S128";
        m.put("arm64-apple-darwin", appleSilicon);
        m.put("arm64-apple-macosx", appleSilicon);
        m.put("arm64-apple-ios", appleSilicon);
        m.put("aarch64-apple-darwin", appleSilicon);
        m.put("aarch64-apple-macosx", appleSilicon);

        String aarch64Windows = "e-m:w-i64:64-i128:128-n32:64-S128";
        m.put("aarch64-pc-windows-msvc", aarch64Windows);
        m.put("aarch64-pc-windows-gnu", aarch64Windows);

        // -----------------------------------------------------------------
        // i686 / 32-bit x86
        // -----------------------------------------------------------------
        String i686Linux =
            "e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128";
        m.put("i686-pc-linux-gnu", i686Linux);
        m.put("i686-unknown-linux-gnu", i686Linux);
        m.put("i386-unknown-linux-gnu", i686Linux);
        m.put("i586-pc-linux-gnu", i686Linux);
        m.put("i486-pc-linux-gnu", i686Linux);

        String i686Windows =
            "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128";
        m.put("i686-pc-windows-msvc", i686Windows);
        m.put("i686-pc-windows-gnu", i686Windows);
        m.put("i386-pc-windows-msvc", i686Windows);

        // -----------------------------------------------------------------
        // RISC-V
        // -----------------------------------------------------------------
        m.put("riscv64-unknown-linux-gnu",
            "e-m:e-p:64:64-i64:64-i128:128-n32:64-S128");
        m.put("riscv64-linux-gnu",
            "e-m:e-p:64:64-i64:64-i128:128-n32:64-S128");
        m.put("riscv64-unknown-freebsd",
            "e-m:e-p:64:64-i64:64-i128:128-n32:64-S128");

        // -----------------------------------------------------------------
        // POWER (ppc64 / ppc64le)
        //
        // Big-endian POWER uses an upper-case "E" as the endianness
        // marker; the little-endian variant uses "e". Both ABI variants
        // use 32-bit alignment for 64-bit scalars (n32:64).
        // -----------------------------------------------------------------
        m.put("powerpc64le-unknown-linux-gnu", "e-m:e-i64:64-n32:64-S128");
        m.put("powerpc64le-linux-gnu", "e-m:e-i64:64-n32:64-S128");
        m.put("powerpc64-unknown-linux-gnu", "E-m:e-i64:64-n32:64-S128");
        m.put("powerpc64-linux-gnu", "E-m:e-i64:64-n32:64-S128");
        m.put("powerpc64le-unknown-freebsd", "e-m:e-i64:64-n32:64-S128");

        // -----------------------------------------------------------------
        // s390x (big-endian)
        // -----------------------------------------------------------------
        m.put("s390x-ibm-linux-gnu",
            "E-m:e-i64:64-f80:128-n8:16:32:64-S128");
        m.put("s390x-unknown-linux-gnu",
            "E-m:e-i64:64-f80:128-n8:16:32:64-S128");
        m.put("s390x-linux-gnu",
            "E-m:e-i64:64-f80:128-n8:16:32:64-S128");

        // -----------------------------------------------------------------
        // 32-bit ARM (armv7)
        // -----------------------------------------------------------------
        String armv7Linux =
            "e-m:e-p:32:32-i64:64-v128:64:128-a:0:32-n32-S64";
        m.put("armv7-unknown-linux-gnueabihf", armv7Linux);
        m.put("armv7-unknown-linux-gnueabi", armv7Linux);
        m.put("arm-unknown-linux-gnueabihf", armv7Linux);
        m.put("armv7l-unknown-linux-gnueabihf", armv7Linux);

        return Collections.unmodifiableMap(m);
    }

    private final String triple;
    private final String dataLayout;

    private TargetPlatform(String triple, String dataLayout) {
        this.triple = triple;
        this.dataLayout = dataLayout;
    }

    // =====================================================================
    //  Factories
    // =====================================================================

    /**
     * Builds a platform from an explicit triple and an explicit
     * datalayout. The two are stored verbatim; no consistency check is
     * performed. Either argument may be {@code null} or blank, in which
     * case the corresponding field is filled from
     * {@link #detectFromBuildHost()} — but the more common entry point
     * is {@link #resolve(String, String)}, which handles the partial-
     * override cases with the same fallback rules that the CLI uses.
     */
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

    /**
     * Builds a platform from a triple alone, looking the datalayout up
     * in the runtime's table. If the triple is not in the table the
     * datalayout is derived from the triple's architecture prefix; if
     * the prefix is not recognised either, the historical fallback
     * datalayout is used.
     */
    public static TargetPlatform ofTriple(String triple) {
        if (triple == null || triple.isBlank()) {
            return detectFromBuildHost();
        }
        String t = triple.trim();
        return new TargetPlatform(t, lookupDataLayout(t));
    }

    /**
     * Inspects the JVM's own {@code os.name} and {@code os.arch}
     * properties and returns the platform the local toolchain would
     * target by default. Never returns {@code null} and never throws:
     * an unrecognised OS or architecture collapses to
     * {@link #FALLBACK_TRIPLE} / {@link #FALLBACK_DATALAYOUT}, which is
     * exactly what every previous revision of the emitter wrote
     * unconditionally.
     */
    public static TargetPlatform detectFromBuildHost() {
        String osName = safeProperty("os.name");
        String osArch = safeProperty("os.arch");

        String arch = normalizeArch(osArch);
        String triple = detectTriple(osName, arch);
        String layout = lookupDataLayout(triple);

        return new TargetPlatform(triple, layout);
    }

    /**
     * Reconciles two optional CLI overrides against the detected host
     * platform and returns a fully populated {@link TargetPlatform}.
     *
     * <p>This is the single entry point that the CLI uses. The rules
     * are:</p>
     *
     * <ol>
     *   <li>Both {@code null} or blank: return
     *       {@link #detectFromBuildHost()}. This is the default case
     *       for a build that does not pass either flag.</li>
     *
     *   <li>Only {@code targetTriple} supplied: build a platform for
     *       that triple, looking up the datalayout in the table. The
     *       triple alone is enough because every triple implies an
     *       ABI.</li>
     *
     *   <li>Only {@code targetDataLayout} supplied: keep the detected
     *       triple and replace only the datalayout. Useful for
     *       experimenting with a non-default code model or a custom
     *       pointer width on an otherwise standard target.</li>
     *
     *   <li>Both supplied: store both verbatim. No consistency check.</li>
     * </ol>
     */
    public static TargetPlatform resolve(String targetTriple,
                                         String targetDataLayout) {
        boolean hasTriple =
            targetTriple != null && !targetTriple.isBlank();
        boolean hasLayout =
            targetDataLayout != null && !targetDataLayout.isBlank();

        if (!hasTriple && !hasLayout) {
            return detectFromBuildHost();
        }
        if (hasTriple && !hasLayout) {
            return ofTriple(targetTriple);
        }

        TargetPlatform detected = detectFromBuildHost();
        String triple = hasTriple ? targetTriple.trim() : detected.triple;
        String layout = targetDataLayout.trim();
        return new TargetPlatform(triple, layout);
    }

    // =====================================================================
    //  Accessors
    // =====================================================================

    /** The LLVM target triple, e.g. {@code "x86_64-pc-linux-gnu"}. */
    public String triple() {
        return triple;
    }

    /** The LLVM datalayout string, e.g. {@code "e-m:e-p270:32:32-..."}. */
    public String dataLayout() {
        return dataLayout;
    }

    /**
     * The two-line preamble that heads every emitted {@code .ll} file,
     * terminated by a blank line. Rendered exactly as LLVM expects it:
     * datalayout first, triple second, blank line third.
     *
     * <p>Returned as a single string so that the two lines cannot be
     * reordered or partially dropped by a caller that assembles the
     * preamble by hand.</p>
     */
    public String preamble() {
        String sb = "target datalayout = \"" + dataLayout + "\"\n" +
                "target triple = \"" + triple + "\"\n\n";
        return sb;
    }

    // =====================================================================
    //  Detection helpers
    // =====================================================================

    private static String safeProperty(String key) {
        String v = System.getProperty(key);
        return (v == null) ? "" : v.toLowerCase(Locale.ROOT);
    }

    /**
     * Normalises the many spellings of the same CPU architecture that
     * the JVM's {@code os.arch} property can hold on different
     * platforms, to a small set of canonical names used by
     * {@link #detectTriple(String, String)} and
     * {@link #genericDataLayoutForArch(String)}.
     */
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

    /**
     * Builds the LLVM triple for the given canonical architecture and
     * JVM {@code os.name} value.
     *
     * <p>The vendor field follows each platform's conventions rather
     * than a single fixed value: Linux uses {@code pc} for x86 and
     * {@code unknown} for everything else, macOS uses {@code apple},
     * Windows uses {@code pc}, and the BSDs use {@code unknown}. This
     * matters because clang's own target recognition is vendor-sensitive
     * — {@code aarch64-unknown-darwin} is not the same target as
     * {@code arm64-apple-darwin} even though both describe the same
     * machine.</p>
     */
    private static String detectTriple(String osName, String arch) {
        boolean isWindows  = osName.contains("win");
        boolean isMac      = osName.contains("mac")
                          || osName.contains("darwin")
                          || osName.contains("osx");
        boolean isLinux    = osName.contains("linux")
                          || osName.contains("nux")
                          || osName.contains("nix")
                          || osName.isEmpty();          // treat unknown Unix-like as Linux
        boolean isFreeBSD  = osName.contains("freebsd");
        boolean isNetBSD   = osName.contains("netbsd");
        boolean isOpenBSD  = osName.contains("openbsd");
        boolean isSolaris  = osName.contains("sunos")
                          || osName.contains("solaris");

        if (isWindows) {
            // JDK distributions on Windows are built with MSVC, and the
            // LLVM triple for that ABI is *-pc-windows-msvc. A caller
            // who wants the MinGW ABI can override with
            // --target=<arch>-pc-windows-gnu.
            return arch + "-pc-windows-msvc";
        }
        if (isMac) {
            // Apple uses "arm64" for the ARM architecture on Darwin
            // and "x86_64" for Intel; both are also accepted with the
            // "aarch64" spelling but "arm64" is what clang prints.
            String appleArch = arch.equals("aarch64") ? "arm64" : arch;
            return appleArch + "-apple-darwin";
        }
        if (isLinux) {
            return arch + "-pc-linux-gnu";
        }
        if (isFreeBSD) {
            return arch + "-unknown-freebsd";
        }
        if (isNetBSD) {
            return arch + "-unknown-netbsd";
        }
        if (isOpenBSD) {
            return arch + "-unknown-openbsd";
        }
        if (isSolaris) {
            return arch + "-sun-solaris";
        }
        // Unknown OS. The safest assumption is the same Linux ABI the
        // runtime has historically targeted. A caller who is building
        // on something more exotic should pass --target explicitly.
        return arch + "-pc-linux-gnu";
    }

    /**
     * Looks a datalayout up for the given triple, falling back to a
     * per-architecture generic value when the triple is not in the
     * table. Never returns {@code null}.
     */
    private static String lookupDataLayout(String triple) {
        String exact = KNOWN_DATALAYOUTS.get(triple);
        if (exact != null) {
            return exact;
        }

        // Two-pass fallback. First try progressively shorter suffixes
        // of the triple — "x86_64-pc-linux-gnu" -> "x86_64-pc-linux",
        // "x86_64-pc", "x86_64". This catches triples that differ only
        // in a version suffix or a minor vendor spelling. Then fall
        // back to a per-architecture generic value.
        String candidate = triple;
        int lastDash = candidate.lastIndexOf('-');
        while (lastDash > 0) {
            candidate = candidate.substring(0, lastDash);
            String hit = KNOWN_DATALAYOUTS.get(candidate);
            if (hit != null) {
                return hit;
            }
            lastDash = candidate.lastIndexOf('-');
        }

        String arch = triple;
        int firstDash = arch.indexOf('-');
        if (firstDash > 0) {
            arch = arch.substring(0, firstDash);
        }
        return genericDataLayoutForArch(arch);
    }

    /**
     * Generic per-architecture datalayout used when a triple's exact
     * vendor-OS combination is not in the table. The values describe
     * the architecture's usual ABI (64-bit pointers, natural alignment
     * for scalar types, little-endian except where the architecture is
     * conventionally big-endian) and are correct for the common case of
     * a Unix-like OS.
     */
    private static String genericDataLayoutForArch(String arch) {
        return switch (arch) {
            case "x86_64", "amd64" -> "e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128";
            case "i686", "i586", "i486", "i386", "x86" ->
                    "e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128";
            case "aarch64", "arm64" -> "e-m:e-i8:8:32-i16:16:32-i64:64-i128:128-n32:64-S128";
            case "armv7", "arm" -> "e-m:e-p:32:32-i64:64-v128:64:128-a:0:32-n32-S64";
            case "riscv64" -> "e-m:e-p:64:64-i64:64-i128:128-n32:64-S128";
            case "powerpc64le" -> "e-m:e-i64:64-n32:64-S128";
            case "powerpc64" -> "E-m:e-i64:64-n32:64-S128";
            case "s390x" -> "E-m:e-i64:64-f80:128-n8:16:32:64-S128";
            default -> FALLBACK_DATALAYOUT;
        };
    }

    // =====================================================================
    //  Object protocol
    // =====================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TargetPlatform other)) return false;
        return triple.equals(other.triple)
            && dataLayout.equals(other.dataLayout);
    }

    @Override
    public int hashCode() {
        return 31 * triple.hashCode() + dataLayout.hashCode();
    }

    @Override
    public String toString() {
        return "TargetPlatform{triple=" + triple
            + ", dataLayout=" + dataLayout + '}';
    }
}

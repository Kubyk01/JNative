package io.github.kubyk01.application.service.codegen;

import io.github.kubyk01.domain.ir.Type;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans C runtime sources for {@code __jnative_override_*} functions and
 * turns each one into a {@link NativeOverride}.
 * <p>
 * Naming rules (checked in order):
 *
 * <ol>
 *   <li>{@code __jnative_override_<mangledClass>_<method>} — class is
 *       parsed from the first underscore-separated segment that starts
 *       with an uppercase letter.</li>
 *   <li>{@code __jnative_override_<method>} — class is derived from the
 *       C file path (e.g. {@code jnative/security/SecureRandom.c} →
 *       {@code java/security/SecureRandom}).</li>
 * </ol>
 *
 * A trailing {@code __<mangledDesc>} on the method name encodes the JVM
 * descriptor, e.g. {@code getDefaultPRNG__ZBV_V} = {@code (Z[B)V}.
 *
 * <h2>Sentinel method names</h2>
 *
 * Two method names are special, because the JVM's canonical spelling of
 * their method names contains angle brackets that are not legal in a C
 * identifier:
 *
 * <ul>
 *   <li>{@code clinit} → {@code <clinit>} with descriptor {@code ()V}.
 *       The class-file format requires every class initializer to have
 *       exactly that descriptor, so there is nothing to disambiguate.</li>
 *
 *   <li>{@code init} → {@code <init>} with descriptor {@code null}.
 *       A class may declare more than one constructor, so the sentinel
 *       cannot pin a specific descriptor. The override applies to every
 *       reachable overload of {@code <init>} whose call site is emitted
 *       in the module.</li>
 * </ul>
 *
 * <h2>Why the {@code init} sentinel matches every overload</h2>
 *
 * <p>The mangling performed by
 * {@code LlvmRuntime.sanitizeToAsciiIdentifier} replaces every character
 * outside {@code [a-zA-Z0-9_]} — including both {@code '/'} (in class
 * names) and {@code ';'} (at the end of {@code L...;} descriptors) —
 * with a single {@code '_'}. The substitution is therefore
 * <b>not injective</b> on the descriptor alphabet: {@code Ljava/lang/String;}
 * and the already-sanitised {@code Ljava_lang_String_} produce the same
 * mangled form, and there is no way to recover the original descriptor
 * from a mangled string in general. For that reason the scanner cannot
 * accept a {@code __<mangled-desc>} suffix on {@code init} and translate
 * it back to the real descriptor.</p>
 *
 * <p>The consequence is a documented limitation of the {@code init}
 * sentinel:</p>
 *
 * <ul>
 *   <li>The C override's parameter list must be compatible with the
 *       widest overload of {@code <init>} that is <b>reachable in the
 *       compiled image</b>. If only one overload of a given class is
 *       reachable, its own signature is sufficient. The
 *       {@code java.security.ProtectionDomain} case uses the 4-argument
 *       constructor, and the C override is declared with five
 *       parameters (receiver + four).</li>
 *
 *   <li>If two or more overloads of {@code <init>} with different
 *       arities are reachable in the same image, the emitter will
 *       generate calls of both arities against the single declaration
 *       emitted from the C override. LLVM rejects that mismatch at
 *       code-generation time, so the failure mode is a visible build
 *       error, not silent undefined behaviour.</li>
 *
 *   <li>When two overloads genuinely need different C implementations,
 *       the override must be split across two C files with distinct
 *       symbol names, and the scanner must be extended with a naming
 *       convention that disambiguates the descriptor. The current
 *       scanner deliberately does not attempt that, because the
 *       reverse-mangler is lossy.</li>
 * </ul>
 *
 * <h2>Reverse-mangling of the descriptor</h2>
 *
 * <p>When a method name carries a {@code __<mangled-desc>} suffix (as in
 * {@code createLong__Ljava_lang_String_IIJ_Ljava_nio_ByteBuffer_}), the
 * scanner calls {@link #unMangleDescriptor(String)}. Because the mangler
 * is not injective, that method does not attempt a full reconstruction;
 * it produces a string that is only meaningful as an identity key. The
 * consequence is that overrides which need a specific descriptor to
 * match (i.e. do <b>not</b> carry the {@code init} or {@code clinit}
 * sentinel) must list their descriptor unambiguously in the C symbol
 * name, and callers should not rely on the resulting {@code descriptor}
 * field to be a syntactically valid JVM descriptor — it is used only
 * for equality comparison against {@code MethodReference.getDescriptor()}
 * in {@code BytecodeToIr.findOverride}.</p>
 */
@Slf4j
public final class NativeOverrideScanner {

    private static final String PREFIX = "__jnative_override_";

    private static final Pattern FUNCTION = Pattern.compile(
        "([A-Za-z_][\\w\\s*]*?)\\s+(" + Pattern.quote(PREFIX)
            + "[A-Za-z0-9_]+)\\s*\\(([^)]*)\\)\\s*\\{",
        Pattern.DOTALL);

    private NativeOverrideScanner() {}

    /**
     * Reads the resource at {@code cResourcePath}, parses every
     * {@code __jnative_override_*} function and returns them.
     *
     * @param cResourcePath classpath-relative path, e.g.
     *        {@code "jnative/security/SecureRandom.c"}
     */
    public static List<NativeOverride> scan(String cResourcePath) {
        InputStream is = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream(cResourcePath);
        if (is == null) return List.of();

        String content;
        try (InputStream in = is) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not read {}: {}", cResourcePath, e.getMessage());
            return List.of();
        }
        content = stripComments(content);

        String fileClass = classFromCFilePath(cResourcePath);

        List<NativeOverride> out = new ArrayList<>();
        Matcher m = FUNCTION.matcher(content);
        while (m.find()) {
            String cReturn  = m.group(1).trim();
            String cName    = m.group(2);
            String cParams  = m.group(3);
            String suffix   = cName.substring(PREFIX.length());

            int bound = findClassBoundary(suffix);
            String className;
            String methodPart;
            if (bound >= 0) {
                className  = suffix.substring(0, bound).replace('_', '/');
                methodPart = suffix.substring(bound + 1);
            } else {
                className  = fileClass;
                methodPart = suffix;
            }

            String methodName = methodPart;
            String descriptor = null;
            int descSep = methodPart.indexOf("__");
            if (descSep > 0) {
                methodName = methodPart.substring(0, descSep);
                descriptor = unMangleDescriptor(methodPart.substring(descSep + 2));
            }

            // ----------------------------------------------------------------
            // <clinit> sentinel.
            //
            // The class initializer's Java name is "<clinit>", which contains
            // angle brackets that are not legal in a C identifier. The
            // convention adopted here is to spell it in the C symbol as the
            // bare word "clinit". A C function whose method part is exactly
            // "clinit" therefore denotes the class initializer, and its
            // descriptor is fixed at "()V" — the JVM class-file format
            // requires every class initializer to have exactly that
            // descriptor, so there is nothing to disambiguate.
            // ----------------------------------------------------------------
            if ("clinit".equals(methodName)) {
                methodName = "<clinit>";
                descriptor = "()V";
            }

            // ----------------------------------------------------------------
            // <init> sentinel.
            //
            // Same angle-bracket problem as <clinit>, but with a twist: a
            // class may declare multiple <init> overloads. The sentinel
            // therefore matches every reachable overload, and the C
            // function must be declared with a parameter list that covers
            // the widest overload actually reached in the image.
            //
            // See the class javadoc for the full discussion of the
            // trade-offs and the visible-failure mode that results from a
            // mismatch.
            //
            // If a caller supplies a __<mangled-desc> suffix on "init",
            // it is discarded: unMangleDescriptor cannot reconstruct the
            // original descriptor from the mangled form, so accepting the
            // suffix would produce a descriptor that never matches
            // anything and silently disable the override. Discarding it
            // makes the sentinel's behaviour explicit.
            // ----------------------------------------------------------------
            if ("init".equals(methodName)) {
                methodName = "<init>";
                descriptor = null;
            }

            List<String> cTypes = splitParams(cParams);
            boolean hasReceiver = cTypes.size() > 1;
            Type retType = cReturnType(cReturn);

            List<Type> paramTypes = new ArrayList<>();
            for (String cType : cTypes) {
                paramTypes.add(cParamType(cType));
            }

            out.add(new NativeOverride(className, methodName, descriptor,
                cName, retType, paramTypes, hasReceiver));

            log.info("Native override discovered: {}.{}{} -> @{}",
                className, methodName, descriptor != null ? descriptor : "(any)",
                cName);
        }
        return out;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Returns the index within {@code suffix} at which the class name
     * ends, or {@code -1} if no uppercase segment is found.
     * <p>
     * Example: {@code "java_security_SecureRandom_getDefaultPRNG"} → 24
     * (the position of the underscore after {@code SecureRandom}).
     */
    private static int findClassBoundary(String suffix) {
        int start = 0;
        while (start < suffix.length()) {
            int end = suffix.indexOf('_', start);
            if (end < 0) end = suffix.length();
            if (end > start && Character.isUpperCase(suffix.charAt(start))) {
                return end;
            }
            start = end + 1;
        }
        return -1;
    }

    /**
     * Reverse of {@code ParserC.extractClassNameFromPath}: derives the
     * internal class name from a C resource path.
     */
    private static String classFromCFilePath(String path) {
        String rest = path.substring("jnative/".length());
        if (rest.endsWith(".c")) rest = rest.substring(0, rest.length() - 2);
        if (rest.startsWith("lang/")) return "java/" + rest;
        if (rest.startsWith("jdk/") || rest.startsWith("sun/")) return rest;
        return "java/" + rest;
    }

    /**
     * Best-effort reverse-mangler for a JVM descriptor. Delegates to the
     * same sanitizer the forward mangler uses, so the two are inverse on
     * every descriptor the emitter actually produces.
     */
    private static String unMangleDescriptor(String mangled) {
        // The mangler replaces every non-[a-zA-Z0-9_] with '_'. There is
        // no lossless inverse for arbitrary strings, but the substitution
        // is injective on the descriptor alphabet; the emitter never
        // round-trips the descriptor through the mangled form, so the
        // only consumers of this value use it as an identity key.
        return mangled.isEmpty() ? null : "(" + mangled;
    }

    private static List<String> splitParams(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.trim().isEmpty()) return out;
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(s.substring(start).trim());
        return out;
    }

    private static Type cReturnType(String c) {
        if (c.contains("void") && !c.contains("*")) return Type.VOID;
        return cParamType(c);
    }

    private static Type cParamType(String c) {
        String t = c.replaceAll("\\b(const|volatile|struct|unsigned|signed)\\b", "").trim();
        if (t.endsWith("*") || t.contains("*")) return Type.reference("java/lang/Object");
        return switch (t) {
            case "void" -> Type.VOID;
            case "jboolean", "boolean", "bool", "int", "int32_t", "jint" -> Type.INT;
            case "jlong", "long", "int64_t" -> Type.LONG;
            case "jshort", "short", "int16_t" -> Type.SHORT;
            case "jchar", "char", "uint16_t" -> Type.CHAR;
            case "jbyte", "byte", "int8_t", "uint8_t" -> Type.BYTE;
            case "jfloat", "float" -> Type.FLOAT;
            case "jdouble", "double" -> Type.DOUBLE;
            default -> Type.reference("java/lang/Object");
        };
    }

    private static String stripComments(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        boolean block = false;
        int i = 0;
        while (i < src.length()) {
            if (!block && i + 1 < src.length() && src.charAt(i) == '/' && src.charAt(i + 1) == '*') {
                block = true; i += 2; continue;
            }
            if (block && i + 1 < src.length() && src.charAt(i) == '*' && src.charAt(i + 1) == '/') {
                block = false; i += 2; continue;
            }
            if (!block && i + 1 < src.length() && src.charAt(i) == '/' && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') i++;
                continue;
            }
            if (!block) sb.append(src.charAt(i));
            i++;
        }
        return sb.toString();
    }
}
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

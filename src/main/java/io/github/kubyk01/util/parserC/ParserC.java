package io.github.kubyk01.util.parserC;

import lombok.Getter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for C files containing JNative native method implementations.
 * Extracts function signatures with the __jnative_fn_ prefix and converts them
 * into Java method information (class, name, descriptor) and the full C function name.
 */
public class ParserC {

    @Getter
    public static class NativeMethodInfo {
        private final String className;          // fully qualified class name with dots, e.g. "java.lang.invoke.VarHandle"
        private final String methodName;
        private final String descriptor;
        private final String cReturnType;
        private final List<CParameter> parameters;
        private final String fullFunctionName;   // e.g. "__jnative_fn_java_lang_invoke_VarHandle_get___BI_I"

        public NativeMethodInfo(String className, String methodName, String descriptor,
                                String cReturnType, List<CParameter> parameters,
                                String fullFunctionName) {
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.cReturnType = cReturnType;
            this.parameters = parameters;
            this.fullFunctionName = fullFunctionName;
        }

        @Override
        public String toString() {
            return String.format("%s.%s%s (%s) -> %s [%s]",
                className, methodName, descriptor, parameters, cReturnType, fullFunctionName);
        }
    }

    @Getter
    public static class CParameter {
        private final String type;
        private final String name;

        public CParameter(String type, String name) {
            this.type = type.trim();
            this.name = name.trim();
        }

        @Override
        public String toString() {
            return type + " " + name;
        }
    }

    private static final Pattern FUNCTION_PATTERN = Pattern.compile(
        "([\\w\\s*]+?)\\s+(__jnative_fn_\\w+)\\s*\\(([^)]*)\\)\\s*\\{",
        Pattern.DOTALL
    );

    /**
     * Parses a C file identified by resourcePath.
     *
     * @param resourcePath can be:
     *                     - a path to a .c file (e.g. "jnative/jdk/internal/misc/Unsafe.c")
     *                     - a function name (e.g. "__jnative_fn_java_lang_Class_getEnclosingMethod0____Ljava_lang_Object_")
     *                     - a class name (e.g. "java.lang.Object")
     * @return list of {@link NativeMethodInfo}
     * @throws IOException if the file is not found or a read error occurs
     */
    public static List<NativeMethodInfo> parse(String resourcePath) throws IOException {
        String cFilePath = resolveCFilePath(resourcePath);
        if (cFilePath == null) {
            throw new IllegalArgumentException("Cannot resolve .c file for: " + resourcePath);
        }
        try (InputStream is = ParserC.class.getClassLoader().getResourceAsStream(cFilePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + cFilePath);
            }
            String content = readContent(is);
            content = removeComments(content);
            String className = extractClassNameFromPath(cFilePath);
            String classWithUnderscores = className.replace('.', '_');

            List<NativeMethodInfo> result = new ArrayList<>();
            Matcher matcher = FUNCTION_PATTERN.matcher(content);
            while (matcher.find()) {
                String cReturnType = matcher.group(1).trim();
                String fullFuncName = matcher.group(2);
                String paramString = matcher.group(3);

                // Extract method name using the class prefix
                String methodName = extractMethodName(fullFuncName, classWithUnderscores);
                if (methodName == null || methodName.isEmpty()) {
                    continue;
                }

                List<CParameter> params = parseParameters(paramString);
                String descriptor = buildDescriptor(cReturnType, params);

                result.add(new NativeMethodInfo(className, methodName, descriptor,
                    cReturnType, params, fullFuncName));
            }
            return result;
        }
    }

    /**
     * Resolves the .c file path in resources from the input string.
     */
    private static String resolveCFilePath(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        if (input.endsWith(".c")) {
            return input;
        }
        if (input.startsWith("__jnative_fn_")) {
            String className = extractClassNameFromFunctionName(input);
            if (className == null) {
                return null;
            }
            return classNameToPath(className);
        }
        // Assume it is a class name (with dots)
        if (input.contains(".")) {
            return classNameToPath(input);
        }
        return null;
    }

    /**
     * Converts a fully qualified class name to a .c file path.
     */
    private static String classNameToPath(String className) {
        String path = className.replace('.', '/');
        if (path.startsWith("java/lang/")) {
            // for java.lang.* use jnative/lang/ (strip "java/")
            return "jnative/" + path.substring("java/".length()) + ".c";
        } else {
            return "jnative/" + path + ".c";
        }
    }

    public static String getResourcePathForClass(String className) {
        return classNameToPath(className);
    }

    /**
     * Extracts the fully qualified class name from a .c file path.
     */
    private static String extractClassNameFromPath(String cFilePath) {
        if (!cFilePath.startsWith("jnative/")) {
            // fallback
            String name = cFilePath;
            if (name.endsWith(".c")) {
                name = name.substring(0, name.length() - 2);
            }
            return name.replace('/', '.');
        }
        String rest = cFilePath.substring("jnative/".length());
        if (rest.endsWith(".c")) {
            rest = rest.substring(0, rest.length() - 2);
        }
        if (rest.startsWith("lang/")) {
            return "java.lang." + rest.substring("lang/".length()).replace('/', '.');
        } else {
            return rest.replace('/', '.');
        }
    }

    /**
     * Extracts the class name from a function name of the form __jnative_fn_<class>_<method>__<descriptor>
     * (only used for fallback, not for method name extraction).
     */
    private static String extractClassNameFromFunctionName(String fullFuncName) {
        if (!fullFuncName.startsWith("__jnative_fn_")) {
            return null;
        }
        String withoutPrefix = fullFuncName.substring("__jnative_fn_".length());
        int doubleUnderscore = withoutPrefix.lastIndexOf("__");
        if (doubleUnderscore <= 0) {
            return null;
        }
        String beforeDesc = withoutPrefix.substring(0, doubleUnderscore);
        int lastUnderscore = beforeDesc.lastIndexOf('_');
        if (lastUnderscore < 0) {
            return null;
        }
        String classWithUnderscores = beforeDesc.substring(0, lastUnderscore);
        return classWithUnderscores.replace('_', '.');
    }

    /**
     * Extracts the method name from the full function name using the underscore-delimited class name.
     */
    private static String extractMethodName(String fullFuncName, String classWithUnderscores) {
        if (!fullFuncName.startsWith("__jnative_fn_")) {
            return null;
        }
        String mangledPart = fullFuncName.substring("__jnative_fn_".length());
        // Find the class prefix in the mangled part
        int classStart = mangledPart.indexOf(classWithUnderscores);
        if (classStart != 0) {
            // If not at start, fallback to old heuristic
            return extractMethodNameFallback(fullFuncName);
        }
        int classEnd = classStart + classWithUnderscores.length();
        // There should be an underscore after the class name
        if (classEnd >= mangledPart.length() || mangledPart.charAt(classEnd) != '_') {
            return extractMethodNameFallback(fullFuncName);
        }
        int afterClass = classEnd + 1; // skip the underscore
        // Find the next underscore which separates method name from descriptor
        int methodEnd = mangledPart.indexOf('_', afterClass);
        if (methodEnd < 0) {
            return extractMethodNameFallback(fullFuncName);
        }
        String methodName = mangledPart.substring(afterClass, methodEnd);
        if (methodName.isEmpty()) {
            return extractMethodNameFallback(fullFuncName);
        }
        return methodName;
    }

    /**
     * Fallback method name extraction (old heuristic) – used if the primary method fails.
     */
    private static String extractMethodNameFallback(String fullFuncName) {
        if (!fullFuncName.startsWith("__jnative_fn_")) {
            return null;
        }
        String withoutPrefix = fullFuncName.substring("__jnative_fn_".length());
        int doubleUnderscore = withoutPrefix.lastIndexOf("__");
        if (doubleUnderscore > 0) {
            String beforeDesc = withoutPrefix.substring(0, doubleUnderscore);
            int lastUnderscore = beforeDesc.lastIndexOf('_');
            if (lastUnderscore < 0) return null;
            return beforeDesc.substring(lastUnderscore + 1);
        } else {
            int lastUnderscore = withoutPrefix.lastIndexOf('_');
            if (lastUnderscore < 0) return null;
            return withoutPrefix.substring(lastUnderscore + 1);
        }
    }

    /**
     * Parses a comma-separated parameter string.
     */
    private static List<CParameter> parseParameters(String paramString) {
        List<CParameter> params = new ArrayList<>();
        if (paramString.trim().isEmpty()) {
            return params;
        }
        String[] parts = paramString.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int lastSpace = part.lastIndexOf(' ');
            if (lastSpace < 0) {
                params.add(new CParameter(part, ""));
            } else {
                String type = part.substring(0, lastSpace).trim();
                String name = part.substring(lastSpace + 1).trim();
                params.add(new CParameter(type, name));
            }
        }
        return params;
    }

    /**
     * Builds a Java method descriptor from C types.
     */
    private static String buildDescriptor(String cReturnType, List<CParameter> params) {
        // If there is exactly one parameter and its type contains "void" and "*", it is a polymorphic wrapper
        if (params.size() == 1) {
            String pType = params.getFirst().getType().trim();
            if (pType.contains("void") && pType.contains("*")) {
                return "(" + cTypeToDescriptor(cReturnType); // descriptor without parameters
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (CParameter p : params) {
            sb.append(cTypeToDescriptor(p.getType()));
        }
        sb.append(')');
        sb.append(cTypeToDescriptor(cReturnType));
        return sb.toString();
    }

    /**
     * Converts a C type to a Java descriptor.
     */
    private static String cTypeToDescriptor(String cType) {
        cType = cType.trim();
        cType = cType.replaceAll("\\b(const|volatile)\\b", "").trim();

        if (cType.endsWith("**")) {
            return "[Ljava/lang/Object;";
        } else if (cType.endsWith("*")) {
            if (cType.startsWith("void")) {
                return "Ljava/lang/Object;";
            }
            if (cType.contains("struct") || cType.contains("Reflection")) {
                return "Ljava/lang/Object;";
            }
            String base = cType.replace("*", "").trim();
            return switch (base) {
                case "int", "int32_t", "jint" -> "[I";
                case "long", "int64_t", "jlong" -> "[J";
                case "char", "uint16_t", "jchar" -> "[C";
                case "short", "int16_t", "jshort" -> "[S";
                case "byte", "int8_t", "uint8_t", "jbyte" -> "[B";
                case "float", "jfloat" -> "[F";
                case "double", "jdouble" -> "[D";
                case "boolean", "jboolean" -> "[Z";
                default -> "[Ljava/lang/Object;";
            };
        }

        return switch (cType) {
            case "void" -> "V";
            case "int", "int32_t", "jint" -> "I";
            case "long", "int64_t", "jlong" -> "J";
            case "char", "uint16_t", "jchar" -> "C";
            case "short", "int16_t", "jshort" -> "S";
            case "byte", "int8_t", "uint8_t", "jbyte" -> "B";
            case "float", "jfloat" -> "F";
            case "double", "jdouble" -> "D";
            case "boolean", "jboolean" -> "Z";
            default -> {
                if (cType.startsWith("struct") || cType.contains("Reflection") || cType.contains("Class")) {
                    yield "Ljava/lang/Object;";
                }
                yield "Ljava/lang/Object;";
            }
        };
    }

    private static String readContent(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String removeComments(String code) {
        StringBuilder result = new StringBuilder();
        boolean inBlockComment = false;
        int i = 0;
        while (i < code.length()) {
            if (!inBlockComment && i + 1 < code.length() && code.charAt(i) == '/' && code.charAt(i + 1) == '*') {
                inBlockComment = true;
                i += 2;
                continue;
            }
            if (inBlockComment && i + 1 < code.length() && code.charAt(i) == '*' && code.charAt(i + 1) == '/') {
                inBlockComment = false;
                i += 2;
                continue;
            }
            if (!inBlockComment && i + 1 < code.length() && code.charAt(i) == '/' && code.charAt(i + 1) == '/') {
                while (i < code.length() && code.charAt(i) != '\n') {
                    i++;
                }
                if (i < code.length() && code.charAt(i) == '\n') {
                    result.append('\n');
                }
                continue;
            }
            if (!inBlockComment) {
                result.append(code.charAt(i));
            }
            i++;
        }
        return result.toString();
    }
}
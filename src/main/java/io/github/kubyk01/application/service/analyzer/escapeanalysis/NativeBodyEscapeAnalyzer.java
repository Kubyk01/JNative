package io.github.kubyk01.application.service.analyzer.escapeanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class NativeBodyEscapeAnalyzer {

    private static final int MAX_HELPER_DEPTH = 64;

    private final DependencyResolver resolver;
    private final Map<String, EscapeSummary> cache = new ConcurrentHashMap<>();
    private final Map<String, EscapeSummary> helperCache = new ConcurrentHashMap<>();
    private final Map<String, String> sourceByOwner = new ConcurrentHashMap<>();
    private final Map<String, Map<String, CFunction>> functionsByOwner = new ConcurrentHashMap<>();
    private final ThreadLocal<ArrayDeque<String>> inProgressLocal =
        ThreadLocal.withInitial(ArrayDeque::new);

    public NativeBodyEscapeAnalyzer(DependencyResolver resolver) {
        this.resolver = resolver;
    }

    public EscapeSummary summaryFor(String calleeName) {
        EscapeSummary cached = cache.get(calleeName);
        if (cached != null) return cached;

        ArrayDeque<String> inProgress = inProgressLocal.get();
        String cycleKey = "top:" + calleeName;
        if (inProgress.contains(cycleKey)) return allEscape();

        inProgress.push(cycleKey);
        try {
            EscapeSummary summary = compute(calleeName);
            cache.put(calleeName, summary);
            return summary;
        } finally {
            inProgress.pop();
        }
    }

    private EscapeSummary compute(String calleeName) {
        int paren = calleeName.indexOf('(');
        if (paren < 0) return allEscape();
        int dot = calleeName.lastIndexOf('.', paren);
        if (dot < 0) return allEscape();

        String owner = calleeName.substring(0, dot);
        String name  = calleeName.substring(dot + 1, paren);
        String desc  = calleeName.substring(paren);

        ClassNode ownerNode = resolver.getClassNode(owner);
        if (ownerNode == null) return allEscape();

        MethodNode method = null;
        for (MethodNode m : ownerNode.getMethods()) {
            if (m.getName().equals(name) && m.getDescriptor().equals(desc)) {
                method = m;
                break;
            }
        }
        if (method == null) return allEscape();

        Map<String, CFunction> functions = functionsFor(owner);
        if (functions.isEmpty()) return allEscape();

        String mangled = "__jnative_"
            + io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime
            .mangleMethod(owner, name, desc);

        CFunction function = functions.get(mangled);
        if (function == null) {
            String override = "__jnative_override_"
                + owner.replace('/', '_') + "_" + name;
            function = functions.get(override);
        }
        if (function == null) return allEscape();

        Set<Integer> escapedToHeap  = new HashSet<>();
        Set<Integer> returned       = new HashSet<>();
        GlobalFlag   unusedFlag     = new GlobalFlag(); // reserved; not consumed

        analyzeBody(function, functions, owner,
            escapedToHeap, returned, unusedFlag);

        return EscapeSummary.builder()
            .paramsEscapedToHeap(escapedToHeap)
            .paramsReturned(returned)
            .returnsObject(function.returnsPointer)
            .build();
    }

    private static final class GlobalFlag { boolean value; }

    private static EscapeSummary allEscape() {
        return EscapeSummary.builder().unresolved(true).build();
    }

    // ---- Source loading and function parsing (unchanged) --------------

    private String loadSource(String ownerInternalName) {
        String cached = sourceByOwner.get(ownerInternalName);
        if (cached != null) return cached.isEmpty() ? null : cached;

        String path = cResourcePathForClass(ownerInternalName);
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                sourceByOwner.put(ownerInternalName, "");
                return null;
            }
            String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            sourceByOwner.put(ownerInternalName, text);
            return text;
        } catch (IOException e) {
            sourceByOwner.put(ownerInternalName, "");
            return null;
        }
    }

    private Map<String, CFunction> functionsFor(String ownerInternalName) {
        Map<String, CFunction> cached = functionsByOwner.get(ownerInternalName);
        if (cached != null) return cached;

        String text = loadSource(ownerInternalName);
        if (text == null) {
            Map<String, CFunction> empty = Collections.emptyMap();
            functionsByOwner.put(ownerInternalName, empty);
            return empty;
        }

        Map<String, CFunction> parsed = parseAllFunctions(text);
        functionsByOwner.put(ownerInternalName, parsed);
        return parsed;
    }

    private static String cResourcePathForClass(String ownerInternalName) {
        if (ownerInternalName.startsWith("java/")) {
            return "jnative/" + ownerInternalName.substring("java/".length()) + ".c";
        }
        return "jnative/" + ownerInternalName + ".c";
    }

    private static final class CFunction {
        final String name;
        final boolean returnsPointer;
        final LinkedHashMap<String, Integer> paramIndex = new LinkedHashMap<>();
        String body;
        CFunction(String name, boolean returnsPointer) {
            this.name = name;
            this.returnsPointer = returnsPointer;
        }
    }

    private static Map<String, CFunction> parseAllFunctions(String text) {
        Map<String, CFunction> result = new HashMap<>();
        int n = text.length();
        int i = 0;
        int braceDepth = 0;

        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') { i = skipLineComment(text, i + 2, n); continue; }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') { i = skipBlockComment(text, i, n); continue; }
            if (c == '"') { i = skipStringLiteral(text, i, n); continue; }
            if (c == '\'') { i = skipCharLiteral(text, i, n); continue; }
            if (c == '{') { braceDepth++; i++; continue; }
            if (c == '}') { if (braceDepth > 0) braceDepth--; i++; continue; }
            if (braceDepth != 0) { i++; continue; }
            if (!isIdentifierStart(c)) { i++; continue; }

            int nameStart = i;
            int nameEnd = i;
            while (nameEnd < n && isIdentifierChar(text.charAt(nameEnd))) nameEnd++;
            String name = text.substring(nameStart, nameEnd);

            int j = nameEnd;
            while (j < n && Character.isWhitespace(text.charAt(j))) j++;
            if (j >= n || text.charAt(j) != '(') { i = nameEnd; continue; }

            int parenEnd = matchClose(text, j, '(', ')');
            if (parenEnd < 0) { i = nameEnd; continue; }

            int k = parenEnd + 1;
            while (k < n && Character.isWhitespace(text.charAt(k))) k++;
            if (k >= n || text.charAt(k) != '{') { i = parenEnd + 1; continue; }

            int braceEnd = matchClose(text, k, '{', '}');
            if (braceEnd < 0) break;

            int typeEnd = nameStart;
            while (typeEnd > 0 && Character.isWhitespace(text.charAt(typeEnd - 1))) typeEnd--;
            int typeStart = typeEnd;
            while (typeStart > 0) {
                char tc = text.charAt(typeStart - 1);
                if (isIdentifierChar(tc) || tc == '*' || Character.isWhitespace(tc)) typeStart--;
                else break;
            }
            String returnType = text.substring(typeStart, typeEnd).trim();
            boolean returnsPointer = returnType.contains("*");

            CFunction cf = new CFunction(name, returnsPointer);
            parseParamsInto(cf, text.substring(j + 1, parenEnd));
            cf.body = text.substring(k + 1, braceEnd);
            result.putIfAbsent(name, cf);

            i = braceEnd + 1;
        }
        return result;
    }

    private static void parseParamsInto(CFunction cf, String params) {
        int index = 0;
        for (String raw : splitArguments(params)) {
            String p = raw.trim();
            if (p.isEmpty() || p.equals("void")) continue;
            String[] parts = p.split("\\s+");
            String pname = parts[parts.length - 1];
            while (pname.startsWith("*")) pname = pname.substring(1);
            if (!pname.isEmpty()) cf.paramIndex.put(pname, index++);
        }
    }

    private static int matchClose(String text, int openPos, char open, char close) {
        int depth = 0;
        int n = text.length();
        int i = openPos;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') { i = skipLineComment(text, i + 2, n); continue; }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') { i = skipBlockComment(text, i, n); continue; }
            if (c == '"') { i = skipStringLiteral(text, i, n); continue; }
            if (c == '\'') { i = skipCharLiteral(text, i, n); continue; }
            if (c == open) depth++;
            else if (c == close) { depth--; if (depth == 0) return i; }
            i++;
        }
        return -1;
    }

    private static int skipLineComment(String text, int i, int n) {
        while (i < n && text.charAt(i) != '\n') i++;
        return i;
    }

    private static int skipBlockComment(String text, int i, int n) {
        i += 2;
        while (i + 1 < n) {
            if (text.charAt(i) == '*' && text.charAt(i + 1) == '/') return i + 2;
            i++;
        }
        return n;
    }

    private static int skipStringLiteral(String text, int i, int n) {
        i++;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n) { i += 2; continue; }
            if (c == '"') return i + 1;
            i++;
        }
        return n;
    }

    private static int skipCharLiteral(String text, int i, int n) {
        i++;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n) { i += 2; continue; }
            if (c == '\'') return i + 1;
            i++;
        }
        return n;
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ---- Body analysis -----------------------------------------------

    private static final Pattern RETURN_STMT =
        Pattern.compile("\\breturn\\s+([^;]+);");
    private static final Pattern ASSIGN_STMT =
        Pattern.compile("([^;=()\\s][^;=()]*?)\\s*=\\s*([^;]+?);", Pattern.DOTALL);
    private static final Pattern CALL_EXPR =
        Pattern.compile(
            "\\b(?!if\\b|while\\b|for\\b|switch\\b|return\\b"
                + "|sizeof\\b|else\\b|do\\b|case\\b|default\\b"
                + "|_Static_assert\\b|_Generic\\b|typeof\\b|asm\\b"
                + "|alignof\\b|_Alignof\\b|__attribute__\\b)"
                + "(\\w+)\\s*\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)");

    private void analyzeBody(CFunction function,
                             Map<String, CFunction> functions,
                             String owner,
                             Set<Integer> escapedToHeap,
                             Set<Integer> returned,
                             GlobalFlag unusedFlag) {

        // Rule 1: `return <expr>;` — only when the function returns a pointer.
        // Returning a scalar value is not an escape: the caller receives a
        // value, not a reference.
        if (function.returnsPointer) {
            Matcher returns = RETURN_STMT.matcher(function.body);
            while (returns.find()) {
                String expr = returns.group(1);
                for (Map.Entry<String, Integer> p : function.paramIndex.entrySet()) {
                    if (containsIdentifier(expr, p.getKey())) {
                        returned.add(p.getValue());
                    }
                }
            }
        }

        // Rule 2: `*LHS = RHS;` (dereferenced LHS) — the RHS escapes to heap.
        Matcher assigns = ASSIGN_STMT.matcher(function.body);
        while (assigns.find()) {
            String lhs = assigns.group(1).trim();
            String rhs = assigns.group(2).trim();
            if (!isDereferencedLhs(lhs)) continue;
            for (Map.Entry<String, Integer> p : function.paramIndex.entrySet()) {
                if (containsIdentifier(rhs, p.getKey())) {
                    escapedToHeap.add(p.getValue());
                }
            }
        }

        // Rule 3: calls to helpers / functions.
        Matcher calls = CALL_EXPR.matcher(function.body);
        while (calls.find()) {
            String callee = calls.group(1);
            if (LIBRARY_NON_RETAINING.contains(callee)) continue;

            List<String> args = splitArguments(calls.group(2));

            boolean anyParameterInArgs = false;
            for (String arg : args) {
                for (String paramName : function.paramIndex.keySet()) {
                    if (containsIdentifier(arg, paramName)) {
                        anyParameterInArgs = true;
                        break;
                    }
                }
                if (anyParameterInArgs) break;
            }
            if (!anyParameterInArgs) continue;

            EscapeSummary calleeSummary = resolveHelperSummary(functions, owner, callee);

            if (calleeSummary == null) {
                // Conservative: unknown helper retains the argument.
                for (String arg : args) {
                    for (Map.Entry<String, Integer> p : function.paramIndex.entrySet()) {
                        if (containsIdentifier(arg, p.getKey())) {
                            escapedToHeap.add(p.getValue());
                        }
                    }
                }
                continue;
            }

            for (int i = 0; i < args.size(); i++) {
                boolean escapesToHeap =
                    calleeSummary.getParamsEscapedToHeap().contains(i);
                boolean escapesToStatic =
                    calleeSummary.getParamsEscapedToStatic().contains(i);
                boolean escapesToThread =
                    calleeSummary.getParamsEscapedToThread().contains(i);
                boolean isReturned =
                    calleeSummary.getParamsReturned().contains(i);
                if (!escapesToHeap && !escapesToStatic && !escapesToThread && !isReturned) {
                    continue;
                }
                for (Map.Entry<String, Integer> p : function.paramIndex.entrySet()) {
                    if (containsIdentifier(args.get(i), p.getKey())) {
                        // Static / thread / heap all collapse to "escaped"
                        // here; the C analyzer does not need the distinction
                        // because the caller (`EscapeSummaryBuilder.processCall`)
                        // will look up the caller's own parameter and apply
                        // the status that matches the callee's summary. The
                        // intraprocedural caller uses `escapedToHeap` to
                        // conservatively promote its own local allocations
                        // reachable from the parameter, which is the safe
                        // direction.
                        escapedToHeap.add(p.getValue());
                    }
                }
            }
        }
    }

    private EscapeSummary resolveHelperSummary(Map<String, CFunction> functions,
                                               String owner,
                                               String helperName) {
        CFunction helper = functions.get(helperName);
        if (helper == null) return null;

        String key = owner + "#" + helperName;
        EscapeSummary cached = helperCache.get(key);
        if (cached != null) return cached;

        ArrayDeque<String> inProgress = inProgressLocal.get();
        String cycleKey = "helper:" + key;
        if (inProgress.contains(cycleKey)) return allEscape();
        if (inProgress.size() >= MAX_HELPER_DEPTH) return allEscape();

        inProgress.push(cycleKey);
        try {
            Set<Integer> escapedToHeap = new HashSet<>();
            Set<Integer> returned = new HashSet<>();
            GlobalFlag unusedFlag = new GlobalFlag();

            analyzeBody(helper, functions, owner,
                escapedToHeap, returned, unusedFlag);

            EscapeSummary summary = EscapeSummary.builder()
                .paramsEscapedToHeap(escapedToHeap)
                .paramsReturned(returned)
                .returnsObject(helper.returnsPointer)
                .build();
            helperCache.put(key, summary);
            return summary;
        } finally {
            inProgress.pop();
        }
    }

    // ---- Library non-retaining set (unchanged) -----------------------

    private static final Set<String> LIBRARY_NON_RETAINING = Set.of(
        "memcpy", "memmove", "memset", "memcmp",
        "strlen", "strcmp", "strncmp", "strchr", "strrchr", "strstr",
        "strcpy", "strncpy", "strdup",
        "printf", "fprintf", "snprintf", "fputs", "fwrite",
        "puts", "putchar", "fopen", "fclose", "fscanf", "fgets",
        "malloc", "calloc", "realloc", "free",
        "abort", "exit", "_exit",
        "clock_gettime", "gettimeofday", "time", "localtime_r", "gmtime_r",
        "open", "openat", "close", "read", "write", "lseek",
        "pread", "pwrite", "fstat", "stat", "lstat", "access", "ftruncate",
        "dlopen", "dlsym", "dlclose", "dlerror",
        "pthread_mutex_lock", "pthread_mutex_unlock", "pthread_mutex_init",
        "pthread_cond_wait", "pthread_cond_signal", "pthread_cond_broadcast",
        "pthread_self", "pthread_create",
        "sysconf", "getenv", "getcwd", "gethostname",
        "htonl", "htons", "ntohl", "ntohs",
        "isatty", "ioctl",
        "sscanf", "atoi", "atol", "strtol", "strtoul",
        "setjmp", "_setjmp", "longjmp",
        "getaddrinfo", "freeaddrinfo", "getnameinfo", "if_nametoindex"
    );

    // ---- Low-level helpers (unchanged) ------------------------------

    private static List<String> splitArguments(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private static boolean containsIdentifier(String expr, String id) {
        int from = 0;
        while ((from = expr.indexOf(id, from)) >= 0) {
            boolean leftOk = from == 0 || !isWordChar(expr.charAt(from - 1));
            boolean rightOk = from + id.length() >= expr.length()
                || !isWordChar(expr.charAt(from + id.length()));
            if (leftOk && rightOk) return true;
            from += id.length();
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isDereferencedLhs(String lhs) {
        String t = lhs.trim();
        if (t.isEmpty()) return false;
        if (t.startsWith("*")) return true;
        if (t.contains("->")) return true;
        if (t.contains("[")) return true;
        return t.contains(".");
    }
}
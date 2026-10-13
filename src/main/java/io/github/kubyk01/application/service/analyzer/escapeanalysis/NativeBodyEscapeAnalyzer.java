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

/**
 * Computes a {@link EscapeSummary} for a native Java method by reading
 * the method's C implementation and analyzing which of its parameters
 * can outlive the call.
 *
 * <h2>What "escape" means here</h2>
 *
 * <p>There are exactly two escape routes this analyzer recognizes:</p>
 *
 * <ul>
 *   <li><b>Return escape</b>: the parameter (or an object reachable
 *       from it) is returned to the caller.</li>
 *   <li><b>Heap escape</b>: the parameter is written into a field of
 *       another object, into an array element, or into a global.</li>
 * </ul>
 *
 * <p>Any parameter that is only read, compared, passed to a library
 * function whose contract forbids retaining pointers, or returned
 * through a helper that itself does not retain it, is <b>not</b>
 * escaping and therefore does not need to be pinned to the heap.</p>
 *
 * <h2>How the C source is parsed</h2>
 *
 * <p>The analyzer does <b>not</b> use a regex to find function
 * definitions. A single linear scan of the source text collects every
 * top-level function into a map, which is then consulted by name. The
 * scan is O(N) in the size of the file and uses no backtracking
 * regular expressions, so it can neither run out of Java stack nor
 * blow up on an input whose shape happens to be pathological for a
 * regex engine.</p>
 *
 * <h2>Recursion and cycles</h2>
 *
 * <p>Calls to helper functions defined in the same C translation unit
 * are resolved recursively. Both the top-level callee and every helper
 * push their identity onto a per-thread stack; a second visit of the
 * same identity breaks the cycle by returning the conservative
 * "everything escapes" summary. Helper summaries are cached, so each
 * (owner, helper) pair is analyzed at most once across the whole run,
 * no matter how many different callers reference it.</p>
 *
 * <h2>Thread-safety</h2>
 *
 * <p>One instance is shared by every thread of the Reactor pool that
 * {@link EscapeSummaryBuilder} uses to compute summaries in parallel.
 * All mutable state is either concurrent or thread-local:</p>
 *
 * <ul>
 *   <li>{@link #cache} — {@link ConcurrentHashMap}, deterministic value
 *       per key, so concurrent writes of the same key are benign.</li>
 *   <li>{@link #helperCache} — {@link ConcurrentHashMap}, same
 *       reasoning.</li>
 *   <li>{@link #sourceByOwner} — {@link ConcurrentHashMap}, cache of C
 *       source text keyed by internal class name.</li>
 *   <li>{@link #functionsByOwner} — {@link ConcurrentHashMap}, cache of
 *       the parsed function maps keyed by internal class name.</li>
 *   <li>{@link #inProgressLocal} — {@link ThreadLocal}, per-thread
 *       stack used for cycle detection in the two mutually recursive
 *       analyses.</li>
 * </ul>
 */
@Slf4j
public final class NativeBodyEscapeAnalyzer {

    /**
     * Maximum helper-recursion depth. A C translation unit in this
     * runtime has at most a few dozen helpers, and their call graph is
     * shallow; the limit exists only to keep a pathological input from
     * overflowing the Java stack. Reaching the limit returns the
     * conservative "everything escapes" summary, which is always
     * sound.
     */
    private static final int MAX_HELPER_DEPTH = 64;

    private final DependencyResolver resolver;

    /** Top-level callee name -> its summary. */
    private final Map<String, EscapeSummary> cache = new ConcurrentHashMap<>();

    /** "owner#helper" -> its summary. */
    private final Map<String, EscapeSummary> helperCache = new ConcurrentHashMap<>();

    /** Internal class name -> contents of its .c file (or "" if absent). */
    private final Map<String, String> sourceByOwner = new ConcurrentHashMap<>();

    /** Internal class name -> parsed function map. */
    private final Map<String, Map<String, CFunction>> functionsByOwner =
            new ConcurrentHashMap<>();

    /** "Currently analyzing" stack — one per thread. */
    private final ThreadLocal<ArrayDeque<String>> inProgressLocal =
            ThreadLocal.withInitial(ArrayDeque::new);

    public NativeBodyEscapeAnalyzer(DependencyResolver resolver) {
        this.resolver = resolver;
    }

    // ==================================================================
    //  Public entry point
    // ==================================================================

    /**
     * Returns the escape summary of the given callee, computing and
     * caching it on the first request.
     *
     * @param calleeName the fully qualified callee name in the same form
     *                   the IR uses: {@code "owner.name(desc)"} with
     *                   internal class names separated by {@code '/'},
     *                   e.g. {@code "java/lang/Object.toString()Ljava/lang/String;"}.
     */
    public EscapeSummary summaryFor(String calleeName) {
        EscapeSummary cached = cache.get(calleeName);
        if (cached != null) {
            return cached;
        }

        ArrayDeque<String> inProgress = inProgressLocal.get();
        String cycleKey = "top:" + calleeName;
        if (inProgress.contains(cycleKey)) {
            return allEscape();
        }

        inProgress.push(cycleKey);
        try {
            EscapeSummary summary = compute(calleeName);
            cache.put(calleeName, summary);
            return summary;
        } finally {
            inProgress.pop();
        }
    }

    // ==================================================================
    //  Top-level computation
    // ==================================================================

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

        Set<Integer> escaped  = new HashSet<>();
        Set<Integer> returned = new HashSet<>();
        GlobalFlag   flag     = new GlobalFlag();

        analyzeBody(function, functions, owner, escaped, returned, flag);

        return EscapeSummary.builder()
                .paramsEscaped(escaped)
                .paramsReturned(returned)
                .returnsObject(function.returnsPointer)
                .escapesGlobally(flag.value)
                .build();
    }

    private static final class GlobalFlag {
        boolean value;
    }

    private static EscapeSummary allEscape() {
        return EscapeSummary.builder().escapesGlobally(true).build();
    }

    // ==================================================================
    //  Source loading and parsing — cached per class
    // ==================================================================

    private String loadSource(String ownerInternalName) {
        String cached = sourceByOwner.get(ownerInternalName);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }

        String path = cResourcePathForClass(ownerInternalName);
        try (InputStream is =
                     getClass().getClassLoader().getResourceAsStream(path)) {
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
            return "jnative/"
                    + ownerInternalName.substring("java/".length())
                    + ".c";
        }
        return "jnative/" + ownerInternalName + ".c";
    }

    // ==================================================================
    //  Function model
    // ==================================================================

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

    // ==================================================================
    //  Linear scanner — no backtracking regex is applied to the source
    // ==================================================================

    private static Map<String, CFunction> parseAllFunctions(String text) {
        Map<String, CFunction> result = new HashMap<>();
        int n = text.length();
        int i = 0;
        int braceDepth = 0;

        while (i < n) {
            char c = text.charAt(i);

            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                i = skipLineComment(text, i + 2, n);
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                i = skipBlockComment(text, i, n);
                continue;
            }
            if (c == '"') {
                i = skipStringLiteral(text, i, n);
                continue;
            }
            if (c == '\'') {
                i = skipCharLiteral(text, i, n);
                continue;
            }

            if (c == '{') {
                braceDepth++;
                i++;
                continue;
            }
            if (c == '}') {
                if (braceDepth > 0) braceDepth--;
                i++;
                continue;
            }

            if (braceDepth != 0) {
                i++;
                continue;
            }

            if (!isIdentifierStart(c)) {
                i++;
                continue;
            }

            int nameStart = i;
            int nameEnd = i;
            while (nameEnd < n && isIdentifierChar(text.charAt(nameEnd))) {
                nameEnd++;
            }
            String name = text.substring(nameStart, nameEnd);

            int j = nameEnd;
            while (j < n && Character.isWhitespace(text.charAt(j))) j++;

            if (j >= n || text.charAt(j) != '(') {
                i = nameEnd;
                continue;
            }

            int parenEnd = matchClose(text, j, '(', ')');
            if (parenEnd < 0) {
                i = nameEnd;
                continue;
            }

            int k = parenEnd + 1;
            while (k < n && Character.isWhitespace(text.charAt(k))) k++;

            if (k >= n || text.charAt(k) != '{') {
                i = parenEnd + 1;
                continue;
            }

            int braceEnd = matchClose(text, k, '{', '}');
            if (braceEnd < 0) {
                break;
            }

            int typeEnd = nameStart;
            while (typeEnd > 0 && Character.isWhitespace(text.charAt(typeEnd - 1))) {
                typeEnd--;
            }
            int typeStart = typeEnd;
            while (typeStart > 0) {
                char tc = text.charAt(typeStart - 1);
                if (isIdentifierChar(tc) || tc == '*'
                        || Character.isWhitespace(tc)) {
                    typeStart--;
                } else {
                    break;
                }
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
            if (!pname.isEmpty()) {
                cf.paramIndex.put(pname, index++);
            }
        }
    }

    private static int matchClose(String text, int openPos,
                                  char open, char close) {
        int depth = 0;
        int n = text.length();
        int i = openPos;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                i = skipLineComment(text, i + 2, n);
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                i = skipBlockComment(text, i, n);
                continue;
            }
            if (c == '"') {
                i = skipStringLiteral(text, i, n);
                continue;
            }
            if (c == '\'') {
                i = skipCharLiteral(text, i, n);
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
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
            if (text.charAt(i) == '*' && text.charAt(i + 1) == '/') {
                return i + 2;
            }
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

    // ==================================================================
    //  Body analysis
    // ==================================================================

    private static final Pattern RETURN_STMT =
            Pattern.compile("\\breturn\\s+([^;]+);");

    private static final Pattern ASSIGN_STMT =
            Pattern.compile("([^;=()\\s][^;=()]*?)\\s*=\\s*([^;]+?);",
                    Pattern.DOTALL);

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
                             Set<Integer> escaped,
                             Set<Integer> returned,
                             GlobalFlag globalFlag) {

        // ------------------------------------------------------------------
        // Rule 1 — `return <expr>;` makes every parameter that appears in
        // <expr> both returned and escaped — BUT ONLY WHEN THE FUNCTION
        // ACTUALLY RETURNS A POINTER.
        //
        // A C function whose declared return type carries no '*' cannot
        // hand the caller back a reference to any of its arguments. The
        // caller receives a scalar (int / long / float / double / char /
        // bool), which is a value, not a reference to a live object. From
        // the Java memory model's point of view, nothing reachable from
        // the parameter has escaped into the caller's frame.
        //
        // The concrete failure this guard closes:
        //
        //     int __jnative_fn_java_lang_Object_hashCode___I(void* obj) {
        //         if (!obj) return 0;
        //         return (int)((uintptr_t)obj);
        //     }
        //
        // The return expression mentions `obj`, so the previous,
        // unconditional rule marked `obj` as both escaped AND returned.
        // That propagated up through every caller of hashCode — in
        // particular through the receiver of Object.toString, whose
        // bytecode calls this.hashCode() — and promoted every freshly
        // allocated receiver of toString, including
        //     new Test2(i).toString()
        // in the reported benchmark, to EscapeStatus.GLOBAL. The object
        // was stack-allocatable in principle, but the analysis refused to
        // see it because hashCode appeared to leak its receiver.
        //
        // The condition is intentionally on `function.returnsPointer`,
        // not on "the expression is cast to a pointer": a function that
        // returns `void*` and whose body is
        //     return (void*)(uintptr_t)obj;
        // DOES leak obj (the caller gets a dereferenceable pointer), and
        // IS correctly caught by the check below. A function that returns
        // a plain scalar cannot leak anything, no matter what the return
        // expression contains.
        // ------------------------------------------------------------------
        if (function.returnsPointer) {
            Matcher returns = RETURN_STMT.matcher(function.body);
            while (returns.find()) {
                String expr = returns.group(1);
                for (Map.Entry<String, Integer> p : function.paramIndex.entrySet()) {
                    if (containsIdentifier(expr, p.getKey())) {
                        returned.add(p.getValue());
                        escaped.add(p.getValue());
                    }
                }
            }
        }

        // Rule 2 — `LHS = RHS;` where LHS is a dereference.
        Matcher assigns = ASSIGN_STMT.matcher(function.body);
        while (assigns.find()) {
            String lhs = assigns.group(1).trim();
            String rhs = assigns.group(2).trim();
            if (!isDereferencedLhs(lhs)) continue;

            for (Map.Entry<String, Integer> p : function.paramIndex.entrySet()) {
                if (containsIdentifier(rhs, p.getKey())) {
                    escaped.add(p.getValue());
                }
            }
        }

        // Rule 3 — calls.
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

            EscapeSummary calleeSummary =
                    resolveHelperSummary(functions, owner, callee);

            if (calleeSummary == null) {
                for (String arg : args) {
                    for (Map.Entry<String, Integer> p
                            : function.paramIndex.entrySet()) {
                        if (containsIdentifier(arg, p.getKey())) {
                            escaped.add(p.getValue());
                        }
                    }
                }
                continue;
            }

            for (int i = 0; i < args.size(); i++) {
                if (!calleeSummary.getParamsEscaped().contains(i)
                        && !calleeSummary.getParamsReturned().contains(i)) {
                    continue;
                }
                for (Map.Entry<String, Integer> p
                        : function.paramIndex.entrySet()) {
                    if (containsIdentifier(args.get(i), p.getKey())) {
                        escaped.add(p.getValue());
                    }
                }
            }

            if (calleeSummary.isEscapesGlobally()) {
                globalFlag.value = true;
            }
        }
    }

    // ==================================================================
    //  Helper resolution — cycle-safe and cached
    // ==================================================================

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

        if (inProgress.contains(cycleKey)) {
            return allEscape();
        }
        if (inProgress.size() >= MAX_HELPER_DEPTH) {
            return allEscape();
        }

        inProgress.push(cycleKey);
        try {
            Set<Integer> escaped  = new HashSet<>();
            Set<Integer> returned = new HashSet<>();
            GlobalFlag   flag     = new GlobalFlag();

            analyzeBody(helper, functions, owner, escaped, returned, flag);

            EscapeSummary summary = EscapeSummary.builder()
                    .paramsEscaped(escaped)
                    .paramsReturned(returned)
                    .returnsObject(helper.returnsPointer)
                    .escapesGlobally(flag.value)
                    .build();
            helperCache.put(key, summary);
            return summary;
        } finally {
            inProgress.pop();
        }
    }

    // ==================================================================
    //  Library non-retaining set
    // ==================================================================

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

    // ==================================================================
    //  Low-level helpers
    // ==================================================================

    private static List<String> splitArguments(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;

        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
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
            boolean leftOk =
                    from == 0 || !isWordChar(expr.charAt(from - 1));
            boolean rightOk =
                    from + id.length() >= expr.length()
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
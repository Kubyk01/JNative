package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.domain.ir.Type;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class LlvmRuntime {

    /**
     * Cache from a Java string to its hex-encoded, filename-safe identifier.
     * String literal names are looked up repeatedly during code generation
     * (once per reference to the same literal), so the cache prevents the
     * repeated UTF-8 encoding and hex conversion. ConcurrentHashMap is used
     * because code generation for independent functions may proceed in
     * parallel in future revisions of the emitter.
     */
    private static final ConcurrentHashMap<String, String> STRING_ID_CACHE =
        new ConcurrentHashMap<>();

    /**
     * Replaces every character outside {@code [a-zA-Z0-9_]} with {@code '_'}.
     *
     * <p>Behaviourally identical to {@code s.replaceAll("[^a-zA-Z0-9_]", "_")},
     * but without compiling a {@link Pattern} on every call. The previous
     * implementation invoked {@code replaceAll} three times per
     * {@code mangleMethod}, and {@code mangleMethod} on every CALL instruction
     * of every function evaluation in {@code InterproceduralPointsTo} and
     * {@code EscapeSummaryBuilder}. On a large JDK closure that is millions of
     * {@code Pattern.compile} calls per phase, a noticeable share of the
     * worker threads' hot stack.</p>
     *
     * <p>{@code '/'} is handled by the same rule as any other non-alphanumeric
     * character, so the leading {@code .replace('/', '_')} of the old
     * {@code mangleMethod} is unnecessary and has been removed: it was a pure
     * no-op relative to the final result.</p>
     */
    private static String sanitizeToAsciiIdentifier(String s) {
        if (s == null) return "_";
        int n = s.length();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /**
     * The {@code "owner.name(desc)"} shape with the character set permitted in
     * generated names. Precompiled because {@code String.matches(String)}
     * compiles a fresh {@link Pattern} on every call — and
     * {@link #mangleCallable} is invoked from {@code canonicalCalleeKey} on
     * every CALL site.
     */
    private static final Pattern MANGLED_CALLABLE_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9_/$]+\\.[a-zA-Z0-9_<>$]+\\([^)]*\\)[^)]*$");

    /**
     * Returns the block of {@code declare} lines that every generated module
     * must contain: the set of runtime helpers that the emitted code calls
     * into, together with the libc and pthread functions that those helpers
     * transitively rely on.
     *
     * <p>The declarations are emitted once at the top of the module. They are
     * intentionally declarations only — the definitions live in
     * {@code jnative_runtime.c} and in the per-class native support files, and
     * are linked into the executable together with the LLVM-compiled code.</p>
     *
     * <p>The declaration list is grouped roughly by area so that a reader
     * looking for a specific helper can find it without reading the entire
     * block:</p>
     * <ul>
     *   <li>initialization / diagnostic entry points;</li>
     *   <li>libc allocation and process control;</li>
     *   <li>pthread mutex primitives;</li>
     *   <li>setjmp/longjmp used by the try-guard lowering;</li>
     *   <li>runtime type identity and monitor helpers;</li>
     *   <li>exception construction and dispatch;</li>
     *   <li>lazy {@code <clinit>} state machine;</li>
     *   <li>string conversion and concatenation helpers;</li>
     *   <li>reflection trampolines.</li>
     * </ul>
     */
    public static String getDeclarations() {
        return """
            declare void @__jnative_debug_clinit(i8*)
            declare void @__jnative_init_string_pool(i8**, i32)
            declare i8* @malloc(i64)
            declare i8* @calloc(i64, i64)
            declare void @free(i8*)
            declare void @__jnative_free(i8*)

            declare void @__jnative_begin_shutdown()
            declare i32  @__jnative_dtor_enter(i8*)
            declare void @__jnative_dtor_unclaim(i8*)

            declare i32 @printf(i8*, ...)
            declare void @abort() noreturn
            declare i32 @atexit(void ()*)
            declare i64 @llvm.objectsize.i64.p0i8(i8*, i1)
            declare void @llvm.memset.p0i8.i64(i8* nocapture writeonly, i8, i64, i1 immarg)

            declare i32 @pthread_mutex_lock(i8*)
            declare i32 @pthread_mutex_unlock(i8*)
            declare i32 @pthread_mutex_init(i8*, i8*)
            declare i32 @pthread_mutex_destroy(i8*)
            declare i32 @pthread_mutexattr_init(i8*)
            declare i32 @pthread_mutexattr_settype(i8*, i32)
            declare i32 @pthread_mutexattr_destroy(i8*)

            declare i32 @_setjmp(i8*) returns_twice
            declare void @longjmp(i8*, i32) noreturn

            declare i8* @__jnative_create_string_array(i32, i8**)
            declare i8* @__jnative_make_bootstrap_props(i32, i32, i32, i32, i32, i32, i32, i32, i32, i32)
            declare i8* @__jnative_new_multi_array(i8*, i32, i32*, i32)
            declare void @__jnative_monitor_enter(i8*)
            declare void @__jnative_monitor_exit(i8*)
            declare i1 @__jnative_instanceof(i8*, i8**)
            declare void @__jnative_push_catch(i8*, i8**)
            declare void @__jnative_pop_catch()
            declare void @__jnative_throw_exception(i8*)
            declare i8* @__jnative_get_exception_object()
            declare i1 @__jnative_catch_matches(i8*, i8**)

            declare i8** @__jnative_lookup_itable(%JNativeIfaceMap*, i32)

            ; ----- dispatch receiver resolution -----
            declare i1 @__jnative_is_class_mirror(i8*)
            declare %JNativeVTable* @__jnative_resolve_dispatch_vtable(i8*)

            declare void @__jnative_throw_null_pointer_exception()
            declare void @__jnative_throw_array_index_out_of_bounds()
            declare void @__jnative_throw_class_cast_exception()
            declare void @__jnative_throw_arithmetic_exception()

            declare void @__jnative_throw_exception_ctx(i8*, i8*)
            declare void @__jnative_throw_null_pointer_exception_ctx(i8*, i8*)
            declare void @__jnative_throw_array_index_out_of_bounds_ctx(i8*)
            declare void @__jnative_throw_class_cast_exception_ctx(i8*)
            declare void @__jnative_throw_arithmetic_exception_ctx(i8*)

            declare void @__jnative_unresolved_slot(i8*) noreturn

            declare i32 @__jnative_clinit_enter(i8*)
            declare void @__jnative_clinit_exit(i8*)

            declare i32 @__jnative_unsafe_address_size()
            declare i32 @__jnative_unsafe_page_size()
            declare i32 @__jnative_unsafe_big_endian()
            declare i32 @__jnative_unsafe_unaligned_access()
            declare i32 @__jnative_unsafe_data_cache_line_flush_size()

            declare i8* @__jnative_concat_strings(i32, ...)

            declare i8* @__jnative_value_to_string_int(i32)
            declare i8* @__jnative_value_to_string_long(i64)
            declare i8* @__jnative_value_to_string_float(float)
            declare i8* @__jnative_value_to_string_double(double)
            declare i8* @__jnative_value_to_string_boolean(i32)
            declare i8* @__jnative_value_to_string_char(i32)
            declare i8* @__jnative_value_to_string_byte(i32)
            declare i8* @__jnative_value_to_string_short(i32)
            declare i8* @__jnative_value_to_string_object(i8*)

            declare i8* @__jnative_invoke_method(i8*, i8*, i8**)
            declare i8* @__jnative_new_instance(i8*, i8**)

            declare i8* @__jnative_get_jnienv()
            declare i8* @__jnative_get_thread_jnienv()

            declare i8* @__jnative_jni_resolve(i8*, i8*, i8*, i8*)
            declare void @__jnative_jni_load_all(i8**, i32)

            declare i8* @jnative_static_field_address(i8*, i8*)
            """;
    }

    /**
     * Returns the three LLVM struct type definitions that describe the
     * runtime's virtual-dispatch ABI:
     *
     * <ul>
     *   <li>{@code %JNativeIfaceMapEntry} — a single (interface id, itable)
     *       pair;</li>
     *   <li>{@code %JNativeIfaceMap} — the array of such pairs that a class
     *       carries to describe the interfaces it implements;</li>
     *   <li>{@code %JNativeVTable} — the object header itself: the class's
     *       virtual method table, its interface map, and its internal
     *       name.</li>
     * </ul>
     *
     * <p>The layout of these structures is part of the ABI between the
     * LLVM-generated code and every C file that performs virtual dispatch
     * ({@code jnative_runtime.c}, {@code Object.c}, {@code AccessController.c},
     * etc.). Any change here must be mirrored on the C side simultaneously.</p>
     */
    public static String getVtableTypeDefinition() {
        return """
            %JNativeIfaceMapEntry = type { i32, i8** }
            %JNativeIfaceMap      = type { i32, %JNativeIfaceMapEntry* }
            %JNativeVTable       = type { i8**, %JNativeIfaceMap*, i8* }
            """;
    }

    /**
     * Length in bytes of the string constant that carries {@code s}, including
     * the trailing NUL. Used by the emitters that materialize string literals
     * as {@code [N x i8]} globals.
     */
    public static int typeStringArrayLength(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length + 1;
    }

    /**
     * Returns the hex-encoded identifier used to build LLVM global names for
     * the string {@code s}. Every byte of the UTF-8 encoding is rendered as
     * two hex digits, so the result is guaranteed to be a valid LLVM symbol
     * suffix regardless of the string's contents (quotes, backslashes, UTF-8
     * multibyte sequences, control characters, and so on).
     *
     * <p>The encoding is deterministic and collision-free: two different
     * strings produce two different suffixes, and equal strings produce
     * equal suffixes. That property is what lets the emitter use the suffix
     * directly as part of a global's name without a separate deduplication
     * pass.</p>
     *
     * <p>Results are memoised; string literals are looked up repeatedly
     * during emission, and recomputing the hex encoding on every lookup is
     * pure waste.</p>
     */
    public static String stringIdSuffix(String s) {
        if (s == null) {
            return "_null";
        }
        String cached = STRING_ID_CACHE.get(s);
        if (cached != null) {
            return cached;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            sb.append(Character.forDigit(v >>> 4, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        String result = sb.toString();
        STRING_ID_CACHE.put(s, result);
        return result;
    }

    /**
     * LLVM symbol name of the raw byte-string global for {@code s}. This is
     * the C-string form used whenever native code needs a plain
     * {@code const char*} — for example the arguments to
     * {@code __jnative_throw_null_pointer_exception_ctx}, or the class name
     * passed to the lazy-{@code <clinit>} state machine.
     */
    public static String typeStringGlobalName(String s) {
        return "@.str." + stringIdSuffix(s);
    }

    /**
     * LLVM symbol name (without the leading {@code @}) of the Java String
     * object global for {@code s}. This is the object-form used whenever
     * generated code needs a real {@code java.lang.String} reference — for
     * example when a string literal is pushed onto the operand stack, or
     * when a {@code ldc} is lowered.
     */
    public static String stringObjectGlobalName(String s) {
        return "jstr_" + stringIdSuffix(s);
    }

    /**
     * Returns the LLVM {@code global} definition for the byte-string constant
     * that carries {@code s}.
     *
     * <p>The emitted constant has the shape:</p>
     * <pre>
     *   @.str.&lt;hex&gt; = unnamed_addr constant [N x i8] c"&lt;escaped&gt;\00"
     * </pre>
     * <p>No linkage keyword is emitted, so the symbol gets LLVM's default
     * ({@code external}) linkage and stays visible from the other {@code .ll}
     * modules: per-class files reference it through a plain
     * {@code external constant [N x i8]} declaration. {@code linkonce_odr} is
     * deliberately not used — each unique string is defined exactly once in
     * {@code _module.ll}, so there is nothing to deduplicate.</p>
     * <p>with every byte that LLVM's string-literal syntax cannot represent
     * directly escaped in {@code \XX} form. Printable ASCII passes through
     * unchanged; backslash, double quote, and the common whitespace escapes
     * are handled explicitly; everything else is emitted as an uppercase
     * two-digit hex escape.</p>
     */
    public static String typeStringConstant(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder escaped = new StringBuilder(bytes.length + 8);
        for (byte b : bytes) {
            int v = b & 0xFF;
            switch (v) {
                case '\\' -> escaped.append("\\5C");
                case '"'  -> escaped.append("\\22");
                case '\n' -> escaped.append("\\0A");
                case '\r' -> escaped.append("\\0D");
                case '\t' -> escaped.append("\\09");
                default -> {
                    if (v < 0x20 || v > 0x7E) {
                        escaped.append(String.format("\\%02X", v));
                    } else {
                        escaped.append((char) v);
                    }
                }
            }
        }
        return typeStringGlobalName(s) + " = unnamed_addr constant ["
            + (bytes.length + 1) + " x i8] c\"" + escaped + "\\00\"\n";
    }

    /**
     * Mangles a fully qualified method reference of the form
     * {@code "owner.name(desc)"} into the LLVM function name used by the
     * emitter.
     *
     * <p>Inputs that do not match that shape — for example a bare helper name
     * such as {@code "__jnative_throw_exception"} — are mangled by a simpler
     * rule: every character that is not a letter, digit, or underscore
     * becomes an underscore, and the {@code fn_} prefix is prepended.</p>
     */
    public static String mangleFunction(String name) {
        int dotIdx = name.lastIndexOf('.');
        int parenIdx = name.indexOf('(');
        if (dotIdx > 0 && parenIdx > dotIdx) {
            String className = name.substring(0, dotIdx);
            String methodPart = name.substring(dotIdx + 1);
            int parenPos = methodPart.indexOf('(');
            if (parenPos > 0) {
                String methodName = methodPart.substring(0, parenPos);
                String descriptor = methodPart.substring(parenPos);
                return mangleMethod(className, methodName, descriptor);
            }
        }
        return "fn_" + sanitizeToAsciiIdentifier(name);
    }

    /**
     * Mangles a method reference into the canonical
     * {@code fn_<class>_<name>_<desc>} form used by every generated function
     * and every native implementation symbol.
     *
     * <p>The mangling is applied to the internal class name (slash
     * separators), the method name (which may include angle brackets for
     * {@code <init>} and {@code <clinit>}), and the JVM descriptor. Every
     * character outside the set {@code [a-zA-Z0-9_]} is replaced with a
     * single underscore. The result is stable and unambiguous for every
     * legal Java method reference.</p>
     * <p>Callers that invoke this on the same (class, name, desc) triple
     * thousands of times per analysis run should go through {@link
     * io.github.kubyk01.util.LlvmUtil#canonicalCalleeKey(String)}, which
     * memoises the result.</p>
     */
    public static String mangleMethod(String className, String methodName, String descriptor) {
        return "fn_" + sanitizeToAsciiIdentifier(className)
            + "_" + sanitizeToAsciiIdentifier(methodName)
            + "_" + sanitizeToAsciiIdentifier(descriptor);
    }

    /**
     * Mangles a callable reference the same way {@link #mangleFunction} does,
     * but also accepts bare helper names that do not look like Java method
     * references.
     *
     * <p>Call sites in the IR carry the callee as a {@code "owner.name(desc)"}
     * string for ordinary methods, and as a plain symbol name for runtime
     * helpers. This method inspects the shape of its argument and dispatches
     * to the appropriate mangling rule; both rules produce the same string
     * for any input that already looks like a fully qualified method
     * reference.</p>
     */
    public static String mangleCallable(String callableName) {
        if (!MANGLED_CALLABLE_PATTERN.matcher(callableName).matches()) {
            return "fn_" + sanitizeToAsciiIdentifier(callableName);
        }
        int dotIdx = callableName.lastIndexOf('.');
        if (dotIdx < 0) return mangleFunction(callableName);
        String methodPart = callableName.substring(dotIdx + 1);
        int parenIdx = methodPart.indexOf('(');
        if (parenIdx < 0) return mangleFunction(callableName);
        String className = callableName.substring(0, dotIdx);
        String methodName = methodPart.substring(0, parenIdx);
        String descriptor = methodPart.substring(parenIdx);
        return mangleMethod(className, methodName, descriptor);
    }

    /**
     * Returns the LLVM function-pointer type that a virtual or interface
     * dispatch slot must have, for the method described by
     * {@code methodSig} (which must be the {@code "name(desc)"} suffix of a
     * mangled callable reference).
     *
     * <p>The signature of a dispatch slot is that of an instance method: the
     * implicit {@code this} parameter comes first, followed by the method's
     * declared parameters, with the method's declared return type.</p>
     */
    public static String getFunctionType(String owner, String methodSig) {
        int paren = methodSig.indexOf('(');
        if (paren < 0) return "i8* (...) *";
        String desc = methodSig.substring(paren);
        Type retType = TypeResolver.descToReturnType(desc);
        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);
        StringBuilder sb = new StringBuilder();
        sb.append(LlvmTypeMapper.toLlvmType(retType)).append(" (");
        sb.append(LlvmTypeMapper.toLlvmType(Type.reference(owner)));
        for (Type pt : paramTypes) {
            sb.append(", ");
            sb.append(LlvmTypeMapper.toLlvmType(pt));
        }
        sb.append(")*");
        return sb.toString();
    }

    /**
     * Builds the JNI-compatible symbol name for a Java {@code native}
     * method, following the "Resolving Native Method Names" rules of the
     * JNI specification:
     *
     * <pre>
     *   Java_&lt;mangled-owner&gt;_&lt;mangled-name&gt;
     * </pre>
     *
     * <p>Mangling maps {@code '_' -> "_1"}, {@code ';' -> "_2"},
     * {@code '[' -> "_3"}, {@code '/'} and {@code '.'} to a single
     * underscore, and every other non-alphanumeric character to
     * {@code "_0XXXX"} (four lowercase hex digits). When the class
     * declares more than one method with the same name (an overload set),
     * the descriptor is appended as {@code "__<mangled-desc>"}, which is
     * the JNI convention for disambiguating overloads.</p>
     *
     * @param owner              internal class name, e.g. {@code "java/io/File"}
     * @param name               the Java method name
     * @param descriptor         the JVM method descriptor
     * @param ownerOverloadCount number of methods with {@code name} declared
     *                           by {@code owner}; values greater than 1 add
     *                           the {@code __<descriptor>} suffix
     */
    public static String toJniSymbolName(String owner, String name,
                                         String descriptor, int ownerOverloadCount) {
        StringBuilder sb = new StringBuilder("Java_");
        appendJniMangled(sb, owner);
        sb.append('_');
        appendJniMangled(sb, name);
        if (ownerOverloadCount > 1) {
            sb.append("__");
            appendJniMangled(sb, descriptor);
        }
        return sb.toString();
    }

    /**
     * Appends the JNI mangling of {@code s} to {@code sb}. See
     * {@link #toJniSymbolName(String, String, String, int)} for the
     * character mapping.
     */
    public static void appendJniMangled(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (c == '/' || c == '.') {
                sb.append('_');
            } else if (c == '_') {
                sb.append("_1");
            } else if (c == ';') {
                sb.append("_2");
            } else if (c == '[') {
                sb.append("_3");
            } else {
                sb.append("_0");
                String hex = Integer.toHexString(c);
                sb.repeat("0", 4 - hex.length());
                sb.append(hex);
            }
        }
    }
}
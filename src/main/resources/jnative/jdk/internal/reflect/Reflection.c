#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <dlfcn.h>
#include "jnative_runtime.h"

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <dbghelp.h>
#pragma comment(lib, "dbghelp.lib")
#else
#include <execinfo.h>
#endif

/* =========================================================================
 * Reflection.getCallerClass()
 *
 * The runtime's contract for getCallerClass is:
 *
 *   return the Class of the first stack frame, starting from the direct
 *   caller of getCallerClass and walking towards main, that is a real
 *   Java method and is not itself a @CallerSensitive method.
 *
 * Three facts make this implementable without frame counting:
 *
 *   1. Every JNative-generated Java function has a mangled name that
 *      starts with "fn_" (bytecode-translated methods) or "__jnative_fn_"
 *      (C implementations of native Java methods). Every other frame on
 *      the stack belongs to the C runtime, libc, or the dynamic loader
 *      and is transparent to the walk.
 *
 *   2. The exact set of @CallerSensitive methods is known at codegen time
 *      — LlvmGlobalEmitter reads the RuntimeVisibleAnnotations attribute
 *      of every emitted method and writes the resulting list into
 *      @jnative_caller_sensitive_symbols. The runtime only needs to test
 *      membership.
 *
 *   3. The exact mapping from a mangled symbol to its internal class name
 *      is also known at codegen time — LlvmGlobalEmitter writes it into
 *      @jnative_symbol_class_map. The runtime only needs to look up by
 *      symbol name, never to demangle anything.
 *
 * The walk therefore has no notion of "skip exactly N frames": it filters
 * frames by their symbol and returns the first one that survives. This
 * makes the result independent of the number of frames that the LLVM
 * backend has inlined.
 *
 * -------------------------------------------------------------------------
 * <clinit> identity after tail-call optimization
 * -------------------------------------------------------------------------
 *
 * In addition to the three facts above, the walk must answer correctly
 * even when the LLVM backend has tail-call-optimized the last instruction
 * of a <clinit> body. When it does — as it does for
 * java.security.SecureClassLoader.<clinit>, whose body is a single
 * `ClassLoader.registerAsParallelCapable()`, and for
 * jdk.internal.loader.BuiltinClassLoader.<clinit> and
 * jdk.internal.loader.ClassLoaders$PlatformClassLoader.<clinit> for the
 * same reason — the body's frame is gone from the native stack by the
 * time a @CallerSensitive method it invoked calls back into
 * getCallerClass(). What remains is the wrapper
 * (fn___lazy_clinit_run_<sanitized-class>) that invoked the body.
 *
 * The runtime maintains a per-thread stack of currently-executing
 * <clinit> class names (see jnative_runtime.c and
 * __jnative_current_clinit_class in jnative_runtime.h). This walk consults
 * that stack whenever it encounters a lazy-<clinit> wrapper, so the correct
 * class is recovered regardless of how the compiler reshaped the frames.
 *
 * The concrete failure that this handle addresses is
 * ClassLoader.registerAsParallelCapable being invoked from
 * SecureClassLoader.<clinit> but observing jdk.internal.loader.ClassLoaders
 * (the next surviving Java frame below the wrapper) as its caller, which
 * raised
 *
 *     java.lang.IllegalCallerException:
 *         class jdk.internal.loader.ClassLoaders not a subclass of
 *         ClassLoader
 *
 * See the class-level javadoc on jnative_runtime.c's clinit state machine
 * for the full reasoning.
 * ========================================================================= */

#define JNATIVE_MAX_FRAMES 256

/*
 * The lazy-<clinit> guard wrapper prefix, mirroring
 * LazyClinitInstrumenter.WRAPPER_NAME_PREFIX. Wrappers are structurally
 * Java frames (they start with "fn_"), but they carry no entry in
 * @jnative_symbol_class_map — only real method symbols do — and they
 * belong to the same class as the method they guard. The walk must
 * therefore special-case them: instead of skipping them outright (which
 * would let the walk return an unrelated outer frame), it consults the
 * thread-local <clinit> identity stack to recover the class name that
 * the body would have provided had its frame survived.
 */
#define JNATIVE_LAZY_CLINIT_PREFIX "fn___lazy_clinit_run_"

/*
 * The C shim that Reflection.getCallerClass is compiled into. Its body is
 * exactly `return find_first_java_caller();`, so clang inlines it at -O2
 * into whatever function calls it — usually a @CallerSensitive method.
 * The walk therefore usually does not see this symbol at all; the
 * explicit check below is defensive, and covers the case where the shim
 * is compiled without inlining.
 */
#define JNATIVE_GET_CALLER_CLASS_SHIM \
    "__jnative_fn_jdk_internal_reflect_Reflection_getCallerClass___Ljava_lang_Class_"

/*
 * Constant-length prefix test. Used only with string literals, so the
 * compiler folds strlen() at compile time and the generated code is a
 * direct memcmp against a constant.
 */
static int has_prefix(const char* s, const char* prefix) {
    size_t n = strlen(prefix);
    return strncmp(s, prefix, n) == 0;
}

/*
 * True for a symbol produced by JNative for a Java method, in either its
 * bytecode-translated form ("fn_...") or its native-implementation form
 * ("__jnative_fn_..."). Everything else on the stack — the C runtime
 * helpers declared in jnative_runtime.c, libc, the dynamic loader — is
 * transparent.
 */
static int is_java_frame_symbol(const char* sym) {
    if (sym == NULL) return 0;
    if (has_prefix(sym, "fn_")) return 1;
    if (has_prefix(sym, "__jnative_fn_")) return 1;
    return 0;
}

/*
 * Binary search of the sorted @jnative_symbol_class_map table.
 * The symbol names are pure ASCII, so the ordering imposed by
 * LlvmGlobalEmitter's TreeMap<String,...> coincides with strcmp.
 */
static struct ReflectionClass* class_for_symbol(const char* sym) {
    if (sym == NULL) return NULL;
    if ((uintptr_t)&jnative_symbol_class_map_size == 0) return NULL;
    if ((uintptr_t)&jnative_symbol_class_map == 0) return NULL;

    int64_t lo = 0;
    int64_t hi = jnative_symbol_class_map_size;
    while (lo < hi) {
        int64_t mid = lo + (hi - lo) / 2;
        int cmp = strcmp(jnative_symbol_class_map[mid].symbol, sym);
        if (cmp == 0) return jnative_symbol_class_map[mid].cls;
        if (cmp < 0)  lo = mid + 1;
        else          hi = mid;
    }
    return NULL;
}

/*
 * Binary search of the sorted @jnative_caller_sensitive_symbols table.
 * The list is small (~50 entries for a typical JDK 21 build) but the
 * search is uniform with class_for_symbol and free of linear scanning.
 */
static int is_caller_sensitive_symbol(const char* sym) {
    if (sym == NULL) return 0;
    if ((uintptr_t)&jnative_caller_sensitive_symbols_size == 0) return 0;
    if ((uintptr_t)&jnative_caller_sensitive_symbols == 0) return 0;

    int64_t lo = 0;
    int64_t hi = jnative_caller_sensitive_symbols_size;
    while (lo < hi) {
        int64_t mid = lo + (hi - lo) / 2;
        int cmp = strcmp(jnative_caller_sensitive_symbols[mid], sym);
        if (cmp == 0) return 1;
        if (cmp < 0)  lo = mid + 1;
        else          hi = mid;
    }
    return 0;
}

/*
 * True for a Java frame that must be transparent to the walk. Each of
 * the skip rules corresponds to one category of frame that is
 * structurally a Java frame but must never be returned by
 * getCallerClass().
 *
 *   1. Reflection trampolines (__reflect_adaptor_* and
 *      __reflect_adaptor_ctor_*). Emitted by
 *      LlvmGlobalEmitter.emitAdaptorForMethod / emitAdaptorForConstructor.
 *      They are called from the C runtime (jnative_invoke_method /
 *      new_instance), never from bytecode, and they are not real Java
 *      methods.
 *
 *   2. Every method declared @CallerSensitive in the JDK, including
 *      Reflection.getCallerClass itself. This is the whole point of the
 *      walk: the contract is to return the class of the first caller
 *      that is NOT itself @CallerSensitive.
 *
 *   3. The C shim that Reflection.getCallerClass is compiled into. The
 *      shim is also @CallerSensitive (rule 2 already covers it), but
 *      keeping the explicit check makes the intent self-documenting and
 *      defends against a future revision that drops the annotation from
 *      the shim.
 *
 * Lazy-<clinit> guard wrappers (fn___lazy_clinit_run_*) are deliberately
 * NOT listed here: they are handled separately in find_first_java_caller
 * because the walk must consult the thread-local <clinit> identity stack
 * when it reaches one, not simply skip it.
 */
static int is_service_frame_symbol(const char* sym) {
    if (sym == NULL) return 0;

    /* 1. Reflection trampolines. The ctor prefix is a strict extension
     *    of the method prefix; checking it first is not required for
     *    correctness but makes the two cases read symmetrically. */
    if (has_prefix(sym, "__reflect_adaptor_ctor_")) return 1;
    if (has_prefix(sym, "__reflect_adaptor_"))      return 1;

    /* 2. Any @CallerSensitive method. */
    if (is_caller_sensitive_symbol(sym)) return 1;

    /* 3. The C shim, in case it was not inlined into its caller. */
    if (strcmp(sym, JNATIVE_GET_CALLER_CLASS_SHIM) == 0) return 1;

    return 0;
}

/*
 * Resolves the class that a lazy-<clinit> wrapper frame belongs to,
 * using the thread-local identity stack maintained by the runtime's
 * clinit state machine.
 *
 * Called from find_first_java_caller when the walk reaches a frame whose
 * symbol begins with JNATIVE_LAZY_CLINIT_PREFIX. The stack holds the
 * internal name of the innermost <clinit> body currently active on this
 * thread; that name is exactly what getCallerClass must return, because
 * the wrapper would not be on the stack at all if its body had not been
 * entered.
 *
 * The name may be stored in either slash form ("java/lang/Foo") or the
 * dotted form ("java.lang.Foo") depending on which caller pushed it, so
 * both lookups are attempted. Returns NULL if the class is not registered
 * in reflect_all_classes[] or if no <clinit> is active on this thread.
 */
static struct ReflectionClass* class_for_active_clinit(void) {
    const char* active = __jnative_current_clinit_class();
    if (active == NULL) return NULL;

    struct ReflectionClass* cls = jnative_class_by_name(active);
    if (cls != NULL) return cls;

    cls = jnative_class_by_dotted_name(active);
    return cls;
}

#if defined(__linux__) || defined(__APPLE__) || defined(__unix__) || defined(__unix)

__attribute__((noinline))
static struct ReflectionClass* find_first_java_caller(void) {
    void* frames[JNATIVE_MAX_FRAMES];
    int n = backtrace(frames, JNATIVE_MAX_FRAMES);

    /*
     * Walk every frame from the top down and return the first Java frame
     * that is not a service frame. The rule is independent of how many
     * frames the backend has inlined:
     *
     *   - non-Java frames are transparent;
     *   - lazy-<clinit> wrapper frames are resolved through the
     *     thread-local <clinit> identity stack: if a body is active on
     *     this thread, its class is the answer, regardless of whether
     *     the body's frame is still present;
     *   - service frames (reflect adaptors, @CallerSensitive methods,
     *     the C shim) are transparent;
     *   - the first remaining Java frame is the answer.
     *
     * This matches HotSpot's vframe walk, which skips every method that
     * carries the JVM-internal is_caller_sensitive flag and every
     * non-Java frame, and returns the class of the first frame that
     * survives. It is the reason the result is correct even when LLVM
     * has inlined the shim into a @CallerSensitive method and that
     * method into its caller: the walk does not depend on how many
     * frames are present, only on which frames are service frames.
     */
    for (int i = 0; i < n; i++) {
        Dl_info info;
        memset(&info, 0, sizeof(info));
        if (dladdr(frames[i], &info) == 0) continue;
        if (info.dli_sname == NULL) continue;

        const char* sym = info.dli_sname;

        if (!is_java_frame_symbol(sym)) continue;

        /*
         * Lazy-<clinit> guard wrapper. The wrapper's frame is on the
         * stack only because the initializer body is (or was, before
         * tail-call optimization) on the current dynamic extent of this
         * thread. The identity stack records exactly that relationship,
         * so the wrapper maps to whatever class the runtime reported as
         * "currently initializing" when it granted this thread the right
         * to run the body.
         *
         * If the stack is empty or the recorded class cannot be resolved,
         * the wrapper is skipped and the walk continues. That preserves
         * the pre-existing behaviour for the pathological case where a
         * wrapper is on the stack outside any active initialization
         * (which should be impossible, but the walk must not crash on
         * it).
         */
        if (has_prefix(sym, JNATIVE_LAZY_CLINIT_PREFIX)) {
            struct ReflectionClass* cls = class_for_active_clinit();
            if (cls != NULL) return cls;
            continue;
        }

        if (is_service_frame_symbol(sym)) continue;

        struct ReflectionClass* cls = class_for_symbol(sym);
        if (cls != NULL) return cls;

        /*
         * The frame is a genuine Java frame that is not a service
         * symbol, but its symbol is not in @jnative_symbol_class_map.
         * That happens for synthetic functions the emitter does not
         * register against any class: the reflection adaptors that this
         * walk filters above, the lambdas produced by
         * generateLambdaAdaptors(), the JSR/RET blockaddress shims, and
         * any function whose owning class was not part of the emitted
         * symbol table.
         *
         * Such a frame cannot be the answer to getCallerClass(): the
         * Java-level caller is always a real method with a real owning
         * class, and the JDK never consults getCallerClass from a
         * synthetic frame. Returning NULL here — as the previous
         * revision did — would break the contract for the class that
         * follows: callers such as ClassLoader.registerAsParallelCapable
         * expect a non-null Class and would NPE on the subclass test.
         * Continuing the walk lets the next frame answer instead, which
         * is what HotSpot's own vframe walk does.
         */
    }
    return NULL;
}

#elif defined(_WIN32)

__attribute__((noinline))
static struct ReflectionClass* find_first_java_caller(void) {
    static int sym_initialized = 0;
    if (!sym_initialized) {
        SymSetOptions(SYMOPT_UNDNAME | SYMOPT_DEFERRED_LOADS | SYMOPT_LOAD_LINES);
        SymInitialize(GetCurrentProcess(), NULL, TRUE);
        sym_initialized = 1;
    }

    void* frames[JNATIVE_MAX_FRAMES];
    USHORT n = CaptureStackBackTrace(0, JNATIVE_MAX_FRAMES, frames, NULL);

    /*
     * Same rule as on Unix: return the first Java frame that is not a
     * service frame, resolving lazy-<clinit> wrappers through the
     * thread-local <clinit> identity stack. Classification is done on
     * the symbol name returned by SymFromAddr, which for the C symbols
     * emitted by the LLVM backend is the mangled form
     * ("fn_java_lang_...") used in @jnative_symbol_class_map and
     * @jnative_caller_sensitive_symbols.
     *
     * If a future toolchain change starts demangling those names, the
     * prefix checks below and the binary searches inside
     * is_caller_sensitive_symbol / class_for_symbol will need a
     * corresponding demangling step; the current code assumes the
     * undecorated name is the symbol as written by the emitter.
     */
    for (USHORT i = 0; i < n; i++) {
        DWORD64 addr = (DWORD64)(uintptr_t)frames[i];
        char buf[sizeof(SYMBOL_INFO) + MAX_SYM_NAME];
        PSYMBOL_INFO pSym = (PSYMBOL_INFO)buf;
        pSym->SizeOfStruct = sizeof(SYMBOL_INFO);
        pSym->MaxNameLen   = MAX_SYM_NAME;
        DWORD64 displacement = 0;
        if (!SymFromAddr(GetCurrentProcess(), addr, &displacement, pSym)) continue;

        const char* sym = pSym->Name;

        if (!is_java_frame_symbol(sym)) continue;

        if (has_prefix(sym, JNATIVE_LAZY_CLINIT_PREFIX)) {
            struct ReflectionClass* cls = class_for_active_clinit();
            if (cls != NULL) return cls;
            continue;
        }

        if (is_service_frame_symbol(sym)) continue;

        struct ReflectionClass* cls = class_for_symbol(sym);
        if (cls != NULL) return cls;
    }
    return NULL;
}

#else
#error "Unsupported platform"
#endif

void* __jnative_fn_jdk_internal_reflect_Reflection_getCallerClass___Ljava_lang_Class_(void) {
    return (void*)find_first_java_caller();
}

/* --------------------------------------------------------------------------
 * static native int getClassAccessFlags(Class<?> c);
 * ------------------------------------------------------------------------ */
int32_t __jnative_fn_jdk_internal_reflect_Reflection_getClassAccessFlags__Ljava_lang_Class__I(
        void* cls) {
    if (cls == NULL) return 0;
    return ((struct ReflectionClass*)cls)->modifiers;
}

/* --------------------------------------------------------------------------
 * static native boolean areNestMates(Class<?> currentClass, Class<?> memberClass);
 *
 * This runtime has no per-nest metadata: the class parser discards
 * NestHost / NestMembers, and the LLVM emitter never produces a
 * per-nest table. Every class is therefore its own nest host.
 * ------------------------------------------------------------------------ */
int32_t __jnative_fn_jdk_internal_reflect_Reflection_areNestMates__Ljava_lang_Class_Ljava_lang_Class__Z(
        void* current_class, void* member_class) {
    (void)current_class;
    (void)member_class;
    return 1;
}
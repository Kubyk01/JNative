package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.ssa.GraphUtils;
import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.application.service.codegen.llvm.nativepolymorphicfunctionresolver.PolymorphicResolver;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.BranchTerminator;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.IndirectBranchTerminator;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.InvokeDynamicInfo;
import io.github.kubyk01.domain.ir.LookupSwitchTerminator;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ResolvedCall;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.TableSwitchTerminator;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.TryCatchRange;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.util.parserC.ParserC;
import io.github.kubyk01.util.parserC.ParserC.NativeMethodInfo;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractClassName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;
import static io.github.kubyk01.util.LlvmUtil.extractTypeName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;
import static io.github.kubyk01.util.LlvmUtil.getElementSizeOfType;

@RequiredArgsConstructor
public class LlvmFunctionEmitter {

    /**
     * Name prefix of every lazy-{@code <clinit>} guard wrapper emitted by
     * {@link io.github.kubyk01.application.service.optimizer.LazyClinitInstrumenter}.
     *
     * <p>Duplicated here as a string literal rather than imported so that
     * the codegen layer stays free of a dependency on the optimizer
     * layer. The value must stay in lockstep with the constant of the
     * same name in {@code LazyClinitInstrumenter}; the prefix is part of
     * the two classes' contract.</p>
     *
     * <p>Functions whose mangled name starts with this prefix are emitted
     * with the {@code noinline} attribute so that LLVM does not inline
     * the wrapper — and with it the {@code __jnative_clinit_enter}/
     * {@code __jnative_clinit_exit} state machine — into every active-use
     * call site. See the comment at the emission site in
     * {@link #emitFunction} for the full rationale.</p>
     */
    private static final String LAZY_CLINIT_WRAPPER_PREFIX = "fn___lazy_clinit_run_";

    /**
     * Suffix of the mangled name of every {@code <clinit>} body.
     *
     * <p>{@code LlvmRuntime.mangleMethod("…", "<clinit>", "()V")} produces
     * {@code fn_<sanitised-class>___clinit____V}; the three underscores
     * before the {@code V} come from {@code _} terminating the mangled
     * {@code <clinit>} name, the separator {@code _}, and the {@code __}
     * that mangles {@code ()}. The suffix below is therefore the exact
     * tail every {@code <clinit>} function carries and no other function
     * does.</p>
     *
     * <p>{@link #emitFunction} emits the definition of every function whose
     * name ends with this suffix with the {@code noinline} attribute. See
     * the comment at the emission site for the full rationale — briefly:
     * {@code Reflection.getCallerClass()} identifies "the class whose
     * {@code <clinit>} is currently running" by the name of the stack
     * frame that called the caller-sensitive method. If LLVM inlines a
     * {@code <clinit>} body into its lazy wrapper, that frame disappears,
     * the walk skips the wrapper (which is itself a service frame), and
     * returns the class of the next surviving frame — which is some outer
     * class unrelated to the one whose initializer actually made the call.</p>
     */
    private static final String CLINIT_SUFFIX = "__clinit____V";

    /**
     * Every external symbol the generated prelude declares, extracted from
     * {@link LlvmRuntime#getDeclarations()} at class-initialisation time.
     *
     * <p>{@link #isCallableSymbolDefined} treats membership in this set as
     * proof that a call to the symbol will resolve at link time. That is
     * exactly what the prelude guarantees: every {@code declare …} line it
     * emits has a matching definition in {@code jnative_runtime.c} or one
     * of its sibling per-class native files, and the linker will find it.
     *
     * <p>Why this is needed and why it is derived rather than hand-written:
     * several optimizer passes ({@code LazyClinitInstrumenter},
     * {@code DestructorInserter}, {@code InstructionHandlers}) construct
     * {@code STATIC_CALL} instructions whose callee operand is the raw
     * runtime-helper name — {@code __jnative_clinit_enter},
     * {@code __jnative_clinit_exit}, {@code __jnative_get_exception_object},
     * {@code __jnative_dtor_enter}, {@code __jnative_dtor_unclaim}.
     * Those names do not match {@code __jnative_fn_*} (they are not Java
     * methods) and are not represented as {@code Function} objects in the
     * {@code Module} (they live in the prelude as bare {@code declare}s),
     * so a hand-written check that looked only at those two families
     * classified them as undefined and rewrote the call into
     * {@code __jnative_unresolved_slot} at program start-up:
     *
     * <pre>
     *   === JNative unresolved vtable/itable slot ===
     *   unresolved call target '__jnative_clinit_enter'
     *   (original: __jnative_clinit_enter)
     * </pre>
     *
     * <p>Deriving the set from {@code LlvmRuntime.getDeclarations()}
     * guarantees the predicate and the prelude can never drift: the
     * declaration of every runtime helper and the recognition of that
     * helper as callable come from the same string.
     */
    private static final Set<String> DECLARED_RUNTIME_HELPERS =
        parseDeclaredRuntimeHelpers();

    /**
     * Parses the {@code declare …} lines of {@link LlvmRuntime#getDeclarations()}
     * and returns the set of symbol names (without the leading {@code @}).
     *
     * <p>The input format is entirely under this codebase's control, so
     * the parser only has to recognise what {@code getDeclarations()}
     * emits: one {@code declare <return-type> @<name>(<params>)} per line,
     * with the symbol name between the first {@code @} and the first
     * {@code (} that follows it.</p>
     */
    private static Set<String> parseDeclaredRuntimeHelpers() {
        Set<String> names = new HashSet<>();
        for (String line : LlvmRuntime.getDeclarations().split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("declare ")) continue;
            int at = trimmed.indexOf('@');
            if (at < 0) continue;
            int paren = trimmed.indexOf('(', at);
            if (paren < 0) continue;
            names.add(trimmed.substring(at + 1, paren));
        }
        return names;
    }

    @FunctionalInterface
    private interface CallEmitter {
        void emit(StringBuilder sb, String resultReg);
    }

    private static final int JMP_BUF_SIZE = 256;

    private final Module module;
    private final LlvmGlobalEmitter globalEmitter;
    private final PolymorphicResolver polymorphicResolver;
    private final DependencyResolver resolver;

    private final LlvmValueMapper valueMapper = new LlvmValueMapper();

    /**
     * Slots for values that cannot be represented as plain SSA registers across
     * a try-guard boundary. See {@link #emitCall(StringBuilder, Type, Value, String, List, CallEmitter)}.
     * Keyed by reference identity — {@link io.github.kubyk01.domain.ir.Temporary}
     * uses identity semantics, so this matches {@code valueMapper}.
     */
    private final Map<Value, String> valueSlots = new HashMap<>();

    /**
     * Stack-allocated objects whose alloca lives in the function's entry
     * block.
     *
     * <p>Maps the SSA value produced by a {@code NEW} instruction that has
     * been marked stack-allocated (see {@code ScalarReplacer}'s first pass)
     * to the name of its {@code alloca} in the entry block, together with
     * the LLVM spelling of the allocated struct type. Every use of such a
     * value re-emits a fresh {@code bitcast} from the alloca at the use
     * site, so the pointer value is always available in the block that
     * uses it, no matter where that block is relative to the block that
     * contains the {@code NEW}.</p>
     *
     * <h2>Why this is necessary</h2>
     *
     * <p>The alloca itself is emitted into the entry block (through the
     * {@code pendingSlotDeclarations} mechanism, see the comment inside
     * the {@code NEW} case), so the alloca address dominates every block
     * of the function. But a {@code bitcast} of that address into the
     * object's reference type, emitted once at the {@code NEW} site, is an
     * SSA value defined in the {@code NEW}'s block. A use of that value in
     * a different block — most importantly, a destructor call inserted by
     * {@code DestructorInserter} at the object's last syntactic use, which
     * routinely lives in a successor block of the one containing the
     * {@code NEW} — is then rejected by the LLVM verifier with</p>
     *
     * <pre>
     *   Instruction does not dominate all uses!
     *     %tmp = bitcast ptr %aux_alloc to ptr
     *     call void @__destruct_X(ptr %tmp)
     * </pre>
     *
     * <p>Re-emitting the bitcast at each use site removes the problem
     * without changing the meaning of the value: with opaque pointer
     * syntax (LLVM 15+) the bitcast is essentially an identity, and with
     * typed pointers it produces the same pointer from the same alloca.
     * The alloca is guaranteed to be live at the use site because it is
     * in the entry block.</p>
     *
     * <p>This mirrors the {@link #valueSlots} mechanism exactly, with one
     * difference: a value in {@code valueSlots} is a pointer to a stack
     * cell that must be {@code load}-ed, whereas a value in
     * {@code valuePointers} is the stack cell itself and must be
     * {@code bitcast}-ed.</p>
     */
    private final Map<Value, StackAlloc> valuePointers = new HashMap<>();

    /**
     * One entry of {@link #valuePointers}: the name of the entry-block
     * alloca that backs a stack-allocated object, together with the LLVM
     * spelling of the allocated struct type (used as the source type of
     * the re-emitted bitcast at each use site).
     */
    private record StackAlloc(String allocaName, String structType) {}

    /**
     * Per-phi cache of preloaded slot-based operands. Keyed by the phi
     * {@link Instruction}, then by the incoming-edge index. Materialised in
     * the predecessor block, right before its terminator, so that the loaded
     * register dominates the edge to the phi's block.
     */
    private final Map<Instruction, Map<Integer, String>> phiPreloads = new HashMap<>();

    /**
     * Stack slots for values that cannot be represented as plain SSA
     * registers across a try-guard boundary (see
     * {@link #emitCall(StringBuilder, Type, Value, String, List, CallEmitter)}).
     *
     * <p>Each entry is a complete {@code alloca} declaration. They are
     * accumulated while block bodies are emitted and then flushed into the
     * function's entry block during assembly, so that the address of every
     * slot dominates every block that may load it. Emitting the alloca in
     * the block that performs the call only makes the slot available on
     * paths that pass through that block; any subsequent load on a
     * bypassing path — for instance a later guarded region that shares the
     * enclosing continuation block — violates LLVM's dominance check with
     * <em>"Instruction does not dominate all uses!"</em>.</p>
     */
    private final List<String> pendingSlotDeclarations = new ArrayList<>();

    /**
     * Values defined in try-body blocks and used in catch-handler blocks.
     * For each such value a stack slot is allocated in the entry block,
     * a store is emitted at the definition site, and every use reads
     * from the slot via {@link #getLlvmValue}.
     * <p>
     * Reason: the setjmp/longjmp-based try-guard lowering produces a
     * LLVM CFG where the try-body block and the catch-dispatch block are
     * siblings under the arm block. The try-body therefore does not
     * dominate the handler in the emitted CFG, even though the IR CFG has
     * an exceptional edge making it dominate. Any SSA value crossing this
     * boundary without materialization is rejected by the LLVM verifier
     * with "Instruction does not dominate all uses!".
     */
    private final Set<Value> tryCrossingValues = new HashSet<>();

    private int tmpCounter = 0;
    private int labelCounter = 0;
    private BasicBlock currentEntryBlock;

    /**
     * Mangled name of the function currently being emitted. Passed as the
     * caller argument to the __jnative_throw_*_ctx helpers so an unhandled
     * exception reports the enclosing Java method in its stack trace.
     */
    private String currentFunctionName;

    private final Map<BasicBlock, List<TryCatchRange>> blockToTryRanges = new HashMap<>();
    private final Map<TryCatchRange, BasicBlock> handlerBlockByRange = new HashMap<>();
    private final Map<TryCatchRange, Integer> rangeOrdinals = new HashMap<>();

    /** Per-block: is this block a try-region entry (has a predecessor outside its region). */
    private final Map<BasicBlock, Boolean> tryRegionEntry = new HashMap<>();

    /** Per-block: does control leave a try-region at this block (via terminator or no-succ). */
    private final Map<BasicBlock, Boolean> tryRegionExit = new HashMap<>();

    /** Shared catch-dispatch block per try-range set (emitted once at function end). */
    private final Map<RangeKey, String> catchLabelByRangeSet = new LinkedHashMap<>();
    private final Map<RangeKey, StringBuilder> catchBodyByRangeSet = new LinkedHashMap<>();

    /** Structural key for a try-range set. Compared by reference identity of ranges. */
    private static final class RangeKey {
        final List<TryCatchRange> ranges;
        RangeKey(List<TryCatchRange> ranges) { this.ranges = ranges; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof RangeKey k)) return false;
            if (ranges.size() != k.ranges.size()) return false;
            for (int i = 0; i < ranges.size(); i++) {
                if (ranges.get(i) != k.ranges.get(i)) return false;
            }
            return true;
        }
        @Override public int hashCode() {
            int h = 1;
            for (TryCatchRange r : ranges) h = 31 * h + System.identityHashCode(r);
            return h;
        }
    }

    /**
     * For each trampoline block: the ordered list of try-ranges whose
     * frames must be popped at the trampoline's entry.
     */
    private final Map<BasicBlock, List<TryCatchRange>> trampolinePops = new HashMap<>();

    /**
     * For each block that is a mixed-exit of a try region: mapping from
     * the block's original successor (outside the region) to the
     * trampoline block that pops the region frames and then branches to
     * that successor.
     * <p>
     * Populated in {@link #computeTryRegions(Function)}; consumed in
     * {@link #emitBlock} to build the {@code targetRedirects} map handed
     * to {@link #emitTerminator}.
     */
    private final Map<BasicBlock, Map<BasicBlock, BasicBlock>> blockExitRedirects = new HashMap<>();

    public String emitFunction(Function func) {
        String funcName = func.getName();

        if ("fn_java_security_Provider_checkInitialized___V".equals(funcName)) {
            return "define void @" + funcName + "(i8* %param_0) {\n"
                + "entry:\n"
                + "  ret void\n"
                + "}\n\n";
        }

        /*
         * jdk.internal.misc.UnsafeConstants is the one class in the JDK
         * whose static fields are *injected* by the VM after the class
         * file's <clinit> has run. HotSpot overwrites the placeholder
         * values (0, 0, false, false, 0) with platform-specific values
         * before any Java code observes them; Unsafe.<clinit> reads
         * them a few instructions later and derives ADDRESS_SIZE,
         * PAGE_SIZE, unalignedAccess() and friends from them.
         *
         * A JNative image has no VM step to perform that injection, so
         * the compiled UnsafeConstants.<clinit> body writes the
         * placeholders verbatim and every consumer of the fields reads
         * zero. The concrete failure is documented at length in
         * UnsafeConstants.c: Unsafe.allocateMemory ends up returning 0
         * for every non-negative request because its alignment mask
         * degenerates to zero, and the first NativeBuffers.newNativeBuffer
         * call hands a NULL address to Unsafe.copyMemory.
         *
         * Rather than let that happen, emit a replacement body for
         * UnsafeConstants.<clinit> that queries the platform through
         * the runtime helpers declared in LlvmRuntime.getDeclarations()
         * and stores the results into the same globals the placeholder
         * stores targeted. The names of those globals are fixed by
         * LlvmGlobalEmitter.generateStaticFields: "gv_" followed by
         * LlvmTypeMapper.sanitizeIdentifier applied to the fully
         * qualified field name
         * "jdk/internal/misc/UnsafeConstants.<FIELD>".
         *
         * The IR below must stay in lockstep with that emitter:
         * the boolean fields BIG_ENDIAN and UNALIGNED_ACCESS are stored
         * as i1 (Type.BOOLEAN maps to i1 in LlvmTypeMapper), and the
         * three int fields are stored as i32.
         */
        if ("fn_jdk_internal_misc_UnsafeConstants__clinit____V".equals(funcName)) {
            return emitUnsafeConstantsClinit();
        }

        this.currentFunctionName = func.getName();

        valueMapper.clear();
        valueSlots.clear();
        valuePointers.clear();
        phiPreloads.clear();
        pendingSlotDeclarations.clear();
        tryCrossingValues.clear();
        tryRegionEntry.clear();
        tryRegionExit.clear();
        catchLabelByRangeSet.clear();
        catchBodyByRangeSet.clear();
        trampolinePops.clear();
        blockExitRedirects.clear();

        StringBuilder signature = new StringBuilder();
        signature.append("define ")
            .append(LlvmTypeMapper.toLlvmType(func.getReturnType()))
            .append(" @").append(funcName).append("(");
        List<Parameter> params = func.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) signature.append(", ");
            signature.append(LlvmTypeMapper.toLlvmType(params.get(i).getType()))
                .append(" %param_").append(i);
        }
        signature.append(")");
        // Function attributes go AFTER the parameter list in LLVM IR — they
        // are not modifiers of the return type. The earlier revision emitted
        // `define noinline void @name(...)`, which clang rejects with
        //     error: this attribute does not apply to return values
        // because the parser is looking at `noinline` where it expects a
        // type. The `noinline` here keeps LLVM from inlining two
        // categories of function that the runtime depends on being
        // present as separate stack frames, or that the runtime depends
        // on being called exactly once:
        //
        //   1. The lazy-<clinit> guard wrappers, whose body contains the
        //      __jnative_clinit_enter/__jnative_clinit_exit state machine
        //      (pthread_mutex_lock + linear strcmp walk over
        //      clinit_table). Inlining the wrapper into every active-use
        //      call site — of which a large image has tens of thousands —
        //      duplicates the state machine, bloats the binary, and buries
        //      the surrounding CFG under a heavyweight instruction
        //      sequence, turning any control-flow defect in that function
        //      into a hot spot inside the state machine instead of a
        //      structural problem in the guarded regions.
        //
        //   2. The <clinit> bodies themselves. Reflection.getCallerClass()
        //      identifies "the class whose <clinit> is currently running"
        //      by the name of the stack frame that called the
        //      caller-sensitive method: the symbol is looked up in
        //      @jnative_symbol_class_map, which maps
        //      fn_<class>___clinit____V back to the class name. If the
        //      backend inlines a <clinit> body into its lazy wrapper, that
        //      frame disappears; the walk then sees only the wrapper
        //      (a service frame, skipped by rule 1 of the walk) and
        //      continues to the next surviving Java frame, which is some
        //      unrelated outer class. The concrete failure that motivated
        //      marking these noinline:
        //
        //          java.lang.IllegalCallerException:
        //              class jdk.internal.loader.ClassLoaders not a
        //              subclass of ClassLoader
        //
        //      raised from ClassLoader.registerAsParallelCapable called by
        //      SecureClassLoader.<clinit>, whose body had been inlined into
        //      fn___lazy_clinit_run_java_security_SecureClassLoader.
        //
        // The two checks are independent: a wrapper is not a <clinit> body
        // and vice versa, and either one being inlined away breaks a
        // different invariant. Both constants (LAZY_CLINIT_WRAPPER_PREFIX
        // and CLINIT_SUFFIX) are stable parts of the contract with
        // LazyClinitInstrumenter and LlvmRuntime.mangleMethod.
        if (funcName.startsWith(LAZY_CLINIT_WRAPPER_PREFIX)
            || funcName.endsWith(CLINIT_SUFFIX)) {
            signature.append(" noinline");
        }
        signature.append(" {\n");

        for (int i = 0; i < params.size(); i++) {
            valueMapper.setValue(params.get(i), "%param_" + i);
        }

        currentEntryBlock = func.getEntryBlock();
        buildTryCatchInfo(func);
        computeTryRegions(func);
        tryCrossingValues.addAll(computeTryCrossingValues(func));

        List<String> bodies = new ArrayList<>();
        int entryIdx = 0;
        boolean firstBlock = true;
        for (int i = 0; i < func.getBlocks().size(); i++) {
            BasicBlock block = func.getBlocks().get(i);
            boolean isEntry = (block == currentEntryBlock)
                || (currentEntryBlock == null && firstBlock);
            if (isEntry && (currentEntryBlock != null)) {
                entryIdx = i;
            } else if (isEntry && currentEntryBlock == null) {
                entryIdx = i;
            }
            bodies.add(emitBlock(block, isEntry));
            firstBlock = false;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(signature);
        sb.append("entry:\n");

        Set<Integer> usedLocals = collectUsedLocals(func);
        for (int idx : usedLocals) {
            sb.append("  %local_").append(idx).append(" = alloca i64, align 8\n");
            // Zero-initialise the slot. The per-slot width invariant
            // (enforced by LocalSlotWidthValidator) guarantees that no
            // correct code ever reads a slot at a width greater than the
            // width it was written at, so this initialisation is not
            // observable when the invariant holds. Its purpose is to
            // remove nondeterminism when the invariant is violated by a
            // bug in an earlier pass: reading a narrower-written slot
            // yields the sign/zero-extended form of the written value
            // instead of whatever happened to be in the stack frame at
            // process start. Note that this does NOT replace the
            // sign-extension performed by the STORE case — a negative
            // byte must still produce a negative int, not 0x000000FF.
            sb.append("  store i64 0, i64* %local_").append(idx).append("\n");
        }

        for (int i = 0; i < params.size(); i++) {
            Parameter p = params.get(i);
            int slotIdx = p.getIndex();
            if (!usedLocals.contains(slotIdx)) continue;
            String ty  = LlvmTypeMapper.toLlvmType(p.getType());
            String ptr = newAux("param_init");
            sb.append("  ").append(ptr)
                .append(" = bitcast i64* %local_").append(slotIdx)
                .append(" to ").append(ty).append("*\n");
            sb.append("  store ").append(ty).append(" %param_").append(i)
                .append(", ").append(ty).append("* ").append(ptr).append("\n");
        }

        List<TryCatchRange> ranges = func.getTryCatchRanges();
        if (ranges != null) {
            for (int i = 0; i < ranges.size(); i++) {
                sb.append("  %jmp_buf_").append(i).append(" = alloca [")
                    .append(JMP_BUF_SIZE).append(" x i8], align 16\n");
            }
        }

        for (String decl : pendingSlotDeclarations) {
            sb.append(decl);
        }

        sb.append(bodies.get(entryIdx));
        for (int i = 0; i < bodies.size(); i++) {
            if (i != entryIdx) {
                sb.append(bodies.get(i));
            }
        }

        // Emit the shared catch dispatch blocks once per try-range set.
        for (StringBuilder cb : catchBodyByRangeSet.values()) {
            sb.append(cb);
        }

        sb.append("}\n\n");
        return sb.toString();
    }

    private void buildTryCatchInfo(Function func) {
        blockToTryRanges.clear();
        handlerBlockByRange.clear();
        rangeOrdinals.clear();

        List<TryCatchRange> ranges = func.getTryCatchRanges();
        if (ranges == null || ranges.isEmpty()) return;

        Map<String, BasicBlock> byLabel = new HashMap<>();
        for (BasicBlock b : func.getBlocks()) {
            byLabel.put(b.getLabel(), b);
        }

        int ordinal = 0;
        for (TryCatchRange range : ranges) {
            BasicBlock handlerBlock = byLabel.get("L" + range.handler);
            if (handlerBlock == null) continue;

            // -----------------------------------------------------------------
            // Read the frozen snapshot, do NOT recompute.
            //
            // range.body was captured by MethodTranslator.visitEnd() before
            // any handler edge existed in the CFG. Recomputing here — as the
            // previous revision did — is exactly the code path that produced
            // the infinite ping-pong between sibling handlers in
            // java.security.Provider$Service.newInstance: by the time codegen
            // runs, the CFG carries every body -> handler edge inserted by
            // MethodTranslator, and a BFS from the body therefore walks into
            // the handlers of all sibling ranges of the same try statement.
            // Each such handler then gets wrapped in the try-guard of every
            // OTHER sibling range, whose own handler is a rethrow, and the
            // pair loops forever inside longjmp.
            //
            // The snapshot fixes this at the source and cannot be defeated by
            // any subsequent IR pass, because it is a plain list of
            // BasicBlock references and no IR pass mutates it.
            // -----------------------------------------------------------------
            List<BasicBlock> body = range.body;

            if (body == null) {
                // Defensive fallback for ranges whose labels did not resolve
                // during translation. Under normal compilation this branch is
                // unreachable: MethodTranslator always populates range.body
                // for every range whose start/end/handler labels exist in
                // labelToBlock, and the codegen only sees ranges whose
                // handler block is present in func.
                BasicBlock startBlock = byLabel.get("L" + range.start);
                BasicBlock endBlock   = byLabel.get("L" + range.end);
                if (startBlock == null || endBlock == null) continue;
                body = GraphUtils.getBlocksBetween(startBlock, endBlock, handlerBlock);
            }

            for (BasicBlock b : body) {
                if (b == handlerBlock) continue;
                blockToTryRanges.computeIfAbsent(b, x -> new ArrayList<>()).add(range);
            }

            handlerBlockByRange.put(range, handlerBlock);
            rangeOrdinals.put(range, ordinal++);
        }
    }

    /**
     * Returns the try ranges that may be emitted as guards for the
     * instructions of {@code block}.
     *
     * <p>Filters out any range whose handler is {@code block} itself.
     * Emitting such a range's guard on the handler's own instructions
     * produces a structural livelock: the guard's catch-successor
     * branches to the handler's LLVM label, which is the block currently
     * being emitted, so control re-enters the guard on every exception
     * and nothing in the guarded region can break the cycle.</p>
     *
     * <p>The primary defence against this pattern lives in
     * {@link #buildTryCatchInfo}, in {@code TryCatchHandler.handle} and in
     * {@code MethodTranslator.addExceptionalEdges}, all of which exclude
     * the handler from the body of the range it handles. This method is
     * the last line of defence at emission time: whatever upstream
     * produced {@code blockToTryRanges}, a block never wraps its own
     * instructions in a guard that would catch and jump back to itself.</p>
     */
    private List<TryCatchRange> effectiveRangesFor(BasicBlock block) {
        List<TryCatchRange> all = blockToTryRanges.get(block);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<TryCatchRange> filtered = new ArrayList<>(all.size());
        for (TryCatchRange r : all) {
            BasicBlock h = handlerBlockByRange.get(r);
            if (h == block) continue;
            filtered.add(r);
        }
        return filtered;
    }

    private static boolean sameRanges(List<TryCatchRange> a, List<TryCatchRange> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    /**
     * Computes the entry/exit sets used by region-level try hoisting.
     *
     * <p>A block is a region <b>entry</b> iff at least one of its predecessors
     * carries a different try-range set (or, for the function entry block,
     * always). A block is a region <b>exit</b> iff at least one of its
     * successors carries a different try-range set, or the block ends with
     * {@code return} (which unwinds the entire frame and therefore every
     * active catch context within it).</p>
     *
     * <p>Both properties are consumed by {@link #emitBlock} to place the
     * {@code setjmp + push_catch} arm and the {@code pop_catch} exit code
     * exactly at the region boundaries — once per region activation, not once
     * per guarded instruction.</p>
     */
    private void computeTryRegions(Function func) {
        // ---- Phase 1: entry/exit flags (unchanged logic). ----
        for (BasicBlock block : func.getBlocks()) {
            List<TryCatchRange> myRanges = effectiveRangesFor(block);
            if (myRanges.isEmpty()) {
                tryRegionEntry.put(block, false);
                tryRegionExit.put(block, false);
                continue;
            }
            boolean isEntry = false;
            for (BasicBlock pred : block.getPredecessors()) {
                if (!sameRanges(effectiveRangesFor(pred), myRanges)) {
                    isEntry = true;
                    break;
                }
            }
            boolean isExit = block.getTerminator() instanceof ReturnTerminator;
            if (!isExit) {
                for (BasicBlock succ : block.getSuccessors()) {
                    if (!sameRanges(effectiveRangesFor(succ), myRanges)) {
                        isExit = true;
                        break;
                    }
                }
            }
            tryRegionEntry.put(block, isEntry);
            tryRegionExit.put(block, isExit);
        }

        // ---- Phase 2: create trampolines for mixed-exit blocks. ----
        //
        // A "mixed exit" is a block whose terminator has some normal
        // targets inside the block's own try region and some outside.
        // All-inside and all-outside exits do not need a trampoline: the
        // former needs nothing, the latter runs the pop_catch sequence
        // unconditionally before the terminator.
        List<BasicBlock> snapshot = new ArrayList<>(func.getBlocks());
        for (BasicBlock block : snapshot) {
            List<TryCatchRange> myRanges = effectiveRangesFor(block);
            if (myRanges.isEmpty()) continue;
            if (!tryRegionExit.getOrDefault(block, false)) continue;

            Terminator term = block.getTerminator();
            if (term == null || term instanceof ReturnTerminator) continue;

            boolean hasInsideNormalSucc  = false;
            boolean hasOutsideNormalSucc = false;
            for (BasicBlock succ : term.getTargets()) {
                if (sameRanges(effectiveRangesFor(succ), myRanges)) hasInsideNormalSucc  = true;
                else                                                hasOutsideNormalSucc = true;
            }
            if (!hasInsideNormalSucc || !hasOutsideNormalSucc) continue;

            Map<BasicBlock, BasicBlock> redirects = new HashMap<>();
            for (BasicBlock succ : term.getTargets()) {
                if (sameRanges(effectiveRangesFor(succ), myRanges)) continue;

                BasicBlock tramp = new BasicBlock(
                    block.getLabel() + "_exit_tramp_" + (trampolinePops.size()
                        + blockExitRedirects.size() + 1));
                // Route this trampoline's own emission through the
                // trampoline path in emitBlock.
                trampolinePops.put(tramp, myRanges);
                func.addBlock(tramp);

                // ------------------------------------------------------------------
                // ORDER IS LOAD-BEARING.
                //
                // `tramp.setTerminator(BranchTerminator(succ))` internally calls
                // `tramp.addSuccessor(succ)`, which appends `tramp` to
                // `succ.predecessors`. If we set the terminator FIRST and then
                // do `preds.set(blockIdx, tramp)`, the successor ends up with
                // `tramp` listed TWICE: once at `blockIdx` (from the replace),
                // once at the end (from addSuccessor). PHI emission is
                // positional against `succ.predecessors`, so the duplication
                // corrupts the PHI operand list and LLVM rejects the module
                // with "does not dominate all uses".
                //
                // By rewiring the predecessor slot FIRST, addSuccessor's
                // duplicate check (`if (!block.predecessors.contains(this))`)
                // sees `tramp` already present and skips the second add.
                // After both calls, `succ.predecessors` contains exactly one
                // entry per real predecessor, with `tramp` in the position
                // where `block` used to sit.
                // ------------------------------------------------------------------
                List<BasicBlock> preds = succ.getPredecessors();
                int blockIdx = preds.indexOf(block);
                if (blockIdx >= 0) {
                    preds.set(blockIdx, tramp);
                }

                tramp.setTerminator(new BranchTerminator(succ));

                redirects.put(succ, tramp);
            }
            if (!redirects.isEmpty()) {
                blockExitRedirects.put(block, redirects);
            }
        }
    }

    /**
     * Значения, которые пересекают границу try-body → catch-region.
     *
     * <p>Setjmp-машинерия try-guard'ов порождает CFG, в котором
     * {@code bodyLabel} и {@code catchLabel} — сиблинги под общим {@code arm}-блоком.
     * В IR-CFG exceptional edge делает def-блок из try-body доминирующим над
     * handler'ом, но в эмитированном LLVM CFG такого доминирования нет. Любое
     * SSA-значение, определённое в try-body и использованное в блоке, достижимом
     * из соответствующего handler'а, должно быть материализовано через слот в
     * entry-блоке.</p>
     *
     * <p>Материализация выполняется для тела <em>каждого</em> диапазона
     * независимо: значение, определённое в теле r1, используется в catch-region
     * r2 (вложенного в r1), материализуется по правилу r2, а не r1 — это
     * минимизирует число материализаций и корректно для вложенных try.</p>
     */
    private Set<Value> computeTryCrossingValues(Function func) {
        Set<Value> result = new HashSet<>();
        List<TryCatchRange> ranges = func.getTryCatchRanges();
        if (ranges == null || ranges.isEmpty()) return result;

        Map<String, BasicBlock> byLabel = new HashMap<>();
        for (BasicBlock b : func.getBlocks()) {
            byLabel.put(b.getLabel(), b);
        }

        BasicBlock entry = func.getEntryBlock();

        for (TryCatchRange range : ranges) {
            BasicBlock handler = byLabel.get("L" + range.handler);
            if (handler == null) continue;

            // Множество блоков, составляющих тело данного диапазона. Строится
            // из blockToTryRanges — то есть ровно то же множество, что
            // использует buildTryCatchInfo (включая fallback через
            // GraphUtils.getBlocksBetween, если range.body не был заполнен
            // при трансляции).
            Set<BasicBlock> bodySet = new HashSet<>();
            for (Map.Entry<BasicBlock, List<TryCatchRange>> e : blockToTryRanges.entrySet()) {
                if (e.getValue().contains(range)) bodySet.add(e.getKey());
            }
            if (bodySet.isEmpty()) continue;

            // Транзитивное замыкание handler'а по обычному CFG. Включает
            // сам handler, catch-логику и всё, что от неё достижимо (в том
            // числе блоки после finally, петли обратно в try-body и т.д.).
            Set<BasicBlock> catchReachable = new HashSet<>();
            Deque<BasicBlock> worklist = new ArrayDeque<>();
            worklist.add(handler);
            while (!worklist.isEmpty()) {
                BasicBlock cur = worklist.poll();
                if (!catchReachable.add(cur)) continue;
                for (BasicBlock succ : cur.getSuccessors()) {
                    if (!catchReachable.contains(succ)) worklist.add(succ);
                }
            }

            for (BasicBlock B : catchReachable) {
                for (Instruction inst : B.getInstructions()) {
                    for (Value op : inst.getOperands()) {
                        addIfCrossing(result, op, B, bodySet, entry);
                    }
                }
                Terminator term = B.getTerminator();
                if (term != null) {
                    for (Value op : terminatorOperands(term)) {
                        addIfCrossing(result, op, B, bodySet, entry);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Добавляет {@code op} в результирующее множество, если это Temporary,
     * определённый в {@code bodyBlocks} (не в {@code useBlock} и не в
     * entry-блоке). Параметры пропускаются автоматически — они не Temporary.
     */
    private static void addIfCrossing(Set<Value> result,
                                      Value op,
                                      BasicBlock useBlock,
                                      Set<BasicBlock> bodyBlocks,
                                      BasicBlock entry) {
        if (!(op instanceof Temporary t)) return;
        Instruction def = t.getDefiningInstruction();
        if (def == null) return;
        BasicBlock defBlock = def.getParent();
        if (defBlock == null) return;
        if (defBlock == useBlock) return;
        if (defBlock == entry) return;
        if (!bodyBlocks.contains(defBlock)) return;
        result.add(op);
    }

    /** Собирает операнды терминатора, включая key/target/exception/condition. */
    private static List<Value> terminatorOperands(Terminator term) {
        List<Value> out = new ArrayList<>();
        if (term instanceof ReturnTerminator rt) {
            if (rt.getValue() != null) out.add(rt.getValue());
        } else if (term instanceof ThrowTerminator tt) {
            if (tt.getException() != null) out.add(tt.getException());
        } else if (term instanceof CondBranchTerminator cbt) {
            if (cbt.getCondition() != null) out.add(cbt.getCondition());
        } else if (term instanceof LookupSwitchTerminator lst) {
            if (lst.getKey() != null) out.add(lst.getKey());
        } else if (term instanceof TableSwitchTerminator tst) {
            if (tst.getKey() != null) out.add(tst.getKey());
        } else if (term instanceof IndirectBranchTerminator ibt) {
            if (ibt.getTargetBlock() != null) out.add(ibt.getTargetBlock());
        }
        return out;
    }

    private Set<Integer> collectUsedLocals(Function func) {
        Set<Integer> locals = new HashSet<>();
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() == Opcode.LOAD || inst.getOpcode() == Opcode.STORE) {
                    locals.add(inst.getLocalIndex());
                }
            }
        }
        return locals;
    }

    private String emitBlock(BasicBlock block, boolean labelAlreadyEmitted) {
        StringBuilder sb = new StringBuilder();
        if (!labelAlreadyEmitted) {
            sb.append(llvmLabel(block)).append(":\n");
        }

        // ---------- Trampoline blocks: emit pops + branch, then stop. ----------
        //
        // These blocks were materialised in computeTryRegions and are always
        // emitted AFTER the block that owns the exit trampoline. They contain
        // exactly one sequence of pops (one per active catch frame in the
        // region being exited) followed by the block's single terminator.
        List<TryCatchRange> trampolineRanges = trampolinePops.get(block);
        if (trampolineRanges != null) {
            sb.repeat("  call void @__jnative_pop_catch()\n", trampolineRanges.size());
            Terminator trampTerm = block.getTerminator();
            if (trampTerm != null) {
                sb.append(emitTerminator(trampTerm, List.of(), Collections.emptyMap()));
            } else {
                sb.append("  unreachable\n");
            }
            return sb.toString();
        }

        // ---------- Normal block: entry / body / exit / terminator ----------
        List<TryCatchRange> ranges = effectiveRangesFor(block);
        boolean isEntry = tryRegionEntry.getOrDefault(block, false);
        boolean isExit  = tryRegionExit.getOrDefault(block, false);
        Terminator term = block.getTerminator();

        // --- entry: arm the cascaded setjmp + push_catch ---
        String bodyLabel;
        if (isEntry && !ranges.isEmpty()) {
            bodyLabel = newLabel("body");
            RangeKey key = new RangeKey(ranges);
            String catchLabel = catchLabelByRangeSet.get(key);
            if (catchLabel == null) {
                catchLabel = newLabel("catch_dispatch");
                catchLabelByRangeSet.put(key, catchLabel);
                StringBuilder cb = new StringBuilder();
                emitCatchDispatchBody(cb, catchLabel, ranges);
                catchBodyByRangeSet.put(key, cb);
            }
            emitRegionArm(sb, ranges, catchLabel, bodyLabel);
            sb.append(bodyLabel).append(":\n");
        }

        // --- PHI preloads (for successor PHIs) — unchanged from previous revision ---
        if (term != null) {
            for (BasicBlock succ : term.getTargets()) {
                int predIdx = succ.getPredecessors().indexOf(block);
                if (predIdx < 0) continue;
                for (Instruction succInst : succ.getInstructions()) {
                    if (succInst.getOpcode() != Opcode.PHI) continue;
                    if (predIdx >= succInst.getOperands().size()) continue;
                    Value phiOp = succInst.getOperands().get(predIdx);
                    if (phiOp == null) continue;

                    String slot = valueSlots.get(phiOp);
                    if (slot != null) {
                        String ty = LlvmTypeMapper.toLlvmType(phiOp.getType());
                        String tmp = newAux("phi_preload");
                        sb.append("  ").append(tmp).append(" = load ")
                          .append(ty).append(", ").append(ty).append("* ")
                          .append(slot).append("\n");
                        phiPreloads.computeIfAbsent(succInst, k -> new HashMap<>())
                                   .put(predIdx, tmp);
                        continue;
                    }

                    StackAlloc ptrSlot = valuePointers.get(phiOp);
                    if (ptrSlot != null) {
                        String destTy = LlvmTypeMapper.toLlvmType(phiOp.getType());
                        String tmp = newAux("phi_preload");
                        sb.append("  ").append(tmp).append(" = bitcast ")
                          .append(ptrSlot.structType()).append("* ")
                          .append(ptrSlot.allocaName())
                          .append(" to ").append(destTy).append("\n");
                        phiPreloads.computeIfAbsent(succInst, k -> new HashMap<>())
                                   .put(predIdx, tmp);
                    }
                }
            }
        }

        // --- body instructions (no per-instruction guards; region is armed) ---
        for (Instruction inst : block.getInstructions()) {
            sb.append(emitInstruction(inst, List.of()));
        }

        // --- exit: emit pops for the all-outside case, or prepare redirects ---
        Map<BasicBlock, String> targetRedirects = Collections.emptyMap();
        if (isExit && !ranges.isEmpty()) {
            boolean isReturn = (term == null) || (term instanceof ReturnTerminator);

            boolean hasInsideNormalSucc  = false;
            boolean hasOutsideNormalSucc = false;
            if (!isReturn) {
                for (BasicBlock succ : term.getTargets()) {
                    if (sameRanges(effectiveRangesFor(succ), ranges)) hasInsideNormalSucc  = true;
                    else                                                hasOutsideNormalSucc = true;
                }
            }

            if (isReturn || (hasOutsideNormalSucc && !hasInsideNormalSucc)) {
                // All normal exits are outside (or the block returns):
                // pop inline, before the terminator. The exception path also
                // pops, via the shared catch-dispatch block; the two paths
                // are mutually exclusive.
                sb.repeat("  call void @__jnative_pop_catch()\n", ranges.size());
            } else if (hasOutsideNormalSucc && hasInsideNormalSucc) {
                // Mixed exit: the trampolines created by computeTryRegions
                // carry the per-edge pops.
                Map<BasicBlock, BasicBlock> redirects = blockExitRedirects.get(block);
                if (redirects != null && !redirects.isEmpty()) {
                    targetRedirects = new HashMap<>();
                    for (Map.Entry<BasicBlock, BasicBlock> e : redirects.entrySet()) {
                        targetRedirects.put(e.getKey(), llvmLabel(e.getValue()));
                    }
                }
                // If redirects is unexpectedly empty, we fall through to the
                // terminator without redirects — but that cannot happen in a
                // well-formed run, because computeTryRegions creates the
                // trampolines exactly when both flags above are true.
            }
            // If only inside normal successors exist: nothing to do on the
            // normal path; exception edges pop via catch-dispatch.
        }

        // --- terminator ---
        if (term != null) {
            sb.append(emitTerminator(term, List.of(), targetRedirects));
        } else {
            Type retType = block.getFunction().getReturnType();
            if (retType.isVoid()) {
                sb.append("  ret void\n");
            } else {
                String zero = getZeroValue(retType);
                sb.append("  ret ").append(LlvmTypeMapper.toLlvmType(retType))
                  .append(" ").append(zero).append("\n");
            }
        }
        return sb.toString();
    }

    private String getZeroValue(Type type) {
        if (type == null) return "null";
        if (type == Type.FLOAT || type == Type.DOUBLE) return "0.0";
        if (type.isReference() || type.isArray() || type.isNull()
            || type.isBlock() || type.isUnknown()) {
            return "null";
        }
        if (type == Type.BOOLEAN) return "false";
        return "0";
    }

    /**
     * Emits the replacement body for {@code
     * jdk.internal.misc.UnsafeConstants.<clinit>}.
     *
     * <p>The body overwrites the placeholder values that the class file
     * supplies ({@code 0, 0, false, false, 0}) with the values HotSpot's
     * VM start-up step would have injected. Each value comes from a
     * runtime helper that queries the executing platform; nothing is
     * hard-coded except the choice of which platform capability each
     * field describes.</p>
     *
     * <p>The store targets are the {@code @gv_*} globals produced by
     * {@link LlvmGlobalEmitter#generateStaticFields()} for the five
     * fields of {@code UnsafeConstants}. Their names are
     * {@code gv_} concatenated with
     * {@link LlvmTypeMapper#sanitizeIdentifier(String)} applied to
     * {@code "jdk/internal/misc/UnsafeConstants.<FIELD>"}, which
     * collapses to
     * {@code gv_jdk_internal_misc_UnsafeConstants_<FIELD>} for every
     * field. If that naming convention ever changes, this method and
     * the emitter must change together.</p>
     *
     * <p>The two boolean fields are stored as {@code i1} because that
     * is what {@link LlvmTypeMapper#toLlvmType(Type)} produces for
     * {@link Type#BOOLEAN}, and the emitter's own {@code STORE}
     * lowering uses the same mapping for those fields. The runtime
     * helpers return {@code i32} so the C side is uniform, and the
     * {@code icmp ne ... , 0} here performs the conversion. The three
     * {@code int} fields are stored as {@code i32} unchanged.</p>
     *
     * <p>This function is emitted unconditionally, without going
     * through the module's IR at all. Its {@link Function} entry in
     * the module is not consulted; the eager {@code <clinit>} schedule
     * in {@link LlvmGenerator#generateMain()} calls it by name, and
     * {@link LlvmFunctionEmitter#emitFunction} reaches this method
     * before touching any of the per-function state, so a later
     * decision to add IR-level instrumentation to this class will not
     * collide with the state this method deliberately does not use.</p>
     */
    private String emitUnsafeConstantsClinit() {

        return "define void @fn_jdk_internal_misc_UnsafeConstants__clinit____V() {\n" +
                "entry:\n" +

                /* ADDRESS_SIZE0 : int */
                "  %addr_size = call i32 @__jnative_unsafe_address_size()\n" +
                "  store i32 %addr_size, i32* " +
                "@gv_jdk_internal_misc_UnsafeConstants_ADDRESS_SIZE0, align 4\n" +

                /* PAGE_SIZE : int */
                "  %page_size = call i32 @__jnative_unsafe_page_size()\n" +
                "  store i32 %page_size, i32* " +
                "@gv_jdk_internal_misc_UnsafeConstants_PAGE_SIZE, align 4\n" +

                /* BIG_ENDIAN : boolean  -> i1 */
                "  %be_i32 = call i32 @__jnative_unsafe_big_endian()\n" +
                "  %be = icmp ne i32 %be_i32, 0\n" +
                "  store i1 %be, i1* " +
                "@gv_jdk_internal_misc_UnsafeConstants_BIG_ENDIAN, align 1\n" +

                /* UNALIGNED_ACCESS : boolean  -> i1 */
                "  %ua_i32 = call i32 @__jnative_unsafe_unaligned_access()\n" +
                "  %ua = icmp ne i32 %ua_i32, 0\n" +
                "  store i1 %ua, i1* " +
                "@gv_jdk_internal_misc_UnsafeConstants_UNALIGNED_ACCESS, align 1\n" +

                /* DATA_CACHE_LINE_FLUSH_SIZE : int */
                "  %flush = call i32 @__jnative_unsafe_data_cache_line_flush_size()\n" +
                "  store i32 %flush, i32* " +
                "@gv_jdk_internal_misc_UnsafeConstants_DATA_CACHE_LINE_FLUSH_SIZE, align 4\n" +
                "  ret void\n" +
                "}\n\n";
    }

    private String llvmLabel(BasicBlock block) {
        if (block == currentEntryBlock) {
            return "entry";
        }
        return block.getLabel().replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String newAux(String prefix) {
        return "%aux_" + prefix + "_" + (tmpCounter++);
    }

    private String newLabel(String prefix) {
        return "lbl_" + prefix + "_" + (labelCounter++);
    }
    private String stringRef(String s) {
        if (s == null || s.isEmpty()) {
            return "null";
        }
        // Register the string with the global emitter so it is emitted
        // into the module's constant section. Without this, any stringRef
        // call site that does not go through globalEmitter's own
        // registration path produces a forward reference to a global that
        // is never defined, and the LLVM parser rejects the module with
        // "use of undefined value".
        globalEmitter.registerDeferredString(s);
        int len = LlvmRuntime.typeStringArrayLength(s);
        String g = LlvmRuntime.typeStringGlobalName(s);
        return "getelementptr inbounds ([" + len + " x i8], [" + len + " x i8]* "
            + g + ", i32 0, i32 0)";
    }

    private void emitThrowHelper(StringBuilder sb, String callee, List<TryCatchRange> ranges) {
        String ctxCallee = callee + "_ctx";
        String funcNameRef = stringRef(currentFunctionName);

        if (ranges.isEmpty()) {
            sb.append("  call void ").append(ctxCallee)
                .append("(i8* ").append(funcNameRef).append(")\n");
            sb.append("  unreachable\n");
        } else {
            emitTryGuard(sb, ranges,
                inner -> inner.append("  call void ").append(ctxCallee)
                    .append("(i8* ").append(funcNameRef).append(")\n"),
                true);
        }
    }

    /**
     * Emits a call to {@code __jnative_throw_null_pointer_exception_ctx}
     * (or its guarded equivalent), followed by {@code unreachable}.
     *
     * <p>{@code context} is a human-readable Java-level description of
     * what was being dereferenced — a field name, a method name, the
     * literal string {@code "monitor"}, the literal string
     * {@code "<array>"}, or anything else the caller knows about the
     * failing instruction — or {@code null} when no extra detail is
     * available.</p>
     *
     * <p>The value is passed through as-is into the runtime's
     * NullPointerException message, together with the mangled name of the
     * enclosing method. SSA register names, temporary identifiers, and any
     * other emitter-internal value must NOT be passed here: they render as
     * {@code "%tmp_NNNNN"} in the eventual exception message, which is
     * meaningless to a Java programmer reading a stack trace. The runtime
     * side has no way to tell one string from another, so the contract is
     * enforced purely by the discipline of the callers below.</p>
     */
    private void emitNpeThrowHelper(StringBuilder sb,
                                    String context,
                                    List<TryCatchRange> ranges) {
        String ctxCallee   = "@__jnative_throw_null_pointer_exception_ctx";
        String funcNameRef = stringRef(currentFunctionName);

        String extraRef;
        if (context != null && !context.isEmpty()) {
            globalEmitter.registerDeferredString(context);
            extraRef = stringRef(context);
        } else {
            extraRef = "null";
        }

        if (ranges.isEmpty()) {
            sb.append("  call void ").append(ctxCallee)
                .append("(i8* ").append(funcNameRef)
                .append(", i8* ").append(extraRef).append(")\n");
            sb.append("  unreachable\n");
        } else {
            emitTryGuard(sb, ranges,
                inner -> inner.append("  call void ").append(ctxCallee)
                    .append("(i8* ").append(funcNameRef)
                    .append(", i8* ").append(extraRef).append(")\n"),
                true);
        }
    }


    /**
     * Emits a null check on {@code obj}. When the check fails, the
     * runtime throws a NullPointerException whose message names the
     * enclosing method and, if {@code context} is non-null, the
     * Java-level construct that was dereferenced.
     *
     * <p>{@code context} must be a Java-level description — a field
     * name, a method name, {@code "monitor"}, {@code "<array>"}, etc. —
     * or {@code null}. It must never be an SSA register name or any
     * other emitter-internal identifier; see
     * {@link #emitNpeThrowHelper} for the reasoning.</p>
     *
     * <p>The null check is skipped for non-pointer types, because an
     * {@code icmp ne i32, null} is not a well-typed LLVM comparison and
     * the emitter never needs one for a primitive.</p>
     */
    private void emitNullCheck(StringBuilder sb, Value obj, List<TryCatchRange> ranges,
                               String context) {
        // Skip null check for non‑pointer types to avoid invalid LLVM (e.g., icmp ne i32, null)
        if (!(obj.getType().isReference()
            || obj.getType().isArray()
            || obj.getType().isNull()
            || obj.getType().isBlock()
            || obj.getType().isUnknown())) {
            return;
        }
        String ty = LlvmTypeMapper.toLlvmType(obj.getType());

        String objRef = getLlvmValue(sb, obj);

        String chk      = newAux("npe_chk");
        String throwBlk = newLabel("throw_npe");
        String cont     = newLabel("npe_ok");

        sb.append("  ").append(chk).append(" = icmp ne ").append(ty).append(" ")
            .append(objRef).append(", null\n");
        sb.append("  br i1 ").append(chk)
            .append(", label %").append(cont)
            .append(", label %").append(throwBlk).append("\n");
        sb.append(throwBlk).append(":\n");

        emitNpeThrowHelper(sb, context, ranges);

        sb.append(cont).append(":\n");
    }

    private int getBaseElementSize(String desc) {
        String base = desc;
        while (base.startsWith("[")) {
            base = base.substring(1);
        }
        if (base.length() == 1) {
            return switch (base.charAt(0)) {
                case 'Z', 'B' -> 1;
                case 'S', 'C' -> 2;
                case 'I', 'F' -> 4;
                case 'J', 'D' -> 8;
                default -> 8;
            };
        }
        return 8;
    }

    private Type elemTypeFromConst(String s) {
        return switch (s) {
            case "boolean" -> Type.BOOLEAN;
            case "byte" -> Type.BYTE;
            case "short" -> Type.SHORT;
            case "char" -> Type.CHAR;
            case "int" -> Type.INT;
            case "long" -> Type.LONG;
            case "float" -> Type.FLOAT;
            case "double" -> Type.DOUBLE;
            default -> Type.fromDescriptor(s);
        };
    }

    /**
     * Maps the element-type operand of NEW_ARRAY (a primitive name, a
     * class internal name, or an already-array descriptor) to the JVM
     * array descriptor of the array being allocated. That descriptor is
     * what identifies the {@code @refclass_*} mirror stored in the array
     * header at offset 0, so it must match the descriptor
     * LlvmGlobalEmitter emits a mirror for.
     *
     * @return the array descriptor, or {@code null} for an unusable
     *         element-type name (the caller then falls back to
     *         {@code [Ljava/lang/Object;})
     */
    private static String arrayClassDescriptorFor(String elemTypeName) {
        if (elemTypeName == null) return null;
        switch (elemTypeName) {
            case "boolean": return "[Z";
            case "byte":    return "[B";
            case "short":   return "[S";
            case "char":    return "[C";
            case "int":     return "[I";
            case "long":    return "[J";
            case "float":   return "[F";
            case "double":  return "[D";
        }
        if (elemTypeName.startsWith("[")) return "[" + elemTypeName;
        return "[L" + elemTypeName + ";";
    }

    private String emitInstruction(Instruction inst, List<TryCatchRange> ranges) {
        StringBuilder sb = new StringBuilder();
        Opcode op = inst.getOpcode();
        String resultName = null;
        if (inst.getResult() != null) {
            resultName = "%tmp_" + (tmpCounter++);
            valueMapper.setValue(inst.getResult(), resultName);
        }

        switch (op) {
            case LOAD: {
                int idx = inst.getLocalIndex();
                Type loadType = inst.getResult().getType();

                String llvmType = LlvmTypeMapper.toLlvmType(loadType);
                String ptr = newAux("ptrcast");
                sb.append("  ").append(ptr).append(" = bitcast i64* %local_").append(idx)
                    .append(" to ").append(llvmType).append("*\n");
                sb.append("  ").append(resultName).append(" = load ").append(llvmType)
                    .append(", ").append(llvmType).append("* ").append(ptr).append("\n");
                break;
            }

            case STORE: {
                Value stored = inst.getOperands().getFirst();
                int idx = inst.getLocalIndex();

                Type storedType = stored.getType();
                Type localType = (inst.getResult() != null)
                    ? inst.getResult().getType()
                    : storedType;
                if (localType.isUnknown()) {
                    localType = storedType;
                }

                String valRef = getLlvmValue(sb, stored);
                String valConverted = castValueToType(sb, valRef, storedType, localType);

                String llvmType = LlvmTypeMapper.toLlvmType(localType);
                String ptr = newAux("ptrcast");
                sb.append("  ").append(ptr).append(" = bitcast i64* %local_").append(idx)
                    .append(" to ").append(llvmType).append("*\n");
                sb.append("  store ").append(llvmType).append(" ").append(valConverted)
                    .append(", ").append(llvmType).append("* ").append(ptr).append("\n");

                if (inst.getResult() != null) {
                    if (inst.getResult() instanceof Temporary resultTmp) {
                        resultTmp.setType(localType);
                    }
                    valueMapper.setValue(inst.getResult(), valConverted);

                    String storedSlot = valueSlots.get(stored);
                    if (storedSlot != null) {
                        valueSlots.put(inst.getResult(), storedSlot);
                    }
                }
                break;
            }

            case ADD: case SUB: case MUL: case DIV: case REM:
            case AND: case OR: case XOR: case SHL: case SHR: case USHR: {
                Value left  = inst.getOperands().get(0);
                Value right = inst.getOperands().get(1);
                Type resType = inst.getResult().getType();

                if ((op == Opcode.DIV || op == Opcode.REM) && !ranges.isEmpty()) {
                    Type lt = left.getType();
                    if (lt == Type.INT || lt == Type.LONG) {
                        emitDivByZeroCheck(sb, right, ranges);
                    }
                }

                String  leftLlvm   = LlvmTypeMapper.toLlvmType(left.getType());
                String  rightLlvm  = LlvmTypeMapper.toLlvmType(right.getType());
                String  resLlvm    = LlvmTypeMapper.toLlvmType(resType);
                boolean leftIsPtr  = leftLlvm.endsWith("*");
                boolean rightIsPtr = rightLlvm.endsWith("*");
                boolean resIsPtr   = resLlvm.endsWith("*");

                boolean isBitwise = (op == Opcode.AND || op == Opcode.OR || op == Opcode.XOR
                    || op == Opcode.SHL || op == Opcode.SHR || op == Opcode.USHR);
                boolean anyPtr    = leftIsPtr || rightIsPtr || resIsPtr;

                if (isBitwise && (leftIsPtr || rightIsPtr)) {
                    Type intType = Type.LONG;
                    String lInt = castValueToType(sb, getLlvmValue(sb, left),  left.getType(),  intType);
                    String rInt = castValueToType(sb, getLlvmValue(sb, right), right.getType(), intType);
                    String llvmOp = mapArithOp(op, Type.LONG);
                    String tmp = newAux("bitwise_int");
                    sb.append("  ").append(tmp).append(" = ").append(llvmOp)
                        .append(" ").append(LlvmTypeMapper.toLlvmType(intType))
                        .append(" ").append(lInt).append(", ").append(rInt).append("\n");
                    String finalVal = castValueToType(sb, tmp, intType, resType);
                    if (resultName != null) {
                        valueMapper.setValue(inst.getResult(), finalVal);
                    }
                    break;
                }

                if (!isBitwise && anyPtr) {
                    String lInt = castValueToType(sb, getLlvmValue(sb, left),  left.getType(),  Type.LONG);
                    String rInt = castValueToType(sb, getLlvmValue(sb, right), right.getType(), Type.LONG);
                    String llvmOp = mapArithOp(op, Type.LONG);
                    String tmp = newAux("ptr_arith");
                    sb.append("  ").append(tmp).append(" = ").append(llvmOp)
                        .append(" i64 ").append(lInt).append(", ").append(rInt).append("\n");
                    String finalVal = castValueToType(sb, tmp, Type.LONG, resType);
                    if (resultName != null) {
                        valueMapper.setValue(inst.getResult(), finalVal);
                    }
                    break;
                }

                String l = castValueToType(sb, getLlvmValue(sb, left),  left.getType(),  resType);
                String r = castValueToType(sb, getLlvmValue(sb, right), right.getType(), resType);
                String llvmOp = mapArithOp(op, resType);
                sb.append("  ").append(resultName).append(" = ").append(llvmOp)
                    .append(" ").append(LlvmTypeMapper.toLlvmType(resType))
                    .append(" ").append(l).append(", ").append(r).append("\n");
                break;
            }

            case EQ: case NE: case LT: case LE: case GT: case GE: {
                Value left = inst.getOperands().get(0);
                Value right = inst.getOperands().get(1);
                Type leftType = left.getType();
                Type rightType = right.getType();
                String l = getLlvmValue(sb, left);
                String r = getLlvmValue(sb, right);

                boolean leftIsPtr = leftType.isReference() || leftType.isArray() || leftType.isNull() || leftType.isBlock();
                boolean rightIsPtr = rightType.isReference() || rightType.isArray() || rightType.isNull() || rightType.isBlock();

                if (leftIsPtr && "0".equals(r)) { r = "null"; }
                if (rightIsPtr && "0".equals(l)) { l = "null"; }

                if ((leftIsPtr && !rightIsPtr) || (!leftIsPtr && rightIsPtr)) {
                    l = castValueToType(sb, l, leftType, Type.LONG);
                    r = castValueToType(sb, r, rightType, Type.LONG);
                    leftType = Type.LONG;
                } else if (!leftIsPtr && !rightIsPtr) {
                    boolean leftIsFloat = leftType == Type.FLOAT || leftType == Type.DOUBLE;
                    boolean rightIsFloat = rightType == Type.FLOAT || rightType == Type.DOUBLE;
                    boolean leftIsInt = isIntegerType(leftType);
                    boolean rightIsInt = isIntegerType(rightType);

                    if ((leftIsFloat && rightIsInt) || (leftIsInt && rightIsFloat)) {
                        if (leftIsInt && rightIsFloat) {
                            l = castValueToType(sb, l, leftType, rightType);
                            leftType = rightType;
                        } else if (leftIsFloat && rightIsInt) {
                            r = castValueToType(sb, r, rightType, leftType);
                        }
                    } else if (leftIsInt && rightIsInt) {
                        l = castValueToType(sb, l, leftType, Type.LONG);
                        r = castValueToType(sb, r, rightType, Type.LONG);
                        leftType = Type.LONG;
                    } else if (!leftType.equals(rightType)) {
                        r = castValueToType(sb, r, rightType, leftType);
                    }
                }

                boolean isFloat = leftType == Type.FLOAT || leftType == Type.DOUBLE;
                String instr = isFloat ? "fcmp" : "icmp";
                String pred = mapCmpOp(op, isFloat);
                sb.append("  ").append(resultName).append(" = ").append(instr)
                    .append(" ").append(pred)
                    .append(" ").append(LlvmTypeMapper.toLlvmType(leftType))
                    .append(" ").append(l).append(", ").append(r).append("\n");
                break;
            }

            case CAST: {
                Value val = inst.getOperands().getFirst();
                Type srcType = val.getType();
                Type destType = inst.getResult().getType();
                String casted = castValueToType(sb, getLlvmValue(sb, val), srcType, destType);
                valueMapper.setValue(inst.getResult(), casted);
                break;
            }

            case GET_FIELD: {
                Value base = inst.getOperands().getFirst();
                Value fieldOperand = inst.getOperands().size() > 1 ? inst.getOperands().get(1) : null;

                if (fieldOperand instanceof Constant offc && offc.getType() == Type.INT) {
                    int offset = ((Number) offc.getValue()).intValue();

                    emitNullCheck(sb, base, ranges, "captured field at offset " + offset);

                    String baseI8 = emitBaseToI8Pointer(sb, base);

                    String gep = newAux("gep");
                    sb.append("  ").append(gep).append(" = getelementptr i8, i8* ")
                        .append(baseI8).append(", i32 ").append(offset).append("\n");

                    Type fieldType = inst.getResult().getType();
                    String fieldLlvm = LlvmTypeMapper.toLlvmType(fieldType);
                    String ptrCast = newAux("ptrcast");
                    sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(gep)
                        .append(" to ").append(fieldLlvm).append("*\n");
                    sb.append("  ").append(resultName).append(" = load ").append(fieldLlvm)
                        .append(", ").append(fieldLlvm).append("* ").append(ptrCast).append("\n");
                    break;
                }

                String fullFieldName = extractFieldName(inst);
                String owner;
                String fieldName;
                int lastDot = fullFieldName.lastIndexOf('.');
                if (lastDot > 0) {
                    owner = fullFieldName.substring(0, lastDot);
                    fieldName = fullFieldName.substring(lastDot + 1);
                } else {
                    owner = extractClassName(base);
                    fieldName = fullFieldName;
                }

                int offset = globalEmitter.getFieldOffset(owner, fieldName);

                emitNullCheck(sb, base, ranges,
                    "field '" + owner + "." + fieldName + "'");

                String baseI8 = emitBaseToI8Pointer(sb, base);

                String gep = newAux("gep");
                sb.append("  ").append(gep).append(" = getelementptr i8, i8* ").append(baseI8)
                    .append(", i32 ").append(offset).append("\n");

                Type fieldType = inst.getResult().getType();
                String fieldLlvm = LlvmTypeMapper.toLlvmType(fieldType);
                String ptrCast = newAux("ptrcast");
                sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(gep)
                    .append(" to ").append(fieldLlvm).append("*\n");
                sb.append("  ").append(resultName).append(" = load ").append(fieldLlvm)
                    .append(", ").append(fieldLlvm).append("* ").append(ptrCast).append("\n");
                break;
            }

            case PUT_FIELD: {
                if (inst.getOperands().size() < 3) {
                    break;
                }

                Value base = inst.getOperands().get(0);
                Value rhs  = inst.getOperands().get(2);
                Value fieldOperand = inst.getOperands().get(1);

                if (fieldOperand instanceof Constant offc && offc.getType() == Type.INT) {
                    int offset = ((Number) offc.getValue()).intValue();

                    emitNullCheck(sb, base, ranges, "captured field at offset " + offset);

                    String baseI8 = emitBaseToI8Pointer(sb, base);

                    String gep = newAux("gep");
                    sb.append("  ").append(gep).append(" = getelementptr i8, i8* ")
                        .append(baseI8).append(", i32 ").append(offset).append("\n");

                    Type fieldType = rhs.getType();
                    String fieldLlvm = LlvmTypeMapper.toLlvmType(fieldType);
                    String rhsRef = getLlvmValue(sb, rhs);
                    String rhsConverted = castValueToType(sb, rhsRef, rhs.getType(), fieldType);
                    String ptrCast = newAux("ptrcast");
                    sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(gep)
                        .append(" to ").append(fieldLlvm).append("*\n");
                    sb.append("  store ").append(fieldLlvm).append(" ").append(rhsConverted)
                        .append(", ").append(fieldLlvm).append("* ").append(ptrCast).append("\n");
                    break;
                }

                String fullFieldName = extractFieldName(inst);
                String owner;
                String fieldName;
                int lastDot = fullFieldName.lastIndexOf('.');
                if (lastDot > 0) {
                    owner = fullFieldName.substring(0, lastDot);
                    fieldName = fullFieldName.substring(lastDot + 1);
                } else {
                    owner = extractClassName(base);
                    fieldName = fullFieldName;
                }

                int offset = globalEmitter.getFieldOffset(owner, fieldName);

                emitNullCheck(sb, base, ranges,
                    "field '" + owner + "." + fieldName + "'");

                String baseI8 = emitBaseToI8Pointer(sb, base);

                String gep = newAux("gep");
                sb.append("  ").append(gep).append(" = getelementptr i8, i8* ").append(baseI8)
                    .append(", i32 ").append(offset).append("\n");

                Type fieldType = globalEmitter.getFieldType(owner, fieldName);
                if (fieldType == null) {
                    fieldType = rhs.getType();
                }
                String fieldLlvm = LlvmTypeMapper.toLlvmType(fieldType);
                String rhsRef = getLlvmValue(sb, rhs);
                String rhsConverted = castValueToType(sb, rhsRef, rhs.getType(), fieldType);
                String ptrCast = newAux("ptrcast");
                sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(gep)
                    .append(" to ").append(fieldLlvm).append("*\n");
                sb.append("  store ").append(fieldLlvm).append(" ").append(rhsConverted)
                    .append(", ").append(fieldLlvm).append("* ").append(ptrCast).append("\n");
                break;
            }

            case GET_STATIC: {
                String fieldName = extractFieldName(inst);
                String globalName = "gv_" + LlvmTypeMapper.sanitizeIdentifier(fieldName);

                String owner;
                String bareField;
                int lastDot = fieldName.lastIndexOf('.');
                if (lastDot > 0) {
                    owner = fieldName.substring(0, lastDot);
                    bareField = fieldName.substring(lastDot + 1);
                } else {
                    owner = "";
                    bareField = fieldName;
                }

                if (LlvmTypeMapper.LAZY_CLINIT_FLAG_FIELD.equals(bareField)) {
                    // The lazy-<clinit> completion flag is read with an
                    // acquire load. It pairs with the release store of the
                    // thread that ran the initializer, so a thread that
                    // observes the flag also observes the state the
                    // initializer published.
                    sb.append("  ").append(resultName)
                        .append(" = load atomic i32, i32* @").append(globalName)
                        .append(" acquire, align 4\n");
                    break;
                }

                // ------------------------------------------------------------------
                // Runtime-owned shutdown flag: defined in jnative_runtime.c as
                // `volatile int gv_jnative_in_shutdown`, written exactly once via
                // __jnative_begin_shutdown's __ATOMIC_RELEASE store. The reader side
                // pairs with acquire so that any state the initializer published
                // before the release is visible here.
                //
                // Routing through getFieldType() would call
                // resolver.getField("", "jnative_in_shutdown"), install an empty-
                // class stub in classMap, log a spurious "class not found"
                // diagnostic, and fall back to the temp's declared type anyway. The
                // explicit case avoids all three and emits the correct memory
                // ordering in one step.
                // ------------------------------------------------------------------
                if ("jnative_in_shutdown".equals(bareField)) {
                    sb.append("  ").append(resultName)
                      .append(" = load atomic i32, i32* @").append(globalName)
                      .append(" acquire, align 4\n");
                    break;
                }

                Type fieldType = globalEmitter.getFieldType(owner, bareField);
                if (fieldType == null) {
                    fieldType = inst.getResult().getType();
                }
                String llvmType = LlvmTypeMapper.toLlvmType(fieldType);
                sb.append("  ").append(resultName).append(" = load ")
                  .append(llvmType).append(", ")
                  .append(llvmType).append("* @").append(globalName).append("\n");
                break;
            }

            case PUT_STATIC: {
                if (inst.getOperands().size() >= 2) {
                    Value rhs = inst.getOperands().get(1);
                    String fieldName = extractFieldName(inst);
                    String globalName = "gv_" + LlvmTypeMapper.sanitizeIdentifier(fieldName);

                    String owner;
                    String bareField;
                    int lastDot = fieldName.lastIndexOf('.');
                    if (lastDot > 0) {
                        owner = fieldName.substring(0, lastDot);
                        bareField = fieldName.substring(lastDot + 1);
                    } else {
                        owner = "";
                        bareField = fieldName;
                    }

                    if (LlvmTypeMapper.LAZY_CLINIT_FLAG_FIELD.equals(bareField)) {
                        // The lazy-<clinit> completion flag is written with
                        // a release store: everything the initializer
                        // published before this store is visible to every
                        // thread that later reads the flag with an acquire
                        // load.
                        String rhsRef = getLlvmValue(sb, rhs);
                        String rhsConverted = castValueToType(sb, rhsRef, rhs.getType(), Type.INT);
                        sb.append("  store atomic i32 ").append(rhsConverted)
                            .append(", i32* @").append(globalName)
                            .append(" release, align 4\n");
                        break;
                    }

                    Type fieldType = globalEmitter.getFieldType(owner, bareField);
                    if (fieldType == null) {
                        fieldType = rhs.getType();
                    }

                    String rhsRef = getLlvmValue(sb, rhs);
                    String rhsConverted = castValueToType(sb, rhsRef, rhs.getType(), fieldType);
                    String llvmType = LlvmTypeMapper.toLlvmType(fieldType);

                    sb.append("  store ").append(llvmType).append(" ").append(rhsConverted)
                        .append(", ").append(llvmType).append("* @").append(globalName).append("\n");
                }
                break;
            }

            case VIRTUAL_CALL:
            case INTERFACE_CALL: {
                List<Value> operands = inst.getOperands();
                if (operands.size() < 2) break;

                Value receiver = operands.get(0);
                Value calleeConst = operands.get(1);
                if (!(calleeConst instanceof Constant)) break;

                String calleeName = ((Constant) calleeConst).getValue().toString();

                int dotIdx = calleeName.lastIndexOf('.');
                if (dotIdx < 0) break;
                String owner = calleeName.substring(0, dotIdx);
                String sig = calleeName.substring(dotIdx + 1);
                int parenIdx = sig.indexOf('(');
                if (parenIdx < 0) break;
                String methodName = sig.substring(0, parenIdx);
                String methodDesc = sig.substring(parenIdx);

                Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;

                {
                    String[] foundOwner = new String[1];
                    io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode targetMethod =
                        resolver.findMethodInHierarchy(owner, methodName, methodDesc, foundOwner);
                    if (targetMethod != null && targetMethod.isStatic()) {
                        String actualOwner = foundOwner[0] != null ? foundOwner[0] : owner;
                        String directMangled =
                            LlvmRuntime.mangleMethod(actualOwner, methodName, methodDesc);

                        if (module.getFunction(directMangled) == null
                            || module.getFunction(directMangled).getEntryBlock() == null) {
                            ensureFunctionDeclared(actualOwner, methodName, methodDesc);
                        }

                        Function directFunc = module.getFunction(directMangled);
                        if (directFunc == null || directFunc.getEntryBlock() == null) {
                            String nativeCandidate =
                                "__jnative_" + LlvmRuntime.mangleMethod(actualOwner, methodName, methodDesc);
                            Function nativeFunc = module.getFunction(nativeCandidate);
                            if (nativeFunc != null) {
                                directFunc = nativeFunc;
                                directMangled = nativeCandidate;
                            }
                        }

                        if (directFunc == null) {
                            throw new IllegalStateException(
                                "VIRTUAL_CALL / INTERFACE_CALL to a static method '"
                                    + actualOwner + "." + methodName + methodDesc
                                    + "' has no IR definition and no native declaration. "
                                    + "Either the resolver fabricated a virtual call for a "
                                    + "static target, or the target was never translated. "
                                    + "The vtable-dispatch fallback would produce a call "
                                    + "with a shifted argument list — refusing to emit it.");
                        }

                        List<Value> args = new ArrayList<>(operands.size() - 1);
                        for (int i = 2; i < operands.size(); i++) {
                            args.add(operands.get(i));
                        }

                        final List<Value> argsForCall = args;
                        final Function calleeForCall = directFunc;
                        final String directMangledFinal = directMangled;
                        final Type retTypeForCall = retType;
                        emitCall(sb, retTypeForCall, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                            StringBuilder argList = new StringBuilder();
                            for (int i = 0; i < argsForCall.size(); i++) {
                                if (i > 0) argList.append(", ");
                                Value arg = argsForCall.get(i);
                                Type paramType = (calleeForCall != null
                                    && i < calleeForCall.getParameters().size())
                                    ? calleeForCall.getParameters().get(i).getType()
                                    : arg.getType();
                                String val = castValueToType(callSb, getLlvmValue(callSb, arg),
                                    arg.getType(), paramType);
                                argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                    .append(" ").append(val);
                            }
                            if (resultReg != null) {
                                callSb.append("  ").append(resultReg).append(" = call ")
                                    .append(LlvmTypeMapper.toLlvmType(retTypeForCall))
                                    .append(" @").append(directMangledFinal)
                                    .append("(").append(argList).append(")\n");
                            } else {
                                callSb.append("  call ")
                                    .append(LlvmTypeMapper.toLlvmType(retTypeForCall))
                                    .append(" @").append(directMangledFinal)
                                    .append("(").append(argList).append(")\n");
                            }
                        });
                        break;
                    }
                }

                // ---------- Polymorphic signature handling ----------
                if (inst.isPolymorphicSignature()) {
                    List<Value> allArgs = new ArrayList<>();
                    allArgs.add(receiver);
                    for (int i = 2; i < operands.size(); i++) {
                        allArgs.add(operands.get(i));
                    }
                    List<Value> argsWithoutReceiver = allArgs.subList(1, allArgs.size());
                    List<Type> paramTypes = argsWithoutReceiver.stream()
                        .map(Value::getType)
                        .collect(Collectors.toList());

                    NativeMethodInfo best = polymorphicResolver.findBestMatch(owner, methodName, retType, paramTypes);
                    if (best != null) {
                        String expectedDesc = buildDescriptor(retType, paramTypes);
                        boolean exactMatch      = expectedDesc.equals(best.getDescriptor());
                        boolean isVoidPtrWrapper = isVoidPtrArgsSignature(best);

                        /*
                         * The concrete typed call path is taken when either:
                         *
                         *   (a) the emitter's descriptor exactly equals the
                         *       resolver's descriptor (the normal, healthy
                         *       case after the encoder fix above), or
                         *
                         *   (b) the resolver returned a concrete typed
                         *       native (its parameters do not fit the
                         *       single-void** wrapper shape). In that case
                         *       the resolver has already confirmed — by way
                         *       of the same full descriptor encoding it used
                         *       to select this candidate — that the target
                         *       is the correct specialization for this call
                         *       site. Any residual string difference is
                         *       cosmetic and must not push the call into the
                         *       packed-i8** path, which would invoke the
                         *       concrete C function with the wrong ABI.
                         *
                         * The void** path is taken only when the resolver
                         * actually selected one of the legacy wrapper
                         * dispatchers.
                         */
                        if (exactMatch || !isVoidPtrWrapper) {
                            String funcName = best.getFullFunctionName();
                            Function func = ensurePolymorphicFunctionDeclared(best);
                            final List<Value> argsForCall = argsWithoutReceiver;
                            final Function calleeForCall = func;
                            emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                                StringBuilder argList = new StringBuilder();
                                for (int i = 0; i < argsForCall.size(); i++) {
                                    if (i > 0) argList.append(", ");
                                    Value arg = argsForCall.get(i);
                                    Type paramType = (calleeForCall != null && i < calleeForCall.getParameters().size())
                                        ? calleeForCall.getParameters().get(i).getType()
                                        : arg.getType();
                                    String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                                    argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                        .append(" ").append(val);
                                }
                                if (resultReg != null) {
                                    callSb.append("  ").append(resultReg).append(" = call ")
                                        .append(LlvmTypeMapper.toLlvmType(retType)).append(" @").append(funcName)
                                        .append("(").append(argList).append(")\n");
                                } else {
                                    callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType))
                                        .append(" @").append(funcName).append("(").append(argList).append(")\n");
                                }
                            });
                            break;
                        } else {
                            emitPolymorphicCall(sb, allArgs, best, retType, paramTypes, ranges, resultName);
                            break;
                        }
                    }

                    String nativeSymbol = "__jnative_"
                        + LlvmRuntime.mangleMethod(owner, methodName, methodDesc);

                    Function nativeFunc = module.getFunction(nativeSymbol);
                    if (nativeFunc == null) {
                        Type nRetType = TypeResolver.descToReturnType(methodDesc);
                        List<Type> nParamTypes = TypeResolver.descToParamTypes(methodDesc);
                        nativeFunc = new Function(nativeSymbol, nRetType);
                        for (int i = 0; i < nParamTypes.size(); i++) {
                            nativeFunc.addParameter(new Parameter(nParamTypes.get(i), i));
                        }
                        module.addFunction(nativeFunc);
                    }

                    final List<Value> argsForCall = argsWithoutReceiver;
                    final Function nativeFuncForCall = nativeFunc;
                    final String nativeSymbolFinal = nativeSymbol;
                    emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                        StringBuilder argList = new StringBuilder();
                        for (int i = 0; i < argsForCall.size(); i++) {
                            if (i > 0) argList.append(", ");
                            Value arg = argsForCall.get(i);
                            Type paramType = (i < nativeFuncForCall.getParameters().size())
                                ? nativeFuncForCall.getParameters().get(i).getType()
                                : arg.getType();
                            String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                            argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                .append(" ").append(val);
                        }
                        if (resultReg != null) {
                            callSb.append("  ").append(resultReg).append(" = call ")
                                .append(LlvmTypeMapper.toLlvmType(retType)).append(" @").append(nativeSymbolFinal)
                                .append("(").append(argList).append(")\n");
                        } else {
                            callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType))
                                .append(" @").append(nativeSymbolFinal).append("(").append(argList).append(")\n");
                        }
                    });
                    break;
                }

                // ---------- Native candidate (direct resolution, bypasses vtable) ----------
                String nativeCandidate = "__jnative_" + LlvmRuntime.mangleCallable(calleeName);
                Function nativeFunc = null;
                if (isVirtualCallDirectNativeSafe(owner, methodName, methodDesc)) {
                    nativeFunc = module.getFunction(nativeCandidate);
                }
                if (nativeFunc != null) {
                    List<Value> args = new ArrayList<>();
                    args.add(receiver);
                    for (int i = 2; i < operands.size(); i++) args.add(operands.get(i));
                    final List<Value> argsForCall = args;
                    final Function nativeFuncForCall = nativeFunc;
                    final String nativeCandidateFinal = nativeCandidate;
                    emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                        StringBuilder argList = new StringBuilder();
                        for (int i = 0; i < argsForCall.size(); i++) {
                            if (i > 0) argList.append(", ");
                            Value arg = argsForCall.get(i);
                            Type paramType = (i < nativeFuncForCall.getParameters().size())
                                ? nativeFuncForCall.getParameters().get(i).getType()
                                : arg.getType();
                            String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                            argList.append(LlvmTypeMapper.toLlvmType(paramType)).append(" ").append(val);
                        }
                        if (resultReg != null) {
                            callSb.append("  ").append(resultReg).append(" = call ")
                                .append(LlvmTypeMapper.toLlvmType(retType)).append(" @").append(nativeCandidateFinal)
                                .append("(").append(argList).append(")\n");
                        } else {
                            callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType))
                                .append(" @").append(nativeCandidateFinal).append("(").append(argList).append(")\n");
                        }
                    });
                    break;
                }

                // ---------- Dispatch ----------
                List<Type> paramTypes = TypeResolver.descToParamTypes(methodDesc);
                Type receiverType = Type.reference(owner);
                List<Value> allArgs = new ArrayList<>();
                allArgs.add(receiver);
                for (int i = 2; i < operands.size(); i++) allArgs.add(operands.get(i));

                boolean receiverIsRef = receiver.getType().isReference()
                    || receiver.getType().isArray()
                    || receiver.getType().isNull()
                    || receiver.getType().isBlock()
                    || receiver.getType().isUnknown();

                boolean receiverIsArray = receiver.getType().isArray();

                boolean isInterfaceCall = (op == Opcode.INTERFACE_CALL);

                int dispatchSlot;
                int interfaceId = -1;
                if (isInterfaceCall) {
                    interfaceId  = globalEmitter.getInterfaceId(owner);
                    dispatchSlot = globalEmitter.getInterfaceMethodSlot(owner, methodName, methodDesc);
                } else {
                    dispatchSlot = globalEmitter.getVirtualSlot(owner, methodName, methodDesc);
                }

                if (!receiverIsRef || dispatchSlot < 0 || (isInterfaceCall && interfaceId < 0)
                    || receiverIsArray) {

                    // If owner is an array name ("[I", "[Ljava/lang/Object;",
                    // "[[I", ...), the actual method is inherited from
                    // java.lang.Object: no array declares methods of its own.
                    // MethodTranslator usually resolves owner to
                    // java/lang/Object by itself, but the fallback is
                    // mandatory: when findMethodInHierarchy declines (e.g. for
                    // a non-standard array descriptor) without it we would look
                    // for
                    //     fn__Ljava_lang_Object__clone___...
                    // and find neither a body nor a native — landing in
                    // unresolved_slot.
                    String directOwner = owner;
                    if (directOwner != null && !directOwner.isEmpty()
                        && directOwner.charAt(0) == '[') {
                        directOwner = "java/lang/Object";
                    }

                    String directMangled = LlvmRuntime.mangleMethod(
                        directOwner, methodName, methodDesc);
                    String callTarget = null;
                    Function calleeForCall = null;

                    // Look up the direct target. If `directMangled` is registered as an
                    // alias — either to a real IR definition or to a C-side override
                    // declaration — the returned Function's name differs from
                    // `directMangled` and its target is callable even without an entry
                    // block in this module. The previous check required
                    // `getEntryBlock() != null`, which rejected every alias to a
                    // declaration (native overrides, __jnative_* externs) and pushed the
                    // call into the unresolved-slot path.
                    Function plainConcrete = module.getFunction(directMangled);
                    if (plainConcrete != null) {
                        boolean isAlias = !plainConcrete.getName().equals(directMangled);
                        if (isAlias || plainConcrete.getEntryBlock() != null) {
                            callTarget = plainConcrete.getName();
                            calleeForCall = plainConcrete;
                        }
                    }

                    if (callTarget == null) {
                        String nativeMangled = "__jnative_" + directMangled;
                        Function nativeTarget = module.getFunction(nativeMangled);
                        if (nativeTarget != null) {
                            callTarget = nativeMangled;
                            calleeForCall = nativeTarget;
                        }
                    }

                    if (callTarget != null) {
                        final List<Value> argsForCall = allArgs;
                        final Function calleeForCallFinal = calleeForCall;
                        final Type receiverTypeFinal = receiverType;
                        final List<Type> paramTypesFinal = paramTypes;
                        final String callTargetFinal = callTarget;
                        emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                            StringBuilder argList = new StringBuilder();
                            for (int i = 0; i < argsForCall.size(); i++) {
                                if (i > 0) argList.append(", ");
                                Value arg = argsForCall.get(i);
                                Type paramType;
                                if (calleeForCallFinal != null && i < calleeForCallFinal.getParameters().size()) {
                                    paramType = calleeForCallFinal.getParameters().get(i).getType();
                                } else if (i == 0) {
                                    paramType = receiverTypeFinal;
                                } else if (i - 1 < paramTypesFinal.size()) {
                                    paramType = paramTypesFinal.get(i - 1);
                                } else {
                                    paramType = arg.getType();
                                }
                                String val = castValueToType(callSb, getLlvmValue(callSb, arg),
                                    arg.getType(), paramType);
                                argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                    .append(" ").append(val);
                            }
                            if (resultReg != null) {
                                callSb.append("  ").append(resultReg).append(" = call ")
                                    .append(LlvmTypeMapper.toLlvmType(retType))
                                    .append(" @").append(callTargetFinal)
                                    .append("(").append(argList).append(")\n");
                            } else {
                                callSb.append("  call ")
                                    .append(LlvmTypeMapper.toLlvmType(retType))
                                    .append(" @").append(callTargetFinal)
                                    .append("(").append(argList).append(")\n");
                            }
                        });
                        break;
                    }

                    String diag = "unresolved call target '" + directMangled
                        + "' (original: " + owner + "." + methodName + methodDesc
                        + ") — the method is referenced by reachable bytecode "
                        + "but has no body in the module. "
                        + "This is a reachability-analysis gap.";
                    String msgRef = stringRef(diag);
                    sb.append("  call void @__jnative_unresolved_slot(i8* ")
                        .append(msgRef).append(")\n");
                    sb.append("  unreachable\n");
                    if (inst.getResult() != null) {
                        valueMapper.setValue(inst.getResult(),
                            getDefaultValue(inst.getResult().getType()));
                    }
                    break;
                }

                // -------- vtable / itable dispatch --------
                emitNullCheck(sb, receiver, ranges,
                    "receiver of " + owner + "." + methodName + methodDesc);
                String receiverRef = getLlvmValue(sb, receiver);

                // Whether the runtime type of `receiver` can be an array.
                // Arrays are the only objects whose first word is a class
                // mirror rather than a vtable, and only three static types
                // admit them: Object, Cloneable, Serializable. For every
                // other static reference type the runtime value is
                // guaranteed to be an ordinary object, so obj[0] can be read
                // directly as a %JNativeVTable* and the whole hash-set probe
                // inside __jnative_resolve_dispatch_vtable is skipped.
                //
                // The emitter derives this from the receiver's declared IR
                // type, which is exactly the static type the bytecode
                // verifier computed. An UNKNOWN, NULL or BLOCK type keeps the
                // resolve path because there is no static guarantee.
                boolean maybeArray = true;
                Type recvTy = receiver.getType();
                if (recvTy.isReference()) {
                    String rcn = recvTy.getClassName();
                    maybeArray = (rcn == null)
                        || "java/lang/Object".equals(rcn)
                        || "java/lang/Cloneable".equals(rcn)
                        || "java/io/Serializable".equals(rcn);
                }

                String vtableStruct = newAux("vtable_struct");
                if (maybeArray) {
                    // __jnative_resolve_dispatch_vtable distinguishes an
                    // ordinary object (obj[0] is a vtable) from an array
                    // (obj[0] is a %ReflectionClass*), and throws a
                    // diagnostic when obj[0] is NULL or unregistered.
                    sb.append("  ").append(vtableStruct)
                        .append(" = call %JNativeVTable* "
                            + "@__jnative_resolve_dispatch_vtable(i8* ")
                        .append(receiverRef).append(")\n");
                } else {
                    // The static type guarantees an ordinary object, so the
                    // first word is the vtable itself.
                    String vtableSlot = newAux("vtable_slot");
                    sb.append("  ").append(vtableSlot)
                        .append(" = bitcast i8* ").append(receiverRef)
                        .append(" to %JNativeVTable**\n");
                    sb.append("  ").append(vtableStruct)
                        .append(" = load %JNativeVTable*, %JNativeVTable** ")
                        .append(vtableSlot).append("\n");
                }

                String funcPtr;
                if (isInterfaceCall) {
                    String ifacemapFieldPtr = newAux("ifacemap_field_ptr");
                    sb.append("  ").append(ifacemapFieldPtr)
                        .append(" = getelementptr %JNativeVTable, %JNativeVTable* ")
                        .append(vtableStruct).append(", i32 0, i32 1\n");

                    String ifacemapPtr = newAux("ifacemap_ptr");
                    sb.append("  ").append(ifacemapPtr)
                        .append(" = load %JNativeIfaceMap*, %JNativeIfaceMap** ")
                        .append(ifacemapFieldPtr).append("\n");

                    String itablePtr = newAux("itable_ptr");
                    sb.append("  ").append(itablePtr)
                        .append(" = call i8** @__jnative_lookup_itable(%JNativeIfaceMap* ")
                        .append(ifacemapPtr).append(", i32 ").append(interfaceId).append(")\n");

                    String funcPtrGep = newAux("funcptr_gep");
                    sb.append("  ").append(funcPtrGep)
                        .append(" = getelementptr i8*, i8** ").append(itablePtr)
                        .append(", i32 ").append(dispatchSlot).append("\n");

                    funcPtr = newAux("funcptr");
                    sb.append("  ").append(funcPtr)
                        .append(" = load i8*, i8** ").append(funcPtrGep).append("\n");
                } else {
                    String methodsFieldPtr = newAux("methods_field_ptr");
                    sb.append("  ").append(methodsFieldPtr)
                        .append(" = getelementptr %JNativeVTable, %JNativeVTable* ")
                        .append(vtableStruct).append(", i32 0, i32 0\n");

                    String methodsArr = newAux("methods_arr");
                    sb.append("  ").append(methodsArr)
                        .append(" = load i8**, i8*** ").append(methodsFieldPtr).append("\n");

                    String funcPtrGep = newAux("funcptr_gep");
                    sb.append("  ").append(funcPtrGep)
                        .append(" = getelementptr i8*, i8** ").append(methodsArr)
                        .append(", i32 ").append(dispatchSlot).append("\n");

                    funcPtr = newAux("funcptr");
                    sb.append("  ").append(funcPtr)
                        .append(" = load i8*, i8* ").append(funcPtrGep).append("\n");
                }

                String funcType = LlvmRuntime.getFunctionType(owner, sig);
                String funcPtrCast = newAux("fptrcast");
                sb.append("  ").append(funcPtrCast).append(" = bitcast i8* ").append(funcPtr)
                    .append(" to ").append(funcType).append("\n");

                final List<Value> argsForCall = allArgs;
                final Type receiverTypeFinal = receiverType;
                final List<Type> paramTypesFinal = paramTypes;
                final String funcPtrCastFinal = funcPtrCast;
                emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                    StringBuilder argList = new StringBuilder();
                    for (int i = 0; i < argsForCall.size(); i++) {
                        if (i > 0) argList.append(", ");
                        Value arg = argsForCall.get(i);
                        Type paramType;
                        if (i == 0) paramType = receiverTypeFinal;
                        else if (i - 1 < paramTypesFinal.size()) paramType = paramTypesFinal.get(i - 1);
                        else paramType = arg.getType();
                        String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                        argList.append(LlvmTypeMapper.toLlvmType(paramType)).append(" ").append(val);
                    }
                    if (resultReg != null) {
                        callSb.append("  ").append(resultReg).append(" = call ")
                            .append(LlvmTypeMapper.toLlvmType(retType)).append(" ").append(funcPtrCastFinal)
                            .append("(").append(argList).append(")\n");
                    } else {
                        callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType)).append(" ")
                            .append(funcPtrCastFinal).append("(").append(argList).append(")\n");
                    }
                });
                break;
            }

            case STATIC_CALL:
            case CALL: {
                String calleeName = extractCalleeName(inst);
                if (calleeName == null) break;

                String[] foundOwner = new String[1];
                boolean calleeIsStatic = false;
                {
                    int dotIdx = calleeName.lastIndexOf('.');
                    int parenIdx = calleeName.indexOf('(');
                    if (dotIdx > 0 && parenIdx > dotIdx) {
                        String owner = calleeName.substring(0, dotIdx);
                        String methodPart = calleeName.substring(dotIdx + 1);
                        int localParenIdx = parenIdx - dotIdx - 1;
                        String methodName = methodPart.substring(0, localParenIdx);
                        String descriptor = methodPart.substring(localParenIdx);

                        io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode mn =
                            resolver.findMethodInHierarchy(owner, methodName, descriptor, foundOwner);
                        if (mn != null && mn.isStatic()) {
                            calleeIsStatic = true;
                            String actualOwner =
                                (foundOwner[0] != null && !foundOwner[0].isEmpty())
                                    ? foundOwner[0]
                                    : owner;
                            if (!actualOwner.equals(owner)) {
                                calleeName = actualOwner + "." + methodName + descriptor;
                            }
                        }
                    }
                }

                if (!calleeIsStatic) {
                    int dotIdx = calleeName.lastIndexOf('.');
                    int parenIdx = calleeName.indexOf('(');
                    if (dotIdx > 0 && parenIdx > dotIdx) {
                        String owner = calleeName.substring(0, dotIdx);
                        String methodPart = calleeName.substring(dotIdx + 1);
                        int localParenIdx = parenIdx - dotIdx - 1;
                        String methodName = methodPart.substring(0, localParenIdx);
                        String descriptor = methodPart.substring(localParenIdx);

                        String[] look = new String[1];
                        io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode mn =
                            resolver.findMethodInHierarchy(owner, methodName, descriptor, look);
                        if (mn != null && !mn.isStatic()
                            && !owner.startsWith("java/lang/invoke")
                            && !"__jnative_get_exception_object".equals(calleeName)
                            && !calleeName.startsWith("__jnative_")) {
                            throw new IllegalStateException(
                                "STATIC_CALL / CALL opcode carries an instance-method "
                                    + "target '" + owner + "." + methodName + descriptor
                                    + "'.  The reachability analysis or the IR builder "
                                    + "mis-classified the call site; refusing to emit "
                                    + "a direct call with a mismatched calling convention.");
                        }
                    }
                }

                if (inst.isPolymorphicSignature()) {
                    int dotIdx = calleeName.lastIndexOf('.');
                    int parenIdx = calleeName.indexOf('(');
                    if (dotIdx > 0 && parenIdx > dotIdx) {
                        String owner = calleeName.substring(0, dotIdx);
                        String methodPart = calleeName.substring(dotIdx + 1);
                        int localParenIdx = parenIdx - dotIdx - 1;
                        String methodName = methodPart.substring(0, localParenIdx);

                        Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;
                        List<Value> args = getCallArguments(inst);
                        List<Type> paramTypes = args.stream().map(Value::getType).collect(Collectors.toList());

                        NativeMethodInfo best = polymorphicResolver.findBestMatch(
                            owner, methodName, retType, paramTypes);
                        if (best != null) {
                            String expectedDesc = buildDescriptor(retType, paramTypes);
                            boolean exactMatch       = expectedDesc.equals(best.getDescriptor());
                            boolean isVoidPtrWrapper = isVoidPtrArgsSignature(best);

                            /*
                             * Same dispatch rule as the VIRTUAL_CALL /
                             * INTERFACE_CALL site above: a concrete typed
                             * native (not a single-void** wrapper) is
                             * always invoked through the direct typed path,
                             * even if a residual encoding difference makes
                             * the string comparison fail. Only genuine
                             * void** dispatchers go through the packed
                             * i8** path.
                             */
                            if (exactMatch || !isVoidPtrWrapper) {
                                String funcName = best.getFullFunctionName();
                                Function func = ensurePolymorphicFunctionDeclared(best);
                                final List<Value> argsForCall = args;
                                final Function calleeForCall = func;
                                emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                                    StringBuilder argList = new StringBuilder();
                                    for (int i = 0; i < argsForCall.size(); i++) {
                                        if (i > 0) argList.append(", ");
                                        Value arg = argsForCall.get(i);
                                        Type paramType = (calleeForCall != null && i < calleeForCall.getParameters().size())
                                            ? calleeForCall.getParameters().get(i).getType()
                                            : arg.getType();
                                        String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                                        argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                            .append(" ").append(val);
                                    }
                                    if (resultReg != null) {
                                        callSb.append("  ").append(resultReg).append(" = call ")
                                            .append(LlvmTypeMapper.toLlvmType(retType)).append(" @").append(funcName)
                                            .append("(").append(argList).append(")\n");
                                    } else {
                                        callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType))
                                            .append(" @").append(funcName).append("(").append(argList).append(")\n");
                                    }
                                });
                                break;
                            } else {
                                emitPolymorphicCall(sb, args, best, retType, paramTypes,
                                    ranges, resultName);
                                break;
                            }
                        }
                    }
                }

                Function calleeFunc = module.getFunction(calleeName);
                if (calleeFunc == null) {
                    calleeFunc = module.getFunction(LlvmRuntime.mangleCallable(calleeName));
                }
                String mangledCallee;
                if (calleeFunc != null) {
                    mangledCallee = calleeFunc.getName();
                } else if (calleeName.startsWith("__jnative_")) {
                    mangledCallee = calleeName;
                } else {
                    String nativeCandidate = "__jnative_" + LlvmRuntime.mangleCallable(calleeName);
                    if (module.getFunction(nativeCandidate) != null) {
                        mangledCallee = nativeCandidate;
                    } else {
                        String nativeSymbol = null;
                        Function nativeFunc = null;
                        int dotIdx = calleeName.lastIndexOf('.');
                        int parenIdx = calleeName.indexOf('(');
                        if (dotIdx > 0 && parenIdx > dotIdx) {
                            String owner = calleeName.substring(0, dotIdx);
                            String methodPart = calleeName.substring(dotIdx + 1);
                            int localParenIdx = parenIdx - dotIdx - 1;
                            String methodName = methodPart.substring(0, localParenIdx);
                            String descriptor = methodPart.substring(localParenIdx);

                            String[] nativeLookup = new String[1];
                            io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode mn =
                                resolver.findMethodInHierarchy(owner, methodName, descriptor, nativeLookup);
                            if (mn != null && mn.isNative()) {
                                String actualOwner =
                                    nativeLookup[0] != null ? nativeLookup[0] : owner;
                                // Prefer the function the translator already
                                // registered for this native — either the
                                // __jnative_ override or, when the class has
                                // no JNative C file, its JNI symbol.
                                Function resolved =
                                    lookupNativeFunction(actualOwner, methodName, descriptor);
                                if (resolved != null) {
                                    nativeFunc = resolved;
                                    nativeSymbol = resolved.getName();
                                } else {
                                    nativeSymbol = "__jnative_"
                                        + LlvmRuntime.mangleMethod(actualOwner, methodName, descriptor);
                                }
                            }
                        }

                        if (nativeSymbol != null) {
                            if (nativeFunc == null && module.getFunction(nativeSymbol) == null) {
                                int paren2 = calleeName.indexOf('(');
                                String desc2 = calleeName.substring(paren2);
                                Type nRetType = TypeResolver.descToReturnType(desc2);
                                List<Type> nParamTypes = TypeResolver.descToParamTypes(desc2);
                                Function nFunc = new Function(nativeSymbol, nRetType);
                                for (int i = 0; i < nParamTypes.size(); i++) {
                                    nFunc.addParameter(new Parameter(nParamTypes.get(i), i));
                                }
                                module.addFunction(nFunc);
                            }
                            mangledCallee = nativeSymbol;
                            if (nativeFunc != null) {
                                calleeFunc = nativeFunc;
                            }
                        } else {
                            if (dotIdx > 0 && parenIdx > dotIdx) {
                                String owner = calleeName.substring(0, dotIdx);
                                String methodPart = calleeName.substring(dotIdx + 1);
                                int localParenIdx = parenIdx - dotIdx - 1;
                                String methodName = methodPart.substring(0, localParenIdx);
                                String descriptor = methodPart.substring(localParenIdx);
                                ensureFunctionDeclared(owner, methodName, descriptor);
                            }
                            mangledCallee = LlvmRuntime.mangleCallable(calleeName);
                        }
                    }
                }

                if (!isCallableSymbolDefined(mangledCallee)) {
                    String diag = "unresolved call target '" + mangledCallee
                        + "' (original: " + calleeName + ") — the method is referenced "
                        + "by reachable bytecode but was never translated into the module. "
                        + "This is almost always a reachability-analysis gap.";
                    String msgRef = stringRef(diag);
                    sb.append("  call void @__jnative_unresolved_slot(i8* ")
                      .append(msgRef).append(")\n");
                    sb.append("  unreachable\n");
                    if (inst.getResult() != null) {
                        valueMapper.setValue(inst.getResult(),
                            getDefaultValue(inst.getResult().getType()));
                    }
                    break;
                }

                List<Value> args = getCallArguments(inst);

                if (calleeFunc != null) {
                    int expected = calleeFunc.getParameters().size();
                    if (args.size() > expected) {
                        boolean removeFirst = false;
                        if (expected == 0) {
                            removeFirst = true;
                        } else {
                            Type firstArgType = args.getFirst().getType();
                            Type firstParamType = calleeFunc.getParameters().getFirst().getType();
                            if (firstArgType.isReference() && !firstParamType.isReference()) {
                                removeFirst = true;
                            }
                        }
                        if (removeFirst) {
                            args = args.subList(1, args.size());
                        }
                    }
                }

                Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;
                final List<Value> argsForCall = args;
                final Function calleeForCall = calleeFunc;
                final String mangledCalleeFinal = mangledCallee;
                final Type retTypeForCall = retType;
                final boolean isJni = calleeForCall != null && calleeForCall.isJniNative();
                final boolean isStaticJni = calleeForCall != null && calleeForCall.isStaticJniNative();
                final String jniClassRef = isJni && calleeForCall.getOwnerClass() != null
                    ? globalEmitter.getRefClassGlobalName(calleeForCall.getOwnerClass())
                    : null;
                emitCall(sb, retTypeForCall, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                    StringBuilder argList = new StringBuilder();
                    // A JNI native never receives the Java receiver alone:
                    // the C ABI requires the thread-local JNIEnv* first and,
                    // for a static method, the jclass mirror second.
                    if (isJni) {
                        String env = newAux("jnienv");
                        callSb.append("  ").append(env)
                            .append(" = call i8* @__jnative_get_jnienv()\n");
                        argList.append("i8* ").append(env);
                        if (isStaticJni && jniClassRef != null) {
                            argList.append(", i8* ").append(jniClassRef);
                        }
                    }
                    for (int i = 0; i < argsForCall.size(); i++) {
                        if (!argList.isEmpty()) argList.append(", ");
                        Value arg = argsForCall.get(i);
                        Type paramType;
                        if (calleeForCall != null && i < calleeForCall.getParameters().size()) {
                            paramType = calleeForCall.getParameters().get(i).getType();
                        } else {
                            paramType = arg.getType();
                        }
                        String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                        argList.append(LlvmTypeMapper.toLlvmType(paramType)).append(" ").append(val);
                    }
                    if (resultReg != null) {
                        callSb.append("  ").append(resultReg).append(" = call ")
                            .append(LlvmTypeMapper.toLlvmType(retTypeForCall)).append(" @").append(mangledCalleeFinal)
                            .append("(").append(argList).append(")\n");
                    } else {
                        callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retTypeForCall))
                            .append(" @").append(mangledCalleeFinal).append("(").append(argList).append(")\n");
                    }
                });
                break;
            }

            case SPECIAL_CALL: {
                if (inst.getOperands().size() < 2) break;
                Value receiver = inst.getOperands().get(0);
                Value calleeConst = inst.getOperands().get(1);
                if (!(calleeConst instanceof Constant)) break;
                String calleeName = ((Constant) calleeConst).getValue().toString();

                if (inst.isPolymorphicSignature()) {
                    int dotIdx = calleeName.lastIndexOf('.');
                    int parenIdx = calleeName.indexOf('(');
                    if (dotIdx > 0 && parenIdx > dotIdx) {
                        String owner = calleeName.substring(0, dotIdx);
                        String methodPart = calleeName.substring(dotIdx + 1);
                        int localParenIdx = parenIdx - dotIdx - 1;
                        String methodName = methodPart.substring(0, localParenIdx);

                        List<Value> allArgs = new ArrayList<>();
                        allArgs.add(receiver);
                        for (int i = 2; i < inst.getOperands().size(); i++) {
                            allArgs.add(inst.getOperands().get(i));
                        }
                        List<Value> argsWithoutReceiver = allArgs.subList(1, allArgs.size());
                        Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;
                        List<Type> paramTypes = argsWithoutReceiver.stream()
                            .map(Value::getType).collect(Collectors.toList());

                        NativeMethodInfo best = polymorphicResolver.findBestMatch(
                            owner, methodName, retType, paramTypes);
                        if (best != null) {
                            String expectedDesc = buildDescriptor(retType, paramTypes);
                            boolean exactMatch       = expectedDesc.equals(best.getDescriptor());
                            boolean isVoidPtrWrapper = isVoidPtrArgsSignature(best);

                            /*
                             * Same dispatch rule as the VIRTUAL_CALL /
                             * INTERFACE_CALL and STATIC_CALL / CALL sites.
                             * See those comments for the full rationale.
                             */
                            if (exactMatch || !isVoidPtrWrapper) {
                                String funcName = best.getFullFunctionName();
                                Function func = ensurePolymorphicFunctionDeclared(best);
                                final List<Value> argsForCall = argsWithoutReceiver;
                                final Function calleeForCall = func;
                                emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                                    StringBuilder argList = new StringBuilder();
                                    for (int i = 0; i < argsForCall.size(); i++) {
                                        if (i > 0) argList.append(", ");
                                        Value arg = argsForCall.get(i);
                                        Type paramType = (calleeForCall != null && i < calleeForCall.getParameters().size())
                                            ? calleeForCall.getParameters().get(i).getType()
                                            : arg.getType();
                                        String val = castValueToType(callSb, getLlvmValue(callSb, arg),
                                            arg.getType(), paramType);
                                        argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                            .append(" ").append(val);
                                    }
                                    if (resultReg != null) {
                                        callSb.append("  ").append(resultReg).append(" = call ")
                                            .append(LlvmTypeMapper.toLlvmType(retType)).append(" @").append(funcName)
                                            .append("(").append(argList).append(")\n");
                                    } else {
                                        callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType))
                                            .append(" @").append(funcName).append("(").append(argList).append(")\n");
                                    }
                                });
                                break;
                            } else {
                                emitPolymorphicCall(sb, allArgs, best, retType, paramTypes,
                                    ranges, resultName);
                                break;
                            }
                        }
                    }
                }

                Function calleeFunc = module.getFunction(calleeName);
                if (calleeFunc == null) {
                    calleeFunc = module.getFunction(LlvmRuntime.mangleCallable(calleeName));
                }
                String mangledCallee;
                if (calleeFunc != null) {
                    mangledCallee = calleeFunc.getName();
                } else {
                    String nativeCandidate = "__jnative_" + LlvmRuntime.mangleCallable(calleeName);
                    if (module.getFunction(nativeCandidate) != null) {
                        mangledCallee = nativeCandidate;
                    } else {
                        String nativeSymbol = null;
                        Function nativeFunc = null;
                        int dotIdx = calleeName.lastIndexOf('.');
                        int parenIdx = calleeName.indexOf('(');
                        if (dotIdx > 0 && parenIdx > dotIdx) {
                            String owner = calleeName.substring(0, dotIdx);
                            String methodPart = calleeName.substring(dotIdx + 1);
                            int localParenIdx = parenIdx - dotIdx - 1;
                            String methodName = methodPart.substring(0, localParenIdx);
                            String descriptor = methodPart.substring(localParenIdx);

                            String[] nativeLookup = new String[1];
                            io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode mn =
                                resolver.findMethodInHierarchy(owner, methodName, descriptor, nativeLookup);
                            if (mn != null && mn.isNative()) {
                                String actualOwner =
                                    nativeLookup[0] != null ? nativeLookup[0] : owner;
                                // Prefer the function the translator already
                                // registered for this native — either the
                                // __jnative_ override or, when the class has
                                // no JNative C file, its JNI symbol.
                                Function resolved =
                                    lookupNativeFunction(actualOwner, methodName, descriptor);
                                if (resolved != null) {
                                    nativeFunc = resolved;
                                    nativeSymbol = resolved.getName();
                                } else {
                                    nativeSymbol = "__jnative_"
                                        + LlvmRuntime.mangleMethod(actualOwner, methodName, descriptor);
                                }
                            }
                        }

                        if (nativeSymbol != null) {
                            if (nativeFunc == null && module.getFunction(nativeSymbol) == null) {
                                int paren2 = calleeName.indexOf('(');
                                String desc2 = calleeName.substring(paren2);
                                Type nRetType = TypeResolver.descToReturnType(desc2);
                                List<Type> nParamTypes = TypeResolver.descToParamTypes(desc2);
                                Function nFunc = new Function(nativeSymbol, nRetType);
                                for (int i = 0; i < nParamTypes.size(); i++) {
                                    nFunc.addParameter(new Parameter(nParamTypes.get(i), i));
                                }
                                module.addFunction(nFunc);
                            }
                            mangledCallee = nativeSymbol;
                            if (nativeFunc != null) {
                                calleeFunc = nativeFunc;
                            }
                        } else {
                            if (dotIdx > 0 && parenIdx > dotIdx) {
                                String owner = calleeName.substring(0, dotIdx);
                                String methodPart = calleeName.substring(dotIdx + 1);
                                int localParenIdx = parenIdx - dotIdx - 1;
                                String methodName = methodPart.substring(0, localParenIdx);
                                String descriptor = methodPart.substring(localParenIdx);
                                ensureFunctionDeclared(owner, methodName, descriptor);
                            }
                            mangledCallee = LlvmRuntime.mangleCallable(calleeName);
                        }
                    }
                }

                if (!isCallableSymbolDefined(mangledCallee)) {
                    String diag = "unresolved call target '" + mangledCallee
                        + "' (original: " + calleeName + ") — the method is referenced "
                        + "by reachable bytecode but was never translated into the module. "
                        + "This is almost always a reachability-analysis gap.";
                    String msgRef = stringRef(diag);
                    sb.append("  call void @__jnative_unresolved_slot(i8* ")
                      .append(msgRef).append(")\n");
                    sb.append("  unreachable\n");
                    if (inst.getResult() != null) {
                        valueMapper.setValue(inst.getResult(),
                            getDefaultValue(inst.getResult().getType()));
                    }
                    break;
                }

                List<Value> args = new ArrayList<>();
                args.add(receiver);
                for (int i = 2; i < inst.getOperands().size(); i++) {
                    args.add(inst.getOperands().get(i));
                }

                Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;
                final List<Value> argsForCall = args;
                final Function calleeForCall = calleeFunc;
                final String mangledCalleeFinal = mangledCallee;
                final Type retTypeForCall = retType;
                final boolean isJni = calleeForCall != null && calleeForCall.isJniNative();
                final boolean isStaticJni = calleeForCall != null && calleeForCall.isStaticJniNative();
                final String jniClassRef = isJni && calleeForCall.getOwnerClass() != null
                    ? globalEmitter.getRefClassGlobalName(calleeForCall.getOwnerClass())
                    : null;
                emitCall(sb, retTypeForCall, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                    StringBuilder argList = new StringBuilder();
                    // A JNI native never receives the Java receiver alone:
                    // the C ABI requires the thread-local JNIEnv* first and,
                    // for a static method, the jclass mirror second.
                    if (isJni) {
                        String env = newAux("jnienv");
                        callSb.append("  ").append(env)
                            .append(" = call i8* @__jnative_get_jnienv()\n");
                        argList.append("i8* ").append(env);
                        if (isStaticJni && jniClassRef != null) {
                            argList.append(", i8* ").append(jniClassRef);
                        }
                    }
                    for (int i = 0; i < argsForCall.size(); i++) {
                        if (!argList.isEmpty()) argList.append(", ");
                        Value arg = argsForCall.get(i);
                        Type paramType;
                        if (calleeForCall != null && i < calleeForCall.getParameters().size()) {
                            paramType = calleeForCall.getParameters().get(i).getType();
                        } else {
                            paramType = arg.getType();
                        }
                        String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                        argList.append(LlvmTypeMapper.toLlvmType(paramType)).append(" ").append(val);
                    }
                    if (resultReg != null) {
                        callSb.append("  ").append(resultReg).append(" = call ")
                            .append(LlvmTypeMapper.toLlvmType(retTypeForCall)).append(" @").append(mangledCalleeFinal)
                            .append("(").append(argList).append(")\n");
                    } else {
                        callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retTypeForCall))
                            .append(" @").append(mangledCalleeFinal).append("(").append(argList).append(")\n");
                    }
                });
                break;
            }

            case NEW: {
                String className = extractTypeName(inst);
                String structType = globalEmitter.getStructName(className);

                if (inst.isStackAllocated()) {
                    // ------------------------------------------------------------------
                    // Stack-allocated object.
                    //
                    // The alloca is queued into pendingSlotDeclarations rather than
                    // emitted in the current block, so that it lands in the function's
                    // entry block. Its address therefore dominates every use,
                    // including uses that follow a try-guard region.
                    //
                    // The bitcast into the object's reference type is NOT emitted here.
                    // Doing so would define an SSA value in this block, and a use of
                    // that value in a different block — most importantly, the
                    // destructor call that DestructorInserter routinely places in a
                    // successor block — would then violate LLVM's dominance rule and
                    // the module would be rejected with
                    //
                    //     Instruction does not dominate all uses!
                    //
                    // Instead, the alloca name is registered in valuePointers and
                    // getLlvmValue re-emits the bitcast at each use site, in the block
                    // that actually performs the use.
                    //
                    // The alloca is explicitly zeroed with llvm.memset for exactly the
                    // same reason the heap path below uses calloc: the field-overwrite
                    // instrumentation of DestructorInserter reads the field's
                    // "previous" value BEFORE any constructor body has written it, and
                    // that read must observe NULL, not uninitialised stack memory.
                    // ------------------------------------------------------------------
                    String allocName = newAux("stack_alloc");
                    pendingSlotDeclarations.add(
                        "  " + allocName + " = alloca " + structType + ", align 8\n");

                    String gepReg = newAux("alloc_gep");
                    sb.append("  ").append(gepReg)
                        .append(" = getelementptr ").append(structType)
                        .append(", ").append(structType).append("* null, i32 1\n");

                    String sizeReg = newAux("alloc_size");
                    sb.append("  ").append(sizeReg)
                        .append(" = ptrtoint ").append(structType)
                        .append("* ").append(gepReg).append(" to i64\n");

                    String i8Ptr = newAux("stack_i8");
                    sb.append("  ").append(i8Ptr)
                        .append(" = bitcast ").append(structType)
                        .append("* ").append(allocName).append(" to i8*\n");
                    sb.append("  call void @llvm.memset.p0i8.i64(i8* ")
                        .append(i8Ptr).append(", i8 0, i64 ").append(sizeReg)
                        .append(", i1 false)\n");

                    String vtableName = globalEmitter.getVtableName(className);
                    if (vtableName != null) {
                        String vtablePtr = newAux("vtableptr");
                        sb.append("  ").append(vtablePtr)
                            .append(" = bitcast %JNativeVTable* ").append(vtableName)
                            .append(" to i8*\n");

                        String objPtrCast = newAux("objptrcast");
                        sb.append("  ").append(objPtrCast).append(" = bitcast ").append(structType)
                            .append("* ").append(allocName).append(" to i8**\n");

                        sb.append("  store i8* ").append(vtablePtr).append(", i8** ")
                            .append(objPtrCast).append("\n");
                    }

                    // Register the entry-block alloca as this value's pointer source.
                    // Every read of inst.getResult() through getLlvmValue will emit a
                    // fresh bitcast from this alloca at the read site.
                    valuePointers.put(inst.getResult(),
                        new StackAlloc(allocName, structType));

                    // Remove the stale valueMapper entry created at the top of
                    // emitInstruction, so that a caller bypassing getLlvmValue cannot
                    // observe an SSA name that was never defined.
                    valueMapper.setValue(inst.getResult(), null);

                    // resultName was reserved at the top of emitInstruction but is
                    // deliberately not assigned here: the value is materialised lazily
                    // by getLlvmValue at every use.
                    break;
                }

                String gepReg = newAux("alloc_gep");
                sb.append("  ").append(gepReg)
                    .append(" = getelementptr ").append(structType)
                    .append(", ").append(structType).append("* null, i32 1\n");

                String sizeReg = newAux("alloc_size");
                sb.append("  ").append(sizeReg)
                    .append(" = ptrtoint ").append(structType)
                    .append("* ").append(gepReg).append(" to i64\n");

                String allocReg = newAux("alloc");
                sb.append("  ").append(allocReg)
                    .append(" = call i8* @calloc(i64 1, i64 ").append(sizeReg).append(")\n");

                sb.append("  ").append(resultName).append(" = bitcast i8* ").append(allocReg)
                    .append(" to ").append(structType).append("*\n");

                String vtableName = globalEmitter.getVtableName(className);
                if (vtableName != null) {
                    String vtablePtr = newAux("vtableptr");
                    sb.append("  ").append(vtablePtr)
                        .append(" = bitcast %JNativeVTable* ").append(vtableName)
                        .append(" to i8*\n");

                    String objPtrCast = newAux("objptrcast");
                    sb.append("  ").append(objPtrCast).append(" = bitcast ").append(structType)
                        .append("* ").append(resultName).append(" to i8**\n");

                    sb.append("  store i8* ").append(vtablePtr).append(", i8** ")
                        .append(objPtrCast).append("\n");
                }
                break;
            }

            case NEW_ARRAY: {
                if (inst.getOperands().size() < 2) break;
                Value sizeVal = inst.getOperands().get(0);
                Value elemTypeConst = inst.getOperands().get(1);
                if (!(elemTypeConst instanceof Constant)) break;
                String elemTypeName = ((Constant) elemTypeConst).getValue().toString();
                Type elemType = elemTypeFromConst(elemTypeName);
                int elemSize = getElementSizeOfType(elemType);

                String sizeRef = coerceSizeToInt(sb, sizeVal, getLlvmValue(sb, sizeVal));

                String totalSize = newAux("total_size");
                sb.append("  ").append(totalSize).append(" = mul i32 ")
                    .append(sizeRef).append(", ").append(elemSize).append("\n");

                String totalSize64 = newAux("total_size64");
                sb.append("  ").append(totalSize64).append(" = zext i32 ")
                    .append(totalSize).append(" to i64\n");

                String allocSize = newAux("alloc_size");
                sb.append("  ").append(allocSize).append(" = add i64 ")
                    .append(totalSize64).append(", 16\n");

                String allocReg = newAux("alloc");
                sb.append("  ").append(allocReg)
                    .append(" = call i8* @calloc(i64 1, i64 ")
                    .append(allocSize).append(")\n");

                // ----  klass @ 0 ----
                // A mirror of the array's own class, so that
                // Object.getClass() and instanceof can tell an array
                // apart from an ordinary object (whose first word is a
                // vtable). Offsets 0..15 must match
                // JAVA_ARR_KLASS_OFFSET / _LENGTH_ / _ELEM_SIZE_ and
                // JAVA_ARR_HDR in jnative_runtime.h.
                String arrClassName = arrayClassDescriptorFor(elemTypeName);
                if (arrClassName == null) arrClassName = "[Ljava/lang/Object;";
                String arrClassRef = globalEmitter.getRefClassGlobalName(arrClassName);

                String klassPtr = newAux("klass_ptr");
                sb.append("  ").append(klassPtr).append(" = bitcast i8* ").append(allocReg)
                    .append(" to %ReflectionClass**\n");
                sb.append("  store %ReflectionClass* ").append(arrClassRef)
                    .append(", %ReflectionClass** ").append(klassPtr).append("\n");

                // ----  length @ 8 ----
                String lenPtr = newAux("lenptr");
                sb.append("  ").append(lenPtr)
                    .append(" = getelementptr inbounds i8, i8* ").append(allocReg)
                    .append(", i64 8\n");
                String lenPtrI32 = newAux("lenptr_i32");
                sb.append("  ").append(lenPtrI32).append(" = bitcast i8* ").append(lenPtr)
                    .append(" to i32*\n");
                sb.append("  store i32 ").append(sizeRef).append(", i32* ")
                    .append(lenPtrI32).append("\n");

                // ----  elem_size @ 12 ----
                String esPtr = newAux("esptr");
                sb.append("  ").append(esPtr)
                    .append(" = getelementptr inbounds i8, i8* ").append(allocReg)
                    .append(", i64 12\n");
                String esPtrI32 = newAux("esptr_i32");
                sb.append("  ").append(esPtrI32).append(" = bitcast i8* ").append(esPtr)
                    .append(" to i32*\n");
                sb.append("  store i32 ").append(elemSize).append(", i32* ")
                    .append(esPtrI32).append("\n");

                // ----  payload @ 16 (implicit: calloc zeroed it) ----
                sb.append("  ").append(resultName).append(" = bitcast i8* ").append(allocReg)
                    .append(" to ")
                    .append(LlvmTypeMapper.toLlvmType(inst.getResult().getType())).append("\n");
                break;
            }

            case MULTI_NEW_ARRAY: {
                if (inst.getOperands().isEmpty()) break;
                Value descConst = inst.getOperands().getFirst();
                if (!(descConst instanceof Constant)) break;
                String desc = ((Constant) descConst).getValue().toString();

                List<Value> sizeValues = new ArrayList<>();
                for (int i = 1; i < inst.getOperands().size(); i++) {
                    sizeValues.add(inst.getOperands().get(i));
                }
                int dims = sizeValues.size();
                if (dims == 0) break;

                int elemSize = getBaseElementSize(desc);

                String sizesArray = newAux("sizes_array");
                String sizesSize = newAux("sizes_size");
                sb.append("  ").append(sizesSize).append(" = mul i32 ").append(dims).append(", 4\n");
                String sizesSize64 = newAux("sizes_size64");
                sb.append("  ").append(sizesSize64).append(" = zext i32 ").append(sizesSize).append(" to i64\n");
                sb.append("  ").append(sizesArray).append(" = call i8* @calloc(i64 1, i64 ").append(sizesSize64).append(")\n");
                String sizesI32 = newAux("sizes_i32");
                sb.append("  ").append(sizesI32).append(" = bitcast i8* ").append(sizesArray).append(" to i32*\n");
                for (int i = 0; i < dims; i++) {
                    String sizeRef = coerceSizeToInt(sb, sizeValues.get(i), getLlvmValue(sb, sizeValues.get(i)));
                    String ptr = newAux("sizes_ptr_" + i);
                    sb.append("  ").append(ptr).append(" = getelementptr i32, i32* ")
                        .append(sizesI32).append(", i32 ").append(i).append("\n");
                    sb.append("  store i32 ").append(sizeRef)
                        .append(", i32* ").append(ptr).append("\n");
                }

                String callRes = newAux("multiarr");
                int arrLen = LlvmRuntime.typeStringArrayLength(desc);
                sb.append("  ").append(callRes).append(" = call i8* @__jnative_new_multi_array(i8* getelementptr inbounds ([")
                    .append(arrLen).append(" x i8], [")
                    .append(arrLen).append(" x i8]* ")
                    .append(LlvmRuntime.typeStringGlobalName(desc)).append(", i32 0, i32 0), i32 ")
                    .append(dims).append(", i32* ").append(sizesI32).append(", i32 ")
                    .append(elemSize).append(")\n");
                sb.append("  call void @free(i8* ").append(sizesArray).append(")\n");
                if (resultName != null) {
                    sb.append("  ").append(resultName).append(" = bitcast i8* ").append(callRes)
                        .append(" to ").append(LlvmTypeMapper.toLlvmType(inst.getResult().getType())).append("\n");
                }
                break;
            }

            case FREE: {
                if (inst.getOperands().isEmpty()) {
                    // Instruction without an operand: nothing to free. This is
                    // a defensive case; valid IR never contains it.
                    break;
                }
                Value obj = inst.getOperands().getFirst();
                if (obj == null) {
                    break;
                }
                if (obj instanceof Constant c && c.getValue() == null) {
                    // free(NULL) in LLVM IR is not a no-op: it is a real
                    // function call with a null pointer. It does nothing
                    // semantically but pollutes the IR and masks lifetime bugs.
                    // Skip the emission entirely.
                    break;
                }
                String objRef = getLlvmValue(sb, obj);
                if ("null".equals(objRef)) {
                    // getLlvmValue() returned "null" — either because the value
                    // did not map (valueMapper.getValue() == null) or because
                    // the value type is a reference and the value itself is
                    // absent. In both cases there is no register, and
                    // free(null) is meaningless.
                    break;
                }
                // ------------------------------------------------------------------
                // Route the deallocation through __jnative_free rather than
                // through libc free().
                //
                // A reference-typed value that reaches this point is not
                // necessarily heap-allocated. It can be a Java String literal
                // emitted by LlvmGlobalEmitter.generateStringLiterals as a
                // static @jstr_<hex> global in the executable's .data
                // segment, or any other static constant that a class chose to
                // store into a field. Calling free() on such a pointer is
                // undefined behaviour: glibc detects it and aborts with
                // "free(): invalid pointer".
                //
                // The concrete failure the indirection closes:
                //
                //   __jnative_shutdown
                //       -> __destruct_java_lang_invoke_VarHandle$AccessMode
                //           -> frees the `methodName` field
                //
                // VarHandle$AccessMode.GET_AND_ADD_RELEASE is a genuine heap
                // enum constant, but its `methodName` field points at
                // @jstr_676574416e6441646452656c65617365 ("getAndAddRelease"),
                // a static String. free() on that pointer aborted the process
                // during exit, after main() had already printed its result.
                //
                // __jnative_free checks whether the pointer lies inside the
                // main executable's writable data segments and skips the free
                // for anything static; heap pointers pass through unchanged.
                // See the implementation in jnative_runtime.c for the full
                // reasoning.
                //
                // Every emitter of Opcode.FREE -- DestructorInserter's class
                // and array destructors, DestructorSimplifier's inlined
                // FREE, DestructorSimplifier's simplified trivial destructor
                // body -- reaches libc through this single site, so the
                // guard covers all of them at once.
                // ------------------------------------------------------------------
                sb.append("  call void @__jnative_free(i8* ").append(objRef).append(")\n");
                break;
            }

            case JSR: {
                String retLabel = "entry";
                if (!inst.getOperands().isEmpty()
                    && inst.getOperands().getFirst() instanceof Constant c
                    && c.getType() == Type.BLOCK
                    && c.getValue() instanceof BasicBlock rb) {
                    retLabel = llvmLabel(rb);
                }
                String fn = inst.getParent() != null && inst.getParent().getFunction() != null
                    ? LlvmRuntime.mangleFunction(inst.getParent().getFunction().getName())
                    : "";
                sb.append("  ").append(resultName)
                    .append(" = bitcast i8* blockaddress(@").append(fn)
                    .append(", %").append(retLabel).append(") to i8*\n");
                break;
            }

            case MONITOR_ENTER: {
                Value obj = inst.getOperands().getFirst();
                emitNullCheck(sb, obj, ranges, "monitor");
                String objRef = getPointerOperand(sb, obj);
                sb.append("  call void @__jnative_monitor_enter(i8* ")
                    .append(objRef).append(")\n");
                break;
            }

            case MONITOR_EXIT: {
                Value obj = inst.getOperands().getFirst();
                emitNullCheck(sb, obj, ranges, "monitor");
                String objRef = getPointerOperand(sb, obj);
                sb.append("  call void @__jnative_monitor_exit(i8* ")
                    .append(objRef).append(")\n");
                break;
            }

            case INSTANCEOF: {
                Value obj = inst.getOperands().getFirst();
                String typeName = extractTypeName(inst);
                String objRef = getPointerOperand(sb, obj);

                if (typeName != null && typeName.startsWith("[")) {
                    sb.append("  ").append(resultName).append(" = icmp ne i8* ")
                        .append(objRef).append(", null\n");
                    break;
                }

                String typeInfoName = globalEmitter.getTypeInfoName(typeName);

                if (typeInfoName == null) {
                    sb.append("  ; WARNING: no typeInfo for ").append(typeName).append("\n");
                    sb.append("  ").append(resultName).append(" = call i1 @__jnative_instanceof(i8* ")
                        .append(objRef).append(", i8** null)\n");
                } else {
                    sb.append("  ").append(resultName).append(" = call i1 @__jnative_instanceof(i8* ")
                        .append(objRef).append(", i8** ")
                        .append(typeInfoName).append(")\n");
                }
                break;
            }

            case CHECKCAST: {
                Value obj = inst.getOperands().getFirst();
                String typeName = extractTypeName(inst);
                String objRef = getPointerOperand(sb, obj);

                if (typeName != null && typeName.startsWith("[")) {
                    sb.append("  ").append(resultName).append(" = bitcast i8* ").append(objRef)
                        .append(" to ").append(LlvmTypeMapper.toLlvmType(inst.getResult().getType()))
                        .append("\n");
                    break;
                }

                String typeInfoName = globalEmitter.getTypeInfoName(typeName);

                if (typeInfoName == null) {
                    sb.append("  ").append(resultName).append(" = bitcast i8* ").append(objRef)
                        .append(" to ").append(LlvmTypeMapper.toLlvmType(inst.getResult().getType()))
                        .append("\n");
                    break;
                }

                String isNull  = newAux("cc_isnull");
                String isInst  = newAux("cc_instof");
                String okComb  = newAux("cc_ok");
                String failLbl = newLabel("check_fail");
                String okLbl   = newLabel("check_ok");

                sb.append("  ").append(isNull).append(" = icmp eq i8* ").append(objRef).append(", null\n");
                sb.append("  ").append(isInst).append(" = call i1 @__jnative_instanceof(i8* ")
                    .append(objRef).append(", i8** ").append(typeInfoName).append(")\n");
                sb.append("  ").append(okComb).append(" = or i1 ").append(isNull)
                    .append(", ").append(isInst).append("\n");
                sb.append("  br i1 ").append(okComb)
                    .append(", label %").append(okLbl)
                    .append(", label %").append(failLbl).append("\n");
                sb.append(failLbl).append(":\n");
                emitThrowHelper(sb, "@__jnative_throw_class_cast_exception", ranges);
                sb.append(okLbl).append(":\n");
                sb.append("  ").append(resultName).append(" = bitcast i8* ").append(objRef)
                    .append(" to ").append(LlvmTypeMapper.toLlvmType(inst.getResult().getType()))
                    .append("\n");
                break;
            }

            case ARRAYLENGTH: {
                Value arr = inst.getOperands().getFirst();
                String arrRef = getLlvmValue(sb, arr);
                emitNullCheck(sb, arr, ranges, "<array>");
                // length lives at JAVA_ARR_LENGTH_OFFSET (8) in the array header.
                String lenPtr = newAux("lenptr");
                sb.append("  ").append(lenPtr)
                    .append(" = getelementptr inbounds i8, i8* ").append(arrRef)
                    .append(", i64 8\n");
                String lenPtrI32 = newAux("lenptr_i32");
                sb.append("  ").append(lenPtrI32).append(" = bitcast i8* ").append(lenPtr)
                    .append(" to i32*\n");
                sb.append("  ").append(resultName).append(" = load i32, i32* ")
                    .append(lenPtrI32).append("\n");
                break;
            }

            case ALOAD: {
                if (inst.getOperands().size() < 2) break;
                Value arr = inst.getOperands().get(0);
                Value idx = inst.getOperands().get(1);

                emitNullCheck(sb, arr, ranges, "<array>");

                String arrPtr = asPointer(sb, arr);
                String idxRef = getLlvmValue(sb, idx);
                String idxI32 = castValueToType(sb, idxRef, idx.getType(), Type.INT);
                emitBoundsCheck(sb, arrPtr, idxI32, ranges);

                Type declaredElemType = inst.getResult().getType();
                Type elemType = declaredElemType;
                if (arr.getType().isArray()) {
                    Type arrElem = arr.getType().getElementType();
                    if (!arrElem.isUnknown()) {
                        elemType = arrElem;
                    }
                }

                if (!elemType.equals(declaredElemType)
                    && inst.getResult() instanceof Temporary resultTmp) {
                    resultTmp.setType(elemType);
                }

                int elemSize = getElementSizeOfType(elemType);
                String offset = newAux("offset");
                sb.append("  ").append(offset).append(" = mul i32 ")
                    .append(idxI32).append(", ").append(elemSize).append("\n");
                String offset64 = newAux("offset64");
                sb.append("  ").append(offset64).append(" = zext i32 ").append(offset).append(" to i64\n");
                String basePtr = newAux("baseptr");
                sb.append("  ").append(basePtr).append(" = getelementptr i8, i8* ").append(arrPtr)
                    .append(", i64 16\n");
                String elemPtr = newAux("elemptr");
                sb.append("  ").append(elemPtr).append(" = getelementptr i8, i8* ").append(basePtr)
                    .append(", i64 ").append(offset64).append("\n");
                String ptrCast = newAux("ptrcast");
                String llvmType = LlvmTypeMapper.toLlvmType(elemType);
                sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(elemPtr)
                    .append(" to ").append(llvmType).append("*\n");
                sb.append("  ").append(resultName).append(" = load ").append(llvmType)
                    .append(", ").append(llvmType).append("* ").append(ptrCast).append("\n");
                break;
            }

            case ASTORE: {
                if (inst.getOperands().size() >= 3) {
                    Value arr = inst.getOperands().get(0);
                    Value idx = inst.getOperands().get(1);
                    Value val = inst.getOperands().get(2);

                    emitNullCheck(sb, arr, ranges, "<array>");

                    String arrPtr = asPointer(sb, arr);
                    String idxRef = getLlvmValue(sb, idx);
                    String valRef = getLlvmValue(sb, val);
                    String idxI32 = castValueToType(sb, idxRef, idx.getType(), Type.INT);
                    emitBoundsCheck(sb, arrPtr, idxI32, ranges);
                    Type elemType = val.getType();
                    if (arr.getType().isArray()) {
                        Type arrElem = arr.getType().getElementType();
                        if (!arrElem.isUnknown()) {
                            elemType = arrElem;
                        }
                    }
                    int elemSize = getElementSizeOfType(elemType);
                    String offset = newAux("offset");
                    sb.append("  ").append(offset).append(" = mul i32 ")
                        .append(idxI32).append(", ").append(elemSize).append("\n");
                    String offset64 = newAux("offset64");
                    sb.append("  ").append(offset64).append(" = zext i32 ").append(offset).append(" to i64\n");
                    String basePtr = newAux("baseptr");
                    sb.append("  ").append(basePtr).append(" = getelementptr i8, i8* ").append(arrPtr)
                        .append(", i64 16\n");
                    String elemPtr = newAux("elemptr");
                    sb.append("  ").append(elemPtr).append(" = getelementptr i8, i8* ").append(basePtr)
                        .append(", i64 ").append(offset64).append("\n");
                    String ptrCast = newAux("ptrcast");
                    String llvmType = LlvmTypeMapper.toLlvmType(elemType);
                    String valConverted = castValueToType(sb, valRef, val.getType(), elemType);
                    sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(elemPtr)
                        .append(" to ").append(llvmType).append("*\n");
                    sb.append("  store ").append(llvmType).append(" ").append(valConverted)
                        .append(", ").append(llvmType).append("* ").append(ptrCast).append("\n");
                }
                break;
            }

            case PHI: {
                BasicBlock parent = getBasicBlock(inst);

                List<BasicBlock> preds = parent.getPredecessors();

                if (preds.size() != inst.getOperands().size()) {
                    throw new IllegalStateException(
                        "PHI operand count (" + inst.getOperands().size()
                            + ") does not match predecessor count (" + preds.size()
                            + ") in block " + parent.getLabel()
                            + "; phi = " + inst);
                }

                Type phiType = inst.getResult().getType();
                String phiLlvm = LlvmTypeMapper.toLlvmType(phiType);

                sb.append("  ").append(resultName).append(" = phi ").append(phiLlvm).append(" ");
                for (int i = 0; i < inst.getOperands().size(); i++) {
                    if (i > 0) sb.append(", ");
                    Value phiOp = inst.getOperands().get(i);

                    String opLlvm = LlvmTypeMapper.toLlvmType(phiOp.getType());
                    if (!opLlvm.equals(phiLlvm)) {
                        throw new IllegalStateException(
                            "PHI operand type mismatch in block " + parent.getLabel()
                                + ": phi type " + phiLlvm
                                + ", operand " + i + " type " + opLlvm
                                + " (value = " + phiOp + ")");
                    }

                    String valRef;
                    Map<Integer, String> preloads = phiPreloads.get(inst);
                    if (preloads != null && preloads.containsKey(i)) {
                        valRef = preloads.get(i);
                    } else if (valuePointers.containsKey(phiOp)) {
                        // Fallback for a PHI in a block whose predecessor did not run the
                        // phiPreloads pass (the entry block, in practice). getLlvmValue
                        // re-emits the bitcast from the entry-block alloca here, in the
                        // PHI's own block. That is weaker than a proper edge preload but
                        // is still well-formed: the alloca dominates this block, and the
                        // bitcast is only ever used within the PHI's block.
                        valRef = getLlvmValue(sb, phiOp);
                    } else if (valueSlots.containsKey(phiOp)) {
                        String mapped = valueMapper.getValue(phiOp);
                        valRef = mapped != null ? mapped : getDefaultValue(phiOp.getType());
                    } else {
                        valRef = getLlvmValue(sb, phiOp);
                    }
                    sb.append("[ ").append(valRef).append(", %")
                        .append(llvmLabel(preds.get(i))).append(" ]");
                }
                sb.append("\n");
                break;
            }

            case INVOKEDYNAMIC: {
                InvokeDynamicInfo dynInfo = (InvokeDynamicInfo) inst.getInvokedynamicData();
                ResolvedCall call = dynInfo.resolvedCall();
                if (call == null) {
                    sb.append("  ; unresolved invokedynamic\n");
                    if (inst.getResult() != null) {
                        valueMapper.setValue(inst.getResult(), "null");
                    }
                    break;
                }
                switch (call.getType()) {
                    case LAMBDA:
                        emitLambdaCreation(sb, inst, call, resultName);
                        break;
                    case CONCAT:
                        emitConcatCall(sb, inst, resultName);
                        break;
                    default:
                        sb.append("  ; unsupported invokedynamic type: ").append(call.getType()).append("\n");
                        if (inst.getResult() != null) {
                            valueMapper.setValue(inst.getResult(), "null");
                        }
                        break;
                }
                break;
            }

            case SELECT: {
                if (inst.getOperands().size() < 3 || inst.getResult() == null) {
                    sb.append("  ; malformed SELECT\n");
                    break;
                }
                Value cond     = inst.getOperands().get(0);
                Value trueVal  = inst.getOperands().get(1);
                Value falseVal = inst.getOperands().get(2);
                Type  resType  = inst.getResult().getType();

                String condRef  = getLlvmValue(sb, cond);
                String trueRef  = getLlvmValue(sb, trueVal);
                String falseRef = getLlvmValue(sb, falseVal);

                String trueConverted  = castValueToType(sb, trueRef,
                        trueVal.getType(),  resType);
                String falseConverted = castValueToType(sb, falseRef,
                        falseVal.getType(), resType);

                String llvmRes = LlvmTypeMapper.toLlvmType(resType);
                sb.append("  ").append(resultName).append(" = select i1 ")
                  .append(condRef)
                  .append(", ").append(llvmRes).append(" ").append(trueConverted)
                  .append(", ").append(llvmRes).append(" ").append(falseConverted)
                  .append("\n");
                break;
            }

            default:
                sb.append("  ; unsupported opcode: ").append(op).append("\n");
        }

        // Материализация значений, пересекающих try-границу.
        //
        // Значения, определённые в try-body и использованные в catch-region,
        // обязаны жить в стек-слоте из entry-блока: их SSA-регистр определён в
        // блоке, который в эмитированном LLVM CFG не доминирует точку
        // использования (bodyLabel и catchLabel — сиблинги).
        //
        // Слот выделяется в entry-блоке (через pendingSlotDeclarations), запись
        // выполняется сразу после определения, чтение — в точке каждого
        // использования (см. getLlvmValue, ветка valueSlots).
        if (inst.getResult() != null
                && tryCrossingValues.contains(inst.getResult())
                && !valueSlots.containsKey(inst.getResult())) {

            // Инструкции, которые не материализуют свой результат (NOP, ветки
            // dead code и т.п.), не должны получать store в слот — иначе в IR
            // попадёт store из несуществующего SSA-регистра. Проверка через
            // точный паттерн `  %tmp_N = ` (с ведущими двумя пробелами), которой
            // пользуется весь эмиттер при определении значений.
            resultName = valueMapper.getValue(inst.getResult());
            String needle = "  " + resultName + " = ";
            boolean resultActuallyDefined = resultName != null
                && sb.toString().contains(needle);

            if (resultActuallyDefined) {
                Type resType = inst.getResult().getType();
                String resLlvm = LlvmTypeMapper.toLlvmType(resType);
                String slot = newAux("try_slot");
                pendingSlotDeclarations.add(
                    "  " + slot + " = alloca " + resLlvm + ", align 8\n");
                sb.append("  store ").append(resLlvm).append(" ").append(resultName)
                  .append(", ").append(resLlvm).append("* ").append(slot).append("\n");
                valueSlots.put(inst.getResult(), slot);
            }
        }

        return sb.toString();
    }

    private static BasicBlock getBasicBlock(Instruction inst) {
        BasicBlock parent = inst.getParent();

        // The SSA transformer is contractually required to set parent on every
        // PHI it inserts (see SSATransformer.insertPhiFunctions). A null parent
        // here means a phi was injected into a block's instruction list without
        // going through BasicBlock.addInstruction, and the emitter has no way
        // to know which predecessor labels the incoming values belong to.
        // Failing loudly is strictly better than emitting "%unknown", which
        // produces broken LLVM IR that clang will reject far from the source
        // of the bug.
        if (parent == null) {
            throw new IllegalStateException(
                "PHI instruction has no parent block: " + inst
                    + " (the SSA transformer must set parent on every phi)");
        }
        return parent;
    }

    // ----- Helper methods for polymorphic calls -----

    /**
     * Generates a call to a polymorphic native function with argument packing into void**.
     */
    private void emitPolymorphicCall(StringBuilder sb, List<Value> args,
                                     NativeMethodInfo best, Type retType,
                                     List<Type> paramTypes, List<TryCatchRange> ranges,
                                     String resultName) {
        String arrayAlloc = newAux("poly_args");
        int argCount = args.size();
        sb.append("  ").append(arrayAlloc).append(" = alloca i8*, i32 ").append(argCount).append("\n");

        for (int i = 0; i < argCount; i++) {
            Value arg = args.get(i);
            String val = getLlvmValue(sb, arg);
            String ptr;

            if (arg.getType().isPrimitive()) {
                String alloc = newAux("arg_alloc");
                String ty = LlvmTypeMapper.toLlvmType(arg.getType());
                sb.append("  ").append(alloc).append(" = alloca ").append(ty).append("\n");
                sb.append("  store ").append(ty).append(" ").append(val).append(", ").append(ty).append("* ").append(alloc).append("\n");
                String cast = newAux("arg_cast");
                sb.append("  ").append(cast).append(" = bitcast ").append(ty).append("* ").append(alloc).append(" to i8*\n");
                ptr = cast;
            } else {
                String cast = newAux("arg_cast");
                sb.append("  ").append(cast).append(" = bitcast ").append(LlvmTypeMapper.toLlvmType(arg.getType()))
                    .append(" ").append(val).append(" to i8*\n");
                ptr = cast;
            }

            String slot = newAux("slot");
            sb.append("  ").append(slot).append(" = getelementptr i8*, i8** ").append(arrayAlloc).append(", i32 ").append(i).append("\n");
            sb.append("  store i8* ").append(ptr).append(", i8** ").append(slot).append("\n");
        }

        String funcName = best.getFullFunctionName();
        ensurePolymorphicFunctionDeclared(best);

        String retLlvm = LlvmTypeMapper.toLlvmType(retType);
        if (!retType.isVoid()) {
            sb.append("  ").append(resultName).append(" = call ").append(retLlvm)
                .append(" @").append(funcName).append("(i8** ").append(arrayAlloc).append(")\n");
        } else {
            sb.append("  call void @").append(funcName).append("(i8** ").append(arrayAlloc).append(")\n");
        }
    }

    private Function ensurePolymorphicFunctionDeclared(NativeMethodInfo info) {
        String funcName = info.getFullFunctionName();
        Function func = module.getFunction(funcName);
        if (func != null) return func;

        String desc = info.getDescriptor();
        Type retType = Type.fromDescriptor(desc.substring(desc.lastIndexOf(')') + 1));
        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);

        Function newFunc = new Function(funcName, retType);
        for (int i = 0; i < paramTypes.size(); i++) {
            newFunc.addParameter(new Parameter(paramTypes.get(i), i));
        }
        module.addFunction(newFunc);
        return newFunc;
    }

    /**
     * Builds the JVM method descriptor for a polymorphic call site.
     *
     * <p>The resulting string is compared byte-for-byte against
     * {@link NativeMethodInfo#getDescriptor()} to decide whether the
     * resolver found a concrete typed native target (direct typed call)
     * or a generic {@code void**} wrapper (packed {@code i8**} call).
     * It must therefore be produced by exactly the same encoding rules
     * that {@link
     * io.github.kubyk01.application.service.codegen.llvm.nativepolymorphicfunctionresolver.PolymorphicResolver}
     * uses when it builds its own {@code expectedDescriptor} — in
     * particular, an array parameter must be encoded as
     * {@code "[" + elementDescriptor}, not as a bare {@code '['}.</p>
     *
     * <p>Historically this method used
     * {@link #typeToDescriptorChar(Type)} for every parameter and
     * return type, which returns a single {@code '['} for any array
     * type. For a call site with parameters {@code (byte[], int)} and
     * return {@code short} that produced the malformed descriptor
     * {@code "([I)S"}, while the resolver produced the correct
     * {@code "([BI)S"}. The two strings never compared equal, the
     * emitter always took the {@code emitPolymorphicCall} branch, and
     * the concrete C function
     * {@code int16_t ..._get___BI_S(int8_t*, int32_t)} was invoked with
     * the ABI of a {@code void**} dispatcher — one {@code i8**} in
     * slot 1, garbage in slot 2 — which crashed inside
     * {@code barray_check} with a bogus
     * {@code ArrayIndexOutOfBoundsException}.</p>
     */
    private String buildDescriptor(Type returnType, List<Type> paramTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (Type pt : paramTypes) {
            sb.append(typeToFullDescriptor(pt));
        }
        sb.append(')');
        sb.append(typeToFullDescriptor(returnType));
        return sb.toString();
    }

    /**
     * Full JVM type descriptor for a single {@link Type}.
     *
     * <p>This is the emitter-side counterpart of
     * {@code PolymorphicResolver.typeToFullDescriptor}. The two must
     * produce identical strings for the same {@link Type}, otherwise
     * the exact-match check that distinguishes a concrete typed native
     * from a {@code void**} wrapper silently misclassifies every call
     * site whose parameters include an array.</p>
     *
     * <p>The encoding rules are:</p>
     * <ul>
     *   <li>primitives map to their single-letter code
     *       ({@code Z B S C I J F D}, plus {@code V} for {@code void});</li>
     *   <li>an array of {@code T} maps to {@code "[" + fullDescriptor(T)},
     *       recursing so that {@code int[][]} becomes {@code "[[I"} and
     *       {@code String[]} becomes {@code "[Ljava/lang/String;"};</li>
     *   <li>a reference type {@code C} maps to
     *       {@code "L" + C.getClassName() + ";"};</li>
     *   <li>the {@code null}, {@code block} and {@code unknown} sentinels
     *       map to {@code "Ljava/lang/Object;"} — they never appear as a
     *       declared parameter or return type of a real native, but the
     *       mapping keeps the encoder total.</li>
     * </ul>
     */
    private String typeToFullDescriptor(Type type) {
        if (type == null) {
            return "Ljava/lang/Object;";
        }
        if (type.isPrimitive()) {
            return String.valueOf(typeToDescriptorChar(type));
        }
        if (type.isArray()) {
            Type elem = type.getElementType();
            if (elem == null) {
                return "[Ljava/lang/Object;";
            }
            return "[" + typeToFullDescriptor(elem);
        }
        if (type.isReference()) {
            String cls = type.getClassName();
            if (cls == null || cls.isEmpty()) {
                return "Ljava/lang/Object;";
            }
            /*
             * Defensive: a reference type whose name is itself an array
             * descriptor. This can arise when a value's declared type was
             * built from a runtime array descriptor rather than from a
             * class file. Emitting "L[B;" for it would produce a
             * descriptor that no JVM — and no native — would ever match.
             * Returning the descriptor verbatim makes the encoder total
             * and keeps the exact-match check meaningful for such values.
             */
            if (cls.charAt(0) == '[') {
                return cls;
            }
            return "L" + cls + ";";
        }
        if (type.isNull()) {
            return "Ljava/lang/Object;";
        }
        return "Ljava/lang/Object;";
    }

    /**
     * Single-character descriptor for a primitive type. Kept for the
     * primitive branch of {@link #typeToFullDescriptor}; arrays and
     * references must go through the full encoder above, because a
     * bare {@code '['} is not a valid parameter descriptor and cannot
     * be used in the exact-match comparison.
     */
    private char typeToDescriptorChar(Type type) {
        if (type == Type.VOID) return 'V';
        if (type == Type.BOOLEAN) return 'Z';
        if (type == Type.BYTE) return 'B';
        if (type == Type.SHORT) return 'S';
        if (type == Type.CHAR) return 'C';
        if (type == Type.INT) return 'I';
        if (type == Type.LONG) return 'J';
        if (type == Type.FLOAT) return 'F';
        if (type == Type.DOUBLE) return 'D';
        if (type.isReference()) return 'L';
        if (type.isArray()) return '[';
        return 'L';
    }

    /**
     * Returns {@code true} when {@code info} describes one of the
     * legacy {@code void**} polymorphic dispatchers — a native whose
     * sole parameter is a {@code void*} / {@code void**} pointer and
     * whose argument list is therefore carried opaquely in a packed
     * {@code i8**} array rather than in the C ABI's typed registers.
     *
     * <p>This is a byte-for-byte copy of
     * {@code PolymorphicResolver.isVoidPtrArgsSignature}. Keeping a
     * copy here is deliberate: the emitter must be able to decide
     * independently of the resolver whether the target it received is
     * a concrete typed native or a generic wrapper, because the two
     * cases require different call-emission code.</p>
     */
    private boolean isVoidPtrArgsSignature(NativeMethodInfo info) {
        List<ParserC.CParameter> params = info.getParameters();
        if (params.size() != 1) return false;
        String type = params.getFirst().getType().trim();
        return type.contains("void") && type.contains("*");
    }


    private void emitLambdaCreation(StringBuilder sb, Instruction inst, ResolvedCall call, String resultName) {
        String lambdaId = call.getLambdaStructName();
        List<Value> captured = inst.getOperands();
        List<Type> capturedTypes = call.getCapturedTypes();

        // Register (idempotent) the struct type. It was already registered during
        // the pre-pass; this call merely returns the name.
        String structName = globalEmitter.registerLambdaStruct(lambdaId, capturedTypes);

        String lambdaClassName = "__Lambda_" + lambdaId;
        String vtableName = globalEmitter.getVtableName(lambdaClassName);
        if (vtableName == null) {
            // Should not happen — the lambda was registered in generateLambdaAdaptors().
            throw new IllegalStateException(
                "Lambda vtable not registered for lambdaId=" + lambdaId);
        }

        // Size of struct
        String gepReg = newAux("lambda_gep");
        sb.append("  ").append(gepReg).append(" = getelementptr ")
            .append(structName).append(", ").append(structName)
            .append("* null, i32 1\n");

        String sizeReg = newAux("lambda_size");
        sb.append("  ").append(sizeReg).append(" = ptrtoint ")
            .append(structName).append("* ").append(gepReg).append(" to i64\n");

        String alloc = newAux("lambda_alloc");
        sb.append("  ").append(alloc).append(" = call i8* @calloc(i64 1, i64 ")
            .append(sizeReg).append(")\n");

        String objPtr = Objects.requireNonNullElseGet(resultName, () -> newAux("lambda_obj"));
        sb.append("  ").append(objPtr).append(" = bitcast i8* ").append(alloc)
            .append(" to ").append(structName).append("*\n");

        // Store vtable pointer at offset 0
        String vtablePtr = newAux("lambda_vtable_ptr");
        sb.append("  ").append(vtablePtr)
            .append(" = bitcast %JNativeVTable* ").append(vtableName)
            .append(" to i8*\n");

        String objVtableSlot = newAux("obj_vtable_slot");
        sb.append("  ").append(objVtableSlot).append(" = bitcast ").append(structName)
            .append("* ").append(objPtr).append(" to i8**\n");
        sb.append("  store i8* ").append(vtablePtr).append(", i8** ")
            .append(objVtableSlot).append("\n");

        // Captured fields
        int offset = 8;
        for (Value cap : captured) {
            String fieldLlvm = LlvmTypeMapper.toLlvmType(cap.getType());
            String fieldPtr = newAux("cap_ptr");
            sb.append("  ").append(fieldPtr).append(" = getelementptr i8, i8* ").append(alloc)
                .append(", i64 ").append(offset).append("\n");
            String castPtr = newAux("cap_cast");
            sb.append("  ").append(castPtr).append(" = bitcast i8* ").append(fieldPtr)
                .append(" to ").append(fieldLlvm).append("*\n");
            String valRef = getLlvmValue(sb, cap);
            String valConverted = castValueToType(sb, valRef, cap.getType(), cap.getType());
            sb.append("  store ").append(fieldLlvm).append(" ").append(valConverted)
                .append(", ").append(fieldLlvm).append("* ").append(castPtr).append("\n");
            offset += getElementSizeOfType(cap.getType());
        }

        if (inst.getResult() != null && resultName != null) {
            valueMapper.setValue(inst.getResult(), objPtr);
        }
    }

    private void emitConcatCall(StringBuilder sb, Instruction inst, String resultName) {
        ResolvedCall call = ((InvokeDynamicInfo) inst.getInvokedynamicData()).resolvedCall();
        String recipe = call.getConcatFormat();
        List<Value> dynamicArgs = inst.getOperands();

        List<String> parts = new ArrayList<>();
        int dynIdx = 0;

        if (recipe == null) {
            for (Value arg : dynamicArgs) {
                parts.add(convertArgToString(sb, arg));
            }
        } else {
            Object[] constants = parseConstants(call.getPackedConstants());
            int constIdx = 0;
            StringBuilder literal = new StringBuilder();
            for (int i = 0; i < recipe.length(); i++) {
                char c = recipe.charAt(i);
                if (c == '\u0001') {
                    flushLiteral(sb, literal, parts);
                    if (dynIdx >= dynamicArgs.size()) break;
                    parts.add(convertArgToString(sb, dynamicArgs.get(dynIdx++)));
                } else if (c == '\u0002') {
                    flushLiteral(sb, literal, parts);
                    if (constIdx < constants.length) {
                        String s = String.valueOf(constants[constIdx++]);
                        if (s != null) parts.add(emitLiteralString(sb, s));
                    }
                } else {
                    literal.append(c);
                }
            }
            flushLiteral(sb, literal, parts);
        }

        if (parts.isEmpty()) {
            parts.add(emitLiteralString(sb, ""));
        }

        // A fresh SSA name for the concat call result. Using a hard-coded
        // `%concat_result` here produces a duplicate definition as soon as
        // the same function contains two concatenation call sites — the
        // second one then collides with the first and LLVM rejects the
        // module with "multiple definition of local value named
        // 'concat_result'". Every other temporary in this emitter is
        // generated through newAux(); the concat result must go through
        // the same counter.
        String concatResult = newAux("concat_result");
        sb.append("  ").append(concatResult)
            .append(" = call i8* @__jnative_concat_strings(i32 ")
            .append(parts.size());
        for (String p : parts) {
            sb.append(", i8* ").append(p);
        }
        sb.append(")\n");

        if (inst.getResult() != null && resultName != null) {
            String casted = newAux("concat_cast");
            sb.append("  ").append(casted).append(" = bitcast i8* ").append(concatResult)
                .append(" to ")
                .append(LlvmTypeMapper.toLlvmType(inst.getResult().getType())).append("\n");
            valueMapper.setValue(inst.getResult(), casted);
        }
    }

    private void flushLiteral(StringBuilder sb, StringBuilder literal, List<String> parts) {
        if (!literal.isEmpty()) {
            parts.add(emitLiteralString(sb, literal.toString()));
            literal.setLength(0);
        }
    }

    private String emitLiteralString(StringBuilder sb, String s) {
        return "bitcast (%struct.java_lang_String* @"
            + LlvmRuntime.stringObjectGlobalName(s) + " to i8*)";
    }

    private String convertArgToString(StringBuilder sb, Value arg) {
        Type t = arg.getType();
        String ref = getLlvmValue(sb, arg);

        if (t.isReference()) {
            String cls = t.getClassName();
            if ("java/lang/String".equals(cls)) {
                return ref;
            }
            String res = newAux("to_str");
            sb.append("  ").append(res)
                .append(" = call i8* @__jnative_value_to_string_object(i8* ")
                .append(ref).append(")\n");
            return res;
        }

        if (t.isArray() || t.isNull() || t.isBlock() || t.isUnknown()) {
            String res = newAux("to_str");
            sb.append("  ").append(res)
                .append(" = call i8* @__jnative_value_to_string_object(i8* ")
                .append(ref).append(")\n");
            return res;
        }

        if (t == Type.INT || t == Type.BYTE || t == Type.SHORT
            || t == Type.CHAR || t == Type.BOOLEAN) {
            String fn;
            if (t == Type.BYTE)         fn = "__jnative_value_to_string_byte";
            else if (t == Type.SHORT)   fn = "__jnative_value_to_string_short";
            else if (t == Type.CHAR)    fn = "__jnative_value_to_string_char";
            else if (t == Type.BOOLEAN) fn = "__jnative_value_to_string_boolean";
            else                        fn = "__jnative_value_to_string_int";
            String casted = castValueToType(sb, ref, t, Type.INT);
            String res = newAux("to_str");
            sb.append("  ").append(res).append(" = call i8* @").append(fn)
                .append("(i32 ").append(casted).append(")\n");
            return res;
        }

        if (t == Type.LONG) {
            String res = newAux("to_str");
            sb.append("  ").append(res).append(" = call i8* @__jnative_value_to_string_long(i64 ")
                .append(ref).append(")\n");
            return res;
        }

        if (t == Type.FLOAT) {
            String res = newAux("to_str");
            sb.append("  ").append(res).append(" = call i8* @__jnative_value_to_string_float(float ")
                .append(ref).append(")\n");
            return res;
        }

        if (t == Type.DOUBLE) {
            String res = newAux("to_str");
            sb.append("  ").append(res).append(" = call i8* @__jnative_value_to_string_double(double ")
                .append(ref).append(")\n");
            return res;
        }

        return emitLiteralString(sb, "");
    }

    private Object[] parseConstants(String packed) {
        if (packed == null || packed.isEmpty()) return new Object[0];
        return packed.split("\u0000", -1);
    }

    /**
     * Emits a call. When {@code ranges} is empty the callback writes straight
     * into the caller's buffer. Otherwise the call is wrapped in a try-guard
     * and:
     * <ul>
     *   <li>a slot is allocated in the current (pre-guard) block to receive
     *       the result, so uses of the result outside the guard survive the
     *       try lowering's extra CFG edges;</li>
     *   <li>the callback is invoked with the <em>guarded-body</em> buffer,
     *       so any {@code slot_load} for a slot-based argument is emitted
     *       inside the body — where the guard itself dominates it.</li>
     * </ul>
     * The callback is responsible for emitting the call itself and, if
     * {@code resultReg != null}, assigning the call result to that register.
     */
    private void emitCall(StringBuilder sb, Type retType, Value resultValue, String resultName,
                          List<TryCatchRange> ranges, CallEmitter emitter) {
        // Region-level hoisting means call sites never need their own guard:
        // the enclosing region was armed at block entry.  `ranges` is
        // therefore always empty here for guarded code; the non-empty branch
        // is retained only for the legacy fallback that the analyzer might
        // still construct for an unusual shape (it emits the same
        // per-instruction guard it always did).
        if (ranges.isEmpty()) {
            emitter.emit(sb, retType.isVoid() ? null : resultName);
            return;
        }
        String slot = null;
        if (!retType.isVoid() && resultValue != null) {
            slot = newAux("call_slot");
            pendingSlotDeclarations.add("  " + slot + " = alloca "
                + LlvmTypeMapper.toLlvmType(retType) + ", align 8\n");
        }
        final String slotRef = slot;
        // Reuse the legacy per-instruction guard path — only reachable for
        // fallback call sites outside any try region.
        emitTryGuard(sb, ranges, inner -> {
            if (!retType.isVoid()) {
                String bodyResult = newAux("body_result");
                emitter.emit(inner, bodyResult);
                if (slotRef != null) {
                    inner.append("  store ")
                         .append(LlvmTypeMapper.toLlvmType(retType)).append(" ")
                         .append(bodyResult).append(", ")
                         .append(LlvmTypeMapper.toLlvmType(retType)).append("* ")
                         .append(slotRef).append("\n");
                }
            } else {
                emitter.emit(inner, null);
            }
        }, false);
        if (slotRef != null) valueSlots.put(resultValue, slotRef);
    }

    private void emitDivByZeroCheck(StringBuilder sb, Value divisor, List<TryCatchRange> ranges) {
        String ty = LlvmTypeMapper.toLlvmType(divisor.getType());

        String divisorRef = getLlvmValue(sb, divisor);

        String chk      = newAux("div_chk");
        String throwBlk = newLabel("throw_divzero");
        String cont     = newLabel("div_ok");

        sb.append("  ").append(chk).append(" = icmp ne ").append(ty).append(" ")
            .append(divisorRef).append(", 0\n");
        sb.append("  br i1 ").append(chk)
            .append(", label %").append(cont)
            .append(", label %").append(throwBlk).append("\n");
        sb.append(throwBlk).append(":\n");
        emitThrowHelper(sb, "@__jnative_throw_arithmetic_exception", ranges);
        sb.append(cont).append(":\n");
    }

    private void emitTryGuard(StringBuilder sb,
                              List<TryCatchRange> ranges,
                              Consumer<StringBuilder> body,
                              boolean neverReturnsNormally) {
        int k = ranges.size();
        String guardBlk = newLabel("guard");
        String bodyBlk  = newLabel("guarded_body");
        String catchBlk = newLabel("guarded_catch");
        String contBlk  = neverReturnsNormally ? null : newLabel("guarded_cont");

        sb.append("  br label %").append(guardBlk).append("\n");

        sb.append(guardBlk).append(":\n");

        for (int i = k - 1; i >= 0; i--) {
            TryCatchRange r = ranges.get(i);

            String bufPtr = newAux("sj_jb");
            sb.append("  ").append(bufPtr).append(" = bitcast [").append(JMP_BUF_SIZE)
                .append(" x i8]* %jmp_buf_").append(rangeOrdinals.get(r))
                .append(" to i8*\n");

            String sjRet = newAux("sj_ret");
            sb.append("  ").append(sjRet)
                .append(" = call i32 @_setjmp(i8* ").append(bufPtr).append(")\n");

            String isZero = newAux("sj_zero");
            sb.append("  ").append(isZero).append(" = icmp eq i32 ").append(sjRet)
                .append(", 0\n");

            String armContBlk = newLabel("arm_cont");
            sb.append("  br i1 ").append(isZero)
                .append(", label %").append(armContBlk)
                .append(", label %").append(catchBlk).append("\n");

            sb.append(armContBlk).append(":\n");
            // The buffer is now armed. Copy it into the runtime context chain.
            sb.append("  call void @__jnative_push_catch(i8* ").append(bufPtr)
                .append(", i8** ").append(catchTypeInfoOperand(r)).append(")\n");
        }

        sb.append("  br label %").append(bodyBlk).append("\n");

        sb.append(bodyBlk).append(":\n");
        body.accept(sb);
        if (neverReturnsNormally) {
            sb.append("  unreachable\n");
        } else {
            sb.repeat("  call void @__jnative_pop_catch()\n", k);
            sb.append("  br label %").append(contBlk).append("\n");
        }

        sb.append(catchBlk).append(":\n");
        String exc = newAux("caught_exc");
        sb.append("  ").append(exc).append(" = call i8* @__jnative_get_exception_object()\n");

        String[] missLabels = new String[k];
        for (int i = 0; i < k; i++) {
            missLabels[i] = newLabel("cmiss");
        }

        // Dispatch by type, innermost-first. On a match at index i we have
        // already popped i contexts along the miss chain; the hit block
        // pops the remaining (k - i) so that the total is always k.
        for (int i = 0; i < k; i++) {
            TryCatchRange r = ranges.get(i);
            if (i > 0) {
                sb.append(missLabels[i - 1]).append(":\n");
                sb.append("  call void @__jnative_pop_catch()\n");
            }
            String hitBlk = newLabel("chit");
            String ti = r.type != null ? globalEmitter.getTypeInfoName(r.type) : null;
            if (ti != null) {
                String matches = newAux("cm");
                sb.append("  ").append(matches).append(" = call i1 @__jnative_catch_matches(i8* ")
                    .append(exc).append(", i8** ").append(ti).append(")\n");
                sb.append("  br i1 ").append(matches)
                    .append(", label %").append(hitBlk)
                    .append(", label %").append(missLabels[i]).append("\n");
            } else {
                sb.append("  br label %").append(hitBlk).append("\n");
            }
            sb.append(hitBlk).append(":\n");
            sb.repeat("  call void @__jnative_pop_catch()\n", k - i);
            BasicBlock handler = handlerBlockByRange.get(r);
            sb.append("  br label %").append(llvmLabel(handler)).append("\n");
        }

        sb.append(missLabels[k - 1]).append(":\n");
        sb.append("  call void @__jnative_pop_catch()\n");
        sb.append("  call void @__jnative_throw_exception(i8* ").append(exc).append(")\n");
        sb.append("  unreachable\n");

        if (contBlk != null) {
            sb.append(contBlk).append(":\n");
        }
    }

    private String catchTypeInfoOperand(TryCatchRange r) {
        return r.type != null ? globalEmitter.getTypeInfoName(r.type) : null;
    }

    /**
     * Emits the cascaded arm: for each try range in {@code ranges} (outermost
     * first), a block that does {@code setjmp(buf); if (!ok) goto catch;
     * push_catch(buf, type_info);} and branches to the next arm block or to
     * {@code bodyLabel}.
     *
     * <p>The last setjmp emitted is the one for the innermost range (index 0
     * in the list), so a longjmp from the body always lands on the innermost
     * range's setjmp and dispatch checks it first — matching the reference
     * semantics of the previous per-instruction guards.</p>
     */
    private void emitRegionArm(StringBuilder sb,
                               List<TryCatchRange> ranges,
                               String catchLabel,
                               String bodyLabel) {
        int k = ranges.size();
        String[] armLabels = new String[k];
        for (int i = 0; i < k; i++) armLabels[i] = newLabel("arm");

        // Entry goes straight to the outermost arm (index k-1).
        sb.append("  br label %").append(armLabels[k - 1]).append("\n");

        for (int i = k - 1; i >= 0; i--) {
            TryCatchRange r = ranges.get(i);
            int ord = rangeOrdinals.get(r);
            String arm  = armLabels[i];
            String cont = newLabel("arm_cont");

            sb.append(arm).append(":\n");
            String bufPtr = newAux("sj_jb");
            sb.append("  ").append(bufPtr).append(" = bitcast [")
              .append(JMP_BUF_SIZE).append(" x i8]* %jmp_buf_").append(ord)
              .append(" to i8*\n");
            String sjRet = newAux("sj_ret");
            sb.append("  ").append(sjRet).append(" = call i32 @_setjmp(i8* ")
              .append(bufPtr).append(")\n");
            String isZero = newAux("sj_zero");
            sb.append("  ").append(isZero).append(" = icmp eq i32 ").append(sjRet)
              .append(", 0\n");
            sb.append("  br i1 ").append(isZero)
              .append(", label %").append(cont)
              .append(", label %").append(catchLabel).append("\n");

            sb.append(cont).append(":\n");
            sb.append("  call void @__jnative_push_catch(i8* ").append(bufPtr)
              .append(", i8** ").append(catchTypeInfoOperand(r)).append(")\n");
            if (i > 0) sb.append("  br label %").append(armLabels[i - 1]).append("\n");
            else       sb.append("  br label %").append(bodyLabel).append("\n");
        }
    }

    /**
     * Emits a single shared catch-dispatch block per try-range set.  Matches
     * the previous per-guard dispatch exactly: on a hit at range {@code i},
     * pops {@code k - i} frames and jumps to the handler; on miss, pops one
     * frame and rethrows to the next outer context.
     */
    private void emitCatchDispatchBody(StringBuilder sb,
                                       String catchLabel,
                                       List<TryCatchRange> ranges) {
        sb.append(catchLabel).append(":\n");
        String exc = newAux("caught_exc");
        sb.append("  ").append(exc)
          .append(" = call i8* @__jnative_get_exception_object()\n");

        int k = ranges.size();
        String[] missLabels = new String[k];
        for (int i = 0; i < k; i++) missLabels[i] = newLabel("cmiss");

        for (int i = 0; i < k; i++) {
            TryCatchRange r = ranges.get(i);
            if (i > 0) {
                sb.append(missLabels[i - 1]).append(":\n");
                sb.append("  call void @__jnative_pop_catch()\n");
            }
            String hitBlk = newLabel("chit");
            String ti = r.type != null ? globalEmitter.getTypeInfoName(r.type) : null;
            if (ti != null) {
                String matches = newAux("cm");
                sb.append("  ").append(matches)
                  .append(" = call i1 @__jnative_catch_matches(i8* ").append(exc)
                  .append(", i8** ").append(ti).append(")\n");
                sb.append("  br i1 ").append(matches)
                  .append(", label %").append(hitBlk)
                  .append(", label %").append(missLabels[i]).append("\n");
            } else {
                sb.append("  br label %").append(hitBlk).append("\n");
            }
            sb.append(hitBlk).append(":\n");
            sb.repeat("  call void @__jnative_pop_catch()\n", k - i);
            BasicBlock handler = handlerBlockByRange.get(r);
            sb.append("  br label %").append(llvmLabel(handler)).append("\n");
        }
        sb.append(missLabels[k - 1]).append(":\n");
        sb.append("  call void @__jnative_pop_catch()\n");
        sb.append("  call void @__jnative_throw_exception(i8* ").append(exc).append(")\n");
        sb.append("  unreachable\n");
    }

    private String emitTerminator(Terminator term, List<TryCatchRange> ranges,
                              Map<BasicBlock, String> targetRedirects) {
        StringBuilder sb = new StringBuilder();
        java.util.function.Function<BasicBlock, String> targetLabel =
            t -> targetRedirects != null ? targetRedirects.getOrDefault(t, llvmLabel(t)) : llvmLabel(t);

        if (term instanceof ReturnTerminator rt) {
            Value retVal = rt.getValue();
            Type funcRetType = term.getBlock().getFunction().getReturnType();
            if (retVal != null) {
                String valRef = getLlvmValue(sb, retVal);
                valRef = castValueToType(sb, valRef, retVal.getType(), funcRetType);
                sb.append("  ret ").append(LlvmTypeMapper.toLlvmType(funcRetType))
                    .append(" ").append(valRef).append("\n");
            } else {
                if (funcRetType.isVoid()) {
                    sb.append("  ret void\n");
                } else {
                    String zero = getZeroValue(funcRetType);
                    sb.append("  ret ").append(LlvmTypeMapper.toLlvmType(funcRetType))
                        .append(" ").append(zero).append("\n");
                }
            }
        } else if (term instanceof BranchTerminator bt) {
            sb.append("  br label %").append(targetLabel.apply(bt.getTarget())).append("\n");
        } else if (term instanceof CondBranchTerminator cbt) {
            String cond = getLlvmValue(sb, cbt.getCondition());
            sb.append("  br i1 ").append(cond)
              .append(", label %").append(targetLabel.apply(cbt.getTrueTarget()))
              .append(", label %").append(targetLabel.apply(cbt.getFalseTarget()))
              .append("\n");
        } else if (term instanceof ThrowTerminator tt) {
            Value exc = tt.getException();
            String excRef = exc == null ? null : getPointerOperand(sb, exc);
            String funcNameRef = stringRef(currentFunctionName);
            if (ranges.isEmpty()) {
                if (excRef != null) {
                    sb.append("  call void @__jnative_throw_exception_ctx(i8* ")
                        .append(excRef).append(", i8* ").append(funcNameRef).append(")\n");
                } else {
                    sb.append("  call void @__jnative_throw_null_pointer_exception_ctx(i8* ")
                        .append(funcNameRef).append(")\n");
                }
                sb.append("  unreachable\n");
            } else {
                emitTryGuard(sb, ranges, inner -> {
                    if (excRef != null) {
                        inner.append("  call void @__jnative_throw_exception_ctx(i8* ")
                            .append(excRef).append(", i8* ").append(funcNameRef).append(")\n");
                    } else {
                        inner.append("  call void @__jnative_throw_null_pointer_exception_ctx(i8* ")
                            .append(funcNameRef).append(")\n");
                    }
                }, true);
            }
        } else if (term instanceof LookupSwitchTerminator || term instanceof TableSwitchTerminator) {
            Value key;
            BasicBlock defaultTarget;
            if (term instanceof LookupSwitchTerminator lst) {
                key = lst.getKey();
                defaultTarget = lst.getDefaultTarget();
            } else {
                TableSwitchTerminator tst = (TableSwitchTerminator) term;
                key = tst.getKey();
                defaultTarget = tst.getDefaultTarget();
            }
            String keyRef = getLlvmValue(sb, key);
            String keyI32 = castValueToType(sb, keyRef, key.getType(), Type.INT);
            sb.append("  switch i32 ").append(keyI32).append(", label %")
                .append(targetLabel.apply(defaultTarget)).append(" [\n");
            if (term instanceof LookupSwitchTerminator lst) {
                for (int i = 0; i < lst.getKeys().length; i++) {
                    sb.append("    i32 ").append(lst.getKeys()[i]).append(", label %")
                        .append(targetLabel.apply(lst.getTargetsArray()[i])).append("\n");
                }
            } else {
                TableSwitchTerminator tst = (TableSwitchTerminator) term;
                for (int i = 0; i < tst.getTargetsArray().length; i++) {
                    sb.append("    i32 ").append(tst.getMin() + i).append(", label %")
                        .append(targetLabel.apply(tst.getTargetsArray()[i])).append("\n");
                }
            }
            sb.append("  ]\n");
        } else if (term instanceof IndirectBranchTerminator ibt) {
            List<BasicBlock> targets = ibt.getPossibleTargets();
            if (targets.isEmpty()) {
                sb.append("  unreachable\n");
            } else {
                String blockAddr = getLlvmValue(sb, ibt.getTargetBlock());
                sb.append("  indirectbr i8* ").append(blockAddr).append(", [");
                for (int i = 0; i < targets.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("label %").append(targetLabel.apply(targets.get(i)));
                }
                sb.append("]\n");
            }
        } else {
            sb.append("  ; unknown terminator\n");
        }
        return sb.toString();
    }

    // ----- Remaining helper methods (unchanged) -----
    private String mapArithOp(Opcode op, Type type) {
        boolean isFloat = type == Type.FLOAT || type == Type.DOUBLE;
        return switch (op) {
            case ADD -> isFloat ? "fadd" : "add";
            case SUB -> isFloat ? "fsub" : "sub";
            case MUL -> isFloat ? "fmul" : "mul";
            case DIV -> isFloat ? "fdiv" : "sdiv";
            case REM -> isFloat ? "frem" : "srem";
            case AND -> "and";
            case OR  -> "or";
            case XOR -> "xor";
            case SHL -> "shl";
            case SHR -> "ashr";
            case USHR -> "lshr";
            default -> "add";
        };
    }

    private String mapCmpOp(Opcode op, boolean isFloat) {
        if (isFloat) {
            return switch (op) {
                case EQ -> "oeq";
                case NE -> "one";
                case LT -> "olt";
                case LE -> "ole";
                case GT -> "ogt";
                case GE -> "oge";
                default -> "oeq";
            };
        } else {
            return switch (op) {
                case EQ -> "eq";
                case NE -> "ne";
                case LT -> "slt";
                case LE -> "sle";
                case GT -> "sgt";
                case GE -> "sge";
                default -> "eq";
            };
        }
    }

    private String getCastOp(Type src, Type dest) {
        if (src == dest) return null;
        if (!src.isPrimitive() && src.equals(dest)) return null;

        if ((src.isReference() || src.isArray() || src.isNull()) &&
            (dest.isReference() || dest.isArray() || dest.isNull())) {
            return "bitcast";
        }

        if (isIntegerType(src) && (dest.isReference() || dest.isArray() || dest.isNull() || dest.isBlock())) {
            return "inttoptr";
        }

        if ((src.isReference() || src.isArray() || src.isNull() || src.isBlock()) && isIntegerType(dest)) {
            return "ptrtoint";
        }

        if (src.isPrimitive() && dest.isPrimitive()) {
            boolean srcInt = isIntegerType(src);
            boolean destInt = isIntegerType(dest);

            if (srcInt && destInt) {
                int srcBits = getPrimitiveSize(src);
                int destBits = getPrimitiveSize(dest);
                if (srcBits == destBits) return null;
                if (srcBits < destBits) {
                    if (src == Type.CHAR || src == Type.BOOLEAN) return "zext";
                    return "sext";
                } else {
                    return "trunc";
                }
            }

            if (srcInt && (dest == Type.FLOAT || dest == Type.DOUBLE)) {
                return "sitofp";
            }

            if ((src == Type.FLOAT || src == Type.DOUBLE) && destInt) {
                return "fptosi";
            }

            if (src == Type.FLOAT && dest == Type.DOUBLE) return "fpext";
            if (src == Type.DOUBLE && dest == Type.FLOAT) return "fptrunc";
        }

        return "bitcast";
    }

    private boolean isIntegerType(Type type) {
        return type == Type.BOOLEAN || type == Type.BYTE || type == Type.SHORT ||
            type == Type.CHAR || type == Type.INT || type == Type.LONG;
    }

    private int getPrimitiveSize(Type type) {
        if (type == Type.BOOLEAN) return 1;
        if (type == Type.BYTE)    return 8;
        if (type == Type.SHORT || type == Type.CHAR) return 16;
        if (type == Type.INT)     return 32;
        if (type == Type.LONG)    return 64;
        if (type == Type.FLOAT)   return 32;
        if (type == Type.DOUBLE)  return 64;
        return 0;
    }

    private String getDefaultValue(Type type) {
        if (type == null) return "null";
        if (type.isReference() || type.isArray() || type.isNull()
            || type.isBlock() || type.isUnknown()) {
            return "null";
        }
        if (type == Type.BOOLEAN) return "false";
        if (type == Type.BYTE || type == Type.SHORT || type == Type.CHAR
            || type == Type.INT || type == Type.LONG) return "0";
        if (type == Type.FLOAT) return "0.0";
        if (type == Type.DOUBLE) return "0.0";
        if (type.isVoid()) return "void";
        return "null";
    }

    private String getLlvmValue(StringBuilder sb, Value v) {
        if (v == null) return "null";
        if (v instanceof Constant c) {
            return constantToLlvmLiteral(c);
        }

        // ------------------------------------------------------------------
        // Stack-allocated object: re-emit a bitcast from the entry-block
        // alloca at the use site.
        //
        // This branch must be checked before valueSlots and valueMapper:
        // the NEW site never materialises an SSA value for a stack-allocated
        // object (see the case in emitInstruction), so a fall-through to
        // either of the two other sources would return an undefined name
        // or a stale SSA register whose defining block does not dominate
        // the current one.
        //
        // The alloca lives in the entry block and therefore dominates the
        // current position unconditionally. See the valuePointers javadoc
        // for the full reasoning.
        // ------------------------------------------------------------------
        StackAlloc ptrSlot = valuePointers.get(v);
        if (ptrSlot != null) {
            String destTy = LlvmTypeMapper.toLlvmType(v.getType());
            String tmp = newAux("ptr_cast");
            sb.append("  ").append(tmp).append(" = bitcast ")
                .append(ptrSlot.structType()).append("* ")
                .append(ptrSlot.allocaName())
                .append(" to ").append(destTy).append("\n");
            return tmp;
        }

        // Values produced by a call inside a try-guard are not defined as plain
        // SSA registers — they live in a stack slot (see emitCall). Emitting a
        // fresh load here, in the block where the value is used, guarantees that
        // the load's defining block coincides with the block of the use (or is
        // dominated by the slot's alloca in entry). LLVM's structural dominance
        // check therefore never sees a register defined in guarded_cont_N being
        // used in a later guarded_body_M that is reachable from the shared
        // recovery block LL2023938592 without passing through guarded_cont_N.
        String slot = valueSlots.get(v);
        if (slot != null) {
            String ty = LlvmTypeMapper.toLlvmType(v.getType());
            String tmp = newAux("slot_load");
            sb.append("  ").append(tmp).append(" = load ")
                .append(ty).append(", ").append(ty)
                .append("* ").append(slot).append("\n");
            return tmp;
        }

        String name = valueMapper.getValue(v);
        if (name == null) {
            return getDefaultValue(v.getType());
        }
        return name;
    }

    /**
     * Coerces an operand used in an integer context (the size of a Java array)
     * to an i32 LLVM value.
     *
     * <p>Two things can go wrong here:</p>
     * <ul>
     *   <li>the operand is a null constant or an unmapped placeholder that
     *       {@link #getLlvmValue} renders as the string {@code "null"};</li>
     *   <li>the operand has a non-int declared type (typically {@code UNKNOWN}
     *       after SSA reconstruction on a local that was actually int-valued).</li>
     * </ul>
     *
     * <p>In both cases {@code mul i32 null, N} / {@code store i32 null, …}
     * would be produced and LLVM rejects the module. Falling back to the
     * literal {@code 0} keeps the generated IR structurally valid. The result
     * is semantically safe for the array-allocation path because a zero-sized
     * array never fails to allocate; any downstream index-out-of-bounds is a
     * separate, honest runtime error rather than a compile failure in LLVM.</p>
     */
    private String coerceSizeToInt(StringBuilder sb, Value v, String valRef) {
        if ("null".equals(valRef)) {
            return "0";
        }
        if (v == null) {
            return valRef;
        }
        Type t = v.getType();
        if (t == null || t == Type.INT) {
            return valRef;
        }
        if (t.isUnknown()) {
            // UNKNOWN renders as i8*; there is no register whose contents
            // we could safely reinterpret as an integer. Treat as zero.
            return "0";
        }
        return castValueToType(sb, valRef, t, Type.INT);
    }

    private String constantToLlvmLiteral(Constant c) {
        Object val = c.getValue();
        if (val == null) {
            Type type = c.getType();
            if (type.isReference() || type.isArray() || type.isNull()
                || type.isBlock() || type.isUnknown()) {
                return "null";
            }
            if (type == Type.BOOLEAN) return "false";
            if (type == Type.BYTE || type == Type.SHORT || type == Type.CHAR
                || type == Type.INT || type == Type.LONG) return "0";
            if (type == Type.FLOAT || type == Type.DOUBLE) return "0.0";
            return "null";
        }
        Type type = c.getType();

        if (type == Type.INT) {
            return Integer.toString(((Number) val).intValue());
        }
        if (type == Type.LONG) {
            return Long.toString(((Number) val).longValue());
        }
        if (type == Type.BYTE) {
            return Integer.toString(((Number) val).byteValue());
        }
        if (type == Type.SHORT) {
            return Integer.toString(((Number) val).shortValue());
        }
        // char: JVM carries a char constant either as a Character or as a
        // numeric code point (Integer). Both must render as an i16 literal.
        if (type == Type.CHAR) {
            int cp;
            if (val instanceof Character ch) {
                cp = ch;
            } else if (val instanceof Number n) {
                cp = n.intValue();
            } else {
                return "0";
            }
            return Integer.toString(cp);
        }

        if (type == Type.FLOAT || type == Type.DOUBLE) {
            double d = ((Number) val).doubleValue();
            if (Double.isNaN(d)) {
                return "0x7FF8000000000000";
            } else if (Double.isInfinite(d)) {
                return d > 0 ? "0x7FF0000000000000" : "0xFFF0000000000000";
            } else {
                String s = Double.toString(d).replace('E', 'e');
                if (!s.contains(".") && !s.contains("e") && !s.contains("E")) {
                    s = s + ".0";
                }
                return s;
            }
        }

        if (type == Type.BOOLEAN) {
            return ((Boolean) val).toString();
        }

        if (type.isReference() && "java/lang/Class".equals(type.getClassName())) {
            if (val instanceof String className && !className.isEmpty()) {
                return "bitcast (%ReflectionClass* @refclass_"
                    + LlvmTypeMapper.sanitizeIdentifier(className)
                    + " to i8*)";
            }
            return "null";
        }

        if (type.isReference() && "java/lang/String".equals(type.getClassName())
            && val instanceof String s) {
            return "bitcast (%struct.java_lang_String* @"
                + LlvmRuntime.stringObjectGlobalName(s) + " to i8*)";
        }

        // Marker type: value is a class internal name; emits a bitcast of that
        // class's %JNativeVTable global to i8*, so the result can be compared
        // against the receiver's vtable slot (also i8*) or used as a raw pointer.
        //
        // A value that starts with '[' is an array descriptor instead: the word
        // at offset 0 of an array is the array's class mirror (%ReflectionClass*),
        // not a %JNativeVTable*, so the comparison has to be made against the
        // @refclass_* global of that descriptor.
        if (type.isReference()
                && "__llvm_vtable_ptr__".equals(type.getClassName())) {
            String className = String.valueOf(val);
            if (className.startsWith("[")) {
                return "bitcast (%ReflectionClass* @refclass_"
                     + LlvmTypeMapper.sanitizeIdentifier(className) + " to i8*)";
            }
            return "bitcast (%JNativeVTable* @vtable_"
                 + LlvmTypeMapper.sanitizeIdentifier(className) + " to i8*)";
        }

        if (type.isReference() && val instanceof String s) {
            int len = LlvmRuntime.typeStringArrayLength(s);
            return "getelementptr inbounds ([" + len + " x i8], [" + len + " x i8]* "
                + LlvmRuntime.typeStringGlobalName(s) + ", i32 0, i32 0)";
        }

        return "null";
    }

    private String castValueToType(StringBuilder sb, String value, Type fromType, Type toType) {
        // The literal "null" only makes sense in a pointer context. If the declared
        // source type is a primitive (which can happen due to imprecise SSA type
        // inference of a local variable), we must not fall into the inttoptr branch:
        // `inttoptr i16 null` is invalid LLVM. Resolve null relative to the target type.
        if ("null".equals(value)) {
            if (toType.isReference() || toType.isArray() || toType.isNull() || toType.isBlock()) {
                return "null";
            }
            if (toType == Type.BOOLEAN) return "false";
            if (isIntegerType(toType)) return "0";
            if (toType == Type.FLOAT || toType == Type.DOUBLE) return "0.0";
            return "null";
        }

        boolean fromIsPtr = fromType.isReference() || fromType.isArray() || fromType.isNull() || fromType.isBlock();
        String fromLlvm = LlvmTypeMapper.toLlvmType(fromType);
        if (!fromIsPtr && fromLlvm.endsWith("*")) {
            fromIsPtr = true;
        }

        if ("0".equals(value) && fromIsPtr) {
            value = "null";
        }

        if ("0".equals(value) && (toType.isReference() || toType.isArray() || toType.isNull() || toType.isBlock())) {
            return "null";
        }
        if ("null".equals(value) && isIntegerType(toType)) {
            return "0";
        }

        if (value.matches("-?\\d+\\.0") && (toType == Type.INT || toType == Type.LONG || toType == Type.BYTE ||
            toType == Type.SHORT || toType == Type.CHAR || toType == Type.BOOLEAN)) {
            return value.substring(0, value.indexOf('.'));
        }

        String toLlvm = LlvmTypeMapper.toLlvmType(toType);
        if (fromLlvm.equals(toLlvm)) {
            return value;
        }

        boolean toPtr = toLlvm.endsWith("*");
        boolean fromInt = fromLlvm.matches("i\\d+");
        boolean toInt = toLlvm.matches("i\\d+");
        boolean fromFloat = fromType == Type.FLOAT || fromType == Type.DOUBLE;
        boolean toFloat = toType == Type.FLOAT || toType == Type.DOUBLE;

        if (fromFloat && toPtr) {
            String intCast = newAux("float_to_i64");
            String fptosiOp = "fptosi";
            sb.append("  ").append(intCast).append(" = ").append(fptosiOp)
                .append(" ").append(fromLlvm).append(" ").append(value)
                .append(" to i64\n");
            String ptrCast = newAux("i64_to_ptr");
            sb.append("  ").append(ptrCast).append(" = inttoptr i64 ").append(intCast)
                .append(" to ").append(toLlvm).append("\n");
            return ptrCast;
        }

        if (fromIsPtr && toFloat) {
            String intCast = newAux("ptr_to_i64");
            sb.append("  ").append(intCast).append(" = ptrtoint ").append(fromLlvm)
                .append(" ").append(value).append(" to i64\n");
            String floatCast = newAux("i64_to_float");
            String sitofpOp = "sitofp";
            sb.append("  ").append(floatCast).append(" = ").append(sitofpOp)
                .append(" i64 ").append(intCast)
                .append(" to ").append(toLlvm).append("\n");
            return floatCast;
        }

        String castOp;

        if (fromIsPtr && toInt) {
            castOp = "ptrtoint";
        } else if (fromInt && toPtr) {
            castOp = "inttoptr";
        } else if (fromIsPtr && toPtr) {
            castOp = "bitcast";
        } else if (fromInt && toInt) {
            castOp = getCastOp(fromType, toType);
            if (castOp == null) {
                int fromBits = Integer.parseInt(fromLlvm.substring(1));
                int toBits = Integer.parseInt(toLlvm.substring(1));
                if (fromBits < toBits) {
                    castOp = "sext";
                } else if (fromBits > toBits) {
                    castOp = "trunc";
                } else {
                    castOp = "bitcast";
                }
            }
        } else if (fromFloat && toFloat) {
            if (fromLlvm.equals("float") && toLlvm.equals("double")) {
                castOp = "fpext";
            } else if (fromLlvm.equals("double") && toLlvm.equals("float")) {
                castOp = "fptrunc";
            } else {
                castOp = "bitcast";
            }
        } else if (fromInt && toFloat) {
            castOp = "sitofp";
        } else if (fromFloat && toInt) {
            castOp = "fptosi";
        } else {
            castOp = "bitcast";
        }

        if (castOp.equals("bitcast")) {
            if (fromIsPtr && toInt) {
                castOp = "ptrtoint";
            } else if (fromInt && toPtr) {
                castOp = "inttoptr";
            }
        }

        String castName = newAux("cast");
        sb.append("  ").append(castName).append(" = ").append(castOp)
            .append(" ").append(fromLlvm)
            .append(" ").append(value)
            .append(" to ").append(toLlvm).append("\n");
        return castName;
    }

    private void ensureFunctionDeclared(String owner, String methodName, String descriptor) {
        String mangled = LlvmRuntime.mangleMethod(owner, methodName, descriptor);
        if (module.getFunction(mangled) != null) {
            return;
        }

        Type retType = TypeResolver.descToReturnType(descriptor);
        List<Type> paramTypes = TypeResolver.descToParamTypes(descriptor);

        boolean isStatic = false;
        {
            String[] foundOwner = new String[1];
            MethodNode target = resolver.findMethodInHierarchy(
                owner, methodName, descriptor, foundOwner);
            if (target != null) {
                isStatic = target.isStatic();
            }
        }

        List<Type> allParams = new ArrayList<>();
        if (!isStatic) {
            allParams.add(Type.reference(owner));
        }
        allParams.addAll(paramTypes);

        Function func = new Function(mangled, retType);
        for (int i = 0; i < allParams.size(); i++) {
            func.addParameter(new Parameter(allParams.get(i), i));
        }
        module.addFunction(func);
    }

    private String asPointer(StringBuilder sb, Value v) {
        String valRef = getLlvmValue(sb, v);
        Type type = v.getType();
        if (type.isReference() || type.isArray() || type.isNull() || type.isBlock()) {
            String ptrTy = LlvmTypeMapper.toLlvmType(type);
            if (!ptrTy.equals("i8*")) {
                String castName = newAux("ptrcast");
                sb.append("  ").append(castName).append(" = bitcast ").append(ptrTy)
                    .append(" ").append(valRef).append(" to i8*\n");
                return castName;
            }
            return valRef;
        } else if (isIntegerType(type)) {
            String intVal = valRef;
            if (type == Type.INT || type == Type.SHORT || type == Type.BYTE || type == Type.CHAR || type == Type.BOOLEAN) {
                String ext = newAux("sext");
                sb.append("  ").append(ext).append(" = sext ").append(LlvmTypeMapper.toLlvmType(type))
                    .append(" ").append(valRef).append(" to i64\n");
                intVal = ext;
            }  // already i64
            // fallback

            String ptrCast = newAux("inttoptr");
            sb.append("  ").append(ptrCast).append(" = inttoptr i64 ").append(intVal).append(" to i8*\n");
            return ptrCast;
        } else {
            String ptrCast = newAux("ptrcast");
            sb.append("  ").append(ptrCast).append(" = bitcast ").append(LlvmTypeMapper.toLlvmType(type))
                .append(" ").append(valRef).append(" to i8*\n");
            return ptrCast;
        }
    }

    private void emitBoundsCheck(StringBuilder sb, String arrPtr, String idxI32, List<TryCatchRange> ranges) {
        // length lives at JAVA_ARR_LENGTH_OFFSET (8) in the array header.
        String lenPtr = newAux("lenptr");
        String len = newAux("len");
        sb.append("  ").append(lenPtr)
            .append(" = getelementptr inbounds i8, i8* ").append(arrPtr)
            .append(", i64 8\n");
        String lenPtrI32 = newAux("lenptr_i32");
        sb.append("  ").append(lenPtrI32).append(" = bitcast i8* ").append(lenPtr)
            .append(" to i32*\n");
        sb.append("  ").append(len).append(" = load i32, i32* ")
            .append(lenPtrI32).append("\n");
        String chk1 = newAux("bnd_chk1");
        String chk2 = newAux("bnd_chk2");
        String ok = newAux("bnd_ok");
        String throwBlk = newLabel("throw_aioobe");
        String cont = newLabel("bnd_ok");
        sb.append("  ").append(chk1).append(" = icmp sge i32 ").append(idxI32).append(", 0\n");
        sb.append("  ").append(chk2).append(" = icmp slt i32 ").append(idxI32).append(", ").append(len).append("\n");
        sb.append("  ").append(ok).append(" = and i1 ").append(chk1).append(", ").append(chk2).append("\n");
        sb.append("  br i1 ").append(ok)
            .append(", label %").append(cont)
            .append(", label %").append(throwBlk).append("\n");
        sb.append(throwBlk).append(":\n");
        emitThrowHelper(sb, "@__jnative_throw_array_index_out_of_bounds", ranges);
        sb.append(cont).append(":\n");
    }

    private boolean isCallableSymbolDefined(String symbol) {
        if (symbol == null || symbol.isEmpty()) return false;

        // Java methods that carry a JNative C implementation, and the
        // aliases that the override machinery installs, are always
        // callable: their bodies come from the compiled C sources that
        // ship with the runtime, and the linker will resolve them.
        if (symbol.startsWith("__jnative_override_")
            || symbol.startsWith("__jnative_fn_")) {
            return true;
        }

        // LLVM intrinsics are lowered by the backend; there is no
        // declaration to look up and nothing for the linker to resolve.
        if (symbol.startsWith("llvm.")) return true;

        // Runtime helpers. Every symbol declared by
        // LlvmRuntime.getDeclarations() has a matching definition in
        // jnative_runtime.c (or a sibling per-class native file) and is
        // therefore guaranteed to resolve at link time. Membership in
        // the derived set is the proof. See
        // DECLARED_RUNTIME_HELPERS for the concrete failure this check
        // exists to close.
        if (DECLARED_RUNTIME_HELPERS.contains(symbol)) return true;

        // C library entry points. Kept explicit rather than relying on
        // the helper set because these are also called from C code that
        // does not go through the prelude filter (the declarations in
        // LlvmRuntime.getDeclarations() overlap with this set but are
        // not identical to it — `printf` and `atexit`, for example,
        // are declared there, but the switch is also reached for
        // call sites that the emitter's helper-detection did not
        // route through the prelude).
        return switch (symbol) {
            case "malloc", "free", "printf", "abort", "atexit",
                 "memcpy", "memmove", "memset", "_setjmp", "longjmp" ->
                true;
            default -> {
                Function f = module.getFunction(symbol);
                // A JNI native is a declaration on purpose: its body lives
                // in a platform shared object and is resolved by the
                // linker, so it counts as "defined" even without an entry
                // block.
                if (f != null && f.isJniNative()) {
                    yield true;
                }
                // A bare `declare` (no entry block) will fail at link time
                // unless something else provides the body. Only count the
                // symbol as defined when the module really contains a
                // definition for it.
                yield f != null && f.getEntryBlock() != null;
            }
        };
    }

    /**
     * Resolves the {@link Function} the translator registered for a Java
     * {@code native} method: the {@code __jnative_} override when the
     * declaring class ships a JNative C file, or the JNI symbol
     * ({@code Java_…}) when it does not. Returns {@code null} when
     * neither has been registered.
     */
    private Function lookupNativeFunction(String owner, String methodName,
                                          String descriptor) {
        String jnative = "__jnative_"
            + LlvmRuntime.mangleMethod(owner, methodName, descriptor);
        Function f = module.getFunction(jnative);
        if (f != null) {
            return f;
        }
        String jni = LlvmRuntime.toJniSymbolName(owner, methodName, descriptor,
            countOverloads(owner, methodName));
        return module.getFunction(jni);
    }

    /**
     * Number of methods named {@code methodName} declared by {@code owner}.
     * Mirrors the helper in {@code BytecodeToIr} and decides whether the
     * JNI symbol needs the {@code __<descriptor>} overload suffix.
     */
    private int countOverloads(String owner, String methodName) {
        io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode cn =
            resolver.getClassNode(owner);
        if (cn == null) {
            return 1;
        }
        int n = 0;
        for (MethodNode mn : cn.getMethods()) {
            if (mn.getName().equals(methodName)) {
                n++;
            }
        }
        return Math.max(n, 1);
    }

    private boolean areAllInstanceFieldsFinal(String className) {
        ClassNode cls = resolver.getClassNode(className);
        if (cls == null || cls.isExternal()) return false;

        ClassNode cur = cls;
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (cur != null && !cur.isExternal() && seen.add(cur.getName())) {
            for (FieldNode f : cur.getFields()) {
                if ((f.getAccess() & org.objectweb.asm.Opcodes.ACC_STATIC) != 0) continue;
                if ((f.getAccess() & org.objectweb.asm.Opcodes.ACC_FINAL) == 0) return false;
            }
            String sup = cur.getSuperName();
            if (sup == null || sup.equals(cur.getName()) || "java/lang/Object".equals(sup)) break;
            cur = resolver.getClassNode(sup);
        }
        return true;
    }

    private String emitBaseToI8Pointer(StringBuilder sb, Value base) {
        Type t = base.getType();
        String baseRef  = getLlvmValue(sb, base);
        String baseLlvm = LlvmTypeMapper.toLlvmType(t);

        if ("i8*".equals(baseLlvm)) {
            return baseRef;
        }

        if (t.isReference() || t.isArray() || t.isNull() || t.isBlock()
            || baseLlvm.endsWith("*")) {
            String cast = newAux("base_i8");
            sb.append("  ").append(cast).append(" = bitcast ")
                .append(baseLlvm).append(" ").append(baseRef)
                .append(" to i8*\n");
            return cast;
        }

        if (t == Type.FLOAT) {
            String i32Reg = newAux("base_i32");
            sb.append("  ").append(i32Reg).append(" = bitcast float ")
                .append(baseRef).append(" to i32\n");
            String i64Reg = newAux("base_i64");
            sb.append("  ").append(i64Reg).append(" = zext i32 ")
                .append(i32Reg).append(" to i64\n");
            String ptrReg = newAux("base_i8");
            sb.append("  ").append(ptrReg).append(" = inttoptr i64 ")
                .append(i64Reg).append(" to i8*\n");
            return ptrReg;
        }

        if (t == Type.DOUBLE) {
            String i64Reg = newAux("base_i64");
            sb.append("  ").append(i64Reg).append(" = bitcast double ")
                .append(baseRef).append(" to i64\n");
            String ptrReg = newAux("base_i8");
            sb.append("  ").append(ptrReg).append(" = inttoptr i64 ")
                .append(i64Reg).append(" to i8*\n");
            return ptrReg;
        }

        if (isIntegerType(t)) {
            String i64Reg;
            if (t == Type.LONG) {
                i64Reg = baseRef;
            } else {
                i64Reg = newAux("base_i64");
                String op = (t == Type.BOOLEAN) ? "zext" : "sext";
                sb.append("  ").append(i64Reg).append(" = ").append(op).append(" ")
                    .append(baseLlvm).append(" ").append(baseRef)
                    .append(" to i64\n");
            }
            String ptrReg = newAux("base_i8");
            sb.append("  ").append(ptrReg).append(" = inttoptr i64 ")
                .append(i64Reg).append(" to i8*\n");
            return ptrReg;
        }

        throw new IllegalStateException(
            "Cannot convert value of type '" + t + "' to i8* for raw-offset field access. "
                + "Value = " + base + ". This indicates a bug in an earlier IR pass.");
    }

    private String getPointerOperand(StringBuilder sb, Value v) {
        String val = getLlvmValue(sb, v);
        if ("0".equals(val)) {
            return "null";
        }
        return val;
    }

    /**
     * A direct {@code __jnative_} call that bypasses the vtable is only
     * correct when no reachable subclass can override the target method.
     * Otherwise the call must go through the dispatch slot so that a true
     * override (e.g. {@code String.hashCode()}) is honored at runtime.
     */
    private boolean isVirtualCallDirectNativeSafe(String owner, String methodName, String methodDesc) {
        String ownerInternal = owner.replace('.', '/');
        String[] foundOwner = new String[1];
        io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode mn =
            resolver.findMethodInHierarchy(ownerInternal, methodName, methodDesc, foundOwner);
        if (mn == null) return false;

        if (Modifier.isPrivate(mn.getAccess())) return true;

        String declaringClass = foundOwner[0] != null ? foundOwner[0] : ownerInternal;
        io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode declaringCn =
            resolver.getClassNode(declaringClass);
        if (declaringCn != null && Modifier.isFinal(declaringCn.getAccess())) return true;

        if (Modifier.isFinal(mn.getAccess())) return true;

        for (String subclass : resolver.getSubclasses(declaringClass)) {
            if (subclass.equals(declaringClass)) continue;
            io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode override =
                resolver.getMethodNode(subclass, methodName, methodDesc);
            if (override != null && !override.isStatic() && !override.isAbstract()) {
                return false;
            }
        }
        return true;
    }
}
package io.github.kubyk01.application.service.optimizer;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.BranchTerminator;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.TryCatchRange;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.util.LlvmUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
@RequiredArgsConstructor
public class DestructorSimplifier {

    private final Module module;
    @Getter
    private final AliasAnalysisResult aliasResult;
    private final LifetimeAnalysisResult lifetimeResult;
    @Getter
    private final Map<AllocationSite, Value> siteToValue;
    private final boolean enableSimplification;
    private final boolean enableInlining;
    private final boolean enableDeadElimination;

    private final Set<Function> destructors = new HashSet<>();
    private final Set<Function> trivialDestructors = new HashSet<>();

    /**
     * Destructors whose fast-path body can be inlined at every call site.
     *
     * <p>Key   : the destructor function.<br>
     * Value : the ordered list of instance-field keys (in
     *         {@code "owner/Class.name"} form, exactly as the IR field
     *         constant carries them) that the destructor frees in its
     *         fast path. The empty list means "free only the receiver
     *         itself".</p>
     *
     * <p>Populated by {@link #computeInlineableDestructors()} and
     * consumed by {@link #inlineDestructors()}. See the class-level
     * javadoc of the classification method for the exact instruction
     * pattern that qualifies a destructor for inlining.</p>
     */
    private final Map<Function, List<String>> inlineableFields = new LinkedHashMap<>();

    /**
     * Monotone counter that gives every split-off tail block and every
     * inline body block a unique label suffix across the whole pass. The
     * counter is per-instance (one instance per Orchestrator run) and
     * therefore guaranteed unique within a module.
     */
    private int inlineSplitId = 0;

    public void simplify() {
        for (Function func : module.getFunctions()) {
            if (func.getName().startsWith("__destruct_")
                || func.getName().startsWith("__destruct_array_")) {
                destructors.add(func);
            }
        }

        if (enableDeadElimination) {
            eliminateDeadDestructors();
        }

        if (enableSimplification) {
            simplifyDestructors();
        }

        if (enableInlining) {
            inlineDestructors();
        }
    }

    // =====================================================================
    //  Dead destructor elimination (unchanged)
    // =====================================================================

    private void eliminateDeadDestructors() {
        boolean dispatchUsed = false;
        outer:
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst.getOpcode() != Opcode.STATIC_CALL) continue;
                    if (inst.getOperands().isEmpty()) continue;
                    Value callee = inst.getOperands().getFirst();
                    if (!(callee instanceof Constant c)) continue;
                    Object v = c.getValue();
                    if (v instanceof String s
                        && s.equals("__jnative_destruct_dispatch")) {
                        dispatchUsed = true;
                        break outer;
                    }
                }
            }
        }
        if (dispatchUsed) {
            log.debug("DestructorSimplifier: dispatch in use — "
                + "dead destructor elimination skipped");
            return;
        }

        Set<Function> called = new LinkedHashSet<>();
        for (Function func : new ArrayList<>(module.getFunctions())) {
            if (func.getEntryBlock() == null) continue;
            collectCalls(func, called);
        }

        for (Map.Entry<AllocationSite, Set<DestructionPoint>> entry
            : lifetimeResult.getDestructionPoints().entrySet()) {
            Type type = entry.getKey().getType();
            Function dtor = module.getFunction(destructorName(type));
            if (dtor != null && called.add(dtor)) {
                collectCalls(dtor, called);
            }
        }

        Set<Function> dead = new HashSet<>(destructors);
        dead.removeAll(called);

        if (!dead.isEmpty()) {
            Set<String> deadNames = new HashSet<>();
            for (Function f : dead) deadNames.add(f.getName());

            for (Function func : module.getFunctions()) {
                if (dead.contains(func)) continue;
                if (func.getEntryBlock() == null) continue;
                for (BasicBlock block : func.getBlocks()) {
                    block.getInstructions().removeIf(inst -> {
                        if (inst.getOpcode() != Opcode.STATIC_CALL) return false;
                        if (inst.getOperands().isEmpty()) return false;
                        Value callee = inst.getOperands().getFirst();
                        if (!(callee instanceof Constant c)) return false;
                        Object v = c.getValue();
                        return v instanceof String s && deadNames.contains(s);
                    });
                }
            }
        }

        for (Function func : dead) {
            log.debug("Removing dead destructor: {}", func.getName());
            module.removeFunction(func);
            destructors.remove(func);
        }
    }

    private void collectCalls(Function func, Set<Function> accumulator) {
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() == Opcode.STATIC_CALL
                    && !inst.getOperands().isEmpty()) {
                    Value callee = inst.getOperands().getFirst();
                    if (callee instanceof Constant c) {
                        Object val = c.getValue();
                        if (val instanceof String name) {
                            Function called = module.getFunction(name);
                            if (called != null
                                && (called.getName().startsWith("__destruct_")
                                || called.getName().startsWith("__destruct_array_"))) {
                                if (accumulator.add(called)) {
                                    collectCalls(called, accumulator);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // =====================================================================
    //  Old-shape simplification (still applied to any destructor whose
    //  body has already been collapsed to a single FREE + RETURN; this
    //  shape does not occur in the current codegen but is preserved for
    //  any future pass that produces it).
    // =====================================================================

    private void simplifyDestructors() {
        for (Function func : destructors) {
            if (isTrivialDestructor(func)) {
                simplifyToFree(func);
                trivialDestructors.add(func);
            }
        }
    }

    private boolean isTrivialDestructor(Function func) {
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                Opcode op = inst.getOpcode();
                if (op == Opcode.EQ || op == Opcode.COND_BRANCH
                    || op == Opcode.FREE || op == Opcode.RETURN) {
                    // allowed
                } else if (op == Opcode.STATIC_CALL) {
                    return false;
                } else {
                    return false;
                }
            }
        }
        return true;
    }

    private void simplifyToFree(Function func) {
        BasicBlock entry = func.getEntryBlock();
        if (entry == null) return;
        entry.getInstructions().clear();
        Parameter thisParam = func.getParameters().getFirst();
        Instruction freeInst = new Instruction(Opcode.FREE);
        freeInst.addOperand(thisParam);
        entry.addInstruction(freeInst);
        entry.setTerminator(new ReturnTerminator(null));
        List<BasicBlock> toRemove = new ArrayList<>(func.getBlocks());
        toRemove.remove(entry);
        for (BasicBlock block : toRemove) {
            func.getBlocks().remove(block);
        }
        log.debug("Simplified destructor: {}", func.getName());
    }

    // =====================================================================
    //  Inlineable-destructor classification
    // =====================================================================

    /**
     * Populates {@link #inlineableFields} for every destructor that can
     * be inlined at its call sites.
     *
     * <p>A destructor is inlineable when its <b>fast-path block</b> —
     * the block emitted by {@code DestructorInserter.createClassDestructor}
     * under the label suffix {@code "_body_fast"} — contains only:</p>
     *
     * <ul>
     *   <li>{@code GET_FIELD(this, field)} instructions, one per
     *       reference-typed field whose destruction is itself a plain
     *       free (either a runtime free or a primitive-array destructor);</li>
     *   <li>a matching {@code STATIC_CALL} per such field to one of:
     *       <ul>
     *         <li>{@code __jnative_free_fast}</li>
     *         <li>{@code __jnative_free}</li>
     *         <li>{@code __destruct_array_<primitive-array>} — the array
     *             destructor of a primitive-element array is itself a
     *             single {@code FREE} on the array payload and therefore
     *             inlines to the same instruction;</li>
     *         <li>{@code __destruct_<inner>} where {@code inner} is
     *             itself inlineable and its {@link #inlineableFields}
     *             entry is empty (i.e. the inner destructor only frees
     *             its own receiver).</li>
     *       </ul></li>
     *   <li>exactly one {@code STATIC_CALL __jnative_free_fast(this)} or
     *       {@code __jnative_free(this)} (the "self free");</li>
     *   <li>a {@code RETURN} terminator.</li>
     * </ul>
     *
     * <p>Every other shape disqualifies the destructor. In particular a
     * destructor whose fast path contains a call to
     * {@code __jnative_destruct_dispatch} (the polymorphic-field case)
     * is <b>not</b> inlineable, because the runtime dispatch itself
     * looks up a destructor by the receiver's vtable and that lookup is
     * exactly what we would lose by inlining.</p>
     *
     * <h2>Why inlining is sound</h2>
     *
     * <p>The inline replaces the call with:</p>
     *
     * <pre>
     *   if (arg != null) {
     *     %f = GET_FIELD(arg, field);  FREE(%f)  ... (one per field)
     *     FREE(arg)
     *   }
     * </pre>
     *
     * <p>{@code FREE(x)} lowers to {@code __jnative_free(x)}, which:</p>
     *
     * <ul>
     *   <li>returns immediately for a {@code NULL} argument, so the
     *       inner null checks that the corresponding destructors used to
     *       perform on each field are no longer needed;</li>
     *   <li>checks the writable-data range of the main executable, so a
     *       static String literal stored in a reference field is not
     *       passed to libc {@code free};</li>
     *   <li>guards the {@code free} through the shutdown freed set when
     *       {@code gv_jnative_in_shutdown} is non-zero, so the shutdown
     *       path (which is the only context in which a shared object can
     *       arrive at this destructor twice) is preserved exactly.</li>
     * </ul>
     *
     * <p>The one thing the inline does <em>not</em> reproduce is the
     * {@code __jnative_dtor_enter} / {@code __jnative_dtor_unclaim}
     * claim-stack bookkeeping of the slow path. That machinery exists to
     * break cycles in the destructor call chain. A destructor that
     * qualifies as inlineable cannot participate in such a cycle:</p>
     *
     * <ul>
     *   <li>every field it frees is either a primitive array (which
     *       carries no references), a final class with no fields, or
     *       another inlineable destructor whose own field list is
     *       empty. None of those can reference the outer object, so no
     *       cycle through the outer object is possible.</li>
     *   <li>any field whose destructor would be capable of reaching
     *       back to the outer object (a mutable reference field, an
     *       array whose elements are references) falls out of the
     *       classification: the fast path then contains a call to
     *       {@code __jnative_destruct_dispatch} or a call to a
     *       destructor with a non-empty field list, and the whole
     *       destructor is left as a normal function call.</li>
     * </ul>
     *
     * <p>Consequently the inline path is observationally equivalent to
     * the original call for every input it can receive, in both the
     * normal-execution path and the shutdown path.</p>
     */
    private void computeInlineableDestructors() {
        inlineableFields.clear();

        // Old-shape trivial destructors (produced by simplifyToFree above)
        // reduce to a single FREE + RETURN. They carry no fields, so the
        // inline is "null-check + FREE(this)".
        for (Function dtor : trivialDestructors) {
            inlineableFields.put(dtor, Collections.emptyList());
        }

        // New-shape destructors. Classification of one destructor can
        // depend on another's classification (a class destructor whose
        // fast path calls another destructor on one of its fields is
        // only inlineable when that inner destructor is itself
        // inlineable and field-free), so iterate to a fixed point. The
        // lattice is flat with respect to "inlineable or not" and the
        // fixed point is reached in at most the number of destructors
        // iterations, which is bounded by the number of classes in the
        // image.
        boolean changed = true;
        int iterations = 0;
        final int MAX_ITERATIONS = 64;
        while (changed && iterations++ < MAX_ITERATIONS) {
            changed = false;
            for (Function dtor : destructors) {
                if (inlineableFields.containsKey(dtor)) continue;
                List<String> fields = tryClassifyInlineable(dtor);
                if (fields != null) {
                    inlineableFields.put(dtor, fields);
                    changed = true;
                }
            }
        }
        if (iterations >= MAX_ITERATIONS) {
            log.warn("DestructorSimplifier: inlineable-destructor "
                    + "classification did not converge after {} iterations; "
                    + "{} destructor(s) classified so far",
                MAX_ITERATIONS, inlineableFields.size());
        }
    }

    /**
     * Returns the field list of {@code dtor} if it qualifies for
     * inlining, or {@code null} otherwise.
     *
     * <p>The classification only inspects the fast-path block
     * ({@code *_body_fast}). The other blocks of the destructor
     * (entry null-check, shutdown check, slow-path body, return) are
     * not consulted because the inline path reproduces exactly the
     * fast-path semantics, and {@code __jnative_free}'s own shutdown
     * guard covers the slow-path semantics for every operation the
     * inline performs.</p>
     */
    private List<String> tryClassifyInlineable(Function dtor) {
        if (dtor == null || dtor.getEntryBlock() == null) return null;
        if (!dtor.getName().startsWith("__destruct_")) return null;

        List<Parameter> params = dtor.getParameters();
        if (params.size() != 1) return null;
        Parameter thisParam = params.getFirst();

        // Locate the fast-path body emitted by DestructorInserter.
        BasicBlock bodyFast = null;
        for (BasicBlock b : dtor.getBlocks()) {
            if (b.getLabel().endsWith("_body_fast")) {
                bodyFast = b;
                break;
            }
        }
        if (bodyFast == null) return null;

        Map<Value, String> fieldNameByValue = new HashMap<>();
        List<String> fieldNames = new ArrayList<>();
        int selfFreeCount = 0;

        for (Instruction inst : bodyFast.getInstructions()) {
            Opcode op = inst.getOpcode();
            switch (op) {
                case GET_FIELD -> {
                    if (inst.getOperands().size() < 2) return null;
                    Value base = inst.getOperands().getFirst();
                    if (base != thisParam) return null;
                    String fieldName = LlvmUtil.extractFieldName(inst);
                    if (fieldName == null
                        || fieldName.isEmpty()
                        || "unknown".equals(fieldName)) return null;
                    if (inst.getResult() != null) {
                        fieldNameByValue.put(inst.getResult(), fieldName);
                    }
                }
                case STATIC_CALL -> {
                    if (inst.getOperands().size() < 2) return null;
                    Value calleeVal = inst.getOperands().getFirst();
                    if (!(calleeVal instanceof Constant c)) return null;
                    String callee = c.getValue().toString();
                    Value arg = inst.getOperands().get(1);

                    if (arg == thisParam) {
                        // Self free
                        if (!callee.equals("__jnative_free_fast")
                            && !callee.equals("__jnative_free")) {
                            return null;
                        }
                        selfFreeCount++;
                    } else {
                        // Field free
                        String fieldName = fieldNameByValue.get(arg);
                        if (fieldName == null) return null;

                        if (callee.equals("__jnative_free_fast")
                            || callee.equals("__jnative_free")) {
                            fieldNames.add(fieldName);
                        } else if (callee.startsWith("__destruct_")) {
                            Function inner = module.getFunction(callee);
                            if (inner == null
                                || !inlineableFields.containsKey(inner)) {
                                return null;
                            }
                            // Recursing into a nested destructor with its own
                            // fields would require the inline emitter to
                            // reconstruct the full GET_FIELD chain of the
                            // inner destructor against the value of the outer
                            // field. That is out of scope for this pass;
                            // the single-level inline below only handles
                            // field-free inner destructors.
                            if (!inlineableFields.get(inner).isEmpty()) {
                                return null;
                            }
                            fieldNames.add(fieldName);
                        } else {
                            return null;
                        }
                    }
                }
                case RETURN, NOP -> {
                    // Allowed
                }
                default -> {
                    return null;
                }
            }
        }

        if (selfFreeCount != 1) return null;
        return fieldNames;
    }

    // =====================================================================
    //  Inlining
    // =====================================================================

    /**
     * Inlines every {@code STATIC_CALL __destruct_X(arg)} whose callee
     * is in {@link #inlineableFields}.
     *
     * <p>The pass walks every block of every function, and at the first
     * inlineable call site it:</p>
     *
     * <ol>
     *   <li>splits the block at the call site into the prefix
     *       (everything before the call, retained in the original
     *       block) and the suffix (everything after the call, moved to
     *       a fresh tail block);</li>
     *   <li>appends a {@code EQ arg, null} null check to the original
     *       block, followed by a {@code COND_BRANCH} that routes to the
     *       tail on null and to a fresh free block otherwise;</li>
     *   <li>emits the free block: one {@code GET_FIELD(arg, field) +
     *       FREE} pair per classified field, followed by a final
     *       {@code FREE(arg)} and a branch to the tail.</li>
     * </ol>
     *
     * <p>After emitting the inline sequence the walk continues from
     * the tail, so multiple inlineable calls in the same original
     * block are all inlined in one pass.</p>
     */
    private void inlineDestructors() {
        computeInlineableDestructors();
        if (inlineableFields.isEmpty()) {
            log.debug("DestructorSimplifier: no inlineable destructors");
            return;
        }

        int before = countInlineableCallSites();

        List<Function> allFuncs = new ArrayList<>(module.getFunctions());
        for (Function func : allFuncs) {
            if (func.getEntryBlock() == null) continue;
            inlineInFunction(func);
        }

        int after = countInlineableCallSites();
        log.info("DestructorSimplifier: inlined {} destructor call site(s) "
                + "({} -> {} remaining)",
            before - after, before, after);
    }

    private int countInlineableCallSites() {
        int n = 0;
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() == null) continue;
            for (BasicBlock b : f.getBlocks()) {
                for (Instruction i : b.getInstructions()) {
                    if (i.getOpcode() != Opcode.STATIC_CALL) continue;
                    if (i.getOperands().size() < 2) continue;
                    Value calleeVal = i.getOperands().getFirst();
                    if (!(calleeVal instanceof Constant c)) continue;
                    Function dtor = module.getFunction(c.getValue().toString());
                    if (dtor != null && inlineableFields.containsKey(dtor)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private void inlineInFunction(Function func) {
        Deque<BasicBlock> worklist = new ArrayDeque<>(func.getBlocks());
        while (!worklist.isEmpty()) {
            BasicBlock block = worklist.poll();
            processBlockForInlining(func, block);
        }
    }

    /**
     * Rewrites every inlineable call site of {@code block}, splitting
     * the block as needed.
     *
     * <h2>Try-range exclusion</h2>
     *
     * <p>The split introduces two new blocks ({@code tail}, {@code doFree})
     * that are not present in any {@code TryCatchRange.body}. If
     * {@code block} is inside a try range, {@code tail} would contain
     * throwing instructions that the LLVM emitter does not wrap in a
     * try guard (because
     * {@link io.github.kubyk01.application.service.codegen.llvm.LlvmFunctionEmitter#buildTryCatchInfo}
     * iterates {@code range.body} and knows nothing about {@code tail}),
     * so an exception raised by the suffix would escape uncaught.</p>
     *
     * <p>Rather than replicate the try-range bookkeeping inside this
     * pass — which would need to know how {@code LlvmFunctionEmitter}
     * computes each range's arm, exit and catch-dispatch blocks — the
     * whole block is skipped whenever any enclosing try-range could be
     * affected. Blocks outside any try range are safe to split: the
     * emitter will emit them without try guards before and after, so no
     * range bookkeeping changes.</p>
     *
     * <p>The check is conservative in two directions:</p>
     * <ul>
     *   <li>any block that is in the {@code body} list of at least one
     *       {@link TryCatchRange} is skipped;</li>
     *   <li>any range whose {@code body} was never precomputed (which
     *       happens when the translator could not resolve the range's
     *       start/end/handler labels) causes every block of the
     *       function to be treated as potentially inside that range.
     *       That path is unreachable on a well-formed image, but
     *       skipping is the correct safety direction when the try-guard
     *       layout is uncertain.</li>
     * </ul>
     */
    private void processBlockForInlining(Function func, BasicBlock block) {
        if (isInsideTryRange(func, block)) {
            return;
        }

        while (true) {
            List<Instruction> insts = block.getInstructions();
            int callIdx = -1;
            Instruction callInst = null;
            List<String> fields = null;

            for (int i = 0; i < insts.size(); i++) {
                Instruction inst = insts.get(i);
                if (inst.getOpcode() != Opcode.STATIC_CALL) continue;
                if (inst.getOperands().size() < 2) continue;
                Value calleeVal = inst.getOperands().getFirst();
                if (!(calleeVal instanceof Constant c)) continue;
                Function dtor = module.getFunction(c.getValue().toString());
                if (dtor == null || !inlineableFields.containsKey(dtor)) {
                    continue;
                }
                callIdx = i;
                callInst = inst;
                fields = inlineableFields.get(dtor);
                break;
            }

            if (callIdx < 0) return;

            Value obj = callInst.getOperands().get(1);

            // Java-level null constant: nothing to free, drop the call.
            if (obj instanceof Constant c2 && c2.getValue() == null) {
                insts.remove(callIdx);
                continue;
            }
            if (obj.getType() == null
                || obj.getType().isNull()
                || obj.getType().isUnknown()) {
                insts.remove(callIdx);
                continue;
            }

            BasicBlock tail = splitBlockAt(func, block, callIdx);
            if (tail == null) {
                log.warn("DestructorSimplifier: could not split block {} "
                        + "at instruction {} — leaving destructor call intact",
                    block.getLabel(), callIdx);
                return;
            }

            emitInlineBody(func, block, tail, obj, fields);
            block = tail;

            // After the first split `tail` is a freshly created block
            // that is not in any try range (the parent was already
            // verified not to be in one), so subsequent splits from
            // `tail` are safe without re-running the check.
        }
    }

    /**
     * True when {@code block} participates in any try-range body of
     * {@code func}, or when the function contains a try-range whose
     * body could not be precomputed.
     *
     * <p>See {@link #processBlockForInlining} for the full rationale
     * and for the two directions in which the check is conservative.</p>
     */
    private boolean isInsideTryRange(Function func, BasicBlock block) {
        List<TryCatchRange> ranges = func.getTryCatchRanges();
        if (ranges == null || ranges.isEmpty()) return false;
        for (TryCatchRange range : ranges) {
            List<BasicBlock> body = range.body;
            if (body == null) {
                // Body was not precomputed for this range; the emitter
                // will compute it lazily from the CFG. We cannot know
                // whether `block` belongs to it, so we do not split it.
                return true;
            }
            if (body.contains(block)) return true;
        }
        return false;
    }

    /**
     * Splits {@code block} at instruction index {@code callIdx}: the
     * prefix {@code [0, callIdx)} stays in {@code block}, the suffix
     * {@code [callIdx+1, end)} is moved to a fresh tail block (the
     * instruction at {@code callIdx} is discarded), and the terminator
     * plus every CFG link that pointed at {@code block} is moved to the
     * tail.
     *
     * <h2>Predecessor-position preservation</h2>
     *
     * <p>The most important invariant here is that, for every successor
     * {@code succ} of the original {@code block}, the predecessor slot
     * that used to hold {@code block} must now hold {@code tail}
     * <b>at the same index</b>. PHI operands are positional against
     * {@link BasicBlock#getPredecessors()}: the i-th operand belongs to
     * the i-th predecessor. If the split moves the tail to the end of
     * the list — which is what a plain {@code setTerminator} +
     * {@code addSuccessor} pair would do — every PHI in {@code succ}
     * is misaligned by one slot, and {@code OutOfSsaPass} subsequently
     * emits {@code store <wrong operand>, local} in the wrong
     * predecessor, producing the LLVM verifier error</p>
     *
     * <pre>
     *   Instruction does not dominate all uses!
     * </pre>
     *
     * <p>for every successor that carries a PHI. The reordering below
     * keeps each successor's predecessor list in its original order, so
     * PHI operands remain aligned without any explicit PHI rewrite.</p>
     *
     * <h2>Order of operations</h2>
     *
     * <ol>
     *   <li>capture each successor's current index of {@code block};</li>
     *   <li>write {@code tail} into that index — <b>before</b> touching
     *       terminators, so {@code setTerminator}'s own
     *       {@code addSuccessor} sees {@code tail} already present and
     *       does not append a duplicate;</li>
     *   <li>clear {@code block}'s old terminator (detaches its success
     *       edges and, as a side-effect, tries — and fails, harmlessly —
     *       to remove {@code block} from the same predecessor lists);</li>
     *   <li>attach the old terminator to {@code tail};</li>
     *   <li>copy the block's exceptional-successor edges to
     *       {@code tail}. Both the prefix and the suffix can throw, and
     *       both must reach the enclosing handler.</li>
     * </ol>
     *
     * @return the new tail block, or {@code null} if the split cannot
     *         be performed (which leaves {@code block} unchanged)
     */
    private BasicBlock splitBlockAt(Function func, BasicBlock block, int callIdx) {
        List<Instruction> insts = block.getInstructions();
        Terminator oldTerm = block.getTerminator();

        BasicBlock tail = new BasicBlock(
            block.getLabel() + "_inline_tail_" + (++inlineSplitId));
        func.addBlock(tail);

        for (int i = callIdx + 1; i < insts.size(); i++) {
            tail.addInstruction(insts.get(i));
        }
        while (insts.size() > callIdx) {
            insts.removeLast();
        }

        if (oldTerm != null) {
            // ---- Step 1: capture each successor's current index of `block` ----
            Map<BasicBlock, Integer> oldIndices = new HashMap<>();
            for (BasicBlock succ : oldTerm.getTargets()) {
                if (succ == null) continue;
                int pi = succ.getPredecessors().indexOf(block);
                if (pi >= 0) oldIndices.put(succ, pi);
            }

            // ---- Step 2: replace `block` with `tail` at the SAME index ----
            // Must happen BEFORE setTerminator calls: setTerminator(succ)
            // internally invokes addSuccessor, which only appends to
            // succ.predecessors if `tail` is not already present there.
            // With the position already in place, addSuccessor becomes a
            // no-op for the predecessor list and leaves the ordering
            // intact.
            for (Map.Entry<BasicBlock, Integer> e : oldIndices.entrySet()) {
                List<BasicBlock> preds = e.getKey().getPredecessors();
                int pi = e.getValue();
                if (pi >= 0 && pi < preds.size() && preds.get(pi) == block) {
                    preds.set(pi, tail);
                }
            }

            // ---- Step 3: detach old terminator from `block` ----
            // Removes block.successors and tries to remove `block` from
            // each successor's preds. The latter is a no-op: `block` is
            // already gone from those lists after step 2.
            block.setTerminator(null);

            // ---- Step 4: attach old terminator to `tail` ----
            // addSuccessor sees `tail` already in each successor's preds
            // (from step 2) and does not append a duplicate. The
            // predecessor list ordering is preserved exactly.
            tail.setTerminator(oldTerm);

            // ---- Step 5: propagate exceptional edges ----
            // The original block had (possibly) an exceptional edge to a
            // handler; both the prefix (still in `block`) and the suffix
            // (now in `tail`) can throw, and both need to reach the
            // handler. The prefix keeps its edges untouched; the suffix
            // needs its own.
            for (BasicBlock exc : new ArrayList<>(block.getExceptionalSuccessors())) {
                tail.addExceptionalSuccessor(exc);
            }
        }

        return tail;
    }

    /**
     * Emits the inline replacement of a destructor call at the end of
     * {@code block}:
     *
     * <pre>
     *   block:
     *     %is_null = icmp eq arg, null
     *     br i1 %is_null, label %tail, label %do_free
     *
     *   do_free:
     *     (per field)  %f = GET_FIELD(arg, field);  FREE(%f)
     *     FREE(arg)
     *     br label %tail
     * </pre>
     *
     * <p>The null check is mandatory: {@code GET_FIELD(arg, field)} on
     * a null {@code arg} would attempt to read through the null
     * pointer. The check is one compare and one branch, both trivially
     * predictable in the destructor call sites that dominate this pass
     * (they are reached from a caller that has just allocated the
     * object).</p>
     */
    private void emitInlineBody(Function func,
                                BasicBlock block,
                                BasicBlock tail,
                                Value obj,
                                List<String> fields) {
        Instruction isNull = new Instruction(Opcode.EQ);
        isNull.addOperand(obj);
        isNull.addOperand(new Constant(Type.NULL, null));
        Temporary isNullRes = new Temporary(Type.BOOLEAN);
        isNull.setResult(isNullRes);
        isNullRes.setDefiningInstruction(isNull);
        isNull.setParent(block);
        block.addInstruction(isNull);

        BasicBlock doFree = new BasicBlock(
            block.getLabel() + "_inline_free_" + (++inlineSplitId));
        func.addBlock(doFree);

        block.setTerminator(new CondBranchTerminator(isNullRes, tail, doFree));

        for (String fieldName : fields) {
            Instruction getField = new Instruction(Opcode.GET_FIELD);
            getField.addOperand(obj);
            getField.addOperand(new Constant(
                Type.reference(fieldName), fieldName));
            Temporary fieldVal = new Temporary(Type.reference("java/lang/Object"));
            getField.setResult(fieldVal);
            fieldVal.setDefiningInstruction(getField);
            getField.setParent(doFree);
            doFree.addInstruction(getField);

            Instruction freeField = new Instruction(Opcode.FREE);
            freeField.addOperand(fieldVal);
            freeField.setParent(doFree);
            doFree.addInstruction(freeField);
        }

        Instruction freeObj = new Instruction(Opcode.FREE);
        freeObj.addOperand(obj);
        freeObj.setParent(doFree);
        doFree.addInstruction(freeObj);

        doFree.setTerminator(new BranchTerminator(tail));
    }

    // =====================================================================
    //  Naming helper (unchanged)
    // =====================================================================

    private String destructorName(Type type) {
        if (type.isArray()) {
            return "__destruct_array_"
                + type.toString()
                .replace('/', '_')
                .replace('[', '_')
                .replace(';', '_');
        } else if (type.isReference()) {
            return "__destruct_"
                + type.getClassName().replace('/', '_').replace('.', '_');
        }
        return "__destruct_unknown";
    }
}
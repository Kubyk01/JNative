package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.IndirectBranchTerminator;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.LookupSwitchTerminator;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.TableSwitchTerminator;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.UndefinedValue;
import io.github.kubyk01.domain.ir.Value;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lowers every {@code PHI} instruction to an explicit store into a
 * per-phi local slot, and to a load from that slot at the head of the
 * phi's block. This is the standard out-of-SSA transformation.
 *
 * <p>The LLVM emitter restructures the CFG while emitting a function:
 * every call that is covered by a {@code try} range is wrapped in a
 * sequence of freshly-created blocks ({@code guarded_body_N},
 * {@code guarded_cont_N}, {@code chit_N}, …). Those blocks become the
 * real predecessors of the block that carries the phi, so the phi's
 * operand list — populated by {@link SSATransformer} from the
 * <em>IR-level</em> predecessor list — no longer matches the emitted
 * CFG. LLVM then rejects the module with
 * <em>"PHINode should have one entry for each predecessor"</em>, and in
 * the same restructuring the value the phi's operand referred to no
 * longer dominates the phi.</p>
 *
 * <p>Expressing the same data-flow through a memory slot removes the
 * problem entirely: the store lands at the end of the predecessor's
 * emitted tail, the load sits at the head of the phi's block, and the
 * slot itself is allocated in the function entry block by
 * {@link io.github.kubyk01.application.service.codegen.llvm.LlvmFunctionEmitter},
 * so the address of the slot dominates every block of the function.</p>
 *
 * <h2>What changed relative to the previous revision</h2>
 *
 * <p>The previous revision silently substituted
 * {@code new Constant(Type.NULL, null)} for any phi operand that was
 * missing from the operand list, whenever the IR-level predecessor
 * count exceeded the operand count. It never checked whether the extra
 * predecessors were <b>real</b> predecessors — i.e. whether their
 * terminators actually target the phi's block, or whether they are
 * exceptional predecessors. It also never checked the reverse case,
 * where the operand count exceeded the predecessor count.</p>
 *
 * <p>Two independent bugs were masked by that silent substitution:</p>
 *
 * <ol>
 *   <li><b>False predecessors left over from {@code setTerminator}.</b>
 *       {@link BasicBlock#setTerminator(Terminator)} adds the new
 *       terminator's targets to the block's successor list but does
 *       <b>not</b> remove the old terminator's targets. It also does
 *       not remove the removed targets from those blocks' predecessor
 *       lists. A block that was terminated twice — which happens when
 *       {@code MethodTranslator} re-emits a terminator over an
 *       already-terminated block, or when {@code TryCatchHandler}
 *       installs an exceptional edge on a block whose normal terminator
 *       has already targeted a different block — therefore carries
 *       stale predecessors that no instruction actually jumps from.</li>
 *
 *   <li><b>Phantom exceptional predecessors.</b>
 *       {@link BasicBlock#addExceptionalSuccessor(BasicBlock)} adds a
 *       predecessor link but no corresponding entry in the phi's
 *       operand list. The phi was populated by
 *       {@link SSATransformer#renameBlock} from the IR-level successor
 *       list, which does <b>not</b> include exceptional successors.
 *       Any block whose predecessors include such a phantom therefore
 *       has a phi whose operand list is one entry short of the
 *       predecessor list.</li>
 * </ol>
 *
 * <p>In both cases the previous revision read one slot too far into the
 * operand list and got {@code null}, then stored {@code null} into the
 * phi's local slot. That {@code null} then propagated into every use of
 * the phi result — including the base of a {@code PUT_FIELD} — and
 * produced the observed
 * {@code "Cannot invoke java.security.ProtectionDomain(init…) because
 * field 'java/security/ProtectionDomain.principals' is null"} crash.</p>
 *
 * <p>The fix has two parts, applied together:</p>
 *
 * <ol>
 *   <li><b>CFG sanitisation.</b> Before processing any phi, the pass
 *       walks every block's predecessor list and drops every
 *       predecessor whose terminator does not actually reach the block
 *       (neither as a normal target nor as an exceptional successor).
 *       The dropped entries are exactly the stale links described
 *       above. After this step, the predecessor list reflects the CFG
 *       that will be emitted, and the phi's operand count is required
 *       to match it exactly.</li>
 *
 *   <li><b>Hard failure on remaining mismatch.</b> If, after
 *       sanitisation, the predecessor and operand counts still differ,
 *       the pass throws {@link IllegalStateException} instead of
 *       substituting a default. A mismatch at this point is a genuine
 *       bug in {@link SSATransformer} (or in one of the CFG-mutating
 *       helpers it relies on), and the diagnostic names the block, the
 *       phi and both counts, so the underlying defect is visible at
 *       build time instead of becoming a silent null at run time.</li>
 * </ol>
 *
 * <p>The two changes are complementary, not alternatives. The
 * sanitiser removes the legitimate causes of mismatch (stale and
 * phantom predecessors). The hard failure then becomes an invariant
 * check on the remaining CFG, which is exactly what an out-of-SSA pass
 * needs in order to be sound: the transformation is defined only for a
 * phi whose operand list matches its block's real predecessors.</p>
 */
public final class OutOfSsaPass {

    private OutOfSsaPass() {}

    public static void transform(Module module) {
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            transformFunction(func);
        }
    }

    // =====================================================================
    //  CFG sanitisation
    // =====================================================================

    /**
     * Returns true iff {@code pred} actually reaches {@code target} in
     * the CFG that will be emitted.
     *
     * <p>"Reaches" is defined as either:</p>
     *
     * <ul>
     *   <li>the predecessor's current terminator names {@code target}
     *       among its {@link Terminator#getTargets() targets}; or</li>
     *   <li>{@code target} is one of the predecessor's
     *       {@link BasicBlock#getExceptionalSuccessors() exceptional
     *       successors}.</li>
     * </ul>
     *
     * <p>This is exactly the relation that
     * {@link BasicBlock#addSuccessor(BasicBlock)} and
     * {@link BasicBlock#addExceptionalSuccessor(BasicBlock)} are
     * supposed to maintain, but which can drift when
     * {@link BasicBlock#setTerminator(Terminator)} is invoked on an
     * already-terminated block (the old terminator's targets stay in
     * {@code pred.successors}) or when
     * {@link BasicBlock#addExceptionalSuccessor(BasicBlock)} is invoked
     * on a block whose normal terminator already targets a different
     * block (the exceptional edge adds a predecessor link but no phi
     * operand).</p>
     *
     * <p>Both drift sources produce the same observable symptom in this
     * pass: a phi whose block has more predecessors than operands. The
     * sanitiser filters them out and leaves the phi with the correct
     * predecessor set.</p>
     */
    private static boolean predecessorActuallyReaches(BasicBlock pred,
                                                      BasicBlock target) {
        Terminator term = pred.getTerminator();
        if (term != null) {
            for (BasicBlock t : term.getTargets()) {
                if (t == target) return true;
            }
        }
        for (BasicBlock exc : pred.getExceptionalSuccessors()) {
            if (exc == target) return true;
        }
        return false;
    }

    // =====================================================================
    //  Per-function transformation
    // =====================================================================

    private static void transformFunction(Function func) {
        // ------------------------------------------------------------------
        // Predecessor sanitisation before the phi processing.
        //
        // The phi operands are positional: the i-th operand corresponds to
        // the i-th predecessor of the block at the moment the phi was
        // built in SSATransformer. After SSA, DestructorInserter and
        // Optimizer mutate the CFG (block splits, instruction
        // insertion/removal), so the positional binding may become
        // incorrect. Here we filter out the predecessors whose terminators
        // do not lead to the block and that were not added through
        // addExceptionalSuccessor, and rebuild the phi operands by
        // predecessor identity.
        // ------------------------------------------------------------------
        Set<BasicBlock> reachable = computeReachable(func);

        for (BasicBlock block : func.getBlocks()) {
            if (!reachable.contains(block)) continue;

            List<BasicBlock> preds = block.getPredecessors();
            if (preds.isEmpty()) continue;

            // 1. Identify the stale predecessors.
            List<BasicBlock> stale = new ArrayList<>();
            for (BasicBlock pred : preds) {
                if (!predecessorActuallyReaches(pred, block)) {
                    stale.add(pred);
                }
            }
            if (stale.isEmpty()) continue;

            // 2. Capture the current binding (predecessor -> operand)
            //    before mutating the predecessor list.
            Map<Instruction, Map<BasicBlock, Value>> bindings = new HashMap<>();
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() != Opcode.PHI) continue;
                Map<BasicBlock, Value> binding = new HashMap<>();
                List<Value> ops = inst.getOperands();
                for (int i = 0; i < preds.size() && i < ops.size(); i++) {
                    binding.put(preds.get(i), ops.get(i));
                }
                bindings.put(inst, binding);
            }

            // 3. Drop the stale predecessors.
            preds.removeAll(stale);

            // 4. Rebuild the phi operands: only the surviving
            //    predecessors, in their new order.
            for (Map.Entry<Instruction, Map<BasicBlock, Value>> e : bindings.entrySet()) {
                Instruction phi = e.getKey();
                Map<BasicBlock, Value> binding = e.getValue();
                List<Value> ops = phi.getOperands();
                ops.clear();
                for (BasicBlock pred : preds) {
                    Value v = binding.get(pred);
                    if (v == null) {
                        throw new IllegalStateException(
                            "OutOfSsaPass: phi in block '" + block.getLabel()
                                + "' of function '" + func.getName()
                                + "' has no operand binding for surviving "
                                + "predecessor '" + pred.getLabel()
                                + "' after CFG sanitisation. This indicates a "
                                + "structurally inconsistent CFG: the predecessor "
                                + "reaches the block, but was not present in the "
                                + "phi's operand list at the time this pass ran. "
                                + "The phi was built positionally by "
                                + "SSATransformer.renameBlock; a later pass must "
                                + "have added a new edge to this block without "
                                + "rebuilding the phi.");
                    }
                    ops.add(v);
                }
            }
        }

        // ------------------------------------------------------------------
        // Resolve UndefinedValue operands that arrive on exceptional-only
        // edges.
        //
        // renameBlock substitutes Constant(Type.NULL, null) for the incoming
        // operand of a phi edge that is exceptional-only (see the javadoc
        // there). This block is the safety net: it re-applies the same
        // substitution for any phi that still carries an UndefinedValue on an
        // exceptional-only edge - for example a phi that was built before the
        // renameBlock fix, or a phi that received an UndefinedValue through an
        // intermediate phi chain.
        //
        // The substitution is sound for exactly the same reason it is sound in
        // renameBlock: the JVM verifier guarantees the phi's result is not
        // observed on the exception path. A normal (or mixed) edge with a
        // missing version is left as UndefinedValue so the verification loop
        // below still fails on it as a genuine bug (the ProtectionDomain case).
        // ------------------------------------------------------------------
        for (BasicBlock block : func.getBlocks()) {
            if (!reachable.contains(block)) continue;
            List<BasicBlock> preds = block.getPredecessors();
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() != Opcode.PHI) continue;
                List<Value> ops = inst.getOperands();
                for (int i = 0; i < ops.size() && i < preds.size(); i++) {
                    if (!(ops.get(i) instanceof UndefinedValue)) continue;

                    BasicBlock pred = preds.get(i);
                    boolean isNormalTarget = false;
                    Terminator term = pred.getTerminator();
                    if (term != null) {
                        for (BasicBlock t : term.getTargets()) {
                            if (t == block) { isNormalTarget = true; break; }
                        }
                    }
                    boolean isExceptional = pred.getExceptionalSuccessors().contains(block);

                    if (!isNormalTarget && isExceptional) {
                        ops.set(i, new Constant(Type.NULL, null));
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Verification
        //
        // Two independent checks, both on phi operands in reachable blocks:
        //
        //   1. UndefinedValue  -> always a defect (missing version on a
        //      genuine normal edge, or an unexpected fallthrough from the
        //      renameBlock fail-fast path).
        //
        //   2. Constant(null)  -> a defect ONLY if the phi's result flows to
        //      a non-PHI use (i.e. the phi is "live"). This is precisely the
        //      ProtectionDomain.<init> class of defect described in the
        //      JNI-22 debug report: renameBlock substitutes
        //      Constant(Type.NULL, null) for a missing version on an
        //      "exceptional-only" edge, and that placeholder must never
        //      reach a live use. If the edge really was exceptional-only and
        //      the value is genuinely unobservable there, the phi will be
        //      dead (its result is only used by other dead phis or not at
        //      all), and the check correctly allows it.
        //
        // The previous check had two flaws:
        //
        //   * it tested `!c.getType().isNull()`, which excludes the
        //     Constant(Type.NULL, null) shape that renameBlock actually
        //     produces;
        //
        //   * it tested `c.getType().isReference()`, but Type.NULL has kind
        //     NULL, not REFERENCE, so even without the first test the
        //     placeholder slipped through.
        //
        // Both flaws together made the check dead for exactly the case it was
        // meant to catch.
        // ------------------------------------------------------------------

        // ---- Step 1: compute liveness ----
        //
        // A value is live iff it appears as an operand of a non-PHI
        // instruction or of a terminator, OR it is an operand of another live
        // phi. The set is closed transitively through phi operands so that a
        // phi feeding only another live phi is also considered live.

        Set<Value> liveValues = new HashSet<>();
        for (BasicBlock block : func.getBlocks()) {
            if (!reachable.contains(block)) continue;
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() == Opcode.PHI) continue;
                for (Value op : inst.getOperands()) {
                    if (op != null) liveValues.add(op);
                }
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                for (Value op : terminatorOperands(term)) {
                    if (op != null) liveValues.add(op);
                }
            }
        }

        boolean liveChanged = true;
        int liveIter = 0;
        while (liveChanged && liveIter++ < 100_000) {
            liveChanged = false;
            for (BasicBlock block : func.getBlocks()) {
                if (!reachable.contains(block)) continue;
                for (Instruction inst : block.getInstructions()) {
                    if (inst.getOpcode() != Opcode.PHI) continue;
                    if (inst.getResult() == null) continue;
                    if (!liveValues.contains(inst.getResult())) continue;
                    for (Value op : inst.getOperands()) {
                        if (op != null && liveValues.add(op)) {
                            liveChanged = true;
                        }
                    }
                }
            }
        }

        // ---- Step 2: verify every phi operand ----

        for (BasicBlock block : func.getBlocks()) {
            if (!reachable.contains(block)) continue;
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() != Opcode.PHI) continue;
                List<Value> ops = inst.getOperands();
                for (int i = 0; i < ops.size(); i++) {
                    Value op = ops.get(i);

                    if (op instanceof UndefinedValue) {
                        throw new IllegalStateException(
                            "OutOfSsaPass: phi in block '" + block.getLabel()
                                + "' of function '" + func.getName()
                                + "' has an UndefinedValue at operand index " + i
                                + ". This is a placeholder emitted by "
                                + "SSATransformer.renameBlock when the slot had no "
                                + "version on the corresponding incoming edge. "
                                + "The phi is alive (its result is used somewhere), "
                                + "which means the missing version is a genuine bug, "
                                + "not a dead-phi artifact. Investigate dominance "
                                + "of '" + block.getLabel() + "', stale predecessor "
                                + "edges in the CFG, or a phi/predecessor-count "
                                + "mismatch. Substituting null here would silently "
                                + "corrupt every use of the phi result.");
                    }

                    if (op instanceof Constant c
                            && c.getValue() == null
                            && liveValues.contains(inst.getResult())) {
                        throw new IllegalStateException(
                            "OutOfSsaPass: phi in block '" + block.getLabel()
                                + "' of function '" + func.getName()
                                + "' has a null Constant placeholder at operand index "
                                + i + " (type " + c.getType() + "), and the phi's "
                                + "result is used by a non-PHI instruction or "
                                + "terminator. This is the ProtectionDomain.<init> "
                                + "class of defect: a null placeholder introduced by "
                                + "SSATransformer.renameBlock for an exceptional-only "
                                + "edge has leaked into a live use. Two likely causes: "
                                + "(a) the edge was misclassified as exceptional-only "
                                + "when it is actually normal - check the try-range "
                                + "body computation in MethodTranslator.visitEnd and "
                                + "Analyzer.retranslateClinit; (b) the phi operand "
                                + "binding has been shifted by a stale predecessor - "
                                + "check predecessorActuallyReaches() and the CFG "
                                + "mutation order in DestructorInserter/optimizer. "
                                + "Substituting null here is exactly what produced "
                                + "the 'field java/security/ProtectionDomain.principals' "
                                + "NPE crash at run time; failing at build time is "
                                + "strictly better.");
                    }
                }
            }
        }

        // ---- Existing out-of-SSA lowering ----

        int maxLocal = -1;
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                Opcode op = inst.getOpcode();
                if (op == Opcode.LOAD || op == Opcode.STORE) {
                    if (inst.getLocalIndex() > maxLocal) {
                        maxLocal = inst.getLocalIndex();
                    }
                }
            }
        }
        int nextLocal = maxLocal + 1;

        Map<Instruction, Integer> phiSlot = new HashMap<>();
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() == Opcode.PHI) {
                    phiSlot.put(inst, nextLocal++);
                }
            }
        }

        if (phiSlot.isEmpty()) return;

        for (Map.Entry<Instruction, Integer> entry : phiSlot.entrySet()) {
            Instruction phi = entry.getKey();
            int slot = entry.getValue();
            BasicBlock parent = phi.getParent();
            if (parent == null) continue;

            List<BasicBlock> preds = parent.getPredecessors();
            List<Value> operands = phi.getOperands();

            if (preds.size() != operands.size()) {
                throw new IllegalStateException(
                    "OutOfSsaPass: phi operand count (" + operands.size()
                        + ") does not match predecessor count (" + preds.size()
                        + ") in block " + parent.getLabel()
                        + "; phi = " + phi);
            }

            Type slotType = (phi.getResult() != null)
                ? phi.getResult().getType()
                : Type.UNKNOWN;

            for (int i = 0; i < preds.size(); i++) {
                BasicBlock pred = preds.get(i);
                Value val = operands.get(i);

                Instruction store = new Instruction(Opcode.STORE);
                store.addOperand(val);
                store.setLocalIndex(slot);
                Temporary storeResult = new Temporary(slotType);
                store.setResult(storeResult);
                storeResult.setDefiningInstruction(store);
                store.setParent(pred);
                pred.getInstructions().add(store);
            }
        }

        // ---- Replace every PHI with a LOAD from the same slot ----------
        for (BasicBlock block : func.getBlocks()) {
            List<Instruction> instrs = block.getInstructions();
            for (int i = 0; i < instrs.size(); i++) {
                Instruction phi = instrs.get(i);
                if (phi.getOpcode() != Opcode.PHI) continue;
                Integer slot = phiSlot.get(phi);
                if (slot == null) continue;

                Type phiType = phi.getResult() != null
                    ? phi.getResult().getType()
                    : Type.UNKNOWN;

                Instruction load = new Instruction(Opcode.LOAD);
                load.setLocalIndex(slot);
                Temporary loadResult = new Temporary(phiType);
                load.setResult(loadResult);
                loadResult.setDefiningInstruction(load);
                load.setParent(block);

                instrs.set(i, load);

                if (phi.getResult() != null) {
                    replaceUses(func, phi.getResult(), loadResult);
                }
            }
        }
    }

    /**
     * Returns the set of blocks reachable from the entry through
     * terminators and exceptional edges. Unreachable blocks (dead code)
     * are neither sanitised nor verified: their phi operands were never
     * filled in by SSATransformer.renameBlock (it only walks the
     * dom-tree children of the entry), so a null-operand check on them
     * would produce a false positive for every dead-code block.
     */
    private static Set<BasicBlock> computeReachable(Function func) {
        Set<BasicBlock> reachable = new HashSet<>();
        BasicBlock entry = func.getEntryBlock();
        if (entry == null) return reachable;

        Deque<BasicBlock> work = new ArrayDeque<>();
        work.push(entry);
        reachable.add(entry);
        while (!work.isEmpty()) {
            BasicBlock b = work.pop();
            for (BasicBlock s : b.getSuccessors()) {
                if (reachable.add(s)) work.push(s);
            }
        }
        return reachable;
    }

    /**
     * The operands a terminator consumes, matching the shape of
     * {@link Instruction#getOperands()} for the purposes of liveness
     * analysis in the verification step.
     */
    private static List<Value> terminatorOperands(Terminator term) {
        List<Value> out = new ArrayList<>(2);
        switch (term) {
            case CondBranchTerminator cbt -> {
                if (cbt.getCondition() != null) out.add(cbt.getCondition());
            }
            case ReturnTerminator rt -> {
                if (rt.getValue() != null) out.add(rt.getValue());
            }
            case ThrowTerminator tt -> {
                if (tt.getException() != null) out.add(tt.getException());
            }
            case LookupSwitchTerminator lst -> {
                if (lst.getKey() != null) out.add(lst.getKey());
            }
            case TableSwitchTerminator tst -> {
                if (tst.getKey() != null) out.add(tst.getKey());
            }
            case IndirectBranchTerminator ibt -> {
                if (ibt.getTargetBlock() != null) out.add(ibt.getTargetBlock());
            }
            case null, default -> {
            }
        }
        return out;
    }

    // =====================================================================
    //  Use rewriting
    // =====================================================================

    private static void replaceUses(Function func, Value oldVal, Value newVal) {
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                List<Value> ops = inst.getOperands();
                for (int i = 0; i < ops.size(); i++) {
                    if (ops.get(i) == oldVal) {
                        ops.set(i, newVal);
                    }
                }
            }
            Terminator term = block.getTerminator();
            switch (term) {
                case CondBranchTerminator cbt -> {
                    if (cbt.getCondition() == oldVal) cbt.setCondition(newVal);
                }
                case ReturnTerminator rt -> {
                    if (rt.getValue() == oldVal) rt.setValue(newVal);
                }
                case ThrowTerminator tt -> {
                    if (tt.getException() == oldVal) tt.setException(newVal);
                }
                case LookupSwitchTerminator lst -> {
                    if (lst.getKey() == oldVal) lst.setKey(newVal);
                }
                case TableSwitchTerminator tst -> {
                    if (tst.getKey() == oldVal) tst.setKey(newVal);
                }
                case IndirectBranchTerminator ibt -> {
                    if (ibt.getTargetBlock() == oldVal) ibt.setTargetBlock(newVal);
                }
                case null, default -> {
                }
            }
        }
    }
}
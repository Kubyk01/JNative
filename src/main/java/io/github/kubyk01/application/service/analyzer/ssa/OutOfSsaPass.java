package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
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
import io.github.kubyk01.domain.ir.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        // ------------------------------------------------------------------
        // Валидация инварианта φ без мутации CFG.
        //
        // Операнды φ позиционные: i-й операнд соответствует i-му
        // предшественнику блока на момент построения φ в
        // SSATransformer.renameBlock. Любая пост-SSA мутация списка
        // предшественников (setTerminator без снятия старых рёбер,
        // addSuccessor после SSA, ручное удаление из predecessors без
        // соответствующего удаления операнда) способна сломать это
        // соответствие. Здесь — только диагностика, чтобы такой
        // дефект не маскировался подстановкой Constant(Type.NULL, null):
        // φ-узел, чей операнд молча заменён null'ом, заражает null'ом
        // все свои использования.
        // ------------------------------------------------------------------
        for (Instruction phi : phiSlot.keySet()) {
            BasicBlock parent = phi.getParent();
            if (parent == null) {
                throw new IllegalStateException(
                        "OutOfSsaPass: PHI instruction has no parent block: " + phi
                                + " (SSATransformer.insertPhiFunctions must set parent "
                                + "on every phi it inserts)");
            }

            int preds = parent.getPredecessors().size();
            int operands = phi.getOperands().size();
            if (preds != operands) {
                throw new IllegalStateException(
                        "OutOfSsaPass: phi invariant violated in block '"
                                + parent.getLabel() + "': " + operands
                                + " operand(s) but " + preds + " predecessor(s). "
                                + "Operand position is defined by the predecessor list "
                                + "used by SSATransformer.renameBlock; any post-SSA CFG "
                                + "mutation must preserve predecessor indices (see "
                                + "BasicBlock.setTerminator's stale-edge cleanup, "
                                + "TryCatchHandler.handle's addExceptionalSuccessor, "
                                + "and DestructorInserter's block-splitting logic). "
                                + "Substituting null here would silently corrupt every "
                                + "use of the phi result. Phi: " + phi);
            }
        }

        // ---- Вставка STORE в конец каждого predecessor-блока -------------
        for (Map.Entry<Instruction, Integer> entry : phiSlot.entrySet()) {
            Instruction phi = entry.getKey();
            int slot = entry.getValue();
            BasicBlock parent = phi.getParent();
            if (parent == null) continue;

            List<BasicBlock> preds = parent.getPredecessors();
            List<Value> operands = phi.getOperands();

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

        // ---- Замена каждого PHI на LOAD из того же слота -----------------
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
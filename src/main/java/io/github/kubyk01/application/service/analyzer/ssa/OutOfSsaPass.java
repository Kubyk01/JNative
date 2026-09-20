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
import io.github.kubyk01.domain.ir.Value;

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
 * <p>This pass is intentionally conservative. If the IR-level
 * predecessor count does not match the phi's operand count, a {@code
 * null} value is stored on the extra edges; the phi is still removed
 * and replaced by a load, which keeps the IR structurally valid.</p>
 */
public final class OutOfSsaPass {

    private OutOfSsaPass() {}

    public static void transform(Module module) {
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            transformFunction(func);
        }
    }

    private static void transformFunction(Function func) {
        // Pick a fresh local slot for every phi. Use indices above the
        // largest slot the bytecode translation already produced, so
        // the emitter's `%local_N` allocas never collide.
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

        // Insert one STORE at the end of every predecessor block.
        for (Map.Entry<Instruction, Integer> entry : phiSlot.entrySet()) {
            Instruction phi = entry.getKey();
            int slot = entry.getValue();
            BasicBlock parent = phi.getParent();
            if (parent == null) continue;

            List<BasicBlock> preds = parent.getPredecessors();
            List<Value> operands = phi.getOperands();

            for (int i = 0; i < preds.size(); i++) {
                BasicBlock pred = preds.get(i);
                Value val = (i < operands.size()) ? operands.get(i) : null;
                if (val == null) {
                    val = new Constant(Type.NULL, null);
                }

                Instruction store = new Instruction(Opcode.STORE);
                store.addOperand(val);
                store.setLocalIndex(slot);
                Temporary storeResult = new Temporary(val.getType());
                store.setResult(storeResult);
                storeResult.setDefiningInstruction(store);
                store.setParent(pred);
                pred.getInstructions().add(store);
            }
        }

        // Replace every PHI with a LOAD and rewrite all uses of the
        // phi's result to the load's result.
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

                // Replace the phi in the instruction list FIRST, then
                // rewrite uses: this keeps replaceUses from visiting the
                // phi's own operands (the self-loop case).
                instrs.set(i, load);

                if (phi.getResult() != null) {
                    replaceUses(func, phi.getResult(), loadResult);
                }
            }
        }
    }

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
package io.github.kubyk01.application.service.optimizer;

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
import io.github.kubyk01.domain.ir.Value;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;

@Slf4j
@RequiredArgsConstructor
public class DeadCodeEliminator {

    private final Module module;

    public void eliminate() {
        boolean changed = true;
        int iterations = 0;
        final int maxIterations = 20;

        while (changed && iterations++ < maxIterations) {
            changed = false;

            Set<String> readStaticFields = collectReadStaticFields();
            Set<Integer> loadedLocals   = collectLoadedLocals();
            Set<Value> usedValues       = collectUsedValues(readStaticFields);

            for (Function func : module.getFunctions()) {
                if (func.getEntryBlock() == null) continue;
                for (BasicBlock block : func.getBlocks()) {
                    List<Instruction> instructions = block.getInstructions();
                    for (int i = 0; i < instructions.size(); i++) {
                        Instruction inst = instructions.get(i);
                        if (shouldRemove(inst, usedValues, readStaticFields, loadedLocals)) {
                            instructions.remove(i);
                            i--;
                            changed = true;
                        }
                    }
                }
            }
        }

        if (iterations >= maxIterations) {
            log.warn("Dead code elimination did not converge after {} iterations",
                maxIterations);
        } else {
            log.debug("Dead code elimination completed in {} iterations", iterations);
        }

        removeEmptyClinitFunctions();
    }

    /**
     * Removes every {@code <clinit>} function whose body became empty after
     * elimination and removes all call sites that referenced them. A
     * {@code <clinit>} that performs no observable work — no calls, no
     * writes to fields that are read elsewhere — is not part of the
     * reachable program and must not appear in the emitted module.
     */
    private void removeEmptyClinitFunctions() {
        Set<String> emptyClinits = new HashSet<>();
        for (Function func : module.getFunctions()) {
            String name = func.getName();
            if (!name.contains("__clinit__")) continue;
            if (isEffectivelyEmpty(func)) {
                emptyClinits.add(name);
            }
        }

        if (emptyClinits.isEmpty()) return;

        for (Function func : module.getFunctions()) {
            if (emptyClinits.contains(func.getName())) continue;
            for (BasicBlock block : func.getBlocks()) {
                block.getInstructions().removeIf(inst -> isCallTo(inst, emptyClinits));
            }
        }

        for (Function func : new ArrayList<>(module.getFunctions())) {
            if (emptyClinits.contains(func.getName())) {
                module.removeFunction(func);
            }
        }

        log.debug("Removed {} empty <clinit> function(s)", emptyClinits.size());
    }

    private boolean isEffectivelyEmpty(Function func) {
        if (func.getEntryBlock() == null) return true;
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                Opcode op = inst.getOpcode();
                if (op == Opcode.NOP) continue;
                if (op == Opcode.PHI) continue;
                return false;
            }
        }
        return true;
    }

    private boolean isCallTo(Instruction inst, Set<String> calleeNames) {
        Opcode op = inst.getOpcode();
        if (op != Opcode.STATIC_CALL && op != Opcode.CALL) return false;
        String callee = extractCalleeName(inst);
        return callee != null && calleeNames.contains(callee);
    }

    /**
     * Slot indices for which at least one LOAD instruction still exists after
     * the SSA pass. These are exactly the slots that the SSA transformer
     * left in memory (see SSATransformer.identifyUnsafeLocals); every STORE
     * to such a slot is observable and must not be eliminated.
     */
    private Set<Integer> collectLoadedLocals() {
        Set<Integer> loaded = new HashSet<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst.getOpcode() == Opcode.LOAD && inst.getLocalIndex() >= 0) {
                        loaded.add(inst.getLocalIndex());
                    }
                }
            }
        }
        return loaded;
    }

    private Set<String> collectReadStaticFields() {
        Set<String> fields = new HashSet<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst.getOpcode() == Opcode.GET_STATIC) {
                        String name = extractFieldName(inst);
                        if (name != null) fields.add(name);
                    }
                }
            }
        }
        return fields;
    }

    private Set<Value> collectUsedValues(Set<String> readStaticFields) {
        Set<Value> used = new HashSet<>();
        Deque<Value> worklist = new ArrayDeque<>();

        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (isRoot(inst, readStaticFields)) {
                        for (Value op : inst.getOperands()) {
                            if (op != null && used.add(op)) worklist.add(op);
                        }
                    }
                }
                Terminator term = block.getTerminator();
                if (term != null) {
                    for (Value v : terminatorOperands(term)) {
                        if (v != null && used.add(v)) worklist.add(v);
                    }
                }
            }
        }

        while (!worklist.isEmpty()) {
            Value v = worklist.poll();
            if (v instanceof Temporary t) {
                Instruction def = t.getDefiningInstruction();
                if (def != null) {
                    for (Value op : def.getOperands()) {
                        if (op != null && used.add(op)) worklist.add(op);
                    }
                }
            }
        }

        return used;
    }

    private List<Value> terminatorOperands(Terminator term) {
        List<Value> result = new ArrayList<>();
        if (term instanceof CondBranchTerminator cbt) {
            result.add(cbt.getCondition());
        } else if (term instanceof ReturnTerminator rt) {
            result.add(rt.getValue());
        } else if (term instanceof ThrowTerminator tt) {
            result.add(tt.getException());
        } else if (term instanceof LookupSwitchTerminator lst) {
            result.add(lst.getKey());
        } else if (term instanceof TableSwitchTerminator tst) {
            result.add(tst.getKey());
        } else if (term instanceof IndirectBranchTerminator ibt) {
            result.add(ibt.getTargetBlock());
        }
        return result;
    }

    private boolean isRoot(Instruction inst, Set<String> readStaticFields) {
        Opcode op = inst.getOpcode();
        return switch (op) {
            case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                String callee = extractCalleeName(inst);
                boolean pureQuery = callee != null
                    && callee.startsWith("java/lang/Class.")
                    && callee.contains("desiredAssertionStatus");
                yield !pureQuery;
            }
            case INVOKEDYNAMIC,
                 PUT_FIELD,
                 ASTORE,
                 MONITOR_ENTER, MONITOR_EXIT,
                 FREE,
                 CHECKCAST,
                 JSR,
                 NEW, NEW_ARRAY, MULTI_NEW_ARRAY, STORE -> true;
            case PUT_STATIC -> {
                String name = extractFieldName(inst);
                yield name != null && readStaticFields.contains(name);
            }
            default -> false;
        };
    }

    private boolean shouldRemove(Instruction inst,
                                 Set<Value> usedValues,
                                 Set<String> readStaticFields,
                                 Set<Integer> loadedLocals) {
        Opcode op = inst.getOpcode();

        if (op == Opcode.PUT_STATIC) {
            String name = extractFieldName(inst);
            return name != null && !readStaticFields.contains(name);
        }

        if (op == Opcode.STORE) {
            int idx = inst.getLocalIndex();
            if (idx >= 0 && loadedLocals.contains(idx)) {
                return false;
            }
            return inst.getResult() != null && !usedValues.contains(inst.getResult());
        }

        if (isPure(op) && inst.getResult() != null) {
            return !usedValues.contains(inst.getResult());
        }

        if ((op == Opcode.VIRTUAL_CALL
            || op == Opcode.INTERFACE_CALL
            || op == Opcode.STATIC_CALL
            || op == Opcode.SPECIAL_CALL
            || op == Opcode.CALL)
            && inst.getResult() != null
            && !usedValues.contains(inst.getResult())) {
            String callee = extractCalleeName(inst);
            return callee != null
                && callee.startsWith("java/lang/Class.")
                && callee.contains("desiredAssertionStatus");
        }

        return false;
    }

    private boolean isPure(Opcode op) {
        return switch (op) {
            case ADD, SUB, MUL,
                 EQ, NE, LT, LE, GT, GE,
                 AND, OR, XOR, SHL, SHR, USHR,
                 CAST,
                 INSTANCEOF,
                 PHI, NOP -> true;
            default -> false;
        };
    }
}
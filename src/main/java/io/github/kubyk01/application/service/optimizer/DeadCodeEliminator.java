package io.github.kubyk01.application.service.optimizer;

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
import io.github.kubyk01.domain.ir.Value;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
            Set<Value> usedValues = collectUsedValues(readStaticFields);

            for (Function func : module.getFunctions()) {
                if (func.getEntryBlock() == null) continue;
                for (BasicBlock block : func.getBlocks()) {
                    List<Instruction> instructions = block.getInstructions();
                    for (int i = 0; i < instructions.size(); i++) {
                        Instruction inst = instructions.get(i);
                        if (shouldRemove(inst, usedValues, readStaticFields)) {
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
    }

    // ------------------------------------------------------------------
    //  Static field read collection
    // ------------------------------------------------------------------

    private Set<String> collectReadStaticFields() {
        Set<String> fields = new HashSet<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst.getOpcode() == Opcode.GET_STATIC) {
                        String name = extractFieldName(inst, 0);
                        if (name != null) fields.add(name);
                    }
                }
            }
        }
        return fields;
    }

    private String extractFieldName(Instruction inst, int idx) {
        if (inst.getOperands().size() <= idx) return null;
        Value v = inst.getOperands().get(idx);
        if (v instanceof Constant c && c.getType().isReference()) {
            return c.getValue().toString();
        }
        return null;
    }

    /**
     * Extracts the callee name from a call instruction. The operand layout
     * depends on the opcode:
     *
     * <ul>
     *   <li>{@code VIRTUAL_CALL} / {@code INTERFACE_CALL} / {@code SPECIAL_CALL}:
     *       {@code [receiver, calleeConst, args...]}</li>
     *   <li>{@code STATIC_CALL} / {@code CALL}:
     *       {@code [calleeConst, args...]}</li>
     * </ul>
     */
    private String extractCalleeName(Instruction inst) {
        Opcode op = inst.getOpcode();
        int idx = (op == Opcode.VIRTUAL_CALL
            || op == Opcode.INTERFACE_CALL
            || op == Opcode.SPECIAL_CALL) ? 1 : 0;
        if (inst.getOperands().size() > idx) {
            Value v = inst.getOperands().get(idx);
            if (v instanceof Constant c && c.getType().isReference()) {
                return c.getValue().toString();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  Live value analysis
    // ------------------------------------------------------------------

    private Set<Value> collectUsedValues(Set<String> readStaticFields) {
        Set<Value> used = new HashSet<>();
        Deque<Value> worklist = new ArrayDeque<>();

        // Seed: operands of root (side-effecting) instructions and terminator operands.
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

        // Transitive closure: if a used value is produced by an instruction,
        // its operands become used as well.
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

    /**
     * A root instruction is one whose side effects must be preserved even if
     * its result is unused. Everything not marked here is a candidate for
     * removal if its result is unused.
     */
    private boolean isRoot(Instruction inst, Set<String> readStaticFields) {
        Opcode op = inst.getOpcode();
        return switch (op) {
            case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                // A call whose result is unused is dead only if the target has
                // no observable side effects. The JVM's assertion-status query
                // is a pure read of VM state — whitelist it so that
                // `assert` machinery inside <clinit> blocks does not force
                // us to emit a call to a method that is not actually in the
                // reachable set (and whose vtable slot is therefore null).
                // Any other call remains a root (conservative).
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
                 NEW, NEW_ARRAY, MULTI_NEW_ARRAY -> true;
            case PUT_STATIC -> {
                String name = extractFieldName(inst, 0);
                yield name != null && readStaticFields.contains(name);
            }
            default -> false;
        };
    }

    private boolean shouldRemove(Instruction inst,
                                 Set<Value> usedValues,
                                 Set<String> readStaticFields) {
        Opcode op = inst.getOpcode();

        if (op == Opcode.PUT_STATIC) {
            String name = extractFieldName(inst, 0);
            return name != null && !readStaticFields.contains(name);
        }

        if (op == Opcode.STORE) {
            return inst.getResult() != null && !usedValues.contains(inst.getResult());
        }

        if (isPure(op) && inst.getResult() != null) {
            return !usedValues.contains(inst.getResult());
        }

        // Whitelisted pure query calls: drop them if the result is unused.
        if ((op == Opcode.VIRTUAL_CALL
            || op == Opcode.INTERFACE_CALL
            || op == Opcode.STATIC_CALL
            || op == Opcode.SPECIAL_CALL
            || op == Opcode.CALL)
            && inst.getResult() != null
            && !usedValues.contains(inst.getResult())) {
            String callee = extractCalleeName(inst);
            if (callee != null
                && callee.startsWith("java/lang/Class.")
                && callee.contains("desiredAssertionStatus")) {
                return true;
            }
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
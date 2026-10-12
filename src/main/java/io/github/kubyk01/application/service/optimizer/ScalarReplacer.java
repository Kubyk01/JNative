package io.github.kubyk01.application.service.optimizer;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.LookupSwitchTerminator;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.TableSwitchTerminator;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.Value;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;

/**
 * Replaces objects that do not escape and have no observable side effects
 * with a set of local variables (scalar replacement).
 * Removes NEW, GET_FIELD, PUT_FIELD instructions, replacing them with operations on temporary variables.
 */
@Slf4j
@RequiredArgsConstructor
public class ScalarReplacer {

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final EscapeAnalysisResult escapeResult;
    @Getter
    private final Map<AllocationSite, Value> siteToValue;

    // Mapping AllocationSite -> set of fields (fieldName -> current value)
    private final Map<AllocationSite, Map<String, Value>> fieldValues = new HashMap<>();

    public void replace() {
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;

            // ------------------------------------------------------------------
            // First pass: every NEW whose result is a STACK-status object
            // but which is used as a real object — i.e. as a receiver of a
            // call — cannot be scalar-replaced (there is no way to give the
            // callee a real object pointer without an allocation). Mark
            // such a NEW as stack-allocated. The codegen will emit an
            // alloca in the function's entry block instead of a calloc,
            // and no destructor will be inserted, because the alloca's
            // memory is released automatically when the function returns.
            //
            // The condition `isUsedAsObject(inst)` is deliberate and is the
            // same predicate that gates scalar replacement below. An object
            // that passes this check has at least one call use that is not
            // a destructor call, so scalar replacement is not applicable.
            // ------------------------------------------------------------------
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst.getOpcode() != Opcode.NEW) continue;
                    Value result = inst.getResult();
                    if (result == null) continue;

                    PointsToSet pts = aliasResult.getPointsTo(result);
                    if (pts.getSites().isEmpty()) continue;

                    boolean allStack = true;
                    for (AllocationSite site : pts.getSites()) {
                        if (escapeResult.getSiteStatus(site) != EscapeStatus.STACK) {
                            allStack = false;
                            break;
                        }
                    }
                    if (!allStack) continue;

                    if (isUsedAsObject(inst)) {
                        inst.setStackAllocated(true);
                    }
                }
            }

            // ------------------------------------------------------------------
            // Second pass: scalar replacement for STACK objects that are
            // never used as opaque references. These are the objects whose
            // entire observable state is their fields, so the object can be
            // removed and the fields turned into individual SSA values.
            // ------------------------------------------------------------------
            Set<AllocationSite> candidates = collectCandidates(func);
            if (candidates.isEmpty()) continue;

            for (BasicBlock block : func.getBlocks()) {
                List<Instruction> instructions = block.getInstructions();
                List<Instruction> newInstructions = new ArrayList<>();
                for (Instruction inst : instructions) {
                    if (processInstruction(inst, candidates)) {
                        // Instruction was replaced or removed.
                    } else {
                        newInstructions.add(inst);
                    }
                }
                block.getInstructions().clear();
                block.getInstructions().addAll(newInstructions);
            }

            cleanup(func);
        }
    }

    private boolean isUsedAsObject(Instruction newInst) {
        Value result = newInst.getResult();
        if (result == null) return false;
        BasicBlock parent = newInst.getParent();
        if (parent == null) return false;
        Function func = parent.getFunction();
        if (func == null) return false;

        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst == newInst) continue;
                Opcode op = inst.getOpcode();

                boolean isCall = switch (op) {
                    case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> true;
                    default -> false;
                };
                if (!isCall) continue;

                // ------------------------------------------------------------------
                // Destructor calls are clean-up, not genuine object uses.
                //
                // The DestructorInserter runs before this pass and, for every
                // object it decides to free, emits a
                //     call void @__destruct_<Type>(obj)
                // at the object's death point. That call holds the object as
                // its argument and would otherwise make `isUsedAsObject`
                // return true, which in turn would prevent scalar
                // replacement of any object that the rest of the pipeline
                // had already classified as STACK. The destructor call is
                // removed by DestructorSimplifier when the object no longer
                // has a NEW, so it is safe to ignore here.
                // ------------------------------------------------------------------
                if (op == Opcode.STATIC_CALL) {
                    String callee = extractCalleeName(inst);
                    if (callee != null
                        && (callee.startsWith("__destruct_")
                            || callee.startsWith("__destruct_array_"))) {
                        continue;
                    }
                }

                // Every operand, including the receiver slot at index 0 for
                // VIRTUAL_CALL / INTERFACE_CALL / SPECIAL_CALL.
                for (Value operand : inst.getOperands()) {
                    if (operand == result) return true;
                }
            }
        }
        return false;
    }

    private Set<AllocationSite> collectCandidates(Function func) {
        Set<AllocationSite> candidates = new HashSet<>();
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                Opcode op = inst.getOpcode();

                if (op == Opcode.NEW_ARRAY || op == Opcode.MULTI_NEW_ARRAY) {
                    continue;
                }
                if (op != Opcode.NEW) {
                    continue;
                }

                Value result = inst.getResult();
                if (result == null) continue;

                PointsToSet pts = aliasResult.getPointsTo(result);
                for (AllocationSite site : pts.getSites()) {
                    EscapeStatus status = escapeResult.getSiteStatus(site);
                    if (status == EscapeStatus.STACK && !isUsedAsObject(inst)) {
                        candidates.add(site);
                    }
                }
            }
        }
        return candidates;
    }

    private boolean processInstruction(Instruction inst,
                                       Set<AllocationSite> candidates) {
        Opcode op = inst.getOpcode();

        if (op == Opcode.NEW) {
            Value result = inst.getResult();
            if (result == null) return false;
            PointsToSet pts = aliasResult.getPointsTo(result);
            for (AllocationSite site : pts.getSites()) {
                if (candidates.contains(site)) {
                    // Initialize the field map for this site
                    fieldValues.put(site, new HashMap<>());
                    // Remove the instruction (replace with NOP)
                    inst.setOpcode(Opcode.NOP);
                    inst.getOperands().clear();
                    inst.setResult(null);
                    return true;
                }
            }
            return false;
        }

        if (op == Opcode.NEW_ARRAY || op == Opcode.MULTI_NEW_ARRAY) {
            return false;
        }

        if (op == Opcode.GET_FIELD) {
            if (inst.getOperands().size() >= 2) {
                Value base = inst.getOperands().getFirst();
                String fieldName = extractFieldName(inst);
                PointsToSet pts = aliasResult.getPointsTo(base);
                for (AllocationSite site : pts.getSites()) {
                    if (candidates.contains(site)) {
                        Map<String, Value> fieldMap = fieldValues.get(site);
                        if (fieldMap != null && fieldMap.containsKey(fieldName)) {
                            // Replace the result of GET_FIELD with the value stored for the field
                            Value replacement = fieldMap.get(fieldName);
                            // Replace all uses of the result
                            replaceUses(inst.getResult(), replacement);
                            inst.setOpcode(Opcode.NOP);
                            inst.getOperands().clear();
                            inst.setResult(null);
                            return true;
                        }
                    }
                }
            }
        } else if (op == Opcode.PUT_FIELD) {
            if (inst.getOperands().size() >= 3) {
                Value base = inst.getOperands().get(0);
                Value rhs = inst.getOperands().get(2);
                String fieldName = extractFieldName(inst);
                PointsToSet pts = aliasResult.getPointsTo(base);
                for (AllocationSite site : pts.getSites()) {
                    if (candidates.contains(site)) {
                        Map<String, Value> fieldMap = fieldValues.get(site);
                        if (fieldMap != null) {
                            fieldMap.put(fieldName, rhs);
                            inst.setOpcode(Opcode.NOP);
                            inst.getOperands().clear();
                            inst.setResult(null);
                            return true;
                        }
                    }
                }
            }
        }
        // Leave other instructions untouched
        return false;
    }

    private void replaceUses(Value oldVal, Value newVal) {
        // Find all instructions using oldVal and replace their operands with newVal
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    for (int i = 0; i < inst.getOperands().size(); i++) {
                        if (inst.getOperands().get(i) == oldVal) {
                            inst.getOperands().set(i, newVal);
                        }
                    }
                }
                // Also terminator instructions
                Terminator term = block.getTerminator();
                if (term != null) {
                    switch (term) {
                        case ReturnTerminator returnTerminator -> {
                            if (returnTerminator.getValue() == oldVal) {
                                returnTerminator.setValue(newVal);
                            }
                        }
                        case CondBranchTerminator condBranchTerminator -> {
                            if (condBranchTerminator.getCondition() == oldVal) {
                                condBranchTerminator.setCondition(newVal);
                            }
                        }
                        case ThrowTerminator throwTerminator -> {
                            if (throwTerminator.getException() == oldVal) {
                                throwTerminator.setException(newVal);
                            }
                        }
                        case LookupSwitchTerminator lookupSwitchTerminator -> {
                            if (lookupSwitchTerminator.getKey() == oldVal) {
                                lookupSwitchTerminator.setKey(newVal);
                            }
                        }
                        case TableSwitchTerminator tableSwitchTerminator -> {
                            if (tableSwitchTerminator.getKey() == oldVal) {
                                tableSwitchTerminator.setKey(newVal);
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
        }
    }

    private void cleanup(Function func) {
        // Remove NOP instructions and dead temporaries
        for (BasicBlock block : func.getBlocks()) {
            block.getInstructions().removeIf(inst ->
                    inst.getOpcode() == Opcode.NOP && inst.getResult() == null);
        }
    }
}

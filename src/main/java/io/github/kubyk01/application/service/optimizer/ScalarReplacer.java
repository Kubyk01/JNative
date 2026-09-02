package io.github.kubyk01.application.service.optimizer;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
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
            // Collect all allocation sites eligible for replacement (STACK and not used as a whole object)
            Set<AllocationSite> candidates = collectCandidates(func);
            if (candidates.isEmpty()) continue;

            // Iterate over blocks and instructions, performing replacement
            for (BasicBlock block : func.getBlocks()) {
                List<Instruction> instructions = block.getInstructions();
                List<Instruction> newInstructions = new ArrayList<>();
                for (Instruction inst : instructions) {
                    if (processInstruction(inst, candidates)) {
                        // Instruction processed (replaced or removed)
                    } else {
                        newInstructions.add(inst);
                    }
                }
                block.getInstructions().clear();
                block.getInstructions().addAll(newInstructions);
            }

            // Remove all NOP instructions and dead temporaries
            cleanup(func);
        }
    }

    private Set<AllocationSite> collectCandidates(Function func) {
        Set<AllocationSite> candidates = new HashSet<>();
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() == Opcode.NEW || inst.getOpcode() == Opcode.NEW_ARRAY || inst.getOpcode() == Opcode.MULTI_NEW_ARRAY) {
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
        }
        return candidates;
    }

    private boolean isUsedAsObject(Instruction newInst) {
        // Check whether the result of NEW is used as an object (e.g., passed to a method)
        // For simplicity, assume that if there is a method call with this object as receiver or argument,
        // the object is not eligible for scalar replacement.
        Value result = newInst.getResult();
        if (result == null) return false;
        // If used in GET_FIELD or PUT_FIELD, that is fine (we will replace them).
        // If used in CALL as receiver or argument, do not replace.
        BasicBlock parent = newInst.getParent();
        if (parent == null) return false;
        Function func = parent.getFunction();
        if (func == null) return false;

        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                for (Value operand : inst.getOperands()) {
                    if (operand == result) {
                        Opcode op = inst.getOpcode();
                        if (op == Opcode.CALL || op == Opcode.VIRTUAL_CALL || op == Opcode.INTERFACE_CALL
                                || op == Opcode.STATIC_CALL || op == Opcode.SPECIAL_CALL) {
                            return true;
                        }
                    }
                }
                // GET_FIELD/PUT_FIELD with this object do not prevent scalar replacement – they will be replaced
            }
        }
        return false;
    }

    private boolean processInstruction(Instruction inst,
                                       Set<AllocationSite> candidates) {
        Opcode op = inst.getOpcode();
        if (op == Opcode.NEW || op == Opcode.NEW_ARRAY || op == Opcode.MULTI_NEW_ARRAY) {
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
        } else if (op == Opcode.GET_FIELD) {
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

    private String extractFieldName(Instruction inst) {
        if (inst.getOperands().size() >= 2) {
            Value v = inst.getOperands().get(1);
            if (v instanceof Constant c && c.getType().isReference()) {
                String val = c.getValue().toString();
                int dot = val.lastIndexOf('.');
                return dot >= 0 ? val.substring(dot + 1) : val;
            }
        }
        return "unknown";
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

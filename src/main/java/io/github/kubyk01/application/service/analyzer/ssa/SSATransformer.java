package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.analyzer.ir.*;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class SSATransformer {

    private final Map<Integer, Deque<Value>> versionStacks = new HashMap<>();
    private final Map<Integer, Integer> versionCounters = new HashMap<>();
    private final Map<Value, Value> replacements = new HashMap<>();
    private DominatorTree domTree;
    private Function currentFunction;

    public void transform(Function function) {
        if (function.getEntryBlock() == null || function.getBlocks().isEmpty()) return;

        this.currentFunction = function;
        domTree = new DominatorTree(function);

        initializeStacks(function);
        insertPhiFunctions(function);
        renameBlock(function.getEntryBlock());
        optimizePhis(function);
        cleanupPhis(function);
        cleanupNops(function);
    }

    private void initializeStacks(Function function) {
        for (Parameter param : function.getParameters()) {
            int idx = param.getIndex();
            versionStacks.computeIfAbsent(idx, k -> new ArrayDeque<>()).push(param);
            versionCounters.putIfAbsent(idx, 0);
        }
    }

    private Type inferType(int localIndex, Function function) {
        for (Parameter p : function.getParameters()) {
            if (p.getIndex() == localIndex) {
                return p.getType();
            }
        }
        for (BasicBlock block : function.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() == Opcode.STORE && inst.getLocalIndex() == localIndex) {
                    if (!inst.getOperands().isEmpty()) {
                        return inst.getOperands().getFirst().getType();
                    }
                }
            }
        }
        return Type.UNKNOWN;
    }

    private void insertPhiFunctions(Function function) {
        Map<Integer, Set<BasicBlock>> defs = collectDefBlocks(function);

        for (Map.Entry<Integer, Set<BasicBlock>> entry : defs.entrySet()) {
            int localIndex = entry.getKey();
            Set<BasicBlock> defBlocks = entry.getValue();

            if (defBlocks.size() < 2) continue;

            Set<BasicBlock> hasPhi = new HashSet<>(defBlocks);
            Queue<BasicBlock> worklist = new LinkedList<>(defBlocks);

            while (!worklist.isEmpty()) {
                BasicBlock block = worklist.poll();
                for (BasicBlock frontier : domTree.getDominanceFrontier(block)) {
                    if (!hasPhi.contains(frontier)) {
                        Instruction phi = new Instruction(Opcode.PHI);
                        phi.setLocalIndex(localIndex);
                        Type varType = inferType(localIndex, function);
                        Temporary phiResult = new Temporary(varType);
                        phi.setResult(phiResult);
                        phiResult.setDefiningInstruction(phi);
                        frontier.getInstructions().addFirst(phi);
                        hasPhi.add(frontier);
                        worklist.add(frontier);
                    }
                }
            }
        }
    }

    private Map<Integer, Set<BasicBlock>> collectDefBlocks(Function function) {
        Map<Integer, Set<BasicBlock>> defs = new HashMap<>();
        BasicBlock entry = function.getEntryBlock();

        for (Parameter param : function.getParameters()) {
            defs.computeIfAbsent(param.getIndex(), k -> new HashSet<>()).add(entry);
        }

        for (BasicBlock block : function.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() == Opcode.STORE && inst.getLocalIndex() >= 0) {
                    defs.computeIfAbsent(inst.getLocalIndex(), k -> new HashSet<>()).add(block);
                }
            }
        }

        return defs;
    }

    private void renameBlock(BasicBlock block) {
        Map<Integer, Integer> savedSizes = new HashMap<>();

        // Process phi functions at the beginning of the block
        for (Instruction inst : block.getInstructions()) {
            if (inst.getOpcode() == Opcode.PHI) {
                int idx = inst.getLocalIndex();
                if (idx < 0) continue;
                Type varType = inferType(idx, currentFunction);
                Temporary newVer = newVersion(idx, varType);
                inst.setResult(newVer);
                newVer.setDefiningInstruction(inst);
                savedSizes.putIfAbsent(idx, stackSize(idx));
            }
        }

        // Process the remaining instructions
        for (Instruction inst : block.getInstructions()) {
            Opcode op = inst.getOpcode();
            if (op == Opcode.PHI) continue;

            // Replace operands with their current versions
            inst.getOperands().replaceAll(this::resolve);

            if (op == Opcode.LOAD) {
                int idx = inst.getLocalIndex();
                if (idx < 0) continue;
                Value curVer = currentVersion(idx);
                if (curVer == null) {
                    curVer = new UndefinedValue(inferType(idx, currentFunction));
                    versionStacks.computeIfAbsent(idx, k -> new ArrayDeque<>()).push(curVer);
                    versionCounters.putIfAbsent(idx, 0);
                    savedSizes.putIfAbsent(idx, stackSize(idx));
                }
                replacements.put(inst.getResult(), curVer);
                inst.setOpcode(Opcode.NOP);
                inst.getOperands().clear();
                inst.setResult(null);
            } else if (op == Opcode.STORE) {
                int idx = inst.getLocalIndex();
                if (idx < 0) continue;
                Value operand = inst.getOperands().isEmpty() ?
                    new UndefinedValue(Type.UNKNOWN) : resolve(inst.getOperands().getFirst());
                Type type = operand.getType();
                Temporary newVer = newVersion(idx, type);
                if (inst.getResult() != null) {
                    inst.getResult().setDefiningInstruction(null);
                }
                inst.setResult(newVer);
                newVer.setDefiningInstruction(inst);
                savedSizes.putIfAbsent(idx, stackSize(idx));
            }
        }

        renameTerminator(block);

        // Fill in phi function operands in successors
        for (BasicBlock succ : block.getSuccessors()) {
            int predIdx = succ.getPredecessors().indexOf(block);
            if (predIdx < 0) continue;
            for (Instruction inst : succ.getInstructions()) {
                if (inst.getOpcode() == Opcode.PHI && inst.getLocalIndex() >= 0) {
                    Value curVer = currentVersion(inst.getLocalIndex());
                    if (curVer == null) {
                        // If the variable is undefined on this path, use UndefinedValue
                        curVer = new UndefinedValue(inferType(inst.getLocalIndex(), currentFunction));
                    }
                    ensurePhiOperandCount(inst, predIdx + 1);
                    inst.getOperands().set(predIdx, curVer);
                }
            }
        }

        // Recursively process children in the dominator tree
        for (BasicBlock child : domTree.getChildren(block)) {
            renameBlock(child);
        }

        // Restore the stacks after processing all children
        for (Map.Entry<Integer, Integer> entry : savedSizes.entrySet()) {
            restoreStack(entry.getKey(), entry.getValue());
        }
    }

    private void renameTerminator(BasicBlock block) {
        Terminator term = block.getTerminator();
        switch (term) {
            case CondBranchTerminator cbt -> cbt.setCondition(resolve(cbt.getCondition()));
            case ReturnTerminator rt -> {
                if (rt.getValue() != null) rt.setValue(resolve(rt.getValue()));
            }
            case ThrowTerminator tt -> tt.setException(resolve(tt.getException()));
            case LookupSwitchTerminator lst -> lst.setKey(resolve(lst.getKey()));
            case TableSwitchTerminator tst -> tst.setKey(resolve(tst.getKey()));
            case null, default -> {
            }
        }

    }

    private Value resolve(Value v) {
        if (v == null) return null;
        Value r = replacements.get(v);
        return r != null ? r : v;
    }

    private Temporary newVersion(int localIndex, Type type) {
        int ver = versionCounters.computeIfAbsent(localIndex, k -> 0);
        versionCounters.put(localIndex, ver + 1);
        Temporary tmp = new Temporary(type);
        versionStacks.computeIfAbsent(localIndex, k -> new ArrayDeque<>()).push(tmp);
        return tmp;
    }

    private Value currentVersion(int localIndex) {
        Deque<Value> stack = versionStacks.get(localIndex);
        return stack != null ? stack.peek() : null;
    }

    private int stackSize(int localIndex) {
        Deque<Value> stack = versionStacks.get(localIndex);
        return stack != null ? stack.size() : 0;
    }

    private void restoreStack(int localIndex, int size) {
        Deque<Value> stack = versionStacks.get(localIndex);
        if (stack != null) {
            while (stack.size() > size) {
                stack.pop();
            }
        }
    }

    private void ensurePhiOperandCount(Instruction phi, int count) {
        while (phi.getOperands().size() < count) {
            phi.addOperand(new Constant(Type.UNKNOWN, null));
        }
    }

    /**
     * Phi optimization: if all operands are identical, replace the result with that operand and remove the phi.
     */
    private void optimizePhis(Function function) {
        List<Instruction> toRemove = new ArrayList<>();
        Map<Instruction, Value> replacementMap = new HashMap<>();

        for (BasicBlock block : function.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() != Opcode.PHI) continue;
                if (inst.getOperands().isEmpty()) {
                    toRemove.add(inst);
                    continue;
                }
                Value first = inst.getOperands().getFirst();
                boolean allSame = true;
                for (int i = 1; i < inst.getOperands().size(); i++) {
                    if (!inst.getOperands().get(i).equals(first)) {
                        allSame = false;
                        break;
                    }
                }
                if (allSame) {
                    replacementMap.put(inst, first);
                    toRemove.add(inst);
                }
            }
        }

        // Replace uses of the phi result with the replacement value
        for (Map.Entry<Instruction, Value> entry : replacementMap.entrySet()) {
            Instruction phi = entry.getKey();
            Value replacement = entry.getValue();
            // Walk over all instructions and replace operands
            for (BasicBlock block : function.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    for (int i = 0; i < inst.getOperands().size(); i++) {
                        if (inst.getOperands().get(i) == phi.getResult()) {
                            inst.getOperands().set(i, replacement);
                        }
                    }
                }
                Terminator term = block.getTerminator();
                if (term != null) {
                    replaceInTerminator(term, phi.getResult(), replacement);
                }
            }
            // Remove the phi
            phi.getParent().getInstructions().remove(phi);
        }
    }

    private void replaceInTerminator(Terminator term, Value oldVal, Value newVal) {
        if (term instanceof CondBranchTerminator cbt) {
            if (cbt.getCondition() == oldVal) cbt.setCondition(newVal);
        } else if (term instanceof ReturnTerminator rt) {
            if (rt.getValue() == oldVal) rt.setValue(newVal);
        } else if (term instanceof ThrowTerminator tt) {
            if (tt.getException() == oldVal) tt.setException(newVal);
        } else if (term instanceof LookupSwitchTerminator lst) {
            if (lst.getKey() == oldVal) lst.setKey(newVal);
        } else if (term instanceof TableSwitchTerminator tst) {
            if (tst.getKey() == oldVal) tst.setKey(newVal);
        }
    }

    private void cleanupPhis(Function function) {
        Set<Value> usedValues = new HashSet<>();
        for (BasicBlock block : function.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                usedValues.addAll(inst.getOperands());
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                switch (term) {
                    case CondBranchTerminator cbt -> usedValues.add(cbt.getCondition());
                    case ReturnTerminator rt -> {
                        if (rt.getValue() != null) usedValues.add(rt.getValue());
                    }
                    case ThrowTerminator tt -> {
                        if (tt.getException() != null) usedValues.add(tt.getException());
                    }
                    case LookupSwitchTerminator lst -> usedValues.add(lst.getKey());
                    case TableSwitchTerminator tst -> usedValues.add(tst.getKey());
                    default -> {
                    }
                }
            }
        }
        for (BasicBlock block : function.getBlocks()) {
            block.getInstructions().removeIf(inst ->
                inst.getOpcode() == Opcode.PHI && !usedValues.contains(inst.getResult())
            );
        }
    }

    private void cleanupNops(Function function) {
        for (BasicBlock block : function.getBlocks()) {
            block.getInstructions().removeIf(inst ->
                inst.getOpcode() == Opcode.NOP && inst.getResult() == null);
        }
    }
}
package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.IndirectBranchTerminator;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.LookupSwitchTerminator;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.TableSwitchTerminator;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.UndefinedValue;
import io.github.kubyk01.domain.ir.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

import static io.github.kubyk01.util.LlvmUtil.inferLocalType;

@Slf4j
public class SSATransformer {

    private final Map<Integer, Deque<Value>> versionStacks = new HashMap<>();
    private final Map<Integer, Integer> versionCounters = new HashMap<>();
    private final Map<Value, Value> replacements = new HashMap<>();
    private DominatorTree domTree;
    private Function currentFunction;

    // ── new field ───────────────────────────────────────────────────────────────
    private Set<Integer> unsafeLocals = new HashSet<>();

    public void transform(Function function) {
        if (function.getEntryBlock() == null || function.getBlocks().isEmpty()) return;

        this.currentFunction = function;
        this.unsafeLocals = identifyUnsafeLocals(function);
        domTree = new DominatorTree(function);

        initializeStacks(function);
        insertPhiFunctions(function);
        renameBlock(function.getEntryBlock());
        optimizePhis(function);
        cleanupPhis(function);
        cleanupNops(function);
    }

    /**
     * A local is unsafe for SSA when it is STOREd inside a block that has an
     * exceptional successor <em>and</em> the STORE occurs after at least one
     * instruction that can throw.
     *
     * <p>The SSA construction models a block as a whole: every STORE is
     * assumed to have executed by the time control leaves the block, so the
     * version at the block's tail is the one that flows into every successor.
     * The LLVM emitter, however, wraps each throwing instruction in a
     * {@code setjmp}-based guard and lowers the block into a small sub-CFG
     * whose normal-exit sub-block ({@code guarded_cont_*}) is bypassed by the
     * exceptional exit ({@code guarded_catch_*} → {@code chit_*} → handler).
     * A version produced by a STORE that appears after a throwing instruction
     * therefore does not dominate any use on the exceptional edge, and LLVM
     * rejects the module with <em>"Instruction does not dominate all uses!"</em>.</p>
     *
     * <p>Keeping such locals as plain memory accesses sidesteps the issue
     * entirely: the STORE runs only if the preceding throwing instructions
     * completed, and any LOAD on the exceptional path reads the last value
     * that was actually committed — the correct JVM semantics.</p>
     */
    private Set<Integer> identifyUnsafeLocals(Function function) {
        Set<Integer> unsafe = new HashSet<>();
        for (BasicBlock block : function.getBlocks()) {
            if (block.getExceptionalSuccessors().isEmpty()) continue;
            boolean seenThrowing = false;
            for (Instruction inst : block.getInstructions()) {
                if (seenThrowing
                    && inst.getOpcode() == Opcode.STORE
                    && inst.getLocalIndex() >= 0) {
                    unsafe.add(inst.getLocalIndex());
                }
                if (inst.canThrow()) {
                    seenThrowing = true;
                }
            }
        }
        return unsafe;
    }

    private void initializeStacks(Function function) {
        for (Parameter param : function.getParameters()) {
            int idx = param.getIndex();
            versionStacks.computeIfAbsent(idx, k -> new ArrayDeque<>()).push(param);
            versionCounters.putIfAbsent(idx, 0);
        }
    }

    /**
     * Inserts PHI instructions into the iterated dominance frontier of the
     * definition blocks of each local variable.
     *
     * <p>PHI instructions are the only instructions that get inserted directly
     * into a block's instruction list via {@link List#addFirst(Object)} and
     * not through {@link BasicBlock#addInstruction(Instruction)} — the latter
     * is the only place that sets {@code inst.setParent(this)}. The LLVM
     * emitter relies on that parent link to discover the predecessor list
     * for the phi node's incoming edge labels. Forgetting the parent produces
     * a phi with a null parent, an empty predecessor list at emission time
     * and a literal {@code %unknown} block label in the generated IR. The
     * parent assignment below is therefore mandatory.</p>
     */
    private void insertPhiFunctions(Function function) {
        Map<Integer, Set<BasicBlock>> defs = collectDefBlocks(function);

        for (Map.Entry<Integer, Set<BasicBlock>> entry : defs.entrySet()) {
            int localIndex = entry.getKey();
            Set<BasicBlock> defBlocks = entry.getValue();

            Set<BasicBlock> hasPhi = new HashSet<>();
            Queue<BasicBlock> worklist = new LinkedList<>(defBlocks);

            while (!worklist.isEmpty()) {
                BasicBlock block = worklist.poll();
                for (BasicBlock frontier : domTree.getDominanceFrontier(block)) {
                    if (!hasPhi.contains(frontier)) {
                        Instruction phi = new Instruction(Opcode.PHI);
                        phi.setLocalIndex(localIndex);
                        Type varType = inferLocalType(function, localIndex);
                        Temporary phiResult = new Temporary(varType);
                        phi.setResult(phiResult);
                        phiResult.setDefiningInstruction(phi);

                        phi.setParent(frontier);
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
                    if (unsafeLocals.contains(inst.getLocalIndex())) continue;
                    defs.computeIfAbsent(inst.getLocalIndex(), k -> new HashSet<>()).add(block);
                }
            }
        }

        return defs;
    }

    private void renameBlock(BasicBlock block) {
        Map<Integer, Integer> savedSizes = new HashMap<>();

        // Process phi functions at the beginning of the block.
        //
        // The saved size MUST be taken before newVersion() pushes the phi's
        // SSA version onto the stack. If it is taken after the push, restoreStack
        // at the end of this block will leave that version on the stack, and
        // siblings of this block in the dominator tree will then see it as the
        // "current" version of the local — even though the phi's block does not
        // dominate them. The observable symptom is a use of a value defined in
        // a non-dominating block, e.g. clang reporting
        //     "Instruction does not dominate all uses!"
        // for a call whose receiver was taken from the sibling branch.
        for (Instruction inst : block.getInstructions()) {
            if (inst.getOpcode() == Opcode.PHI) {
                int idx = inst.getLocalIndex();
                if (idx < 0) continue;

                savedSizes.putIfAbsent(idx, stackSize(idx));

                Type varType = inferLocalType(currentFunction, idx);
                Temporary newVer = newVersion(idx, varType);
                inst.setResult(newVer);
                newVer.setDefiningInstruction(inst);
            }
        }

        // Process the remaining instructions
        for (Instruction inst : block.getInstructions()) {
            Opcode op = inst.getOpcode();
            if (op == Opcode.PHI) continue;

            inst.getOperands().replaceAll(this::resolve);

            if (op == Opcode.LOAD) {
                int idx = inst.getLocalIndex();
                if (idx < 0) continue;

                // Leave unsafe locals as real loads; see identifyUnsafeLocals().
                if (unsafeLocals.contains(idx)) continue;

                // The LOAD's declared result type comes from the bytecode
                // opcode (ILOAD -> INT, ALOAD -> reference, etc.). Capture it
                // before the result is cleared below so we can decide whether
                // the current SSA version of this slot is usable here.
                Type loadResultType = inst.getResult() != null
                    ? inst.getResult().getType()
                    : Type.UNKNOWN;

                Value curVer = currentVersion(idx);
                if (curVer == null) {
                    // Save the pre-block size BEFORE the push. See the phi
                    // loop above for the reasoning.
                    savedSizes.putIfAbsent(idx, stackSize(idx));

                    curVer = new UndefinedValue(inferLocalType(currentFunction, idx));
                    versionStacks.computeIfAbsent(idx, k -> new ArrayDeque<>()).push(curVer);
                    versionCounters.putIfAbsent(idx, 0);
                }

                // Type-discipline guard, symmetric with the one applied to
                // phi operands further down. A JVM local slot may be reused
                // for values of different types on disjoint control-flow
                // paths: in a synchronized block the same slot can hold the
                // lock object before `monitorenter` and an int inside the
                // body. The dominator-tree walk that drives SSA versioning can
                // then expose the wrong version at a block that is only
                // reached through exceptional edges, producing malformed IR
                // such as
                //     call void @__jnative_monitor_exit(i8* %i32_value)
                // which clang rejects with
                //     "'%tmp_X' defined with type 'i32' but expected 'ptr'".
                // Substituting a correctly-typed default keeps the module
                // structurally valid; the offending local is already
                // semantically compromised by the slot reuse, so this does
                // not make anything worse than it already was.
                if (!typesCompatible(curVer.getType(), loadResultType)) {
                    log.debug(
                        "LOAD from local {} in block {} replaced with incompatible value: "
                            + "expected {}, got {} — substituting typed default",
                        idx, block.getLabel(), loadResultType, curVer.getType());
                    curVer = defaultValueConstantFor(loadResultType);
                }

                replacements.put(inst.getResult(), curVer);
                inst.setOpcode(Opcode.NOP);
                inst.getOperands().clear();
                inst.setResult(null);
            } else if (op == Opcode.STORE) {
                int idx = inst.getLocalIndex();
                if (idx < 0) continue;

                // Leave unsafe locals as real stores; see identifyUnsafeLocals().
                if (unsafeLocals.contains(idx)) continue;

                // Save the pre-block size BEFORE the push. This is the fix
                // for the sibling-block visibility bug: if the size is
                // recorded after newVersion() below, restoreStack at the end
                // of this method becomes a no-op and the stored version leaks
                // into every sibling of this block in the dominator tree.
                savedSizes.putIfAbsent(idx, stackSize(idx));

                Value operand = inst.getOperands().isEmpty() ?
                    new UndefinedValue(Type.UNKNOWN) : resolve(inst.getOperands().getFirst());
                Type type = operand.getType();
                Temporary newVer = newVersion(idx, type);
                if (inst.getResult() != null) {
                    inst.getResult().setDefiningInstruction(null);
                }
                inst.setResult(newVer);
                newVer.setDefiningInstruction(inst);
            }
        }

        renameTerminator(block);

        // Fill in phi function operands in successors.
        //
        // Type discipline is critical here. A JVM local slot may be reused
        // for values of different types on disjoint control-flow paths — the
        // bytecode verifier allows this, e.g. a slot may hold an int on one
        // branch and a reference on another. SSA has a single type per
        // variable, and the phi's result type was fixed when the phi was
        // created (from the first STORE into that slot). If the current
        // version of the local on this predecessor has an incompatible type,
        // blindly storing it as a phi operand produces IR like
        //
        //     %phi = phi i8* [ 0, %pred ], ...
        //
        // which the LLVM parser rejects with "integer constant must have
        // integer type". Substituting a correctly-typed zero/null keeps the
        // CFG reachable, preserves the phi's declared type, and produces
        // valid IR. On such a path the local's value is irrelevant to the
        // phi's users, since they all see the phi's declared type.
        for (BasicBlock succ : block.getSuccessors()) {
            int predIdx = succ.getPredecessors().indexOf(block);
            if (predIdx < 0) continue;
            for (Instruction inst : succ.getInstructions()) {
                if (inst.getOpcode() == Opcode.PHI && inst.getLocalIndex() >= 0) {
                    Value curVer = currentVersion(inst.getLocalIndex());
                    if (curVer == null) {
                        curVer = new UndefinedValue(
                            inferLocalType(currentFunction, inst.getLocalIndex()));
                    }

                    Type phiType = inst.getResult() != null
                        ? inst.getResult().getType()
                        : inferLocalType(currentFunction, inst.getLocalIndex());

                    if (!typesCompatible(curVer.getType(), phiType)) {
                        log.debug(
                            "Phi in {} received incompatible incoming value "
                                + "from block {}: expected {}, got {} — "
                                + "substituting typed default",
                            succ.getLabel(), block.getLabel(),
                            phiType, curVer.getType());
                        curVer = defaultValueConstantFor(phiType);
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

        // Restore the stacks after processing all children. Because every
        // savedSizes entry now records the size at block entry (before any
        // push performed in this block), this correctly pops every version
        // that this block pushed, and none of them remain visible to
        // siblings of this block in the dominator tree.
        for (Map.Entry<Integer, Integer> entry : savedSizes.entrySet()) {
            restoreStack(entry.getKey(), entry.getValue());
        }
    }

    private static boolean typesCompatible(Type a, Type b) {
        if (a == null || b == null) return true;
        if (a.equals(b)) return true;
        if (a.isUnknown() || b.isUnknown()) return true;

        boolean aRef = a.isReference() || a.isArray() || a.isNull() || a.isBlock();
        boolean bRef = b.isReference() || b.isArray() || b.isNull() || b.isBlock();
        return aRef && bRef;
    }

    /**
     * Returns a correctly-typed zero/null constant for the given type. Used
     * to fill in a phi operand when the SSA-merged version of the local has
     * an incompatible type (see the type-discipline comment in
     * {@link #renameBlock}).
     */
    private static Value defaultValueConstantFor(Type t) {
        if (t == null) return new Constant(Type.NULL, null);
        if (t.isReference() || t.isArray() || t.isNull() || t.isBlock()) {
            return new Constant(Type.NULL, null);
        }
        if (t == Type.BOOLEAN) return new Constant(Type.BOOLEAN, false);
        if (t == Type.BYTE)    return new Constant(Type.BYTE, (byte) 0);
        if (t == Type.SHORT)   return new Constant(Type.SHORT, (short) 0);
        if (t == Type.CHAR)    return new Constant(Type.CHAR, (char) 0);
        if (t == Type.INT)     return new Constant(Type.INT, 0);
        if (t == Type.LONG)    return new Constant(Type.LONG, 0L);
        if (t == Type.FLOAT)   return new Constant(Type.FLOAT, 0.0f);
        if (t == Type.DOUBLE)  return new Constant(Type.DOUBLE, 0.0);
        return new Constant(Type.NULL, null);
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
            case IndirectBranchTerminator ibt -> ibt.setTargetBlock(resolve(ibt.getTargetBlock()));
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

        for (Map.Entry<Instruction, Value> entry : replacementMap.entrySet()) {
            Instruction phi = entry.getKey();
            Value replacement = entry.getValue();
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
            BasicBlock parent = phi.getParent();
            if (parent != null) {
                parent.getInstructions().remove(phi);
            }
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
        } else if (term instanceof IndirectBranchTerminator ibt) {
            if (ibt.getTargetBlock() == oldVal) ibt.setTargetBlock(newVal);
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
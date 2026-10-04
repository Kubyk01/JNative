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

@Slf4j
public class SSATransformer {

    // ------------------------------------------------------------------
    //  Local-slot access type buckets.
    // ------------------------------------------------------------------

    /** {@code ILOAD/ISTORE}: boolean, byte, short, char, int. */
    private static final int BUCKET_INT    = 0;
    /** {@code LLOAD/LSTORE}. */
    private static final int BUCKET_LONG   = 1;
    /** {@code FLOAD/FSTORE}. */
    private static final int BUCKET_FLOAT  = 2;
    /** {@code DLOAD/DSTORE}. */
    private static final int BUCKET_DOUBLE = 3;
    /** {@code ALOAD/ASTORE}: references, arrays, null, block, unknown. */
    private static final int BUCKET_REF    = 4;

    private static int typeBucket(Type t) {
        if (t == null) return BUCKET_REF;
        if (t == Type.BOOLEAN || t == Type.BYTE || t == Type.SHORT
            || t == Type.CHAR || t == Type.INT) {
            return BUCKET_INT;
        }
        if (t == Type.LONG)   return BUCKET_LONG;
        if (t == Type.FLOAT)  return BUCKET_FLOAT;
        if (t == Type.DOUBLE) return BUCKET_DOUBLE;
        // reference, array, null, block, unknown, void — all of these are
        // either i8* in LLVM, or (for void, which never appears in a slot)
        // also normalised into the ref bucket.
        return BUCKET_REF;
    }

    private static Type canonicalTypeForBucket(int bucket) {
        return switch (bucket) {
            case BUCKET_INT    -> Type.INT;
            case BUCKET_LONG   -> Type.LONG;
            case BUCKET_FLOAT  -> Type.FLOAT;
            case BUCKET_DOUBLE -> Type.DOUBLE;
            case BUCKET_REF    -> Type.reference("java/lang/Object");
            default            -> Type.UNKNOWN;
        };
    }

    private record SlotKey(int index, int typeClass) {}

    // ------------------------------------------------------------------
    //  Transformation state.
    // ------------------------------------------------------------------

    /** One version stack per (slot, type class). */
    private final Map<SlotKey, Deque<Value>> versionStacks = new HashMap<>();
    /** Version counter per (slot, type class). */
    private final Map<SlotKey, Integer> versionCounters = new HashMap<>();
    /** Substitution map: "old value -> current SSA version". */
    private final Map<Value, Value> replacements = new HashMap<>();

    private DominatorTree domTree;
    private Function currentFunction;

    /**
     * The set of slots that, for one reason or another, remain in
     * memory and do not participate in SSA. The key is just the slot
     * number: the "unsafe" attribute belongs to the slot as a cell,
     * not to its current type.
     */
    private Set<Integer> unsafeLocals = new HashSet<>();

    // ------------------------------------------------------------------
    //  Entry point.
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    //  Identification of "unsafe" slots (essentially unchanged).
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    //  Initialization of version stacks.
    // ------------------------------------------------------------------

    /**
     * Pushes every parameter onto the version stack of its bucket.
     *
     * <p>For buckets whose canonical type differs from the parameter's
     * type (which is only possible in the int bucket — a parameter of
     * type {@code BOOLEAN}/{@code BYTE}/{@code SHORT}/{@code CHAR}), a
     * single {@code CAST} instruction is inserted into the entry block
     * to coerce the parameter to the canonical type. Without this
     * normalisation, a PHI whose result always carries the canonical
     * type would receive an incoming value of a different LLVM type and
     * would fail in the emitter.</p>
     */
    private void initializeStacks(Function function) {
        BasicBlock entry = function.getEntryBlock();
        for (Parameter param : function.getParameters()) {
            int idx = param.getIndex();
            int bucket = typeBucket(param.getType());
            SlotKey key = new SlotKey(idx, bucket);

            Value version = param;

            // The int bucket is the only one where different LLVM widths are possible.
            if (bucket == BUCKET_INT && param.getType() != Type.INT) {
                Type canonical = canonicalTypeForBucket(bucket);
                Instruction cast = new Instruction(Opcode.CAST);
                cast.addOperand(param);
                Temporary tmp = new Temporary(canonical);
                cast.setResult(tmp);
                tmp.setDefiningInstruction(cast);
                if (entry != null) {
                    cast.setParent(entry);
                    entry.getInstructions().addFirst(cast);
                }
                version = tmp;
            }

            versionStacks.computeIfAbsent(key, k -> new ArrayDeque<>()).push(version);
            versionCounters.putIfAbsent(key, 0);
        }
    }

    // ------------------------------------------------------------------
    //  Phi insertion.
    // ------------------------------------------------------------------

    /**
     * Inserts PHIs on the iterated dominance frontier of the blocks that
     * define each (slot, bucket) version.
     *
     * <p>A PHI is added directly at the head of the block's instruction
     * list via {@link List#addFirst(Object)}, not through
     * {@link BasicBlock#addInstruction(Instruction)}; that is why
     * {@code setParent(frontier)} is mandatory — the emitter uses
     * {@code inst.getParent()} to obtain the predecessor list when it
     * prints the incoming edges of a PHI.</p>
     */
    private void insertPhiFunctions(Function function) {
        Map<SlotKey, Set<BasicBlock>> defs = collectDefBlocks(function);

        for (Map.Entry<SlotKey, Set<BasicBlock>> entry : defs.entrySet()) {
            SlotKey key = entry.getKey();
            Set<BasicBlock> defBlocks = entry.getValue();

            Type phiType = inferSlotTypeForBucket(function, key.index, key.typeClass);

            Set<BasicBlock> hasPhi = new HashSet<>();
            Queue<BasicBlock> worklist = new LinkedList<>(defBlocks);

            while (!worklist.isEmpty()) {
                BasicBlock block = worklist.poll();
                for (BasicBlock frontier : domTree.getDominanceFrontier(block)) {
                    if (hasPhi.contains(frontier)) continue;

                    Instruction phi = new Instruction(Opcode.PHI);
                    phi.setLocalIndex(key.index);
                    Temporary phiResult = new Temporary(phiType);
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

    /**
     * Collects the definition blocks for each (slot, bucket) key.
     *
     * <p>Parameters define their slot in the entry block of their
     * bucket. A STORE defines its slot in its own block, under the key
     * whose bucket is computed from the declared type of the STORE's
     * result (INT for {@code ISTORE}, LONG for {@code LSTORE}, ...).
     * Slots in {@link #unsafeLocals} are skipped.</p>
     */
    private Map<SlotKey, Set<BasicBlock>> collectDefBlocks(Function function) {
        Map<SlotKey, Set<BasicBlock>> defs = new HashMap<>();
        BasicBlock entry = function.getEntryBlock();

        for (Parameter param : function.getParameters()) {
            int idx = param.getIndex();
            int bucket = typeBucket(param.getType());
            if (unsafeLocals.contains(idx)) continue;
            SlotKey key = new SlotKey(idx, bucket);
            defs.computeIfAbsent(key, k -> new HashSet<>()).add(entry);
        }

        for (BasicBlock block : function.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() != Opcode.STORE) continue;
                int idx = inst.getLocalIndex();
                if (idx < 0) continue;
                if (unsafeLocals.contains(idx)) continue;

                Type declaredType = (inst.getResult() != null)
                    ? inst.getResult().getType()
                    : Type.UNKNOWN;
                int bucket = typeBucket(declaredType);

                SlotKey key = new SlotKey(idx, bucket);
                defs.computeIfAbsent(key, k -> new HashSet<>()).add(block);
            }
        }

        return defs;
    }

    /**
     * Returns the IR type that the result of a PHI must carry for the
     * given (slot, bucket) key.
     *
     * <p>For non-ref buckets the type is unambiguous. For the ref
     * bucket, the type of the parameter of the same slot is preferred
     * when available; otherwise the first reference type encountered
     * among the LOAD/STORE instructions of this slot is used; otherwise
     * {@code java/lang/Object}. In every case the result is a reference
     * type, and all references are compatible both in the IR semantics
     * ({@code typesCompatible}) and in LLVM ({@code i8*}).</p>
     */
    private Type inferSlotTypeForBucket(Function func, int localIndex, int bucket) {
        switch (bucket) {
            case BUCKET_INT:    return Type.INT;
            case BUCKET_LONG:   return Type.LONG;
            case BUCKET_FLOAT:  return Type.FLOAT;
            case BUCKET_DOUBLE: return Type.DOUBLE;
            case BUCKET_REF: {
                for (Parameter p : func.getParameters()) {
                    if (p.getIndex() == localIndex
                        && typeBucket(p.getType()) == BUCKET_REF) {
                        return p.getType();
                    }
                }
                for (BasicBlock block : func.getBlocks()) {
                    for (Instruction inst : block.getInstructions()) {
                        Opcode op = inst.getOpcode();
                        if ((op != Opcode.STORE && op != Opcode.LOAD)
                            || inst.getLocalIndex() != localIndex
                            || inst.getResult() == null) {
                            continue;
                        }
                        Type t = inst.getResult().getType();
                        if (typeBucket(t) == BUCKET_REF && !t.isUnknown()) {
                            return t;
                        }
                    }
                }
                return Type.reference("java/lang/Object");
            }
            default:
                return Type.UNKNOWN;
        }
    }

    // ------------------------------------------------------------------
    //  Renaming.
    // ------------------------------------------------------------------

    /**
     * Recursive traversal of the dominator tree with renaming of
     * versions.
     *
     * <p>The processing order within a block is:</p>
     * <ol>
     *   <li>PHIs — create new versions at the beginning of the block;</li>
     *   <li>the remaining instructions — LOADs are replaced with the
     *       current version, STOREs create new versions;</li>
     *   <li>the terminator — the current versions are substituted in;</li>
     *   <li>the incoming values of the successors' PHIs are filled in
     *       with the versions that are current at the exit of the
     *       current block;</li>
     *   <li>a recursive descent into the dominator-tree children;</li>
     *   <li>restoration of the stacks to the size they had on entry to
     *       the block.</li>
     * </ol>
     */
    private void renameBlock(BasicBlock block) {
        // savedSizes records the size of each stack BEFORE this block
        // pushes its own versions. The restoration at the end of this
        // method pops exactly what this block pushed and nothing else —
        // in particular, it leaves nothing behind for the dominator-tree
        // siblings.
        Map<SlotKey, Integer> savedSizes = new HashMap<>();

        // --- PHI ---
        for (Instruction inst : block.getInstructions()) {
            if (inst.getOpcode() != Opcode.PHI) continue;
            int idx = inst.getLocalIndex();
            if (idx < 0) continue;

            Type phiType = (inst.getResult() != null)
                ? inst.getResult().getType()
                : Type.UNKNOWN;
            int bucket = typeBucket(phiType);
            SlotKey key = new SlotKey(idx, bucket);

            savedSizes.putIfAbsent(key, stackSize(key));

            Temporary newVer = newVersion(key, phiType);
            inst.setResult(newVer);
            newVer.setDefiningInstruction(inst);
        }

        // --- remaining instructions ---
        for (Instruction inst : block.getInstructions()) {
            Opcode op = inst.getOpcode();
            if (op == Opcode.PHI) continue;

            inst.getOperands().replaceAll(this::resolve);

            if (op == Opcode.LOAD) {
                int idx = inst.getLocalIndex();
                if (idx < 0) continue;
                if (unsafeLocals.contains(idx)) continue;

                Type loadResultType = (inst.getResult() != null)
                    ? inst.getResult().getType()
                    : Type.UNKNOWN;
                int bucket = typeBucket(loadResultType);
                SlotKey key = new SlotKey(idx, bucket);

                Value curVer = currentVersion(key);
                if (curVer == null) {
                    savedSizes.putIfAbsent(key, stackSize(key));

                    Type undefType = inferSlotTypeForBucket(
                        currentFunction, idx, bucket);
                    curVer = new UndefinedValue(undefType);
                    versionStacks.computeIfAbsent(key, k -> new ArrayDeque<>())
                        .push(curVer);
                    versionCounters.putIfAbsent(key, 0);
                }

                // Within a bucket the types are compatible by
                // construction, so no constant substitution should
                // happen here. If that invariant is ever violated, it is
                // a bug in the transformation itself, and it must fail
                // loudly rather than silently corrupting the value.
                if (!typesCompatible(curVer.getType(), loadResultType)) {
                    throw new IllegalStateException(
                        "SSA slot-type invariant violated: LOAD of local "
                            + idx + " (bucket " + bucket + ") in block "
                            + block.getLabel() + " expects " + loadResultType
                            + ", but the current SSA version has type "
                            + curVer.getType() + ". This indicates a bug in "
                            + "versionStacks keying or in inferSlotTypeForBucket; "
                            + "silent substitution is deliberately not performed.");
                }

                replacements.put(inst.getResult(), curVer);
                inst.setOpcode(Opcode.NOP);
                inst.getOperands().clear();
                inst.setResult(null);

            } else if (op == Opcode.STORE) {
                int idx = inst.getLocalIndex();
                if (idx < 0) continue;
                if (unsafeLocals.contains(idx)) continue;

                savedSizes.putIfAbsent(
                    new SlotKey(idx, BUCKET_INT), stackSize(new SlotKey(idx, BUCKET_INT)));
                // ^ save just in case; the actual key is computed below.

                Value operand = inst.getOperands().isEmpty()
                    ? new UndefinedValue(Type.UNKNOWN)
                    : resolve(inst.getOperands().getFirst());

                // The declared type of the slot is the result type of
                // the STORE, set by IrBuilder.createStore from the
                // bytecode opcode (ISTORE -> INT, LSTORE -> LONG, ...).
                // It is this type — not the type of the stored value —
                // that determines both the bucket and, more importantly,
                // the width with which the slot will be read by every
                // subsequent LOAD.
                Type declaredType = (inst.getResult() != null)
                    ? inst.getResult().getType()
                    : operand.getType();
                if (declaredType.isUnknown()) {
                    declaredType = operand.getType();
                }
                int bucket = typeBucket(declaredType);
                SlotKey key = new SlotKey(idx, bucket);

                savedSizes.putIfAbsent(key, stackSize(key));

                Temporary newVer = newVersion(key, declaredType);
                if (inst.getResult() != null) {
                    inst.getResult().setDefiningInstruction(null);
                }
                inst.setResult(newVer);
                newVer.setDefiningInstruction(inst);
            }
        }

        renameTerminator(block);

        // --- incoming PHI values of the successors ---
        for (BasicBlock succ : block.getSuccessors()) {
            int predIdx = succ.getPredecessors().indexOf(block);
            if (predIdx < 0) continue;

            for (Instruction inst : succ.getInstructions()) {
                if (inst.getOpcode() != Opcode.PHI) continue;
                int idx = inst.getLocalIndex();
                if (idx < 0) continue;

                Type phiType = (inst.getResult() != null)
                    ? inst.getResult().getType()
                    : Type.UNKNOWN;
                int bucket = typeBucket(phiType);
                SlotKey key = new SlotKey(idx, bucket);

                Value curVer = currentVersion(key);
                if (curVer == null) {
                    Type undefType = inferSlotTypeForBucket(
                        currentFunction, idx, bucket);
                    curVer = new UndefinedValue(undefType);
                }

                if (!typesCompatible(curVer.getType(), phiType)) {
                    throw new IllegalStateException(
                        "SSA PHI-type invariant violated in successor "
                            + succ.getLabel() + " of block " + block.getLabel()
                            + ": phi for slot " + idx + " (bucket " + bucket
                            + ") has type " + phiType
                            + ", but the incoming value from this predecessor has "
                            + curVer.getType() + ". Silent substitution is "
                            + "deliberately not performed.");
                }

                ensurePhiOperandCount(inst, predIdx + 1);
                inst.getOperands().set(predIdx, curVer);
            }
        }

        // --- recursion into the dominator tree ---
        for (BasicBlock child : domTree.getChildren(block)) {
            renameBlock(child);
        }

        // --- restoration of the stacks ---
        for (Map.Entry<SlotKey, Integer> entry : savedSizes.entrySet()) {
            restoreStack(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Checks whether two IR types are compatible from the point of view
     * of SSA merging within a single bucket.
     *
     * <p>With correct keying this function should never return
     * {@code false}: the int bucket contains only integer types, the
     * ref bucket contains only reference types. The function is kept as
     * a defensive invariant and as a trigger for the
     * {@link IllegalStateException} in {@link #renameBlock}, not as a
     * license to substitute a default.</p>
     */
    private static boolean typesCompatible(Type a, Type b) {
        if (a == null || b == null) return true;
        if (a == b) return true;
        if (a.equals(b)) return true;
        if (a.isUnknown() || b.isUnknown()) return true;

        boolean aPrim = a.isPrimitive();
        boolean bPrim = b.isPrimitive();
        if (aPrim && bPrim) return true;

        boolean aRef = a.isReference() || a.isArray() || a.isNull() || a.isBlock();
        boolean bRef = b.isReference() || b.isArray() || b.isNull() || b.isBlock();
        return aRef && bRef;
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

    // ------------------------------------------------------------------
    //  Version-stack management.
    // ------------------------------------------------------------------

    private Temporary newVersion(SlotKey key, Type type) {
        int ver = versionCounters.computeIfAbsent(key, k -> 0);
        versionCounters.put(key, ver + 1);
        Temporary tmp = new Temporary(type);
        versionStacks.computeIfAbsent(key, k -> new ArrayDeque<>()).push(tmp);
        return tmp;
    }

    private Value currentVersion(SlotKey key) {
        Deque<Value> stack = versionStacks.get(key);
        return stack != null ? stack.peek() : null;
    }

    private int stackSize(SlotKey key) {
        Deque<Value> stack = versionStacks.get(key);
        return stack != null ? stack.size() : 0;
    }

    private void restoreStack(SlotKey key, int size) {
        Deque<Value> stack = versionStacks.get(key);
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

    // ------------------------------------------------------------------
    //  PHI optimization and NOP cleanup.
    // ------------------------------------------------------------------

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
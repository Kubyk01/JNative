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
import io.github.kubyk01.util.ScratchIdentityMap;
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

    // ------------------------------------------------------------------
    //  Transformation state.
    // ------------------------------------------------------------------

    /** One version stack per (slot, type class). */
    private Value[][] verStack = new Value[64][];
    private int[] verTop = new int[64];
    private int[] verStamp = new int[64];
    private int fnStamp;
    private int[] savedKeys = new int[64];
    private int[] savedSizes = new int[64];
    private int[] savedFrame = new int[64];
    private int savedTop;
    private int frameCounter;
    private long[] liveRows = new long[0];
    private long[] defRows = new long[0];
    private long[] outRow = new long[0];
    private long[] unionRow = new long[0];
    private int[] succStart = new int[1];
    private int[] succList = new int[0];
    private int[] phiStamp = new int[0];
    private int phiCounter;
    private int[] phiWork = new int[0];
    private final ScratchIdentityMap<Instruction, Value> phiReplacements = new ScratchIdentityMap<>();
    private final ScratchIdentityMap<Value, Boolean> usedValues = new ScratchIdentityMap<>();
    /** Version counter per (slot, type class). */
    /** Substitution map: "old value -> current SSA version". */
    private final ScratchIdentityMap<Value, Value> replacements = new ScratchIdentityMap<>();

    private final DominatorTree domTree = new DominatorTree();
    private Function currentFunction;

    /**
     * The set of slots that, for one reason or another, remain in
     * memory and do not participate in SSA. The key is just the slot
     * number: the "unsafe" attribute belongs to the slot as a cell,
     * not to its current type.
     */
    private int[] unsafeStamp = new int[64];

    // ------------------------------------------------------------------
    //  Entry point.
    // ------------------------------------------------------------------

    public void transform(Function function) {
        if (function.getEntryBlock() == null || function.getBlocks().isEmpty()) return;

        /*
         * Reset per-function state.
         *
         * The SSATransformer instance is reused across every function in
         * the module (see BytecodeToIr.translate and Orchestrator's SSA
         * loop). Without this reset the three maps below grow monotonically
         * with the total number of LOAD results, PHI nodes and slot accesses
         * of the whole module — on a full JDK closure that is several
         * million entries and several hundred MB of retained heap. The maps
         * are only meaningful within a single function, so clearing them at
         * entry is not only safe, it is the only correct behaviour.
         */
        fnStamp++;
        savedTop = 0;
        replacements.clear();

        this.currentFunction = function;
        markUnsafeLocals(function);
        domTree.reset(function);

        initializeStacks(function);
        insertPhiFunctions(function);
        renameBlock(function.getEntryBlock());
        padPhiOperands(function);
        optimizePhis(function);
        cleanupPhis(function);
        cleanupNops(function);
        retypeReferencePhis(function);
    }

    private void retypeReferencePhis(Function function) {
        List<Instruction> phis = new ArrayList<>();
        List<BasicBlock> blockList1 = function.getBlocks();
        List<? extends BasicBlock> jnLoop1 = blockList1;
        for (int jnIdx1 = 0; jnIdx1 < jnLoop1.size(); jnIdx1++) {
            BasicBlock block = jnLoop1.get(jnIdx1);
            List<Instruction> instList2 = block.getInstructions();
            List<? extends Instruction> jnLoop2 = instList2;
            for (int jnIdx2 = 0; jnIdx2 < jnLoop2.size(); jnIdx2++) {
                Instruction inst = jnLoop2.get(jnIdx2);
                if (inst.getOpcode() == Opcode.PHI
                    && inst.getResult() != null
                    && typeBucket(inst.getResult().getType()) == BUCKET_REF) {
                    phis.add(inst);
                }
            }
        }
        Type object = Type.reference("java/lang/Object");
        boolean changed = true;
        for (int round = 0; changed && round < 64; round++) {
            changed = false;
            List<? extends Instruction> jnLoop3 = phis;
            for (int jnIdx3 = 0; jnIdx3 < jnLoop3.size(); jnIdx3++) {
                Instruction phi = jnLoop3.get(jnIdx3);
                Type joined = null;
                List<Value> incomingList4 = phi.getOperands();
                List<? extends Value> jnLoop4 = incomingList4;
                for (int jnIdx4 = 0; jnIdx4 < jnLoop4.size(); jnIdx4++) {
                    Value incoming = jnLoop4.get(jnIdx4);
                    if (incoming == null || incoming == phi.getResult()) continue;
                    Type t = incoming.getType();
                    if (t == null || t.isNull() || t.isUnknown()) continue;
                    joined = joined == null || joined.equals(t) ? t : object;
                }
                if (joined != null && !joined.equals(phi.getResult().getType())) {
                    phi.getResult().setType(joined);
                    changed = true;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Identification of "unsafe" slots (essentially unchanged).
    // ------------------------------------------------------------------

    private void markUnsafeLocals(Function function) {
        List<BasicBlock> blocks = function.getBlocks();
        List<? extends BasicBlock> jnLoop5 = blocks;
        for (int jnIdx5 = 0; jnIdx5 < jnLoop5.size(); jnIdx5++) {
            BasicBlock block = jnLoop5.get(jnIdx5);
            if (block.getExceptionalSuccessors().isEmpty()) continue;
            boolean seenThrowing = false;
            List<Instruction> insts = block.getInstructions();
            List<? extends Instruction> jnLoop6 = insts;
            for (int jnIdx6 = 0; jnIdx6 < jnLoop6.size(); jnIdx6++) {
                Instruction inst = jnLoop6.get(jnIdx6);
                if (seenThrowing
                    && inst.getOpcode() == Opcode.STORE
                    && inst.getLocalIndex() >= 0) {
                    int idx = inst.getLocalIndex();
                    if (idx >= unsafeStamp.length) {
                        unsafeStamp = Arrays.copyOf(unsafeStamp, Math.max(idx + 1, unsafeStamp.length << 1));
                    }
                    unsafeStamp[idx] = fnStamp;
                }
                if (inst.canThrow()) {
                    seenThrowing = true;
                }
            }
        }
    }

    private boolean isUnsafe(int idx) {
        return idx >= 0 && idx < unsafeStamp.length && unsafeStamp[idx] == fnStamp;
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
        List<Parameter> paramList5 = function.getParameters();
        List<? extends Parameter> jnLoop7 = paramList5;
        for (int jnIdx7 = 0; jnIdx7 < jnLoop7.size(); jnIdx7++) {
            Parameter param = jnLoop7.get(jnIdx7);
            int idx = param.getIndex();
            int bucket = typeBucket(param.getType());
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

            if (idx >= 0) pushVersion(slotKey(idx, bucket), version);
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
        int n = computeLiveness(function);
        if (n == 0) return;
        int words = outRow.length;
        if (unionRow.length != words) unionRow = new long[words];
        long[] defUnion = unionRow;
        Arrays.fill(defUnion, 0L);
        for (int i = 0; i < n; i++) {
            int base = i * words;
            for (int w = 0; w < words; w++) defUnion[w] |= defRows[base + w];
        }
        if (phiStamp.length < n) phiStamp = new int[Math.max(n, phiStamp.length << 1)];
        if (phiWork.length < 2 * n) phiWork = new int[Math.max(2 * n, phiWork.length << 1)];
        for (int w = 0; w < words; w++) {
            long bits = defUnion[w];
            while (bits != 0) {
                int bit = (w << 6) | Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                int slot = bit >>> 3;
                int bucket = bit & 7;
                Type phiType = inferSlotTypeForBucket(function, slot, bucket);
                int stamp = ++phiCounter;
                int head = 0;
                int tail = 0;
                long mask = 1L << (bit & 63);
                for (int i = 0; i < n; i++) {
                    if ((defRows[i * words + w] & mask) != 0) phiWork[tail++] = i;
                }
                while (head < tail) {
                    int x = phiWork[head++];
                    int count = domTree.frontierCount(x);
                    for (int k = 0; k < count; k++) {
                        int f = domTree.frontierAt(x, k);
                        if (phiStamp[f] == stamp) continue;
                        if ((liveRows[f * words + w] & mask) == 0) continue;
                        BasicBlock frontier = domTree.blockAt(f);

                        Instruction phi = new Instruction(Opcode.PHI);
                        phi.setLocalIndex(slot);
                        Temporary phiResult = new Temporary(phiType);
                        phi.setResult(phiResult);
                        phiResult.setDefiningInstruction(phi);

                        phi.setParent(frontier);
                        frontier.getInstructions().addFirst(phi);

                        phiStamp[f] = stamp;
                        if (tail == phiWork.length) phiWork = Arrays.copyOf(phiWork, tail << 1);
                        phiWork[tail++] = f;
                    }
                }
            }
        }
    }

    private int computeLiveness(Function function) {
        int n = domTree.size();
        if (n == 0) return 0;
        int maxSlot = 0;
        for (int i = 0; i < n; i++) {
            List<Instruction> insts = domTree.blockAt(i).getInstructions();
            for (int k = 0; k < insts.size(); k++) {
                Instruction inst = insts.get(k);
                Opcode op = inst.getOpcode();
                if ((op == Opcode.LOAD || op == Opcode.STORE) && inst.getLocalIndex() > maxSlot) {
                    maxSlot = inst.getLocalIndex();
                }
            }
        }
        List<Parameter> params = function.getParameters();
        List<? extends Parameter> jnLoop8 = params;
        for (int jnIdx8 = 0; jnIdx8 < jnLoop8.size(); jnIdx8++) {
            Parameter param = jnLoop8.get(jnIdx8);
            if (param.getIndex() > maxSlot) maxSlot = param.getIndex();
        }
        int words = (((maxSlot + 1) << 3) + 63) >>> 6;
        int cells = n * words;
        if (liveRows.length < cells) {
            liveRows = new long[Math.max(cells, liveRows.length << 1)];
            defRows = new long[liveRows.length];
        }
        Arrays.fill(liveRows, 0, cells, 0L);
        Arrays.fill(defRows, 0, cells, 0L);
        if (outRow.length != words) outRow = new long[words];

        for (int i = 0; i < n; i++) {
            int base = i * words;
            List<Instruction> insts = domTree.blockAt(i).getInstructions();
            List<? extends Instruction> jnLoop9 = insts;
            for (int jnIdx9 = 0; jnIdx9 < jnLoop9.size(); jnIdx9++) {
                Instruction inst = jnLoop9.get(jnIdx9);
                Opcode op = inst.getOpcode();
                if (op != Opcode.LOAD && op != Opcode.STORE) continue;
                int idx = inst.getLocalIndex();
                if (idx < 0) continue;
                if (isUnsafe(idx)) continue;
                Type t = (inst.getResult() != null) ? inst.getResult().getType() : Type.UNKNOWN;
                int bit = (idx << 3) | typeBucket(t);
                int at = base + (bit >>> 6);
                long mask = 1L << (bit & 63);
                if (op == Opcode.LOAD) {
                    if ((defRows[at] & mask) == 0) liveRows[at] |= mask;
                } else {
                    defRows[at] |= mask;
                }
            }
        }
        List<? extends Parameter> jnLoop10 = params;
        for (int jnIdx10 = 0; jnIdx10 < jnLoop10.size(); jnIdx10++) {
            Parameter p = jnLoop10.get(jnIdx10);
            if (p.getIndex() < 0 || isUnsafe(p.getIndex())) continue;
            int bit = (p.getIndex() << 3) | typeBucket(p.getType());
            defRows[bit >>> 6] |= 1L << (bit & 63);
        }

        if (succStart.length < n + 1) succStart = new int[Math.max(n + 1, succStart.length << 1)];
        int edges = 0;
        for (int i = 0; i < n; i++) {
            succStart[i] = edges;
            List<BasicBlock> ss = domTree.blockAt(i).getSuccessors();
            if (succList.length < edges + ss.size()) {
                succList = Arrays.copyOf(succList, Math.max(edges + ss.size(), succList.length << 1));
            }
            List<? extends BasicBlock> jnLoop11 = ss;
            for (int jnIdx11 = 0; jnIdx11 < jnLoop11.size(); jnIdx11++) {
                BasicBlock s = jnLoop11.get(jnIdx11);
                int j = domTree.indexOf(s);
                if (j >= 0) succList[edges++] = j;
            }
        }
        succStart[n] = edges;

        long[] out = outRow;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = n - 1; i >= 0; i--) {
                Arrays.fill(out, 0L);
                for (int e = succStart[i]; e < succStart[i + 1]; e++) {
                    int sb = succList[e] * words;
                    for (int w = 0; w < words; w++) out[w] |= liveRows[sb + w];
                }
                int base = i * words;
                for (int w = 0; w < words; w++) {
                    long in = liveRows[base + w];
                    long next = in | (out[w] & ~defRows[base + w]);
                    if (next != in) {
                        liveRows[base + w] = next;
                        changed = true;
                    }
                }
            }
        }
        return n;
    }

    /**
     * Liveness per (slot, bucket) key for the blocks of a
     * function.
     *
     * <p>Semantics: {@code liveIn[B]} contains the key {@code (slot,
     * bucket)} if and only if there exists a path from the entry of
     * {@code B} to a use of that key (a LOAD with the matching bucket)
     * on which there is no STORE into the same key. Parameters count as
     * defined in the entry, so they sit in {@code def[entry]} and do
     * not "leak" into {@code liveIn[entry]} through the use part.</p>
     *
     * <p>Used only to gate PHI insertion in
     * {@link #insertPhiFunctions}: a key needs a PHI in a block if the
     * key is live-in in that block. This is "pruned SSA" — the standard
     * SSA construction optimization that eliminates dead PHI nodes with
     * undefined incoming operands (see the javadoc of
     * {@link #insertPhiFunctions}).</p>
     *
     * <p><b>Bucketing invariant.</b> {@code use[]} and {@code def[]} are
     * built with the same {@code typeBucket(result.getType())} predicate
     * as {@link #collectDefBlocks}. Any divergence in bucketing between
     * this method, {@code collectDefBlocks} and {@code renameBlock}
     * would break key consistency and produce either the liveness of the
     * wrong key or an empty version stack for a "live" key. All three
     * use the same private {@link #typeBucket}; when the bucketing logic
     * changes, all three must be changed together.</p>
     *
     * <p><b>Exceptional edges.</b> {@code block.getSuccessors()} merges
     * the ordinary and the exceptional edges (see {@code BasicBlock}),
     * so liveness propagates along the exception front as well. This is
     * conservative in the right direction (it may slightly overestimate
     * live-in, but never underestimate it), and it does not lift the
     * known limitation of SSA construction for mid-block exceptions (the
     * value of a slot on an exceptional edge is the state at the moment
     * of the throw, not the state at the end of the block). That
     * limitation is a separate class of defects, unrelated to dead
     * loop-carried PHIs; here we merely do not stand in its way.</p>
     */
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
                List<Parameter> pList6 = func.getParameters();
                List<? extends Parameter> jnLoop12 = pList6;
                for (int jnIdx12 = 0; jnIdx12 < jnLoop12.size(); jnIdx12++) {
                    Parameter p = jnLoop12.get(jnIdx12);
                    if (p.getIndex() == localIndex
                        && typeBucket(p.getType()) == BUCKET_REF) {
                        return p.getType();
                    }
                }
                List<BasicBlock> blockList7 = func.getBlocks();
                List<? extends BasicBlock> jnLoop13 = blockList7;
                for (int jnIdx13 = 0; jnIdx13 < jnLoop13.size(); jnIdx13++) {
                    BasicBlock block = jnLoop13.get(jnIdx13);
                    List<Instruction> instList8 = block.getInstructions();
                    List<? extends Instruction> jnLoop14 = instList8;
                    for (int jnIdx14 = 0; jnIdx14 < jnLoop14.size(); jnIdx14++) {
                        Instruction inst = jnLoop14.get(jnIdx14);
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
        int frame = ++frameCounter;
        int savedBase = savedTop;

        // --- PHI ---
        List<Instruction> instList9 = block.getInstructions();
        List<? extends Instruction> jnLoop15 = instList9;
        for (int jnIdx15 = 0; jnIdx15 < jnLoop15.size(); jnIdx15++) {
            Instruction inst = jnLoop15.get(jnIdx15);
            if (inst.getOpcode() != Opcode.PHI) continue;
            int idx = inst.getLocalIndex();
            if (idx < 0) continue;

            Type phiType = (inst.getResult() != null)
                ? inst.getResult().getType()
                : Type.UNKNOWN;
            int bucket = typeBucket(phiType);
            int key = slotKey(idx, bucket);

            saveSize(key, frame);

            Temporary newVer = newVersion(key, phiType);
            inst.setResult(newVer);
            newVer.setDefiningInstruction(inst);
        }

        // --- remaining instructions ---
        List<Instruction> instList10 = block.getInstructions();
        List<? extends Instruction> jnLoop16 = instList10;
        for (int jnIdx16 = 0; jnIdx16 < jnLoop16.size(); jnIdx16++) {
            Instruction inst = jnLoop16.get(jnIdx16);
            Opcode op = inst.getOpcode();
            if (op == Opcode.PHI) continue;

            List<Value> ops = inst.getOperands();
            for (int k = 0; k < ops.size(); k++) {
                Value v = ops.get(k);
                Value r = resolve(v);
                if (r != v) ops.set(k, r);
            }

            if (op == Opcode.LOAD) {
                int idx = inst.getLocalIndex();
                if (idx < 0) continue;
                if (isUnsafe(idx)) continue;

                Type loadResultType = (inst.getResult() != null)
                    ? inst.getResult().getType()
                    : Type.UNKNOWN;
                int bucket = typeBucket(loadResultType);
                int key = slotKey(idx, bucket);

                Value curVer = currentVersion(key);
                if (curVer == null) {
                    throw new IllegalStateException(
                        "SSATransformer: LOAD from slot " + idx
                            + " (bucket " + bucket + ") in block '"
                            + block.getLabel() + "' of function '"
                            + currentFunction.getName()
                            + "' has no version on the version stack. "
                            + "The JVM verifier guarantees that a STORE precedes "
                            + "every reachable LOAD on every path; the absence "
                            + "of a version here means the dominator tree used "
                            + "by SSATransformer could not propagate the STORE's "
                            + "version to this point. Investigate: "
                            + "(a) stale predecessors of '" + block.getLabel()
                            + "' that are not in any terminator's targets and not "
                            + "in any predecessor's exceptionalSuccessors "
                            + "(DominatorTree.compute sanitises these, but a bug "
                            + "there could still slip through); "
                            + "(b) the phi operand binding at '"
                            + block.getLabel()
                            + "', which is positional against block.getPredecessors(); "
                            + "(c) a missing STORE from the translator for a "
                            + "synthetic stack slot (slot index >= 10000).");
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
                if (isUnsafe(idx)) continue;

                saveSize(slotKey(idx, BUCKET_INT), frame);
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
                int bucket = typeBucket(inst.getResult() != null
                    ? inst.getResult().getType()
                    : Type.UNKNOWN);
                if (declaredType.isUnknown()) {
                    declaredType = operand.getType();
                }
                int key = slotKey(idx, bucket);

                saveSize(key, frame);

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
        List<BasicBlock> succList11 = block.getSuccessors();
        List<? extends BasicBlock> jnLoop17 = succList11;
        for (int jnIdx17 = 0; jnIdx17 < jnLoop17.size(); jnIdx17++) {
            BasicBlock succ = jnLoop17.get(jnIdx17);
            int predIdx = succ.getPredecessors().indexOf(block);
            if (predIdx < 0) continue;

            List<Instruction> instList = succ.getInstructions();

            List<? extends Instruction> jnLoop18 = instList;
            for (int jnIdx18 = 0; jnIdx18 < jnLoop18.size(); jnIdx18++) {
                Instruction inst = jnLoop18.get(jnIdx18);

                if (inst.getOpcode() != Opcode.PHI) continue;
                int idx = inst.getLocalIndex();
                if (idx < 0) continue;

                Type phiType = (inst.getResult() != null)
                    ? inst.getResult().getType()
                    : Type.UNKNOWN;
                int bucket = typeBucket(phiType);
                int key = slotKey(idx, bucket);

                Value curVer = currentVersion(key);
                if (curVer == null) {
                    /*
                     * The slot is not defined along the path through this
                     * predecessor. Two fundamentally different situations
                     * collapse to the same "version stack is empty" signal,
                     * and the edge kind tells them apart:
                     *
                     *   1. EXCEPTIONAL-ONLY EDGE.
                     *
                     *      `pred` does not list `succ` among its
                     *      terminator's targets, but `succ` is in
                     *      `pred.getExceptionalSuccessors()`. This means
                     *      the edge carries control on a thrown exception.
                     *      The exception transfers control mid-block, so a
                     *      slot that is assigned after the throwing
                     *      instruction - or that is simply not assigned
                     *      at all on the block's normal path - has no
                     *      value here. The JVM verifier permits this
                     *      precisely because the phi's result is never
                     *      observed on this path (any observable use of
                     *      the slot after the catch would require the
                     *      slot to be definitely assigned on every path
                     *      to the use, including this one, which it is
                     *      not).
                     *
                     *      Substituting `Constant(Type.NULL, null)` for
                     *      the incoming operand makes the emitted IR
                     *      contain `null` on the exception edge - exactly
                     *      the runtime value of a slot that was not
                     *      assigned before the throw. Because the JVM
                     *      verifier guarantees the value is not consumed
                     *      on this path, `null` cannot reach any use
                     *      that would dereference it.
                     *
                     *   2. NORMAL (OR MIXED) EDGE.
                     *
                     *      `pred` lists `succ` among its terminator's
                     *      targets. Then the missing version is a genuine
                     *      bug: either the dominator tree used by
                     *      SSATransformer failed to propagate the STORE's
                     *      version across a real edge (the
                     *      ProtectionDomain.<init> class of defect), or
                     *      the IR violates the JVM verifier's
                     *      definite-assignment rule. Fail-fast is required
                     *      so that the bug surfaces at build time rather
                     *      than as a null base of a PUT_FIELD at run
                     *      time.
                     *
                     * The two cases are distinguished here, per edge,
                     * rather than globally, because the same phi routinely
                     * has a normal incoming edge (valid version) and one
                     * or more exceptional incoming edges (undefined
                     * version). The phi is alive if its result is used on
                     * any of its normal paths; the exceptional operands
                     * are dead by construction.
                     */
                    boolean predIsNormalTarget = false;
                    Terminator predTerm = block.getTerminator();
                    if (predTerm != null) {
                        List<BasicBlock> tList12 = predTerm.getTargets();
                        List<? extends BasicBlock> jnLoop19 = tList12;
                        for (int jnIdx19 = 0; jnIdx19 < jnLoop19.size(); jnIdx19++) {
                            BasicBlock t = jnLoop19.get(jnIdx19);
                            if (t == succ) {
                                predIsNormalTarget = true;
                                break;
                            }
                        }
                    }
                    boolean predIsExceptional =
                        block.getExceptionalSuccessors().contains(succ);

                    if (!predIsNormalTarget && predIsExceptional) {
                        // Exceptional-only edge: the slot has no value
                        // here, and the JVM verifier guarantees it is not
                        // observed. `Type.NULL` is the null literal type
                        // that every other null constant in the IR uses,
                        // so the emitter renders it as the LLVM literal
                        // `null` (of any pointer type it must be coerced
                        // to).
                        curVer = new Constant(Type.NULL, null);
                    } else {
                        // Normal edge with a missing version - genuine
                        // bug. Let OutOfSsaPass fail on it with a
                        // diagnostic naming the block and the operand
                        // index.
                        curVer = new UndefinedValue(phiType);
                    }
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
        int self = domTree.indexOf(block);
        if (self >= 0) {
            int children = domTree.childCount(self);
            for (int k = 0; k < children; k++) {
                renameBlock(domTree.blockAt(domTree.childAt(self, k)));
            }
        }

        // --- restoration of the stacks ---
        for (int k = savedTop - 1; k >= savedBase; k--) {
            restoreStack(savedKeys[k], savedSizes[k]);
        }
        savedTop = savedBase;
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

    private static int slotKey(int idx, int bucket) {
        return (idx << 3) | bucket;
    }

    private void ensureKey(int key) {
        if (key < verTop.length) return;
        int c = Math.max(key + 1, verTop.length << 1);
        verStack = Arrays.copyOf(verStack, c);
        verTop = Arrays.copyOf(verTop, c);
        verStamp = Arrays.copyOf(verStamp, c);
        savedFrame = Arrays.copyOf(savedFrame, c);
    }

    private void pushVersion(int key, Value v) {
        ensureKey(key);
        if (verStamp[key] != fnStamp) {
            verStamp[key] = fnStamp;
            verTop[key] = 0;
        }
        Value[] st = verStack[key];
        int top = verTop[key];
        if (st == null) {
            st = new Value[8];
            verStack[key] = st;
        } else if (top == st.length) {
            st = Arrays.copyOf(st, top << 1);
            verStack[key] = st;
        }
        st[top] = v;
        verTop[key] = top + 1;
    }

    private Temporary newVersion(int key, Type type) {
        Temporary tmp = new Temporary(type);
        pushVersion(key, tmp);
        return tmp;
    }

    private Value currentVersion(int key) {
        int size = stackSize(key);
        return size > 0 ? verStack[key][size - 1] : null;
    }

    private int stackSize(int key) {
        return key < verTop.length && verStamp[key] == fnStamp ? verTop[key] : 0;
    }

    private void saveSize(int key, int frame) {
        ensureKey(key);
        if (savedFrame[key] == frame) return;
        savedFrame[key] = frame;
        if (savedTop == savedKeys.length) {
            savedKeys = Arrays.copyOf(savedKeys, savedTop << 1);
            savedSizes = Arrays.copyOf(savedSizes, savedTop << 1);
        }
        savedKeys[savedTop] = key;
        savedSizes[savedTop++] = stackSize(key);
    }

    private void restoreStack(int key, int size) {
        if (stackSize(key) > size) verTop[key] = size;
    }

    private void padPhiOperands(Function function) {
        List<BasicBlock> blockList13 = function.getBlocks();
        List<? extends BasicBlock> jnLoop20 = blockList13;
        for (int jnIdx20 = 0; jnIdx20 < jnLoop20.size(); jnIdx20++) {
            BasicBlock block = jnLoop20.get(jnIdx20);
            int preds = block.getPredecessors().size();
            List<Instruction> instList14 = block.getInstructions();
            List<? extends Instruction> jnLoop21 = instList14;
            for (int jnIdx21 = 0; jnIdx21 < jnLoop21.size(); jnIdx21++) {
                Instruction inst = jnLoop21.get(jnIdx21);
                if (inst.getOpcode() == Opcode.PHI) ensurePhiOperandCount(inst, preds);
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
        ScratchIdentityMap<Instruction, Value> replacementMap = phiReplacements;
        replacementMap.clear();

        List<BasicBlock> blockList = function.getBlocks();

        List<? extends BasicBlock> jnLoop22 = blockList;
        for (int jnIdx22 = 0; jnIdx22 < jnLoop22.size(); jnIdx22++) {
            BasicBlock block = jnLoop22.get(jnIdx22);

            List<Instruction> instList15 = block.getInstructions();
            List<? extends Instruction> jnLoop23 = instList15;
            for (int jnIdx23 = 0; jnIdx23 < jnLoop23.size(); jnIdx23++) {
                Instruction inst = jnLoop23.get(jnIdx23);
                if (inst.getOpcode() != Opcode.PHI) continue;
                if (inst.getOperands().isEmpty()) {
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
                }
            }
        }

        for (int r = 0; r < replacementMap.size(); r++) {
            Instruction phi = replacementMap.keyAt(r);
            Value replacement = replacementMap.valueAt(r);
            List<BasicBlock> blockList16 = function.getBlocks();
            List<? extends BasicBlock> jnLoop24 = blockList16;
            for (int jnIdx24 = 0; jnIdx24 < jnLoop24.size(); jnIdx24++) {
                BasicBlock block = jnLoop24.get(jnIdx24);
                List<Instruction> instList17 = block.getInstructions();
                List<? extends Instruction> jnLoop25 = instList17;
                for (int jnIdx25 = 0; jnIdx25 < jnLoop25.size(); jnIdx25++) {
                    Instruction inst = jnLoop25.get(jnIdx25);
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
        ScratchIdentityMap<Value, Boolean> used = usedValues;
        used.clear();
        List<BasicBlock> blocks = function.getBlocks();
        List<? extends BasicBlock> jnLoop26 = blocks;
        for (int jnIdx26 = 0; jnIdx26 < jnLoop26.size(); jnIdx26++) {
            BasicBlock block = jnLoop26.get(jnIdx26);
            List<Instruction> insts = block.getInstructions();
            List<? extends Instruction> jnLoop27 = insts;
            for (int jnIdx27 = 0; jnIdx27 < jnLoop27.size(); jnIdx27++) {
                Instruction inst = jnLoop27.get(jnIdx27);
                if (inst.getOpcode() == Opcode.PHI) continue;
                List<Value> ops = inst.getOperands();
                List<? extends Value> jnLoop28 = ops;
                for (int jnIdx28 = 0; jnIdx28 < jnLoop28.size(); jnIdx28++) {
                    Value op = jnLoop28.get(jnIdx28);
                    if (op != null) used.put(op, Boolean.TRUE);
                }
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                switch (term) {
                    case CondBranchTerminator cbt -> markUsed(used, cbt.getCondition());
                    case ReturnTerminator rt -> markUsed(used, rt.getValue());
                    case ThrowTerminator tt -> markUsed(used, tt.getException());
                    case LookupSwitchTerminator lst -> markUsed(used, lst.getKey());
                    case TableSwitchTerminator tst -> markUsed(used, tst.getKey());
                    default -> {
                    }
                }
            }
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            List<? extends BasicBlock> jnLoop29 = blocks;
            for (int jnIdx29 = 0; jnIdx29 < jnLoop29.size(); jnIdx29++) {
                BasicBlock block = jnLoop29.get(jnIdx29);
                List<Instruction> insts = block.getInstructions();
                List<? extends Instruction> jnLoop30 = insts;
                for (int jnIdx30 = 0; jnIdx30 < jnLoop30.size(); jnIdx30++) {
                    Instruction inst = jnLoop30.get(jnIdx30);
                    if (inst.getOpcode() != Opcode.PHI) continue;
                    if (inst.getResult() == null) continue;
                    if (!used.containsKey(inst.getResult())) continue;
                    List<Value> ops = inst.getOperands();
                    List<? extends Value> jnLoop31 = ops;
                    for (int jnIdx31 = 0; jnIdx31 < jnLoop31.size(); jnIdx31++) {
                        Value op = jnLoop31.get(jnIdx31);
                        if (op != null && used.put(op, Boolean.TRUE)) changed = true;
                    }
                }
            }
        }

        List<? extends BasicBlock> jnLoop32 = blocks;
        for (int jnIdx32 = 0; jnIdx32 < jnLoop32.size(); jnIdx32++) {
            BasicBlock block = jnLoop32.get(jnIdx32);
            List<Instruction> insts = block.getInstructions();
            int w = 0;
            for (int i = 0; i < insts.size(); i++) {
                Instruction inst = insts.get(i);
                boolean dead = inst.getOpcode() == Opcode.PHI
                    && (inst.getResult() == null || !used.containsKey(inst.getResult()));
                if (!dead) {
                    if (w != i) insts.set(w, inst);
                    w++;
                }
            }
            if (w < insts.size()) insts.subList(w, insts.size()).clear();
        }
    }

    private static void markUsed(ScratchIdentityMap<Value, Boolean> used, Value v) {
        if (v != null) used.put(v, Boolean.TRUE);
    }

    private void cleanupNops(Function function) {
        List<BasicBlock> blocks = function.getBlocks();
        List<? extends BasicBlock> jnLoop33 = blocks;
        for (int jnIdx33 = 0; jnIdx33 < jnLoop33.size(); jnIdx33++) {
            BasicBlock block = jnLoop33.get(jnIdx33);
            List<Instruction> insts = block.getInstructions();
            int w = 0;
            for (int i = 0; i < insts.size(); i++) {
                Instruction inst = insts.get(i);
                if (inst.getOpcode() == Opcode.NOP && inst.getResult() == null) continue;
                if (w != i) insts.set(w, inst);
                w++;
            }
            if (w < insts.size()) insts.subList(w, insts.size()).clear();
        }
    }
}
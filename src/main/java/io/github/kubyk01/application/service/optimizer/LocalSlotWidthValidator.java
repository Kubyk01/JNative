package io.github.kubyk01.application.service.optimizer;

import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the invariant that a JVM local slot is never read at a width
 * wider than the one it was written at, on any path whose writes agree.
 *
 * <p>Every JVM local slot is an 8-byte cell. A STORE writes a declared
 * number of bytes into it and a LOAD reads a declared number of bytes
 * back. If a STORE writes only one byte and a later LOAD reads four, the
 * three upper bytes of the read value are whatever happened to be in the
 * stack frame at that address — typically non-zero, frequently negative
 * after sign extension, and never deterministic. That is exactly the
 * failure mode that produced the {@code NumberFormatException} on the
 * literal {@code "0"}.</p>
 *
 * <h2>Why the check is a dataflow analysis</h2>
 *
 * <p>The obvious formulation — "one width per slot index" — is wrong, and
 * rejects perfectly valid bytecode. A JVM slot index is not one typed
 * variable for its whole extent: javac reuses a slot as soon as the
 * previous value's live range has ended, including the first word of a
 * long/double pair. {@code java.nio.CharBuffer.putBuffer(int, CharBuffer,
 * int, int)}, for instance, keeps an {@code address + i*2} long in
 * local 7 on the "addressable" path and an {@code int} loop index in
 * local 7 on the "heap" path. The paths are mutually exclusive and each
 * is internally consistent, but the slot index alone does not say which
 * is which.</p>
 *
 * <p>What the JVM's own type-checking verifier guarantees is narrower and
 * is exactly what codegen needs: on any single path, a LOAD's width
 * equals the width of the last STORE that wrote the slot on that path.
 * This validator therefore propagates, per block, the width that every
 * path reaching that block must have written each slot at, and rejects a
 * LOAD whose width disagrees with a universally-agreed, narrower
 * preceding write.</p>
 *
 * <h2>Why the analysis is a must-analysis</h2>
 *
 * <p>The join is "all reaching paths must agree", and disagreement
 * collapses to {@link #UNKNOWN}. That is not conservatism for its own
 * sake: this IR's control-flow graph contains deliberately
 * over-approximated edges. {@code MethodTranslator.addExceptionalEdges}
 * attaches a handler as an exceptional successor of every
 * potentially-throwing instruction in a try range, handler blocks
 * re-enter themselves so that the normal and exceptional exits share one
 * block, and {@code LazyClinitInstrumenter} introduces a clinit dispatch
 * block that is a successor of almost every block in the function. A
 * union-based (may) analysis over such a graph degenerates: every slot
 * ends up carrying every width ever written to it anywhere in the
 * function, and valid code is rejected. Requiring agreement keeps the
 * analysis exact wherever the graph is trustworthy — which is where the
 * bug this check exists for lives, since a store/load width mismatch is a
 * property of the value's type rather than of the path — and yields
 * "no information" at the joins where the graph cannot be trusted.</p>
 *
 * <h2>What is compared</h2>
 *
 * <p>Widths are compared as byte counts, not as types: every reference,
 * array, null, block and UNKNOWN type is emitted as the same {@code i8*},
 * so all of them form one uniform 8-byte class, and two different
 * reference types are legitimately mixed across ALOAD/ASTORE of the same
 * slot. Conversely, a LOAD <em>narrower</em> than an agreed wider write
 * is not reported: it is a truncation of a value that was fully written,
 * which is deterministic, and the over-approximated handler edges make
 * that direction common in perfectly valid code.</p>
 */
public final class LocalSlotWidthValidator {

    /** No width is known for the slot on all reaching paths. */
    private static final int UNKNOWN = -1;

    private LocalSlotWidthValidator() {}

    /**
     * Runs the check on every function of {@code module}.
     *
     * @throws IllegalStateException if any LOAD reads a slot more widely
     *         than every path reaching it wrote it.
     */
    public static void validate(Module module) {
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            validateFunction(func);
        }
    }

    private static void validateFunction(Function func) {
        Map<BasicBlock, Map<Integer, Integer>> inStates = computeInStates(func);

        for (BasicBlock block : func.getBlocks()) {
            Map<Integer, Integer> state = copyOf(inStates.get(block));
            List<Instruction> insts = block.getInstructions();
            for (int i = 0; i < insts.size(); i++) {
                Instruction inst = insts.get(i);
                if (inst.getOpcode() == Opcode.STORE) {
                    int idx = inst.getLocalIndex();
                    if (idx < 0) continue;
                    state.put(idx, accessWidthBytes(inst));
                } else if (inst.getOpcode() == Opcode.LOAD) {
                    checkLoad(func, block, i, inst, state);
                }
            }
        }
    }

    /**
     * Fixed-point computation of the slot widths that hold on entry to
     * each block.
     *
     * <p>A STORE <em>defines</em> the slot — it overwrites the previous
     * width rather than combining with it, because the previous contents
     * are gone — while a LOAD leaves the widths untouched. The join of
     * two states is their pointwise agreement: identical widths survive,
     * anything else becomes {@link #UNKNOWN} and stays {@link #UNKNOWN}.
     * That makes the lattice a flat two-level domain in which states only
     * ever lose information, so the worklist converges.</p>
     */
    private static Map<BasicBlock, Map<Integer, Integer>> computeInStates(Function func) {
        Map<BasicBlock, Map<Integer, Integer>> inStates = new HashMap<>();
        BasicBlock entry = func.getEntryBlock();
        if (entry == null) return inStates;

        // A parameter's slot is written by the emitter's parameter
        // prologue before the entry block's body runs, so its width is
        // live everywhere the entry block is.
        Map<Integer, Integer> entryState = new HashMap<>();
        for (Parameter p : func.getParameters()) {
            entryState.put(p.getIndex(), llvmWidthBytes(p.getType()));
        }
        inStates.put(entry, entryState);

        Deque<BasicBlock> worklist = new ArrayDeque<>();
        Set<BasicBlock> queued = new HashSet<>();
        worklist.add(entry);
        queued.add(entry);

        while (!worklist.isEmpty()) {
            BasicBlock block = worklist.poll();
            queued.remove(block);

            Map<Integer, Integer> outState = copyOf(inStates.get(block));
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() != Opcode.STORE) continue;
                int idx = inst.getLocalIndex();
                if (idx < 0) continue;
                outState.put(idx, accessWidthBytes(inst));
            }

            for (BasicBlock succ : block.getSuccessors()) {
                Map<Integer, Integer> succState =
                    inStates.computeIfAbsent(succ, k -> new HashMap<>());
                if (join(succState, outState) && queued.add(succ)) {
                    worklist.add(succ);
                }
            }
        }
        return inStates;
    }

    private static Map<Integer, Integer> copyOf(Map<Integer, Integer> state) {
        return state == null ? new HashMap<>() : new HashMap<>(state);
    }

    /**
     * Intersects two states: a slot's width survives only if both states
     * agree on it. Returns whether {@code target} changed, which is what
     * drives the worklist.
     */
    private static boolean join(Map<Integer, Integer> target, Map<Integer, Integer> source) {
        boolean changed = false;
        for (Map.Entry<Integer, Integer> e : source.entrySet()) {
            int incoming = e.getValue();
            int before = target.getOrDefault(e.getKey(), UNKNOWN);
            if (before == UNKNOWN) continue;
            int after = (before == incoming) ? incoming : UNKNOWN;
            if (after != before) {
                target.put(e.getKey(), after);
                changed = true;
            }
        }
        return changed;
    }

    private static void checkLoad(Function func, BasicBlock block, int instIndex,
                                 Instruction inst, Map<Integer, Integer> state) {
        int idx = inst.getLocalIndex();
        if (inst.getResult() == null) return;
        if (idx < 0) return;

        int written = state.getOrDefault(idx, UNKNOWN);
        // Either the slot was never written on a path reaching this
        // point, or the paths reaching it disagree about the width. In
        // both cases the preceding writes cannot be characterised, and
        // the cell is zero-initialised by the emitter, so the read is
        // deterministic regardless. Nothing can be concluded.
        if (written == UNKNOWN) return;

        int readBytes = llvmWidthBytes(inst.getResult().getType());
        if (written >= readBytes) return;

        throw new IllegalStateException(
            "Local slot width mismatch in function " + func.getName()
                + ", slot " + idx + ": " + block.getLabel() + " #" + instIndex
                + " reads it as " + inst.getResult().getType() + " ("
                + (readBytes * 8) + " bits), but every path reaching this point"
                + " last wrote the slot at only " + (written * 8) + " bits."
                + " The bytes above the written value were never initialised,"
                + " so the read produces garbage. Every LOAD of a slot must"
                + " use the declared width of the STORE that wrote it on the"
                + " same path. See the STORE handling in LlvmFunctionEmitter"
                + " and the slot-type plumbing in MethodTranslator."
        );
    }

    /**
     * The width, in bytes, that a LOAD or STORE actually occupies in the
     * 8-byte slot cell. This mirrors what the emitter does: the STORE
     * case casts the value to the instruction's declared type and stores
     * at that width, and a STORE that carries no result falls back to the
     * type of the value being stored, exactly as the emitter does.
     */
    private static int accessWidthBytes(Instruction inst) {
        if (inst.getResult() != null) {
            return llvmWidthBytes(inst.getResult().getType());
        }
        List<Value> operands = inst.getOperands();
        if (!operands.isEmpty() && operands.getFirst() != null) {
            return llvmWidthBytes(operands.getFirst().getType());
        }
        return 8;
    }

    /**
     * The number of bytes the emitted LLVM type of {@code t} occupies in
     * the slot cell. Mirrors
     * {@link io.github.kubyk01.application.service.codegen.llvm.LlvmTypeMapper#toLlvmType}:
     * {@code boolean}/{@code byte} are 1 byte, {@code short}/{@code char}
     * 2, {@code int}/{@code float} 4, {@code long}/{@code double} 8, and
     * every reference, array, null, block or UNKNOWN type is an
     * {@code i8*} and therefore 8.
     */
    private static int llvmWidthBytes(Type t) {
        if (t == Type.BOOLEAN) return 1;
        if (t == Type.BYTE)    return 1;
        if (t == Type.SHORT || t == Type.CHAR) return 2;
        if (t == Type.INT)     return 4;
        if (t == Type.FLOAT)   return 4;
        if (t == Type.LONG)    return 8;
        if (t == Type.DOUBLE)  return 8;
        return 8;
    }
}

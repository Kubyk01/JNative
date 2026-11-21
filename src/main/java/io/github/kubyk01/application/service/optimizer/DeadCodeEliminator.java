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
import io.github.kubyk01.util.LlvmUtil;
import io.github.kubyk01.util.ScratchIdentityMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;

@Slf4j
@RequiredArgsConstructor
public class DeadCodeEliminator {

    private final Module module;

    private Scheduler scheduler = Schedulers.single();

    private Set<String> reflectivelyReadStaticFields = Set.of();

    public void setReflectivelyReadStaticFields(Set<String> fields) {
        this.reflectivelyReadStaticFields = fields != null ? fields : Set.of();
    }

    public void setScheduler(Scheduler scheduler) {
        if (scheduler != null) this.scheduler = scheduler;
    }

    private void forEachParallel(List<Function> functions, Consumer<Function> action) {
        LlvmUtil.awaitMono(Flux.fromIterable(functions)
            .parallel()
            .runOn(scheduler)
            .doOnNext(action)
            .sequential()
            .then());
    }

    public void eliminate() {
        boolean changed = true;
        int iterations = 0;
        final int maxIterations = 20;

        while (changed && iterations++ < maxIterations) {
            changed = false;

            Set<String> readStaticFields = collectReadStaticFields();
            Set<Integer> loadedLocals   = collectLoadedLocals();
            AtomicBoolean removed = new AtomicBoolean();
            forEachParallel(new ArrayList<>(module.getFunctions()), func -> {
                if (func.getEntryBlock() != null && removeDead(func, readStaticFields, loadedLocals)) {
                    removed.set(true);
                }
            });
            changed = removed.get();
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
        List<? extends Function> jnLoop1 = module.getFunctions();
        for (Function func : jnLoop1) {
            String name = func.getName();
            if (!name.contains("__clinit__")) continue;
            if (isEffectivelyEmpty(func)) {
                emptyClinits.add(name);
            }
        }

        if (emptyClinits.isEmpty()) return;

        List<? extends Function> jnLoop2 = module.getFunctions();
        for (Function func : jnLoop2) {
            if (emptyClinits.contains(func.getName())) continue;
            for (int blockIdx1 = 0; blockIdx1 < func.getBlocks().size(); blockIdx1++) {
                BasicBlock block = func.getBlocks().get(blockIdx1);
                block.getInstructions().removeIf(inst -> isCallTo(inst, emptyClinits));
            }
        }

        Set<Function> doomed = new HashSet<>();
        List<? extends Function> jnLoop3 = module.getFunctions();
        for (Function func : jnLoop3) {
            if (emptyClinits.contains(func.getName())) doomed.add(func);
        }
        module.removeFunctions(doomed);

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
        List<Function> functions = module.getFunctions();
        for (Function func : functions) {
            if (func.getEntryBlock() == null) continue;
            List<BasicBlock> blocks = func.getBlocks();
            for (BasicBlock block : blocks) {
                List<Instruction> insts = block.getInstructions();
                for (Instruction inst : insts) {
                    if (inst.getOpcode() == Opcode.LOAD && inst.getLocalIndex() >= 0) {
                        loaded.add(inst.getLocalIndex());
                    }
                }
            }
        }
        return loaded;
    }

    private Set<String> collectReadStaticFields() {
        Set<String> fields = ConcurrentHashMap.newKeySet();
        fields.addAll(reflectivelyReadStaticFields);
        forEachParallel(new ArrayList<>(module.getFunctions()), func -> {
            if (func.getEntryBlock() == null) return;
            List<BasicBlock> blocks = func.getBlocks();
            for (BasicBlock block : blocks) {
                List<Instruction> insts = block.getInstructions();
                for (Instruction inst : insts) {
                    if (inst.getOpcode() == Opcode.GET_STATIC) {
                        String name = extractFieldName(inst);
                        if (name != null) fields.add(name);
                    }
                }
            }
        });
        return fields;
    }

    private static final class Scratch {
        final ScratchIdentityMap<Value, Boolean> used = new ScratchIdentityMap<>();
        final ArrayDeque<Value> worklist = new ArrayDeque<>();
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private boolean removeDead(Function func, Set<String> readStaticFields, Set<Integer> loadedLocals) {
        Scratch scratch = SCRATCH.get();
        ScratchIdentityMap<Value, Boolean> used = scratch.used;
        ArrayDeque<Value> worklist = scratch.worklist;
        used.clear();
        worklist.clear();
        List<BasicBlock> blocks = func.getBlocks();
        for (BasicBlock block : blocks) {
            List<Instruction> insts = block.getInstructions();
            for (Instruction inst : insts) {
                if (isRoot(inst, readStaticFields)) markOperands(inst, used, worklist);
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                mark(terminatorOperand(term), used, worklist);
                if (term instanceof CondBranchTerminator cbt) mark(cbt.getCondition(), used, worklist);
            }
        }
        while (!worklist.isEmpty()) {
            Value v = worklist.poll();
            if (v instanceof Temporary t) {
                Instruction def = t.getDefiningInstruction();
                if (def != null) markOperands(def, used, worklist);
            }
        }
        boolean removed = false;
        for (BasicBlock block : blocks) {
            List<Instruction> insts = block.getInstructions();
            int w = 0;
            for (int i = 0; i < insts.size(); i++) {
                Instruction inst = insts.get(i);
                if (shouldRemove(inst, used, readStaticFields, loadedLocals)) {
                    removed = true;
                    continue;
                }
                if (w != i) insts.set(w, inst);
                w++;
            }
            if (w < insts.size()) insts.subList(w, insts.size()).clear();
        }
        return removed;
    }

    private static void markOperands(Instruction inst, ScratchIdentityMap<Value, Boolean> used, ArrayDeque<Value> worklist) {
        List<Value> ops = inst.getOperands();
        for (Value op : ops) mark(op, used, worklist);
    }

    private static void mark(Value v, ScratchIdentityMap<Value, Boolean> used, ArrayDeque<Value> worklist) {
        if (v != null && used.put(v, Boolean.TRUE)) worklist.add(v);
    }

    private static Value terminatorOperand(Terminator term) {
        if (term instanceof ReturnTerminator rt) return rt.getValue();
        if (term instanceof ThrowTerminator tt) return tt.getException();
        if (term instanceof LookupSwitchTerminator lst) return lst.getKey();
        if (term instanceof TableSwitchTerminator tst) return tst.getKey();
        if (term instanceof IndirectBranchTerminator ibt) return ibt.getTargetBlock();
        return null;
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
                                 ScratchIdentityMap<Value, Boolean> usedValues,
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
            return inst.getResult() != null && !usedValues.containsKey(inst.getResult());
        }

        if (isPure(op) && inst.getResult() != null) {
            return !usedValues.containsKey(inst.getResult());
        }

        if ((op == Opcode.VIRTUAL_CALL
            || op == Opcode.INTERFACE_CALL
            || op == Opcode.STATIC_CALL
            || op == Opcode.SPECIAL_CALL
            || op == Opcode.CALL)
            && inst.getResult() != null
            && !usedValues.containsKey(inst.getResult())) {
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
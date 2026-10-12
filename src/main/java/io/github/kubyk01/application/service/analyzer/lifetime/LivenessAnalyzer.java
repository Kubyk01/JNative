package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.ir.*;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.util.LlvmUtil;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.kubyk01.util.LlvmUtil.canonicalCalleeKey;
import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;

@RequiredArgsConstructor
public class LivenessAnalyzer {

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final Map<String, FunctionSummary> summaries;

    private Scheduler scheduler = Schedulers.single();

    public void setScheduler(Scheduler scheduler) {
        if (scheduler != null) this.scheduler = scheduler;
    }

    // Points-to cache: aliasResult is frozen once escape analysis is done,
    // so a concurrent map is safe and saves repeated lookups.
    private final Map<Value, Set<AllocationSite>> pointsToCache = new ConcurrentHashMap<>();

    private Map<BasicBlock, Set<AllocationSite>> liveInGlobal;
    private Map<BasicBlock, Set<AllocationSite>> liveOutGlobal;

    private Set<AllocationSite> getPointsTo(Value v) {
        if (v == null) return Collections.emptySet();
        return pointsToCache.computeIfAbsent(v,
            key -> aliasResult.getPointsTo(key).getSites());
    }

    public void computeLiveSets(Collection<AllocationSite> allSites) {
        Set<AllocationSite> allowed = new HashSet<>(allSites);

        List<Function> live = new ArrayList<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() != null) live.add(f);
        }
        if (live.isEmpty()) {
            liveInGlobal = Collections.emptyMap();
            liveOutGlobal = Collections.emptyMap();
            return;
        }

        // -------- Phase 1: use/def for every block, in parallel --------
        Map<BasicBlock, Set<AllocationSite>> use = new ConcurrentHashMap<>();
        Map<BasicBlock, Set<AllocationSite>> def = new ConcurrentHashMap<>();

        LlvmUtil.awaitMono(Flux.fromIterable(live)
            .parallel()
            .runOn(scheduler)
            .doOnNext(func -> {
                for (BasicBlock block : func.getBlocks()) {
                    Set<AllocationSite> blockUse = new HashSet<>();
                    Set<AllocationSite> blockDef = new HashSet<>();
                    for (Instruction inst : block.getInstructions()) {
                        blockUse.addAll(getAllocationSitesUsed(inst, allowed));
                        blockDef.addAll(getAllocationSitesDef(inst, allowed));
                    }
                    Terminator term = block.getTerminator();
                    if (term != null) {
                        blockUse.addAll(getAllocationSitesUsed(term, allowed));
                    }
                    use.put(block, blockUse);
                    def.put(block, blockDef);
                }
            })
            .sequential()
            .then()
        );

        // -------- Phase 2: liveIn/liveOut storage, concurrent --------
        liveInGlobal = new ConcurrentHashMap<>();
        liveOutGlobal = new ConcurrentHashMap<>();
        for (Function func : live) {
            for (BasicBlock block : func.getBlocks()) {
                liveInGlobal.put(block, new HashSet<>());
                liveOutGlobal.put(block, new HashSet<>());
            }
        }

        // -------- Phase 3: per-function fixed point, in parallel --------
        //
        // The dataflow equations only reference block.getSuccessors(),
        // i.e. blocks inside the same function. use/def are frozen
        // after phase 1. Consequently the fixed point for each function
        // is independent from every other function, and the whole
        // iteration can run per-function in parallel.
        LlvmUtil.awaitMono(Flux.fromIterable(live)
            .parallel()
            .runOn(scheduler)
            .doOnNext(func -> solveFunction(func, use, def))
            .sequential()
            .then()
        );
    }

    private void solveFunction(Function func,
                               Map<BasicBlock, Set<AllocationSite>> use,
                               Map<BasicBlock, Set<AllocationSite>> def) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BasicBlock block : func.getBlocks()) {
                Set<AllocationSite> newLiveOut = new HashSet<>();
                for (BasicBlock succ : block.getSuccessors()) {
                    Set<AllocationSite> succLiveIn = liveInGlobal.get(succ);
                    if (succLiveIn != null) newLiveOut.addAll(succLiveIn);
                }
                if (!newLiveOut.equals(liveOutGlobal.get(block))) {
                    liveOutGlobal.put(block, newLiveOut);
                    changed = true;
                }

                Set<AllocationSite> newLiveIn = new HashSet<>(use.get(block));
                Set<AllocationSite> liveOutMinusDef = new HashSet<>(newLiveOut);
                liveOutMinusDef.removeAll(def.get(block));
                newLiveIn.addAll(liveOutMinusDef);
                if (!newLiveIn.equals(liveInGlobal.get(block))) {
                    liveInGlobal.put(block, newLiveIn);
                    changed = true;
                }
            }
        }
    }

    public Set<AllocationSite> getLiveIn(BasicBlock block) {
        return liveInGlobal != null
            ? liveInGlobal.getOrDefault(block, Collections.emptySet())
            : Collections.emptySet();
    }

    public Set<AllocationSite> getLiveOut(BasicBlock block) {
        return liveOutGlobal != null
            ? liveOutGlobal.getOrDefault(block, Collections.emptySet())
            : Collections.emptySet();
    }

    /**
     * A single syntactic occurrence of a value as an operand: the block it
     * appears in, the index of the instruction that consumes it (equal to
     * {@code block.getInstructions().size()} when the consumer is the
     * terminator), and the value itself.
     */
    public record ValueLastUse(BasicBlock block, int instructionIndex, Value value) {}

    /**
     * Value-level liveness support: for every value that appears as an
     * operand anywhere in {@code func}, the list of its occurrences. The
     * last element of each list is the value's last syntactic use. This is
     * a purely syntactic index; it makes no attempt to be a true liveness
     * analysis, which is deliberate — the lifetime pass only needs the
     * candidate death sites and filters them by the block-level live sets.
     */
    public Map<Value, List<ValueLastUse>> computeValueLastUses(Function func) {
        Map<Value, List<ValueLastUse>> lastUses = new HashMap<>();
        for (BasicBlock block : func.getBlocks()) {
            List<Instruction> insts = block.getInstructions();
            for (int i = 0; i < insts.size(); i++) {
                for (Value operand : insts.get(i).getOperands()) {
                    if (operand == null) continue;
                    lastUses.computeIfAbsent(operand, k -> new ArrayList<>())
                            .add(new ValueLastUse(block, i, operand));
                }
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                for (Value operand : terminatorOperands(term)) {
                    if (operand == null) continue;
                    lastUses.computeIfAbsent(operand, k -> new ArrayList<>())
                            .add(new ValueLastUse(block, insts.size(), operand));
                }
            }
        }
        return lastUses;
    }

    /**
     * The operands a terminator consumes, in the same spirit as
     * {@link Instruction#getOperands()}.
     */
    public List<Value> terminatorOperands(Terminator term) {
        List<Value> result = new ArrayList<>();
        if (term instanceof ReturnTerminator) {
            Value v = ((ReturnTerminator) term).getValue();
            if (v != null) result.add(v);
        } else if (term instanceof ThrowTerminator) {
            Value v = ((ThrowTerminator) term).getException();
            if (v != null) result.add(v);
        } else if (term instanceof CondBranchTerminator) {
            Value v = ((CondBranchTerminator) term).getCondition();
            if (v != null) result.add(v);
        } else if (term instanceof LookupSwitchTerminator) {
            Value v = ((LookupSwitchTerminator) term).getKey();
            if (v != null) result.add(v);
        } else if (term instanceof TableSwitchTerminator) {
            Value v = ((TableSwitchTerminator) term).getKey();
            if (v != null) result.add(v);
        } else if (term instanceof IndirectBranchTerminator) {
            Value v = ((IndirectBranchTerminator) term).getTargetBlock();
            if (v != null) result.add(v);
        }
        return result;
    }

    private Set<AllocationSite> getAllocationSitesDef(Instruction inst,
                                                      Set<AllocationSite> allowed) {
        Set<AllocationSite> result = new HashSet<>();
        Opcode op = inst.getOpcode();
        if (op == Opcode.FREE) {
            if (!inst.getOperands().isEmpty()) {
                Value obj = inst.getOperands().getFirst();
                for (AllocationSite site : getPointsTo(obj)) {
                    if (allowed.contains(site)) result.add(site);
                }
            }
        } else if (op == Opcode.CALL || op == Opcode.VIRTUAL_CALL
                || op == Opcode.INTERFACE_CALL || op == Opcode.STATIC_CALL
                || op == Opcode.SPECIAL_CALL) {
            String calleeName = extractCalleeName(inst);
            if (calleeName == null) return result;
            String calleeKey = canonicalCalleeKey(calleeName);
            FunctionSummary summary = summaries.get(calleeKey);
            if (summary == null) return result;
            List<Value> args = getCallArguments(inst);
            for (int i = 0; i < args.size(); i++) {
                if (summary.getParamsDestroyed().contains(i)) {
                    Value arg = args.get(i);
                    for (AllocationSite site : getPointsTo(arg)) {
                        if (allowed.contains(site)) result.add(site);
                    }
                }
            }
        }
        return result;
    }

    private Set<AllocationSite> getAllocationSitesUsed(Instruction inst,
                                                      Set<AllocationSite> allowed) {
        Set<AllocationSite> result = new HashSet<>();
        for (Value operand : inst.getOperands()) {
            if (operand == null) continue;
            for (AllocationSite site : getPointsTo(operand)) {
                if (allowed.contains(site)) result.add(site);
            }
        }
        return result;
    }

    private Set<AllocationSite> getAllocationSitesUsed(Terminator term,
                                                      Set<AllocationSite> allowed) {
        Set<AllocationSite> result = new HashSet<>();
        Value used = null;
        if (term instanceof ReturnTerminator) {
            used = ((ReturnTerminator) term).getValue();
        } else if (term instanceof ThrowTerminator) {
            used = ((ThrowTerminator) term).getException();
        } else if (term instanceof CondBranchTerminator) {
            used = ((CondBranchTerminator) term).getCondition();
        } else if (term instanceof LookupSwitchTerminator) {
            used = ((LookupSwitchTerminator) term).getKey();
        } else if (term instanceof TableSwitchTerminator) {
            used = ((TableSwitchTerminator) term).getKey();
        }
        if (used != null) {
            for (AllocationSite site : getPointsTo(used)) {
                if (allowed.contains(site)) result.add(site);
            }
        }
        return result;
    }
}

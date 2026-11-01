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

    private final Map<Value, Set<AllocationSite>> pointsToCache = new ConcurrentHashMap<>();
    private final Map<Function, Map<Value, List<ValueLastUse>>> valueLastUsesCache =
        new ConcurrentHashMap<>();

    private Map<BasicBlock, Set<AllocationSite>> liveInGlobal;
    private Map<BasicBlock, Set<AllocationSite>> liveOutGlobal;

    private Set<AllocationSite> getPointsTo(Value v) {
        if (v == null) return Collections.emptySet();
        return pointsToCache.computeIfAbsent(v, key -> aliasResult.getPointsTo(key).getSites());
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
                    if (term != null) blockUse.addAll(getAllocationSitesUsed(term, allowed));
                    use.put(block, blockUse);
                    def.put(block, blockDef);
                }
            })
            .sequential()
            .then()
        );

        liveInGlobal = new ConcurrentHashMap<>();
        liveOutGlobal = new ConcurrentHashMap<>();
        for (Function func : live) {
            for (BasicBlock block : func.getBlocks()) {
                liveInGlobal.put(block, new HashSet<>());
                liveOutGlobal.put(block, new HashSet<>());
            }
        }

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
        Set<AllocationSite> newLiveOut = new HashSet<>();
        Set<AllocationSite> newLiveIn  = new HashSet<>();
        Set<AllocationSite> temp       = new HashSet<>();

        boolean changed = true;
        while (changed) {
            changed = false;
            for (BasicBlock block : func.getBlocks()) {
                newLiveOut.clear();
                for (BasicBlock succ : block.getSuccessors()) {
                    Set<AllocationSite> succLiveIn = liveInGlobal.get(succ);
                    if (succLiveIn != null && !succLiveIn.isEmpty()) {
                        newLiveOut.addAll(succLiveIn);
                    }
                }
                Set<AllocationSite> currentOut = liveOutGlobal.get(block);
                if (currentOut == null) {
                    liveOutGlobal.put(block, new HashSet<>(newLiveOut));
                    changed = true;
                } else if (!newLiveOut.equals(currentOut)) {
                    currentOut.clear();
                    currentOut.addAll(newLiveOut);
                    changed = true;
                }

                newLiveIn.clear();
                Set<AllocationSite> u = use.get(block);
                if (u != null && !u.isEmpty()) newLiveIn.addAll(u);

                temp.clear();
                temp.addAll(newLiveOut);
                Set<AllocationSite> d = def.get(block);
                if (d != null && !d.isEmpty()) temp.removeAll(d);
                newLiveIn.addAll(temp);

                Set<AllocationSite> currentIn = liveInGlobal.get(block);
                if (currentIn == null) {
                    liveInGlobal.put(block, new HashSet<>(newLiveIn));
                    changed = true;
                } else if (!newLiveIn.equals(currentIn)) {
                    currentIn.clear();
                    currentIn.addAll(newLiveIn);
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

    public Map<BasicBlock, Set<AllocationSite>> getLiveOutMap() {
        return liveOutGlobal;
    }

    public record ValueLastUse(BasicBlock block, int instructionIndex, Value value) {}

    public Map<Value, List<ValueLastUse>> computeValueLastUses(Function func) {
        return valueLastUsesCache.computeIfAbsent(func, this::doComputeValueLastUses);
    }

    private Map<Value, List<ValueLastUse>> doComputeValueLastUses(Function func) {
        Map<Value, List<ValueLastUse>> lastUses = new HashMap<>();
        for (BasicBlock block : func.getBlocks()) {
            List<Instruction> insts = block.getInstructions();
            for (int i = 0; i < insts.size(); i++) {
                Instruction inst = insts.get(i);
                if (inst.getOpcode() == Opcode.PHI) continue;
                for (Value operand : inst.getOperands()) {
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

    public List<Value> terminatorOperands(Terminator term) {
        List<Value> result = new ArrayList<>();
        if (term instanceof ReturnTerminator rt) { if (rt.getValue() != null) result.add(rt.getValue()); }
        else if (term instanceof ThrowTerminator tt) { if (tt.getException() != null) result.add(tt.getException()); }
        else if (term instanceof CondBranchTerminator cbt) { if (cbt.getCondition() != null) result.add(cbt.getCondition()); }
        else if (term instanceof LookupSwitchTerminator lst) { if (lst.getKey() != null) result.add(lst.getKey()); }
        else if (term instanceof TableSwitchTerminator tst) { if (tst.getKey() != null) result.add(tst.getKey()); }
        else if (term instanceof IndirectBranchTerminator ibt) { if (ibt.getTargetBlock() != null) result.add(ibt.getTargetBlock()); }
        return result;
    }

    private Set<AllocationSite> getAllocationSitesDef(Instruction inst, Set<AllocationSite> allowed) {
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
            FunctionSummary summary = summaries.get(canonicalCalleeKey(calleeName));
            if (summary == null) return result;
            List<Value> args = getCallArguments(inst);
            for (int i = 0; i < args.size(); i++) {
                if (summary.getParamsDestroyed().contains(i)) {
                    for (AllocationSite site : getPointsTo(args.get(i))) {
                        if (allowed.contains(site)) result.add(site);
                    }
                }
            }
        }
        return result;
    }

    private Set<AllocationSite> getAllocationSitesUsed(Instruction inst, Set<AllocationSite> allowed) {
        Set<AllocationSite> result = new HashSet<>();
        for (Value operand : inst.getOperands()) {
            if (operand == null) continue;
            for (AllocationSite site : getPointsTo(operand)) {
                if (allowed.contains(site)) result.add(site);
            }
        }
        return result;
    }

    private Set<AllocationSite> getAllocationSitesUsed(Terminator term, Set<AllocationSite> allowed) {
        Set<AllocationSite> result = new HashSet<>();
        Value used = null;
        if (term instanceof ReturnTerminator rt) used = rt.getValue();
        else if (term instanceof ThrowTerminator tt) used = tt.getException();
        else if (term instanceof CondBranchTerminator cbt) used = cbt.getCondition();
        else if (term instanceof LookupSwitchTerminator lst) used = lst.getKey();
        else if (term instanceof TableSwitchTerminator tst) used = tst.getKey();
        if (used != null) {
            for (AllocationSite site : getPointsTo(used)) {
                if (allowed.contains(site)) result.add(site);
            }
        }
        return result;
    }
}
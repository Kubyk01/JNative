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

    private final Map<AllocationSite, Integer> siteIds = new HashMap<>();
    private List<AllocationSite> sitesById = List.of();

    private Map<BasicBlock, BitSet> liveInBits;
    private Map<BasicBlock, BitSet> liveOutBits;

    private final Map<BasicBlock, Set<AllocationSite>> liveInCache = new ConcurrentHashMap<>();
    private final Map<BasicBlock, Set<AllocationSite>> liveOutCache = new ConcurrentHashMap<>();

    private Set<AllocationSite> getPointsTo(Value v) {
        if (v == null) return Collections.emptySet();
        return pointsToCache.computeIfAbsent(v, key -> aliasResult.getPointsTo(key).getSites());
    }

    public void computeLiveSets(Collection<AllocationSite> allSites) {
        siteIds.clear();
        List<AllocationSite> ordered = new ArrayList<>(allSites.size());
        for (AllocationSite site : allSites) {
            if (siteIds.putIfAbsent(site, ordered.size()) == null) ordered.add(site);
        }
        sitesById = ordered;
        liveInCache.clear();
        liveOutCache.clear();

        List<Function> live = new ArrayList<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() != null) live.add(f);
        }
        liveInBits = new ConcurrentHashMap<>();
        liveOutBits = new ConcurrentHashMap<>();
        if (live.isEmpty()) return;

        LlvmUtil.awaitMono(Flux.fromIterable(live)
            .parallel()
            .runOn(scheduler)
            .doOnNext(this::solveFunction)
            .sequential()
            .then()
        );
    }

    private void solveFunction(Function func) {
        List<BasicBlock> blocks = func.getBlocks();
        int n = blocks.size();
        if (n == 0) return;

        Map<BasicBlock, Integer> blockIndex = new HashMap<>(n * 2);
        BitSet[] use = new BitSet[n];
        BitSet[] def = new BitSet[n];
        for (int i = 0; i < n; i++) {
            BasicBlock block = blocks.get(i);
            blockIndex.put(block, i);
            BitSet u = new BitSet();
            BitSet d = new BitSet();
            for (Instruction inst : block.getInstructions()) {
                collectUses(inst, u);
                collectDefs(inst, d);
            }
            Terminator term = block.getTerminator();
            if (term != null) collectTerminatorUses(term, u);
            use[i] = u;
            def[i] = d;
        }

        int[][] succs = new int[n][];
        for (int i = 0; i < n; i++) {
            List<BasicBlock> ss = blocks.get(i).getSuccessors();
            int[] idx = new int[ss.size()];
            int k = 0;
            for (BasicBlock succ : ss) {
                Integer si = blockIndex.get(succ);
                if (si != null) idx[k++] = si;
            }
            succs[i] = k == idx.length ? idx : Arrays.copyOf(idx, k);
        }

        BitSet[] liveIn = new BitSet[n];
        BitSet[] liveOut = new BitSet[n];
        for (int i = 0; i < n; i++) {
            liveIn[i] = new BitSet();
            liveOut[i] = new BitSet();
        }

        BitSet newLiveOut = new BitSet();
        BitSet newLiveIn = new BitSet();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                newLiveOut.clear();
                for (int si : succs[i]) newLiveOut.or(liveIn[si]);
                if (!newLiveOut.equals(liveOut[i])) {
                    liveOut[i] = (BitSet) newLiveOut.clone();
                    changed = true;
                }

                newLiveIn.clear();
                newLiveIn.or(newLiveOut);
                newLiveIn.andNot(def[i]);
                newLiveIn.or(use[i]);
                if (!newLiveIn.equals(liveIn[i])) {
                    liveIn[i] = (BitSet) newLiveIn.clone();
                    changed = true;
                }
            }
        }

        for (int i = 0; i < n; i++) {
            BasicBlock block = blocks.get(i);
            liveInBits.put(block, liveIn[i]);
            liveOutBits.put(block, liveOut[i]);
        }
    }

    private void setSites(Set<AllocationSite> sites, BitSet out) {
        for (AllocationSite site : sites) {
            Integer id = siteIds.get(site);
            if (id != null) out.set(id);
        }
    }

    private void collectUses(Instruction inst, BitSet out) {
        for (Value operand : inst.getOperands()) {
            if (operand != null) setSites(getPointsTo(operand), out);
        }
    }

    private void collectDefs(Instruction inst, BitSet out) {
        Opcode op = inst.getOpcode();
        if (op == Opcode.FREE) {
            if (!inst.getOperands().isEmpty()) setSites(getPointsTo(inst.getOperands().getFirst()), out);
        } else if (op == Opcode.CALL || op == Opcode.VIRTUAL_CALL
            || op == Opcode.INTERFACE_CALL || op == Opcode.STATIC_CALL
            || op == Opcode.SPECIAL_CALL) {
            String calleeName = extractCalleeName(inst);
            if (calleeName == null) return;
            FunctionSummary summary = summaries.get(canonicalCalleeKey(calleeName));
            if (summary == null) return;
            List<Value> args = getCallArguments(inst);
            for (int i = 0; i < args.size(); i++) {
                if (summary.getParamsDestroyed().contains(i)) setSites(getPointsTo(args.get(i)), out);
            }
        }
    }

    private void collectTerminatorUses(Terminator term, BitSet out) {
        Value used = null;
        if (term instanceof ReturnTerminator rt) used = rt.getValue();
        else if (term instanceof ThrowTerminator tt) used = tt.getException();
        else if (term instanceof CondBranchTerminator cbt) used = cbt.getCondition();
        else if (term instanceof LookupSwitchTerminator lst) used = lst.getKey();
        else if (term instanceof TableSwitchTerminator tst) used = tst.getKey();
        if (used != null) setSites(getPointsTo(used), out);
    }

    public Set<AllocationSite> getLiveIn(BasicBlock block) {
        return materialize(block, liveInBits, liveInCache);
    }

    public Set<AllocationSite> getLiveOut(BasicBlock block) {
        return materialize(block, liveOutBits, liveOutCache);
    }

    private Set<AllocationSite> materialize(BasicBlock block, Map<BasicBlock, BitSet> bitsMap,
                                            Map<BasicBlock, Set<AllocationSite>> cache) {
        Set<AllocationSite> cached = cache.get(block);
        if (cached != null) return cached;
        BitSet bits = bitsMap != null ? bitsMap.get(block) : null;
        if (bits == null) return Collections.emptySet();
        Set<AllocationSite> out = new HashSet<>(Math.max(4, bits.cardinality() * 2));
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) out.add(sitesById.get(i));
        Set<AllocationSite> prev = cache.putIfAbsent(block, out);
        return prev != null ? prev : out;
    }

    public Map<BasicBlock, Set<AllocationSite>> getLiveOutMap() {
        if (liveOutBits == null) return null;
        Map<BasicBlock, Set<AllocationSite>> m = new ConcurrentHashMap<>();
        for (BasicBlock b : liveOutBits.keySet()) m.put(b, getLiveOut(b));
        return m;
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
}

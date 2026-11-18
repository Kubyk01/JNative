package io.github.kubyk01.domain.analyzer.lifetime.strategies;

import io.github.kubyk01.application.service.analyzer.lifetime.LivenessAnalyzer;
import io.github.kubyk01.application.service.analyzer.ssa.DominatorTree;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.UndefinedValue;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.util.LlvmUtil;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AllocatorLastUseLifetimeStrategy implements LifetimeAnalysisStrategies {

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public LifetimeAnalysisResult analyze(Module module, Set<AllocationSite> sites, AliasAnalysisResult aliasResult,
                                          EscapeAnalysisResult escapeResult, LivenessAnalyzer liveness,
                                          Map<Function, DominatorTree> domTrees,
                                          Map<Function, Map<BasicBlock, Set<BasicBlock>>> loops,
                                          Scheduler scheduler) {
        Map<String, List<AllocationSite>> byFunction = new HashMap<>();
        for (AllocationSite site : sites) {
            byFunction.computeIfAbsent(site.getMethodName(), k -> new ArrayList<>()).add(site);
        }
        Map<AllocationSite, Value> siteToValue = aliasResult.getAllocationSiteToValue();
        List<LifetimeAnalysisResult> parts = LlvmUtil.awaitFlux(Flux.fromIterable(byFunction.values())
                .parallel()
                .runOn(scheduler)
                .map(group -> analyzeFunction(module.getFunction(group.getFirst().getMethodName()), group,
                        aliasResult, escapeResult, liveness, domTrees, loops, siteToValue))
                .sequential());
        LifetimeAnalysisResult result = new LifetimeAnalysisResult();
        if (parts == null) return result;
        for (LifetimeAnalysisResult part : parts) {
            result.getDestructionPoints().putAll(part.getDestructionPoints());
            result.getUnresolved().addAll(part.getUnresolved());
        }
        return result;
    }

    private LifetimeAnalysisResult analyzeFunction(Function func, List<AllocationSite> group,
                                                   AliasAnalysisResult aliasResult,
                                                   EscapeAnalysisResult escapeResult,
                                                   LivenessAnalyzer liveness,
                                                   Map<Function, DominatorTree> domTrees,
                                                   Map<Function, Map<BasicBlock, Set<BasicBlock>>> loops,
                                                   Map<AllocationSite, Value> siteToValue) {
        LifetimeAnalysisResult out = new LifetimeAnalysisResult();
        DominatorTree dom = func == null || func.getEntryBlock() == null ? null : domTrees.get(func);
        if (dom == null) return out;
        Map<AllocationSite, Value> allocVals = new HashMap<>();
        List<AllocationSite> withValue = new ArrayList<>();
        for (AllocationSite site : group) {
            EscapeStatus status = escapeResult.getSiteStatus(site);
            if (status == EscapeStatus.GLOBAL || status == EscapeStatus.THREAD || status == EscapeStatus.RETURN) continue;
            Value allocVal = siteToValue.get(site);
            if (allocVal == null) continue;
            allocVals.put(site, allocVal);
            withValue.add(site);
        }
        if (withValue.isEmpty()) return out;

        Map<AllocationSite, Set<Value>> aliases = collectAliases(withValue, func, allocVals, aliasResult, liveness);
        Map<Value, List<LivenessAnalyzer.ValueLastUse>> uses = liveness.computeValueLastUses(func);
        Map<BasicBlock, Set<BasicBlock>> funcLoops = loops.getOrDefault(func, Map.of());
        for (AllocationSite site : withValue) {
            List<DestructionPoint> points = computeSite(site, func, dom, funcLoops, allocVals.get(site),
                    aliases.get(site), uses, liveness);
            if (points == null) {
                out.getUnresolved().add(site);
            } else {
                out.getDestructionPoints().put(site, new HashSet<>(points));
            }
        }
        return out;
    }

    private List<DestructionPoint> computeSite(AllocationSite site, Function func, DominatorTree dom,
                                               Map<BasicBlock, Set<BasicBlock>> loops, Value allocVal,
                                               Set<Value> roots, Map<Value, List<LivenessAnalyzer.ValueLastUse>> uses,
                                               LivenessAnalyzer liveness) {
        List<DestructionPoint> points = new ArrayList<>();
        for (Value root : roots) {
            List<LivenessAnalyzer.ValueLastUse> rootUses = uses.get(root);
            if (rootUses == null || rootUses.isEmpty()) continue;
            LivenessAnalyzer.ValueLastUse last = rootUses.getLast();
            BasicBlock block = last.block();
            if (!availableAt(root, block, dom)) continue;
            if (!liveness.getLiveIn(block).contains(site) || liveness.getLiveOut(block).contains(site)) continue;
            List<Instruction> insts = block.getInstructions();
            int idx = last.instructionIndex();
            Instruction before = idx >= 0 && idx + 1 < insts.size() ? insts.get(idx + 1) : null;
            points.add(new DestructionPoint(block, before, root));
        }

        List<DestructionPoint> filtered = new ArrayList<>();
        for (DestructionPoint p : points) {
            if (!insideLiveLoop(loops, p.getBlock(), site, liveness)) filtered.add(p);
        }
        if (!filtered.isEmpty()) return dedup(filtered, dom);

        List<BasicBlock> returnBlocks = new ArrayList<>();
        for (BasicBlock block : func.getBlocks()) {
            if (block.getTerminator() instanceof ReturnTerminator) returnBlocks.add(block);
        }
        if (returnBlocks.isEmpty()) return null;
        BasicBlock lca = dom.getLCA(returnBlocks);
        if (lca == null || !availableAt(allocVal, lca, dom)) return null;
        return List.of(new DestructionPoint(lca, null, allocVal));
    }

    static boolean availableAt(Value v, BasicBlock block, DominatorTree dom) {
        return switch (v) {
            case null -> false;
            case Constant ignored -> true;
            case Parameter ignored -> true;
            case UndefinedValue ignored -> true;
            case Temporary t -> {
                Instruction def = t.getDefiningInstruction();
                BasicBlock defBlock = def == null ? null : def.getParent();
                yield defBlock != null && (defBlock == block || dom.dominates(defBlock, block));
            }
            default -> false;
        };
    }

    static boolean insideLiveLoop(Map<BasicBlock, Set<BasicBlock>> loops, BasicBlock block,
                                  AllocationSite site, LivenessAnalyzer liveness) {
        for (Map.Entry<BasicBlock, Set<BasicBlock>> e : loops.entrySet()) {
            if (e.getValue().contains(block) && liveness.getLiveIn(e.getKey()).contains(site)) return true;
        }
        return false;
    }

    private Map<AllocationSite, Set<Value>> collectAliases(List<AllocationSite> sites, Function func,
                                                           Map<AllocationSite, Value> allocVals,
                                                           AliasAnalysisResult aliasResult,
                                                           LivenessAnalyzer liveness) {
        int k = sites.size();
        AllocationSite[] byIndex = sites.toArray(new AllocationSite[0]);
        Arrays.sort(byIndex, Comparator.comparingInt(AllocationSite::getId));
        int[] ids = new int[k];
        List<Set<Value>> sets = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            ids[i] = byIndex[i].getId();
            Set<Value> aliases = new LinkedHashSet<>();
            aliases.add(allocVals.get(byIndex[i]));
            sets.add(aliases);
        }
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                addAlias(inst.getResult(), ids, sets, aliasResult);
                for (Value op : inst.getOperands()) addAlias(op, ids, sets, aliasResult);
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                for (Value op : liveness.terminatorOperands(term)) addAlias(op, ids, sets, aliasResult);
            }
        }
        for (Parameter p : func.getParameters()) addAlias(p, ids, sets, aliasResult);
        Map<AllocationSite, Set<Value>> out = new HashMap<>(k * 2);
        for (int i = 0; i < k; i++) out.put(byIndex[i], sets.get(i));
        return out;
    }

    private void addAlias(Value v, int[] ids, List<Set<Value>> sets, AliasAnalysisResult aliasResult) {
        if (v == null) return;
        PointsToSet pts = aliasResult.getPointsTo(v);
        if (pts.isEmpty()) return;
        if (pts.size() <= ids.length) {
            for (int id : pts.rawIds()) {
                int i = Arrays.binarySearch(ids, id);
                if (i >= 0) sets.get(i).add(v);
            }
        } else {
            for (int i = 0; i < ids.length; i++) {
                if (pts.containsId(ids[i])) sets.get(i).add(v);
            }
        }
    }

    private List<DestructionPoint> dedup(List<DestructionPoint> points, DominatorTree dom) {
        List<DestructionPoint> kept = new ArrayList<>();
        Set<BasicBlock> seen = new HashSet<>();
        for (DestructionPoint p : points) {
            if (!seen.add(p.getBlock())) continue;
            boolean dominated = false;
            for (DestructionPoint q : points) {
                if (q.getBlock() != p.getBlock() && dom.dominates(q.getBlock(), p.getBlock())) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) kept.add(p);
        }
        return kept;
    }
}

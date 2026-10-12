package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.application.service.analyzer.ssa.DominatorTree;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.ir.*;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.util.LlvmUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class LifetimeAnalyzer {

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final EscapeAnalysisResult escapeResult;
    private final Map<String, FunctionSummary> summaries;
    private Scheduler scheduler = Schedulers.single();

    public LifetimeAnalyzer(Module module,
                            AliasAnalysisResult aliasResult,
                            EscapeAnalysisResult escapeResult,
                            Map<String, FunctionSummary> summaries) {
        this.module = module;
        this.aliasResult = aliasResult;
        this.escapeResult = escapeResult;
        this.summaries = summaries;
    }

    public void setScheduler(Scheduler scheduler) {
        if (scheduler != null) this.scheduler = scheduler;
    }

    public LifetimeAnalysisResult analyze(Map<AllocationSite, Value> siteToValue) {
        LifetimeAnalysisResult result = new LifetimeAnalysisResult();

        // -------- 1. Collect sites (parallel over functions) -----
        AllocationSiteCollector collector =
            new AllocationSiteCollector(module, aliasResult, scheduler);
        List<AllocationSite> allSites = collector.collect();

        // -------- 2. Cyclic reference detection --------
        CyclicReferenceDetector cyclicDetector =
            new CyclicReferenceDetector(aliasResult.getGraph(), allSites);
        Set<AllocationSite> cyclicSites = cyclicDetector.detect();
        result.getUnresolved().addAll(cyclicSites);

        // -------- 3. Liveness (now internally parallel) --------
        LivenessAnalyzer livenessAnalyzer =
            new LivenessAnalyzer(module, aliasResult, summaries);
        livenessAnalyzer.setScheduler(scheduler);
        livenessAnalyzer.computeLiveSets(allSites);

        // -------- 4. Dominator trees + natural loops, in parallel --------
        List<Function> live = new ArrayList<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() != null) live.add(f);
        }

        Map<Function, DominatorTree> domTrees = new ConcurrentHashMap<>();
        Map<Function, Map<BasicBlock, Set<BasicBlock>>> loopHeaders =
            new ConcurrentHashMap<>();

        LlvmUtil.awaitMono(Flux.fromIterable(live)
            .parallel()
            .runOn(scheduler)
            .doOnNext(func -> {
                DominatorTree domTree = new DominatorTree(func);
                domTrees.put(func, domTree);
                loopHeaders.put(func, detectNaturalLoops(func, domTree));
            })
            .sequential()
            .then()
        );

        // -------- 5. Per-site analysis, in parallel --------
        //
        // Each site writes to its own entry in the destination maps and
        // reads only from frozen aliasResult / escapeResult / liveness
        // and the precomputed dominator/loop structures. Independent
        // across sites.
        Set<AllocationSite> skip = result.getUnresolved();

        List<AllocationSite> candidates = new ArrayList<>(allSites.size());
        for (AllocationSite site : allSites) {
            if (skip.contains(site)) continue;
            candidates.add(site);
        }

        // Each worker accumulates its own per-site results, then the
        // main thread merges them sequentially. That keeps the result
        // maps as plain HashMaps (no concurrency concerns) while still
        // running the analysis itself in parallel.
        List<SiteResult> siteResults = LlvmUtil.awaitFlux(
            Flux.fromIterable(candidates)
                .parallel()
                .runOn(scheduler)
                .map(site -> computeSite(site, domTrees, loopHeaders,
                                         livenessAnalyzer, siteToValue))
                .sequential()
        );

        if (siteResults != null) {
            for (SiteResult sr : siteResults) {
                if (sr.unresolved()) {
                    result.getUnresolved().add(sr.site());
                } else {
                    for (DestructionPoint point : sr.points()) {
                        result.getDestructionPoints()
                              .computeIfAbsent(sr.site(), k -> new HashSet<>())
                              .add(point);
                    }
                }
            }
        }

        return result;
    }

    private SiteResult computeSite(AllocationSite site,
                                   Map<Function, DominatorTree> domTrees,
                                   Map<Function, Map<BasicBlock, Set<BasicBlock>>> loopHeaders,
                                   LivenessAnalyzer livenessAnalyzer,
                                   Map<AllocationSite, Value> siteToValue) {
        EscapeStatus status = escapeResult.getSiteStatus(site);
        if (status == EscapeStatus.GLOBAL
            || status == EscapeStatus.THREAD
            || status == EscapeStatus.RETURN) {
            return new SiteResult(site, List.of(), false);
        }

        Function func = module.getFunction(site.getMethodName());
        if (func == null || func.getEntryBlock() == null) {
            return new SiteResult(site, List.of(), false);
        }

        DominatorTree dom = domTrees.get(func);
        if (dom == null) return new SiteResult(site, List.of(), false);

        Map<BasicBlock, Set<BasicBlock>> loops = loopHeaders.get(func);

        Value allocVal = siteToValue.get(site);
        if (allocVal == null) return new SiteResult(site, List.of(), false);

        // Any value that may point to the site is a legal "root" for the
        // destructor call; pick the last syntactic use of each.
        Set<Value> roots = collectAliases(site, func, allocVal);

        Map<Value, List<LivenessAnalyzer.ValueLastUse>> valueLastUses =
            livenessAnalyzer.computeValueLastUses(func);

        List<DestructionPoint> points = new ArrayList<>();
        for (Value root : roots) {
            List<LivenessAnalyzer.ValueLastUse> uses = valueLastUses.get(root);
            if (uses == null || uses.isEmpty()) continue;

            // The list is built in block/instruction order, so the final
            // entry is the root's last syntactic use.
            LivenessAnalyzer.ValueLastUse last = uses.getLast();
            BasicBlock block = last.block();

            // A real death requires the site to be live on entry to the
            // block but not live on exit. If it is still live on exit the
            // value flows onward and this use is not a death site.
            Set<AllocationSite> liveIn = livenessAnalyzer.getLiveIn(block);
            Set<AllocationSite> liveOut = livenessAnalyzer.getLiveOut(block);
            if (!liveIn.contains(site) || liveOut.contains(site)) continue;

            Instruction before = null;
            List<Instruction> insts = block.getInstructions();
            int idx = last.instructionIndex();
            if (idx >= 0 && idx < insts.size()) {
                before = insts.get(idx);
            }
            points.add(new DestructionPoint(block, before, root));
        }

        List<DestructionPoint> filtered =
            filterPointsInLoops(points, site, loops, livenessAnalyzer);

        if (filtered.isEmpty()) {
            // No provably-dead last use. Fall back to the common
            // post-return point so the object is still released on normal
            // exit; this mirrors the pre-existing conservative behaviour.
            List<BasicBlock> returnBlocks = new ArrayList<>();
            for (BasicBlock block : func.getBlocks()) {
                if (block.getTerminator() instanceof ReturnTerminator) {
                    returnBlocks.add(block);
                }
            }
            if (returnBlocks.isEmpty()) {
                return new SiteResult(site, List.of(), true);
            }
            BasicBlock lca = dom.getLCA(returnBlocks);
            if (lca == null) {
                return new SiteResult(site, List.of(), true);
            }
            return new SiteResult(site,
                List.of(new DestructionPoint(lca, null, allocVal)), false);
        }

        return new SiteResult(site, dedupPoints(filtered, dom), false);
    }

    private record SiteResult(AllocationSite site,
                              List<DestructionPoint> points,
                              boolean unresolved) {}

    /**
     * All IR values in {@code func} whose points-to set contains
     * {@code site}: the allocation itself, any aliasing temporary, and any
     * parameter or field read that may refer to it.
     */
    private Set<Value> collectAliases(AllocationSite site,
                                      Function func,
                                      Value allocVal) {
        Set<Value> aliases = new LinkedHashSet<>();
        aliases.add(allocVal);
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                Value result = inst.getResult();
                if (result != null && pointsToSite(result, site)) {
                    aliases.add(result);
                }
                for (Value op : inst.getOperands()) {
                    if (op != null && pointsToSite(op, site)) {
                        aliases.add(op);
                    }
                }
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                for (Value op : terminatorOperands(term)) {
                    if (op != null && pointsToSite(op, site)) {
                        aliases.add(op);
                    }
                }
            }
        }
        for (Parameter p : func.getParameters()) {
            if (pointsToSite(p, site)) aliases.add(p);
        }
        return aliases;
    }

    private boolean pointsToSite(Value v, AllocationSite site) {
        return aliasResult.getPointsTo(v).getSites().contains(site);
    }

    private List<Value> terminatorOperands(Terminator term) {
        List<Value> out = new ArrayList<>();
        if (term instanceof ReturnTerminator) {
            Value v = ((ReturnTerminator) term).getValue();
            if (v != null) out.add(v);
        } else if (term instanceof ThrowTerminator) {
            Value v = ((ThrowTerminator) term).getException();
            if (v != null) out.add(v);
        } else if (term instanceof CondBranchTerminator) {
            Value v = ((CondBranchTerminator) term).getCondition();
            if (v != null) out.add(v);
        } else if (term instanceof LookupSwitchTerminator) {
            Value v = ((LookupSwitchTerminator) term).getKey();
            if (v != null) out.add(v);
        } else if (term instanceof TableSwitchTerminator) {
            Value v = ((TableSwitchTerminator) term).getKey();
            if (v != null) out.add(v);
        } else if (term instanceof IndirectBranchTerminator) {
            Value v = ((IndirectBranchTerminator) term).getTargetBlock();
            if (v != null) out.add(v);
        }
        return out;
    }

    /**
     * Removes points that fall inside a natural loop whose header still has
     * the site live-in. Freeing there would run the destructor on an object
     * that is used again on the next iteration.
     */
    private List<DestructionPoint> filterPointsInLoops(
            List<DestructionPoint> points,
            AllocationSite site,
            Map<BasicBlock, Set<BasicBlock>> loops,
            LivenessAnalyzer livenessAnalyzer) {
        if (points.isEmpty() || loops == null || loops.isEmpty()) return points;

        List<DestructionPoint> filtered = new ArrayList<>();
        for (DestructionPoint p : points) {
            boolean inLiveLoop = false;
            for (Map.Entry<BasicBlock, Set<BasicBlock>> e : loops.entrySet()) {
                if (e.getValue().contains(p.getBlock())
                    && livenessAnalyzer.getLiveIn(e.getKey()).contains(site)) {
                    inLiveLoop = true;
                    break;
                }
            }
            if (!inLiveLoop) filtered.add(p);
        }
        return filtered;
    }

    /**
     * Collapses candidate points: at most one per block, and no point whose
     * block is dominated by another candidate's block (the dominating block
     * is the earlier death site, so the dominated one is unreachable).
     */
    private List<DestructionPoint> dedupPoints(List<DestructionPoint> points,
                                               DominatorTree dom) {
        List<DestructionPoint> kept = new ArrayList<>();
        Set<BasicBlock> seen = new HashSet<>();
        for (DestructionPoint p : points) {
            if (!seen.add(p.getBlock())) continue;
            boolean dominated = false;
            for (DestructionPoint q : points) {
                if (q.getBlock() != p.getBlock()
                    && dom.dominates(q.getBlock(), p.getBlock())) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) kept.add(p);
        }
        return kept;
    }

    private Map<BasicBlock, Set<BasicBlock>> detectNaturalLoops(Function func,
                                                               DominatorTree domTree) {
        Map<BasicBlock, Set<BasicBlock>> loops = new HashMap<>();
        for (BasicBlock block : func.getBlocks()) {
            for (BasicBlock pred : block.getPredecessors()) {
                if (domTree.dominates(block, pred)) {
                    Set<BasicBlock> body = new HashSet<>();
                    findLoopBody(block, pred, body);
                    loops.put(block, body);
                }
            }
        }
        return loops;
    }

    private void findLoopBody(BasicBlock header, BasicBlock latch,
                              Set<BasicBlock> body) {
        Deque<BasicBlock> stack = new ArrayDeque<>();
        stack.push(latch);
        body.add(header);
        while (!stack.isEmpty()) {
            BasicBlock block = stack.pop();
            if (!body.add(block)) continue;
            for (BasicBlock pred : block.getPredecessors()) {
                if (!body.contains(pred)) stack.push(pred);
            }
        }
    }
}

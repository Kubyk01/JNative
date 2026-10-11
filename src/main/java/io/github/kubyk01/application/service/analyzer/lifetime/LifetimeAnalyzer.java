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

        // -------- 1. Collect sites (already parallel-friendly; keep as-is) -----
        AllocationSiteCollector collector =
            new AllocationSiteCollector(module, aliasResult);
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
                } else if (sr.point() != null) {
                    result.getDestructionPoints()
                          .computeIfAbsent(sr.site(), k -> new HashSet<>())
                          .add(sr.point());
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
            return new SiteResult(site, null, false);
        }

        Function func = module.getFunction(site.getMethodName());
        if (func == null || func.getEntryBlock() == null) {
            return new SiteResult(site, null, false);
        }

        DominatorTree domTree = domTrees.get(func);
        if (domTree == null) return new SiteResult(site, null, false);

        Map<BasicBlock, Set<BasicBlock>> loops = loopHeaders.get(func);

        Set<BasicBlock> deathBlocks = new HashSet<>();
        for (BasicBlock block : func.getBlocks()) {
            Set<AllocationSite> liveIn = livenessAnalyzer.getLiveIn(block);
            Set<AllocationSite> liveOut = livenessAnalyzer.getLiveOut(block);

            if (liveIn.contains(site) && !liveOut.contains(site)) {
                Terminator term = block.getTerminator();
                boolean isReturned = false;
                if (term instanceof ReturnTerminator) {
                    Value retVal = ((ReturnTerminator) term).getValue();
                    if (retVal != null
                        && aliasResult.getPointsTo(retVal).getSites().contains(site)) {
                        isReturned = true;
                    }
                }
                if (!isReturned) deathBlocks.add(block);
            }
        }

        Set<BasicBlock> filteredDeathBlocks = filterDeathBlocksInLoops(
            deathBlocks, site, loops, livenessAnalyzer);

        if (filteredDeathBlocks.isEmpty()) {
            List<BasicBlock> returnBlocks = new ArrayList<>();
            for (BasicBlock block : func.getBlocks()) {
                if (block.getTerminator() instanceof ReturnTerminator) {
                    returnBlocks.add(block);
                }
            }
            if (returnBlocks.isEmpty()) {
                return new SiteResult(site, null, false);
            }
            BasicBlock lca = domTree.getLCA(returnBlocks);
            if (lca == null) {
                return new SiteResult(site, null, true);
            }
            Value objRef = siteToValue.get(site);
            if (objRef == null) return new SiteResult(site, null, false);
            return new SiteResult(site,
                new DestructionPoint(lca, null, objRef), false);
        }

        BasicBlock lca = domTree.getLCA(new ArrayList<>(filteredDeathBlocks));
        if (lca == null) {
            return new SiteResult(site, null, true);
        }
        Value objRef = siteToValue.get(site);
        if (objRef == null) return new SiteResult(site, null, false);
        return new SiteResult(site,
            new DestructionPoint(lca, null, objRef), false);
    }

    private record SiteResult(AllocationSite site,
                              DestructionPoint point,
                              boolean unresolved) {}

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

    private Set<BasicBlock> filterDeathBlocksInLoops(
            Set<BasicBlock> deathBlocks,
            AllocationSite site,
            Map<BasicBlock, Set<BasicBlock>> loops,
            LivenessAnalyzer livenessAnalyzer) {
        Set<BasicBlock> result = new HashSet<>(deathBlocks);
        if (loops == null) return result;
        for (Map.Entry<BasicBlock, Set<BasicBlock>> entry : loops.entrySet()) {
            BasicBlock header = entry.getKey();
            Set<BasicBlock> body = entry.getValue();
            boolean liveOnEntry =
                livenessAnalyzer.getLiveIn(header).contains(site);
            if (liveOnEntry) {
                for (BasicBlock block : body) result.remove(block);
            }
        }
        return result;
    }
}

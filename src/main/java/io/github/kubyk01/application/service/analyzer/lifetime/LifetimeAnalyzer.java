package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.application.service.analyzer.ssa.DominatorTree;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.lifetime.strategies.LifetimeAnalysisStrategies;
import io.github.kubyk01.domain.ir.*;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.util.LlvmUtil;
import io.github.kubyk01.util.StrategyScanner;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
        System.out.println("Lifetime: " + allSites.size() + " allocation sites");

        // -------- 2. Cyclic reference detection --------
        CyclicReferenceDetector cyclicDetector =
                new CyclicReferenceDetector(aliasResult.getGraph(), allSites);
        Set<AllocationSite> cyclicSites = cyclicDetector.detect();
        result.getUnresolved().addAll(cyclicSites);
        System.out.println("Lifetime: " + cyclicSites.size() + " cyclic site(s)");

        // -------- 3. Liveness (now internally parallel) --------
        LivenessAnalyzer livenessAnalyzer =
                new LivenessAnalyzer(module, aliasResult, summaries);
        livenessAnalyzer.setScheduler(scheduler);
        livenessAnalyzer.computeLiveSets(allSites);

        // -------- 3b. Publish live-out per block (read-only views) --------
        Map<BasicBlock, Set<AllocationSite>> liveOutMap = livenessAnalyzer.getLiveOutMap();
        result.setLiveOut(liveOutMap != null ? liveOutMap : Map.of());

        // -------- 4. Dominator trees + natural loops, in parallel --------
        List<Function> live = new ArrayList<>();
        List<? extends Function> jnLoop1 = module.getFunctions();
        for (int jnIdx1 = 0; jnIdx1 < jnLoop1.size(); jnIdx1++) {
            Function f = jnLoop1.get(jnIdx1);
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
        System.out.println("Lifetime: dominator trees for " + live.size() + " functions");

        Set<AllocationSite> sites = new HashSet<>(allSites);
        sites.removeAll(cyclicSites);
        List<LifetimeAnalysisStrategies> strategies =
                StrategyScanner.scan(LifetimeAnalysisStrategies.class, LifetimeAnalysisStrategies::priority);
        System.out.println("Lifetime strategies: " + strategies.stream()
                .map(s -> s.getClass().getSimpleName()).toList());

        List<LifetimeAnalysisResult> parts = LlvmUtil.awaitFlux(Flux.fromIterable(strategies)
                .flatMapSequential(strategy -> Mono.fromCallable(() -> strategy.analyze(module, sites,
                                aliasResult, escapeResult, livenessAnalyzer, domTrees, loopHeaders, scheduler))
                        .subscribeOn(Schedulers.boundedElastic())));

        Set<AllocationSite> unresolvedCandidates = new HashSet<>();
        {
            for (int jnIdx2 = 0; jnIdx2 < parts.size(); jnIdx2++) { LifetimeAnalysisResult part = ((List<? extends LifetimeAnalysisResult>) parts).get(jnIdx2);
            unresolvedCandidates.addAll(part.getUnresolved());
            part.getDestructionPoints().forEach((site, points) -> {
                if (points.isEmpty()) return;
                result.getDestructionPoints().computeIfAbsent(site, k -> new HashSet<>()).addAll(points);
            });
            result.getShallowSites().addAll(part.getShallowSites());
            result.getFrameSites().addAll(part.getFrameSites());
        } }
        for (AllocationSite site : unresolvedCandidates) {
            if (!result.getDestructionPoints().containsKey(site)) result.getUnresolved().add(site);
        }
        System.out.println("Lifetime: done, " + result.getDestructionPoints().size()
                + " site(s) with destruction points (" + result.getShallowSites().size()
                + " by ownership transfer), " + result.getUnresolved().size() + " unresolved");

        return result;
    }

    private Map<BasicBlock, Set<BasicBlock>> detectNaturalLoops(Function func,
                                                                DominatorTree domTree) {
        Map<BasicBlock, Set<BasicBlock>> loops = new HashMap<>();
        for (int blockIdx1 = 0; blockIdx1 < func.getBlocks().size(); blockIdx1++) { BasicBlock block = func.getBlocks().get(blockIdx1);
            for (int predIdx2 = 0; predIdx2 < block.getPredecessors().size(); predIdx2++) { BasicBlock pred = block.getPredecessors().get(predIdx2);
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
            for (int predIdx3 = 0; predIdx3 < block.getPredecessors().size(); predIdx3++) { BasicBlock pred = block.getPredecessors().get(predIdx3);
                if (!body.contains(pred)) stack.push(pred);
            }
        }
    }
}
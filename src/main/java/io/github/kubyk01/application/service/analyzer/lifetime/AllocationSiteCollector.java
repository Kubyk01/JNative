package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.util.LlvmUtil;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.kubyk01.util.LlvmUtil.isAllocation;

/**
 * Collects every allocation site of the module.
 *
 * <p>The two-argument constructor is kept for backward compatibility (tests,
 * external callers) and uses {@link Schedulers#single()}. The three-argument
 * constructor takes the scheduler owned by
 * {@link io.github.kubyk01.application.service.Orchestrator} and parallelises
 * the scan over all functions. On a full JDK closure this is the only way to
 * keep the site collection from becoming a serial phase, because there are tens
 * of thousands of functions.</p>
 */
public class AllocationSiteCollector {

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final Scheduler scheduler;

    public AllocationSiteCollector(Module module, AliasAnalysisResult aliasResult) {
        this(module, aliasResult, Schedulers.single());
    }

    public AllocationSiteCollector(Module module,
                                   AliasAnalysisResult aliasResult,
                                   Scheduler scheduler) {
        this.module = module;
        this.aliasResult = aliasResult;
        this.scheduler = (scheduler != null) ? scheduler : Schedulers.single();
    }

    public List<AllocationSite> collect() {
        List<Function> live = new ArrayList<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() != null) live.add(func);
        }
        if (live.isEmpty()) return List.of();

        Set<AllocationSite> sites = ConcurrentHashMap.newKeySet();

        LlvmUtil.awaitMono(
            Flux.fromIterable(live)
                .parallel()
                .runOn(scheduler)
                .doOnNext(func -> {
                    for (BasicBlock block : func.getBlocks()) {
                        for (Instruction inst : block.getInstructions()) {
                            if (!isAllocation(inst.getOpcode())) continue;
                            if (inst.getResult() == null) continue;
                            sites.addAll(
                                aliasResult.getPointsTo(inst.getResult())
                                           .getSites());
                        }
                    }
                })
                .sequential()
                .then()
        );

        return new ArrayList<>(sites);
    }
}

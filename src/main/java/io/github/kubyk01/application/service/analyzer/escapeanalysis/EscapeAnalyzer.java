package io.github.kubyk01.application.service.analyzer.escapeanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.analyzer.escapeanalysis.strategies.EscapeAnalysisStrategies;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.util.LlvmUtil;
import io.github.kubyk01.util.StrategyScanner;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

@Slf4j
public class EscapeAnalyzer {

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final DependencyResolver resolver;
    private final Scheduler scheduler;

    public EscapeAnalyzer(Module module,
                          AliasAnalysisResult aliasResult,
                          DependencyResolver resolver) {
        this(module, aliasResult, resolver, Schedulers.single());
    }

    public EscapeAnalyzer(Module module,
                          AliasAnalysisResult aliasResult,
                          DependencyResolver resolver,
                          Scheduler scheduler) {
        this.module = module;
        this.aliasResult = aliasResult;
        this.resolver = resolver;
        this.scheduler = (scheduler != null) ? scheduler : Schedulers.single();
    }

    public EscapeAnalysisResult analyze() {
        Map<String, EscapeSummary> summaries =
            new EscapeSummaryBuilder(module, aliasResult, resolver, scheduler).build();
        List<EscapeAnalysisStrategies> strategies =
            StrategyScanner.scan(EscapeAnalysisStrategies.class, EscapeAnalysisStrategies::priority);
        System.out.println("Escape strategies: " + strategies.stream()
            .map(s -> s.getClass().getSimpleName()).toList());

        return LlvmUtil.awaitMono(Flux.fromIterable(strategies)
            .flatMapSequential(strategy -> Mono.fromCallable(() -> strategy.analyze(module, aliasResult, summaries, resolver, scheduler))
                .subscribeOn(Schedulers.boundedElastic()))
            .reduce(new EscapeAnalysisResult(), EscapeAnalysisResult::merge));
    }
}

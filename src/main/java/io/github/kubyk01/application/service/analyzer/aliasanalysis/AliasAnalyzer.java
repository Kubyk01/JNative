package io.github.kubyk01.application.service.analyzer.aliasanalysis;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToGraph;
import io.github.kubyk01.domain.ir.Module;
import reactor.core.scheduler.Scheduler;

import java.util.Map;

public class AliasAnalyzer {

    private final Module module;
    private final Scheduler scheduler;
    private final int parallelism;

    public AliasAnalyzer(Module module, Scheduler scheduler) {
        this(module, scheduler, Runtime.getRuntime().availableProcessors());
    }

    public AliasAnalyzer(Module module, Scheduler scheduler, int parallelism) {
        this.module = module;
        this.scheduler = scheduler;
        this.parallelism = parallelism;
    }

    public AliasAnalysisResult analyze() {
        SummaryBuilder summaryBuilder = new SummaryBuilder(module, scheduler);
        Map<String, FunctionSummary> summaries = summaryBuilder.build();

        InterproceduralPointsTo interproc =
                new InterproceduralPointsTo(module, summaries, scheduler, parallelism);
        PointsToGraph graph = interproc.analyze();

        return new AliasAnalysisResult(graph, summaries);
    }
}
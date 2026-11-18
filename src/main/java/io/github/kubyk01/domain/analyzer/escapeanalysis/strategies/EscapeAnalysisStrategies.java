package io.github.kubyk01.domain.analyzer.escapeanalysis.strategies;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.Discoverable;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.ir.Module;
import reactor.core.scheduler.Scheduler;

import java.util.Map;

@Discoverable
public interface EscapeAnalysisStrategies {

    int priority();

    EscapeAnalysisResult analyze(Module module,
                                 AliasAnalysisResult aliasResult,
                                 Map<String, EscapeSummary> summaries,
                                 DependencyResolver resolver,
                                 Scheduler scheduler);
}

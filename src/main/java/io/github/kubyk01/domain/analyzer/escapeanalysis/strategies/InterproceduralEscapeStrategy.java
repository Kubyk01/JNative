package io.github.kubyk01.domain.analyzer.escapeanalysis.strategies;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.escapeanalysis.InterproceduralEscape;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.ir.Module;
import reactor.core.scheduler.Scheduler;

import java.util.Map;

public final class InterproceduralEscapeStrategy implements EscapeAnalysisStrategies {

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public EscapeAnalysisResult analyze(Module module, AliasAnalysisResult aliasResult,
                                        Map<String, EscapeSummary> summaries,
                                        DependencyResolver resolver, Scheduler scheduler) {
        EscapeAnalysisResult result = new EscapeAnalysisResult();
        new InterproceduralEscape(module, aliasResult, summaries, resolver, scheduler)
                .analyze()
                .forEach(result::setSiteStatus);
        return result;
    }
}

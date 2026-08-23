package io.github.kubyk01.application.service.analyzer.escapeanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.analyzer.ir.Module;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class EscapeAnalyzer {

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final DependencyResolver resolver;

    public EscapeAnalyzer(Module module, AliasAnalysisResult aliasResult, DependencyResolver resolver) {
        this.module = module;
        this.aliasResult = aliasResult;
        this.resolver = resolver;
    }

    public EscapeAnalysisResult analyze() {
        EscapeSummaryBuilder summaryBuilder = new EscapeSummaryBuilder(module, aliasResult, resolver);
        Map<String, EscapeSummary> summaries = summaryBuilder.build();

        InterproceduralEscape interproc = new InterproceduralEscape(module, aliasResult, summaries, resolver);
        Map<AllocationSite, EscapeStatus> siteStatus = interproc.analyze();

        EscapeAnalysisResult result = new EscapeAnalysisResult();
        for (Map.Entry<AllocationSite, EscapeStatus> entry : siteStatus.entrySet()) {
            result.setSiteStatus(entry.getKey(), entry.getValue());
        }
        return result;
    }
}

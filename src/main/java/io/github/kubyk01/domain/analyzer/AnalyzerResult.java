package io.github.kubyk01.domain.analyzer;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record AnalyzerResult(
    List<ClinitScheduleEntry> clinitSchedule,
    Map<String, String> clinitWrappers,
    Set<String> deferredClinits,
    AliasAnalysisResult aliasResult,
    EscapeAnalysisResult escapeResult,
    LifetimeAnalysisResult lifetimeResult
) {}
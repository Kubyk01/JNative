package io.github.kubyk01.domain.analyzer;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.ir.Function;

import java.util.List;
import java.util.Map;

public record AnalyzerResult(
    List<Function> clinitFunctions,
    Map<String, String> clinitWrappers,
    AliasAnalysisResult aliasResult,
    EscapeAnalysisResult escapeResult,
    LifetimeAnalysisResult lifetimeResult
) {}

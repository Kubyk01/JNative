package io.github.kubyk01.domain.analyzer.aliasanalysis;

import io.github.kubyk01.domain.ir.Value;
import lombok.Getter;

import java.util.Map;

@Getter
public class AliasAnalysisResult {
    private final PointsToGraph graph;
    private final Map<String, FunctionSummary> functionSummaries;

    public AliasAnalysisResult(PointsToGraph graph, Map<String, FunctionSummary> functionSummaries) {
        this.graph = graph;
        this.functionSummaries = functionSummaries;
    }

    public PointsToSet getPointsTo(Value v) {
        return graph.get(v);
    }

    public boolean mayAlias(Value a, Value b) {
        return graph.mayAlias(a, b);
    }

    // --- new methods for fields ---

    public PointsToSet getFieldPointsToForSites(PointsToSet sites, String field) {
        return graph.getFieldPointsToForSites(sites, field);
    }

    public PointsToSet getStaticFieldPointsTo(String field) {
        return graph.getStaticFieldPointsTo(field);
    }

    // --- mapping allocation site -> IR value ---
    public Map<AllocationSite, Value> getAllocationSiteToValue() {
        return graph.getAllocationSiteToValue();
    }
}

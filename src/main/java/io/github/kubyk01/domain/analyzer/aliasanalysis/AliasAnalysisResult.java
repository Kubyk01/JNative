package io.github.kubyk01.domain.analyzer.aliasanalysis;

import io.github.kubyk01.domain.analyzer.ir.Value;
import lombok.Getter;

import java.util.Map;

@Getter
public class AliasAnalysisResult {
    private final PointsToGraph graph;

    public AliasAnalysisResult(PointsToGraph graph) {
        this.graph = graph;
    }

    public PointsToSet getPointsTo(Value v) {
        return graph.get(v);
    }

    public boolean mayAlias(Value a, Value b) {
        return graph.mayAlias(a, b);
    }

    public boolean isUnique(Value v) {
        return graph.isUnique(v);
    }

    public AllocationSite getUniqueSite(Value v) {
        return graph.getUniqueSite(v);
    }

    // --- new methods for fields ---
    public PointsToSet getFieldPointsTo(AllocationSite site, String field) {
        return graph.getFieldPointsTo(site, field);
    }

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

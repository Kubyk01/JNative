package io.github.kubyk01.domain.analyzer.aliasanalysis;

import io.github.kubyk01.domain.analyzer.ir.Value;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

public class PointsToGraph {
    private final Map<Value, PointsToSet> nodeToPointsTo = new HashMap<>();
    // Field points-to: for each allocation site and field name
    private final Map<AllocationSite, Map<String, PointsToSet>> fieldPointsTo = new HashMap<>();
    // Static fields
    private final Map<String, PointsToSet> staticFieldPointsTo = new HashMap<>();
    // Mapping allocation site -> IR value (NEW result)
    @Getter
    private final Map<AllocationSite, Value> allocationSiteToValue = new HashMap<>();

    public void putAllocationSite(AllocationSite site, Value value) {
        allocationSiteToValue.put(site, value);
    }

    // ---- regular points-to (for values) ----
    public PointsToSet get(Value v) {
        return nodeToPointsTo.computeIfAbsent(v, k -> new PointsToSet());
    }

    public void set(Value v, PointsToSet pts) {
        nodeToPointsTo.put(v, pts);
    }

    public void addAll(Value v, PointsToSet pts) {
        get(v).addAll(pts);
    }

    public void add(Value v, AllocationSite site) {
        get(v).add(site);
    }

    public boolean merge(Value v, PointsToSet pts) {
        PointsToSet existing = get(v);
        int oldSize = existing.getSites().size();
        existing.addAll(pts);
        return existing.getSites().size() > oldSize;
    }

    public Map<Value, PointsToSet> getAll() {
        return nodeToPointsTo;
    }

    // ---- field points-to (instance fields) ----
    public PointsToSet getFieldPointsTo(AllocationSite site, String field) {
        return fieldPointsTo
                .computeIfAbsent(site, k -> new HashMap<>())
                .computeIfAbsent(field, k -> new PointsToSet());
    }

    public void mergeFieldPointsTo(AllocationSite site, String field, PointsToSet pts) {
        getFieldPointsTo(site, field).addAll(pts);
    }

    /**
     * Returns a map field -> points-to for the given allocation site,
     * or null if nothing was ever stored into this object.
     */
    public Map<String, PointsToSet> getFieldPointsToMap(AllocationSite site) {
        return fieldPointsTo.get(site);
    }

    /**
     * Returns the union of the field's points-to for all allocation sites from the given set.
     */
    public PointsToSet getFieldPointsToForSites(PointsToSet sites, String field) {
        PointsToSet result = new PointsToSet();
        for (AllocationSite site : sites.getSites()) {
            result.addAll(getFieldPointsTo(site, field));
        }
        return result;
    }

    // ---- static fields ----
    public PointsToSet getStaticFieldPointsTo(String field) {
        return staticFieldPointsTo.computeIfAbsent(field, k -> new PointsToSet());
    }

    public void mergeStaticFieldPointsTo(String field, PointsToSet pts) {
        getStaticFieldPointsTo(field).addAll(pts);
    }

    public Map<String, PointsToSet> getStaticFieldPointsToMap() {
        return staticFieldPointsTo;
    }

    // ---- helper methods ----
    public boolean mayAlias(Value a, Value b) {
        return get(a).intersects(get(b));
    }

    public boolean isUnique(Value v) {
        return get(v).getSites().size() == 1;
    }

    public AllocationSite getUniqueSite(Value v) {
        PointsToSet pts = get(v);
        if (pts.getSites().size() == 1) {
            return pts.getSites().iterator().next();
        }
        return null;
    }

    // ---- graph merging (for interprocedural analysis) ----
    public void mergeGraph(PointsToGraph other) {
        for (Map.Entry<Value, PointsToSet> e : other.nodeToPointsTo.entrySet()) {
            this.merge(e.getKey(), e.getValue());
        }
        for (Map.Entry<AllocationSite, Map<String, PointsToSet>> e : other.fieldPointsTo.entrySet()) {
            for (Map.Entry<String, PointsToSet> fe : e.getValue().entrySet()) {
                this.mergeFieldPointsTo(e.getKey(), fe.getKey(), fe.getValue());
            }
        }
        for (Map.Entry<String, PointsToSet> e : other.staticFieldPointsTo.entrySet()) {
            this.mergeStaticFieldPointsTo(e.getKey(), e.getValue());
        }
        for (Map.Entry<AllocationSite, Value> e : other.allocationSiteToValue.entrySet()) {
            this.allocationSiteToValue.putIfAbsent(e.getKey(), e.getValue());
        }
    }
}

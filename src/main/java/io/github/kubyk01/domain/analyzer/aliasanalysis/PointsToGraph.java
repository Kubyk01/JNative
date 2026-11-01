package io.github.kubyk01.domain.analyzer.aliasanalysis;

import io.github.kubyk01.domain.ir.Value;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PointsToGraph {

    private final Map<Value, PointsToSet> nodeToPointsTo = new ConcurrentHashMap<>();
    private final Map<AllocationSite, Map<String, PointsToSet>> fieldPointsTo = new ConcurrentHashMap<>();
    private final Map<String, PointsToSet> staticFieldPointsTo = new ConcurrentHashMap<>();

    @Getter
    private final Map<AllocationSite, Value> allocationSiteToValue = new HashMap<>();

    public void putAllocationSite(AllocationSite site, Value value) {
        allocationSiteToValue.put(site, value);
    }

    public PointsToSet get(Value v) {
        PointsToSet s = nodeToPointsTo.get(v);
        return s != null ? s : PointsToSet.EMPTY;
    }

    public PointsToSet getIfPresent(Value v) { return nodeToPointsTo.get(v); }

    public void set(Value v, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) { nodeToPointsTo.remove(v); return; }
        nodeToPointsTo.put(v, pts);
    }

    public void addAll(Value v, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) return;
        nodeToPointsTo.computeIfAbsent(v, k -> PointsToSet.shared()).addAll(pts);
    }

    public void add(Value v, AllocationSite site) { addSite(v, site); }

    public boolean addSite(Value v, AllocationSite site) {
        return nodeToPointsTo.computeIfAbsent(v, k -> PointsToSet.shared()).add(site);
    }

    public boolean merge(Value v, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) return false;
        return nodeToPointsTo.computeIfAbsent(v, k -> PointsToSet.shared()).addAll(pts);
    }

    public Map<Value, PointsToSet> getAll() { return nodeToPointsTo; }

    public PointsToSet getFieldPointsTo(AllocationSite site, String field) {
        Map<String, PointsToSet> m = fieldPointsTo.get(site);
        if (m == null) return PointsToSet.EMPTY;
        PointsToSet s = m.get(field);
        return s != null ? s : PointsToSet.EMPTY;
    }

    public boolean mergeFieldPointsTo(AllocationSite site, String field, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) return false;
        Map<String, PointsToSet> perSite = fieldPointsTo.computeIfAbsent(
            site, k -> new ConcurrentHashMap<>(4));
        PointsToSet existing = perSite.computeIfAbsent(field, k -> PointsToSet.shared());
        return existing.addAll(pts);
    }

    public boolean fieldContainsUnknown(AllocationSite site, String field) {
        Map<String, PointsToSet> m = fieldPointsTo.get(site);
        if (m == null) return false;
        PointsToSet pts = m.get(field);
        return pts != null && pts.containsUnknown();
    }

    public Map<String, PointsToSet> getFieldPointsToMap(AllocationSite site) {
        return fieldPointsTo.get(site);
    }

    public PointsToSet getFieldPointsToForSites(PointsToSet sites, String field) {
        if (sites == null || sites.isEmpty()) return PointsToSet.EMPTY;
        PointsToSet result = null;
        for (AllocationSite site : sites) {
            Map<String, PointsToSet> m = fieldPointsTo.get(site);
            if (m == null) continue;
            PointsToSet ftp = m.get(field);
            if (ftp == null || ftp.isEmpty()) continue;
            if (result == null) result = PointsToSet.local();
            result.addAll(ftp);
        }
        return result != null ? result : PointsToSet.EMPTY;
    }

    public PointsToSet getArrayElementPointsTo(AllocationSite site) {
        return getFieldPointsTo(site, "[]");
    }

    public boolean mergeArrayElementPointsTo(AllocationSite site, PointsToSet pts) {
        return mergeFieldPointsTo(site, "[]", pts);
    }

    public PointsToSet getStaticFieldPointsTo(String field) {
        PointsToSet s = staticFieldPointsTo.get(field);
        return s != null ? s : PointsToSet.EMPTY;
    }

    public boolean mergeStaticFieldPointsTo(String field, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) return false;
        return staticFieldPointsTo.computeIfAbsent(field, k -> PointsToSet.shared()).addAll(pts);
    }

    public boolean staticFieldContainsUnknown(String field) {
        PointsToSet pts = staticFieldPointsTo.get(field);
        return pts != null && pts.containsUnknown();
    }

    public Map<String, PointsToSet> getStaticFieldPointsToMap() { return staticFieldPointsTo; }

    public boolean mayAlias(Value a, Value b) {
        return get(a).intersects(get(b));
    }
}
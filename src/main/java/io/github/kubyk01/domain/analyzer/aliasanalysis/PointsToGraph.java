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
        return nodeToPointsTo.computeIfAbsent(v, k -> new PointsToSet());
    }

    /**
     * Non-creating counterpart of {@link #get(Value)}: returns {@code null}
     * when the value has no points-to information yet, instead of installing
     * an empty {@code PointsToSet} for it.
     *
     * <p>Callers that only read (for example the return-value handling and the
     * parameter-write widening loop in
     * {@code InterproceduralPointsTo.processCall}) must use this overload: an
     * empty entry created by {@link #get(Value)} is not equivalent to no
     * entry, because other consumers treat the mere presence of the key as
     * "this value has points-to information".</p>
     */
    public PointsToSet getIfPresent(Value v) {
        return nodeToPointsTo.get(v);
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

    /**
     * Adds a single allocation site to a value's points-to set and
     * returns {@code true} iff the set actually changed.
     *
     * <p>The change flag comes from the underlying
     * {@link java.util.concurrent.ConcurrentHashMap.KeySetView#add(Object)},
     * which is itself atomic — two concurrent calls with the same
     * argument cannot both observe a change. This is what lets the
     * return-value handling in
     * {@code InterproceduralPointsTo.processCall} skip the temporary
     * {@code PointsToSet} it used to build.</p>
     */
    public boolean addSite(Value v, AllocationSite site) {
        PointsToSet existing = nodeToPointsTo.get(v);
        if (existing == null) {
            existing = nodeToPointsTo.computeIfAbsent(v, k -> new PointsToSet());
        }
        return existing.addSite(site);
    }

    /**
     * Same change-flag contract as {@link #mergeFieldPointsTo}.
     *
     * <p>The size comparison is exact: the destination set is this
     * value's own concurrent key set, and {@code addAll} on it either
     * leaves the size unchanged (all elements already present) or
     * strictly increases it. Under concurrent evaluation of different
     * functions, a value's set is only ever written by the thread that
     * is evaluating the function that contains the value — see the
     * "evaluation owner" invariant in
     * {@code InterproceduralPointsTo.analyze} — so the comparison is
     * exact for the caller's perspective.</p>
     */
    public boolean merge(Value v, PointsToSet pts) {
        PointsToSet existing = nodeToPointsTo.get(v);
        if (existing == null) {
            existing = nodeToPointsTo.computeIfAbsent(v, k -> new PointsToSet());
        }
        int oldSize = existing.getSites().size();
        existing.addAll(pts);
        return existing.getSites().size() > oldSize;
    }

    public Map<Value, PointsToSet> getAll() {
        return nodeToPointsTo;
    }

    public PointsToSet getFieldPointsTo(AllocationSite site, String field) {
        return fieldPointsTo
                .computeIfAbsent(site, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(field, k -> new PointsToSet());
    }

    /**
     * Returns {@code true} iff the field's points-to set grew as a
     * result of the merge, {@code false} otherwise.
     *
     * <p>Returning the change flag is load-bearing for the caller: the
     * interprocedural worklist uses this to decide whether the enclosing
     * function actually produced new information and must therefore be
     * propagated to its callers. Returning {@code void} — as earlier
     * revisions did — forced every caller to assume a change had
     * occurred, which either silently dropped the re-enqueue (the
     * pre-fix behaviour) or over-enqueued. Neither is acceptable now that
     * the widening loops run hundreds of thousands of times on a full
     * JDK image.</p>
     *
     * <p>Under parallel evaluation, the destination set may be modified
     * concurrently by another worker merging into the same (site, field)
     * pair. Because writes are monotone — we only ever add, never remove
     * — the size comparison cannot produce a false negative. It can
     * produce a false positive (return true when the only growth came
     * from the other worker's insert), which is harmless: the caller
     * re-enqueues a caller that might not have needed it, and the
     * fast-path skip in {@code processCall} makes that re-enqueue
     * essentially free.</p>
     */
    public boolean mergeFieldPointsTo(AllocationSite site, String field, PointsToSet pts) {
        Map<String, PointsToSet> perSite = fieldPointsTo.get(site);
        PointsToSet existing = perSite == null ? null : perSite.get(field);
        if (existing == null) {
            existing = getFieldPointsTo(site, field);
        }
        int oldSize = existing.getSites().size();
        existing.addAll(pts);
        return existing.getSites().size() > oldSize;
    }

    /**
     * Fast-path predicate used by the coarsened widening loops in
     * {@code InterproceduralPointsTo.processCall}.
     *
     * <p>Both levels of the lookup use the plain, non-creating
     * {@link Map#get(Object)} path. That is deliberate: the predicate
     * must not have the side effect of allocating an empty
     * {@code PointsToSet} for a field that has never been written.
     * Creating such an entry would make every later read of
     * {@link #getFieldPointsToMap(AllocationSite)} observe a non-null
     * map, which the cyclic-reference detector and the escape analysis
     * both interpret as "this site has fields with points-to
     * information". An empty entry is not equivalent to no entry for
     * either of those consumers.</p>
     */
    public boolean fieldContainsUnknown(AllocationSite site, String field) {
        Map<String, PointsToSet> perSite = fieldPointsTo.get(site);
        if (perSite == null) return false;
        PointsToSet pts = perSite.get(field);
        return pts != null && pts.containsUnknown();
    }

    public Map<String, PointsToSet> getFieldPointsToMap(AllocationSite site) {
        return fieldPointsTo.get(site);
    }

    public PointsToSet getFieldPointsToForSites(PointsToSet sites, String field) {
        PointsToSet result = new PointsToSet();
        for (AllocationSite site : sites.getSites()) {
            result.addAll(getFieldPointsTo(site, field));
        }
        return result;
    }

    public PointsToSet getArrayElementPointsTo(AllocationSite site) {
        return getFieldPointsTo(site, "[]");
    }

    public boolean mergeArrayElementPointsTo(AllocationSite site, PointsToSet pts) {
        return mergeFieldPointsTo(site, "[]", pts);
    }

    public PointsToSet getStaticFieldPointsTo(String field) {
        return staticFieldPointsTo.computeIfAbsent(field, k -> new PointsToSet());
    }

    public boolean mergeStaticFieldPointsTo(String field, PointsToSet pts) {
        PointsToSet existing = staticFieldPointsTo.get(field);
        if (existing == null) {
            existing = staticFieldPointsTo.computeIfAbsent(field, k -> new PointsToSet());
        }
        int oldSize = existing.getSites().size();
        existing.addAll(pts);
        return existing.getSites().size() > oldSize;
    }

    /**
     * Static-field counterpart of {@link #fieldContainsUnknown}. Uses
     * the non-creating {@code get} path for the same reason.
     */
    public boolean staticFieldContainsUnknown(String field) {
        PointsToSet pts = staticFieldPointsTo.get(field);
        return pts != null && pts.containsUnknown();
    }

    public Map<String, PointsToSet> getStaticFieldPointsToMap() {
        return staticFieldPointsTo;
    }

    public boolean mayAlias(Value a, Value b) {
        return get(a).intersects(get(b));
    }

}
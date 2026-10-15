package io.github.kubyk01.domain.analyzer.aliasanalysis;

import io.github.kubyk01.domain.ir.Value;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PointsToGraph {

    /**
     * Value-level points-to map.
     *
     * <p>Every entry corresponds to a value that actually acquired at
     * least one allocation site. Values that were queried but never
     * received a site are <b>not</b> represented; reads of such values
     * return {@link PointsToSet#EMPTY} directly, without allocating or
     * inserting anything.</p>
     *
     * <p>Backing store: {@link ConcurrentHashMap}, because different
     * workers can insert entries for different values in parallel. The
     * values are partitioned by the {@code inWorklist} invariant — one
     * evaluating thread per function — so a value's entry, once
     * created, is only ever written by that value's owner thread
     * during the analysis; concurrent insertion of new keys is still
     * possible from different threads, which is exactly what CHM is
     * designed for.</p>
     */
    private final Map<Value, PointsToSet> nodeToPointsTo = new ConcurrentHashMap<>();

    private final Map<AllocationSite, Map<String, PointsToSet>> fieldPointsTo
            = new ConcurrentHashMap<>();
    private final Map<String, PointsToSet> staticFieldPointsTo
            = new ConcurrentHashMap<>();

    @Getter
    private final Map<AllocationSite, Value> allocationSiteToValue = new HashMap<>();

    public void putAllocationSite(AllocationSite site, Value value) {
        allocationSiteToValue.put(site, value);
    }

    /**
     * Read-only accessor. Returns {@link PointsToSet#EMPTY} for a value
     * that has no points-to information yet, without inserting anything
     * into the graph and without allocating a {@code PointsToSet}.
     *
     * <p>This is the single most important hot path in the whole
     * analysis phase. On the JDK image it is invoked tens of millions
     * of times per points-to fixed-point iteration, once for every
     * operand of every instruction that the walk inspects. The previous
     * implementation allocated a fresh {@code PointsToSet} on every
     * miss, which under C2's escape analysis manifested as the
     * "failed reallocation of scalar replaced objects" abort and, when
     * it did not abort, as the dominant wall-clock cost of the
     * iteration.</p>
     */
    public PointsToSet get(Value v) {
        PointsToSet s = nodeToPointsTo.get(v);
        return s != null ? s : PointsToSet.EMPTY;
    }

    /**
     * Non-creating lookup for callers that need to distinguish
     * "present-but-empty" (which never happens now) from "not present".
     * Kept for compatibility with the alias analysis's early revision;
     * returns {@code null} only when no entry exists.
     */
    public PointsToSet getIfPresent(Value v) {
        return nodeToPointsTo.get(v);
    }

    public void set(Value v, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) {
            nodeToPointsTo.remove(v);
            return;
        }
        nodeToPointsTo.put(v, pts);
    }

    /**
     * Unconditional add-all. Creates the entry if it does not exist and
     * {@code pts} is non-empty; is a no-op otherwise.
     */
    public void addAll(Value v, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) return;
        PointsToSet existing = nodeToPointsTo.get(v);
        if (existing == null) {
            existing = PointsToSet.local();
            nodeToPointsTo.put(v, existing);
        }
        existing.addAll(pts);
    }

    /**
     * Adds a single allocation site to the value's points-to set.
     * Allocates the entry only on the first successful add.
     */
    public void add(Value v, AllocationSite site) {
        addSite(v, site);
    }

    /**
     * Adds a single allocation site to the value's points-to set and
     * returns {@code true} iff the set actually changed.
     *
     * <p>Change is detected through the underlying set's own
     * {@link java.util.Set#add(Object)}: a HashSet-backed local set
     * returns {@code true} exactly once per site, and the caller
     * relies on that to drive the interprocedural worklist. The
     * previous revision used a size comparison against a
     * {@code ConcurrentHashMap.KeySetView}, which forced an extra
     * hash lookup per call; the set's own return value is both cheaper
     * and exact.</p>
     */
    public boolean addSite(Value v, AllocationSite site) {
        PointsToSet existing = nodeToPointsTo.get(v);
        if (existing == null) {
            existing = PointsToSet.local();
            nodeToPointsTo.put(v, existing);
            existing.add(site);
            return true;
        }
        return existing.addSite(site);
    }

    /**
     * Same change-flag contract as {@link #addSite(Value, AllocationSite)}.
     * The incoming set is only iterated when it is non-empty, so the
     * vast majority of calls — which pass the shared {@code EMPTY}
     * singleton — cost a single {@code isEmpty()} check and return
     * without touching the CHM.
     */
    public boolean merge(Value v, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) return false;

        PointsToSet existing = nodeToPointsTo.get(v);
        if (existing == null) {
            existing = PointsToSet.local();
            existing.addAll(pts);
            nodeToPointsTo.put(v, existing);
            return true;
        }
        int before = existing.getSites().size();
        existing.addAll(pts);
        return existing.getSites().size() > before;
    }

    public Map<Value, PointsToSet> getAll() {
        return nodeToPointsTo;
    }

    /**
     * Read-only lookup of a field's points-to set. Returns
     * {@link PointsToSet#EMPTY} when the (site, field) pair has no
     * entry; does not allocate.
     */
    public PointsToSet getFieldPointsTo(AllocationSite site, String field) {
        Map<String, PointsToSet> perSite = fieldPointsTo.get(site);
        if (perSite == null) return PointsToSet.EMPTY;
        PointsToSet s = perSite.get(field);
        return s != null ? s : PointsToSet.EMPTY;
    }

    /**
     * Merges {@code pts} into the field's points-to set for the given
     * base site. The entry is created lazily: a call whose {@code pts}
     * is empty is a no-op.
     *
     * <p>Returns {@code true} iff the destination set grew. Under
     * parallel evaluation the destination set can be written
     * concurrently by another worker; because writes are monotone, the
     * size comparison cannot produce a false negative. A false
     * positive (returning {@code true} when the growth actually came
     * from the other worker's insert) is harmless — the caller
     * re-enqueues a caller that might not have needed it, and the
     * fast-path skip in {@code InterproceduralPointsTo.processCall}
     * makes that re-enqueue essentially free.</p>
     */
    public boolean mergeFieldPointsTo(AllocationSite site, String field, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) return false;

        Map<String, PointsToSet> perSite = fieldPointsTo.get(site);
        if (perSite == null) {
            Map<String, PointsToSet> fresh = new ConcurrentHashMap<>();
            Map<String, PointsToSet> prev = fieldPointsTo.putIfAbsent(site, fresh);
            perSite = (prev != null) ? prev : fresh;
        }
        PointsToSet existing = perSite.get(field);
        if (existing == null) {
            existing = PointsToSet.shared();
            PointsToSet prev = perSite.putIfAbsent(field, existing);
            if (prev != null) existing = prev;
        }
        int before = existing.getSites().size();
        existing.addAll(pts);
        return existing.getSites().size() > before;
    }

    /**
     * Fast-path predicate used by the coarsened widening loops in
     * {@code InterproceduralPointsTo.processCall}. Uses non-creating
     * lookups at both levels of the map, so a field that has never
     * been written is never materialised by this probe.
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

    /**
     * Union of the field's points-to set over every base site in
     * {@code sites}. The result is computed incrementally: only sites
     * whose field-map actually contributes are visited, and the
     * returned set is the shared {@link PointsToSet#EMPTY} singleton
     * when the union turns out empty. That keeps the common case — a
     * field read on a base whose points-to set contains no site that
     * has ever written that field — from allocating anything at
     * all.</p>
     */
    public PointsToSet getFieldPointsToForSites(PointsToSet sites, String field) {
        if (sites == null || sites.isEmpty()) return PointsToSet.EMPTY;

        PointsToSet result = null;
        for (AllocationSite site : sites.getSites()) {
            Map<String, PointsToSet> perSite = fieldPointsTo.get(site);
            if (perSite == null) continue;
            PointsToSet ftp = perSite.get(field);
            if (ftp == null || ftp.isEmpty()) continue;
            if (result == null) result = PointsToSet.local();
            result.addAll(ftp);
        }
        return (result != null) ? result : PointsToSet.EMPTY;
    }

    public PointsToSet getArrayElementPointsTo(AllocationSite site) {
        return getFieldPointsTo(site, "[]");
    }

    public boolean mergeArrayElementPointsTo(AllocationSite site, PointsToSet pts) {
        return mergeFieldPointsTo(site, "[]", pts);
    }

    /**
     * Read-only accessor for a static field's points-to set. Returns
     * {@link PointsToSet#EMPTY} for a static field that has never been
     * written; does not allocate.
     */
    public PointsToSet getStaticFieldPointsTo(String field) {
        PointsToSet s = staticFieldPointsTo.get(field);
        return s != null ? s : PointsToSet.EMPTY;
    }

    public boolean mergeStaticFieldPointsTo(String field, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) return false;

        PointsToSet existing = staticFieldPointsTo.get(field);
        if (existing == null) {
            existing = PointsToSet.shared();
            PointsToSet prev = staticFieldPointsTo.putIfAbsent(field, existing);
            if (prev != null) existing = prev;
        }
        int before = existing.getSites().size();
        existing.addAll(pts);
        return existing.getSites().size() > before;
    }

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

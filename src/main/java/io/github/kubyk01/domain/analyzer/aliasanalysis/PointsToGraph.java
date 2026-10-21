package io.github.kubyk01.domain.analyzer.aliasanalysis;

import io.github.kubyk01.domain.ir.Value;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interprocedural, field-sensitive points-to graph.
 *
 * <h2>Thread-safety</h2>
 *
 * <p>The graph is written by every thread that evaluates a function in
 * the parallel points-to worklist. Since the class now propagates
 * caller points-to into callee {@code Parameter}-s (precise
 * interprocedural field sensitivity), a value's {@code PointsToSet}
 * may be written by <em>several</em> threads concurrently:</p>
 *
 * <ul>
 *   <li>a {@code Parameter}'s set is written by its own function's
 *       seeding pass and by every caller's argument propagation;</li>
 *   <li>a {@code Temporary}'s set is written only by its owning
 *       function, but may be <em>iterated</em> by that same thread
 *       while another thread is writing into a {@code Parameter}
 *       that the temporary aliases;</li>
 *   <li>field and static-field sets are written by every function
 *       that stores into the corresponding field.</li>
 * </ul>
 *
 * <p>Every {@code PointsToSet} stored in this graph therefore uses
 * {@link PointsToSet#shared()} — a {@code ConcurrentHashMap} key set —
 * rather than {@link PointsToSet#local()}. Iteration over such a set
 * is weakly consistent and never throws {@code ConcurrentModificationException};
 * {@code add}, {@code contains}, {@code size} are all atomic.</p>
 *
 * <p>The only sets that remain {@code local()} are thread-local
 * temporaries (the result of {@link #getFieldPointsToForSites} and of
 * {@link #getFieldPointsToMap}), which are produced and consumed by a
 * single thread and never reach any caller from another thread.</p>
 */
public class PointsToGraph {

    /**
     * Value-level points-to map.
     *
     * <p>Backed by {@link ConcurrentHashMap}. Values are partitioned
     * by owner function — one thread per function during evaluation —
     * but cross-function propagation now writes into callee
     * {@code Parameter}-s from arbitrary caller threads, so concurrent
     * insertion of new keys and concurrent updates of existing keys
     * are both expected.</p>
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

    public PointsToSet get(Value v) {
        PointsToSet s = nodeToPointsTo.get(v);
        return s != null ? s : PointsToSet.EMPTY;
    }

    public PointsToSet getIfPresent(Value v) {
        return nodeToPointsTo.get(v);
    }

    /**
     * Directly installs a set. Only safe when the set is itself
     * thread-safe — i.e. obtained from {@link PointsToSet#shared()} or
     * from {@link #get(Value)}. Prefer {@link #merge} or
     * {@link #addSite} which perform the correct atomic install.
     */
    public void set(Value v, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) {
            nodeToPointsTo.remove(v);
            return;
        }
        nodeToPointsTo.put(v, pts);
    }

    /**
     * Atomic merge-all. Creates the entry on first use with
     * {@code shared()} semantics.
     */
    public void addAll(Value v, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) return;
        PointsToSet existing = nodeToPointsTo.computeIfAbsent(v, k -> PointsToSet.shared());
        existing.addAll(pts);
    }

    public void add(Value v, AllocationSite site) {
        addSite(v, site);
    }

    /**
     * Atomic add-site with change detection.
     *
     * <p>Creation uses {@code computeIfAbsent} so that two concurrent
     * callers cannot both observe a null entry, each install a fresh
     * {@code PointsToSet}, and lose one of the sites (last write wins).
     * The installed set is {@code shared()}, so the {@code add} itself
     * is thread-safe.</p>
     */
    public boolean addSite(Value v, AllocationSite site) {
        PointsToSet existing = nodeToPointsTo.computeIfAbsent(v, k -> PointsToSet.shared());
        return existing.addSite(site);
    }

    /**
     * Atomic merge with change detection.
     *
     * <p>Change is detected via a size comparison against the shared
     * set. Under concurrent evaluation the {@code before} observation
     * may be stale; because sets grow monotonically, any growth
     * (from us or from another thread) makes the result {@code true}.
     * A false positive re-enqueues a caller that did not need to be
     * re-enqueued — harmless. A false negative is impossible, because
     * our own {@code addAll} either added at least one new site
     * (size grows) or added none (no growth, correct answer).</p>
     */
    public boolean merge(Value v, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) return false;
        PointsToSet existing = nodeToPointsTo.computeIfAbsent(v, k -> PointsToSet.shared());
        int before = existing.getSites().size();
        existing.addAll(pts);
        return existing.getSites().size() > before;
    }

    public Map<Value, PointsToSet> getAll() {
        return nodeToPointsTo;
    }

    // =================================================================
    //  Field-level points-to
    // =================================================================

    public PointsToSet getFieldPointsTo(AllocationSite site, String field) {
        Map<String, PointsToSet> perSite = fieldPointsTo.get(site);
        if (perSite == null) return PointsToSet.EMPTY;
        PointsToSet s = perSite.get(field);
        return s != null ? s : PointsToSet.EMPTY;
    }

    /**
     * Thread-safe merge into a field's points-to set. Every per-site
     * map and every per-field set is concurrent; creation is atomic
     * through {@code computeIfAbsent} at both levels.
     */
    public boolean mergeFieldPointsTo(AllocationSite site, String field, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) return false;

        Map<String, PointsToSet> perSite =
            fieldPointsTo.computeIfAbsent(site, k -> new ConcurrentHashMap<>());
        PointsToSet existing =
            perSite.computeIfAbsent(field, k -> PointsToSet.shared());

        int before = existing.getSites().size();
        existing.addAll(pts);
        return existing.getSites().size() > before;
    }

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
     * {@code sites}.
     *
     * <p>The result is a fresh, thread-local {@code PointsToSet}
     * (a {@code HashSet}-backed {@code local()} set). It is produced
     * and consumed by a single thread — {@code processInstruction}
     * builds it and immediately passes it to {@code merge}, both on
     * the same thread — so no concurrent modification is possible.
     * The iteration over {@code sites.getSites()} is safe because
     * {@code sites} is a value-level set stored in the graph, i.e.
     * {@code shared()} and therefore weakly consistent.</p>
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

    // =================================================================
    //  Static-field points-to
    // =================================================================

    public PointsToSet getStaticFieldPointsTo(String field) {
        PointsToSet s = staticFieldPointsTo.get(field);
        return s != null ? s : PointsToSet.EMPTY;
    }

    public boolean mergeStaticFieldPointsTo(String field, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) return false;
        PointsToSet existing =
            staticFieldPointsTo.computeIfAbsent(field, k -> PointsToSet.shared());
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
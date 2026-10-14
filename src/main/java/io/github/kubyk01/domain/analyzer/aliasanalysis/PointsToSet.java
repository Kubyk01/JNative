package io.github.kubyk01.domain.analyzer.aliasanalysis;

import lombok.Getter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class PointsToSet {
    /**
     * Thread-safe set.
     *
     * <p>The interprocedural points-to propagation phase evaluates
     * independent functions in parallel (see
     * {@code InterproceduralPointsTo.analyze}), and every one of those
     * evaluations reads from and writes to the same value-level and
     * field-level points-to sets that this class backs. A plain
     * {@link java.util.HashSet} corrupts its internal table under
     * concurrent {@code add}, producing either an infinite loop inside
     * {@code HashMap.resize()} or silently dropped elements — the same
     * failure mode that {@code PointsToGraph.nodeToPointsTo} avoids by
     * using {@link java.util.concurrent.ConcurrentHashMap} for its outer
     * map.</p>
     *
     * <p>{@link ConcurrentHashMap#newKeySet()} is chosen over a
     * {@code Collections.synchronizedSet(new HashSet<>())} wrapper
     * because the workload is overwhelmingly write-heavy: the fast path
     * in {@code InterproceduralPointsTo.processCall} skips a merge when
     * the field already holds {@code UNKNOWN}, but for every pair that
     * is not yet widened the merge performs an atomic insert per element
     * of the source set. A single global lock around every add would
     * serialize all workers on that lock; the striped-locking design of
     * {@link ConcurrentHashMap} keeps them independent.</p>
     *
     * <p>Iteration over the returned set is weakly consistent: it never
     * throws {@code ConcurrentModificationException}, and elements added
     * after an iteration has begun may or may not be observed. That is
     * exactly the semantics the callers need — {@code addAll} copies
     * whatever is currently present, {@code intersects} returns true as
     * soon as a single overlap is found, and the change-detection size
     * comparisons are monotone.</p>
     */
    private final Set<AllocationSite> sites = ConcurrentHashMap.newKeySet();

    /**
     * Adds a single site and returns {@code true} iff the set changed.
     *
     * <p>The change flag comes from the underlying
     * {@link java.util.concurrent.ConcurrentHashMap.KeySetView}, whose
     * {@code add} returns {@code true} when the element was not already
     * present. Callers that do not need the flag can ignore the return
     * value — Java makes this source-compatible with the previous
     * {@code void add(...)} signature.</p>
     */
    public boolean add(AllocationSite site) {
        return sites.add(site);
    }

    /** Alias of {@link #add(AllocationSite)} kept for readability at call
     *  sites where the boolean result is the point. */
    public boolean addSite(AllocationSite site) {
        return sites.add(site);
    }

    public void addAll(PointsToSet other) {
        sites.addAll(other.sites);
    }

    public boolean contains(AllocationSite site) {
        return sites.contains(site);
    }

    /**
     * Fast-path test used by the coarsened field-widening loops in
     * {@code InterproceduralPointsTo.processCall}.
     *
     * <p>Adding {@link AllocationSite#UNKNOWN} to a field is idempotent:
     * once the field's points-to set contains UNKNOWN, every subsequent
     * merge with {@code {UNKNOWN}} is a physical no-op — the destination
     * set is unchanged, and no new information propagates to any reader.
     * The widening loops therefore need a way to ask "is UNKNOWN already
     * present for this field?" without:</p>
     *
     * <ul>
     *   <li>allocating a temporary {@code PointsToSet} to hold
     *       {@code {UNKNOWN}} (see the shared {@code UNKNOWN_SINGLETON}
     *       in {@code InterproceduralPointsTo});</li>
     *   <li>calling {@code AllocationSite.UNKNOWN.equals(other)} in the
     *       inner loop, which would otherwise force a full field-by-field
     *       comparison of two {@code AllocationSite} records on every
     *       iteration;</li>
     *   <li>constructing iterators over the destination set.</li>
     * </ul>
     *
     * <p>Identity comparison is used rather than structural equality:
     * {@code AllocationSite.UNKNOWN} is a process-wide singleton, and
     * the only way UNKNOWN ever enters a points-to set is by a call to
     * {@link #add(AllocationSite)} with that exact instance. Identity is
     * therefore both sufficient and cheaper than structural equality,
     * which matters in the inner loop that this method exists to
     * serve.</p>
     */
    public boolean containsUnknown() {
        return sites.contains(AllocationSite.UNKNOWN);
    }

    public boolean isEmpty() {
        return sites.isEmpty();
    }

    public PointsToSet copy() {
        PointsToSet copy = new PointsToSet();
        copy.sites.addAll(this.sites);
        return copy;
    }

    public boolean intersects(PointsToSet other) {
        for (AllocationSite s : sites) {
            if (other.sites.contains(s)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return sites.toString();
    }
}
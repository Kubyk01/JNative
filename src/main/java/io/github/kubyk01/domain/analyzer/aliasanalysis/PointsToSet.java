package io.github.kubyk01.domain.analyzer.aliasanalysis;

import lombok.Getter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public final class PointsToSet {

    /**
     * Shared immutable instance representing the empty points-to set.
     *
     * <p>Backed by {@link Collections#emptySet()}, so every read is
     * well-defined and every attempt to mutate it raises
     * {@link UnsupportedOperationException} — which the mutators below
     * translate into a clearer diagnostic before the underlying set
     * ever sees the call. There is exactly one such instance for the
     * lifetime of the process, and it is the answer returned by every
     * read of a value or field that has no points-to information
     * yet.</p>
     *
     * <p>The whole point of this singleton is that the hot paths of
     * {@link PointsToGraph#get(io.github.kubyk01.domain.ir.Value)}
     * and friends become non-allocating. On a full JDK reachability
     * closure the points-to graph observes several million distinct
     * {@code Value} instances, of which the overwhelming majority
     * never acquire a single allocation site. Allocating a fresh
     * {@code HashSet}-backed {@code PointsToSet} for every one of
     * those reads is what triggered the C2 "failed reallocation of
     * scalar replaced objects" abort: HotSpot's escape analysis
     * scalar-replaced the per-read {@code PointsToSet}, and the
     * analysis worker threads collectively held enough materialised
     * copies to exhaust the heap.</p>
     */
    public static final PointsToSet EMPTY =
            new PointsToSet(Collections.emptySet(), false);

    /**
     * -- GETTER --
     *  Direct view of the backing store. Callers must not mutate the
     *  returned set on
     *  and must not retain the view
     *  beyond the current evaluation if the set is thread-safe and
     *  concurrently mutable.
     */
    private final Set<AllocationSite> sites;
    private final boolean mutable;

    private PointsToSet(Set<AllocationSite> sites, boolean mutable) {
        this.sites = sites;
        this.mutable = mutable;
    }

    /**
     * Thread-safe mutable set. Use for any set that can be written by
     * more than one thread concurrently: field-level points-to sets,
     * static-field points-to sets, and read-only singletons that are
     * shared across workers.
     */
    public static PointsToSet shared() {
        return new PointsToSet(ConcurrentHashMap.newKeySet(), true);
    }

    /**
     * Non-thread-safe mutable set, sized for the overwhelmingly common
     * case of one to three allocation sites per value. The default
     * HashSet capacity of 16 wastes ~96 bytes per empty set — measurable
     * at the millions of PointsToSet instances created by the
     * points-to fixed point on a full JDK closure.
     */
    public static PointsToSet local() {
        return new PointsToSet(new HashSet<>(4), true);
    }

    /**
     * Immutable empty set. Returns the shared singleton; use this in
     * every read-only lookup that must not allocate.
     */
    public static PointsToSet empty() {
        return EMPTY;
    }

    public boolean add(AllocationSite site) {
        if (!mutable) {
            throw new UnsupportedOperationException(
                "PointsToSet.EMPTY is immutable; call PointsToSet.local() "
                    + "or PointsToSet.shared() to obtain a mutable set");
        }
        return sites.add(site);
    }

    public boolean addSite(AllocationSite site) {
        return add(site);
    }

    public void addAll(PointsToSet other) {
        if (other == null || other.sites.isEmpty()) return;
        if (!mutable) {
            throw new UnsupportedOperationException(
                "PointsToSet.EMPTY is immutable; call PointsToSet.local() "
                    + "or PointsToSet.shared() to obtain a mutable set");
        }
        sites.addAll(other.sites);
    }

    public boolean contains(AllocationSite site) {
        return sites.contains(site);
    }

    public boolean containsUnknown() {
        return sites.contains(AllocationSite.UNKNOWN);
    }

    public boolean isEmpty() {
        return sites.isEmpty();
    }

    public int size() {
        return sites.size();
    }

    public PointsToSet copy() {
        if (sites.isEmpty()) return EMPTY;
        PointsToSet c = new PointsToSet(new HashSet<>(), true);
        c.sites.addAll(this.sites);
        return c;
    }

    public boolean intersects(PointsToSet other) {
        if (other == null) return false;
        Set<AllocationSite> a = this.sites;
        Set<AllocationSite> b = other.sites;
        if (a.isEmpty() || b.isEmpty()) return false;
        // Iterate the smaller set to minimise the number of probes into
        // the larger one.
        if (a.size() > b.size()) {
            Set<AllocationSite> tmp = a;
            a = b;
            b = tmp;
        }
        for (AllocationSite s : a) {
            if (b.contains(s)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return sites.toString();
    }
}

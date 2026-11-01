package io.github.kubyk01.domain.analyzer.aliasanalysis;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hybrid set: linear array for ≤4 sites, {@link ConcurrentHashMap} beyond.
 * Monotone growth only. {@code add}/{@code addAll} synchronized; reads are lock-free.
 */
public final class PointsToSet extends AbstractSet<AllocationSite> {
    private static final int SMALL_MAX = 4;
    public static final PointsToSet EMPTY = new PointsToSet(true);

    private final boolean immutable;
    private volatile AllocationSite[] small;
    private volatile int smallSize;
    private volatile ConcurrentHashMap<AllocationSite, Boolean> large;

    private PointsToSet(boolean immutable) {
        this.immutable = immutable;
        if (!immutable) this.small = new AllocationSite[SMALL_MAX];
    }

    public static PointsToSet shared() { return new PointsToSet(false); }
    public static PointsToSet local()  { return new PointsToSet(false); }
    public static PointsToSet empty()  { return EMPTY; }

    @Override public int size()          { var l = large; return l != null ? l.size() : smallSize; }
    @Override public boolean isEmpty()   { var l = large; return l != null ? l.isEmpty() : smallSize == 0; }

    @Override public boolean contains(Object o) {
        var l = large;
        if (l != null) return l.containsKey(o);
        AllocationSite[] arr = small; int n = smallSize;
        for (int i = 0; i < n; i++) if (arr[i].equals(o)) return true;
        return false;
    }

    public boolean containsUnknown() { return contains(AllocationSite.UNKNOWN); }

    @Override public synchronized boolean add(AllocationSite site) {
        if (immutable) throw new UnsupportedOperationException("immutable PointsToSet");
        return addInternal(site);
    }

    public boolean addSite(AllocationSite site) { return add(site); }

    @Override
    public synchronized boolean addAll(Collection<? extends AllocationSite> c) {
        if (immutable) throw new UnsupportedOperationException("immutable PointsToSet");
        if (c == this || c.isEmpty()) return false;
        boolean changed = false;
        if (c instanceof PointsToSet pts && pts.large == null) {
            AllocationSite[] arr = pts.small; int n = pts.smallSize;
            for (int i = 0; i < n; i++) if (addInternal(arr[i])) changed = true;
        } else {
            for (AllocationSite s : c) if (addInternal(s)) changed = true;
        }
        return changed;
    }

    private boolean addInternal(AllocationSite site) {
        var l = large;
        if (l != null) return l.putIfAbsent(site, Boolean.TRUE) == null;
        AllocationSite[] arr = small; int n = smallSize;
        for (int i = 0; i < n; i++) if (arr[i].equals(site)) return false;
        if (n < SMALL_MAX) { arr[n] = site; smallSize = n + 1; return true; }
        promoteToLarge();
        return large.putIfAbsent(site, Boolean.TRUE) == null;
    }

    private void promoteToLarge() {
        ConcurrentHashMap<AllocationSite, Boolean> m = new ConcurrentHashMap<>(16);
        AllocationSite[] arr = small; int n = smallSize;
        for (int i = 0; i < n; i++) m.put(arr[i], Boolean.TRUE);
        this.large = m;
    }

    @Override public Iterator<AllocationSite> iterator() {
        var l = large;
        if (l != null) return l.keySet().iterator();
        return new Iterator<>() {
            private final AllocationSite[] arr = small;
            private final int n = smallSize;
            private int i;
            @Override public boolean hasNext() { return i < n; }
            @Override public AllocationSite next() {
                if (i >= n) throw new NoSuchElementException();
                return arr[i++];
            }
        };
    }

    public PointsToSet copy() {
        if (isEmpty()) return EMPTY;
        PointsToSet c = new PointsToSet(false);
        synchronized (this) {
            c.addAll(this);
        }
        return c;
    }

    public boolean intersects(PointsToSet other) {
        if (other == null || isEmpty() || other.isEmpty()) return false;
        if (size() > other.size()) { for (AllocationSite s : other) if (contains(s)) return true; }
        else                       { for (AllocationSite s : this)  if (other.contains(s)) return true; }
        return false;
    }

    /** Backwards-compatible alias — returns this (which is a Set). */
    public Set<AllocationSite> getSites() { return this; }
}

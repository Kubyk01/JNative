package io.github.kubyk01.domain.analyzer.aliasanalysis;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PointsToSet extends AbstractSet<AllocationSite> {
    private static final int INITIAL_CAP = 4;
    private static final int SMALL_MAX = 12;

    public static final PointsToSet EMPTY = new PointsToSet(true);

    private final boolean immutable;
    private volatile AllocationSite[] small;
    private volatile int smallSize;
    private volatile ConcurrentHashMap<AllocationSite, Boolean> large;
    private volatile AllocationSite[] largeSnapshot;

    private PointsToSet(boolean immutable) {
        this.immutable = immutable;
    }

    public static PointsToSet shared() { return new PointsToSet(false); }
    public static PointsToSet local()  { return new PointsToSet(false); }
    public static PointsToSet empty()  { return EMPTY; }

    @Override
    public int size() {
        ConcurrentHashMap<AllocationSite, Boolean> l = large;
        return l != null ? l.size() : smallSize;
    }

    @Override
    public boolean isEmpty() {
        ConcurrentHashMap<AllocationSite, Boolean> l = large;
        return l != null ? l.isEmpty() : smallSize == 0;
    }

    @Override
    public boolean contains(Object o) {
        ConcurrentHashMap<AllocationSite, Boolean> l = large;
        if (l != null) return l.containsKey(o);
        AllocationSite[] arr = small;
        if (arr == null) return false;
        int n = smallSize;
        if (!(o instanceof AllocationSite site)) return false;
        int h = site.hashCode();
        for (int i = 0; i < n; i++) {
            AllocationSite a = arr[i];
            if (a == site) return true;
            if (a.hashCode() == h && a.equals(site)) return true;
        }
        return false;
    }

    public boolean containsUnknown() { return contains(AllocationSite.UNKNOWN); }

    @Override
    public synchronized boolean add(AllocationSite site) {
        if (immutable) throw new UnsupportedOperationException("immutable PointsToSet");
        return addInternal(site);
    }

    @Override
    public synchronized boolean addAll(Collection<? extends AllocationSite> c) {
        if (immutable) throw new UnsupportedOperationException("immutable PointsToSet");
        if (c == this || c.isEmpty()) return false;
        boolean changed = false;
        if (c instanceof PointsToSet pts) {
            ConcurrentHashMap<AllocationSite, Boolean> pl = pts.large;
            if (pl == null) {
                AllocationSite[] arr = pts.small;
                int n = pts.smallSize;
                if (arr != null) {
                    for (int i = 0; i < n; i++) {
                        if (addInternal(arr[i])) changed = true;
                    }
                }
            } else {
                for (AllocationSite s : pl.keySet()) {
                    if (addInternal(s)) changed = true;
                }
            }
        } else {
            for (AllocationSite s : c) {
                if (addInternal(s)) changed = true;
            }
        }
        return changed;
    }

    private boolean addInternal(AllocationSite site) {
        ConcurrentHashMap<AllocationSite, Boolean> l = large;
        if (l != null) {
            if (l.putIfAbsent(site, Boolean.TRUE) == null) {
                largeSnapshot = null;
                return true;
            }
            return false;
        }

        AllocationSite[] arr = small;
        if (arr == null) {
            arr = new AllocationSite[INITIAL_CAP];
            small = arr;
        }

        int n = smallSize;
        int h = site.hashCode();
        for (int i = 0; i < n; i++) {
            AllocationSite a = arr[i];
            if (a == site) return false;
            if (a.hashCode() == h && a.equals(site)) return false;
        }

        if (n < arr.length) {
            arr[n] = site;
            smallSize = n + 1;
            return true;
        }

        if (arr.length < SMALL_MAX) {
            int next = Math.min(arr.length * 2, SMALL_MAX);
            AllocationSite[] bigger = new AllocationSite[next];
            System.arraycopy(arr, 0, bigger, 0, n);
            bigger[n] = site;
            small = bigger;
            smallSize = n + 1;
            return true;
        }

        promoteToLarge();
        if (large.putIfAbsent(site, Boolean.TRUE) == null) {
            largeSnapshot = null;
            return true;
        }
        return false;
    }

    private void promoteToLarge() {
        ConcurrentHashMap<AllocationSite, Boolean> m = new ConcurrentHashMap<>(32);
        AllocationSite[] arr = small;
        int n = smallSize;
        if (arr != null) {
            for (int i = 0; i < n; i++) m.put(arr[i], Boolean.TRUE);
        }
        this.large = m;
        this.largeSnapshot = null;
    }

    @Override
    public Iterator<AllocationSite> iterator() {
        ConcurrentHashMap<AllocationSite, Boolean> l = large;
        if (l != null) {
            AllocationSite[] snap = largeSnapshot;
            if (snap == null) {
                snap = l.keySet().toArray(new AllocationSite[0]);
                largeSnapshot = snap;
            }
            final AllocationSite[] a = snap;
            final int n = a.length;
            return new Iterator<>() {
                private int i;
                @Override public boolean hasNext() { return i < n; }
                @Override public AllocationSite next() {
                    if (i >= n) throw new NoSuchElementException();
                    return a[i++];
                }
            };
        }
        AllocationSite[] a = small;
        int n = smallSize;
        if (a == null || n == 0) return Collections.emptyIterator();
        final AllocationSite[] arr = a;
        final int size = n;
        return new Iterator<>() {
            private int i;
            @Override public boolean hasNext() { return i < size; }
            @Override public AllocationSite next() {
                if (i >= size) throw new NoSuchElementException();
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
        if (size() > other.size()) {
            for (AllocationSite s : other) if (contains(s)) return true;
        } else {
            for (AllocationSite s : this) if (other.contains(s)) return true;
        }
        return false;
    }

    public Set<AllocationSite> getSites() { return this; }
}
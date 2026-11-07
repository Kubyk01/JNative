package io.github.kubyk01.domain.analyzer.aliasanalysis;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class PointsToSet extends AbstractSet<AllocationSite> {

    private static final int SMALL_MAX = 12;

    public static final PointsToSet EMPTY = new PointsToSet(true);

    private final boolean immutable;
    private final AtomicReference<Object> state;

    private PointsToSet(boolean immutable) {
        this.immutable = immutable;
        this.state = new AtomicReference<>(null);
    }

    public static PointsToSet shared() { return new PointsToSet(false); }
    public static PointsToSet local()  { return new PointsToSet(false); }
    public static PointsToSet empty()  { return EMPTY; }

    @Override
    public int size() {
        Object cur = state.get();
        if (cur == null) return 0;
        if (cur instanceof AllocationSite[] arr) return arr.length;
        return ((ConcurrentHashMap.KeySetView<?, ?>) cur).size();
    }

    @Override
    public boolean isEmpty() {
        Object cur = state.get();
        if (cur == null) return true;
        if (cur instanceof AllocationSite[] arr) return arr.length == 0;
        return ((ConcurrentHashMap.KeySetView<?, ?>) cur).isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        if (!(o instanceof AllocationSite site)) return false;
        Object cur = state.get();
        if (cur == null) return false;
        if (cur instanceof AllocationSite[] arr) {
            for (AllocationSite a : arr) {
                if (a == site || a.equals(site)) return true;
            }
            return false;
        }
        return view(cur).contains(site);
    }

    public boolean containsUnknown() { return contains(AllocationSite.UNKNOWN); }

    @Override
    public boolean add(AllocationSite site) {
        if (immutable) throw new UnsupportedOperationException("immutable PointsToSet");
        while (true) {
            Object cur = state.get();

            if (cur == null) {
                if (state.compareAndSet(null, new AllocationSite[] { site })) return true;
                continue;
            }

            if (cur instanceof AllocationSite[] arr) {
                for (AllocationSite a : arr) {
                    if (a == site || a.equals(site)) return false;
                }
                int n = arr.length;
                if (n < SMALL_MAX) {
                    AllocationSite[] next = new AllocationSite[n + 1];
                    System.arraycopy(arr, 0, next, 0, n);
                    next[n] = site;
                    if (state.compareAndSet(arr, next)) return true;
                    continue;
                }
                ConcurrentHashMap.KeySetView<AllocationSite, Boolean> v =
                    ConcurrentHashMap.newKeySet(n * 2);
                Collections.addAll(v, arr);
                v.add(site);
                if (state.compareAndSet(arr, v)) return true;
                continue;
            }

            return view(cur).add(site);
        }
    }

    @Override
    public boolean addAll(Collection<? extends AllocationSite> c) {
        if (immutable) throw new UnsupportedOperationException("immutable PointsToSet");
        if (c == this || c.isEmpty()) return false;

        if (c instanceof PointsToSet pts) return addAllFromPointsToSet(pts);

        boolean changed = false;
        for (AllocationSite s : c) {
            if (add(s)) changed = true;
        }
        return changed;
    }

    private boolean addAllFromPointsToSet(PointsToSet other) {
        Object otherState = other.state.get();
        if (otherState == null) return false;

        if (otherState instanceof AllocationSite[] otherArr) {
            boolean changed = false;
            for (AllocationSite s : otherArr) {
                if (add(s)) changed = true;
            }
            return changed;
        }

        ConcurrentHashMap.KeySetView<AllocationSite, Boolean> otherView = view(otherState);

        while (true) {
            Object cur = state.get();
            if (cur instanceof ConcurrentHashMap.KeySetView) {
                ConcurrentHashMap.KeySetView<AllocationSite, Boolean> v = view(cur);
                boolean changed = false;
                for (AllocationSite s : otherView) {
                    if (v.add(s)) changed = true;
                }
                return changed;
            }

            ConcurrentHashMap.KeySetView<AllocationSite, Boolean> newView =
                ConcurrentHashMap.newKeySet(Math.max(8, otherView.size() + 4));
            if (cur instanceof AllocationSite[] arr) {
                Collections.addAll(newView, arr);
            }
            if (state.compareAndSet(cur, newView)) {
                boolean changed = false;
                for (AllocationSite s : otherView) {
                    if (newView.add(s)) changed = true;
                }
                return changed;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap.KeySetView<AllocationSite, Boolean> view(Object o) {
        return (ConcurrentHashMap.KeySetView<AllocationSite, Boolean>) o;
    }

    @Override
    public Iterator<AllocationSite> iterator() {
        Object cur = state.get();
        if (cur == null) return Collections.emptyIterator();
        if (cur instanceof AllocationSite[] arr) return Arrays.asList(arr).iterator();
        return view(cur).iterator();
    }

    public PointsToSet copy() {
        Object cur = state.get();
        if (cur == null) return EMPTY;
        PointsToSet c = new PointsToSet(false);
        if (cur instanceof AllocationSite[] arr) {
            c.state.set(Arrays.copyOf(arr, arr.length));
        } else {
            ConcurrentHashMap.KeySetView<AllocationSite, Boolean> v = view(cur);
            ConcurrentHashMap.KeySetView<AllocationSite, Boolean> copyView =
                ConcurrentHashMap.newKeySet(v.size() * 2);
            copyView.addAll(v);
            c.state.set(copyView);
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

package io.github.kubyk01.domain.analyzer.aliasanalysis;

import java.lang.ref.WeakReference;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

public final class PointsToSet extends AbstractSet<AllocationSite> {

    private static final int[] NO_IDS = new int[0];

    private static final class Stripe {
        @SuppressWarnings("unchecked")
        WeakReference<PointsToSet>[] refs = new WeakReference[64];
        int[] keys = new int[64];
        int used;
    }

    private static final Stripe[] INTERNED = new Stripe[256];

    static {
        for (int i = 0; i < INTERNED.length; i++) INTERNED[i] = new Stripe();
    }

    private static final ThreadLocal<int[][]> SCRATCH = ThreadLocal.withInitial(() -> new int[][]{new int[256]});

    static int[] scratch(int capacity) {
        int[][] holder = SCRATCH.get();
        if (holder[0].length < capacity) holder[0] = new int[Math.max(capacity, holder[0].length << 1)];
        return holder[0];
    }

    private static int keyOf(int[] a, int n) {
        int h = n;
        for (int i = 0; i < n; i++) h = h * 0x9E3779B1 + a[i];
        return h ^ (h >>> 15);
    }

    private static boolean sameIds(int[] ids, int[] a, int n) {
        if (ids.length != n) return false;
        for (int i = 0; i < n; i++) {
            if (ids[i] != a[i]) return false;
        }
        return true;
    }

    static PointsToSet intern(int[] a, int n, boolean owned) {
        if (n == 0) return EMPTY;
        if (n == 1) return AllocationSite.byId(a[0]).singleton();
        int key = keyOf(a, n);
        Stripe stripe = INTERNED[key & 255];
        synchronized (stripe) {
            WeakReference<PointsToSet>[] refs = stripe.refs;
            int[] keys = stripe.keys;
            int mask = refs.length - 1;
            int i = (key >>> 8) & mask;
            int free = -1;
            for (WeakReference<PointsToSet> r; (r = refs[i]) != null; i = (i + 1) & mask) {
                PointsToSet e = r.get();
                if (e == null) {
                    if (free < 0) free = i;
                } else if (keys[i] == key && sameIds(e.ids, a, n)) {
                    return e;
                }
            }
            PointsToSet created = new PointsToSet(owned && a.length == n ? a : Arrays.copyOf(a, n), key);
            int slot = free >= 0 ? free : i;
            refs[slot] = new WeakReference<>(created);
            keys[slot] = key;
            if (free < 0 && ++stripe.used * 2 > refs.length) rehash(stripe);
            return created;
        }
    }

    @SuppressWarnings("unchecked")
    private static void rehash(Stripe stripe) {
        WeakReference<PointsToSet>[] old = stripe.refs;
        int[] oldKeys = stripe.keys;
        int live = 0;
        for (WeakReference<PointsToSet> r : old) {
            if (r != null && r.get() != null) live++;
        }
        int capacity = Math.max(64, Integer.highestOneBit(Math.max(1, live) * 4));
        WeakReference<PointsToSet>[] refs = new WeakReference[capacity];
        int[] keys = new int[capacity];
        int mask = capacity - 1;
        for (int j = 0; j < old.length; j++) {
            WeakReference<PointsToSet> r = old[j];
            if (r == null || r.get() == null) continue;
            int i = (oldKeys[j] >>> 8) & mask;
            while (refs[i] != null) i = (i + 1) & mask;
            refs[i] = r;
            keys[i] = oldKeys[j];
        }
        stripe.refs = refs;
        stripe.keys = keys;
        stripe.used = live;
    }

    public static final PointsToSet EMPTY = new PointsToSet(NO_IDS, 0);

    private final int[] ids;
    private final int key;
    private int hash;

    private PointsToSet(int[] ids, int key) {
        this.ids = ids;
        this.key = key;
    }

    public static PointsToSet empty() { return EMPTY; }

    public static PointsToSet ofSortedUnique(int[] ids) {
        if (ids.length == 1) return new PointsToSet(ids, keyOf(ids, 1));
        return intern(ids, ids.length, true);
    }

    public static PointsToSet copyOf(Collection<AllocationSite> sites) {
        if (sites == null || sites.isEmpty()) return EMPTY;
        if (sites instanceof PointsToSet pts) return pts;
        int[] a = scratch(sites.size());
        int n = 0;
        for (AllocationSite s : sites) a[n++] = s.getId();
        Arrays.sort(a, 0, n);
        int w = 0;
        for (int i = 0; i < n; i++) {
            if (w == 0 || a[w - 1] != a[i]) a[w++] = a[i];
        }
        return intern(a, w, false);
    }

    public int[] rawIds() { return ids; }

    @Override
    public int size() { return ids.length; }

    @Override
    public boolean isEmpty() { return ids.length == 0; }

    @Override
    public boolean contains(Object o) {
        return o instanceof AllocationSite site && containsId(site.getId());
    }

    public boolean containsId(int id) {
        int[] a = ids;
        int n = a.length;
        if (n <= 8) {
            for (int v : a) {
                if (v == id) return true;
                if (v > id) return false;
            }
            return false;
        }
        return Arrays.binarySearch(a, id) >= 0;
    }

    public boolean containsUnknown() { return containsId(AllocationSite.UNKNOWN.getId()); }

    @Override
    public Iterator<AllocationSite> iterator() {
        int[] a = ids;
        return new Iterator<>() {
            private int i;

            @Override
            public boolean hasNext() { return i < a.length; }

            @Override
            public AllocationSite next() {
                if (i >= a.length) throw new NoSuchElementException();
                return AllocationSite.byId(a[i++]);
            }
        };
    }

    public PointsToSet copy() { return this; }

    public boolean intersects(PointsToSet other) {
        if (other == null || isEmpty() || other.isEmpty()) return false;
        if (other == this) return true;
        int[] a = ids, b = other.ids;
        int i = 0, j = 0;
        while (i < a.length && j < b.length) {
            int x = a[i], y = b[j];
            if (x == y) return true;
            if (x < y) i++; else j++;
        }
        return false;
    }

    public static PointsToSet union(PointsToSet a, PointsToSet b) {
        if (a == b || b == null || b.isEmpty()) return a == null ? EMPTY : a;
        if (a == null || a.isEmpty()) return b;
        int[] x = a.ids, y = b.ids;
        int nx = x.length, ny = y.length;
        int i = 0, j = 0, n = 0;
        if (x[nx - 1] >= y[0] && y[ny - 1] >= x[0]) {
            while (i < nx && j < ny) {
                int u = x[i], v = y[j];
                if (u == v) { i++; j++; }
                else if (u < v) i++;
                else j++;
                n++;
            }
        }
        n += (nx - i) + (ny - j);
        if (n == nx) return a;
        if (n == ny) return b;
        int[] out = scratch(n);
        i = 0; j = 0;
        int k = 0;
        while (i < nx && j < ny) {
            int u = x[i], v = y[j];
            if (u == v) { out[k++] = u; i++; j++; }
            else if (u < v) out[k++] = x[i++];
            else out[k++] = y[j++];
        }
        while (i < nx) out[k++] = x[i++];
        while (j < ny) out[k++] = y[j++];
        return intern(out, n, false);
    }

    public Set<AllocationSite> getSites() { return this; }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (o instanceof PointsToSet other) return Arrays.equals(ids, other.ids);
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        int h = hash;
        if (h == 0 && ids.length != 0) {
            for (int id : ids) h += AllocationSite.byId(id).hashCode();
            hash = h;
        }
        return h;
    }
}

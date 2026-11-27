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
        // todo remove synchronized
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

    static final int DELTA_MIN_BASE = 512;
    private static final int DELTA_MAX = 1024;

    private final int[] ids;
    private final int[] extra;
    private final int key;
    private int hash;

    private PointsToSet(int[] ids, int key) {
        this(ids, NO_IDS, key);
    }

    private PointsToSet(int[] ids, int[] extra, int key) {
        this.ids = ids;
        this.extra = extra;
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

    public long cursor() { return 0L; }

    public boolean has(long c) {
        return (int) (c >>> 32) < ids.length || (int) c < extra.length;
    }

    public int id(long c) {
        int i = (int) (c >>> 32);
        int j = (int) c;
        if (i >= ids.length) return extra[j];
        if (j >= extra.length) return ids[i];
        return Math.min(ids[i], extra[j]);
    }

    public long next(long c) {
        int i = (int) (c >>> 32);
        int j = (int) c;
        if (j >= extra.length || (i < ids.length && ids[i] < extra[j])) return ((long) (i + 1) << 32) | j;
        return ((long) i << 32) | (j + 1);
    }

    @Override
    public int size() { return ids.length + extra.length; }

    @Override
    public boolean isEmpty() { return ids.length == 0 && extra.length == 0; }

    @Override
    public boolean contains(Object o) {
        return o instanceof AllocationSite site && containsId(site.getId());
    }

    public boolean containsId(int id) {
        return containsIn(ids, id) || (extra.length != 0 && containsIn(extra, id));
    }

    private static boolean containsIn(int[] a, int id) {
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
        return new Iterator<>() {
            private long c = cursor();

            @Override
            public boolean hasNext() { return has(c); }

            @Override
            public AllocationSite next() {
                if (!has(c)) throw new NoSuchElementException();
                AllocationSite s = AllocationSite.byId(id(c));
                c = PointsToSet.this.next(c);
                return s;
            }
        };
    }

    public PointsToSet copy() { return this; }

    void addTo(SiteSetBuilder builder) {
        for (int id : ids) builder.add(id);
        for (int id : extra) builder.add(id);
    }

    public boolean intersects(PointsToSet other) {
        if (other == null || isEmpty() || other.isEmpty()) return false;
        if (other == this) return true;
        long ca = cursor();
        long cb = other.cursor();
        while (has(ca) && other.has(cb)) {
            int x = id(ca), y = other.id(cb);
            if (x == y) return true;
            if (x < y) ca = next(ca); else cb = other.next(cb);
        }
        return false;
    }

    public PointsToSet minus(PointsToSet other) {
        if (this == other) return EMPTY;
        if (other.isEmpty()) return this;
        int[] out = scratch(size());
        int n = 0;
        long previous = other.cursor();
        for (long position = cursor(); has(position); position = next(position)) {
            int id = id(position);
            while (other.has(previous) && other.id(previous) < id) previous = other.next(previous);
            if (!other.has(previous) || other.id(previous) != id) out[n++] = id;
        }
        return n == size() ? this : intern(out, n, false);
    }

    public static PointsToSet union(PointsToSet a, PointsToSet b) {
        if (a == b || b == null || b.isEmpty()) return a == null ? EMPTY : a;
        if (a == null || a.isEmpty()) return b;
        PointsToSet big = a.size() >= b.size() ? a : b;
        PointsToSet small = big == a ? b : a;
        if (big.size() >= DELTA_MIN_BASE) {
            int[] out = scratch(small.size());
            int n = 0;
            for (long c = small.cursor(); small.has(c); c = small.next(c)) {
                int id = small.id(c);
                if (!big.containsId(id)) out[n++] = id;
            }
            if (n == 0) return big;
            return plus(big, out, n);
        }
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

    static PointsToSet plus(PointsToSet big, int[] add, int n) {
        if (n == 0) return big;
        int[] base = big.ids;
        int[] ex = big.extra;
        int total = ex.length + n;
        if (base.length >= DELTA_MIN_BASE && total <= Math.min(base.length >>> 2, DELTA_MAX)) {
            int[] merged = new int[total];
            int i = 0, j = 0, k = 0;
            while (i < ex.length && j < n) merged[k++] = ex[i] < add[j] ? ex[i++] : add[j++];
            while (i < ex.length) merged[k++] = ex[i++];
            while (j < n) merged[k++] = add[j++];
            return new PointsToSet(base, merged, 0);
        }
        int[] flat = new int[base.length + total];
        int i = 0, j = 0, k = 0, w = 0;
        while (true) {
            int u = i < base.length ? base[i] : Integer.MAX_VALUE;
            int v = j < ex.length ? ex[j] : Integer.MAX_VALUE;
            int x = k < n ? add[k] : Integer.MAX_VALUE;
            int m = Math.min(u, Math.min(v, x));
            if (m == Integer.MAX_VALUE) break;
            flat[w++] = m;
            if (u == m) i++;
            if (v == m) j++;
            if (x == m) k++;
        }
        return intern(flat, w, true);
    }

    public Set<AllocationSite> getSites() { return this; }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (o instanceof PointsToSet other) {
            if (size() != other.size()) return false;
            if (extra.length == 0 && other.extra.length == 0) return Arrays.equals(ids, other.ids);
            long ca = cursor();
            long cb = other.cursor();
            while (has(ca)) {
                if (id(ca) != other.id(cb)) return false;
                ca = next(ca);
                cb = other.next(cb);
            }
            return true;
        }
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        int h = hash;
        if (h == 0 && !isEmpty()) {
            for (long c = cursor(); has(c); c = next(c)) h += AllocationSite.byId(id(c)).hashCode();
            hash = h;
        }
        return h;
    }
}

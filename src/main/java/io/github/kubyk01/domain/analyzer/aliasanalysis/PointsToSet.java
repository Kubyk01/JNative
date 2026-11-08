package io.github.kubyk01.domain.analyzer.aliasanalysis;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

public final class PointsToSet extends AbstractSet<AllocationSite> {

    private static final int[] NO_IDS = new int[0];

    public static final PointsToSet EMPTY = new PointsToSet(NO_IDS);

    private final int[] ids;
    private int hash;

    private PointsToSet(int[] ids) {
        this.ids = ids;
    }

    public static PointsToSet empty() { return EMPTY; }

    public static PointsToSet ofSortedUnique(int[] ids) {
        return ids.length == 0 ? EMPTY : new PointsToSet(ids);
    }

    public static PointsToSet copyOf(Collection<AllocationSite> sites) {
        if (sites == null || sites.isEmpty()) return EMPTY;
        if (sites instanceof PointsToSet pts) return pts;
        int[] a = new int[sites.size()];
        int n = 0;
        for (AllocationSite s : sites) a[n++] = s.getId();
        Arrays.sort(a, 0, n);
        int w = 0;
        for (int i = 0; i < n; i++) {
            if (w == 0 || a[w - 1] != a[i]) a[w++] = a[i];
        }
        if (w == 1) return AllocationSite.byId(a[0]).singleton();
        return new PointsToSet(w == a.length ? a : Arrays.copyOf(a, w));
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
        if (x[nx - 1] < y[0]) return new PointsToSet(concat(x, y));
        if (y[ny - 1] < x[0]) return new PointsToSet(concat(y, x));
        int i = 0, j = 0, n = 0;
        while (i < nx && j < ny) {
            int u = x[i], v = y[j];
            if (u == v) { i++; j++; }
            else if (u < v) i++;
            else j++;
            n++;
        }
        n += (nx - i) + (ny - j);
        if (n == nx) return a;
        if (n == ny) return b;
        int[] out = new int[n];
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
        return new PointsToSet(out);
    }

    private static int[] concat(int[] lo, int[] hi) {
        int[] out = Arrays.copyOf(lo, lo.length + hi.length);
        System.arraycopy(hi, 0, out, lo.length, hi.length);
        return out;
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

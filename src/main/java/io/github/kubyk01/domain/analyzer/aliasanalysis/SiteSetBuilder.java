package io.github.kubyk01.domain.analyzer.aliasanalysis;

import java.util.Arrays;

public final class SiteSetBuilder {

    private static final ThreadLocal<SiteSetBuilder> LOCAL = ThreadLocal.withInitial(SiteSetBuilder::new);

    private long[] words = new long[1024];
    private int[] touched = new int[256];
    private int touchedCount;
    private int count;
    private boolean busy;

    public static SiteSetBuilder acquire() {
        SiteSetBuilder b = LOCAL.get();
        if (b.busy) return new SiteSetBuilder();
        b.busy = true;
        return b;
    }

    public void release() {
        clear();
        busy = false;
    }

    public int size() { return count; }

    public void add(int id) {
        int w = id >>> 6;
        if (w >= words.length) words = Arrays.copyOf(words, Math.max(w + 1, words.length << 1));
        long bit = 1L << id;
        long cur = words[w];
        if ((cur & bit) != 0) return;
        if (cur == 0) {
            if (touchedCount == touched.length) touched = Arrays.copyOf(touched, touched.length << 1);
            touched[touchedCount++] = w;
        }
        words[w] = cur | bit;
        count++;
    }

    public void addAll(PointsToSet s) {
        for (int id : s.rawIds()) add(id);
    }

    public PointsToSet buildOr(PointsToSet base) {
        if (base != null && count == base.size()) {
            clear();
            return base;
        }
        return build();
    }

    public PointsToSet build() {
        if (count == 0) return PointsToSet.EMPTY;
        int[] out = new int[count];
        Arrays.sort(touched, 0, touchedCount);
        int k = 0;
        for (int t = 0; t < touchedCount; t++) {
            int w = touched[t];
            long bits = words[w];
            words[w] = 0;
            int baseId = w << 6;
            while (bits != 0) {
                int b = Long.numberOfTrailingZeros(bits);
                out[k++] = baseId + b;
                bits &= bits - 1;
            }
        }
        touchedCount = 0;
        count = 0;
        if (out.length == 1) return AllocationSite.byId(out[0]).singleton();
        return PointsToSet.ofSortedUnique(out);
    }

    public void clear() {
        for (int t = 0; t < touchedCount; t++) words[touched[t]] = 0;
        touchedCount = 0;
        count = 0;
    }
}

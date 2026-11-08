package io.github.kubyk01.domain.analyzer.aliasanalysis;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.IntConsumer;

public final class FieldCell {

    private static final int SMALL_READERS = 256;
    private static final VarHandle PTS;
    private static final VarHandle VERSION;
    private static final VarHandle READERS;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            PTS = l.findVarHandle(FieldCell.class, "pts", PointsToSet.class);
            VERSION = l.findVarHandle(FieldCell.class, "version", long.class);
            READERS = l.findVarHandle(FieldCell.class, "readers", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int fieldId;
    private final String field;
    private final PointsToSet pts = PointsToSet.EMPTY;
    private volatile long version;
    private volatile Object readers;

    FieldCell(int fieldId, String field) {
        this.fieldId = fieldId;
        this.field = field;
    }

    public int fieldId() { return fieldId; }

    public String field() { return field; }

    public PointsToSet get() { return pts; }

    public long version() { return version; }

    public boolean merge(PointsToSet add) {
        if (add == null || add.isEmpty()) return false;
        while (true) {
            PointsToSet cur = pts;
            PointsToSet next = PointsToSet.union(cur, add);
            if (next == cur) return false;
            if (PTS.compareAndSet(this, cur, next)) return true;
        }
    }

    public void advanceVersion(long v) {
        while (true) {
            long cur = version;
            if (cur >= v) return;
            if (VERSION.compareAndSet(this, cur, v)) return;
        }
    }

    public boolean addReader(int reader, int capacity) {
        while (true) {
            Object r = readers;
            if (r == null) {
                if (READERS.compareAndSet(this, null, new int[]{reader})) return true;
                continue;
            }
            if (r instanceof int[] arr) {
                int pos = Arrays.binarySearch(arr, reader);
                if (pos >= 0) return false;
                Object next;
                if (arr.length >= SMALL_READERS) {
                    AtomicLongArray bits = new AtomicLongArray((capacity >>> 6) + 1);
                    for (int x : arr) bits.set(x >>> 6, bits.get(x >>> 6) | (1L << x));
                    bits.set(reader >>> 6, bits.get(reader >>> 6) | (1L << reader));
                    next = bits;
                } else {
                    int ins = -pos - 1;
                    int[] grown = new int[arr.length + 1];
                    System.arraycopy(arr, 0, grown, 0, ins);
                    grown[ins] = reader;
                    System.arraycopy(arr, ins, grown, ins + 1, arr.length - ins);
                    next = grown;
                }
                if (READERS.compareAndSet(this, arr, next)) return true;
                continue;
            }
            AtomicLongArray bits = (AtomicLongArray) r;
            int w = reader >>> 6;
            long mask = 1L << reader;
            while (true) {
                long cur = bits.get(w);
                if ((cur & mask) != 0) return false;
                if (bits.compareAndSet(w, cur, cur | mask)) return true;
            }
        }
    }

    public void forEachReader(IntConsumer action) {
        Object r = readers;
        if (r == null) return;
        if (r instanceof int[] arr) {
            for (int x : arr) action.accept(x);
            return;
        }
        AtomicLongArray bits = (AtomicLongArray) r;
        for (int w = 0, n = bits.length(); w < n; w++) {
            long word = bits.get(w);
            while (word != 0) {
                int b = Long.numberOfTrailingZeros(word);
                action.accept((w << 6) + b);
                word &= word - 1;
            }
        }
    }

    public void clearReaders() {
        readers = null;
    }
}

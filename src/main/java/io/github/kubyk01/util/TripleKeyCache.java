package io.github.kubyk01.util;

public final class TripleKeyCache<V> {

    private record Entry(String a, String b, String c, int hash, Object value) {}

    private static final int STRIPES = 64;

    private final Entry[][] tables = new Entry[STRIPES][256];
    private final int[] sizes = new int[STRIPES];
    private final Object[] locks = new Object[STRIPES];

    public TripleKeyCache() {
        for (int i = 0; i < STRIPES; i++) locks[i] = new Object();
    }

    private static int hash(String a, String b, String c) {
        return (a.hashCode() * 31 + b.hashCode()) * 31 + c.hashCode();
    }

    @SuppressWarnings("unchecked")
    public V get(String a, String b, String c) {
        int hash = hash(a, b, c);
        int spread = hash ^ (hash >>> 16);
        int stripe = spread & (STRIPES - 1);
        synchronized (locks[stripe]) {
            Entry[] table = tables[stripe];
            int mask = table.length - 1;
            for (int i = (spread >>> 6) & mask; table[i] != null; i = (i + 1) & mask) {
                Entry e = table[i];
                if (e.hash() == hash && e.a().equals(a) && e.b().equals(b) && e.c().equals(c)) return (V) e.value();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public V putIfAbsent(String a, String b, String c, V value) {
        int hash = hash(a, b, c);
        int spread = hash ^ (hash >>> 16);
        int stripe = spread & (STRIPES - 1);
        synchronized (locks[stripe]) {
            Entry[] table = tables[stripe];
            if ((sizes[stripe] + 1) * 2 > table.length) {
                Entry[] grown = new Entry[table.length << 1];
                int gm = grown.length - 1;
                for (Entry e : table) {
                    if (e == null) continue;
                    int es = e.hash() ^ (e.hash() >>> 16);
                    int j = (es >>> 6) & gm;
                    while (grown[j] != null) j = (j + 1) & gm;
                    grown[j] = e;
                }
                tables[stripe] = table = grown;
            }
            int mask = table.length - 1;
            int i = (spread >>> 6) & mask;
            for (; table[i] != null; i = (i + 1) & mask) {
                Entry e = table[i];
                if (e.hash() == hash && e.a().equals(a) && e.b().equals(b) && e.c().equals(c)) return (V) e.value();
            }
            table[i] = new Entry(a, b, c, hash, value);
            sizes[stripe]++;
            return value;
        }
    }
}

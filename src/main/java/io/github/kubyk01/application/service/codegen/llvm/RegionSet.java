package io.github.kubyk01.application.service.codegen.llvm;

import java.util.ArrayList;
import java.util.List;

final class RegionSet {
    private String[] keys;
    private int size;

    RegionSet(int expected) {
        int cap = 16;
        while (cap < expected * 2) cap <<= 1;
        keys = new String[cap];
    }

    static int hash(CharSequence s, int from, int to) {
        int h = 0;
        for (int i = from; i < to; i++) h = 31 * h + s.charAt(i);
        return h;
    }

    /** The stored key equal to {@code text[from, to)}, or null. */
    String find(String text, int from, int to, int hash) {
        int len = to - from;
        int mask = keys.length - 1;
        for (int i = spread(hash) & mask; ; i = (i + 1) & mask) {
            String k = keys[i];
            if (k == null) return null;
            if (k.length() == len && k.hashCode() == hash && text.regionMatches(from, k, 0, len)) return k;
        }
    }

    /** Adds {@code text[from, to)} unless present; allocates only on insert. */
    void addRegion(String text, int from, int to, int hash) {
        if (find(text, from, to, hash) == null) insert(text.substring(from, to));
    }

    void add(String key) {
        if (find(key, 0, key.length(), key.hashCode()) == null) insert(key);
    }

    List<String> toList() {
        List<String> out = new ArrayList<>(size);
        for (String k : keys) if (k != null) out.add(k);
        return out;
    }

    private void insert(String key) {
        if ((size + 1) * 2 > keys.length) {
            String[] old = keys;
            keys = new String[old.length << 1];
            for (String k : old) if (k != null) place(k);
        }
        place(key);
        size++;
    }

    private void place(String key) {
        int mask = keys.length - 1;
        int i = spread(key.hashCode()) & mask;
        while (keys[i] != null) i = (i + 1) & mask;
        keys[i] = key;
    }

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }
}

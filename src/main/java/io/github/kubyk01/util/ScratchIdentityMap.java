package io.github.kubyk01.util;

import java.util.Arrays;

public final class ScratchIdentityMap<K, V> {

    private Object[] keys = new Object[32];
    private Object[] values = new Object[32];
    private int[] used = new int[16];
    private int size;

    public void clear() {
        for (int i = 0; i < size; i++) {
            keys[used[i]] = null;
            values[used[i]] = null;
        }
        size = 0;
    }

    public int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    public K keyAt(int i) {
        return (K) keys[used[i]];
    }

    @SuppressWarnings("unchecked")
    public V valueAt(int i) {
        return (V) values[used[i]];
    }

    @SuppressWarnings("unchecked")
    public V get(Object key) {
        Object[] k = keys;
        int mask = k.length - 1;
        for (int i = slot(key, mask); ; i = (i + 1) & mask) {
            Object e = k[i];
            if (e == null) return null;
            if (e == key) return (V) values[i];
        }
    }

    public boolean containsKey(Object key) {
        Object[] k = keys;
        int mask = k.length - 1;
        for (int i = slot(key, mask); ; i = (i + 1) & mask) {
            Object e = k[i];
            if (e == null) return false;
            if (e == key) return true;
        }
    }

    public boolean put(K key, V value) {
        if ((size + 1) * 2 > keys.length) grow();
        int mask = keys.length - 1;
        int i = slot(key, mask);
        while (keys[i] != null) {
            if (keys[i] == key) {
                values[i] = value;
                return false;
            }
            i = (i + 1) & mask;
        }
        keys[i] = key;
        values[i] = value;
        if (size == used.length) used = Arrays.copyOf(used, size << 1);
        used[size++] = i;
        return true;
    }

    @SuppressWarnings("unchecked")
    private void grow() {
        Object[] oldKeys = keys;
        Object[] oldValues = values;
        int[] oldUsed = Arrays.copyOf(used, size);
        int n = size;
        keys = new Object[oldKeys.length << 1];
        values = new Object[oldKeys.length << 1];
        size = 0;
        for (int j = 0; j < n; j++) {
            int i = oldUsed[j];
            put((K) oldKeys[i], (V) oldValues[i]);
        }
    }

    private static int slot(Object key, int mask) {
        int h = System.identityHashCode(key);
        return (h ^ (h >>> 16)) * 0x9E3779B1 >>> 7 & mask;
    }
}

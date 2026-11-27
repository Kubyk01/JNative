package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.ir.BasicBlock;

final class BlockIndexMap {

    private BasicBlock[] keys = new BasicBlock[32];
    private int[] values = new int[32];
    private int[] used = new int[16];
    private int size;

    void clear() {
        for (int i = 0; i < size; i++) keys[used[i]] = null;
        size = 0;
    }

    int size() {
        return size;
    }

    int get(BasicBlock block) {
        BasicBlock[] k = keys;
        int mask = k.length - 1;
        for (int i = slot(block, mask); ; i = (i + 1) & mask) {
            BasicBlock e = k[i];
            if (e == null) return -1;
            if (e == block) return values[i];
        }
    }

    void put(BasicBlock block, int value) {
        if ((size + 1) * 2 > keys.length) grow();
        int mask = keys.length - 1;
        int i = slot(block, mask);
        while (keys[i] != null) {
            if (keys[i] == block) {
                values[i] = value;
                return;
            }
            i = (i + 1) & mask;
        }
        keys[i] = block;
        values[i] = value;
        if (size == used.length) used = java.util.Arrays.copyOf(used, size << 1);
        used[size++] = i;
    }

    private void grow() {
        BasicBlock[] oldKeys = keys;
        int[] oldValues = values;
        int[] oldUsed = used;
        int n = size;
        keys = new BasicBlock[oldKeys.length << 1];
        values = new int[oldKeys.length << 1];
        size = 0;
        for (int j = 0; j < n; j++) {
            int i = oldUsed[j];
            put(oldKeys[i], oldValues[i]);
        }
    }

    private static int slot(BasicBlock block, int mask) {
        int h = System.identityHashCode(block);
        return (h ^ (h >>> 16)) * 0x9E3779B1 >>> 7 & mask;
    }
}

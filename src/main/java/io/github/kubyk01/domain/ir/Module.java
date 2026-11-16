package io.github.kubyk01.domain.ir;

import lombok.Getter;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;

public class Module {
    @Getter
    private final FunctionList functions = new FunctionList();
    private final Map<String, Function> functionMap = new ConcurrentHashMap<>();

    public void addFunction(Function func) {
        if (functionMap.putIfAbsent(func.getName(), func) == null) {
            functions.append(func);
            func.setModule(this);
        }
    }

    public void removeFunction(Function func) {
        if (func == null) return;
        removeFunctions(Set.of(func));
    }

    /** Removes several functions with one compaction pass instead of one per function. */
    public void removeFunctions(Collection<Function> funcs) {
        if (funcs.isEmpty()) return;
        functions.removeAllOf(funcs);
        for (Function f : funcs) functionMap.remove(f.getName());
    }

    public Function getFunction(String name) {
        return functionMap.get(name);
    }

    /**
     * Registers {@code aliasName} as an additional lookup key for
     * {@code target}. The target itself must already have been added via
     * {@link #addFunction}. Used by the override machinery to make the
     * mangled name of a Java method resolve to a C override function whose
     * own mangled name is different.
     */
    public void registerAlias(String aliasName, Function target) {
        if (aliasName == null || target == null) return;
        functionMap.put(aliasName, target);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("module {\n");
        for (Function f : functions) {
            sb.append(f);
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Read-only, snapshot-iterating function list with amortized O(1) append.
     * Replaces a CopyOnWriteArrayList, whose per-add full array copy made
     * module construction quadratic. Readers see an immutable (array, size)
     * pair: appends only write past every published size or into a fresh
     * array, and removals always build a fresh array, so iteration never
     * races with writers.
     */
    public static final class FunctionList extends AbstractList<Function> implements RandomAccess {
        private record Snap(Function[] items, int size) {}

        private volatile Snap snap = new Snap(new Function[256], 0);

        private synchronized void append(Function f) {
            Snap s = snap;
            Function[] items = s.items;
            if (s.size == items.length) items = Arrays.copyOf(items, items.length * 2);
            items[s.size] = f;
            snap = new Snap(items, s.size + 1);
        }

        private synchronized void removeAllOf(Collection<Function> funcs) {
            Snap s = snap;
            Function[] out = new Function[Math.max(256, s.items.length)];
            int n = 0;
            for (int i = 0; i < s.size; i++) {
                if (!funcs.contains(s.items[i])) out[n++] = s.items[i];
            }
            if (n != s.size) snap = new Snap(out, n);
        }

        @Override
        public Function get(int index) {
            Snap s = snap;
            Objects.checkIndex(index, s.size);
            return s.items[index];
        }

        @Override
        public int size() {
            return snap.size;
        }

        @Override
        public Iterator<Function> iterator() {
            return Spliterators.iterator(spliterator());
        }

        @Override
        public Spliterator<Function> spliterator() {
            Snap s = snap;
            return Arrays.spliterator(s.items, 0, s.size);
        }
    }
}

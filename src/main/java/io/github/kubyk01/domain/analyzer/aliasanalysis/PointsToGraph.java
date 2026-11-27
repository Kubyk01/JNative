package io.github.kubyk01.domain.analyzer.aliasanalysis;

import io.github.kubyk01.domain.ir.Value;
import lombok.Getter;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class PointsToGraph {

    public static final String ARRAY_ELEMENT_FIELD = "[]";

    private static final int CHUNK_BITS = 12;
    private static final int CHUNK_SIZE = 1 << CHUNK_BITS;
    private static final int CHUNK_MASK = CHUNK_SIZE - 1;
    private static final FieldCell[] NO_CELLS = new FieldCell[0];

    private final AtomicReferenceArray<AtomicReferenceArray<SiteFields>> siteCells =
        new AtomicReferenceArray<>(1 << (31 - CHUNK_BITS));
    private final ConcurrentHashMap<String, Integer> fieldIds = new ConcurrentHashMap<>();
    private final AtomicInteger nextFieldId = new AtomicInteger();
    private final ConcurrentHashMap<String, FieldCell> staticCells = new ConcurrentHashMap<>();

    @Getter
    private final Map<AllocationSite, Value> allocationSiteToValue = new HashMap<>();

    public void putAllocationSite(AllocationSite site, Value value) {
        allocationSiteToValue.put(site, value);
    }

    public PointsToSet get(Value v) {
        Object o = v.getPointsTo();
        return o == null ? PointsToSet.EMPTY : (PointsToSet) o;
    }

    public PointsToSet getIfPresent(Value v) {
        return (PointsToSet) v.getPointsTo();
    }

    public void add(Value v, AllocationSite site) { addSite(v, site); }

    public boolean addSite(Value v, AllocationSite site) {
        return merge(v, site.singleton());
    }

    public boolean merge(Value v, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) return false;
        while (true) {
            Object o = v.getPointsTo();
            PointsToSet cur = o == null ? PointsToSet.EMPTY : (PointsToSet) o;
            PointsToSet next = PointsToSet.union(cur, pts);
            if (next == cur) return false;
            if (v.compareAndSetPointsTo(o, next)) return true;
        }
    }

    public int fieldId(String field) {
        Integer id = fieldIds.get(field);
        if (id != null) return id;
        return fieldIds.computeIfAbsent(field, k -> nextFieldId.getAndIncrement());
    }

    public int findFieldId(String field) {
        Integer id = fieldIds.get(field);
        return id == null ? -1 : id;
    }

    public FieldCell findFieldCell(int siteId, int fieldId) {
        AtomicReferenceArray<SiteFields> chunk = siteCells.get(siteId >>> CHUNK_BITS);
        if (chunk == null) return null;
        SiteFields fields = chunk.get(siteId & CHUNK_MASK);
        return fields == null ? null : fields.find(fieldId);
    }

    /** Copy-on-write hash table: published tables are immutable and at most half full. */
    private static final class SiteFields {
        private static final VarHandle CELLS = MethodHandles.arrayElementVarHandle(FieldCell[].class);

        private record Sorted(FieldCell[] table, int size, FieldCell[] cells) {}

        private volatile FieldCell[] table = new FieldCell[2];
        private volatile int size;
        private volatile Sorted sorted;

        private static FieldCell at(FieldCell[] cells, int i) {
            return (FieldCell) CELLS.getAcquire(cells, i);
        }

        private static int slot(FieldCell[] cells, int id) {
            int mask = cells.length - 1;
            int i = (id ^ (id >>> 16)) & mask;
            while (true) {
                FieldCell cell = at(cells, i);
                if (cell == null || cell.fieldId() == id) return i;
                i = (i + 1) & mask;
            }
        }

        FieldCell find(int id) {
            FieldCell[] cells = table;
            FieldCell cell = at(cells, slot(cells, id));
            return cell != null && cell.fieldId() == id ? cell : null;
        }

        FieldCell insert(int id, String field) {
            FieldCell existing = find(id);
            if (existing != null) return existing;
            synchronized (this) {
                FieldCell[] cells = table;
                int i = slot(cells, id);
                existing = at(cells, i);
                if (existing != null) return existing;
                FieldCell created = new FieldCell(id, field);
                int count = size;
                if ((count + 1) * 2 > cells.length) {
                    FieldCell[] next = new FieldCell[cells.length * 2];
                    for (FieldCell cell : cells) {
                        if (cell != null) next[slot(next, cell.fieldId())] = cell;
                    }
                    next[slot(next, id)] = created;
                    table = next;
                } else {
                    CELLS.setRelease(cells, i, created);
                }
                size = count + 1;
                return created;
            }
        }

        FieldCell[] snapshot() {
            int count = size;
            FieldCell[] cells = table;
            Sorted cached = sorted;
            if (cached != null && cached.table() == cells && cached.size() == count) return cached.cells();
            int n = 0;
            for (int i = 0; i < cells.length; i++) {
                if (at(cells, i) != null) n++;
            }
            FieldCell[] result = new FieldCell[n];
            int k = 0;
            for (int i = 0; i < cells.length && k < n; i++) {
                FieldCell cell = at(cells, i);
                if (cell != null) result[k++] = cell;
            }
            Arrays.sort(result, Comparator.comparingInt(FieldCell::fieldId));
            sorted = new Sorted(cells, count, result);
            return result;
        }
    }

    public FieldCell fieldCell(int siteId, int fieldId, String field) {
        int chunkIndex = siteId >>> CHUNK_BITS;
        AtomicReferenceArray<SiteFields> chunk = siteCells.get(chunkIndex);
        if (chunk == null) {
            AtomicReferenceArray<SiteFields> fresh = new AtomicReferenceArray<>(CHUNK_SIZE);
            chunk = siteCells.compareAndExchange(chunkIndex, null, fresh);
            if (chunk == null) chunk = fresh;
        }
        int slot = siteId & CHUNK_MASK;
        SiteFields fields = chunk.get(slot);
        if (fields == null) {
            SiteFields fresh = new SiteFields();
            fields = chunk.compareAndExchange(slot, null, fresh);
            if (fields == null) fields = fresh;
        }
        FieldCell cell = fields.find(fieldId);
        return cell != null ? cell : fields.insert(fieldId, field);
    }

    public FieldCell fieldCell(AllocationSite site, String field) {
        return fieldCell(site.getId(), fieldId(field), field);
    }

    public PointsToSet getFieldPointsTo(AllocationSite site, String field) {
        int fid = findFieldId(field);
        if (fid < 0) return PointsToSet.EMPTY;
        FieldCell c = findFieldCell(site.getId(), fid);
        return c == null ? PointsToSet.EMPTY : c.get();
    }

    public boolean mergeFieldPointsTo(AllocationSite site, String field, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) return false;
        return fieldCell(site, field).merge(pts);
    }

    public boolean fieldContainsUnknown(AllocationSite site, String field) {
        return getFieldPointsTo(site, field).containsUnknown();
    }

    /** The site's field cells in a cached snapshot sorted by fieldId; callers must not modify the array. */
    public FieldCell[] fieldCells(int siteId) {
        AtomicReferenceArray<SiteFields> chunk = siteCells.get(siteId >>> CHUNK_BITS);
        if (chunk == null) return NO_CELLS;
        SiteFields fields = chunk.get(siteId & CHUNK_MASK);
        return fields == null ? NO_CELLS : fields.snapshot();
    }

    public Map<String, PointsToSet> getFieldPointsToMap(AllocationSite site) {
        FieldCell[] cells = fieldCells(site.getId());
        Map<String, PointsToSet> out = null;
        for (FieldCell c : cells) {
            PointsToSet pts = c.get();
            if (pts.isEmpty()) continue;
            if (out == null) out = new LinkedHashMap<>(Math.max(4, cells.length * 2));
            out.put(c.field(), pts);
        }
        return out == null ? null : Collections.unmodifiableMap(out);
    }

    public PointsToSet getFieldPointsToForSites(PointsToSet sites, String field) {
        if (sites == null || sites.isEmpty()) return PointsToSet.EMPTY;
        int fid = findFieldId(field);
        if (fid < 0) return PointsToSet.EMPTY;
        PointsToSet first = null;
        SiteSetBuilder builder = null;
        try {
            for (long cur = sites.cursor(); sites.has(cur); cur = sites.next(cur)) {
                int id = sites.id(cur);
                FieldCell c = findFieldCell(id, fid);
                if (c == null) continue;
                PointsToSet pts = c.get();
                if (pts.isEmpty() || pts == first) continue;
                if (first == null) {
                    first = pts;
                } else {
                    if (builder == null) {
                        builder = SiteSetBuilder.acquire();
                        builder.addAll(first);
                    }
                    builder.addAll(pts);
                }
            }
            if (builder != null) return builder.buildOr(first);
            return first == null ? PointsToSet.EMPTY : first;
        } finally {
            if (builder != null) builder.release();
        }
    }

    public PointsToSet getArrayElementPointsTo(AllocationSite site) {
        return getFieldPointsTo(site, ARRAY_ELEMENT_FIELD);
    }

    public boolean mergeArrayElementPointsTo(AllocationSite site, PointsToSet pts) {
        return mergeFieldPointsTo(site, ARRAY_ELEMENT_FIELD, pts);
    }

    public FieldCell staticCell(String field) {
        FieldCell c = staticCells.get(field);
        if (c != null) return c;
        return staticCells.computeIfAbsent(field, k -> new FieldCell(-1, k));
    }

    public PointsToSet getStaticFieldPointsTo(String field) {
        FieldCell c = staticCells.get(field);
        return c == null ? PointsToSet.EMPTY : c.get();
    }

    public boolean mergeStaticFieldPointsTo(String field, PointsToSet pts) {
        if (pts == null || pts.isEmpty()) return false;
        return staticCell(field).merge(pts);
    }

    public boolean staticFieldContainsUnknown(String field) {
        return getStaticFieldPointsTo(field).containsUnknown();
    }

    public Map<String, PointsToSet> getStaticFieldPointsToMap() {
        Map<String, PointsToSet> out = new TreeMap<>();
        for (Map.Entry<String, FieldCell> e : staticCells.entrySet()) {
            PointsToSet pts = e.getValue().get();
            if (!pts.isEmpty()) out.put(e.getKey(), pts);
        }
        return Collections.unmodifiableMap(out);
    }

    public boolean mayAlias(Value a, Value b) {
        return get(a).intersects(get(b));
    }

    public void releaseSolverState() {
        for (int ci = 0, n = siteCells.length(); ci < n; ci++) {
            AtomicReferenceArray<SiteFields> chunk = siteCells.get(ci);
            if (chunk == null) continue;
            for (int s = 0; s < CHUNK_SIZE; s++) {
                SiteFields fields = chunk.get(s);
                if (fields == null) continue;
                for (FieldCell c : fields.snapshot()) c.clearReaders();
            }
        }
        for (FieldCell c : staticCells.values()) c.clearReaders();
    }
}

package io.github.kubyk01.domain.analyzer.aliasanalysis;

import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Type;
import lombok.Getter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

@Getter
public final class AllocationSite {

    private record Key(String methodName, int instructionIndex, String typeName, Type type) {}

    private static final int CHUNK_BITS = 12;
    private static final int CHUNK_SIZE = 1 << CHUNK_BITS;
    private static final int CHUNK_MASK = CHUNK_SIZE - 1;
    private static final ConcurrentHashMap<Key, AllocationSite> INTERNED = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final AtomicReferenceArray<AllocationSite[]> CHUNKS =
        new AtomicReferenceArray<>(1 << (31 - CHUNK_BITS));

    public static final AllocationSite UNKNOWN =
        of("<unknown>", -1, "<unknown>", Type.UNKNOWN);

    private final String methodName;
    private final int instructionIndex;
    private final String typeName;
    private final Type type;
    private final int cachedHash;
    private final int id;
    @Getter(lombok.AccessLevel.NONE)
    private volatile PointsToSet singleton;

    private AllocationSite(String methodName, int instructionIndex,
                           String typeName, Type type, int cachedHash, int id) {
        this.methodName = methodName;
        this.instructionIndex = instructionIndex;
        this.typeName = typeName;
        this.type = type;
        this.cachedHash = cachedHash;
        this.id = id;
    }

    public static AllocationSite of(String methodName, int idx,
                                    String typeName, Type type) {
        Key key = new Key(methodName, idx, typeName, type);
        AllocationSite existing = INTERNED.get(key);
        if (existing != null) return existing;
        return INTERNED.computeIfAbsent(key, AllocationSite::register);
    }

    private static AllocationSite register(Key k) {
        int id = NEXT_ID.getAndIncrement();
        AllocationSite site = new AllocationSite(k.methodName(), k.instructionIndex(), k.typeName(), k.type(),
            computeHash(k.methodName(), k.instructionIndex(), k.typeName(), k.type()), id);
        int chunkIndex = id >>> CHUNK_BITS;
        AllocationSite[] chunk = CHUNKS.get(chunkIndex);
        if (chunk == null) {
            AllocationSite[] fresh = new AllocationSite[CHUNK_SIZE];
            chunk = CHUNKS.compareAndExchange(chunkIndex, null, fresh);
            if (chunk == null) chunk = fresh;
        }
        chunk[id & CHUNK_MASK] = site;
        return site;
    }

    public static AllocationSite byId(int id) {
        return CHUNKS.get(id >>> CHUNK_BITS)[id & CHUNK_MASK];
    }

    public static int count() {
        return NEXT_ID.get();
    }

    public PointsToSet singleton() {
        PointsToSet s = singleton;
        if (s == null) {
            s = PointsToSet.ofSortedUnique(new int[]{id});
            singleton = s;
        }
        return s;
    }

    public static AllocationSite fromInstruction(Instruction inst, String methodName, int idx) {
        String typeName = "unknown";
        Type type = Type.UNKNOWN;
        Opcode op = inst.getOpcode();
        if (op == Opcode.NEW || op == Opcode.NEW_ARRAY || op == Opcode.MULTI_NEW_ARRAY) {
            if (!inst.getOperands().isEmpty()
                && inst.getOperands().getFirst() instanceof Constant c
                && c.getType().isReference()) {
                typeName = c.getValue().toString();
                type = Type.reference(typeName);
            }
            if (op == Opcode.NEW_ARRAY) {
                typeName = "array[" + typeName + "]";
                type = Type.array(type);
            } else if (op == Opcode.MULTI_NEW_ARRAY) {
                type = Type.fromDescriptor(typeName);
                typeName = type.toString();
            }
        }
        return of(methodName, idx, typeName, type);
    }

    private static int computeHash(String m, int idx, String tn, Type t) {
        int h = m.hashCode();
        h = 31 * h + idx;
        h = 31 * h + tn.hashCode();
        h = 31 * h + t.hashCode();
        return h;
    }

    @Override public int hashCode() { return cachedHash; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AllocationSite other)) return false;
        return instructionIndex == other.instructionIndex
            && methodName.equals(other.methodName)
            && typeName.equals(other.typeName)
            && type.equals(other.type);
    }

    @Override
    public String toString() {
        return "alloc@" + methodName + ":" + instructionIndex + "(" + typeName + ")";
    }
}

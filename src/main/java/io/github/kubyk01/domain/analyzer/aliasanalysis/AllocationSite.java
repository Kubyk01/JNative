package io.github.kubyk01.domain.analyzer.aliasanalysis;

import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Type;
import lombok.Getter;

@Getter
public final class AllocationSite {
    public static final AllocationSite UNKNOWN =
        new AllocationSite("<unknown>", -1, "<unknown>", Type.UNKNOWN, 0);

    private final String methodName;
    private final int instructionIndex;
    private final String typeName;
    private final Type type;
    private final int cachedHash;

    private AllocationSite(String methodName, int instructionIndex,
                           String typeName, Type type, int cachedHash) {
        this.methodName = methodName;
        this.instructionIndex = instructionIndex;
        this.typeName = typeName;
        this.type = type;
        this.cachedHash = cachedHash;
    }

    public static AllocationSite of(String methodName, int idx,
                                    String typeName, Type type) {
        return new AllocationSite(methodName, idx, typeName, type, computeHash(methodName, idx, typeName, type));
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

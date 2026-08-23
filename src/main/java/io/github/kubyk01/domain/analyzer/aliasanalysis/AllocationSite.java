package io.github.kubyk01.domain.analyzer.aliasanalysis;

import io.github.kubyk01.domain.analyzer.ir.Constant;
import io.github.kubyk01.domain.analyzer.ir.Instruction;
import io.github.kubyk01.domain.analyzer.ir.Opcode;
import io.github.kubyk01.domain.analyzer.ir.Type;
import lombok.Value;

@Value
public class AllocationSite {
    public static final AllocationSite UNKNOWN = new AllocationSite("<unknown>", -1, "<unknown>", Type.UNKNOWN);

    String methodName;
    int instructionIndex;
    String typeName;      // original type name (for debugging)
    Type type;            // exact type

    public static AllocationSite fromInstruction(Instruction inst, String methodName, int idx) {
        String typeName = "unknown";
        Type type = Type.UNKNOWN;
        Opcode op = inst.getOpcode();
        if (op == Opcode.NEW || op == Opcode.NEW_ARRAY || op == Opcode.MULTI_NEW_ARRAY) {
            if (!inst.getOperands().isEmpty()) {
                Object val = inst.getOperands().getFirst();
                if (val instanceof Constant c && c.getType().isReference()) {
                    typeName = c.getValue().toString();
                    type = Type.reference(typeName);
                }
            }
            if (op == Opcode.NEW_ARRAY) {
                typeName = "array[" + typeName + "]";
                type = Type.array(type);
            } else if (op == Opcode.MULTI_NEW_ARRAY) {
                typeName = "multiarray[" + typeName + "]";
                type = Type.array(type);
            }
        }
        return new AllocationSite(methodName, idx, typeName, type);
    }

    @Override
    public String toString() {
        return "alloc@" + methodName + ":" + instructionIndex + "(" + typeName + ")";
    }
}

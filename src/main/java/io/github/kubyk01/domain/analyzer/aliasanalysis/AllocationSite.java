package io.github.kubyk01.domain.analyzer.aliasanalysis;

import io.github.kubyk01.domain.analyzer.ir.Constant;
import io.github.kubyk01.domain.analyzer.ir.Instruction;
import io.github.kubyk01.domain.analyzer.ir.Opcode;
import io.github.kubyk01.domain.analyzer.ir.Type;
import lombok.Value;

@Value
public class AllocationSite {
    public static final AllocationSite UNKNOWN = new AllocationSite("<unknown>", -1, "<unknown>");

    String methodName;
    int instructionIndex;
    String type;

    public static AllocationSite fromInstruction(Instruction inst, String methodName, int idx) {
        String type = "unknown";
        Opcode op = inst.getOpcode();
        if (op == Opcode.NEW || op == Opcode.NEW_ARRAY || op == Opcode.MULTI_NEW_ARRAY) {
            if (!inst.getOperands().isEmpty()) {
                Object val = inst.getOperands().getFirst();
                if (val instanceof Constant c && c.getType() == Type.REFERENCE) {
                    type = c.getValue().toString();
                }
            }
            if (op == Opcode.NEW_ARRAY) {
                type = "array[" + type + "]";
            } else if (op == Opcode.MULTI_NEW_ARRAY) {
                type = "multiarray[" + type + "]";
            }
        }
        return new AllocationSite(methodName, idx, type);
    }

    @Override
    public String toString() {
        return "alloc@" + methodName + ":" + instructionIndex + "(" + type + ")";
    }
}

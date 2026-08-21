package io.github.kubyk01.domain.analyzer.ir;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class Instruction {
    @Setter
    private Opcode opcode;
    private final List<Value> operands = new ArrayList<>();
    @Setter
    private Temporary result;
    @Setter
    private BasicBlock parent;
    @Setter
    private int localIndex = -1;

    public Instruction(Opcode opcode) {
        this.opcode = opcode;
    }

    public Instruction addOperand(Value v) {
        operands.add(v);
        return this;
    }

    public boolean canThrow() {
        return switch (opcode) {
            case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL, GET_FIELD, PUT_FIELD, GET_STATIC,
                 PUT_STATIC, NEW, NEW_ARRAY, MULTI_NEW_ARRAY, CHECKCAST, INSTANCEOF, ARRAYLENGTH, ALOAD, ASTORE, DIV,
                 REM, MONITOR_ENTER, MONITOR_EXIT -> true;
            default -> false;
        };
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (result != null) sb.append(result).append(" = ");
        sb.append(opcode);
        if (opcode == Opcode.LOAD || opcode == Opcode.STORE) {
            sb.append(" local[").append(localIndex).append("]");
        }
        if (!operands.isEmpty()) {
            sb.append(" ");
            for (int i = 0; i < operands.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(operands.get(i));
            }
        }
        return sb.toString();
    }
}

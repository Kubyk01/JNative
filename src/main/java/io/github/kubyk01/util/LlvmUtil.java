package io.github.kubyk01.util;

import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;

import java.util.ArrayList;
import java.util.List;

public class LlvmUtil {
    public static Type inferLocalType(Function func, int idx) {
        for (Parameter p : func.getParameters()) {
            if (p.getIndex() == idx) return p.getType();
        }
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() == Opcode.STORE && inst.getLocalIndex() == idx) {
                    if (!inst.getOperands().isEmpty()) {
                        return inst.getOperands().getFirst().getType();
                    }
                }
            }
        }
        return Type.UNKNOWN;
    }

    public static int getElementSizeOfType(Type type) {
        if (type.isPrimitive()) {
            if (type == Type.BOOLEAN || type == Type.BYTE) return 1;
            if (type == Type.SHORT || type == Type.CHAR) return 2;
            if (type == Type.INT || type == Type.FLOAT) return 4;
            if (type == Type.LONG || type == Type.DOUBLE) return 8;
        }
        if (type.isReference() || type.isArray()) {
            return 8;
        }
        return 8;
    }

    /**
     * The field name depends on the opcode: for GET_FIELD/PUT_FIELD the field constant is in operand 1,
     * for GET_STATIC/PUT_STATIC – in operand 0.
     * The full name (including the class) is returned, which prevents name collisions
     * between static fields of different classes.
     */
    public static String extractFieldName(Instruction inst) {
        int fieldIdx = (inst.getOpcode() == Opcode.GET_STATIC || inst.getOpcode() == Opcode.PUT_STATIC) ? 0 : 1;
        if (inst.getOperands().size() > fieldIdx) {
            Value v = inst.getOperands().get(fieldIdx);
            if (v instanceof Constant c && c.getType().isReference()) {
                return c.getValue().toString(); // full name, e.g. "java/lang/System.out"
            }
        }
        return "unknown";
    }

    public static String extractCalleeName(Instruction inst) {
        if (!inst.getOperands().isEmpty()) {
            Value v = inst.getOperands().getFirst();
            if (v instanceof Constant c && c.getType().isReference()) {
                return c.getValue().toString();
            }
        }
        return null;
    }

    public static List<Value> getCallArguments(Instruction inst) {
        List<Value> args = new ArrayList<>();
        boolean skipFirst = true;
        for (Value op : inst.getOperands()) {
            if (skipFirst) { skipFirst = false; continue; }
            args.add(op);
        }
        return args;
    }

    public static String extractTypeName(Instruction inst) {
        for (Value v : inst.getOperands()) {
            if (v instanceof Constant c && c.getType().isReference()) {
                return c.getValue().toString();
            }
        }
        return "java/lang/Object";
    }

    public static String extractClassName(Value v) {
        if (v.getType().isReference()) {
            return v.getType().getClassName();
        } else if (v.getType().isArray()) {
            Type elem = v.getType().getElementType();
            if (elem.isReference()) return elem.getClassName();
            else return "java/lang/Object";
        }
        return "java/lang/Object";
    }

}

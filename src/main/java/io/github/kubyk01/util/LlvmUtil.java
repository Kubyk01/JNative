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

        // Prefer a STORE result: after IrBuilder.createStore was changed
        // to take an explicit slot type, the result carries the type that
        // the bytecode opcode mandated for the slot (ISTORE -> INT, &c),
        // not the type of the value being stored. Falling back to the
        // operand's type keeps this correct on IR produced by an older
        // build, where the result was stamped with the stored value's own
        // type.
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() != Opcode.STORE) continue;
                if (inst.getLocalIndex() != idx) continue;

                if (inst.getResult() != null
                    && !inst.getResult().getType().isUnknown()) {
                    return inst.getResult().getType();
                }
                if (!inst.getOperands().isEmpty()) {
                    Type operandType = inst.getOperands().getFirst().getType();
                    if (!operandType.isUnknown()) return operandType;
                }
            }
        }

        // No STORE at all: the slot can only be set outside the function
        // body (JSR/RET) or is a never-reassigned parameter that already
        // matched above. Fall back to whatever the LOADs declare.
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() != Opcode.LOAD) continue;
                if (inst.getLocalIndex() != idx) continue;
                if (inst.getResult() != null
                    && !inst.getResult().getType().isUnknown()) {
                    return inst.getResult().getType();
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
     * The field name depends on the opcode: for GET_FIELD/PUT_FIELD the field
     * constant is in operand 1, for GET_STATIC/PUT_STATIC - in operand 0.
     * The full name (including the class) is returned, which prevents name
     * collisions between static fields of different classes.
     */
    public static String extractFieldName(Instruction inst) {
        int fieldIdx = (inst.getOpcode() == Opcode.GET_STATIC
            || inst.getOpcode() == Opcode.PUT_STATIC) ? 0 : 1;
        if (inst.getOperands().size() > fieldIdx) {
            Value v = inst.getOperands().get(fieldIdx);
            if (v instanceof Constant c && c.getType().isReference()) {
                return c.getValue().toString();
            }
        }
        return "unknown";
    }

    public static String extractCalleeName(Instruction inst) {
        int idx = switch (inst.getOpcode()) {
            case VIRTUAL_CALL, INTERFACE_CALL, SPECIAL_CALL -> 1;
            default -> 0;
        };
        if (inst.getOperands().size() > idx) {
            Value v = inst.getOperands().get(idx);
            if (v instanceof Constant c && c.getType().isReference()) {
                return c.getValue().toString();
            }
        }
        return null;
    }

    public static List<Value> getCallArguments(Instruction inst) {
        Opcode op = inst.getOpcode();
        List<Value> args = new ArrayList<>();
        switch (op) {
            case VIRTUAL_CALL, INTERFACE_CALL, SPECIAL_CALL: {
                // operands = [receiver, calleeConst, arg0, arg1, ...]
                // receiver —  0; calleeConst skip.
                if (!inst.getOperands().isEmpty()) {
                    args.add(inst.getOperands().getFirst());
                }
                for (int i = 2; i < inst.getOperands().size(); i++) {
                    args.add(inst.getOperands().get(i));
                }
                break;
            }
            default: {
                // CALL / STATIC_CALL: operands = [calleeConst, arg0, arg1, ...]
                for (int i = 1; i < inst.getOperands().size(); i++) {
                    args.add(inst.getOperands().get(i));
                }
                break;
            }
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

    public static boolean isAllocation(Opcode op) {
        return op == Opcode.NEW || op == Opcode.NEW_ARRAY || op == Opcode.MULTI_NEW_ARRAY;
    }

    public static String[] extractFieldOwnerAndName(Instruction inst) {
        String full = extractFieldName(inst);
        int dot = full.lastIndexOf('.');
        if (dot > 0) return new String[]{full.substring(0, dot), full.substring(dot + 1)};
        return new String[]{"", full};
    }

    /**
     * Returns {@code true} for class names that belong to the JDK, to the
     * runtime's own third-party dependencies, or to any other package that is
     * not part of the user's program.
     *
     * <p>Callers use this predicate to distinguish "user code" (the classes
     * the user actually wrote, which are of primary interest for the
     * analysis reports and the destructor pass) from the JDK and library
     * classes that the reachability walk also drags in. It is intentionally
     * a simple prefix test on a fixed list of package roots: the classifier
     * is about provenance, not about VM semantics, and a heuristic is enough
     * for every caller that consults it.</p>
     */
    public static boolean isSystemClassName(String className) {
        String dot = className.replace('/', '.');
        return dot.startsWith("java.") ||
            dot.startsWith("javax.") ||
            dot.startsWith("sun.") ||
            dot.startsWith("jdk.") ||
            dot.startsWith("org.objectweb.asm.") ||
            dot.startsWith("picocli.") ||
            dot.startsWith("reactor.") ||
            dot.startsWith("org.slf4j.") ||
            dot.startsWith("org.reactivestreams.") ||
            dot.startsWith("io.micrometer.") ||
            dot.startsWith("org.junit.") ||
            dot.startsWith("com.fasterxml.");
    }
}
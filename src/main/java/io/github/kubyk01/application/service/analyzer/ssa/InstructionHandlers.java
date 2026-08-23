package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.ir.*;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

public class InstructionHandlers {
    private final IrBuilder builder;
    private final StackFrame frame;
    private final DependencyResolver resolver;

    public InstructionHandlers(IrBuilder builder, StackFrame frame,
                               DependencyResolver resolver) {
        this.builder = builder;
        this.frame = frame;
        this.resolver = resolver;
    }

    public void pushInt(int value) {
        frame.push(new Constant(Type.INT, value));
    }

    public void pushLong(long value) {
        frame.push(new Constant(Type.LONG, value));
    }

    public void pushFloat(float value) {
        frame.push(new Constant(Type.FLOAT, value));
    }

    public void pushDouble(double value) {
        frame.push(new Constant(Type.DOUBLE, value));
    }

    public void pushNull() {
        frame.push(new Constant(Type.NULL, null));
    }

    public void binaryOp(Opcode op) {
        Value right = frame.pop();
        Value left = frame.pop();
        Instruction inst = builder.addInstruction(op, left, right);
        frame.push(inst.getResult());
    }

    public void unaryNeg(Type type) {
        Value val = frame.pop();
        Constant zero = new Constant(type, type == Type.INT ? 0 :
                type == Type.LONG ? 0L :
                        type == Type.FLOAT ? 0.0f : 0.0);
        Instruction inst = builder.addInstruction(Opcode.SUB, zero, val);
        frame.push(inst.getResult());
    }

    public void shiftOp() {
        Value amount = frame.pop();
        Value value = frame.pop();
        Instruction inst = builder.addInstruction(Opcode.SHL, value, amount);
        frame.push(inst.getResult());
    }

    public void cmpOp() {
        Value right = frame.pop();
        Value left = frame.pop();
        Instruction cmp = builder.addInstruction(Opcode.SUB, left, right);
        frame.push(cmp.getResult());
    }

    public void convert() {
        Value val = frame.pop();
        Instruction conv = builder.addInstruction(Opcode.CAST, val);
        frame.push(conv.getResult());
    }

    public void returnValue() {
        Value val = frame.pop();
        builder.createReturn(val);
    }

    public void returnVoid() {
        builder.createReturn(null);
    }

    public void arrayLength() {
        Value arr = frame.pop();
        Instruction len = builder.addInstruction(Opcode.ARRAYLENGTH, arr);
        frame.push(len.getResult());
    }

    public void throwException() {
        Value ex = frame.pop();
        builder.createThrow(ex);
    }

    public void monitorEnter() {
        Value obj = frame.pop();
        builder.addInstruction(Opcode.MONITOR_ENTER, obj);
    }

    public void monitorExit() {
        Value obj = frame.pop();
        builder.addInstruction(Opcode.MONITOR_EXIT, obj);
    }

    public void newArray(int atype) {
        Value size = frame.pop();
        Type elemType = arrayTypeToIr(atype);
        Instruction inst = builder.addInstruction(Opcode.NEW_ARRAY, size,
                new Constant(Type.reference(elemType.toString()), elemType.toString()));
        frame.push(inst.getResult());
    }

    public void newObject(String type) {
        Instruction inst = builder.addInstruction(Opcode.NEW,
                new Constant(Type.reference(type), type));
        frame.push(inst.getResult());
    }

    public void anewArray(String type) {
        Value size = frame.pop();
        Instruction inst = builder.addInstruction(Opcode.NEW_ARRAY, size,
                new Constant(Type.reference(type), type));
        frame.push(inst.getResult());
    }

    public void checkCast(String type) {
        Value val = frame.pop();
        // The constant with the target type is needed by the emitter for the @__jnative_instanceof check
        Instruction inst = builder.addInstruction(Opcode.CHECKCAST, val,
                new Constant(Type.reference(type), type));
        frame.push(inst.getResult());
    }

    public void instanceOf(String type) {
        Value val = frame.pop();
        // The constant with the checked type is needed by the emitter to call @__jnative_instanceof
        Instruction inst = builder.addInstruction(Opcode.INSTANCEOF, val,
                new Constant(Type.reference(type), type));
        frame.push(inst.getResult());
    }

    public void multiNewArray(String desc, int dims) {
        List<Value> sizes = new ArrayList<>();
        for (int i = 0; i < dims; i++) {
            sizes.add(frame.pop());
        }
        // Operands: [0] – descriptor constant, then the sizes from the outer
        // dimension to the inner one (sizes are popped off the stack in reverse order)
        Instruction inst = builder.addInstruction(Opcode.MULTI_NEW_ARRAY,
                new Constant(Type.reference(desc), desc));
        for (int i = sizes.size() - 1; i >= 0; i--) {
            inst.addOperand(sizes.get(i));
        }
        frame.push(inst.getResult());
    }

    public void getField(String owner, String name) {
        Value obj = frame.pop();
        Type fieldType;
        FieldNode field = resolver.getField(owner, name);
        if (field != null) {
            fieldType = field.getType();
        } else {
            fieldType = Type.reference("java/lang/Object");
        }
        Instruction inst = new Instruction(Opcode.GET_FIELD);
        inst.addOperand(obj);
        inst.addOperand(new Constant(Type.reference(owner + "." + name), owner + "." + name));
        Temporary tmp = builder.newTemporary(fieldType);
        inst.setResult(tmp);
        tmp.setDefiningInstruction(inst);
        builder.currentBlock().addInstruction(inst);
        frame.push(tmp);
    }

    public void putField(String owner, String name) {
        Value val = frame.pop();
        Value obj = frame.pop();
        builder.addInstruction(Opcode.PUT_FIELD, obj,
                new Constant(Type.reference(owner + "." + name), owner + "." + name), val);
    }

    public void getStatic(String owner, String name) {
        Type fieldType;
        FieldNode field = resolver.getField(owner, name);
        if (field != null) {
            fieldType = field.getType();
        } else {
            fieldType = Type.reference("java/lang/Object");
        }
        Instruction inst = new Instruction(Opcode.GET_STATIC);
        inst.addOperand(new Constant(Type.reference(owner + "." + name), owner + "." + name));
        Temporary tmp = builder.newTemporary(fieldType);
        inst.setResult(tmp);
        tmp.setDefiningInstruction(inst);
        builder.currentBlock().addInstruction(inst);
        frame.push(tmp);
    }

    public void putStatic(String owner, String name) {
        Value val = frame.pop();
        builder.addInstruction(Opcode.PUT_STATIC,
                new Constant(Type.reference(owner + "." + name), owner + "." + name), val);
    }

    public void callMethod(int opcode, String owner, String name, String desc) {
        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);
        Type retType = TypeResolver.descToReturnType(desc);
        int paramCount = paramTypes.size();

        List<Value> args = frame.popArgs(paramCount);

        Opcode irOpcode;
        Value receiver = null;
        irOpcode = switch (opcode) {
            case Opcodes.INVOKEVIRTUAL -> {
                receiver = frame.pop();
                yield Opcode.VIRTUAL_CALL;
            }
            case Opcodes.INVOKEINTERFACE -> {
                receiver = frame.pop();
                yield Opcode.INTERFACE_CALL;
            }
            case Opcodes.INVOKESTATIC -> Opcode.STATIC_CALL;
            case Opcodes.INVOKESPECIAL -> {
                receiver = frame.pop();
                yield Opcode.SPECIAL_CALL;
            }
            default -> Opcode.CALL;
        };

        Instruction callInst = new Instruction(irOpcode);
        if (receiver != null) callInst.addOperand(receiver);
        callInst.addOperand(new Constant(Type.reference(owner + "." + name + desc), owner + "." + name + desc));
        for (Value arg : args) {
            callInst.addOperand(arg);
        }

        if (!retType.isVoid()) {
            Temporary tmp = builder.newTemporary(retType);
            callInst.setResult(tmp);
            tmp.setDefiningInstruction(callInst);
            builder.currentBlock().addInstruction(callInst);
            frame.push(tmp);
        } else {
            builder.currentBlock().addInstruction(callInst);
        }
    }

    private Type arrayTypeToIr(int atype) {
        return switch (atype) {
            case Opcodes.T_BOOLEAN -> Type.BOOLEAN;
            case Opcodes.T_BYTE -> Type.BYTE;
            case Opcodes.T_CHAR -> Type.CHAR;
            case Opcodes.T_SHORT -> Type.SHORT;
            case Opcodes.T_INT -> Type.INT;
            case Opcodes.T_LONG -> Type.LONG;
            case Opcodes.T_FLOAT -> Type.FLOAT;
            case Opcodes.T_DOUBLE -> Type.DOUBLE;
            default -> Type.UNKNOWN;
        };
    }
}

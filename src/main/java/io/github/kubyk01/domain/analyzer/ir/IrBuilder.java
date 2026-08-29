package io.github.kubyk01.domain.analyzer.ir;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

public class IrBuilder {
    @Getter
    private final Module module = new Module();
    private Function currentFunction;
    @Setter
    private BasicBlock currentBlock;
    private final List<Temporary> temporaries = new ArrayList<>();

    public BasicBlock currentBlock() { return currentBlock; }

    public Function createFunction(String name, Type returnType, List<Type> paramTypes) {
        Function func = new Function(name, returnType);
        for (int i = 0; i < paramTypes.size(); i++) {
            Parameter p = new Parameter(paramTypes.get(i), i);
            func.addParameter(p);
        }
        module.addFunction(func);
        currentFunction = func;
        return func;
    }

    public BasicBlock createBlock(String label) {
        BasicBlock block = new BasicBlock(label);
        if (currentFunction != null) {
            currentFunction.addBlock(block);
            if (currentFunction.getEntryBlock() == null) {
                currentFunction.setEntryBlock(block);
            }
        }
        currentBlock = block;
        return block;
    }

    public Temporary newTemporary(Type type) {
        Temporary tmp = new Temporary(type);
        temporaries.add(tmp);
        return tmp;
    }

    public Instruction addInstruction(Opcode opcode, Value... operands) {
        Instruction inst = new Instruction(opcode);
        for (Value v : operands) {
            inst.addOperand(v);
        }
        if (returnsValue(opcode)) {
            Type resultType = inferResultType(opcode, operands);
            Temporary tmp = newTemporary(resultType);
            inst.setResult(tmp);
            tmp.setDefiningInstruction(inst);
        }
        if (currentBlock != null) {
            currentBlock.addInstruction(inst);
        }
        return inst;
    }

    public Instruction createLoad(int localIndex, Type type) {
        Instruction load = new Instruction(Opcode.LOAD);
        load.setLocalIndex(localIndex);
        Temporary tmp = newTemporary(type);
        load.setResult(tmp);
        tmp.setDefiningInstruction(load);
        if (currentBlock != null) currentBlock.addInstruction(load);
        return load;
    }

    public Instruction createStore(Value value, int localIndex) {
        Instruction store = new Instruction(Opcode.STORE);
        store.addOperand(value);
        store.setLocalIndex(localIndex);
        Temporary tmp = newTemporary(value.getType());
        store.setResult(tmp);
        tmp.setDefiningInstruction(store);
        if (currentBlock != null) currentBlock.addInstruction(store);
        return store;
    }

    private boolean returnsValue(Opcode op) {
        return switch (op) {
            case ADD, SUB, MUL, DIV, REM,
                 EQ, NE, LT, LE, GT, GE,
                 AND, OR, XOR, SHL, SHR, USHR,
                 CAST,
                 LOAD, GET_FIELD, GET_STATIC,
                 CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL,
                 NEW, NEW_ARRAY, MULTI_NEW_ARRAY,
                 INSTANCEOF, CHECKCAST,
                 ARRAYLENGTH,
                 ALOAD
                 -> true;
            default -> false;
        };
    }

    private Type inferResultType(Opcode op, Value... operands) {
        return switch (op) {
            case INSTANCEOF -> Type.BOOLEAN;
            case CHECKCAST -> {
                if (operands.length > 1 && operands[1] instanceof Constant c) {
                    if (c.getType().isReference()) {
                        yield Type.reference(c.getValue().toString());
                    }
                }
                yield operands.length > 0 ? operands[0].getType() : Type.UNKNOWN;
            }
            case ARRAYLENGTH -> Type.INT;
            case ALOAD -> {
                if (operands.length > 0 && operands[0].getType().isArray()) {
                    yield operands[0].getType().getElementType();
                }
                yield Type.UNKNOWN;
            }
            case NEW -> {
                if (operands.length > 0 && operands[0] instanceof Constant) {
                    String className = ((Constant) operands[0]).getValue().toString();
                    yield Type.reference(className);
                }
                yield Type.UNKNOWN;
            }
            case NEW_ARRAY -> {
                if (operands.length >= 2 && operands[1] instanceof Constant) {
                    String elemTypeName = ((Constant) operands[1]).getValue().toString();
                    Type elemType = Type.fromDescriptor(elemTypeName); // handles "int", "java/lang/String", etc.
                    yield Type.array(elemType);
                }
                yield Type.UNKNOWN;
            }
            case MULTI_NEW_ARRAY -> {
                if (operands.length > 0 && operands[0] instanceof Constant) {
                    String desc = ((Constant) operands[0]).getValue().toString();
                    yield Type.array(desc);
                }
                yield Type.UNKNOWN;
            }
            default -> {
                if (operands.length > 0 && operands[0] != null) {
                    yield operands[0].getType();
                }
                yield Type.UNKNOWN;
            }
        };
    }

    public Terminator createBranch(BasicBlock target) {
        BranchTerminator term = new BranchTerminator(target);
        if (currentBlock != null) currentBlock.setTerminator(term);
        return term;
    }

    public Terminator createCondBranch(Value cond, BasicBlock trueTarget, BasicBlock falseTarget) {
        CondBranchTerminator term = new CondBranchTerminator(cond, trueTarget, falseTarget);
        if (currentBlock != null) currentBlock.setTerminator(term);
        return term;
    }

    public Terminator createLookupSwitch(Value key, int[] keys, BasicBlock[] targets, BasicBlock defaultTarget) {
        LookupSwitchTerminator term = new LookupSwitchTerminator(key, keys, targets, defaultTarget);
        if (currentBlock != null) currentBlock.setTerminator(term);
        return term;
    }

    public Terminator createTableSwitch(Value key, int min, int max, BasicBlock[] targets, BasicBlock defaultTarget) {
        TableSwitchTerminator term = new TableSwitchTerminator(key, min, max, targets, defaultTarget);
        if (currentBlock != null) currentBlock.setTerminator(term);
        return term;
    }

    public Terminator createReturn(Value value) {
        ReturnTerminator term = new ReturnTerminator(value);
        if (currentBlock != null) currentBlock.setTerminator(term);
        return term;
    }

    public Terminator createThrow(Value exception) {
        ThrowTerminator term = new ThrowTerminator(exception);
        if (currentBlock != null) currentBlock.setTerminator(term);
        return term;
    }
}

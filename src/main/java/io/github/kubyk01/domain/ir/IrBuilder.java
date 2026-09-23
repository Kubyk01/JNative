package io.github.kubyk01.domain.ir;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

public class IrBuilder {
    @Getter
    private Module module;
    private Function currentFunction;
    @Setter
    private BasicBlock currentBlock;
    private final List<Temporary> temporaries = new ArrayList<>();

    public BasicBlock currentBlock() { return currentBlock; }

    public IrBuilder() {
        this.module = new Module();
    }

    public IrBuilder(Module module) {
        this.module = module;
    }

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

    /**
     * Creates a function with the given parameters. Unlike
     * {@link #createFunction(String, Type, List)}, the parameter indices here are
     * set by the caller — this allows a JVM slot to be used as the index rather
     * than a positional ordinal. Required for correct SSA over methods where
     * long/double occupy two consecutive slots.
     */
    public Function createFunctionWithSlots(String name, Type returnType, List<Parameter> params) {
        Function func = new Function(name, returnType);
        for (Parameter p : params) {
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

    public Instruction addInstruction(Opcode opcode, Type resultType, Value... operands) {
        Instruction inst = new Instruction(opcode);
        for (Value v : operands) {
            inst.addOperand(v);
        }
        if (resultType != null && returnsValue(opcode)) {
            Temporary tmp = newTemporary(resultType);
            inst.setResult(tmp);
            tmp.setDefiningInstruction(inst);
        } else if (returnsValue(opcode)) {
            Type inferred = inferResultType(opcode, operands);
            Temporary tmp = newTemporary(inferred);
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

    /**
     * Creates a STORE into a JVM local slot, using an explicitly supplied
     * slot type.
     *
     * <p>The type of a JVM local slot is fixed by the bytecode opcode that
     * accesses it, not by the type of any single value that flows through
     * it. ISTORE always targets an int slot, LSTORE always targets a long
     * slot, and so on. There is no opcode that stores a byte, short, char,
     * or boolean into a slot, even though the value being stored may carry
     * one of those narrow types as a result of BALOAD/CALOAD/SALOAD, an
     * explicit narrowing conversion (I2B/I2C/I2S), or a boolean-producing
     * comparison.</p>
     *
     * <p>The slot type is therefore an intrinsic property of the access
     * and must be passed in explicitly rather than inferred from the
     * value. The single-argument overload below exists for callers that
     * are not modelling a JVM local slot and for which the value's type
     * really is the right answer.</p>
     *
     * @param value      the value to store into the slot
     * @param localIndex the JVM local slot index
     * @param localType  the slot's declared type (nullable: falls back to
     *                   {@code value.getType()})
     */
    public Instruction createStore(Value value, int localIndex, Type localType) {
        Instruction store = new Instruction(Opcode.STORE);
        store.addOperand(value);
        store.setLocalIndex(localIndex);
        Type slotType = (localType != null) ? localType : value.getType();
        Temporary tmp = newTemporary(slotType);
        store.setResult(tmp);
        tmp.setDefiningInstruction(store);
        if (currentBlock != null) currentBlock.addInstruction(store);
        return store;
    }

    /**
     * Creates a STORE using the type of {@code value} as the slot's
     * declared type. Prefer {@link #createStore(Value, int, Type)} when
     * the slot's declared type is known from the bytecode opcode.
     */
    public Instruction createStore(Value value, int localIndex) {
        return createStore(value, localIndex, value.getType());
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

    /**
     * Computes a common type for arithmetic operations.
     * Promotes integer types to int, long, float, or double as needed.
     */
    private Type computeCommonArithmeticType(Value... operands) {
        if (operands == null || operands.length == 0) {
            return Type.UNKNOWN;
        }
        // Determine the widest primitive type among operands
        boolean hasDouble = false;
        boolean hasFloat = false;
        boolean hasLong = false;
        boolean hasInt = false;
        // Also check if any operand is a pointer/reference (treat as i64 for arithmetic)
        boolean hasPointer = false;

        for (Value v : operands) {
            Type t = v.getType();
            if (t == Type.DOUBLE) hasDouble = true;
            else if (t == Type.FLOAT) hasFloat = true;
            else if (t == Type.LONG) hasLong = true;
            else if (t == Type.INT || t == Type.SHORT || t == Type.BYTE || t == Type.CHAR || t == Type.BOOLEAN) {
                hasInt = true;
            } else if (t.isReference() || t.isArray() || t.isNull() || t.isBlock()) {
                hasPointer = true;
            }
        }

        if (hasDouble) return Type.DOUBLE;
        if (hasFloat) return Type.FLOAT;
        if (hasLong) return Type.LONG;
        if (hasPointer) return Type.LONG;  // treat pointer as i64 for arithmetic (e.g., pointer arith)
        if (hasInt) return Type.INT;
        return Type.UNKNOWN;
    }

    private Type inferResultType(Opcode op, Value... operands) {
        return switch (op) {
            case EQ, NE, LT, LE, GT, GE -> Type.BOOLEAN;
            case INSTANCEOF -> Type.BOOLEAN;
            case CHECKCAST -> {
                // The descriptor constant's own type always stays
                // Type.reference(...) — that is what LlvmUtil.extractTypeName
                // looks for (isReference()), and what the CHECKCAST case in
                // LlvmFunctionEmitter looks for (typeName.startsWith("[")).
                // Only the RESULT type of the instruction changes.
                //
                // For a typeName starting with '[', the result must be
                // Type.array(...), not Type.reference(...). The difference is
                // observable:
                //
                //   - Type.isArray()     -> true for ARRAY, false for REFERENCE
                //   - Type.isReference() -> false for ARRAY, true for REFERENCE
                //   - LlvmTypeMapper.toLlvmType -> "i8*" in both cases
                //
                // In the VIRTUAL_CALL case of LlvmFunctionEmitter the
                // direct-dispatch branch is chosen when receiverIsArray == true.
                // If the checkcast result is typed as REFERENCE, receiverIsArray
                // is false and the call goes down the vtable path instead. For
                // an array obj[0] is a %ReflectionClass* (see
                // JAVA_ARR_KLASS_OFFSET), not a %JNativeVTable*. Loading
                // "%JNativeVTable*" out of obj[0] yields a pointer to the class
                // mirror, and a getelementptr on it yields methods of class
                // java.lang.Class rather than Object's. The result is dispatch
                // through a foreign table with a garbage funcPtr and an
                // infinite loop in foreign code.
                if (operands.length > 1 && operands[1] instanceof Constant c) {
                    if (c.getType().isReference()) {
                        String typeName = String.valueOf(c.getValue());
                        if (typeName.startsWith("[")) {
                            yield Type.fromDescriptor(typeName);
                        }
                        yield Type.reference(typeName);
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
                if (operands.length >= 2 && operands[1] instanceof Constant c) {
                    String elemTypeName = c.getValue().toString();
                    Type elemType = parseArrayElementName(elemTypeName);
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
            case ADD, SUB, MUL, DIV, REM, AND, OR, XOR, SHL, SHR, USHR ->
                // Compute the common numeric type
                computeCommonArithmeticType(operands);
            default -> {
                if (operands.length > 0 && operands[0] != null) {
                    yield operands[0].getType();
                }
                yield Type.UNKNOWN;
            }
        };
    }

    private Type parseArrayElementName(String name) {
        Type prim = switch (name) {
            case "boolean" -> Type.BOOLEAN;
            case "byte"    -> Type.BYTE;
            case "short"   -> Type.SHORT;
            case "char"    -> Type.CHAR;
            case "int"     -> Type.INT;
            case "long"    -> Type.LONG;
            case "float"   -> Type.FLOAT;
            case "double"  -> Type.DOUBLE;
            default -> null;
        };
        if (prim != null) return prim;
        if (name.startsWith("[")) return Type.array(name);
        if (name.length() == 1)   return Type.fromDescriptor(name);
        return Type.reference(name);
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
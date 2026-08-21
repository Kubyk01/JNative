package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.analyzer.ir.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.*;

@Slf4j
public class MethodTranslator extends MethodVisitor {
    private final MethodReference methodRef;
    private final boolean isStatic;
    private final IrBuilder builder;
    private final StackFrame frame;
    private final Map<Label, BasicBlock> labelToBlock = new HashMap<>();
    private final TryCatchHandler tryCatchHandler;
    private final InstructionHandlers handlers;

    @Getter
    private Function currentFunction;
    private BasicBlock currentBlock;

    public MethodTranslator(MethodReference methodRef, boolean isStatic, IrBuilder builder) {
        super(Opcodes.ASM9);
        this.methodRef = methodRef;
        this.isStatic = isStatic;
        this.builder = builder;
        this.frame = new StackFrame(builder);
        this.tryCatchHandler = new TryCatchHandler(labelToBlock);
        this.handlers = new InstructionHandlers(builder, frame, this);
    }

    @Override
    public void visitCode() {
        Type returnType = TypeResolver.descToReturnType(methodRef.getDescriptor());
        List<Type> paramTypes = TypeResolver.descToParamTypes(methodRef.getDescriptor());
        currentFunction = builder.createFunction(methodRef.toString(), returnType, paramTypes);

        int paramIndex = 0;
        if (!isStatic) {
            if (!currentFunction.getParameters().isEmpty()) {
                frame.setLocal(0, currentFunction.getParameters().getFirst());
                paramIndex = 1;
            }
        }
        for (int i = paramIndex; i < currentFunction.getParameters().size(); i++) {
            frame.setLocal(i, currentFunction.getParameters().get(i));
        }
        currentBlock = builder.createBlock("entry");
    }

    @Override
    public void visitLabel(Label label) {
        currentBlock = labelToBlock.computeIfAbsent(label,
                k -> builder.createBlock("L" + k.toString()));
        builder.setCurrentBlock(currentBlock);
    }

    @Override
    public void visitLineNumber(int line, Label start) {
    }

    @Override
    public void visitInsn(int opcode) {
        switch (opcode) {
            case Opcodes.ACONST_NULL -> handlers.pushNull();
            case Opcodes.ICONST_M1 -> handlers.pushInt(-1);
            case Opcodes.ICONST_0 -> handlers.pushInt(0);
            case Opcodes.ICONST_1 -> handlers.pushInt(1);
            case Opcodes.ICONST_2 -> handlers.pushInt(2);
            case Opcodes.ICONST_3 -> handlers.pushInt(3);
            case Opcodes.ICONST_4 -> handlers.pushInt(4);
            case Opcodes.ICONST_5 -> handlers.pushInt(5);
            case Opcodes.LCONST_0 -> handlers.pushLong(0L);
            case Opcodes.LCONST_1 -> handlers.pushLong(1L);
            case Opcodes.FCONST_0 -> handlers.pushFloat(0.0f);
            case Opcodes.FCONST_1 -> handlers.pushFloat(1.0f);
            case Opcodes.FCONST_2 -> handlers.pushFloat(2.0f);
            case Opcodes.DCONST_0 -> handlers.pushDouble(0.0);
            case Opcodes.DCONST_1 -> handlers.pushDouble(1.0);

            case Opcodes.IADD -> handlers.binaryOp(Opcode.ADD, Type.INT);
            case Opcodes.ISUB -> handlers.binaryOp(Opcode.SUB, Type.INT);
            case Opcodes.IMUL -> handlers.binaryOp(Opcode.MUL, Type.INT);
            case Opcodes.IDIV -> handlers.binaryOp(Opcode.DIV, Type.INT);
            case Opcodes.IREM -> handlers.binaryOp(Opcode.REM, Type.INT);
            case Opcodes.INEG -> handlers.unaryNeg(Type.INT);
            case Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR -> handlers.shiftOp();
            case Opcodes.IAND -> handlers.binaryOp(Opcode.AND, Type.INT);
            case Opcodes.IOR -> handlers.binaryOp(Opcode.OR, Type.INT);
            case Opcodes.IXOR -> handlers.binaryOp(Opcode.XOR, Type.INT);

            case Opcodes.LADD -> handlers.binaryOp(Opcode.ADD, Type.LONG);
            case Opcodes.LSUB -> handlers.binaryOp(Opcode.SUB, Type.LONG);
            case Opcodes.LMUL -> handlers.binaryOp(Opcode.MUL, Type.LONG);
            case Opcodes.LDIV -> handlers.binaryOp(Opcode.DIV, Type.LONG);
            case Opcodes.LREM -> handlers.binaryOp(Opcode.REM, Type.LONG);
            case Opcodes.LNEG -> handlers.unaryNeg(Type.LONG);
            case Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR -> handlers.shiftOp();
            case Opcodes.LAND -> handlers.binaryOp(Opcode.AND, Type.LONG);
            case Opcodes.LOR -> handlers.binaryOp(Opcode.OR, Type.LONG);
            case Opcodes.LXOR -> handlers.binaryOp(Opcode.XOR, Type.LONG);

            case Opcodes.FADD -> handlers.binaryOp(Opcode.ADD, Type.FLOAT);
            case Opcodes.FSUB -> handlers.binaryOp(Opcode.SUB, Type.FLOAT);
            case Opcodes.FMUL -> handlers.binaryOp(Opcode.MUL, Type.FLOAT);
            case Opcodes.FDIV -> handlers.binaryOp(Opcode.DIV, Type.FLOAT);
            case Opcodes.FREM -> handlers.binaryOp(Opcode.REM, Type.FLOAT);
            case Opcodes.FNEG -> handlers.unaryNeg(Type.FLOAT);

            case Opcodes.DADD -> handlers.binaryOp(Opcode.ADD, Type.DOUBLE);
            case Opcodes.DSUB -> handlers.binaryOp(Opcode.SUB, Type.DOUBLE);
            case Opcodes.DMUL -> handlers.binaryOp(Opcode.MUL, Type.DOUBLE);
            case Opcodes.DDIV -> handlers.binaryOp(Opcode.DIV, Type.DOUBLE);
            case Opcodes.DREM -> handlers.binaryOp(Opcode.REM, Type.DOUBLE);
            case Opcodes.DNEG -> handlers.unaryNeg(Type.DOUBLE);

            case Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> handlers.cmpOp();

            case Opcodes.I2B, Opcodes.I2C, Opcodes.I2S, Opcodes.I2L, Opcodes.I2F, Opcodes.I2D,
                 Opcodes.L2I, Opcodes.L2F, Opcodes.L2D, Opcodes.F2I, Opcodes.F2L, Opcodes.F2D,
                 Opcodes.D2I, Opcodes.D2L, Opcodes.D2F -> handlers.convert();

            case Opcodes.POP -> { if (!frame.isEmpty()) frame.pop(); }
            case Opcodes.POP2 -> frame.pop2();
            case Opcodes.DUP -> frame.dup();
            case Opcodes.DUP_X1 -> frame.dupX1();
            case Opcodes.DUP_X2 -> frame.dupX2();
            case Opcodes.DUP2 -> frame.dup2();
            case Opcodes.SWAP -> frame.swap();

            case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN,
                 Opcodes.DRETURN, Opcodes.ARETURN -> handlers.returnValue();
            case Opcodes.RETURN -> handlers.returnVoid();

            case Opcodes.ARRAYLENGTH -> handlers.arrayLength();
            case Opcodes.AALOAD -> {
                Value index = frame.pop();
                Value array = frame.pop();
                Instruction inst = builder.addInstruction(Opcode.ALOAD, array, index);
                frame.push(inst.getResult());
            }
            case Opcodes.AASTORE -> {
                Value value = frame.pop();
                Value index = frame.pop();
                Value array = frame.pop();
                builder.addInstruction(Opcode.ASTORE, array, index, value);
            }
            case Opcodes.ATHROW -> handlers.throwException();
            case Opcodes.MONITORENTER -> handlers.monitorEnter();
            case Opcodes.MONITOREXIT -> handlers.monitorExit();

            default -> log.warn("Unhandled insn opcode: {}", opcode);
        }
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        switch (opcode) {
            case Opcodes.BIPUSH:
            case Opcodes.SIPUSH:
                handlers.pushInt(operand);
                break;
            case Opcodes.NEWARRAY:
                handlers.newArray(operand);
                break;
            default:
                log.warn("Unhandled int insn: {} {}", opcode, operand);
        }
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        switch (opcode) {
            case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD -> {
                Type type = typeOfLoad(opcode);
                Instruction load = builder.createLoad(var, type);
                frame.push(load.getResult());
            }
            case Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> {
                Value val = frame.pop();
                Instruction store = builder.createStore(val, var);
                frame.setLocal(var, store.getResult());
            }
            case Opcodes.RET -> { /* ignore */ }
            default -> log.warn("Unhandled var insn: {} {}", opcode, var);
        }
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        switch (opcode) {
            case Opcodes.NEW -> handlers.newObject(type);
            case Opcodes.ANEWARRAY -> handlers.anewArray(type);
            case Opcodes.CHECKCAST -> handlers.checkCast(type);
            case Opcodes.INSTANCEOF -> handlers.instanceOf(type);
            default -> log.warn("Unhandled type insn: {} {}", opcode, type);
        }
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        switch (opcode) {
            case Opcodes.GETFIELD -> handlers.getField(owner, name);
            case Opcodes.PUTFIELD -> handlers.putField(owner, name);
            case Opcodes.GETSTATIC -> handlers.getStatic(owner, name);
            case Opcodes.PUTSTATIC -> handlers.putStatic(owner, name);
            default -> log.warn("Unhandled field insn: {} {} {}", opcode, owner, name);
        }
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
        handlers.callMethod(opcode, owner, name, desc, isInterface);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        BasicBlock target = getOrCreateBlock(label);
        switch (opcode) {
            case Opcodes.GOTO -> builder.createBranch(target);
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE -> {
                Value val = frame.pop();
                Constant zero = new Constant(Type.INT, 0);
                Instruction cmp = builder.addInstruction(mapIfOpcode(opcode), val, zero);
                BasicBlock next = createNextBlock();
                builder.createCondBranch(cmp.getResult(), target, next);
            }
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT,
                 Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> {
                Value right = frame.pop();
                Value left = frame.pop();
                Instruction cmp = builder.addInstruction(mapIfOpcode(opcode), left, right);
                BasicBlock next = createNextBlock();
                builder.createCondBranch(cmp.getResult(), target, next);
            }
            case Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> {
                Value right = frame.pop();
                Value left = frame.pop();
                Opcode cmpOp = opcode == Opcodes.IF_ACMPEQ ? Opcode.EQ : Opcode.NE;
                Instruction cmp = builder.addInstruction(cmpOp, left, right);
                BasicBlock next = createNextBlock();
                builder.createCondBranch(cmp.getResult(), target, next);
            }
            case Opcodes.IFNULL, Opcodes.IFNONNULL -> {
                Value val = frame.pop();
                Opcode cmpOp = opcode == Opcodes.IFNULL ? Opcode.EQ : Opcode.NE;
                Constant nul = new Constant(Type.NULL, null);
                Instruction cmp = builder.addInstruction(cmpOp, val, nul);
                BasicBlock next = createNextBlock();
                builder.createCondBranch(cmp.getResult(), target, next);
            }
            case Opcodes.JSR -> { /* ignore */ }
            default -> log.warn("Unhandled jump insn: {}", opcode);
        }
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        Value key = frame.pop();
        BasicBlock defaultBlock = getOrCreateBlock(dflt);
        BasicBlock[] targetBlocks = Arrays.stream(labels).map(this::getOrCreateBlock).toArray(BasicBlock[]::new);
        builder.createLookupSwitch(key, keys, targetBlocks, defaultBlock);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        Value key = frame.pop();
        BasicBlock defaultBlock = getOrCreateBlock(dflt);
        BasicBlock[] targetBlocks = Arrays.stream(labels).map(this::getOrCreateBlock).toArray(BasicBlock[]::new);
        builder.createTableSwitch(key, min, max, targetBlocks, defaultBlock);
    }

    @Override
    public void visitLdcInsn(Object value) {
        switch (value) {
            case Integer i -> handlers.pushInt(i);
            case Long l -> handlers.pushLong(l);
            case Float f -> handlers.pushFloat(f);
            case Double d -> handlers.pushDouble(d);
            case String s -> frame.push(new Constant(Type.REFERENCE, s));
            case org.objectweb.asm.Type asmType -> frame.push(new Constant(Type.REFERENCE, asmType.getInternalName()));
            case null, default -> frame.push(new Constant(Type.UNKNOWN, value));
        }
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
        handlers.multiNewArray(desc, dims);
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        tryCatchHandler.addTryCatch(start, end, handler, type);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
        String sb = "invokedynamic:" + name + desc +
            " bsm=" + bsm.getOwner() + "." + bsm.getName() + bsm.getDesc();
        Instruction call = new Instruction(Opcode.CALL);
        call.addOperand(new Constant(Type.REFERENCE, sb));
        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);
        int paramCount = paramTypes.size();
        List<Value> args = frame.popArgs(paramCount);
        for (Value arg : args) {
            call.addOperand(arg);
        }
        Type retType = TypeResolver.descToReturnType(desc);
        if (retType != Type.VOID) {
            Temporary tmp = builder.newTemporary(retType);
            call.setResult(tmp);
            tmp.setDefiningInstruction(call);
            builder.currentBlock().addInstruction(call);
            frame.push(tmp);
        } else {
            builder.currentBlock().addInstruction(call);
        }
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) { /* no-op */ }

    @Override
    public void visitEnd() {
        if (currentBlock != null && currentBlock.getTerminator() == null) {
            handlers.returnVoid();
        }
        tryCatchHandler.handle();
    }

    private BasicBlock getOrCreateBlock(Label label) {
        return labelToBlock.computeIfAbsent(label,
                k -> builder.createBlock("L" + k.toString()));
    }

    private BasicBlock createNextBlock() {
        return builder.createBlock("block" + currentFunction.getBlocks().size());
    }

    private Type typeOfLoad(int opcode) {
        return switch (opcode) {
            case Opcodes.ILOAD -> Type.INT;
            case Opcodes.LLOAD -> Type.LONG;
            case Opcodes.FLOAD -> Type.FLOAT;
            case Opcodes.DLOAD -> Type.DOUBLE;
            case Opcodes.ALOAD -> Type.REFERENCE;
            default -> Type.UNKNOWN;
        };
    }

    private Opcode mapIfOpcode(int opcode) {
        return switch (opcode) {
            case Opcodes.IFEQ, Opcodes.IF_ICMPEQ, Opcodes.IF_ACMPEQ -> Opcode.EQ;
            case Opcodes.IFNE, Opcodes.IF_ICMPNE, Opcodes.IF_ACMPNE -> Opcode.NE;
            case Opcodes.IFLT, Opcodes.IF_ICMPLT -> Opcode.LT;
            case Opcodes.IFGE, Opcodes.IF_ICMPGE -> Opcode.GE;
            case Opcodes.IFGT, Opcodes.IF_ICMPGT -> Opcode.GT;
            case Opcodes.IFLE, Opcodes.IF_ICMPLE -> Opcode.LE;
            default -> Opcode.EQ;
        };
    }
}

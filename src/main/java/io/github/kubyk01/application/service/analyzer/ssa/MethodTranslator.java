package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.BranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.IndirectBranchTerminator;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.InvokeDynamicInfo;
import io.github.kubyk01.domain.ir.IrBuilder;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.ResolvedCall;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.TryCatchRange;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
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
    private final List<TryCatchRange> tryCatchRanges = new ArrayList<>();
    private final Map<Integer, Set<BasicBlock>> jsrReturnBlocks = new HashMap<>();
    private final List<IndirectBranchTerminator> indirectBranches = new ArrayList<>();
    private int lambdaCounter = 0;
    private final DependencyResolver resolver;

    @Getter
    private Function currentFunction;
    private BasicBlock currentBlock;

    public MethodTranslator(MethodReference methodRef, boolean isStatic,
                            IrBuilder builder, DependencyResolver resolver) {
        super(Opcodes.ASM9);
        this.methodRef = methodRef;
        this.isStatic = isStatic;
        this.builder = builder;
        this.resolver = resolver;
        this.frame = new StackFrame(builder);
        this.tryCatchHandler = new TryCatchHandler(labelToBlock);
        this.handlers = new InstructionHandlers(builder, frame, resolver);
    }

    @Override
    public void visitCode() {
        Type returnType = TypeResolver.descToReturnType(methodRef.getDescriptor());
        List<Type> paramTypes = TypeResolver.descToParamTypes(methodRef.getDescriptor());
        List<Type> allParamTypes = new ArrayList<>();
        if (!isStatic) {
            allParamTypes.add(Type.reference(methodRef.getOwner())); // receiver
        }
        allParamTypes.addAll(paramTypes);
        String mangledName = LlvmRuntime.mangleMethod(methodRef.getOwner(), methodRef.getName(), methodRef.getDescriptor());
        currentFunction = builder.createFunction(mangledName, returnType, allParamTypes);

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
            case Opcodes.ACONST_NULL:
                handlers.pushNull();
                break;
            case Opcodes.ICONST_M1:
                handlers.pushInt(-1);
                break;
            case Opcodes.ICONST_0:
                handlers.pushInt(0);
                break;
            case Opcodes.ICONST_1:
                handlers.pushInt(1);
                break;
            case Opcodes.ICONST_2:
                handlers.pushInt(2);
                break;
            case Opcodes.ICONST_3:
                handlers.pushInt(3);
                break;
            case Opcodes.ICONST_4:
                handlers.pushInt(4);
                break;
            case Opcodes.ICONST_5:
                handlers.pushInt(5);
                break;
            case Opcodes.LCONST_0:
                handlers.pushLong(0L);
                break;
            case Opcodes.LCONST_1:
                handlers.pushLong(1L);
                break;
            case Opcodes.FCONST_0:
                handlers.pushFloat(0.0f);
                break;
            case Opcodes.FCONST_1:
                handlers.pushFloat(1.0f);
                break;
            case Opcodes.FCONST_2:
                handlers.pushFloat(2.0f);
                break;
            case Opcodes.DCONST_0:
                handlers.pushDouble(0.0);
                break;
            case Opcodes.DCONST_1:
                handlers.pushDouble(1.0);
                break;

            case Opcodes.IADD:
                handlers.binaryOp(Opcode.ADD);
                break;
            case Opcodes.ISUB:
                handlers.binaryOp(Opcode.SUB);
                break;
            case Opcodes.IMUL:
                handlers.binaryOp(Opcode.MUL);
                break;
            case Opcodes.IDIV:
                handlers.binaryOp(Opcode.DIV);
                break;
            case Opcodes.IREM:
                handlers.binaryOp(Opcode.REM);
                break;
            case Opcodes.INEG:
                handlers.unaryNeg(Type.INT);
                break;
            case Opcodes.ISHL:
            case Opcodes.ISHR:
            case Opcodes.IUSHR:
                handlers.shiftOp();
                break;
            case Opcodes.IAND:
                handlers.binaryOp(Opcode.AND);
                break;
            case Opcodes.IOR:
                handlers.binaryOp(Opcode.OR);
                break;
            case Opcodes.IXOR:
                handlers.binaryOp(Opcode.XOR);
                break;

            case Opcodes.LADD:
                handlers.binaryOp(Opcode.ADD);
                break;
            case Opcodes.LSUB:
                handlers.binaryOp(Opcode.SUB);
                break;
            case Opcodes.LMUL:
                handlers.binaryOp(Opcode.MUL);
                break;
            case Opcodes.LDIV:
                handlers.binaryOp(Opcode.DIV);
                break;
            case Opcodes.LREM:
                handlers.binaryOp(Opcode.REM);
                break;
            case Opcodes.LNEG:
                handlers.unaryNeg(Type.LONG);
                break;
            case Opcodes.LSHL:
            case Opcodes.LSHR:
            case Opcodes.LUSHR:
                handlers.shiftOp();
                break;
            case Opcodes.LAND:
                handlers.binaryOp(Opcode.AND);
                break;
            case Opcodes.LOR:
                handlers.binaryOp(Opcode.OR);
                break;
            case Opcodes.LXOR:
                handlers.binaryOp(Opcode.XOR);
                break;

            case Opcodes.FADD:
                handlers.binaryOp(Opcode.ADD);
                break;
            case Opcodes.FSUB:
                handlers.binaryOp(Opcode.SUB);
                break;
            case Opcodes.FMUL:
                handlers.binaryOp(Opcode.MUL);
                break;
            case Opcodes.FDIV:
                handlers.binaryOp(Opcode.DIV);
                break;
            case Opcodes.FREM:
                handlers.binaryOp(Opcode.REM);
                break;
            case Opcodes.FNEG:
                handlers.unaryNeg(Type.FLOAT);
                break;

            case Opcodes.DADD:
                handlers.binaryOp(Opcode.ADD);
                break;
            case Opcodes.DSUB:
                handlers.binaryOp(Opcode.SUB);
                break;
            case Opcodes.DMUL:
                handlers.binaryOp(Opcode.MUL);
                break;
            case Opcodes.DDIV:
                handlers.binaryOp(Opcode.DIV);
                break;
            case Opcodes.DREM:
                handlers.binaryOp(Opcode.REM);
                break;
            case Opcodes.DNEG:
                handlers.unaryNeg(Type.DOUBLE);
                break;

            case Opcodes.LCMP:
            case Opcodes.FCMPL:
            case Opcodes.FCMPG:
            case Opcodes.DCMPL:
            case Opcodes.DCMPG:
                handlers.cmpOp();
                break;

            case Opcodes.I2B:
                handlers.convertTo(Type.BYTE);
                break;
            case Opcodes.I2C:
                handlers.convertTo(Type.CHAR);
                break;
            case Opcodes.I2S:
                handlers.convertTo(Type.SHORT);
                break;
            case Opcodes.I2L:
                handlers.convertTo(Type.LONG);
                break;
            case Opcodes.I2F:
                handlers.convertTo(Type.FLOAT);
                break;
            case Opcodes.I2D:
                handlers.convertTo(Type.DOUBLE);
                break;
            case Opcodes.L2I:
                handlers.convertTo(Type.INT);
                break;
            case Opcodes.L2F:
                handlers.convertTo(Type.FLOAT);
                break;
            case Opcodes.L2D:
                handlers.convertTo(Type.DOUBLE);
                break;
            case Opcodes.F2I:
                handlers.convertTo(Type.INT);
                break;
            case Opcodes.F2L:
                handlers.convertTo(Type.LONG);
                break;
            case Opcodes.F2D:
                handlers.convertTo(Type.DOUBLE);
                break;
            case Opcodes.D2I:
                handlers.convertTo(Type.INT);
                break;
            case Opcodes.D2L:
                handlers.convertTo(Type.LONG);
                break;
            case Opcodes.D2F:
                handlers.convertTo(Type.FLOAT);
                break;

            case Opcodes.POP:
                if (!frame.isEmpty()) frame.pop();
                break;
            case Opcodes.POP2:
                frame.pop2();
                break;
            case Opcodes.DUP:
                frame.dup();
                break;
            case Opcodes.DUP_X1:
                frame.dupX1();
                break;
            case Opcodes.DUP_X2:
                frame.dupX2();
                break;
            case Opcodes.DUP2:
                frame.dup2();
                break;
            case Opcodes.SWAP:
                frame.swap();
                break;

            case Opcodes.IRETURN:
            case Opcodes.LRETURN:
            case Opcodes.FRETURN:
            case Opcodes.DRETURN:
            case Opcodes.ARETURN:
                handlers.returnValue();
                break;
            case Opcodes.RETURN:
                handlers.returnVoid();
                break;

            case Opcodes.ARRAYLENGTH:
                handlers.arrayLength();
                break;

            // Array loads and stores for all types
            case Opcodes.AALOAD:
            case Opcodes.IALOAD:
            case Opcodes.LALOAD:
            case Opcodes.FALOAD:
            case Opcodes.DALOAD:
            case Opcodes.BALOAD:
            case Opcodes.CALOAD:
            case Opcodes.SALOAD: {
                Value index = frame.pop();
                Value array = frame.pop();
                Instruction inst = builder.addInstruction(Opcode.ALOAD, array, index);
                frame.push(inst.getResult());
                break;
            }

            case Opcodes.AASTORE:
            case Opcodes.IASTORE:
            case Opcodes.LASTORE:
            case Opcodes.FASTORE:
            case Opcodes.DASTORE:
            case Opcodes.BASTORE:
            case Opcodes.CASTORE:
            case Opcodes.SASTORE: {
                Value value = frame.pop();
                Value index = frame.pop();
                Value array = frame.pop();
                builder.addInstruction(Opcode.ASTORE, array, index, value);
                break;
            }

            case Opcodes.ATHROW:
                handlers.throwException();
                break;
            case Opcodes.MONITORENTER:
                handlers.monitorEnter();
                break;
            case Opcodes.MONITOREXIT:
                handlers.monitorExit();
                break;

            default:
                log.warn("Unhandled insn opcode: {}", opcode);
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
            case Opcodes.ILOAD:
            case Opcodes.LLOAD:
            case Opcodes.FLOAD:
            case Opcodes.DLOAD:
            case Opcodes.ALOAD: {
                Type type = typeOfLoad(opcode);
                Instruction load = builder.createLoad(var, type);
                frame.push(load.getResult());
                break;
            }
            case Opcodes.ISTORE:
            case Opcodes.LSTORE:
            case Opcodes.FSTORE:
            case Opcodes.DSTORE:
            case Opcodes.ASTORE: {
                Value val = frame.pop();
                Instruction store = builder.createStore(val, var);
                frame.setLocal(var, store.getResult());
                if (val instanceof Temporary t
                    && t.getDefiningInstruction() != null
                    && t.getDefiningInstruction().getOpcode() == Opcode.JSR
                    && !t.getDefiningInstruction().getOperands().isEmpty()
                    && t.getDefiningInstruction().getOperands().getFirst() instanceof Constant c
                    && c.getType() == Type.BLOCK
                    && c.getValue() instanceof BasicBlock returnBlock) {
                    jsrReturnBlocks.computeIfAbsent(var, k -> new HashSet<>()).add(returnBlock);
                }
                break;
            }
            case Opcodes.RET: {
                Instruction load = builder.createLoad(var, Type.BLOCK);
                IndirectBranchTerminator indirect = new IndirectBranchTerminator(load.getResult());
                builder.currentBlock().setTerminator(indirect);
                indirectBranches.add(indirect);
                break;
            }
            default:
                log.warn("Unhandled var insn: {} {}", opcode, var);
        }
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        switch (opcode) {
            case Opcodes.NEW:
                handlers.newObject(type);
                break;
            case Opcodes.ANEWARRAY:
                handlers.anewArray(type);
                break;
            case Opcodes.CHECKCAST:
                handlers.checkCast(type);
                break;
            case Opcodes.INSTANCEOF:
                handlers.instanceOf(type);
                break;
            default:
                log.warn("Unhandled type insn: {} {}", opcode, type);
        }
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        switch (opcode) {
            case Opcodes.GETFIELD:
                handlers.getField(owner, name);
                break;
            case Opcodes.PUTFIELD:
                handlers.putField(owner, name);
                break;
            case Opcodes.GETSTATIC:
                handlers.getStatic(owner, name);
                break;
            case Opcodes.PUTSTATIC:
                handlers.putStatic(owner, name);
                break;
            default:
                log.warn("Unhandled field insn: {} {} {}", opcode, owner, name);
        }
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
        // Determine if the called method is polymorphic (has @PolymorphicSignature)
        boolean isPolymorphic = false;
        ClassNode targetClass = resolver.getClassNode(owner);
        if (targetClass != null) {
            isPolymorphic = targetClass.getPolymorphicMethodNames().contains(name);
        }
        handlers.callMethod(opcode, owner, name, desc, isPolymorphic);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        BasicBlock target = getOrCreateBlock(label);
        switch (opcode) {
            case Opcodes.GOTO:
                builder.createBranch(target);
                break;
            case Opcodes.IFEQ:
            case Opcodes.IFNE:
            case Opcodes.IFLT:
            case Opcodes.IFGE:
            case Opcodes.IFGT:
            case Opcodes.IFLE: {
                Value val = frame.pop();
                Constant zero = new Constant(Type.INT, 0);
                Instruction cmp = builder.addInstruction(mapIfOpcode(opcode), val, zero);
                BasicBlock next = createNextBlock();
                builder.createCondBranch(cmp.getResult(), target, next);
                break;
            }
            case Opcodes.IF_ICMPEQ:
            case Opcodes.IF_ICMPNE:
            case Opcodes.IF_ICMPLT:
            case Opcodes.IF_ICMPGE:
            case Opcodes.IF_ICMPGT:
            case Opcodes.IF_ICMPLE: {
                Value right = frame.pop();
                Value left = frame.pop();
                Instruction cmp = builder.addInstruction(mapIfOpcode(opcode), left, right);
                BasicBlock next = createNextBlock();
                builder.createCondBranch(cmp.getResult(), target, next);
                break;
            }
            case Opcodes.IF_ACMPEQ:
            case Opcodes.IF_ACMPNE: {
                Value right = frame.pop();
                Value left = frame.pop();
                Opcode cmpOp = opcode == Opcodes.IF_ACMPEQ ? Opcode.EQ : Opcode.NE;
                Instruction cmp = builder.addInstruction(cmpOp, left, right);
                BasicBlock next = createNextBlock();
                builder.createCondBranch(cmp.getResult(), target, next);
                break;
            }
            case Opcodes.IFNULL:
            case Opcodes.IFNONNULL: {
                Value val = frame.pop();
                Opcode cmpOp = opcode == Opcodes.IFNULL ? Opcode.EQ : Opcode.NE;
                Constant nul = new Constant(Type.NULL, null);
                Instruction cmp = builder.addInstruction(cmpOp, val, nul);
                BasicBlock next = createNextBlock();
                builder.createCondBranch(cmp.getResult(), target, next);
                break;
            }
            case Opcodes.JSR: {
                BasicBlock current = builder.currentBlock();
                BasicBlock returnBlock = createNextBlock();
                BasicBlock targetBlock = getOrCreateBlock(label);

                Instruction jsrInst = new Instruction(Opcode.JSR);
                jsrInst.addOperand(new Constant(Type.BLOCK, returnBlock));
                Temporary blockVal = builder.newTemporary(Type.BLOCK);
                jsrInst.setResult(blockVal);
                blockVal.setDefiningInstruction(jsrInst);
                current.addInstruction(jsrInst);
                frame.push(blockVal);

                current.setTerminator(new BranchTerminator(targetBlock));
                builder.setCurrentBlock(returnBlock);
                currentBlock = returnBlock;
                break;
            }
            default:
                log.warn("Unhandled jump insn: {}", opcode);
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
            case Float v -> handlers.pushFloat(v);
            case Double v -> handlers.pushDouble(v);
            case String s -> frame.push(new Constant(Type.reference("java/lang/String"), value));
            case org.objectweb.asm.Type asmType ->
                frame.push(new Constant(Type.reference(asmType.getInternalName()), asmType.getInternalName()));
            case null, default -> frame.push(new Constant(Type.UNKNOWN, value));
        }
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
        handlers.multiNewArray(desc, dims);
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        tryCatchRanges.add(new TryCatchRange(start, end, handler, type));
        tryCatchHandler.addTryCatch(start, end, handler, type);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);
        List<Value> captured = new ArrayList<>();
        for (int i = 0; i < paramTypes.size(); i++) {
            captured.add(frame.pop());
        }
        Collections.reverse(captured);

        ResolvedCall resolved = resolveInvokeDynamic(bsm, bsmArgs, captured);
        InvokeDynamicInfo info = new InvokeDynamicInfo(name, desc, bsm, bsmArgs, resolved);

        Instruction inst = new Instruction(Opcode.INVOKEDYNAMIC);
        inst.setInvokedynamicData(info);
        for (Value v : captured) {
            inst.addOperand(v);
        }

        Type retType = TypeResolver.descToReturnType(desc);
        if (!retType.isVoid()) {
            Temporary tmp = builder.newTemporary(retType);
            inst.setResult(tmp);
            tmp.setDefiningInstruction(inst);
            frame.push(tmp);
        }
        builder.currentBlock().addInstruction(inst);
    }

    private ResolvedCall resolveInvokeDynamic(Handle bsm,
                                              Object[] bsmArgs, List<Value> captured) {
        if (bsm == null) return ResolvedCall.unsupported();

        String owner = bsm.getOwner();
        String methodName = bsm.getName();

        if (owner.contains("LambdaMetafactory") &&
            (methodName.equals("metafactory") || methodName.equals("altMetafactory"))) {
            if (bsmArgs.length < 3) return ResolvedCall.unsupported();

            org.objectweb.asm.Type samType = (org.objectweb.asm.Type) bsmArgs[0];
            String interfaceMethodSig = samType.getDescriptor();

            String lambdaId = "lambda_" + (++lambdaCounter) + "_" + System.identityHashCode(this);

            List<Type> capturedTypes = captured.stream().map(Value::getType).collect(java.util.stream.Collectors.toList());
            return ResolvedCall.lambda(lambdaId, interfaceMethodSig, capturedTypes);
        }

        if (owner.equals("java/lang/StringConcatFactory") &&
            (methodName.equals("makeConcat") || methodName.equals("makeConcatWithConstants"))) {
            return ResolvedCall.concat(null);
        }

        return ResolvedCall.unsupported();
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) { /* no-op */ }

    @Override
    public void visitEnd() {
        if (currentBlock != null && currentBlock.getTerminator() == null) {
            handlers.returnVoid();
        }
        for (IndirectBranchTerminator ibt : indirectBranches) {
            int var = -1;
            if (ibt.getTargetBlock() instanceof Temporary t
                && t.getDefiningInstruction() != null) {
                var = t.getDefiningInstruction().getLocalIndex();
            }
            if (var >= 0) {
                ibt.getPossibleTargets().addAll(
                    jsrReturnBlocks.getOrDefault(var, Collections.emptySet()));
            }
        }
        tryCatchHandler.handle();
        currentFunction.setTryCatchRanges(tryCatchRanges);
        addExceptionalEdges();
    }

    private void addExceptionalEdges() {
        for (TryCatchRange range : tryCatchRanges) {
            BasicBlock startBlock = labelToBlock.get(range.start);
            BasicBlock endBlock = labelToBlock.get(range.end);
            BasicBlock handlerBlock = labelToBlock.get(range.handler);
            if (startBlock == null || endBlock == null || handlerBlock == null) continue;
            List<BasicBlock> blocksInRange = GraphUtils.getBlocksBetween(startBlock, endBlock);
            for (BasicBlock block : blocksInRange) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst.canThrow()) {
                        block.addExceptionalSuccessor(handlerBlock);
                    }
                }
                Terminator term = block.getTerminator();
                if (term != null && term.canThrow()) {
                    block.addExceptionalSuccessor(handlerBlock);
                }
            }
        }
    }

    private BasicBlock getOrCreateBlock(Label label) {
        return labelToBlock.computeIfAbsent(label,
            k -> builder.createBlock("L" + k.toString()));
    }

    private BasicBlock createNextBlock() {
        return builder.createBlock("block" + currentFunction.getBlocks().size());
    }

    private Type typeOfLoad(int opcode) {
        switch (opcode) {
            case Opcodes.ILOAD:
                return Type.INT;
            case Opcodes.LLOAD:
                return Type.LONG;
            case Opcodes.FLOAD:
                return Type.FLOAT;
            case Opcodes.DLOAD:
                return Type.DOUBLE;
            case Opcodes.ALOAD:
                return Type.reference("java/lang/Object");
            default:
                return Type.UNKNOWN;
        }
    }

    private Opcode mapIfOpcode(int opcode) {
        switch (opcode) {
            case Opcodes.IFEQ:
            case Opcodes.IF_ICMPEQ:
            case Opcodes.IF_ACMPEQ:
                return Opcode.EQ;
            case Opcodes.IFNE:
            case Opcodes.IF_ICMPNE:
            case Opcodes.IF_ACMPNE:
                return Opcode.NE;
            case Opcodes.IFLT:
            case Opcodes.IF_ICMPLT:
                return Opcode.LT;
            case Opcodes.IFGE:
            case Opcodes.IF_ICMPGE:
                return Opcode.GE;
            case Opcodes.IFGT:
            case Opcodes.IF_ICMPGT:
                return Opcode.GT;
            case Opcodes.IFLE:
            case Opcodes.IF_ICMPLE:
                return Opcode.LE;
            default:
                return Opcode.EQ;
        }
    }
}
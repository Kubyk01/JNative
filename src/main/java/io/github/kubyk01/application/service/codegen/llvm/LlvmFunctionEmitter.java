package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.ssa.GraphUtils;
import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.application.service.codegen.llvm.nativepolymorphicfunctionresolver.PolymorphicResolver;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.BranchTerminator;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.IndirectBranchTerminator;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.InvokeDynamicInfo;
import io.github.kubyk01.domain.ir.LookupSwitchTerminator;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ResolvedCall;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.TableSwitchTerminator;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.TryCatchRange;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.util.parserC.ParserC.NativeMethodInfo;
import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractClassName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;
import static io.github.kubyk01.util.LlvmUtil.extractTypeName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;
import static io.github.kubyk01.util.LlvmUtil.getElementSizeOfType;

@RequiredArgsConstructor
public class LlvmFunctionEmitter {

    @FunctionalInterface
    private interface CallEmitter {
        void emit(StringBuilder sb, String resultReg);
    }

    private static final int JMP_BUF_SIZE = 256;

    private final Module module;
    private final LlvmGlobalEmitter globalEmitter;
    private final PolymorphicResolver polymorphicResolver;
    private final DependencyResolver resolver;

    private final LlvmValueMapper valueMapper = new LlvmValueMapper();

    /**
     * Slots for values that cannot be represented as plain SSA registers across
     * a try-guard boundary. See {@link #emitCall(StringBuilder, Type, Value, String, List, CallEmitter)}.
     * Keyed by reference identity — {@link io.github.kubyk01.domain.ir.Temporary}
     * uses identity semantics, so this matches {@code valueMapper}.
     */
    private final Map<Value, String> valueSlots = new HashMap<>();

    /**
     * Per-phi cache of preloaded slot-based operands. Keyed by the phi
     * {@link Instruction}, then by the incoming-edge index. Materialised in
     * the predecessor block, right before its terminator, so that the loaded
     * register dominates the edge to the phi's block.
     */
    private final Map<Instruction, Map<Integer, String>> phiPreloads = new HashMap<>();

    /**
     * Stack slots for values that cannot be represented as plain SSA
     * registers across a try-guard boundary (see
     * {@link #emitCall(StringBuilder, Type, Value, String, List, CallEmitter)}).
     *
     * <p>Each entry is a complete {@code alloca} declaration. They are
     * accumulated while block bodies are emitted and then flushed into the
     * function's entry block during assembly, so that the address of every
     * slot dominates every block that may load it. Emitting the alloca in
     * the block that performs the call only makes the slot available on
     * paths that pass through that block; any subsequent load on a
     * bypassing path — for instance a later guarded region that shares the
     * enclosing continuation block — violates LLVM's dominance check with
     * <em>"Instruction does not dominate all uses!"</em>.</p>
     */
    private final List<String> pendingSlotDeclarations = new ArrayList<>();

    private int tmpCounter = 0;
    private int labelCounter = 0;
    private BasicBlock currentEntryBlock;

    /**
     * Mangled name of the function currently being emitted. Passed as the
     * caller argument to the __jnative_throw_*_ctx helpers so an unhandled
     * exception reports the enclosing Java method in its stack trace.
     */
    private String currentFunctionName;

    private final Map<BasicBlock, List<TryCatchRange>> blockToTryRanges = new HashMap<>();
    private final Map<TryCatchRange, BasicBlock> handlerBlockByRange = new HashMap<>();
    private final Map<TryCatchRange, Integer> rangeOrdinals = new HashMap<>();

    public String emitFunction(Function func) {
        String funcName = func.getName();

        if ("fn_java_security_Provider_checkInitialized___V".equals(funcName)) {
            return "define void @" + funcName + "(i8* %param_0) {\n"
                + "entry:\n"
                + "  ret void\n"
                + "}\n\n";
        }

        this.currentFunctionName = func.getName();

        valueMapper.clear();
        valueSlots.clear();
        phiPreloads.clear();
        pendingSlotDeclarations.clear();

        StringBuilder signature = new StringBuilder();
        signature.append("define ")
            .append(LlvmTypeMapper.toLlvmType(func.getReturnType()))
            .append(" @").append(funcName).append("(");
        List<Parameter> params = func.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) signature.append(", ");
            signature.append(LlvmTypeMapper.toLlvmType(params.get(i).getType()))
                .append(" %param_").append(i);
        }
        signature.append(") {\n");

        for (int i = 0; i < params.size(); i++) {
            valueMapper.setValue(params.get(i), "%param_" + i);
        }

        currentEntryBlock = func.getEntryBlock();
        buildTryCatchInfo(func);

        List<String> bodies = new ArrayList<>();
        int entryIdx = 0;
        boolean firstBlock = true;
        for (int i = 0; i < func.getBlocks().size(); i++) {
            BasicBlock block = func.getBlocks().get(i);
            boolean isEntry = (block == currentEntryBlock)
                || (currentEntryBlock == null && firstBlock);
            if (isEntry && firstBlock && entryIdx == 0 && i != 0) {
            }
            if (isEntry && entryIdx == 0 && i != 0 && currentEntryBlock != null) {
            }
            if (isEntry && (currentEntryBlock != null)) {
                entryIdx = i;
            } else if (isEntry && currentEntryBlock == null) {
                entryIdx = i;
                firstBlock = false;
            }
            bodies.add(emitBlock(block, isEntry));
            firstBlock = false;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(signature);
        sb.append("entry:\n");

        Set<Integer> usedLocals = collectUsedLocals(func);
        for (int idx : usedLocals) {
            sb.append("  %local_").append(idx).append(" = alloca i64, align 8\n");
        }

        for (int i = 0; i < params.size(); i++) {
            Parameter p = params.get(i);
            int slotIdx = p.getIndex();
            if (!usedLocals.contains(slotIdx)) continue;
            String ty  = LlvmTypeMapper.toLlvmType(p.getType());
            String ptr = newAux("param_init");
            sb.append("  ").append(ptr)
                .append(" = bitcast i64* %local_").append(slotIdx)
                .append(" to ").append(ty).append("*\n");
            sb.append("  store ").append(ty).append(" %param_").append(i)
                .append(", ").append(ty).append("* ").append(ptr).append("\n");
        }

        List<TryCatchRange> ranges = func.getTryCatchRanges();
        if (ranges != null) {
            for (int i = 0; i < ranges.size(); i++) {
                sb.append("  %jmp_buf_").append(i).append(" = alloca [")
                    .append(JMP_BUF_SIZE).append(" x i8], align 16\n");
            }
        }

        for (String decl : pendingSlotDeclarations) {
            sb.append(decl);
        }

        sb.append(bodies.get(entryIdx));
        for (int i = 0; i < bodies.size(); i++) {
            if (i != entryIdx) {
                sb.append(bodies.get(i));
            }
        }

        sb.append("}\n\n");
        return sb.toString();
    }

    private void buildTryCatchInfo(Function func) {
        blockToTryRanges.clear();
        handlerBlockByRange.clear();
        rangeOrdinals.clear();

        List<TryCatchRange> ranges = func.getTryCatchRanges();
        if (ranges == null || ranges.isEmpty()) return;

        Map<String, BasicBlock> byLabel = new HashMap<>();
        for (BasicBlock b : func.getBlocks()) {
            byLabel.put(b.getLabel(), b);
        }

        int ordinal = 0;
        for (TryCatchRange range : ranges) {
            BasicBlock startBlock = byLabel.get("L" + range.start);
            BasicBlock endBlock = byLabel.get("L" + range.end);
            BasicBlock handlerBlock = byLabel.get("L" + range.handler);
            if (startBlock == null || endBlock == null || handlerBlock == null) continue;
            for (BasicBlock b : GraphUtils.getBlocksBetween(startBlock, endBlock)) {
                blockToTryRanges.computeIfAbsent(b, x -> new ArrayList<>()).add(range);
            }
            handlerBlockByRange.put(range, handlerBlock);
            rangeOrdinals.put(range, ordinal++);
        }
    }

    private Set<Integer> collectUsedLocals(Function func) {
        Set<Integer> locals = new HashSet<>();
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() == Opcode.LOAD || inst.getOpcode() == Opcode.STORE) {
                    locals.add(inst.getLocalIndex());
                }
            }
        }
        return locals;
    }

    private String emitBlock(BasicBlock block, boolean labelAlreadyEmitted) {
        StringBuilder sb = new StringBuilder();
        if (!labelAlreadyEmitted) {
            sb.append(llvmLabel(block)).append(":\n");
        }

        List<TryCatchRange> ranges = blockToTryRanges.getOrDefault(block, List.of());

        for (Instruction inst : block.getInstructions()) {
            sb.append(emitInstruction(inst, ranges));
        }

        Terminator term = block.getTerminator();
        if (term != null) {
            // A successor's phi may use a value that lives in a try-guard slot.
            // We cannot reload it inside the phi itself — phi operands must be
            // bare values — so we materialise the load here, in the predecessor
            // block, where the resulting SSA register dominates the edge to the
            // successor.
            for (BasicBlock succ : term.getTargets()) {
                int predIdx = succ.getPredecessors().indexOf(block);
                if (predIdx < 0) continue;
                for (Instruction succInst : succ.getInstructions()) {
                    if (succInst.getOpcode() != Opcode.PHI) continue;
                    if (predIdx >= succInst.getOperands().size()) continue;
                    Value phiOp = succInst.getOperands().get(predIdx);
                    if (phiOp == null) continue;
                    String slot = valueSlots.get(phiOp);
                    if (slot == null) continue;

                    String ty = LlvmTypeMapper.toLlvmType(phiOp.getType());
                    String tmp = newAux("phi_preload");
                    sb.append("  ").append(tmp).append(" = load ")
                        .append(ty).append(", ").append(ty)
                        .append("* ").append(slot).append("\n");

                    phiPreloads
                        .computeIfAbsent(succInst, k -> new HashMap<>())
                        .put(predIdx, tmp);
                }
            }
            sb.append(emitTerminator(term, ranges));
        } else {
            Type retType = block.getFunction().getReturnType();
            if (retType.isVoid()) {
                sb.append("  ret void\n");
            } else {
                String zero = getZeroValue(retType);
                sb.append("  ret ").append(LlvmTypeMapper.toLlvmType(retType))
                    .append(" ").append(zero).append("\n");
            }
        }
        return sb.toString();
    }

    private String getZeroValue(Type type) {
        if (type == null) return "null";
        if (type == Type.FLOAT || type == Type.DOUBLE) return "0.0";
        if (type.isReference() || type.isArray() || type.isNull()
            || type.isBlock() || type.isUnknown()) {
            return "null";
        }
        if (type == Type.BOOLEAN) return "false";
        return "0";
    }

    private String llvmLabel(BasicBlock block) {
        if (block == currentEntryBlock) {
            return "entry";
        }
        return block.getLabel().replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String newAux(String prefix) {
        return "%aux_" + prefix + "_" + (tmpCounter++);
    }

    private String newLabel(String prefix) {
        return "lbl_" + prefix + "_" + (labelCounter++);
    }
    private String stringRef(String s) {
        int len = LlvmRuntime.typeStringArrayLength(s);
        String g = LlvmRuntime.typeStringGlobalName(s);
        return "getelementptr inbounds ([" + len + " x i8], [" + len + " x i8]* "
            + g + ", i32 0, i32 0)";
    }

    private void emitThrowHelper(StringBuilder sb, String callee, List<TryCatchRange> ranges) {
        String ctxCallee = callee + "_ctx";
        String funcNameRef = stringRef(currentFunctionName);

        if (ranges.isEmpty()) {
            sb.append("  call void ").append(ctxCallee)
                .append("(i8* ").append(funcNameRef).append(")\n");
            sb.append("  unreachable\n");
        } else {
            emitTryGuard(sb, ranges,
                inner -> inner.append("  call void ").append(ctxCallee)
                    .append("(i8* ").append(funcNameRef).append(")\n"),
                true);
        }
    }

    private void emitNpeThrowHelper(StringBuilder sb,
                                    String varDesc,
                                    List<TryCatchRange> ranges) {
        String ctxCallee   = "@__jnative_throw_null_pointer_exception_ctx";
        String funcNameRef = stringRef(currentFunctionName);

        String extraRef;
        if (varDesc != null && varDesc.startsWith("%")) {
            globalEmitter.registerDeferredString(varDesc);
            extraRef = stringRef(varDesc);
        } else {
            extraRef = "null";
        }

        if (ranges.isEmpty()) {
            sb.append("  call void ").append(ctxCallee)
              .append("(i8* ").append(funcNameRef)
              .append(", i8* ").append(extraRef).append(")\n");
            sb.append("  unreachable\n");
        } else {
            String finalExtraRef = extraRef;
            emitTryGuard(sb, ranges,
                inner -> inner.append("  call void ").append(ctxCallee)
                    .append("(i8* ").append(funcNameRef)
                    .append(", i8* ").append(finalExtraRef).append(")\n"),
                true);
        }
    }

    private void emitNullCheck(StringBuilder sb, Value obj, List<TryCatchRange> ranges) {
        // Skip null check for non‑pointer types to avoid invalid LLVM (e.g., icmp ne i32, null)
        if (!(obj.getType().isReference()
            || obj.getType().isArray()
            || obj.getType().isNull()
            || obj.getType().isBlock()
            || obj.getType().isUnknown())) {
            return;
        }
        String ty = LlvmTypeMapper.toLlvmType(obj.getType());

        String objRef = getLlvmValue(sb, obj);

        String chk      = newAux("npe_chk");
        String throwBlk = newLabel("throw_npe");
        String cont     = newLabel("npe_ok");

        sb.append("  ").append(chk).append(" = icmp ne ").append(ty).append(" ")
            .append(objRef).append(", null\n");
        sb.append("  br i1 ").append(chk)
            .append(", label %").append(cont)
            .append(", label %").append(throwBlk).append("\n");
        sb.append(throwBlk).append(":\n");

        emitNpeThrowHelper(sb, objRef, ranges);

        sb.append(cont).append(":\n");
    }

    private void emitBoundsCheck(StringBuilder sb, Value arr, String idxI32, List<TryCatchRange> ranges) {
        String arrRef = getLlvmValue(sb, arr);
        String lenPtr = newAux("lenptr");
        String len = newAux("len");
        sb.append("  ").append(lenPtr).append(" = bitcast i8* ").append(arrRef).append(" to i32*\n");
        sb.append("  ").append(len).append(" = load i32, i32* ").append(lenPtr).append("\n");

        String chk1 = newAux("bnd_chk1");
        String chk2 = newAux("bnd_chk2");
        String ok = newAux("bnd_ok");
        String throwBlk = newLabel("throw_aioobe");
        String cont = newLabel("bnd_ok");
        sb.append("  ").append(chk1).append(" = icmp sge i32 ").append(idxI32).append(", 0\n");
        sb.append("  ").append(chk2).append(" = icmp slt i32 ").append(idxI32).append(", ").append(len).append("\n");
        sb.append("  ").append(ok).append(" = and i1 ").append(chk1).append(", ").append(chk2).append("\n");
        sb.append("  br i1 ").append(ok)
            .append(", label %").append(cont)
            .append(", label %").append(throwBlk).append("\n");
        sb.append(throwBlk).append(":\n");
        emitThrowHelper(sb, "@__jnative_throw_array_index_out_of_bounds", ranges);
        sb.append(cont).append(":\n");
    }

    private int getBaseElementSize(String desc) {
        String base = desc;
        while (base.startsWith("[")) {
            base = base.substring(1);
        }
        if (base.length() == 1) {
            return switch (base.charAt(0)) {
                case 'Z', 'B' -> 1;
                case 'S', 'C' -> 2;
                case 'I', 'F' -> 4;
                case 'J', 'D' -> 8;
                default -> 8;
            };
        }
        return 8;
    }

    private Type elemTypeFromConst(String s) {
        return switch (s) {
            case "boolean" -> Type.BOOLEAN;
            case "byte" -> Type.BYTE;
            case "short" -> Type.SHORT;
            case "char" -> Type.CHAR;
            case "int" -> Type.INT;
            case "long" -> Type.LONG;
            case "float" -> Type.FLOAT;
            case "double" -> Type.DOUBLE;
            default -> Type.fromDescriptor(s);
        };
    }

    private String emitInstruction(Instruction inst, List<TryCatchRange> ranges) {
        StringBuilder sb = new StringBuilder();
        Opcode op = inst.getOpcode();
        String resultName = null;
        if (inst.getResult() != null) {
            resultName = "%tmp_" + (tmpCounter++);
            valueMapper.setValue(inst.getResult(), resultName);
        }

        switch (op) {
            case LOAD: {
                int idx = inst.getLocalIndex();
                String llvmType = LlvmTypeMapper.toLlvmType(inst.getResult().getType());
                String ptr = newAux("ptrcast");
                sb.append("  ").append(ptr).append(" = bitcast i64* %local_").append(idx)
                    .append(" to ").append(llvmType).append("*\n");
                sb.append("  ").append(resultName).append(" = load ").append(llvmType)
                    .append(", ").append(llvmType).append("* ").append(ptr).append("\n");
                break;
            }

            case STORE: {
                Value stored = inst.getOperands().getFirst();
                int idx = inst.getLocalIndex();
                String valRef = getLlvmValue(sb, stored);
                Type storedType = stored.getType();
                Type localType = storedType;
                String llvmType = LlvmTypeMapper.toLlvmType(localType);
                String ptr = newAux("ptrcast");
                sb.append("  ").append(ptr).append(" = bitcast i64* %local_").append(idx)
                    .append(" to ").append(llvmType).append("*\n");
                sb.append("  store ").append(llvmType).append(" ").append(valRef)
                    .append(", ").append(llvmType).append("* ").append(ptr).append("\n");
                if (inst.getResult() != null) {
                    valueMapper.setValue(inst.getResult(), valRef);

                    String storedSlot = valueSlots.get(stored);
                    if (storedSlot != null) {
                        valueSlots.put(inst.getResult(), storedSlot);
                    }
                }
                break;
            }

            case ADD: case SUB: case MUL: case DIV: case REM:
            case AND: case OR: case XOR: case SHL: case SHR: case USHR: {
                Value left  = inst.getOperands().get(0);
                Value right = inst.getOperands().get(1);
                Type resType = inst.getResult().getType();

                if ((op == Opcode.DIV || op == Opcode.REM) && !ranges.isEmpty()) {
                    Type lt = left.getType();
                    if (lt == Type.INT || lt == Type.LONG) {
                        emitDivByZeroCheck(sb, right, ranges);
                    }
                }

                String  leftLlvm   = LlvmTypeMapper.toLlvmType(left.getType());
                String  rightLlvm  = LlvmTypeMapper.toLlvmType(right.getType());
                String  resLlvm    = LlvmTypeMapper.toLlvmType(resType);
                boolean leftIsPtr  = leftLlvm.endsWith("*");
                boolean rightIsPtr = rightLlvm.endsWith("*");
                boolean resIsPtr   = resLlvm.endsWith("*");

                boolean isBitwise = (op == Opcode.AND || op == Opcode.OR || op == Opcode.XOR
                    || op == Opcode.SHL || op == Opcode.SHR || op == Opcode.USHR);
                boolean anyPtr    = leftIsPtr || rightIsPtr || resIsPtr;

                if (isBitwise && (leftIsPtr || rightIsPtr)) {
                    Type intType = Type.LONG;
                    String lInt = castValueToType(sb, getLlvmValue(sb, left),  left.getType(),  intType);
                    String rInt = castValueToType(sb, getLlvmValue(sb, right), right.getType(), intType);
                    String llvmOp = mapArithOp(op, Type.LONG);
                    String tmp = newAux("bitwise_int");
                    sb.append("  ").append(tmp).append(" = ").append(llvmOp)
                        .append(" ").append(LlvmTypeMapper.toLlvmType(intType))
                        .append(" ").append(lInt).append(", ").append(rInt).append("\n");
                    String finalVal = castValueToType(sb, tmp, intType, resType);
                    if (resultName != null) {
                        valueMapper.setValue(inst.getResult(), finalVal);
                    }
                    break;
                }

                if (!isBitwise && anyPtr) {
                    String lInt = castValueToType(sb, getLlvmValue(sb, left),  left.getType(),  Type.LONG);
                    String rInt = castValueToType(sb, getLlvmValue(sb, right), right.getType(), Type.LONG);
                    String llvmOp = mapArithOp(op, Type.LONG);
                    String tmp = newAux("ptr_arith");
                    sb.append("  ").append(tmp).append(" = ").append(llvmOp)
                        .append(" i64 ").append(lInt).append(", ").append(rInt).append("\n");
                    String finalVal = castValueToType(sb, tmp, Type.LONG, resType);
                    if (resultName != null) {
                        valueMapper.setValue(inst.getResult(), finalVal);
                    }
                    break;
                }

                String l = castValueToType(sb, getLlvmValue(sb, left),  left.getType(),  resType);
                String r = castValueToType(sb, getLlvmValue(sb, right), right.getType(), resType);
                String llvmOp = mapArithOp(op, resType);
                sb.append("  ").append(resultName).append(" = ").append(llvmOp)
                    .append(" ").append(LlvmTypeMapper.toLlvmType(resType))
                    .append(" ").append(l).append(", ").append(r).append("\n");
                break;
            }
            case EQ: case NE: case LT: case LE: case GT: case GE: {
                Value left = inst.getOperands().get(0);
                Value right = inst.getOperands().get(1);
                Type leftType = left.getType();
                Type rightType = right.getType();
                String l = getLlvmValue(sb, left);
                String r = getLlvmValue(sb, right);

                boolean leftIsPtr = leftType.isReference() || leftType.isArray() || leftType.isNull() || leftType.isBlock();
                boolean rightIsPtr = rightType.isReference() || rightType.isArray() || rightType.isNull() || rightType.isBlock();

                if (leftIsPtr && "0".equals(r)) { r = "null"; }
                if (rightIsPtr && "0".equals(l)) { l = "null"; }

                if ((leftIsPtr && !rightIsPtr) || (!leftIsPtr && rightIsPtr)) {
                    l = castValueToType(sb, l, leftType, Type.LONG);
                    r = castValueToType(sb, r, rightType, Type.LONG);
                    leftType = Type.LONG;
                } else if (!leftIsPtr && !rightIsPtr) {
                    boolean leftIsFloat = leftType == Type.FLOAT || leftType == Type.DOUBLE;
                    boolean rightIsFloat = rightType == Type.FLOAT || rightType == Type.DOUBLE;
                    boolean leftIsInt = isIntegerType(leftType);
                    boolean rightIsInt = isIntegerType(rightType);

                    if ((leftIsFloat && rightIsInt) || (leftIsInt && rightIsFloat)) {
                        if (leftIsInt && rightIsFloat) {
                            l = castValueToType(sb, l, leftType, rightType);
                            leftType = rightType;
                        } else if (leftIsFloat && rightIsInt) {
                            r = castValueToType(sb, r, rightType, leftType);
                        }
                    } else if (leftIsInt && rightIsInt) {
                        l = castValueToType(sb, l, leftType, Type.LONG);
                        r = castValueToType(sb, r, rightType, Type.LONG);
                        leftType = Type.LONG;
                    } else if (!leftType.equals(rightType)) {
                        r = castValueToType(sb, r, rightType, leftType);
                    }
                }

                boolean isFloat = leftType == Type.FLOAT || leftType == Type.DOUBLE;
                String instr = isFloat ? "fcmp" : "icmp";
                String pred = mapCmpOp(op, isFloat);
                sb.append("  ").append(resultName).append(" = ").append(instr)
                    .append(" ").append(pred)
                    .append(" ").append(LlvmTypeMapper.toLlvmType(leftType))
                    .append(" ").append(l).append(", ").append(r).append("\n");
                break;
            }

            case CAST: {
                Value val = inst.getOperands().getFirst();
                Type srcType = val.getType();
                Type destType = inst.getResult().getType();
                String casted = castValueToType(sb, getLlvmValue(sb, val), srcType, destType);
                valueMapper.setValue(inst.getResult(), casted);
                break;
            }
            case GET_FIELD: {
                Value base = inst.getOperands().getFirst();
                Value fieldOperand = inst.getOperands().size() > 1 ? inst.getOperands().get(1) : null;

                if (fieldOperand instanceof Constant offc && offc.getType() == Type.INT) {
                    int offset = ((Number) offc.getValue()).intValue();

                    emitNullCheck(sb, base, ranges);

                    String baseI8 = emitBaseToI8Pointer(sb, base);

                    String gep = newAux("gep");
                    sb.append("  ").append(gep).append(" = getelementptr i8, i8* ")
                        .append(baseI8).append(", i32 ").append(offset).append("\n");

                    Type fieldType = inst.getResult().getType();
                    String fieldLlvm = LlvmTypeMapper.toLlvmType(fieldType);
                    String ptrCast = newAux("ptrcast");
                    sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(gep)
                        .append(" to ").append(fieldLlvm).append("*\n");
                    sb.append("  ").append(resultName).append(" = load ").append(fieldLlvm)
                        .append(", ").append(fieldLlvm).append("* ").append(ptrCast).append("\n");
                    break;
                }

                String fullFieldName = extractFieldName(inst);
                String owner;
                String fieldName;
                int lastDot = fullFieldName.lastIndexOf('.');
                if (lastDot > 0) {
                    owner = fullFieldName.substring(0, lastDot);
                    fieldName = fullFieldName.substring(lastDot + 1);
                } else {
                    owner = extractClassName(base);
                    fieldName = fullFieldName;
                }

                int offset = globalEmitter.getFieldOffset(owner, fieldName);

                emitNullCheck(sb, base, ranges);

                String baseI8 = emitBaseToI8Pointer(sb, base);

                String gep = newAux("gep");
                sb.append("  ").append(gep).append(" = getelementptr i8, i8* ").append(baseI8)
                    .append(", i32 ").append(offset).append("\n");

                Type fieldType = inst.getResult().getType();
                String fieldLlvm = LlvmTypeMapper.toLlvmType(fieldType);
                String ptrCast = newAux("ptrcast");
                sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(gep)
                    .append(" to ").append(fieldLlvm).append("*\n");
                sb.append("  ").append(resultName).append(" = load ").append(fieldLlvm)
                    .append(", ").append(fieldLlvm).append("* ").append(ptrCast).append("\n");
                break;
            }

            case PUT_FIELD: {
                if (inst.getOperands().size() < 3) {
                    break;
                }

                Value base = inst.getOperands().get(0);
                Value rhs  = inst.getOperands().get(2);
                Value fieldOperand = inst.getOperands().get(1);

                if (fieldOperand instanceof Constant offc && offc.getType() == Type.INT) {
                    int offset = ((Number) offc.getValue()).intValue();

                    emitNullCheck(sb, base, ranges);

                    String baseI8 = emitBaseToI8Pointer(sb, base);

                    String gep = newAux("gep");
                    sb.append("  ").append(gep).append(" = getelementptr i8, i8* ")
                        .append(baseI8).append(", i32 ").append(offset).append("\n");

                    Type fieldType = rhs.getType();
                    String fieldLlvm = LlvmTypeMapper.toLlvmType(fieldType);
                    String rhsRef = getLlvmValue(sb, rhs);
                    String rhsConverted = castValueToType(sb, rhsRef, rhs.getType(), fieldType);
                    String ptrCast = newAux("ptrcast");
                    sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(gep)
                        .append(" to ").append(fieldLlvm).append("*\n");
                    sb.append("  store ").append(fieldLlvm).append(" ").append(rhsConverted)
                        .append(", ").append(fieldLlvm).append("* ").append(ptrCast).append("\n");
                    break;
                }

                String fullFieldName = extractFieldName(inst);
                String owner;
                String fieldName;
                int lastDot = fullFieldName.lastIndexOf('.');
                if (lastDot > 0) {
                    owner = fullFieldName.substring(0, lastDot);
                    fieldName = fullFieldName.substring(lastDot + 1);
                } else {
                    owner = extractClassName(base);
                    fieldName = fullFieldName;
                }

                int offset = globalEmitter.getFieldOffset(owner, fieldName);

                emitNullCheck(sb, base, ranges);

                String baseI8 = emitBaseToI8Pointer(sb, base);

                String gep = newAux("gep");
                sb.append("  ").append(gep).append(" = getelementptr i8, i8* ").append(baseI8)
                    .append(", i32 ").append(offset).append("\n");

                Type fieldType = globalEmitter.getFieldType(owner, fieldName);
                if (fieldType == null) {
                    fieldType = rhs.getType();
                }
                String fieldLlvm = LlvmTypeMapper.toLlvmType(fieldType);
                String rhsRef = getLlvmValue(sb, rhs);
                String rhsConverted = castValueToType(sb, rhsRef, rhs.getType(), fieldType);
                String ptrCast = newAux("ptrcast");
                sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(gep)
                    .append(" to ").append(fieldLlvm).append("*\n");
                sb.append("  store ").append(fieldLlvm).append(" ").append(rhsConverted)
                    .append(", ").append(fieldLlvm).append("* ").append(ptrCast).append("\n");
                break;
            }

            case GET_STATIC: {
                String fieldName = extractFieldName(inst);
                String globalName = "gv_" + LlvmTypeMapper.sanitizeIdentifier(fieldName);

                String owner;
                String bareField;
                int lastDot = fieldName.lastIndexOf('.');
                if (lastDot > 0) {
                    owner = fieldName.substring(0, lastDot);
                    bareField = fieldName.substring(lastDot + 1);
                } else {
                    owner = "";
                    bareField = fieldName;
                }
                Type fieldType = globalEmitter.getFieldType(owner, bareField);
                if (fieldType == null) {
                    fieldType = inst.getResult().getType();
                }
                String llvmType = LlvmTypeMapper.toLlvmType(fieldType);
                sb.append("  ").append(resultName).append(" = load ")
                    .append(llvmType).append(", ")
                    .append(llvmType).append("* @").append(globalName).append("\n");
                break;
            }

            case PUT_STATIC: {
                if (inst.getOperands().size() >= 2) {
                    Value rhs = inst.getOperands().get(1);
                    String fieldName = extractFieldName(inst);
                    String globalName = "gv_" + LlvmTypeMapper.sanitizeIdentifier(fieldName);

                    String owner;
                    String bareField;
                    int lastDot = fieldName.lastIndexOf('.');
                    if (lastDot > 0) {
                        owner = fieldName.substring(0, lastDot);
                        bareField = fieldName.substring(lastDot + 1);
                    } else {
                        owner = "";
                        bareField = fieldName;
                    }
                    Type fieldType = globalEmitter.getFieldType(owner, bareField);
                    if (fieldType == null) {
                        fieldType = rhs.getType();
                    }

                    String rhsRef = getLlvmValue(sb, rhs);
                    String rhsConverted = castValueToType(sb, rhsRef, rhs.getType(), fieldType);
                    String llvmType = LlvmTypeMapper.toLlvmType(fieldType);

                    sb.append("  store ").append(llvmType).append(" ").append(rhsConverted)
                        .append(", ").append(llvmType).append("* @").append(globalName).append("\n");
                }
                break;
            }

            case VIRTUAL_CALL:
            case INTERFACE_CALL: {
                List<Value> operands = inst.getOperands();
                if (operands.size() < 2) break;

                Value receiver = operands.get(0);
                Value calleeConst = operands.get(1);
                if (!(calleeConst instanceof Constant)) break;

                String calleeName = ((Constant) calleeConst).getValue().toString();

                int dotIdx = calleeName.lastIndexOf('.');
                if (dotIdx < 0) break;
                String owner = calleeName.substring(0, dotIdx);
                String sig = calleeName.substring(dotIdx + 1);
                int parenIdx = sig.indexOf('(');
                if (parenIdx < 0) break;
                String methodName = sig.substring(0, parenIdx);
                String methodDesc = sig.substring(parenIdx);

                Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;

                // ---------- Polymorphic signature handling ----------
                if (inst.isPolymorphicSignature()) {
                    List<Value> allArgs = new ArrayList<>();
                    allArgs.add(receiver);
                    for (int i = 2; i < operands.size(); i++) {
                        allArgs.add(operands.get(i));
                    }
                    List<Value> argsWithoutReceiver = allArgs.subList(1, allArgs.size());
                    List<Type> paramTypes = argsWithoutReceiver.stream()
                        .map(Value::getType)
                        .collect(Collectors.toList());

                    NativeMethodInfo best = polymorphicResolver.findBestMatch(owner, methodName, retType, paramTypes);
                    if (best != null) {
                        String expectedDesc = buildDescriptor(retType, paramTypes);
                        if (expectedDesc.equals(best.getDescriptor())) {
                            String funcName = best.getFullFunctionName();
                            Function func = ensurePolymorphicFunctionDeclared(best);
                            final List<Value> argsForCall = argsWithoutReceiver;
                            final Function calleeForCall = func;
                            emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                                StringBuilder argList = new StringBuilder();
                                for (int i = 0; i < argsForCall.size(); i++) {
                                    if (i > 0) argList.append(", ");
                                    Value arg = argsForCall.get(i);
                                    Type paramType = (calleeForCall != null && i < calleeForCall.getParameters().size())
                                        ? calleeForCall.getParameters().get(i).getType()
                                        : arg.getType();
                                    String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                                    argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                        .append(" ").append(val);
                                }
                                if (resultReg != null) {
                                    callSb.append("  ").append(resultReg).append(" = call ")
                                        .append(LlvmTypeMapper.toLlvmType(retType)).append(" @").append(funcName)
                                        .append("(").append(argList).append(")\n");
                                } else {
                                    callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType))
                                        .append(" @").append(funcName).append("(").append(argList).append(")\n");
                                }
                            });
                            break;
                        } else {
                            emitPolymorphicCall(sb, allArgs, best, retType, paramTypes, ranges, resultName);
                            break;
                        }
                    }

                    String nativeSymbol = "__jnative_"
                        + LlvmRuntime.mangleMethod(owner, methodName, methodDesc);

                    Function nativeFunc = module.getFunction(nativeSymbol);
                    if (nativeFunc == null) {
                        Type nRetType = TypeResolver.descToReturnType(methodDesc);
                        List<Type> nParamTypes = TypeResolver.descToParamTypes(methodDesc);
                        nativeFunc = new Function(nativeSymbol, nRetType);
                        for (int i = 0; i < nParamTypes.size(); i++) {
                            nativeFunc.addParameter(new Parameter(nParamTypes.get(i), i));
                        }
                        module.addFunction(nativeFunc);
                    }

                    final List<Value> argsForCall = argsWithoutReceiver;
                    final Function nativeFuncForCall = nativeFunc;
                    final String nativeSymbolFinal = nativeSymbol;
                    emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                        StringBuilder argList = new StringBuilder();
                        for (int i = 0; i < argsForCall.size(); i++) {
                            if (i > 0) argList.append(", ");
                            Value arg = argsForCall.get(i);
                            Type paramType = (i < nativeFuncForCall.getParameters().size())
                                ? nativeFuncForCall.getParameters().get(i).getType()
                                : arg.getType();
                            String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                            argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                .append(" ").append(val);
                        }
                        if (resultReg != null) {
                            callSb.append("  ").append(resultReg).append(" = call ")
                                .append(LlvmTypeMapper.toLlvmType(retType)).append(" @").append(nativeSymbolFinal)
                                .append("(").append(argList).append(")\n");
                        } else {
                            callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType))
                                .append(" @").append(nativeSymbolFinal).append("(").append(argList).append(")\n");
                        }
                    });
                    break;
                }

                // ---------- Native candidate (direct resolution, bypasses vtable) ----------
                String nativeCandidate = "__jnative_" + LlvmRuntime.mangleCallable(calleeName);
                Function nativeFunc = module.getFunction(nativeCandidate);
                if (nativeFunc != null) {
                    List<Value> args = new ArrayList<>();
                    args.add(receiver);
                    for (int i = 2; i < operands.size(); i++) args.add(operands.get(i));
                    final List<Value> argsForCall = args;
                    final Function nativeFuncForCall = nativeFunc;
                    final String nativeCandidateFinal = nativeCandidate;
                    emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                        StringBuilder argList = new StringBuilder();
                        for (int i = 0; i < argsForCall.size(); i++) {
                            if (i > 0) argList.append(", ");
                            Value arg = argsForCall.get(i);
                            Type paramType = (i < nativeFuncForCall.getParameters().size())
                                ? nativeFuncForCall.getParameters().get(i).getType()
                                : arg.getType();
                            String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                            argList.append(LlvmTypeMapper.toLlvmType(paramType)).append(" ").append(val);
                        }
                        if (resultReg != null) {
                            callSb.append("  ").append(resultReg).append(" = call ")
                                .append(LlvmTypeMapper.toLlvmType(retType)).append(" @").append(nativeCandidateFinal)
                                .append("(").append(argList).append(")\n");
                        } else {
                            callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType))
                                .append(" @").append(nativeCandidateFinal).append("(").append(argList).append(")\n");
                        }
                    });
                    break;
                }

                // ---------- Dispatch ----------
                List<Type> paramTypes = TypeResolver.descToParamTypes(methodDesc);
                Type receiverType = Type.reference(owner);
                List<Value> allArgs = new ArrayList<>();
                allArgs.add(receiver);
                for (int i = 2; i < operands.size(); i++) allArgs.add(operands.get(i));

                boolean receiverIsRef = receiver.getType().isReference()
                    || receiver.getType().isArray()
                    || receiver.getType().isNull()
                    || receiver.getType().isBlock()
                    || receiver.getType().isUnknown();

                boolean isInterfaceCall = (op == Opcode.INTERFACE_CALL);

                int dispatchSlot;
                int interfaceId = -1;
                if (isInterfaceCall) {
                    interfaceId  = globalEmitter.getInterfaceId(owner);
                    dispatchSlot = globalEmitter.getInterfaceMethodSlot(owner, methodName, methodDesc);
                } else {
                    dispatchSlot = globalEmitter.getVirtualSlot(owner, methodName, methodDesc);
                }

                if (!receiverIsRef || dispatchSlot < 0 || (isInterfaceCall && interfaceId < 0)) {
                    String directMangled = LlvmRuntime.mangleMethod(owner, methodName, methodDesc);
                    Function concrete = module.getFunction(directMangled);

                    if (concrete != null && concrete.getEntryBlock() != null) {
                        final List<Value> argsForCall = allArgs;
                        final Function calleeForCall = concrete;
                        final Type receiverTypeFinal = receiverType;
                        final List<Type> paramTypesFinal = paramTypes;
                        emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                            StringBuilder argList = new StringBuilder();
                            for (int i = 0; i < argsForCall.size(); i++) {
                                if (i > 0) argList.append(", ");
                                Value arg = argsForCall.get(i);
                                Type paramType;
                                if (calleeForCall != null && i < calleeForCall.getParameters().size()) {
                                    paramType = calleeForCall.getParameters().get(i).getType();
                                } else if (i == 0) {
                                    paramType = receiverTypeFinal;
                                } else if (i - 1 < paramTypesFinal.size()) {
                                    paramType = paramTypesFinal.get(i - 1);
                                } else {
                                    paramType = arg.getType();
                                }
                                String val = castValueToType(callSb, getLlvmValue(callSb, arg),
                                    arg.getType(), paramType);
                                argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                    .append(" ").append(val);
                            }
                            if (resultReg != null) {
                                callSb.append("  ").append(resultReg).append(" = call ")
                                    .append(LlvmTypeMapper.toLlvmType(retType))
                                    .append(" @").append(directMangled)
                                    .append("(").append(argList).append(")\n");
                            } else {
                                callSb.append("  call ")
                                    .append(LlvmTypeMapper.toLlvmType(retType))
                                    .append(" @").append(directMangled)
                                    .append("(").append(argList).append(")\n");
                            }
                        });
                        break;
                    }

                    // No concrete body in the module.  Emit an unconditional
                    // call to the runtime's trap helper and mark the block
                    // unreachable.  The trap is not covered by the enclosing
                    // try-guards: it represents a codegen / link-time error,
                    // not a Java exception, and must abort rather than be
                    // catchable from Java code.
                    String msg = (isInterfaceCall
                        ? "interface '" + owner + "'"
                        : "class '" + owner + "'")
                        + ", method '" + methodName + methodDesc + "'"
                        + " (no dispatch slot; no direct body)";
                    String msgRef = stringRef(msg);
                    sb.append("  call void @__jnative_unresolved_slot(i8* ")
                        .append(msgRef).append(")\n");
                    sb.append("  unreachable\n");
                    if (inst.getResult() != null) {
                        valueMapper.setValue(inst.getResult(),
                            getDefaultValue(inst.getResult().getType()));
                    }
                    break;
                }

                emitNullCheck(sb, receiver, ranges);
                String receiverRef = getLlvmValue(sb, receiver);

                String vtSlotPtr = newAux("vtslot");
                sb.append("  ").append(vtSlotPtr).append(" = bitcast ")
                    .append(LlvmTypeMapper.toLlvmType(receiver.getType())).append(" ").append(receiverRef)
                    .append(" to %JNativeVTable**\n");

                String vtableStruct = newAux("vtable_struct");
                sb.append("  ").append(vtableStruct)
                    .append(" = load %JNativeVTable*, %JNativeVTable** ").append(vtSlotPtr).append("\n");

                String funcPtr;
                if (isInterfaceCall) {
                    String ifacemapFieldPtr = newAux("ifacemap_field_ptr");
                    sb.append("  ").append(ifacemapFieldPtr)
                        .append(" = getelementptr %JNativeVTable, %JNativeVTable* ")
                        .append(vtableStruct).append(", i32 0, i32 1\n");

                    String ifacemapPtr = newAux("ifacemap_ptr");
                    sb.append("  ").append(ifacemapPtr)
                        .append(" = load %JNativeIfaceMap*, %JNativeIfaceMap** ")
                        .append(ifacemapFieldPtr).append("\n");

                    String itablePtr = newAux("itable_ptr");
                    sb.append("  ").append(itablePtr)
                        .append(" = call i8** @__jnative_lookup_itable(%JNativeIfaceMap* ")
                        .append(ifacemapPtr).append(", i32 ").append(interfaceId).append(")\n");

                    String funcPtrGep = newAux("funcptr_gep");
                    sb.append("  ").append(funcPtrGep)
                        .append(" = getelementptr i8*, i8** ").append(itablePtr)
                        .append(", i32 ").append(dispatchSlot).append("\n");

                    funcPtr = newAux("funcptr");
                    sb.append("  ").append(funcPtr)
                        .append(" = load i8*, i8** ").append(funcPtrGep).append("\n");
                } else {
                    String methodsFieldPtr = newAux("methods_field_ptr");
                    sb.append("  ").append(methodsFieldPtr)
                        .append(" = getelementptr %JNativeVTable, %JNativeVTable* ")
                        .append(vtableStruct).append(", i32 0, i32 0\n");

                    String methodsArr = newAux("methods_arr");
                    sb.append("  ").append(methodsArr)
                        .append(" = load i8**, i8*** ").append(methodsFieldPtr).append("\n");

                    String funcPtrGep = newAux("funcptr_gep");
                    sb.append("  ").append(funcPtrGep)
                        .append(" = getelementptr i8*, i8** ").append(methodsArr)
                        .append(", i32 ").append(dispatchSlot).append("\n");

                    funcPtr = newAux("funcptr");
                    sb.append("  ").append(funcPtr)
                        .append(" = load i8*, i8* ").append(funcPtrGep).append("\n");
                }

                String funcType = LlvmRuntime.getFunctionType(owner, sig);
                String funcPtrCast = newAux("fptrcast");
                sb.append("  ").append(funcPtrCast).append(" = bitcast i8* ").append(funcPtr)
                    .append(" to ").append(funcType).append("\n");

                final List<Value> argsForCall = allArgs;
                final Type receiverTypeFinal = receiverType;
                final List<Type> paramTypesFinal = paramTypes;
                final String funcPtrCastFinal = funcPtrCast;
                emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                    StringBuilder argList = new StringBuilder();
                    for (int i = 0; i < argsForCall.size(); i++) {
                        if (i > 0) argList.append(", ");
                        Value arg = argsForCall.get(i);
                        Type paramType;
                        if (i == 0) paramType = receiverTypeFinal;
                        else if (i - 1 < paramTypesFinal.size()) paramType = paramTypesFinal.get(i - 1);
                        else paramType = arg.getType();
                        String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                        argList.append(LlvmTypeMapper.toLlvmType(paramType)).append(" ").append(val);
                    }
                    if (resultReg != null) {
                        callSb.append("  ").append(resultReg).append(" = call ")
                            .append(LlvmTypeMapper.toLlvmType(retType)).append(" ").append(funcPtrCastFinal)
                            .append("(").append(argList).append(")\n");
                    } else {
                        callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType)).append(" ")
                            .append(funcPtrCastFinal).append("(").append(argList).append(")\n");
                    }
                });
                break;
            }

            case STATIC_CALL:
            case CALL: {
                String calleeName = extractCalleeName(inst);
                if (calleeName == null) break;

                if (op == Opcode.STATIC_CALL) {
                    int dotIdx = calleeName.lastIndexOf('.');
                    int parenIdx = calleeName.indexOf('(');
                    if (dotIdx > 0 && parenIdx > dotIdx) {
                        String owner = calleeName.substring(0, dotIdx);
                        String methodPart = calleeName.substring(dotIdx + 1);
                        int localParenIdx = parenIdx - dotIdx - 1;
                        String methodName = methodPart.substring(0, localParenIdx);
                        String descriptor = methodPart.substring(localParenIdx);
                        String[] foundOwner = new String[1];
                        io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode mn =
                            resolver.findMethodInHierarchy(owner, methodName, descriptor, foundOwner);
                        if (mn != null && mn.isStatic()
                            && foundOwner[0] != null && !foundOwner[0].equals(owner)) {
                            calleeName = foundOwner[0] + "." + methodName + descriptor;
                        }
                    }
                }

                if (inst.isPolymorphicSignature()) {
                    int dotIdx = calleeName.lastIndexOf('.');
                    int parenIdx = calleeName.indexOf('(');
                    if (dotIdx > 0 && parenIdx > dotIdx) {
                        String owner = calleeName.substring(0, dotIdx);
                        String methodPart = calleeName.substring(dotIdx + 1);
                        int localParenIdx = parenIdx - dotIdx - 1;
                        String methodName = methodPart.substring(0, localParenIdx);

                        Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;
                        List<Value> args = getCallArguments(inst);
                        List<Type> paramTypes = args.stream().map(Value::getType).collect(Collectors.toList());

                        NativeMethodInfo best = polymorphicResolver.findBestMatch(
                            owner, methodName, retType, paramTypes);
                        if (best != null) {
                            String expectedDesc = buildDescriptor(retType, paramTypes);
                            if (expectedDesc.equals(best.getDescriptor())) {
                                String funcName = best.getFullFunctionName();
                                Function func = ensurePolymorphicFunctionDeclared(best);
                                final List<Value> argsForCall = args;
                                final Function calleeForCall = func;
                                emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                                    StringBuilder argList = new StringBuilder();
                                    for (int i = 0; i < argsForCall.size(); i++) {
                                        if (i > 0) argList.append(", ");
                                        Value arg = argsForCall.get(i);
                                        Type paramType = (calleeForCall != null && i < calleeForCall.getParameters().size())
                                            ? calleeForCall.getParameters().get(i).getType()
                                            : arg.getType();
                                        String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                                        argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                            .append(" ").append(val);
                                    }
                                    if (resultReg != null) {
                                        callSb.append("  ").append(resultReg).append(" = call ")
                                            .append(LlvmTypeMapper.toLlvmType(retType)).append(" @").append(funcName)
                                            .append("(").append(argList).append(")\n");
                                    } else {
                                        callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType))
                                            .append(" @").append(funcName).append("(").append(argList).append(")\n");
                                    }
                                });
                                break;
                            } else {
                                emitPolymorphicCall(sb, args, best, retType, paramTypes,
                                    ranges, resultName);
                                break;
                            }
                        }
                    }
                }

                Function calleeFunc = module.getFunction(calleeName);
                String mangledCallee;
                if (calleeFunc != null) {
                    mangledCallee = calleeFunc.getName();
                } else if (calleeName.startsWith("__jnative_")) {
                    mangledCallee = calleeName;
                } else {
                    String nativeCandidate = "__jnative_" + LlvmRuntime.mangleCallable(calleeName);
                    if (module.getFunction(nativeCandidate) != null) {
                        mangledCallee = nativeCandidate;
                    } else {
                        String nativeSymbol = null;
                        int dotIdx = calleeName.lastIndexOf('.');
                        int parenIdx = calleeName.indexOf('(');
                        if (dotIdx > 0 && parenIdx > dotIdx) {
                            String owner = calleeName.substring(0, dotIdx);
                            String methodPart = calleeName.substring(dotIdx + 1);
                            int localParenIdx = parenIdx - dotIdx - 1;
                            String methodName = methodPart.substring(0, localParenIdx);
                            String descriptor = methodPart.substring(localParenIdx);

                            String[] foundOwner = new String[1];
                            io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode mn =
                                resolver.findMethodInHierarchy(owner, methodName, descriptor, foundOwner);
                            if (mn != null && mn.isNative()) {
                                String actualOwner =
                                    foundOwner[0] != null ? foundOwner[0] : owner;
                                nativeSymbol = "__jnative_"
                                    + LlvmRuntime.mangleMethod(actualOwner, methodName, descriptor);
                            }
                        }

                        if (nativeSymbol != null) {
                            if (module.getFunction(nativeSymbol) == null) {
                                int paren2 = calleeName.indexOf('(');
                                String desc2 = calleeName.substring(paren2);
                                Type nRetType = TypeResolver.descToReturnType(desc2);
                                List<Type> nParamTypes = TypeResolver.descToParamTypes(desc2);
                                Function nFunc = new Function(nativeSymbol, nRetType);
                                for (int i = 0; i < nParamTypes.size(); i++) {
                                    nFunc.addParameter(new Parameter(nParamTypes.get(i), i));
                                }
                                module.addFunction(nFunc);
                            }
                            mangledCallee = nativeSymbol;
                        } else {
                            if (dotIdx > 0 && parenIdx > dotIdx) {
                                String owner = calleeName.substring(0, dotIdx);
                                String methodPart = calleeName.substring(dotIdx + 1);
                                int localParenIdx = parenIdx - dotIdx - 1;
                                String methodName = methodPart.substring(0, localParenIdx);
                                String descriptor = methodPart.substring(localParenIdx);
                                ensureFunctionDeclared(owner, methodName, descriptor);
                            }
                            mangledCallee = LlvmRuntime.mangleCallable(calleeName);
                        }
                    }
                }

                if (!isCallableSymbolDefined(mangledCallee)) {
                    System.err.println("[jnative] WARN: skipping call to undefined symbol '"
                        + mangledCallee + "' (original: " + calleeName + ")");
                    if (inst.getResult() != null) {
                        valueMapper.setValue(inst.getResult(),
                            getDefaultValue(inst.getResult().getType()));
                    }
                    break;
                }

                List<Value> args = getCallArguments(inst);

                if (calleeFunc != null) {
                    int expected = calleeFunc.getParameters().size();
                    if (args.size() > expected) {
                        boolean removeFirst = false;
                        if (expected == 0) {
                            removeFirst = true;
                        } else {
                            Type firstArgType = args.getFirst().getType();
                            Type firstParamType = calleeFunc.getParameters().getFirst().getType();
                            if (firstArgType.isReference() && !firstParamType.isReference()) {
                                removeFirst = true;
                            }
                        }
                        if (removeFirst) {
                            args = args.subList(1, args.size());
                        }
                    }
                }

                Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;
                final List<Value> argsForCall = args;
                final Function calleeForCall = calleeFunc;
                final String mangledCalleeFinal = mangledCallee;
                final Type retTypeForCall = retType;
                emitCall(sb, retTypeForCall, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                    StringBuilder argList = new StringBuilder();
                    for (int i = 0; i < argsForCall.size(); i++) {
                        if (i > 0) argList.append(", ");
                        Value arg = argsForCall.get(i);
                        Type paramType;
                        if (calleeForCall != null && i < calleeForCall.getParameters().size()) {
                            paramType = calleeForCall.getParameters().get(i).getType();
                        } else {
                            paramType = arg.getType();
                        }
                        String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                        argList.append(LlvmTypeMapper.toLlvmType(paramType)).append(" ").append(val);
                    }
                    if (resultReg != null) {
                        callSb.append("  ").append(resultReg).append(" = call ")
                            .append(LlvmTypeMapper.toLlvmType(retTypeForCall)).append(" @").append(mangledCalleeFinal)
                            .append("(").append(argList).append(")\n");
                    } else {
                        callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retTypeForCall))
                            .append(" @").append(mangledCalleeFinal).append("(").append(argList).append(")\n");
                    }
                });
                break;
            }

            case SPECIAL_CALL: {
                if (inst.getOperands().size() < 2) break;
                Value receiver = inst.getOperands().get(0);
                Value calleeConst = inst.getOperands().get(1);
                if (!(calleeConst instanceof Constant)) break;
                String calleeName = ((Constant) calleeConst).getValue().toString();

                if (inst.isPolymorphicSignature()) {
                    int dotIdx = calleeName.lastIndexOf('.');
                    int parenIdx = calleeName.indexOf('(');
                    if (dotIdx > 0 && parenIdx > dotIdx) {
                        String owner = calleeName.substring(0, dotIdx);
                        String methodPart = calleeName.substring(dotIdx + 1);
                        int localParenIdx = parenIdx - dotIdx - 1;
                        String methodName = methodPart.substring(0, localParenIdx);

                        List<Value> allArgs = new ArrayList<>();
                        allArgs.add(receiver);
                        for (int i = 2; i < inst.getOperands().size(); i++) {
                            allArgs.add(inst.getOperands().get(i));
                        }
                        List<Value> argsWithoutReceiver = allArgs.subList(1, allArgs.size());
                        Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;
                        List<Type> paramTypes = argsWithoutReceiver.stream()
                            .map(Value::getType).collect(Collectors.toList());

                        NativeMethodInfo best = polymorphicResolver.findBestMatch(
                            owner, methodName, retType, paramTypes);
                        if (best != null) {
                            String expectedDesc = buildDescriptor(retType, paramTypes);
                            if (expectedDesc.equals(best.getDescriptor())) {
                                String funcName = best.getFullFunctionName();
                                Function func = ensurePolymorphicFunctionDeclared(best);
                                final List<Value> argsForCall = argsWithoutReceiver;
                                final Function calleeForCall = func;
                                emitCall(sb, retType, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                                    StringBuilder argList = new StringBuilder();
                                    for (int i = 0; i < argsForCall.size(); i++) {
                                        if (i > 0) argList.append(", ");
                                        Value arg = argsForCall.get(i);
                                        Type paramType = (calleeForCall != null && i < calleeForCall.getParameters().size())
                                            ? calleeForCall.getParameters().get(i).getType()
                                            : arg.getType();
                                        String val = castValueToType(callSb, getLlvmValue(callSb, arg),
                                            arg.getType(), paramType);
                                        argList.append(LlvmTypeMapper.toLlvmType(paramType))
                                            .append(" ").append(val);
                                    }
                                    if (resultReg != null) {
                                        callSb.append("  ").append(resultReg).append(" = call ")
                                            .append(LlvmTypeMapper.toLlvmType(retType)).append(" @").append(funcName)
                                            .append("(").append(argList).append(")\n");
                                    } else {
                                        callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retType))
                                            .append(" @").append(funcName).append("(").append(argList).append(")\n");
                                    }
                                });
                                break;
                            } else {
                                emitPolymorphicCall(sb, allArgs, best, retType, paramTypes,
                                    ranges, resultName);
                                break;
                            }
                        }
                    }
                }

                Function calleeFunc = module.getFunction(calleeName);
                String mangledCallee;
                if (calleeFunc != null) {
                    mangledCallee = calleeFunc.getName();
                } else {
                    String nativeCandidate = "__jnative_" + LlvmRuntime.mangleCallable(calleeName);
                    if (module.getFunction(nativeCandidate) != null) {
                        mangledCallee = nativeCandidate;
                    } else {
                        String nativeSymbol = null;
                        int dotIdx = calleeName.lastIndexOf('.');
                        int parenIdx = calleeName.indexOf('(');
                        if (dotIdx > 0 && parenIdx > dotIdx) {
                            String owner = calleeName.substring(0, dotIdx);
                            String methodPart = calleeName.substring(dotIdx + 1);
                            int localParenIdx = parenIdx - dotIdx - 1;
                            String methodName = methodPart.substring(0, localParenIdx);
                            String descriptor = methodPart.substring(localParenIdx);

                            String[] foundOwner = new String[1];
                            io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode mn =
                                resolver.findMethodInHierarchy(owner, methodName, descriptor, foundOwner);
                            if (mn != null && mn.isNative()) {
                                String actualOwner =
                                    foundOwner[0] != null ? foundOwner[0] : owner;
                                nativeSymbol = "__jnative_"
                                    + LlvmRuntime.mangleMethod(actualOwner, methodName, descriptor);
                            }
                        }

                        if (nativeSymbol != null) {
                            if (module.getFunction(nativeSymbol) == null) {
                                int paren2 = calleeName.indexOf('(');
                                String desc2 = calleeName.substring(paren2);
                                Type nRetType = TypeResolver.descToReturnType(desc2);
                                List<Type> nParamTypes = TypeResolver.descToParamTypes(desc2);
                                Function nFunc = new Function(nativeSymbol, nRetType);
                                for (int i = 0; i < nParamTypes.size(); i++) {
                                    nFunc.addParameter(new Parameter(nParamTypes.get(i), i));
                                }
                                module.addFunction(nFunc);
                            }
                            mangledCallee = nativeSymbol;
                        } else {
                            if (dotIdx > 0 && parenIdx > dotIdx) {
                                String owner = calleeName.substring(0, dotIdx);
                                String methodPart = calleeName.substring(dotIdx + 1);
                                int localParenIdx = parenIdx - dotIdx - 1;
                                String methodName = methodPart.substring(0, localParenIdx);
                                String descriptor = methodPart.substring(localParenIdx);
                                ensureFunctionDeclared(owner, methodName, descriptor);
                            }
                            mangledCallee = LlvmRuntime.mangleCallable(calleeName);
                        }
                    }
                }

                if (!isCallableSymbolDefined(mangledCallee)) {
                    System.err.println("[jnative] WARN: skipping SPECIAL_CALL to undefined symbol '"
                        + mangledCallee + "' (original: " + calleeName + ")");
                    if (inst.getResult() != null) {
                        valueMapper.setValue(inst.getResult(),
                            getDefaultValue(inst.getResult().getType()));
                    }
                    break;
                }

                List<Value> args = new ArrayList<>();
                args.add(receiver);
                for (int i = 2; i < inst.getOperands().size(); i++) {
                    args.add(inst.getOperands().get(i));
                }

                Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;
                final List<Value> argsForCall = args;
                final Function calleeForCall = calleeFunc;
                final String mangledCalleeFinal = mangledCallee;
                final Type retTypeForCall = retType;
                emitCall(sb, retTypeForCall, inst.getResult(), resultName, ranges, (callSb, resultReg) -> {
                    StringBuilder argList = new StringBuilder();
                    for (int i = 0; i < argsForCall.size(); i++) {
                        if (i > 0) argList.append(", ");
                        Value arg = argsForCall.get(i);
                        Type paramType;
                        if (calleeForCall != null && i < calleeForCall.getParameters().size()) {
                            paramType = calleeForCall.getParameters().get(i).getType();
                        } else {
                            paramType = arg.getType();
                        }
                        String val = castValueToType(callSb, getLlvmValue(callSb, arg), arg.getType(), paramType);
                        argList.append(LlvmTypeMapper.toLlvmType(paramType)).append(" ").append(val);
                    }
                    if (resultReg != null) {
                        callSb.append("  ").append(resultReg).append(" = call ")
                            .append(LlvmTypeMapper.toLlvmType(retTypeForCall)).append(" @").append(mangledCalleeFinal)
                            .append("(").append(argList).append(")\n");
                    } else {
                        callSb.append("  call ").append(LlvmTypeMapper.toLlvmType(retTypeForCall))
                            .append(" @").append(mangledCalleeFinal).append("(").append(argList).append(")\n");
                    }
                });
                break;
            }

            case NEW: {
                String className = extractTypeName(inst);
                String structType = globalEmitter.getStructName(className);

                String gepReg = newAux("alloc_gep");
                sb.append("  ").append(gepReg)
                    .append(" = getelementptr ").append(structType)
                    .append(", ").append(structType).append("* null, i32 1\n");

                String sizeReg = newAux("alloc_size");
                sb.append("  ").append(sizeReg)
                    .append(" = ptrtoint ").append(structType)
                    .append("* ").append(gepReg).append(" to i64\n");

                String allocReg = newAux("alloc");
                sb.append("  ").append(allocReg)
                    .append(" = call i8* @calloc(i64 1, i64 ").append(sizeReg).append(")\n");

                sb.append("  ").append(resultName).append(" = bitcast i8* ").append(allocReg)
                    .append(" to ").append(structType).append("*\n");

                String vtableName = globalEmitter.getVtableName(className);
                if (vtableName != null) {
                    String vtablePtr = newAux("vtableptr");
                    sb.append("  ").append(vtablePtr)
                        .append(" = bitcast %JNativeVTable* ").append(vtableName)
                        .append(" to i8*\n");

                    String objPtrCast = newAux("objptrcast");
                    sb.append("  ").append(objPtrCast).append(" = bitcast ").append(structType)
                        .append("* ").append(resultName).append(" to i8**\n");

                    sb.append("  store i8* ").append(vtablePtr).append(", i8** ")
                        .append(objPtrCast).append("\n");
                }
                break;
            }

            case NEW_ARRAY: {
                if (inst.getOperands().size() < 2) break;
                Value sizeVal = inst.getOperands().get(0);
                Value elemTypeConst = inst.getOperands().get(1);
                if (!(elemTypeConst instanceof Constant)) break;
                Type elemType = elemTypeFromConst(((Constant) elemTypeConst).getValue().toString());
                int elemSize = getElementSizeOfType(elemType);
                String sizeRef = coerceSizeToInt(sb, sizeVal, getLlvmValue(sb, sizeVal));
                String totalSize = newAux("total_size");
                sb.append("  ").append(totalSize).append(" = mul i32 ")
                    .append(sizeRef).append(", ").append(elemSize).append("\n");
                String totalSize64 = newAux("total_size64");
                sb.append("  ").append(totalSize64).append(" = zext i32 ").append(totalSize).append(" to i64\n");
                String allocSize = newAux("alloc_size");
                sb.append("  ").append(allocSize).append(" = add i64 ").append(totalSize64).append(", 8\n");
                String allocReg = newAux("alloc");
                sb.append("  ").append(allocReg).append(" = call i8* @calloc(i64 1, i64 ").append(allocSize).append(")\n");
                String lenPtr = newAux("lenptr");
                sb.append("  ").append(lenPtr).append(" = bitcast i8* ").append(allocReg).append(" to i32*\n");
                sb.append("  store i32 ").append(sizeRef).append(", i32* ").append(lenPtr).append("\n");
                String esPtr = newAux("esptr");
                sb.append("  ").append(esPtr).append(" = getelementptr i32, i32* ").append(lenPtr).append(", i32 1\n");
                sb.append("  store i32 ").append(elemSize).append(", i32* ").append(esPtr).append("\n");
                sb.append("  ").append(resultName).append(" = bitcast i8* ").append(allocReg)
                    .append(" to ").append(LlvmTypeMapper.toLlvmType(inst.getResult().getType())).append("\n");
                break;
            }

            case MULTI_NEW_ARRAY: {
                if (inst.getOperands().isEmpty()) break;
                Value descConst = inst.getOperands().getFirst();
                if (!(descConst instanceof Constant)) break;
                String desc = ((Constant) descConst).getValue().toString();

                List<Value> sizeValues = new ArrayList<>();
                for (int i = 1; i < inst.getOperands().size(); i++) {
                    sizeValues.add(inst.getOperands().get(i));
                }
                int dims = sizeValues.size();
                if (dims == 0) break;

                int elemSize = getBaseElementSize(desc);

                String sizesArray = newAux("sizes_array");
                String sizesSize = newAux("sizes_size");
                sb.append("  ").append(sizesSize).append(" = mul i32 ").append(dims).append(", 4\n");
                String sizesSize64 = newAux("sizes_size64");
                sb.append("  ").append(sizesSize64).append(" = zext i32 ").append(sizesSize).append(" to i64\n");
                sb.append("  ").append(sizesArray).append(" = call i8* @calloc(i64 1, i64 ").append(sizesSize64).append(")\n");
                String sizesI32 = newAux("sizes_i32");
                sb.append("  ").append(sizesI32).append(" = bitcast i8* ").append(sizesArray).append(" to i32*\n");
                for (int i = 0; i < dims; i++) {
                    String sizeRef = coerceSizeToInt(sb, sizeValues.get(i), getLlvmValue(sb, sizeValues.get(i)));
                    String ptr = newAux("sizes_ptr_" + i);
                    sb.append("  ").append(ptr).append(" = getelementptr i32, i32* ")
                        .append(sizesI32).append(", i32 ").append(i).append("\n");
                    sb.append("  store i32 ").append(sizeRef)
                        .append(", i32* ").append(ptr).append("\n");
                }

                String callRes = newAux("multiarr");
                int arrLen = LlvmRuntime.typeStringArrayLength(desc);
                sb.append("  ").append(callRes).append(" = call i8* @__jnative_new_multi_array(i8* getelementptr inbounds ([")
                    .append(arrLen).append(" x i8], [")
                    .append(arrLen).append(" x i8]* ")
                    .append(LlvmRuntime.typeStringGlobalName(desc)).append(", i32 0, i32 0), i32 ")
                    .append(dims).append(", i32* ").append(sizesI32).append(", i32 ")
                    .append(elemSize).append(")\n");
                sb.append("  call void @free(i8* ").append(sizesArray).append(")\n");
                if (resultName != null) {
                    sb.append("  ").append(resultName).append(" = bitcast i8* ").append(callRes)
                        .append(" to ").append(LlvmTypeMapper.toLlvmType(inst.getResult().getType())).append("\n");
                }
                break;
            }

            case FREE: {
                Value obj = inst.getOperands().getFirst();
                String objRef = getLlvmValue(sb, obj);
                sb.append("  call void @free(i8* ").append(objRef).append(")\n");
                break;
            }

            case JSR: {
                String retLabel = "entry";
                if (!inst.getOperands().isEmpty()
                    && inst.getOperands().getFirst() instanceof Constant c
                    && c.getType() == Type.BLOCK
                    && c.getValue() instanceof BasicBlock rb) {
                    retLabel = llvmLabel(rb);
                }
                String fn = inst.getParent() != null && inst.getParent().getFunction() != null
                    ? LlvmRuntime.mangleFunction(inst.getParent().getFunction().getName())
                    : "";
                sb.append("  ").append(resultName)
                    .append(" = bitcast i8* blockaddress(@").append(fn)
                    .append(", %").append(retLabel).append(") to i8*\n");
                break;
            }

            case MONITOR_ENTER: {
                Value obj = inst.getOperands().getFirst();
                emitNullCheck(sb, obj, ranges);
                String objRef = getPointerOperand(sb, obj); // hoist
                sb.append("  call void @__jnative_monitor_enter(i8* ")
                    .append(objRef).append(")\n");
                break;
            }

            case MONITOR_EXIT: {
                Value obj = inst.getOperands().getFirst();
                emitNullCheck(sb, obj, ranges);
                String objRef = getPointerOperand(sb, obj); // hoist
                sb.append("  call void @__jnative_monitor_exit(i8* ")
                    .append(objRef).append(")\n");
                break;
            }

            case INSTANCEOF: {
                Value obj = inst.getOperands().getFirst();
                String typeName = extractTypeName(inst);
                String objRef = getPointerOperand(sb, obj);

                // ------------------------------------------------------------------
                // Array instanceof. See the CHECKCAST comment above for why a
                // vtable-based check is impossible. The bytecode verifier guarantees
                // that the object's static type is either exactly the target array
                // type or a supertype of it, so the only piece of information that a
                // runtime check can legitimately contribute is the null test — a
                // null reference is never an instance of any type, including an array
                // type.
                //
                // Returning `obj != null` matches the reference implementation for
                // the code paths that actually reach this instruction in the JDK's
                // startup: every one of them tests a value whose static type is the
                // target array type (or a supertype), on which the Java compiler has
                // already folded the test to `true`.
                // ------------------------------------------------------------------
                if (typeName != null && typeName.startsWith("[")) {
                    sb.append("  ").append(resultName).append(" = icmp ne i8* ")
                        .append(objRef).append(", null\n");
                    break;
                }

                String typeInfoName = globalEmitter.getTypeInfoName(typeName);

                if (typeInfoName == null) {
                    sb.append("  ; WARNING: no typeInfo for ").append(typeName).append("\n");
                    sb.append("  ").append(resultName).append(" = call i1 @__jnative_instanceof(i8* ")
                        .append(objRef).append(", i8** null)\n");
                } else {
                    sb.append("  ").append(resultName).append(" = call i1 @__jnative_instanceof(i8* ")
                        .append(objRef).append(", i8** ")
                        .append(typeInfoName).append(")\n");
                }
                break;
            }

            case CHECKCAST: {
                Value obj = inst.getOperands().getFirst();
                String typeName    = extractTypeName(inst);
                String objRef      = getPointerOperand(sb, obj);

                // ------------------------------------------------------------------
                // Array cast. The runtime has no per-array type metadata: the first
                // bytes of a Java array are the length header, not a vtable pointer,
                // so __jnative_instanceof cannot produce a meaningful answer for an
                // array type. The bytecode verifier has already established that the
                // cast is type-compatible at the static level (the JDK's own compilers
                // only emit `checkcast [X` when the expression's static type is `[X`
                // or a supertype), and there is no runtime check that could add
                // information beyond what the verifier already proved. The cast is
                // therefore materialised as an unconditional bitcast, matching the
                // behaviour the runtime already used for null typeInfos.
                // ------------------------------------------------------------------
                if (typeName != null && typeName.startsWith("[")) {
                    sb.append("  ").append(resultName).append(" = bitcast i8* ").append(objRef)
                        .append(" to ").append(LlvmTypeMapper.toLlvmType(inst.getResult().getType()))
                        .append("\n");
                    break;
                }

                String typeInfoName = globalEmitter.getTypeInfoName(typeName);

                if (typeInfoName == null) {
                    sb.append("  ").append(resultName).append(" = bitcast i8* ").append(objRef)
                        .append(" to ").append(LlvmTypeMapper.toLlvmType(inst.getResult().getType()))
                        .append("\n");
                    break;
                }

                String isNull  = newAux("cc_isnull");
                String isInst  = newAux("cc_instof");
                String okComb  = newAux("cc_ok");
                String failLbl = newLabel("check_fail");
                String okLbl   = newLabel("check_ok");

                sb.append("  ").append(isNull).append(" = icmp eq i8* ").append(objRef).append(", null\n");
                sb.append("  ").append(isInst).append(" = call i1 @__jnative_instanceof(i8* ")
                    .append(objRef).append(", i8** ").append(typeInfoName).append(")\n");
                sb.append("  ").append(okComb).append(" = or i1 ").append(isNull)
                    .append(", ").append(isInst).append("\n");
                sb.append("  br i1 ").append(okComb)
                    .append(", label %").append(okLbl)
                    .append(", label %").append(failLbl).append("\n");
                sb.append(failLbl).append(":\n");
                emitThrowHelper(sb, "@__jnative_throw_class_cast_exception", ranges);
                sb.append(okLbl).append(":\n");
                sb.append("  ").append(resultName).append(" = bitcast i8* ").append(objRef)
                    .append(" to ").append(LlvmTypeMapper.toLlvmType(inst.getResult().getType()))
                    .append("\n");
                break;
            }

            case ARRAYLENGTH: {
                Value arr = inst.getOperands().getFirst();
                String arrRef = getLlvmValue(sb, arr);
                emitNullCheck(sb, arr, ranges);
                String lenPtr = newAux("lenptr");
                sb.append("  ").append(lenPtr).append(" = bitcast i8* ").append(arrRef).append(" to i32*\n");
                sb.append("  ").append(resultName).append(" = load i32, i32* ").append(lenPtr).append("\n");
                break;
            }

            case ALOAD: {
                if (inst.getOperands().size() < 2) break;
                Value arr = inst.getOperands().get(0);
                Value idx = inst.getOperands().get(1);

                emitNullCheck(sb, arr, ranges);

                String arrPtr = asPointer(sb, arr);
                String idxRef = getLlvmValue(sb, idx);
                String idxI32 = castValueToType(sb, idxRef, idx.getType(), Type.INT);
                emitBoundsCheck(sb, arrPtr, idxI32, ranges);
                Type elemType = inst.getResult().getType();
                int elemSize = getElementSizeOfType(elemType);
                String offset = newAux("offset");
                sb.append("  ").append(offset).append(" = mul i32 ")
                    .append(idxI32).append(", ").append(elemSize).append("\n");
                String offset64 = newAux("offset64");
                sb.append("  ").append(offset64).append(" = zext i32 ").append(offset).append(" to i64\n");
                String basePtr = newAux("baseptr");
                sb.append("  ").append(basePtr).append(" = getelementptr i8, i8* ").append(arrPtr)
                    .append(", i64 8\n");
                String elemPtr = newAux("elemptr");
                sb.append("  ").append(elemPtr).append(" = getelementptr i8, i8* ").append(basePtr)
                    .append(", i64 ").append(offset64).append("\n");
                String ptrCast = newAux("ptrcast");
                String llvmType = LlvmTypeMapper.toLlvmType(elemType);
                sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(elemPtr)
                    .append(" to ").append(llvmType).append("*\n");
                sb.append("  ").append(resultName).append(" = load ").append(llvmType)
                    .append(", ").append(llvmType).append("* ").append(ptrCast).append("\n");
                break;
            }

            case ASTORE: {
                if (inst.getOperands().size() >= 3) {
                    Value arr = inst.getOperands().get(0);
                    Value idx = inst.getOperands().get(1);
                    Value val = inst.getOperands().get(2);

                    emitNullCheck(sb, arr, ranges);

                    String arrPtr = asPointer(sb, arr);
                    String idxRef = getLlvmValue(sb, idx);
                    String valRef = getLlvmValue(sb, val);
                    String idxI32 = castValueToType(sb, idxRef, idx.getType(), Type.INT);
                    emitBoundsCheck(sb, arrPtr, idxI32, ranges);
                    Type elemType = val.getType();
                    if (arr.getType().isArray()) {
                        Type arrElem = arr.getType().getElementType();
                        if (!arrElem.isUnknown()) {
                            elemType = arrElem;
                        }
                    }
                    int elemSize = getElementSizeOfType(elemType);
                    String offset = newAux("offset");
                    sb.append("  ").append(offset).append(" = mul i32 ")
                        .append(idxI32).append(", ").append(elemSize).append("\n");
                    String offset64 = newAux("offset64");
                    sb.append("  ").append(offset64).append(" = zext i32 ").append(offset).append(" to i64\n");
                    String basePtr = newAux("baseptr");
                    sb.append("  ").append(basePtr).append(" = getelementptr i8, i8* ").append(arrPtr)
                        .append(", i64 8\n");
                    String elemPtr = newAux("elemptr");
                    sb.append("  ").append(elemPtr).append(" = getelementptr i8, i8* ").append(basePtr)
                        .append(", i64 ").append(offset64).append("\n");
                    String ptrCast = newAux("ptrcast");
                    String llvmType = LlvmTypeMapper.toLlvmType(elemType);
                    String valConverted = castValueToType(sb, valRef, val.getType(), elemType);
                    sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(elemPtr)
                        .append(" to ").append(llvmType).append("*\n");
                    sb.append("  store ").append(llvmType).append(" ").append(valConverted)
                        .append(", ").append(llvmType).append("* ").append(ptrCast).append("\n");
                }
                break;
            }

            case PHI: {
                BasicBlock parent = getBasicBlock(inst);

                List<BasicBlock> preds = parent.getPredecessors();

                // LLVM requires exactly one incoming edge entry per CFG predecessor of
                // the phi's block. A mismatch means the SSA transformer and the CFG
                // disagree about the predecessor list — most likely a lost or extra
                // edge added after the phis were renamed. Reject it here rather than
                // shipping invalid IR downstream.
                if (preds.size() != inst.getOperands().size()) {
                    throw new IllegalStateException(
                        "PHI operand count (" + inst.getOperands().size()
                            + ") does not match predecessor count (" + preds.size()
                            + ") in block " + parent.getLabel()
                            + "; phi = " + inst);
                }

                Type phiType = inst.getResult().getType();
                String phiLlvm = LlvmTypeMapper.toLlvmType(phiType);

                sb.append("  ").append(resultName).append(" = phi ").append(phiLlvm).append(" ");
                for (int i = 0; i < inst.getOperands().size(); i++) {
                    if (i > 0) sb.append(", ");
                    Value phiOp = inst.getOperands().get(i);

                    // Type safety net. The SSA transformer's renameBlock is responsible
                    // for guaranteeing that every phi operand has a type compatible with
                    // the phi's result type (see SSATransformer.typesCompatible). If a
                    // mismatch slips through, the LLVM parser will report a confusing
                    // error like "integer constant must have integer type" far from the
                    // real source. Catching it here turns that into a precise,
                    // actionable diagnostic pointing at the offending block and operand.
                    String opLlvm = LlvmTypeMapper.toLlvmType(phiOp.getType());
                    if (!opLlvm.equals(phiLlvm)) {
                        throw new IllegalStateException(
                            "PHI operand type mismatch in block " + parent.getLabel()
                                + ": phi type " + phiLlvm
                                + ", operand " + i + " type " + opLlvm
                                + " (value = " + phiOp + ")");
                    }

                    String valRef;
                    Map<Integer, String> preloads = phiPreloads.get(inst);
                    if (preloads != null && preloads.containsKey(i)) {
                        valRef = preloads.get(i);
                    } else if (valueSlots.containsKey(phiOp)) {
                        // Slot-based but not preloaded — the predecessor block has not been
                        // emitted yet, or the value flows in from a path we cannot rewrite.
                        // Fall back to the register the value was originally defined with.
                        // If even that is unavailable, use a typed default. This is a
                        // last-resort case; on a correct CFG every slot-based phi operand
                        // will have been preloaded by its predecessor.
                        String mapped = valueMapper.getValue(phiOp);
                        valRef = mapped != null ? mapped : getDefaultValue(phiOp.getType());
                    } else {
                        valRef = getLlvmValue(sb, phiOp);
                    }
                    sb.append("[ ").append(valRef).append(", %")
                        .append(llvmLabel(preds.get(i))).append(" ]");
                }
                sb.append("\n");
                break;
            }

            case INVOKEDYNAMIC: {
                InvokeDynamicInfo dynInfo = (InvokeDynamicInfo) inst.getInvokedynamicData();
                ResolvedCall call = dynInfo.resolvedCall();
                if (call == null) {
                    sb.append("  ; unresolved invokedynamic\n");
                    if (inst.getResult() != null) {
                        valueMapper.setValue(inst.getResult(), "null");
                    }
                    break;
                }
                switch (call.getType()) {
                    case LAMBDA:
                        emitLambdaCreation(sb, inst, call, resultName);
                        break;
                    case CONCAT:
                        emitConcatCall(sb, inst, resultName);
                        break;
                    default:
                        sb.append("  ; unsupported invokedynamic type: ").append(call.getType()).append("\n");
                        if (inst.getResult() != null) {
                            valueMapper.setValue(inst.getResult(), "null");
                        }
                        break;
                }
                break;
            }

            default:
                sb.append("  ; unsupported opcode: ").append(op).append("\n");
        }
        return sb.toString();
    }

    private static BasicBlock getBasicBlock(Instruction inst) {
        BasicBlock parent = inst.getParent();

        // The SSA transformer is contractually required to set parent on every
        // PHI it inserts (see SSATransformer.insertPhiFunctions). A null parent
        // here means a phi was injected into a block's instruction list without
        // going through BasicBlock.addInstruction, and the emitter has no way
        // to know which predecessor labels the incoming values belong to.
        // Failing loudly is strictly better than emitting "%unknown", which
        // produces broken LLVM IR that clang will reject far from the source
        // of the bug.
        if (parent == null) {
            throw new IllegalStateException(
                "PHI instruction has no parent block: " + inst
                    + " (the SSA transformer must set parent on every phi)");
        }
        return parent;
    }

    // ----- Helper methods for polymorphic calls -----

    /**
     * Generates a call to a polymorphic native function with argument packing into void**.
     */
    private void emitPolymorphicCall(StringBuilder sb, List<Value> args,
                                     NativeMethodInfo best, Type retType,
                                     List<Type> paramTypes, List<TryCatchRange> ranges,
                                     String resultName) {
        String arrayAlloc = newAux("poly_args");
        int argCount = args.size();
        sb.append("  ").append(arrayAlloc).append(" = alloca i8*, i32 ").append(argCount).append("\n");

        for (int i = 0; i < argCount; i++) {
            Value arg = args.get(i);
            String val = getLlvmValue(sb, arg);
            String ptr;

            if (arg.getType().isPrimitive()) {
                String alloc = newAux("arg_alloc");
                String ty = LlvmTypeMapper.toLlvmType(arg.getType());
                sb.append("  ").append(alloc).append(" = alloca ").append(ty).append("\n");
                sb.append("  store ").append(ty).append(" ").append(val).append(", ").append(ty).append("* ").append(alloc).append("\n");
                String cast = newAux("arg_cast");
                sb.append("  ").append(cast).append(" = bitcast ").append(ty).append("* ").append(alloc).append(" to i8*\n");
                ptr = cast;
            } else {
                String cast = newAux("arg_cast");
                sb.append("  ").append(cast).append(" = bitcast ").append(LlvmTypeMapper.toLlvmType(arg.getType()))
                    .append(" ").append(val).append(" to i8*\n");
                ptr = cast;
            }

            String slot = newAux("slot");
            sb.append("  ").append(slot).append(" = getelementptr i8*, i8** ").append(arrayAlloc).append(", i32 ").append(i).append("\n");
            sb.append("  store i8* ").append(ptr).append(", i8** ").append(slot).append("\n");
        }

        String funcName = best.getFullFunctionName();
        ensurePolymorphicFunctionDeclared(best);

        String retLlvm = LlvmTypeMapper.toLlvmType(retType);
        if (!retType.isVoid()) {
            sb.append("  ").append(resultName).append(" = call ").append(retLlvm)
                .append(" @").append(funcName).append("(i8** ").append(arrayAlloc).append(")\n");
        } else {
            sb.append("  call void @").append(funcName).append("(i8** ").append(arrayAlloc).append(")\n");
        }
    }

    private Function ensurePolymorphicFunctionDeclared(NativeMethodInfo info) {
        String funcName = info.getFullFunctionName();
        Function func = module.getFunction(funcName);
        if (func != null) return func;

        String desc = info.getDescriptor();
        Type retType = Type.fromDescriptor(desc.substring(desc.lastIndexOf(')') + 1));
        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);

        Function newFunc = new Function(funcName, retType);
        for (int i = 0; i < paramTypes.size(); i++) {
            newFunc.addParameter(new Parameter(paramTypes.get(i), i));
        }
        module.addFunction(newFunc);
        return newFunc;
    }

    private String buildDescriptor(Type returnType, List<Type> paramTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (Type pt : paramTypes) {
            sb.append(typeToDescriptorChar(pt));
        }
        sb.append(')');
        sb.append(typeToDescriptorChar(returnType));
        return sb.toString();
    }

    private char typeToDescriptorChar(Type type) {
        if (type == Type.VOID) return 'V';
        if (type == Type.BOOLEAN) return 'Z';
        if (type == Type.BYTE) return 'B';
        if (type == Type.SHORT) return 'S';
        if (type == Type.CHAR) return 'C';
        if (type == Type.INT) return 'I';
        if (type == Type.LONG) return 'J';
        if (type == Type.FLOAT) return 'F';
        if (type == Type.DOUBLE) return 'D';
        if (type.isReference()) return 'L';
        if (type.isArray()) return '[';
        return 'L';
    }


    private void emitLambdaCreation(StringBuilder sb, Instruction inst, ResolvedCall call, String resultName) {
        String lambdaId = call.getLambdaStructName();
        List<Value> captured = inst.getOperands();
        List<Type> capturedTypes = call.getCapturedTypes();

        // Register (idempotent) the struct type. It was already registered during
        // the pre-pass; this call merely returns the name.
        String structName = globalEmitter.registerLambdaStruct(lambdaId, capturedTypes);

        String lambdaClassName = "__Lambda_" + lambdaId;
        String vtableName = globalEmitter.getVtableName(lambdaClassName);
        if (vtableName == null) {
            // Should not happen — the lambda was registered in generateLambdaAdaptors().
            throw new IllegalStateException(
                "Lambda vtable not registered for lambdaId=" + lambdaId);
        }

        // Size of struct
        String gepReg = newAux("lambda_gep");
        sb.append("  ").append(gepReg).append(" = getelementptr ")
            .append(structName).append(", ").append(structName)
            .append("* null, i32 1\n");

        String sizeReg = newAux("lambda_size");
        sb.append("  ").append(sizeReg).append(" = ptrtoint ")
            .append(structName).append("* ").append(gepReg).append(" to i64\n");

        String alloc = newAux("lambda_alloc");
        sb.append("  ").append(alloc).append(" = call i8* @calloc(i64 1, i64 ")
            .append(sizeReg).append(")\n");

        String objPtr = Objects.requireNonNullElseGet(resultName, () -> newAux("lambda_obj"));
        sb.append("  ").append(objPtr).append(" = bitcast i8* ").append(alloc)
            .append(" to ").append(structName).append("*\n");

        // Store vtable pointer at offset 0
        String vtablePtr = newAux("lambda_vtable_ptr");
        sb.append("  ").append(vtablePtr)
            .append(" = bitcast %JNativeVTable* ").append(vtableName)
            .append(" to i8*\n");

        String objVtableSlot = newAux("obj_vtable_slot");
        sb.append("  ").append(objVtableSlot).append(" = bitcast ").append(structName)
            .append("* ").append(objPtr).append(" to i8**\n");
        sb.append("  store i8* ").append(vtablePtr).append(", i8** ")
            .append(objVtableSlot).append("\n");

        // Captured fields
        int offset = 8;
        for (Value cap : captured) {
            String fieldLlvm = LlvmTypeMapper.toLlvmType(cap.getType());
            String fieldPtr = newAux("cap_ptr");
            sb.append("  ").append(fieldPtr).append(" = getelementptr i8, i8* ").append(alloc)
                .append(", i64 ").append(offset).append("\n");
            String castPtr = newAux("cap_cast");
            sb.append("  ").append(castPtr).append(" = bitcast i8* ").append(fieldPtr)
                .append(" to ").append(fieldLlvm).append("*\n");
            String valRef = getLlvmValue(sb, cap);
            String valConverted = castValueToType(sb, valRef, cap.getType(), cap.getType());
            sb.append("  store ").append(fieldLlvm).append(" ").append(valConverted)
                .append(", ").append(fieldLlvm).append("* ").append(castPtr).append("\n");
            offset += getElementSizeOfType(cap.getType());
        }

        if (inst.getResult() != null && resultName != null) {
            valueMapper.setValue(inst.getResult(), objPtr);
        }
    }

    private void emitConcatCall(StringBuilder sb, Instruction inst, String resultName) {
        ResolvedCall call = ((InvokeDynamicInfo) inst.getInvokedynamicData()).resolvedCall();
        String recipe = call.getConcatFormat();
        List<Value> dynamicArgs = inst.getOperands();

        List<String> parts = new ArrayList<>();
        int dynIdx = 0;

        if (recipe == null) {
            for (Value arg : dynamicArgs) {
                parts.add(convertArgToString(sb, arg));
            }
        } else {
            Object[] constants = parseConstants(call.getPackedConstants());
            int constIdx = 0;
            StringBuilder literal = new StringBuilder();
            for (int i = 0; i < recipe.length(); i++) {
                char c = recipe.charAt(i);
                if (c == '\u0001') {
                    flushLiteral(sb, literal, parts);
                    if (dynIdx >= dynamicArgs.size()) break;
                    parts.add(convertArgToString(sb, dynamicArgs.get(dynIdx++)));
                } else if (c == '\u0002') {
                    flushLiteral(sb, literal, parts);
                    if (constIdx < constants.length) {
                        String s = String.valueOf(constants[constIdx++]);
                        if (s != null) parts.add(emitLiteralString(sb, s));
                    }
                } else {
                    literal.append(c);
                }
            }
            flushLiteral(sb, literal, parts);
        }

        if (parts.isEmpty()) {
            parts.add(emitLiteralString(sb, ""));
        }

        // A fresh SSA name for the concat call result. Using a hard-coded
        // `%concat_result` here produces a duplicate definition as soon as
        // the same function contains two concatenation call sites — the
        // second one then collides with the first and LLVM rejects the
        // module with "multiple definition of local value named
        // 'concat_result'". Every other temporary in this emitter is
        // generated through newAux(); the concat result must go through
        // the same counter.
        String concatResult = newAux("concat_result");
        sb.append("  ").append(concatResult)
            .append(" = call i8* @__jnative_concat_strings(i32 ")
            .append(parts.size());
        for (String p : parts) {
            sb.append(", i8* ").append(p);
        }
        sb.append(")\n");

        if (inst.getResult() != null && resultName != null) {
            String casted = newAux("concat_cast");
            sb.append("  ").append(casted).append(" = bitcast i8* ").append(concatResult)
                .append(" to ")
                .append(LlvmTypeMapper.toLlvmType(inst.getResult().getType())).append("\n");
            valueMapper.setValue(inst.getResult(), casted);
        }
    }

    private void flushLiteral(StringBuilder sb, StringBuilder literal, List<String> parts) {
        if (!literal.isEmpty()) {
            parts.add(emitLiteralString(sb, literal.toString()));
            literal.setLength(0);
        }
    }

    private String emitLiteralString(StringBuilder sb, String s) {
        return "bitcast (%struct.java_lang_String* @"
            + LlvmRuntime.stringObjectGlobalName(s) + " to i8*)";
    }

    private String convertArgToString(StringBuilder sb, Value arg) {
        Type t = arg.getType();
        String ref = getLlvmValue(sb, arg);

        if (t.isReference()) {
            String cls = t.getClassName();
            if ("java/lang/String".equals(cls)) {
                return ref;
            }
            String res = newAux("to_str");
            sb.append("  ").append(res)
                .append(" = call i8* @__jnative_value_to_string_object(i8* ")
                .append(ref).append(")\n");
            return res;
        }

        if (t.isArray() || t.isNull() || t.isBlock() || t.isUnknown()) {
            String res = newAux("to_str");
            sb.append("  ").append(res)
                .append(" = call i8* @__jnative_value_to_string_object(i8* ")
                .append(ref).append(")\n");
            return res;
        }

        if (t == Type.INT || t == Type.BYTE || t == Type.SHORT
            || t == Type.CHAR || t == Type.BOOLEAN) {
            String fn;
            if (t == Type.BYTE)         fn = "__jnative_value_to_string_byte";
            else if (t == Type.SHORT)   fn = "__jnative_value_to_string_short";
            else if (t == Type.CHAR)    fn = "__jnative_value_to_string_char";
            else if (t == Type.BOOLEAN) fn = "__jnative_value_to_string_boolean";
            else                        fn = "__jnative_value_to_string_int";
            String casted = castValueToType(sb, ref, t, Type.INT);
            String res = newAux("to_str");
            sb.append("  ").append(res).append(" = call i8* @").append(fn)
                .append("(i32 ").append(casted).append(")\n");
            return res;
        }

        if (t == Type.LONG) {
            String res = newAux("to_str");
            sb.append("  ").append(res).append(" = call i8* @__jnative_value_to_string_long(i64 ")
                .append(ref).append(")\n");
            return res;
        }

        if (t == Type.FLOAT) {
            String res = newAux("to_str");
            sb.append("  ").append(res).append(" = call i8* @__jnative_value_to_string_float(float ")
                .append(ref).append(")\n");
            return res;
        }

        if (t == Type.DOUBLE) {
            String res = newAux("to_str");
            sb.append("  ").append(res).append(" = call i8* @__jnative_value_to_string_double(double ")
                .append(ref).append(")\n");
            return res;
        }

        return emitLiteralString(sb, "");
    }

    private Object[] parseConstants(String packed) {
        if (packed == null || packed.isEmpty()) return new Object[0];
        return packed.split("\u0000", -1);
    }

    /**
     * Emits a call. When {@code ranges} is empty the callback writes straight
     * into the caller's buffer. Otherwise the call is wrapped in a try-guard
     * and:
     * <ul>
     *   <li>a slot is allocated in the current (pre-guard) block to receive
     *       the result, so uses of the result outside the guard survive the
     *       try lowering's extra CFG edges;</li>
     *   <li>the callback is invoked with the <em>guarded-body</em> buffer,
     *       so any {@code slot_load} for a slot-based argument is emitted
     *       inside the body — where the guard itself dominates it.</li>
     * </ul>
     * The callback is responsible for emitting the call itself and, if
     * {@code resultReg != null}, assigning the call result to that register.
     */
    private void emitCall(StringBuilder sb, Type retType, Value resultValue, String resultName,
                          List<TryCatchRange> ranges, CallEmitter emitter) {
        String retLlvm = LlvmTypeMapper.toLlvmType(retType);

        if (ranges.isEmpty()) {
            emitter.emit(sb, retType.isVoid() ? null : resultName);
            return;
        }

        String slot = null;
        if (!retType.isVoid() && resultValue != null) {
            slot = newAux("call_slot");

            // The alloca is deferred to the entry block (see emitFunction).
            // Emitting it here, in the block that performs the call, would
            // only make the slot's address available on paths that actually
            // pass through this block. Any later load of the slot from a
            // block reachable without crossing this one — for example a
            // sibling guarded region that shares the enclosing continuation
            // block — would then fail LLVM's dominance check with
            // "Instruction does not dominate all uses!".
            pendingSlotDeclarations.add(
                "  " + slot + " = alloca " + retLlvm + ", align 8\n");
        }

        final String slotRef = slot;
        emitTryGuard(sb, ranges, inner -> {
            if (!retType.isVoid()) {
                String bodyResult = newAux("body_result");
                // The emitter must use `inner` for every getLlvmValue it performs,
                // so that arg slot_loads land in the guarded body, not in the
                // pre-guard block.
                emitter.emit(inner, bodyResult);
                if (slotRef != null) {
                    inner.append("  store ").append(retLlvm).append(" ")
                        .append(bodyResult).append(", ").append(retLlvm)
                        .append("* ").append(slotRef).append("\n");
                }
            } else {
                emitter.emit(inner, null);
            }
        }, false);

        if (slotRef != null) {
            valueSlots.put(resultValue, slotRef);
        }
    }

    private void emitDivByZeroCheck(StringBuilder sb, Value divisor, List<TryCatchRange> ranges) {
        String ty = LlvmTypeMapper.toLlvmType(divisor.getType());

        String divisorRef = getLlvmValue(sb, divisor);

        String chk      = newAux("div_chk");
        String throwBlk = newLabel("throw_divzero");
        String cont     = newLabel("div_ok");

        sb.append("  ").append(chk).append(" = icmp ne ").append(ty).append(" ")
            .append(divisorRef).append(", 0\n");
        sb.append("  br i1 ").append(chk)
            .append(", label %").append(cont)
            .append(", label %").append(throwBlk).append("\n");
        sb.append(throwBlk).append(":\n");
        emitThrowHelper(sb, "@__jnative_throw_arithmetic_exception", ranges);
        sb.append(cont).append(":\n");
    }

    private void emitTryGuard(StringBuilder sb,
                              List<TryCatchRange> ranges,
                              Consumer<StringBuilder> body,
                              boolean neverReturnsNormally) {
        int k = ranges.size();
        String guardBlk = newLabel("guard");
        String bodyBlk = newLabel("guarded_body");
        String catchBlk = newLabel("guarded_catch");
        String contBlk = neverReturnsNormally ? null : newLabel("guarded_cont");

        sb.append("  br label %").append(guardBlk).append("\n");

        sb.append(guardBlk).append(":\n");
        for (int i = k - 1; i >= 0; i--) {
            TryCatchRange r = ranges.get(i);
            String bufPtr = newAux("push_jb");
            sb.append("  ").append(bufPtr).append(" = bitcast [").append(JMP_BUF_SIZE)
                .append(" x i8]* %jmp_buf_").append(rangeOrdinals.get(r)).append(" to i8*\n");
            sb.append("  call void @__jnative_push_catch(i8* ").append(bufPtr)
                .append(", i8** ").append(catchTypeInfoOperand(r)).append(")\n");
        }
        TryCatchRange top = ranges.getFirst();
        String topBufPtr = newAux("sj_jb");
        sb.append("  ").append(topBufPtr).append(" = bitcast [").append(JMP_BUF_SIZE)
            .append(" x i8]* %jmp_buf_").append(rangeOrdinals.get(top)).append(" to i8*\n");
        String sjRet = newAux("sj_ret");
        sb.append("  ").append(sjRet).append(" = call i32 @_setjmp(i8* ").append(topBufPtr).append(")\n");
        String isZero = newAux("sj_zero");
        sb.append("  ").append(isZero).append(" = icmp eq i32 ").append(sjRet).append(", 0\n");
        sb.append("  br i1 ").append(isZero)
            .append(", label %").append(bodyBlk)
            .append(", label %").append(catchBlk).append("\n");

        sb.append(bodyBlk).append(":\n");
        body.accept(sb);
        if (neverReturnsNormally) {
            sb.append("  unreachable\n");
        } else {
            sb.append("  call void @__jnative_pop_catch()\n".repeat(k));
            sb.append("  br label %").append(contBlk).append("\n");
        }

        sb.append(catchBlk).append(":\n");
        String exc = newAux("caught_exc");
        sb.append("  ").append(exc).append(" = call i8* @__jnative_get_exception_object()\n");
        String[] missLabels = new String[k];
        for (int i = 0; i < k; i++) {
            missLabels[i] = newLabel("cmiss");
        }
        for (int i = 0; i < k; i++) {
            TryCatchRange r = ranges.get(i);
            if (i > 0) {
                sb.append(missLabels[i - 1]).append(":\n");
                sb.append("  call void @__jnative_pop_catch()\n");
            }
            String hitBlk = newLabel("chit");
            String ti = r.type != null ? globalEmitter.getTypeInfoName(r.type) : null;
            if (ti != null) {
                String matches = newAux("cm");
                sb.append("  ").append(matches).append(" = call i1 @__jnative_catch_matches(i8* ")
                    .append(exc).append(", i8** ").append(ti).append(")\n");
                sb.append("  br i1 ").append(matches)
                    .append(", label %").append(hitBlk)
                    .append(", label %").append(missLabels[i]).append("\n");
            } else {
                sb.append("  br label %").append(hitBlk).append("\n");
            }
            sb.append(hitBlk).append(":\n");
            sb.append("  call void @__jnative_pop_catch()\n".repeat(k - i));
            BasicBlock handler = handlerBlockByRange.get(r);
            sb.append("  br label %").append(llvmLabel(handler)).append("\n");
        }
        sb.append(missLabels[k - 1]).append(":\n");
        sb.append("  call void @__jnative_pop_catch()\n");
        sb.append("  call void @__jnative_throw_exception(i8* ").append(exc).append(")\n");
        sb.append("  unreachable\n");

        if (contBlk != null) {
            sb.append(contBlk).append(":\n");
        }
    }

    private String catchTypeInfoOperand(TryCatchRange r) {
        return r.type != null ? globalEmitter.getTypeInfoName(r.type) : null;
    }

    private String emitTerminator(Terminator term, List<TryCatchRange> ranges) {
        StringBuilder sb = new StringBuilder();

        if (term instanceof ReturnTerminator rt) {
            Value retVal = rt.getValue();
            Type funcRetType = term.getBlock().getFunction().getReturnType();
            if (retVal != null) {
                String valRef = getLlvmValue(sb, retVal);
                valRef = castValueToType(sb, valRef, retVal.getType(), funcRetType);
                sb.append("  ret ").append(LlvmTypeMapper.toLlvmType(funcRetType))
                    .append(" ").append(valRef).append("\n");
            } else {
                if (funcRetType.isVoid()) {
                    sb.append("  ret void\n");
                } else {
                    String zero = getZeroValue(funcRetType);
                    sb.append("  ret ").append(LlvmTypeMapper.toLlvmType(funcRetType))
                        .append(" ").append(zero).append("\n");
                }
            }
        } else if (term instanceof BranchTerminator bt) {
            sb.append("  br label %").append(llvmLabel(bt.getTarget())).append("\n");
        } else if (term instanceof CondBranchTerminator cbt) {
            String cond = getLlvmValue(sb, cbt.getCondition());
            sb.append("  br i1 ").append(cond).append(", label %")
                .append(llvmLabel(cbt.getTrueTarget())).append(", label %")
                .append(llvmLabel(cbt.getFalseTarget())).append("\n");
        } else if (term instanceof ThrowTerminator tt) {
            Value exc = tt.getException();
            String excRef = exc == null ? null : getPointerOperand(sb, exc);
            String funcNameRef = stringRef(currentFunctionName);
            if (ranges.isEmpty()) {
                if (excRef != null) {
                    sb.append("  call void @__jnative_throw_exception_ctx(i8* ")
                        .append(excRef).append(", i8* ").append(funcNameRef).append(")\n");
                } else {
                    sb.append("  call void @__jnative_throw_null_pointer_exception_ctx(i8* ")
                        .append(funcNameRef).append(")\n");
                }
                sb.append("  unreachable\n");
            } else {
                emitTryGuard(sb, ranges, inner -> {
                    if (excRef != null) {
                        inner.append("  call void @__jnative_throw_exception_ctx(i8* ")
                            .append(excRef).append(", i8* ").append(funcNameRef).append(")\n");
                    } else {
                        inner.append("  call void @__jnative_throw_null_pointer_exception_ctx(i8* ")
                            .append(funcNameRef).append(")\n");
                    }
                }, true);
            }
        } else if (term instanceof LookupSwitchTerminator || term instanceof TableSwitchTerminator) {
            Value key;
            BasicBlock defaultTarget;
            if (term instanceof LookupSwitchTerminator lst) {
                key = lst.getKey();
                defaultTarget = lst.getDefaultTarget();
            } else {
                TableSwitchTerminator tst = (TableSwitchTerminator) term;
                key = tst.getKey();
                defaultTarget = tst.getDefaultTarget();
            }
            String keyRef = getLlvmValue(sb, key);
            String keyI32 = castValueToType(sb, keyRef, key.getType(), Type.INT);
            sb.append("  switch i32 ").append(keyI32).append(", label %")
                .append(llvmLabel(defaultTarget)).append(" [\n");
            if (term instanceof LookupSwitchTerminator lst) {
                for (int i = 0; i < lst.getKeys().length; i++) {
                    sb.append("    i32 ").append(lst.getKeys()[i]).append(", label %")
                        .append(llvmLabel(lst.getTargetsArray()[i])).append("\n");
                }
            } else {
                TableSwitchTerminator tst = (TableSwitchTerminator) term;
                for (int i = 0; i < tst.getTargetsArray().length; i++) {
                    sb.append("    i32 ").append(tst.getMin() + i).append(", label %")
                        .append(llvmLabel(tst.getTargetsArray()[i])).append("\n");
                }
            }
            sb.append("  ]\n");
        } else if (term instanceof IndirectBranchTerminator ibt) {
            List<BasicBlock> targets = ibt.getPossibleTargets();
            if (targets.isEmpty()) {
                sb.append("  unreachable\n");
            } else {
                String blockAddr = getLlvmValue(sb, ibt.getTargetBlock());
                sb.append("  indirectbr i8* ").append(blockAddr).append(", [");
                for (int i = 0; i < targets.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("label %").append(llvmLabel(targets.get(i)));
                }
                sb.append("]\n");
            }
        } else {
            sb.append("  ; unknown terminator\n");
        }
        return sb.toString();
    }

    // ----- Remaining helper methods (unchanged) -----
    private String mapArithOp(Opcode op, Type type) {
        boolean isFloat = type == Type.FLOAT || type == Type.DOUBLE;
        return switch (op) {
            case ADD -> isFloat ? "fadd" : "add";
            case SUB -> isFloat ? "fsub" : "sub";
            case MUL -> isFloat ? "fmul" : "mul";
            case DIV -> isFloat ? "fdiv" : "sdiv";
            case REM -> isFloat ? "frem" : "srem";
            case AND -> "and";
            case OR  -> "or";
            case XOR -> "xor";
            case SHL -> "shl";
            case SHR -> "ashr";
            case USHR -> "lshr";
            default -> "add";
        };
    }

    private String mapCmpOp(Opcode op, boolean isFloat) {
        if (isFloat) {
            return switch (op) {
                case EQ -> "oeq";
                case NE -> "one";
                case LT -> "olt";
                case LE -> "ole";
                case GT -> "ogt";
                case GE -> "oge";
                default -> "oeq";
            };
        } else {
            return switch (op) {
                case EQ -> "eq";
                case NE -> "ne";
                case LT -> "slt";
                case LE -> "sle";
                case GT -> "sgt";
                case GE -> "sge";
                default -> "eq";
            };
        }
    }

    private String getCastOp(Type src, Type dest) {
        if (src.equals(dest)) return null;

        if ((src.isReference() || src.isArray() || src.isNull()) &&
            (dest.isReference() || dest.isArray() || dest.isNull())) {
            return "bitcast";
        }

        if (isIntegerType(src) && (dest.isReference() || dest.isArray() || dest.isNull() || dest.isBlock())) {
            return "inttoptr";
        }

        if ((src.isReference() || src.isArray() || src.isNull() || src.isBlock()) && isIntegerType(dest)) {
            return "ptrtoint";
        }

        if (src.isPrimitive() && dest.isPrimitive()) {
            boolean srcInt = isIntegerType(src);
            boolean destInt = isIntegerType(dest);

            if (srcInt && destInt) {
                int srcBits = getPrimitiveSize(src);
                int destBits = getPrimitiveSize(dest);
                if (srcBits == destBits) return null;
                if (srcBits < destBits) {
                    if (src == Type.CHAR || src == Type.BOOLEAN) return "zext";
                    return "sext";
                } else {
                    return "trunc";
                }
            }

            if (srcInt && (dest == Type.FLOAT || dest == Type.DOUBLE)) {
                return "sitofp";
            }

            if ((src == Type.FLOAT || src == Type.DOUBLE) && destInt) {
                return "fptosi";
            }

            if (src == Type.FLOAT && dest == Type.DOUBLE) return "fpext";
            if (src == Type.DOUBLE && dest == Type.FLOAT) return "fptrunc";
        }

        return "bitcast";
    }

    private boolean isIntegerType(Type type) {
        return type == Type.BOOLEAN || type == Type.BYTE || type == Type.SHORT ||
            type == Type.CHAR || type == Type.INT || type == Type.LONG;
    }

    private int getPrimitiveSize(Type type) {
        if (type == Type.BOOLEAN || type == Type.BYTE) return 8;
        if (type == Type.SHORT || type == Type.CHAR) return 16;
        if (type == Type.INT) return 32;
        if (type == Type.LONG) return 64;
        if (type == Type.FLOAT) return 32;
        if (type == Type.DOUBLE) return 64;
        return 0;
    }

    private String getDefaultValue(Type type) {
        if (type == null) return "null";
        if (type.isReference() || type.isArray() || type.isNull()
            || type.isBlock() || type.isUnknown()) {
            return "null";
        }
        if (type == Type.BOOLEAN) return "false";
        if (type == Type.BYTE || type == Type.SHORT || type == Type.CHAR
            || type == Type.INT || type == Type.LONG) return "0";
        if (type == Type.FLOAT) return "0.0";
        if (type == Type.DOUBLE) return "0.0";
        if (type.isVoid()) return "void";
        return "null";
    }

    private String getLlvmValue(StringBuilder sb, Value v) {
        if (v == null) return "null";
        if (v instanceof Constant c) {
            return constantToLlvmLiteral(c);
        }

        // Values produced by a call inside a try-guard are not defined as plain
        // SSA registers — they live in a stack slot (see emitCall). Emitting a
        // fresh load here, in the block where the value is used, guarantees that
        // the load's defining block coincides with the block of the use (or is
        // dominated by the slot's alloca in entry). LLVM's structural dominance
        // check therefore never sees a register defined in guarded_cont_N being
        // used in a later guarded_body_M that is reachable from the shared
        // recovery block LL2023938592 without passing through guarded_cont_N.
        String slot = valueSlots.get(v);
        if (slot != null) {
            String ty = LlvmTypeMapper.toLlvmType(v.getType());
            String tmp = newAux("slot_load");
            sb.append("  ").append(tmp).append(" = load ")
                .append(ty).append(", ").append(ty)
                .append("* ").append(slot).append("\n");
            return tmp;
        }

        String name = valueMapper.getValue(v);
        if (name == null) {
            return getDefaultValue(v.getType());
        }
        return name;
    }

    /**
     * Coerces an operand used in an integer context (the size of a Java array)
     * to an i32 LLVM value.
     *
     * <p>Two things can go wrong here:</p>
     * <ul>
     *   <li>the operand is a null constant or an unmapped placeholder that
     *       {@link #getLlvmValue} renders as the string {@code "null"};</li>
     *   <li>the operand has a non-int declared type (typically {@code UNKNOWN}
     *       after SSA reconstruction on a local that was actually int-valued).</li>
     * </ul>
     *
     * <p>In both cases {@code mul i32 null, N} / {@code store i32 null, …}
     * would be produced and LLVM rejects the module. Falling back to the
     * literal {@code 0} keeps the generated IR structurally valid. The result
     * is semantically safe for the array-allocation path because a zero-sized
     * array never fails to allocate; any downstream index-out-of-bounds is a
     * separate, honest runtime error rather than a compile failure in LLVM.</p>
     */
    private String coerceSizeToInt(StringBuilder sb, Value v, String valRef) {
        if ("null".equals(valRef)) {
            return "0";
        }
        if (v == null) {
            return valRef;
        }
        Type t = v.getType();
        if (t == null || t == Type.INT) {
            return valRef;
        }
        if (t.isUnknown()) {
            // UNKNOWN renders as i8*; there is no register whose contents
            // we could safely reinterpret as an integer. Treat as zero.
            return "0";
        }
        return castValueToType(sb, valRef, t, Type.INT);
    }

    private String constantToLlvmLiteral(Constant c) {
        Object val = c.getValue();
        if (val == null) {
            Type type = c.getType();
            if (type.isReference() || type.isArray() || type.isNull()
                || type.isBlock() || type.isUnknown()) {
                return "null";
            }
            if (type == Type.BOOLEAN) return "false";
            if (type == Type.BYTE || type == Type.SHORT || type == Type.CHAR
                || type == Type.INT || type == Type.LONG) return "0";
            if (type == Type.FLOAT || type == Type.DOUBLE) return "0.0";
            return "null";
        }
        Type type = c.getType();

        if (type == Type.INT) {
            return Integer.toString(((Number) val).intValue());
        }
        if (type == Type.LONG) {
            return Long.toString(((Number) val).longValue());
        }
        if (type == Type.BYTE) {
            return Integer.toString(((Number) val).byteValue());
        }
        if (type == Type.SHORT) {
            return Integer.toString(((Number) val).shortValue());
        }
        // char: JVM carries a char constant either as a Character or as a
        // numeric code point (Integer). Both must render as an i16 literal.
        if (type == Type.CHAR) {
            int cp;
            if (val instanceof Character ch) {
                cp = ch.charValue();
            } else if (val instanceof Number n) {
                cp = n.intValue();
            } else {
                return "0";
            }
            return Integer.toString(cp);
        }

        if (type == Type.FLOAT || type == Type.DOUBLE) {
            double d = ((Number) val).doubleValue();
            if (Double.isNaN(d)) {
                return "0x7FF8000000000000";
            } else if (Double.isInfinite(d)) {
                return d > 0 ? "0x7FF0000000000000" : "0xFFF0000000000000";
            } else {
                String s = Double.toString(d).replace('E', 'e');
                if (!s.contains(".") && !s.contains("e") && !s.contains("E")) {
                    s = s + ".0";
                }
                return s;
            }
        }

        if (type == Type.BOOLEAN) {
            return ((Boolean) val) ? "true" : "false";
        }

        if (type.isReference() && "java/lang/Class".equals(type.getClassName())) {
            if (val instanceof String className && !className.isEmpty()) {
                return "bitcast (%ReflectionClass* @refclass_"
                    + LlvmTypeMapper.sanitizeIdentifier(className)
                    + " to i8*)";
            }
            return "null";
        }

        if (type.isReference() && "java/lang/String".equals(type.getClassName())
            && val instanceof String s) {
            return "bitcast (%struct.java_lang_String* @"
                + LlvmRuntime.stringObjectGlobalName(s) + " to i8*)";
        }

        if (type.isReference() && val instanceof String s) {
            int len = LlvmRuntime.typeStringArrayLength(s);
            return "getelementptr inbounds ([" + len + " x i8], [" + len + " x i8]* "
                + LlvmRuntime.typeStringGlobalName(s) + ", i32 0, i32 0)";
        }

        return "null";
    }

    private String castValueToType(StringBuilder sb, String value, Type fromType, Type toType) {
        // The literal "null" only makes sense in a pointer context. If the declared
        // source type is a primitive (which can happen due to imprecise SSA type
        // inference of a local variable), we must not fall into the inttoptr branch:
        // `inttoptr i16 null` is invalid LLVM. Resolve null relative to the target type.
        if ("null".equals(value)) {
            if (toType.isReference() || toType.isArray() || toType.isNull() || toType.isBlock()) {
                return "null";
            }
            if (toType == Type.BOOLEAN) return "false";
            if (isIntegerType(toType)) return "0";
            if (toType == Type.FLOAT || toType == Type.DOUBLE) return "0.0";
            return "null";
        }

        boolean fromIsPtr = fromType.isReference() || fromType.isArray() || fromType.isNull() || fromType.isBlock();
        String fromLlvm = LlvmTypeMapper.toLlvmType(fromType);
        if (!fromIsPtr && fromLlvm.endsWith("*")) {
            fromIsPtr = true;
        }

        if ("0".equals(value) && fromIsPtr) {
            value = "null";
        }

        if ("0".equals(value) && (toType.isReference() || toType.isArray() || toType.isNull() || toType.isBlock())) {
            return "null";
        }
        if ("null".equals(value) && isIntegerType(toType)) {
            return "0";
        }

        if (value.matches("-?\\d+\\.0") && (toType == Type.INT || toType == Type.LONG || toType == Type.BYTE ||
            toType == Type.SHORT || toType == Type.CHAR || toType == Type.BOOLEAN)) {
            return value.substring(0, value.indexOf('.'));
        }

        String toLlvm = LlvmTypeMapper.toLlvmType(toType);
        if (fromLlvm.equals(toLlvm)) {
            return value;
        }

        boolean toPtr = toLlvm.endsWith("*");
        boolean fromInt = fromLlvm.matches("i\\d+");
        boolean toInt = toLlvm.matches("i\\d+");
        boolean fromFloat = fromType == Type.FLOAT || fromType == Type.DOUBLE;
        boolean toFloat = toType == Type.FLOAT || toType == Type.DOUBLE;

        if (fromFloat && toPtr) {
            String intCast = newAux("float_to_i64");
            String fptosiOp = "fptosi";
            sb.append("  ").append(intCast).append(" = ").append(fptosiOp)
                .append(" ").append(fromLlvm).append(" ").append(value)
                .append(" to i64\n");
            String ptrCast = newAux("i64_to_ptr");
            sb.append("  ").append(ptrCast).append(" = inttoptr i64 ").append(intCast)
                .append(" to ").append(toLlvm).append("\n");
            return ptrCast;
        }

        if (fromIsPtr && toFloat) {
            String intCast = newAux("ptr_to_i64");
            sb.append("  ").append(intCast).append(" = ptrtoint ").append(fromLlvm)
                .append(" ").append(value).append(" to i64\n");
            String floatCast = newAux("i64_to_float");
            String sitofpOp = "sitofp";
            sb.append("  ").append(floatCast).append(" = ").append(sitofpOp)
                .append(" i64 ").append(intCast)
                .append(" to ").append(toLlvm).append("\n");
            return floatCast;
        }

        String castOp;

        if (fromIsPtr && toInt) {
            castOp = "ptrtoint";
        } else if (fromInt && toPtr) {
            castOp = "inttoptr";
        } else if (fromIsPtr && toPtr) {
            castOp = "bitcast";
        } else if (fromInt && toInt) {
            castOp = getCastOp(fromType, toType);
            if (castOp == null) {
                int fromBits = Integer.parseInt(fromLlvm.substring(1));
                int toBits = Integer.parseInt(toLlvm.substring(1));
                if (fromBits < toBits) {
                    castOp = "sext";
                } else if (fromBits > toBits) {
                    castOp = "trunc";
                } else {
                    castOp = "bitcast";
                }
            }
        } else if (fromFloat && toFloat) {
            if (fromLlvm.equals("float") && toLlvm.equals("double")) {
                castOp = "fpext";
            } else if (fromLlvm.equals("double") && toLlvm.equals("float")) {
                castOp = "fptrunc";
            } else {
                castOp = "bitcast";
            }
        } else if (fromInt && toFloat) {
            castOp = "sitofp";
        } else if (fromFloat && toInt) {
            castOp = "fptosi";
        } else {
            castOp = "bitcast";
        }

        if (castOp.equals("bitcast")) {
            if (fromIsPtr && toInt) {
                castOp = "ptrtoint";
            } else if (fromInt && toPtr) {
                castOp = "inttoptr";
            }
        }

        String castName = newAux("cast");
        sb.append("  ").append(castName).append(" = ").append(castOp)
            .append(" ").append(fromLlvm)
            .append(" ").append(value)
            .append(" to ").append(toLlvm).append("\n");
        return castName;
    }

    private void ensureFunctionDeclared(String owner, String methodName, String descriptor) {
        String mangled = LlvmRuntime.mangleMethod(owner, methodName, descriptor);
        if (module.getFunction(mangled) != null) {
            return;
        }

        Type retType = TypeResolver.descToReturnType(descriptor);
        List<Type> paramTypes = TypeResolver.descToParamTypes(descriptor);

        List<Type> allParams = new ArrayList<>();
        allParams.add(Type.reference(owner));
        allParams.addAll(paramTypes);

        Function func = new Function(mangled, retType);
        for (int i = 0; i < allParams.size(); i++) {
            func.addParameter(new Parameter(allParams.get(i), i));
        }
        module.addFunction(func);
    }

    private String asPointer(StringBuilder sb, Value v) {
        String valRef = getLlvmValue(sb, v);
        Type type = v.getType();
        if (type.isReference() || type.isArray() || type.isNull() || type.isBlock()) {
            String ptrTy = LlvmTypeMapper.toLlvmType(type);
            if (!ptrTy.equals("i8*")) {
                String castName = newAux("ptrcast");
                sb.append("  ").append(castName).append(" = bitcast ").append(ptrTy)
                    .append(" ").append(valRef).append(" to i8*\n");
                return castName;
            }
            return valRef;
        } else if (isIntegerType(type)) {
            String intVal = valRef;
            if (type == Type.INT || type == Type.SHORT || type == Type.BYTE || type == Type.CHAR || type == Type.BOOLEAN) {
                String ext = newAux("sext");
                sb.append("  ").append(ext).append(" = sext ").append(LlvmTypeMapper.toLlvmType(type))
                    .append(" ").append(valRef).append(" to i64\n");
                intVal = ext;
            } else if (type == Type.LONG) {
                // already i64
            } else {
                // fallback
            }
            String ptrCast = newAux("inttoptr");
            sb.append("  ").append(ptrCast).append(" = inttoptr i64 ").append(intVal).append(" to i8*\n");
            return ptrCast;
        } else {
            String ptrCast = newAux("ptrcast");
            sb.append("  ").append(ptrCast).append(" = bitcast ").append(LlvmTypeMapper.toLlvmType(type))
                .append(" ").append(valRef).append(" to i8*\n");
            return ptrCast;
        }
    }

    private void emitBoundsCheck(StringBuilder sb, String arrPtr, String idxI32, List<TryCatchRange> ranges) {
        String lenPtr = newAux("lenptr");
        String len = newAux("len");
        sb.append("  ").append(lenPtr).append(" = bitcast i8* ").append(arrPtr).append(" to i32*\n");
        sb.append("  ").append(len).append(" = load i32, i32* ").append(lenPtr).append("\n");
        String chk1 = newAux("bnd_chk1");
        String chk2 = newAux("bnd_chk2");
        String ok = newAux("bnd_ok");
        String throwBlk = newLabel("throw_aioobe");
        String cont = newLabel("bnd_ok");
        sb.append("  ").append(chk1).append(" = icmp sge i32 ").append(idxI32).append(", 0\n");
        sb.append("  ").append(chk2).append(" = icmp slt i32 ").append(idxI32).append(", ").append(len).append("\n");
        sb.append("  ").append(ok).append(" = and i1 ").append(chk1).append(", ").append(chk2).append("\n");
        sb.append("  br i1 ").append(ok)
            .append(", label %").append(cont)
            .append(", label %").append(throwBlk).append("\n");
        sb.append(throwBlk).append(":\n");
        emitThrowHelper(sb, "@__jnative_throw_array_index_out_of_bounds", ranges);
        sb.append(cont).append(":\n");
    }

    private boolean isCallableSymbolDefined(String symbol) {
        if (symbol == null || symbol.isEmpty()) return false;
        if (symbol.startsWith("__jnative_")) return true;
        if (symbol.startsWith("llvm."))    return true;
        return switch (symbol) {
            case "malloc", "free", "printf", "abort", "atexit",
                 "memcpy", "memmove", "memset", "_setjmp", "longjmp" ->
                true;
            default -> {
                Function f = module.getFunction(symbol);
                // A bare `declare` (no entry block) will fail at link time
                // unless something else provides the body.  Only count the
                // symbol as defined when the module really contains a
                // definition for it.
                yield f != null && f.getEntryBlock() != null;
            }
        };
    }

    private String emitBaseToI8Pointer(StringBuilder sb, Value base) {
        Type t = base.getType();
        String baseRef  = getLlvmValue(sb, base);
        String baseLlvm = LlvmTypeMapper.toLlvmType(t);

        if ("i8*".equals(baseLlvm)) {
            return baseRef;
        }

        if (t.isReference() || t.isArray() || t.isNull() || t.isBlock()
            || baseLlvm.endsWith("*")) {
            String cast = newAux("base_i8");
            sb.append("  ").append(cast).append(" = bitcast ")
                .append(baseLlvm).append(" ").append(baseRef)
                .append(" to i8*\n");
            return cast;
        }

        if (t == Type.FLOAT) {
            String i32Reg = newAux("base_i32");
            sb.append("  ").append(i32Reg).append(" = bitcast float ")
                .append(baseRef).append(" to i32\n");
            String i64Reg = newAux("base_i64");
            sb.append("  ").append(i64Reg).append(" = zext i32 ")
                .append(i32Reg).append(" to i64\n");
            String ptrReg = newAux("base_i8");
            sb.append("  ").append(ptrReg).append(" = inttoptr i64 ")
                .append(i64Reg).append(" to i8*\n");
            return ptrReg;
        }

        if (t == Type.DOUBLE) {
            String i64Reg = newAux("base_i64");
            sb.append("  ").append(i64Reg).append(" = bitcast double ")
                .append(baseRef).append(" to i64\n");
            String ptrReg = newAux("base_i8");
            sb.append("  ").append(ptrReg).append(" = inttoptr i64 ")
                .append(i64Reg).append(" to i8*\n");
            return ptrReg;
        }

        if (isIntegerType(t)) {
            String i64Reg;
            if (t == Type.LONG) {
                i64Reg = baseRef;
            } else {
                i64Reg = newAux("base_i64");
                String op = (t == Type.BOOLEAN) ? "zext" : "sext";
                sb.append("  ").append(i64Reg).append(" = ").append(op).append(" ")
                    .append(baseLlvm).append(" ").append(baseRef)
                    .append(" to i64\n");
            }
            String ptrReg = newAux("base_i8");
            sb.append("  ").append(ptrReg).append(" = inttoptr i64 ")
                .append(i64Reg).append(" to i8*\n");
            return ptrReg;
        }

        throw new IllegalStateException(
            "Cannot convert value of type '" + t + "' to i8* for raw-offset field access. "
                + "Value = " + base + ". This indicates a bug in an earlier IR pass.");
    }

    private String getPointerOperand(StringBuilder sb, Value v) {
        String val = getLlvmValue(sb, v);
        if ("0".equals(val)) {
            return "null";
        }
        return val;
    }
}
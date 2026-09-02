package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.ssa.GraphUtils;
import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
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
import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.function.Consumer;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractClassName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;
import static io.github.kubyk01.util.LlvmUtil.extractTypeName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;
import static io.github.kubyk01.util.LlvmUtil.inferLocalType;
import static io.github.kubyk01.util.LlvmUtil.getElementSizeOfType;

@RequiredArgsConstructor
public class LlvmFunctionEmitter {

    private static final int JMP_BUF_SIZE = 256;

    private final Module module;
    private final LlvmTypeMapper typeMapper;
    private final LlvmGlobalEmitter globalEmitter;

    private final LlvmValueMapper valueMapper = new LlvmValueMapper();
    private int tmpCounter = 0;
    private int labelCounter = 0;
    private BasicBlock currentEntryBlock;

    private final Map<BasicBlock, List<TryCatchRange>> blockToTryRanges = new HashMap<>();
    private final Map<TryCatchRange, BasicBlock> handlerBlockByRange = new HashMap<>();
    private final Map<TryCatchRange, Integer> rangeOrdinals = new HashMap<>();

    public String emitFunction(Function func) {
        StringBuilder sb = new StringBuilder();
        String funcName = func.getName();
        sb.append("define ").append(typeMapper.toLlvmType(func.getReturnType()))
            .append(" @").append(funcName).append("(");

        List<Parameter> params = func.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(typeMapper.toLlvmType(params.get(i).getType()))
                .append(" %param_").append(i);
        }
        sb.append(") {\n");

        valueMapper.clear();
        for (int i = 0; i < params.size(); i++) {
            valueMapper.setValue(params.get(i), "%param_" + i);
        }

        currentEntryBlock = func.getEntryBlock();
        buildTryCatchInfo(func);

        boolean firstBlock = true;
        for (BasicBlock block : func.getBlocks()) {
            boolean isEntry = (block == currentEntryBlock) || (currentEntryBlock == null && firstBlock);
            if (isEntry) {
                sb.append("entry:\n");
                Set<Integer> usedLocals = collectUsedLocals(func);
                for (int idx : usedLocals) {
                    sb.append("  %local_").append(idx).append(" = alloca i64, align 8\n");
                }
                List<TryCatchRange> ranges = func.getTryCatchRanges();
                if (ranges != null) {
                    for (int i = 0; i < ranges.size(); i++) {
                        sb.append("  %jmp_buf_").append(i).append(" = alloca [")
                            .append(JMP_BUF_SIZE).append(" x i8], align 16\n");
                    }
                }
            }
            sb.append(emitBlock(block, isEntry));
            firstBlock = false;
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
            sb.append(emitTerminator(term, ranges));
        } else {
            Type retType = block.getFunction().getReturnType();
            if (retType.isVoid()) {
                sb.append("  ret void\n");
            } else {
                String zero = getZeroValue(retType);
                sb.append("  ret ").append(typeMapper.toLlvmType(retType))
                    .append(" ").append(zero).append("\n");
            }
        }
        return sb.toString();
    }

    private String getZeroValue(Type type) {
        if (type == Type.FLOAT || type == Type.DOUBLE) return "0.0";
        if (type.isReference() || type.isArray() || type.isNull()) return "null";
        return "0";
    }

    private String llvmLabel(BasicBlock block) {
        if (block == currentEntryBlock) {
            return "entry";
        }
        return block.getLabel().replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String newAux(String prefix) {
        return "%" + prefix + "_" + (tmpCounter++);
    }

    private String newLabel(String prefix) {
        return prefix + "_" + (labelCounter++);
    }

    // ----- Helper for throwing exceptions -----

    private void emitThrowHelper(StringBuilder sb, String callee, List<TryCatchRange> ranges) {
        if (ranges.isEmpty()) {
            sb.append("  call void ").append(callee).append("()\n");
            sb.append("  unreachable\n");
        } else {
            emitTryGuard(sb, ranges,
                inner -> inner.append("  call void ").append(callee).append("()\n"),
                true);
        }
    }

    private void emitNullCheck(StringBuilder sb, Value obj, List<TryCatchRange> ranges) {
        // Skip null check for non‑pointer types to avoid invalid LLVM (e.g., icmp ne i32, null)
        if (!(obj.getType().isReference() || obj.getType().isArray() || obj.getType().isNull() || obj.getType().isBlock())) {
            return;
        }
        String ty = typeMapper.toLlvmType(obj.getType());
        String chk = newAux("npe_chk");
        String throwBlk = newLabel("throw_npe");
        String cont = newLabel("npe_ok");
        sb.append("  ").append(chk).append(" = icmp ne ").append(ty).append(" ")
            .append(getLlvmValue(obj)).append(", null\n");
        sb.append("  br i1 ").append(chk)
            .append(", label %").append(cont)
            .append(", label %").append(throwBlk).append("\n");
        sb.append(throwBlk).append(":\n");
        emitThrowHelper(sb, "@__jnative_throw_null_pointer_exception", ranges);
        sb.append(cont).append(":\n");
    }

    private void emitBoundsCheck(StringBuilder sb, Value arr, String idxI32, List<TryCatchRange> ranges) {
        String arrRef = getLlvmValue(arr);
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
                String llvmType = typeMapper.toLlvmType(inst.getResult().getType());
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
                String valRef = getLlvmValue(stored);
                Type storedType = stored.getType();
                Type localType = inferLocalType(inst.getParent().getFunction(), idx);
                if (localType == Type.UNKNOWN) {
                    localType = storedType;
                }
                String llvmType = typeMapper.toLlvmType(localType);
                valRef = castValueToType(sb, valRef, storedType, localType);
                String ptr = newAux("ptrcast");
                sb.append("  ").append(ptr).append(" = bitcast i64* %local_").append(idx)
                    .append(" to ").append(llvmType).append("*\n");
                sb.append("  store ").append(llvmType).append(" ").append(valRef)
                    .append(", ").append(llvmType).append("* ").append(ptr).append("\n");
                if (inst.getResult() != null) {
                    valueMapper.setValue(inst.getResult(), valRef);
                }
                break;
            }
            case ADD: case SUB: case MUL: case DIV: case REM:
            case AND: case OR: case XOR: case SHL: case SHR: case USHR: {
                Value left = inst.getOperands().get(0);
                Value right = inst.getOperands().get(1);
                Type resType = inst.getResult().getType();

                // Division/remainder by zero check (only for int/long)
                if ((op == Opcode.DIV || op == Opcode.REM) && !ranges.isEmpty()) {
                    Type lt = left.getType();
                    if (lt == Type.INT || lt == Type.LONG) {
                        emitDivByZeroCheck(sb, right, ranges);
                    }
                }

                boolean leftIsPtr = left.getType().isReference() || left.getType().isArray() || left.getType().isNull() || left.getType().isBlock();
                boolean rightIsPtr = right.getType().isReference() || right.getType().isArray() || right.getType().isNull() || right.getType().isBlock();
                boolean isBitwise = (op == Opcode.AND || op == Opcode.OR || op == Opcode.XOR || op == Opcode.SHL || op == Opcode.SHR || op == Opcode.USHR);
                boolean anyPtr = leftIsPtr || rightIsPtr || resType.isReference() || resType.isArray() || resType.isNull() || resType.isBlock();

                // Bitwise operations involving pointers: convert to i64, perform bitwise op, cast back
                if (isBitwise && (leftIsPtr || rightIsPtr)) {
                    Type intType = Type.LONG;
                    String lInt = castValueToType(sb, getLlvmValue(left), left.getType(), intType);
                    String rInt = castValueToType(sb, getLlvmValue(right), right.getType(), intType);
                    String llvmOp = mapArithOp(op, Type.LONG);
                    String tmp = newAux("bitwise_int");
                    sb.append("  ").append(tmp).append(" = ").append(llvmOp)
                        .append(" ").append(typeMapper.toLlvmType(intType))
                        .append(" ").append(lInt).append(", ").append(rInt).append("\n");
                    String finalVal = castValueToType(sb, tmp, intType, resType);
                    if (resultName != null) {
                        valueMapper.setValue(inst.getResult(), finalVal);
                    }
                    break;
                }

                // Non‑bitwise arithmetic with pointers: convert both to i64, perform op, cast back
                if (!isBitwise && anyPtr) {
                    String lInt = castValueToType(sb, getLlvmValue(left), left.getType(), Type.LONG);
                    String rInt = castValueToType(sb, getLlvmValue(right), right.getType(), Type.LONG);
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

                // Normal arithmetic (both operands are integers or floats)
                String l = castValueToType(sb, getLlvmValue(left), left.getType(), resType);
                String r = castValueToType(sb, getLlvmValue(right), right.getType(), resType);
                String llvmOp = mapArithOp(op, resType);
                sb.append("  ").append(resultName).append(" = ").append(llvmOp)
                    .append(" ").append(typeMapper.toLlvmType(resType))
                    .append(" ").append(l).append(", ").append(r).append("\n");
                break;
            }
            case EQ: case NE: case LT: case LE: case GT: case GE: {
                Value left = inst.getOperands().get(0);
                Value right = inst.getOperands().get(1);
                Type leftType = left.getType();
                Type rightType = right.getType();
                String l = getLlvmValue(left);
                String r = getLlvmValue(right);

                boolean leftIsPtr = leftType.isReference() || leftType.isArray() || leftType.isNull() || leftType.isBlock();
                boolean rightIsPtr = rightType.isReference() || rightType.isArray() || rightType.isNull() || rightType.isBlock();

                // Replace integer 0 with null for pointer comparisons
                if (leftIsPtr && "0".equals(r)) { r = "null"; }
                if (rightIsPtr && "0".equals(l)) { l = "null"; }

                // Handle mixed pointer/integer comparison: cast both to i64
                if ((leftIsPtr && !rightIsPtr) || (!leftIsPtr && rightIsPtr)) {
                    l = castValueToType(sb, l, leftType, Type.LONG);
                    r = castValueToType(sb, r, rightType, Type.LONG);
                    leftType = Type.LONG;
                } else if (!leftIsPtr && !rightIsPtr) {
                    // Both are non-pointers
                    boolean leftIsFloat = leftType == Type.FLOAT || leftType == Type.DOUBLE;
                    boolean rightIsFloat = rightType == Type.FLOAT || rightType == Type.DOUBLE;
                    boolean leftIsInt = isIntegerType(leftType);
                    boolean rightIsInt = isIntegerType(rightType);

                    if ((leftIsFloat && rightIsInt) || (leftIsInt && rightIsFloat)) {
                        // Convert integer to float
                        if (leftIsInt && rightIsFloat) {
                            l = castValueToType(sb, l, leftType, rightType);
                            leftType = rightType;
                        } else if (leftIsFloat && rightIsInt) {
                            r = castValueToType(sb, r, rightType, leftType);
                            // leftType remains float
                        }
                    } else if (leftIsInt && rightIsInt) {
                        // Both integers: promote to i64 to unify types
                        l = castValueToType(sb, l, leftType, Type.LONG);
                        r = castValueToType(sb, r, rightType, Type.LONG);
                        leftType = Type.LONG;
                    } else if (!leftType.equals(rightType)) {
                        // Fallback: cast right to left type
                        r = castValueToType(sb, r, rightType, leftType);
                    }
                }

                boolean isFloat = leftType == Type.FLOAT || leftType == Type.DOUBLE;
                String instr = isFloat ? "fcmp" : "icmp";
                String pred = mapCmpOp(op, isFloat);
                sb.append("  ").append(resultName).append(" = ").append(instr)
                    .append(" ").append(pred)
                    .append(" ").append(typeMapper.toLlvmType(leftType))
                    .append(" ").append(l).append(", ").append(r).append("\n");
                break;
            }

            case CAST: {
                Value val = inst.getOperands().getFirst();
                Type srcType = val.getType();
                Type destType = inst.getResult().getType();
                String casted = castValueToType(sb, getLlvmValue(val), srcType, destType);
                valueMapper.setValue(inst.getResult(), casted);
                break;
            }
            case GET_FIELD: {
                Value base = inst.getOperands().getFirst();
                String fieldName = extractFieldName(inst);
                String baseRef = getLlvmValue(base);
                int offset = globalEmitter.getFieldOffset(extractClassName(base), fieldName);
                emitNullCheck(sb, base, ranges);
                String baseI8 = newAux("base_i8");
                // Use inttoptr if the base is an integer, bitcast otherwise
                String baseTypeLlvm = typeMapper.toLlvmType(base.getType());
                String castOp = baseTypeLlvm.endsWith("*") ? "bitcast" : "inttoptr";
                sb.append("  ").append(baseI8).append(" = ").append(castOp)
                    .append(" ").append(baseTypeLlvm).append(" ").append(baseRef)
                    .append(" to i8*\n");
                String gep = newAux("gep");
                sb.append("  ").append(gep).append(" = getelementptr i8, i8* ").append(baseI8)
                    .append(", i32 ").append(offset).append("\n");
                String ptrCast = newAux("ptrcast");
                Type fieldType = inst.getResult().getType();
                String fieldLlvm = typeMapper.toLlvmType(fieldType);
                sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(gep)
                    .append(" to ").append(fieldLlvm).append("*\n");
                sb.append("  ").append(resultName).append(" = load ").append(fieldLlvm)
                    .append(", ").append(fieldLlvm).append("* ").append(ptrCast).append("\n");
                break;
            }
            case PUT_FIELD: {
                Value base = inst.getOperands().get(0);
                Value rhs = inst.getOperands().get(2);
                String fieldName = extractFieldName(inst);
                String baseRef = getLlvmValue(base);
                int offset = globalEmitter.getFieldOffset(extractClassName(base), fieldName);
                emitNullCheck(sb, base, ranges);
                String baseI8 = newAux("base_i8");
                // Use inttoptr if the base is an integer, bitcast otherwise
                String baseTypeLlvm = typeMapper.toLlvmType(base.getType());
                String castOp = baseTypeLlvm.endsWith("*") ? "bitcast" : "inttoptr";
                sb.append("  ").append(baseI8).append(" = ").append(castOp)
                    .append(" ").append(baseTypeLlvm).append(" ").append(baseRef)
                    .append(" to i8*\n");
                String gep = newAux("gep");
                sb.append("  ").append(gep).append(" = getelementptr i8, i8* ").append(baseI8)
                    .append(", i32 ").append(offset).append("\n");
                String ptrCast = newAux("ptrcast");
                Type fieldType = globalEmitter.getFieldType(extractClassName(base), fieldName);
                if (fieldType == null) {
                    fieldType = rhs.getType();
                }
                String fieldLlvm = typeMapper.toLlvmType(fieldType);
                String rhsRef = getLlvmValue(rhs);
                if ("null".equals(rhsRef) && !(fieldType.isReference() || fieldType.isArray() || fieldType.isNull() || fieldType.isBlock())) {
                    rhsRef = "0";
                }
                sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(gep)
                    .append(" to ").append(fieldLlvm).append("*\n");
                sb.append("  store ").append(fieldLlvm).append(" ").append(rhsRef)
                    .append(", ").append(fieldLlvm).append("* ").append(ptrCast).append("\n");
                break;
            }
            case GET_STATIC: {
                String fieldName = extractFieldName(inst);
                String globalName = "gv_" + fieldName.replace('.', '_').replace('/', '_');
                Type fieldType = inst.getResult().getType();
                String llvmType = typeMapper.toLlvmType(fieldType);
                sb.append("  ").append(resultName).append(" = load ")
                    .append(llvmType).append(", ")
                    .append(llvmType).append("* @").append(globalName).append("\n");
                break;
            }
            case PUT_STATIC: {
                Value rhs = inst.getOperands().get(1);
                String fieldName = extractFieldName(inst);
                String globalName = "gv_" + fieldName.replace('.', '_').replace('/', '_');
                String rhsRef = getLlvmValue(rhs);
                String llvmType = typeMapper.toLlvmType(rhs.getType());
                sb.append("  store ").append(llvmType).append(" ").append(rhsRef)
                    .append(", ").append(llvmType).append("* @").append(globalName).append("\n");
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
                String sig = calleeName.substring(dotIdx + 1);
                int idx = globalEmitter.getMethodIndex(sig);

                Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;

                if (idx < 0) {
                    sb.append("  ; WARNING: virtual method not found in vtable, using direct call\n");
                    String owner = calleeName.substring(0, dotIdx);
                    int parenIdx = sig.indexOf('(');
                    String mName = parenIdx > 0 ? sig.substring(0, parenIdx) : sig;
                    String mDesc = parenIdx > 0 ? sig.substring(parenIdx) : "";

                    ensureFunctionDeclared(owner, mName, mDesc);

                    String funcName = LlvmRuntime.mangleMethod(owner, mName, mDesc);
                    StringBuilder argList = new StringBuilder();
                    for (int i = 0; i < operands.size(); i++) {
                        if (i == 1) continue; // skip the callee constant
                        if (!argList.isEmpty()) argList.append(", ");
                        argList.append(typeMapper.toLlvmType(operands.get(i).getType()))
                            .append(" ").append(getLlvmValue(operands.get(i)));
                    }
                    emitCall(sb, retType, resultName, "@" + funcName, argList.toString(), ranges);
                    break;
                }

                emitNullCheck(sb, receiver, ranges);

                String receiverRef = getLlvmValue(receiver);
                String vtSlotPtr = newAux("vtslot");
                sb.append("  ").append(vtSlotPtr).append(" = bitcast ")
                    .append(typeMapper.toLlvmType(receiver.getType())).append(" ").append(receiverRef)
                    .append(" to i8**\n");
                String vtableLoad = newAux("vtable_load");
                sb.append("  ").append(vtableLoad).append(" = load i8*, i8** ").append(vtSlotPtr).append("\n");

                String funcPtrGep = newAux("funcptr_gep");
                sb.append("  ").append(funcPtrGep).append(" = getelementptr i8*, i8* ").append(vtableLoad)
                    .append(", i32 ").append(idx).append("\n");
                String funcPtr = newAux("funcptr");
                sb.append("  ").append(funcPtr).append(" = load i8*, i8* ").append(funcPtrGep).append("\n");

                String funcType = LlvmRuntime.getFunctionType(sig, typeMapper);
                String funcPtrCast = newAux("fptrcast");
                sb.append("  ").append(funcPtrCast).append(" = bitcast i8* ").append(funcPtr)
                    .append(" to ").append(funcType).append("\n");

                StringBuilder argList = new StringBuilder();
                for (int i = 0; i < operands.size(); i++) {
                    if (i == 1) continue;
                    if (!argList.isEmpty()) argList.append(", ");
                    argList.append(typeMapper.toLlvmType(operands.get(i).getType()))
                        .append(" ").append(getLlvmValue(operands.get(i)));
                }
                emitCall(sb, retType, resultName, funcPtrCast, argList.toString(), ranges);
                break;
            }

            case STATIC_CALL:
            case CALL: {
                String calleeName = extractCalleeName(inst);
                if (calleeName == null) break;

                Function calleeFunc = module.getFunction(calleeName);
                String mangledCallee;
                if (calleeFunc != null) {
                    // Function already exists in the module — use its name directly
                    mangledCallee = calleeFunc.getName();
                } else {
                    // Try to find a native implementation (with __jnative_ prefix)
                    String nativeCandidate = "__jnative_" + LlvmRuntime.mangleCallable(calleeName);
                    if (module.getFunction(nativeCandidate) != null) {
                        mangledCallee = nativeCandidate;
                    } else {
                        // Ensure the function is declared before calling it
                        int dotIdx = calleeName.lastIndexOf('.');
                        int parenIdx = calleeName.indexOf('(');
                        if (dotIdx > 0 && parenIdx > dotIdx) {
                            String owner = calleeName.substring(0, dotIdx);
                            String methodPart = calleeName.substring(dotIdx + 1);
                            int localParenIdx = parenIdx - dotIdx - 1;
                            String methodName = methodPart.substring(0, localParenIdx);
                            String descriptor = methodPart.substring(localParenIdx);
                            ensureFunctionDeclared(owner, methodName, descriptor);
                        }
                        // Perform standard mangling
                        mangledCallee = LlvmRuntime.mangleCallable(calleeName);
                    }
                }

                List<Value> args = getCallArguments(inst);
                StringBuilder argList = new StringBuilder();
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) argList.append(", ");
                    argList.append(typeMapper.toLlvmType(args.get(i).getType()))
                        .append(" ").append(getLlvmValue(args.get(i)));
                }
                Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;
                emitCall(sb, retType, resultName, "@" + mangledCallee, argList.toString(), ranges);
                break;
            }

            case SPECIAL_CALL: {
                if (inst.getOperands().size() < 2) break;
                Value receiver = inst.getOperands().get(0);
                Value calleeConst = inst.getOperands().get(1);
                if (!(calleeConst instanceof Constant)) break;
                String calleeName = ((Constant) calleeConst).getValue().toString();

                Function calleeFunc = module.getFunction(calleeName);
                String mangledCallee;
                if (calleeFunc != null) {
                    String funcName = calleeFunc.getName();
                    mangledCallee = funcName.startsWith("__jnative_")
                        ? funcName
                        : LlvmRuntime.mangleFunction(funcName);
                } else {
                    String nativeCandidate = "__jnative_" + LlvmRuntime.mangleCallable(calleeName);
                    if (module.getFunction(nativeCandidate) != null) {
                        mangledCallee = nativeCandidate;
                    } else {
                        // Ensure the function is declared
                        int dotIdx = calleeName.lastIndexOf('.');
                        int parenIdx = calleeName.indexOf('(');
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

                List<Value> args = new ArrayList<>();
                args.add(receiver);
                for (int i = 2; i < inst.getOperands().size(); i++) {
                    args.add(inst.getOperands().get(i));
                }
                StringBuilder argList = new StringBuilder();
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) argList.append(", ");
                    argList.append(typeMapper.toLlvmType(args.get(i).getType()))
                        .append(" ").append(getLlvmValue(args.get(i)));
                }
                Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;
                emitCall(sb, retType, resultName, "@" + mangledCallee, argList.toString(), ranges);
                break;
            }

            case NEW: {
                String className = extractTypeName(inst);
                String structType = globalEmitter.getStructName(className);
                String sizeReg = newAux("size");
                String allocReg = newAux("alloc");
                sb.append("  ").append(sizeReg).append(" = call i64 @llvm.objectsize.i64.p0i8(i8* null, i1 true)\n");
                sb.append("  ").append(allocReg).append(" = call i8* @malloc(i64 ptrtoint (").append(structType)
                    .append("* getelementptr (").append(structType).append(", ").append(structType)
                    .append("* null, i32 1) to i64))\n");
                sb.append("  ").append(resultName).append(" = bitcast i8* ").append(allocReg)
                    .append(" to ").append(structType).append("*\n");
                String vtableName = globalEmitter.getVtableName(className);
                if (vtableName != null) {
                    String vtablePtr = newAux("vtableptr");
                    sb.append("  ").append(vtablePtr).append(" = bitcast [").append(globalEmitter.getTotalMethods())
                        .append(" x i8*]* ").append(vtableName).append(" to i8*\n");
                    String objPtrCast = newAux("objptrcast");
                    sb.append("  ").append(objPtrCast).append(" = bitcast ").append(structType)
                        .append("* ").append(resultName).append(" to i8**\n");
                    sb.append("  store i8* ").append(vtablePtr).append(", i8** ").append(objPtrCast).append("\n");
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
                String sizeRef = getLlvmValue(sizeVal);
                String totalSize = newAux("total_size");
                sb.append("  ").append(totalSize).append(" = mul i32 ")
                    .append(sizeRef).append(", ").append(elemSize).append("\n");
                String totalSize64 = newAux("total_size64");
                sb.append("  ").append(totalSize64).append(" = zext i32 ").append(totalSize).append(" to i64\n");
                String allocSize = newAux("alloc_size");
                sb.append("  ").append(allocSize).append(" = add i64 ").append(totalSize64).append(", 4\n");
                String allocReg = newAux("alloc");
                sb.append("  ").append(allocReg).append(" = call i8* @malloc(i64 ").append(allocSize).append(")\n");
                String lenPtr = newAux("lenptr");
                sb.append("  ").append(lenPtr).append(" = bitcast i8* ").append(allocReg).append(" to i32*\n");
                sb.append("  store i32 ").append(sizeRef).append(", i32* ").append(lenPtr).append("\n");
                sb.append("  ").append(resultName).append(" = bitcast i8* ").append(allocReg)
                    .append(" to ").append(typeMapper.toLlvmType(inst.getResult().getType())).append("\n");
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
                sb.append("  ").append(sizesArray).append(" = call i8* @malloc(i64 ").append(sizesSize64).append(")\n");
                String sizesI32 = newAux("sizes_i32");
                sb.append("  ").append(sizesI32).append(" = bitcast i8* ").append(sizesArray).append(" to i32*\n");
                for (int i = 0; i < dims; i++) {
                    String ptr = newAux("sizes_ptr_" + i);
                    sb.append("  ").append(ptr).append(" = getelementptr i32, i32* ")
                        .append(sizesI32).append(", i32 ").append(i).append("\n");
                    sb.append("  store i32 ").append(getLlvmValue(sizeValues.get(i)))
                        .append(", i32* ").append(ptr).append("\n");
                }

                String callRes = newAux("multiarr");
                sb.append("  ").append(callRes).append(" = call i8* @__jnative_new_multi_array(i8* getelementptr inbounds ([")
                    .append(desc.length()).append(" x i8], [")
                    .append(desc.length()).append(" x i8]* ")
                    .append(LlvmRuntime.typeStringGlobalName(desc)).append(", i32 0, i32 0), i32 ")
                    .append(dims).append(", i32* ").append(sizesI32).append(", i32 ")
                    .append(elemSize).append(")\n");
                sb.append("  call void @free(i8* ").append(sizesArray).append(")\n");
                if (resultName != null) {
                    sb.append("  ").append(resultName).append(" = bitcast i8* ").append(callRes)
                        .append(" to ").append(typeMapper.toLlvmType(inst.getResult().getType())).append("\n");
                }
                break;
            }

            case FREE: {
                Value obj = inst.getOperands().getFirst();
                String objRef = getLlvmValue(obj);
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
                sb.append("  call void @__jnative_monitor_enter(i8* ")
                    .append(getLlvmValue(obj)).append(")\n");
                break;
            }

            case MONITOR_EXIT: {
                Value obj = inst.getOperands().getFirst();
                emitNullCheck(sb, obj, ranges);
                sb.append("  call void @__jnative_monitor_exit(i8* ")
                    .append(getLlvmValue(obj)).append(")\n");
                break;
            }

            case INSTANCEOF: {
                Value obj = inst.getOperands().getFirst();
                String typeName = extractTypeName(inst);
                String typeInfoName = globalEmitter.getTypeInfoName(typeName);
                if (typeInfoName == null) {
                    sb.append("  ; WARNING: no typeInfo for ").append(typeName).append("\n");
                    sb.append("  ").append(resultName).append(" = call i1 @__jnative_instanceof(i8* ")
                        .append(getLlvmValue(obj)).append(", i8** null)\n");
                } else {
                    sb.append("  ").append(resultName).append(" = call i1 @__jnative_instanceof(i8* ")
                        .append(getLlvmValue(obj)).append(", i8** ")
                        .append(typeInfoName).append(")\n");
                }
                break;
            }

            case CHECKCAST: {
                Value obj = inst.getOperands().getFirst();
                String typeName = extractTypeName(inst);
                String typeInfoName = globalEmitter.getTypeInfoName(typeName);
                if (typeInfoName == null) {
                    sb.append("  ; WARNING: no typeInfo for ").append(typeName).append(", checkcast skipped\n");
                    sb.append("  ").append(resultName).append(" = bitcast i8* ").append(getLlvmValue(obj))
                        .append(" to ").append(typeMapper.toLlvmType(inst.getResult().getType())).append("\n");
                } else {
                    String okReg = newAux("ok");
                    String failLabel = newLabel("check_fail");
                    String okLabel = newLabel("check_ok");
                    sb.append("  ").append(okReg).append(" = call i1 @__jnative_instanceof(i8* ")
                        .append(getLlvmValue(obj)).append(", i8** ")
                        .append(typeInfoName).append(")\n");
                    sb.append("  br i1 ").append(okReg)
                        .append(", label %").append(okLabel)
                        .append(", label %").append(failLabel).append("\n");
                    sb.append(failLabel).append(":\n");
                    emitThrowHelper(sb, "@__jnative_throw_class_cast_exception", ranges);
                    sb.append(okLabel).append(":\n");
                    sb.append("  ").append(resultName).append(" = bitcast i8* ").append(getLlvmValue(obj))
                        .append(" to ").append(typeMapper.toLlvmType(inst.getResult().getType())).append("\n");
                }
                break;
            }

            case ARRAYLENGTH: {
                Value arr = inst.getOperands().getFirst();
                String arrRef = getLlvmValue(arr);
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
                String arrRef = getLlvmValue(arr);
                String idxRef = getLlvmValue(idx);
                String idxI32 = castValueToType(sb, idxRef, idx.getType(), Type.INT);
                emitNullCheck(sb, arr, ranges);
                emitBoundsCheck(sb, arr, idxI32, ranges);
                Type elemType = inst.getResult().getType();
                int elemSize = getElementSizeOfType(elemType);
                String offset = newAux("offset");
                sb.append("  ").append(offset).append(" = mul i32 ")
                    .append(idxI32).append(", ").append(elemSize).append("\n");
                String offset64 = newAux("offset64");
                sb.append("  ").append(offset64).append(" = zext i32 ").append(offset).append(" to i64\n");
                String basePtr = newAux("baseptr");
                sb.append("  ").append(basePtr).append(" = getelementptr i8, i8* ").append(arrRef)
                    .append(", i64 4\n");
                String elemPtr = newAux("elemptr");
                sb.append("  ").append(elemPtr).append(" = getelementptr i8, i8* ").append(basePtr)
                    .append(", i64 ").append(offset64).append("\n");
                String ptrCast = newAux("ptrcast");
                String llvmType = typeMapper.toLlvmType(elemType);
                sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(elemPtr)
                    .append(" to ").append(llvmType).append("*\n");
                sb.append("  ").append(resultName).append(" = load ").append(llvmType)
                    .append(", ").append(llvmType).append("* ").append(ptrCast).append("\n");
                break;
            }

            case ASTORE: {
                if (inst.getOperands().size() < 3) break;
                Value arr = inst.getOperands().get(0);
                Value idx = inst.getOperands().get(1);
                Value val = inst.getOperands().get(2);
                String arrRef = getLlvmValue(arr);
                String idxRef = getLlvmValue(idx);
                String valRef = getLlvmValue(val);
                String idxI32 = castValueToType(sb, idxRef, idx.getType(), Type.INT);
                emitNullCheck(sb, arr, ranges);
                emitBoundsCheck(sb, arr, idxI32, ranges);
                Type elemType = val.getType();
                int elemSize = getElementSizeOfType(elemType);
                String offset = newAux("offset");
                sb.append("  ").append(offset).append(" = mul i32 ")
                    .append(idxI32).append(", ").append(elemSize).append("\n");
                String offset64 = newAux("offset64");
                sb.append("  ").append(offset64).append(" = zext i32 ").append(offset).append(" to i64\n");
                String basePtr = newAux("baseptr");
                sb.append("  ").append(basePtr).append(" = getelementptr i8, i8* ").append(arrRef)
                    .append(", i64 4\n");
                String elemPtr = newAux("elemptr");
                sb.append("  ").append(elemPtr).append(" = getelementptr i8, i8* ").append(basePtr)
                    .append(", i64 ").append(offset64).append("\n");
                String ptrCast = newAux("ptrcast");
                String llvmType = typeMapper.toLlvmType(elemType);
                sb.append("  ").append(ptrCast).append(" = bitcast i8* ").append(elemPtr)
                    .append(" to ").append(llvmType).append("*\n");
                sb.append("  store ").append(llvmType).append(" ").append(valRef)
                    .append(", ").append(llvmType).append("* ").append(ptrCast).append("\n");
                break;
            }

            case PHI: {
                BasicBlock parent = inst.getParent();
                List<BasicBlock> preds = parent != null ? parent.getPredecessors() : new ArrayList<>();
                sb.append("  ").append(resultName).append(" = phi ");
                sb.append(typeMapper.toLlvmType(inst.getResult().getType())).append(" ");
                for (int i = 0; i < inst.getOperands().size(); i++) {
                    if (i > 0) sb.append(", ");
                    Value phiOp = inst.getOperands().get(i);
                    String valRef = getLlvmValue(phiOp);
                    String blockLabel = (i < preds.size()) ? llvmLabel(preds.get(i)) : "unknown";
                    sb.append("[ ").append(valRef).append(", %").append(blockLabel).append(" ]");
                }
                sb.append("\n");
                break;
            }

            case INVOKEDYNAMIC: {
                InvokeDynamicInfo dynInfo = (InvokeDynamicInfo) inst.getInvokedynamicData();
                ResolvedCall call = dynInfo.resolvedCall();
                if (call == null) {
                    sb.append("  ; unresolved invokedynamic\n");
                    // If the result is unused - do nothing,
                    // otherwise map it to null (valid LLVM constant)
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

    // ----- Lambda / invokedynamic helpers -----

    private void emitLambdaCreation(StringBuilder sb, Instruction inst, ResolvedCall call, String resultName) {
        String lambdaId = call.getLambdaStructName();
        List<Value> captured = inst.getOperands();
        List<Type> capturedTypes = call.getCapturedTypes();

        String structName = globalEmitter.registerLambdaStruct(lambdaId, capturedTypes);
        String adaptorName = "adaptor_" + lambdaId;
        String vtableName = globalEmitter.registerLambdaVtable(lambdaId, adaptorName);

        // Memory allocation
        String allocSize = newAux("lambda_alloc_size");
        sb.append("  ").append(allocSize).append(" = call i64 @llvm.objectsize.i64.p0i8(i8* null, i1 true)\n");
        String alloc = newAux("lambda_alloc");
        sb.append("  ").append(alloc).append(" = call i8* @malloc(i64 ").append(allocSize).append(")\n");

        String objPtr;
        // If the result is unused, still create the object (but it may be optimized)
        objPtr = Objects.requireNonNullElseGet(resultName, () -> newAux("lambda_obj"));
        sb.append("  ").append(objPtr).append(" = bitcast i8* ").append(alloc)
            .append(" to ").append(structName).append("*\n");

        // Set vtable (first field)
        String vtablePtr = newAux("vtable_ptr");
        sb.append("  ").append(vtablePtr).append(" = bitcast [1 x i8*]* ").append(vtableName).append(" to i8*\n");
        String objVtable = newAux("obj_vtable_slot");
        sb.append("  ").append(objVtable).append(" = bitcast ").append(structName)
            .append("* ").append(objPtr).append(" to i8**\n");
        sb.append("  store i8* ").append(vtablePtr).append(", i8** ").append(objVtable).append("\n");

        // Fill captured fields (offset 8 - after vtable)
        int offset = 8;
        for (Value cap : captured) {
            String fieldLlvm = typeMapper.toLlvmType(cap.getType());
            String fieldPtr = newAux("cap_ptr");
            sb.append("  ").append(fieldPtr).append(" = getelementptr i8, i8* ").append(alloc)
                .append(", i64 ").append(offset).append("\n");
            String castPtr = newAux("cap_cast");
            sb.append("  ").append(castPtr).append(" = bitcast i8* ").append(fieldPtr)
                .append(" to ").append(fieldLlvm).append("*\n");
            sb.append("  store ").append(fieldLlvm).append(" ").append(getLlvmValue(cap))
                .append(", ").append(fieldLlvm).append("* ").append(castPtr).append("\n");
            offset += getElementSizeOfType(cap.getType());
        }

        if (inst.getResult() != null && resultName != null) {
            valueMapper.setValue(inst.getResult(), objPtr);
        }
    }

    private void emitConcatCall(StringBuilder sb, Instruction inst, String resultName) {
        List<Value> args = inst.getOperands();
        sb.append("  %concat_result = call i8* @__jnative_concat_strings(i32 ").append(args.size());
        for (Value arg : args) {
            sb.append(", ").append(typeMapper.toLlvmType(arg.getType()))
                .append(" ").append(getLlvmValue(arg));
        }
        sb.append(")\n");
        if (inst.getResult() != null && resultName != null) {
            String casted = newAux("concat_cast");
            sb.append("  ").append(casted).append(" = bitcast i8* %concat_result to ")
                .append(typeMapper.toLlvmType(inst.getResult().getType())).append("\n");
            valueMapper.setValue(inst.getResult(), casted);
        }
    }

    private void emitCall(StringBuilder sb, Type retType, String resultName,
                          String funcCallee, String argList, List<TryCatchRange> ranges) {
        String retLlvm = typeMapper.toLlvmType(retType);
        if (ranges.isEmpty()) {
            if (!retType.isVoid()) {
                sb.append("  ").append(resultName).append(" = call ")
                    .append(retLlvm).append(" ").append(funcCallee)
                    .append("(").append(argList).append(")\n");
            } else {
                sb.append("  call ").append(retLlvm).append(" ").append(funcCallee)
                    .append("(").append(argList).append(")\n");
            }
            return;
        }
        emitTryGuard(sb, ranges, inner -> {
            if (!retType.isVoid()) {
                inner.append("  ").append(resultName).append(" = call ")
                    .append(retLlvm).append(" ").append(funcCallee)
                    .append("(").append(argList).append(")\n");
            } else {
                inner.append("  call ").append(retLlvm).append(" ").append(funcCallee)
                    .append("(").append(argList).append(")\n");
            }
        }, false);
    }

    private void emitDivByZeroCheck(StringBuilder sb, Value divisor, List<TryCatchRange> ranges) {
        String ty = typeMapper.toLlvmType(divisor.getType());
        String chk = newAux("div_chk");
        String throwBlk = newLabel("throw_divzero");
        String cont = newLabel("div_ok");
        sb.append("  ").append(chk).append(" = icmp ne ").append(ty).append(" ")
            .append(getLlvmValue(divisor)).append(", 0\n");
        sb.append("  br i1 ").append(chk)
            .append(", label %").append(cont)
            .append(", label %").append(throwBlk).append("\n");
        sb.append(throwBlk).append(":\n");
        emitThrowHelper(sb, "@__jnative_throw_arithmetic_exception", ranges);
        sb.append(cont).append(":\n");
    }

    // ----- Exception model based on setjmp/longjmp -----

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
                String valRef = getLlvmValue(retVal);
                valRef = castValueToType(sb, valRef, retVal.getType(), funcRetType);
                sb.append("  ret ").append(typeMapper.toLlvmType(funcRetType))
                    .append(" ").append(valRef).append("\n");
            } else {
                if (funcRetType.isVoid()) {
                    sb.append("  ret void\n");
                } else {
                    String zero = getZeroValue(funcRetType);
                    sb.append("  ret ").append(typeMapper.toLlvmType(funcRetType))
                        .append(" ").append(zero).append("\n");
                }
            }
        } else if (term instanceof BranchTerminator bt) {
            sb.append("  br label %").append(llvmLabel(bt.getTarget())).append("\n");
        } else if (term instanceof CondBranchTerminator cbt) {
            String cond = getLlvmValue(cbt.getCondition());
            sb.append("  br i1 ").append(cond).append(", label %")
                .append(llvmLabel(cbt.getTrueTarget())).append(", label %")
                .append(llvmLabel(cbt.getFalseTarget())).append("\n");
        } else if (term instanceof ThrowTerminator tt) {
            Value exc = tt.getException();
            if (ranges.isEmpty()) {
                if (exc != null) {
                    sb.append("  call void @__jnative_throw_exception(i8* ")
                        .append(getLlvmValue(exc)).append(")\n");
                } else {
                    sb.append("  call void @__jnative_throw_null_pointer_exception()\n");
                }
                sb.append("  unreachable\n");
            } else {
                final Value excVal = exc;
                emitTryGuard(sb, ranges, inner -> {
                    if (excVal != null) {
                        inner.append("  call void @__jnative_throw_exception(i8* ")
                            .append(getLlvmValue(excVal)).append(")\n");
                    } else {
                        inner.append("  call void @__jnative_throw_null_pointer_exception()\n");
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
            String keyRef = getLlvmValue(key);
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
                String blockAddr = getLlvmValue(ibt.getTargetBlock());
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

    // ----- Helper methods -----

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

        // Reference / array / null → bitcast
        if ((src.isReference() || src.isArray() || src.isNull()) &&
            (dest.isReference() || dest.isArray() || dest.isNull())) {
            return "bitcast";
        }

        // Integer → pointer
        if (isIntegerType(src) && (dest.isReference() || dest.isArray() || dest.isNull() || dest.isBlock())) {
            return "inttoptr";
        }

        // Pointer → integer
        if ((src.isReference() || src.isArray() || src.isNull() || src.isBlock()) && isIntegerType(dest)) {
            return "ptrtoint";
        }

        // Primitive conversions
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

        // Fallback
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
        if (type.isReference() || type.isArray() || type.isNull() || type.isBlock()) {
            return "null";
        }
        if (type == Type.BOOLEAN) return "false";
        if (type == Type.BYTE || type == Type.SHORT || type == Type.INT || type == Type.LONG) return "0";
        if (type == Type.FLOAT) return "0.0";
        if (type == Type.DOUBLE) return "0.0";
        if (type.isVoid()) return "void";
        return "0";
    }

    private String getLlvmValue(Value v) {
        if (v == null) return "null";
        if (v instanceof Constant c) {
            return constantToLlvmLiteral(c);
        }
        String name = valueMapper.getValue(v);
        if (name == null) {
            return getDefaultValue(v.getType());
        }
        return name;
    }

    private String constantToLlvmLiteral(Constant c) {
        Object val = c.getValue();
        if (val == null) return "null";
        Type type = c.getType();

        if (type == Type.INT) {
            return val.toString();
        }
        if (type == Type.LONG) {
            return val.toString();
        }
        if (type == Type.FLOAT || type == Type.DOUBLE) {
            double d = ((Number) val).doubleValue();
            String s = Double.toString(d);
            s = s.replace('E', 'e');
            return s;
        }
        if (type == Type.BOOLEAN) {
            return ((Boolean) val) ? "true" : "false";
        }
        return "null";
    }

    private String castValueToType(StringBuilder sb, String value, Type fromType, Type toType) {
        // If the value is the literal "null" and the target type is integer, return "0" directly.
        if ("null".equals(value) && isIntegerType(toType)) {
            return "0";
        }

        // If the value is a floating-point literal that represents a whole number
        // and the target type is integer, convert the literal to an integer literal.
        if (value.matches("-?\\d+\\.0") && (toType == Type.INT || toType == Type.LONG || toType == Type.BYTE ||
            toType == Type.SHORT || toType == Type.CHAR || toType == Type.BOOLEAN)) {
            // Extract the integer part (before the decimal point)
            return value.substring(0, value.indexOf('.'));
        }

        String fromLlvm = typeMapper.toLlvmType(fromType);
        String toLlvm = typeMapper.toLlvmType(toType);
        if (fromLlvm.equals(toLlvm)) {
            return value;
        }
        String castName = newAux("cast");
        String castOp;

        // Determine cast op based on LLVM types
        boolean fromPtr = fromLlvm.endsWith("*");
        boolean toPtr = toLlvm.endsWith("*");
        boolean fromInt = fromLlvm.matches("i\\d+");
        boolean toInt = toLlvm.matches("i\\d+");

        if (fromPtr && toInt) {
            castOp = "ptrtoint";
        } else if (fromInt && toPtr) {
            castOp = "inttoptr";
        } else if (fromPtr && toPtr) {
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
        } else if (fromLlvm.equals("float") && toLlvm.equals("double")) {
            castOp = "fpext";
        } else if (fromLlvm.equals("double") && toLlvm.equals("float")) {
            castOp = "fptrunc";
        } else if (fromInt && (toLlvm.equals("float") || toLlvm.equals("double"))) {
            castOp = "sitofp";
        } else if ((fromLlvm.equals("float") || fromLlvm.equals("double")) && toInt) {
            castOp = "fptosi";
        } else {
            castOp = "bitcast";
        }

        // Final safety net: if it's still bitcast but types are pointer/integer, correct it
        if (castOp.equals("bitcast")) {
            if (fromPtr && toInt) {
                castOp = "ptrtoint";
            } else if (fromInt && toPtr) {
                castOp = "inttoptr";
            }
        }

        sb.append("  ").append(castName).append(" = ").append(castOp)
            .append(" ").append(fromLlvm)
            .append(" ").append(value)
            .append(" to ").append(toLlvm).append("\n");
        return castName;
    }

    private void ensureFunctionDeclared(String owner, String methodName, String descriptor) {
        // Build the full callable name as it appears in the IR constant
        String fullName = owner + "." + methodName + descriptor;
        // Use the same mangling as the call site (fallback path)
        String mangled = LlvmRuntime.mangleMethod(owner, methodName, descriptor);
        if (module.getFunction(mangled) != null) {
            return; // already declared
        }

        Type retType = TypeResolver.descToReturnType(descriptor);
        List<Type> paramTypes = TypeResolver.descToParamTypes(descriptor);

        // For non-static methods, the first parameter is the receiver (this)
        List<Type> allParams = new ArrayList<>();
        allParams.add(Type.reference(owner));
        allParams.addAll(paramTypes);

        Function func = new Function(mangled, retType);
        for (int i = 0; i < allParams.size(); i++) {
            func.addParameter(new Parameter(allParams.get(i), i));
        }
        // Adding a function without an entry block creates an external declaration
        module.addFunction(func);
    }
}
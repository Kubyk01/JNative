package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.ssa.GraphUtils;
import io.github.kubyk01.domain.analyzer.ir.BasicBlock;
import io.github.kubyk01.domain.analyzer.ir.BranchTerminator;
import io.github.kubyk01.domain.analyzer.ir.CondBranchTerminator;
import io.github.kubyk01.domain.analyzer.ir.Constant;
import io.github.kubyk01.domain.analyzer.ir.Function;
import io.github.kubyk01.domain.analyzer.ir.IndirectBranchTerminator;
import io.github.kubyk01.domain.analyzer.ir.Instruction;
import io.github.kubyk01.domain.analyzer.ir.LookupSwitchTerminator;
import io.github.kubyk01.domain.analyzer.ir.Module;
import io.github.kubyk01.domain.analyzer.ir.Opcode;
import io.github.kubyk01.domain.analyzer.ir.Parameter;
import io.github.kubyk01.domain.analyzer.ir.ReturnTerminator;
import io.github.kubyk01.domain.analyzer.ir.TableSwitchTerminator;
import io.github.kubyk01.domain.analyzer.ir.Terminator;
import io.github.kubyk01.domain.analyzer.ir.ThrowTerminator;
import io.github.kubyk01.domain.analyzer.ir.TryCatchRange;
import io.github.kubyk01.domain.analyzer.ir.Type;
import io.github.kubyk01.domain.analyzer.ir.Value;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class LlvmFunctionEmitter {

    /**
     * Size of jmp_buf in bytes (glibc x86_64: sizeof(jmp_buf) == 200).
     * Taken with headroom and alignment of 16.
     */
    private static final int JMP_BUF_SIZE = 256;

    private final Module module;
    private final LlvmTypeMapper typeMapper;
    private final LlvmGlobalEmitter globalEmitter;

    private final LlvmValueMapper valueMapper = new LlvmValueMapper();
    private int tmpCounter = 0;
    private int labelCounter = 0;
    private BasicBlock currentEntryBlock;

    // try-catch: block -> applicable ranges (in JVM exception table order,
    // i.e. by descending priority), range -> handler block, range -> jmp_buf ordinal
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
                    Type type = inferLocalType(func, idx);
                    sb.append("  %local_").append(idx).append(" = alloca ")
                            .append(typeMapper.toLlvmType(type)).append(", align 8\n");
                }
                // jmp_buf for each try-range of the function
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

    /**
     * Builds the map "block -> list of try-ranges" (in exception table order)
     * and resolves handlers. Ranges with unresolved blocks are skipped.
     */
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

    private Type inferLocalType(Function func, int idx) {
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

    private String emitBlock(BasicBlock block, boolean labelAlreadyEmitted) {
        StringBuilder sb = new StringBuilder();
        if (!labelAlreadyEmitted) {
            sb.append(llvmLabel(block)).append(":\n");
        }

        // Applicable try-ranges (in exception table order): throwing
        // operations of the block are guarded by setjmp/longjmp instead of invoke/landingpad
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

    /**
     * Block label: used both when defining the block and when referenced from branches/PHI.
     */
    private String llvmLabel(BasicBlock block) {
        if (block == currentEntryBlock) {
            return "entry";
        }
        return block.getLabel().replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * Unique name for an auxiliary register within the function (SSA).
     */
    private String newAux(String prefix) {
        return "%" + prefix + "_" + (tmpCounter++);
    }

    /**
     * Unique auxiliary block label (without the % prefix).
     */
    private String newLabel(String prefix) {
        return prefix + "_" + (labelCounter++);
    }

    // ----- Null / bounds / division checks with try-catch support -----

    /**
     * Emits throwing a standard runtime exception.
     * Outside a try-range — direct call; inside — through the setjmp guard,
     * so the exception can be caught.
     */
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

    /**
     * Emits a null-check: on null – throws NullPointerException.
     */
    private void emitNullCheck(StringBuilder sb, Value obj, List<TryCatchRange> ranges) {
        String ty = typeMapper.toLlvmType(obj.getType());
        String chk = newAux("npe_chk");
        String throwBlk = newLabel("throw_npe");
        String cont = newLabel("npe_ok");
        sb.append("  ").append(chk).append(" = icmp ne ").append(ty).append(" ")
                .append(valueMapper.getValue(obj)).append(", null\n");
        sb.append("  br i1 ").append(chk)
                .append(", label %").append(cont)
                .append(", label %").append(throwBlk).append("\n");
        sb.append(throwBlk).append(":\n");
        emitThrowHelper(sb, "@__jnative_throw_null_pointer_exception", ranges);
        sb.append(cont).append(":\n");
    }

    /**
     * Emits an array bounds check: the index must be in [0, length),
     * the length is stored in the header (first 4 bytes).
     */
    private void emitBoundsCheck(StringBuilder sb, Value arr, Value idx, List<TryCatchRange> ranges) {
        String arrRef = valueMapper.getValue(arr);
        String idxRef = valueMapper.getValue(idx);
        String lenPtr = newAux("lenptr");
        String len = newAux("len");
        sb.append("  ").append(lenPtr).append(" = bitcast i8* ").append(arrRef).append(" to i32*\n");
        sb.append("  ").append(len).append(" = load i32, i32* ").append(lenPtr).append("\n");

        String chk1 = newAux("bnd_chk1");
        String chk2 = newAux("bnd_chk2");
        String ok = newAux("bnd_ok");
        String throwBlk = newLabel("throw_aioobe");
        String cont = newLabel("bnd_ok");
        sb.append("  ").append(chk1).append(" = icmp sge i32 ").append(idxRef).append(", 0\n");
        sb.append("  ").append(chk2).append(" = icmp slt i32 ").append(idxRef).append(", ").append(len).append("\n");
        sb.append("  ").append(ok).append(" = and i1 ").append(chk1).append(", ").append(chk2).append("\n");
        sb.append("  br i1 ").append(ok)
                .append(", label %").append(cont)
                .append(", label %").append(throwBlk).append("\n");
        sb.append(throwBlk).append(":\n");
        emitThrowHelper(sb, "@__jnative_throw_array_index_out_of_bounds", ranges);
        sb.append(cont).append(":\n");
    }

    /**
     * Returns the array element size in bytes for the given element type.
     */
    private int getElementSize(Type elemType) {
        if (elemType.isPrimitive()) {
            if (elemType == Type.BOOLEAN || elemType == Type.BYTE) return 1;
            if (elemType == Type.SHORT || elemType == Type.CHAR) return 2;
            if (elemType == Type.INT || elemType == Type.FLOAT) return 4;
            if (elemType == Type.LONG || elemType == Type.DOUBLE) return 8;
        }
        if (elemType.isReference() || elemType.isArray()) {
            return 8; // pointer
        }
        return 8; // fallback
    }

    /**
     * Base element size of a multidimensional array by its full descriptor
     * (e.g. "[[I" or "[[Ljava/lang/String;").
     */
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
        return 8; // references
    }

    /**
     * Element type from a NEW_ARRAY constant: supports primitive names ("int", ...)
     * and descriptors ("I", "Ljava/lang/String;", ...).
     */
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
                sb.append("  ").append(resultName).append(" = load ")
                        .append(llvmType).append(", ")
                        .append(llvmType).append("* %local_").append(idx).append("\n");
                break;
            }
            case STORE: {
                Value stored = inst.getOperands().getFirst();
                int idx = inst.getLocalIndex();
                Function func = inst.getParent().getFunction();
                Type localType = inferLocalType(func, idx); // determine the local variable type
                String valRef;
                if (stored instanceof Constant) {
                    Object val = ((Constant) stored).getValue();
                    if (val == null) {
                        // for null pick the literal based on the storage type
                        if (localType.isReference() || localType.isArray() || localType.isNull()) {
                            valRef = "null";
                        } else {
                            valRef = "0";
                        }
                    } else {
                        // other constants – convert to an LLVM literal
                        valRef = constantToLlvmLiteral((Constant) stored);
                    }
                } else {
                    valRef = valueMapper.getValue(stored);
                    if (valRef == null) {
                        valRef = getDefaultValue(stored.getType());
                    }
                }
                String llvmType = typeMapper.toLlvmType(localType);
                sb.append("  store ").append(llvmType).append(" ").append(valRef)
                    .append(", ").append(llvmType).append("* %local_").append(idx).append("\n");
                if (inst.getResult() != null) {
                    sb.append("  ").append(resultName).append(" = load ").append(llvmType)
                        .append(", ").append(llvmType).append("* %local_").append(idx).append("\n");
                }
                break;
            }
            case ADD: case SUB: case MUL: case DIV: case REM:
            case AND: case OR: case XOR: case SHL: case SHR: case USHR: {
                Value left = inst.getOperands().get(0);
                Value right = inst.getOperands().get(1);
                // Division by zero – ArithmeticException (only for integers inside a try-range)
                if ((op == Opcode.DIV || op == Opcode.REM) && !ranges.isEmpty()) {
                    Type lt = left.getType();
                    if (lt == Type.INT || lt == Type.LONG) {
                        emitDivByZeroCheck(sb, right, ranges);
                    }
                }
                String l = valueMapper.getValue(left);
                String r = valueMapper.getValue(right);
                String llvmOp = mapArithOp(op);
                sb.append("  ").append(resultName).append(" = ").append(llvmOp)
                        .append(" ").append(typeMapper.toLlvmType(left.getType()))
                        .append(" ").append(l).append(", ").append(r).append("\n");
                break;
            }
            case EQ: case NE: case LT: case LE: case GT: case GE: {
                Value left = inst.getOperands().get(0);
                Value right = inst.getOperands().get(1);
                String l = valueMapper.getValue(left);
                String r = valueMapper.getValue(right);

                // Explicit handling of the null constant
                if (left instanceof Constant && ((Constant) left).getValue() == null) {
                    l = "null";
                }
                if (right instanceof Constant && ((Constant) right).getValue() == null) {
                    r = "null";
                }

                // If the value is still not found, substitute a literal based on the type
                if (l == null) {
                    l = getDefaultValue(left.getType());
                }
                if (r == null) {
                    r = getDefaultValue(right.getType());
                }

                // Replace "null" with "0" only for non-pointers (primitives)
                if ("null".equals(l) && !right.getType().isReference() && !right.getType().isArray() && !right.getType().isNull()) {
                    l = "0";
                }
                if ("null".equals(r) && !left.getType().isReference() && !left.getType().isArray() && !left.getType().isNull()) {
                    r = "0";
                }

                // Key fix: if one operand is a pointer and the other is "0", change "0" to "null"
                Type leftType = left.getType();
                Type rightType = right.getType();
                boolean leftIsPtr = leftType.isReference() || leftType.isArray() || leftType.isNull() || leftType.isBlock();
                boolean rightIsPtr = rightType.isReference() || rightType.isArray() || rightType.isNull() || rightType.isBlock();

                if (leftIsPtr && "0".equals(r)) {
                    r = "null";
                }
                if (rightIsPtr && "0".equals(l)) {
                    l = "null";
                }

                String cmpOp = mapCmpOp(op);
                sb.append("  ").append(resultName).append(" = icmp ").append(cmpOp)
                    .append(" ").append(typeMapper.toLlvmType(left.getType()))
                    .append(" ").append(l).append(", ").append(r).append("\n");
                break;
            }

            case CAST: {
                Value val = inst.getOperands().getFirst();
                String v = valueMapper.getValue(val);
                Type dest = inst.getResult().getType();
                if (val.getType().isReference() && dest.isReference()) {
                    sb.append("  ").append(resultName).append(" = bitcast ")
                            .append(typeMapper.toLlvmType(val.getType())).append(" ").append(v)
                            .append(" to ").append(typeMapper.toLlvmType(dest)).append("\n");
                } else {
                    String cast = castToLlvm(dest);
                    sb.append("  ").append(resultName).append(" = ").append(cast)
                            .append(" ").append(typeMapper.toLlvmType(val.getType())).append(" ").append(v)
                            .append(" to ").append(typeMapper.toLlvmType(dest)).append("\n");
                }
                break;
            }
            case GET_FIELD: {
                Value base = inst.getOperands().getFirst();
                String fieldName = extractFieldName(inst);
                String baseRef = valueMapper.getValue(base);
                int offset = globalEmitter.getFieldOffset(extractClassName(base), fieldName);
                emitNullCheck(sb, base, ranges);
                String baseI8 = newAux("base_i8");
                sb.append("  ").append(baseI8).append(" = bitcast ")
                    .append(typeMapper.toLlvmType(base.getType())).append(" ").append(baseRef)
                    .append(" to i8*\n");
                String gep = newAux("gep");
                sb.append("  ").append(gep).append(" = getelementptr i8, i8* ").append(baseI8)
                    .append(", i32 ").append(offset).append("\n");
                String ptrCast = newAux("ptrcast");
                Type fieldType = inst.getResult().getType();   // <- use the result type
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
                String baseRef = valueMapper.getValue(base);
                int offset = globalEmitter.getFieldOffset(extractClassName(base), fieldName);
                emitNullCheck(sb, base, ranges);
                String baseI8 = newAux("base_i8");
                sb.append("  ").append(baseI8).append(" = bitcast ")
                    .append(typeMapper.toLlvmType(base.getType())).append(" ").append(baseRef)
                    .append(" to i8*\n");
                String gep = newAux("gep");
                sb.append("  ").append(gep).append(" = getelementptr i8, i8* ").append(baseI8)
                    .append(", i32 ").append(offset).append("\n");
                String ptrCast = newAux("ptrcast");
                // Determine the field type
                Type fieldType = globalEmitter.getFieldType(extractClassName(base), fieldName);
                if (fieldType == null) {
                    // fallback: use the rhs type
                    fieldType = rhs.getType();
                }
                String fieldLlvm = typeMapper.toLlvmType(fieldType);
                // Build the value to store
                String rhsRef;
                if (rhs instanceof Constant && ((Constant) rhs).getValue() == null) {
                    if (fieldType.isReference() || fieldType.isArray() || fieldType.isNull() || fieldType.isBlock()) {
                        rhsRef = "null";
                    } else {
                        rhsRef = "0";
                    }
                } else {
                    rhsRef = valueMapper.getValue(rhs);
                    if (rhsRef == null) {
                        rhsRef = getDefaultValue(rhs.getType());
                    }
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
                Type fieldType = inst.getResult().getType();   // <- use the result type
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
                String rhsRef;
                if (rhs instanceof Constant) {
                    rhsRef = constantToLlvmLiteral((Constant) rhs);
                } else {
                    rhsRef = valueMapper.getValue(rhs);
                    if (rhsRef == null) rhsRef = getDefaultValue(rhs.getType());
                }
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
                // Constant format: "owner/name.name(desc)" – extract the "name(desc)" signature
                String calleeName = ((Constant) calleeConst).getValue().toString();
                int dotIdx = calleeName.lastIndexOf('.');
                if (dotIdx < 0) break;
                String sig = calleeName.substring(dotIdx + 1);
                int idx = globalEmitter.getMethodIndex(sig);

                Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;

                if (idx < 0) {
                    // fallback: direct call of the implementation (method not found in vtable)
                    sb.append("  ; WARNING: virtual method not found in vtable, using direct call\n");
                    String owner = calleeName.substring(0, dotIdx);
                    int parenIdx = sig.indexOf('(');
                    String mName = parenIdx > 0 ? sig.substring(0, parenIdx) : sig;
                    String mDesc = parenIdx > 0 ? sig.substring(parenIdx) : "";
                    String funcName = LlvmRuntime.mangleMethod(owner, mName, mDesc);
                    StringBuilder argList = new StringBuilder();
                    for (int i = 0; i < operands.size(); i++) {
                        if (i == 1) continue; // skip the callee constant
                        if (!argList.isEmpty()) argList.append(", ");
                        argList.append(typeMapper.toLlvmType(operands.get(i).getType()))
                                .append(" ").append(valueMapper.getValue(operands.get(i)));
                    }
                    emitCall(sb, retType, resultName, "@" + funcName, argList.toString(), ranges);
                    break;
                }

                // Receiver null-check
                emitNullCheck(sb, receiver, ranges);

                // Load the vtable pointer from the start of the object (offset 0)
                String receiverRef = valueMapper.getValue(receiver);
                String vtSlotPtr = newAux("vtslot");
                sb.append("  ").append(vtSlotPtr).append(" = bitcast ")
                        .append(typeMapper.toLlvmType(receiver.getType())).append(" ").append(receiverRef)
                        .append(" to i8**\n");
                String vtableLoad = newAux("vtable_load");
                sb.append("  ").append(vtableLoad).append(" = load i8*, i8** ").append(vtSlotPtr).append("\n");

                // Function pointer from the vtable by index
                String funcPtrGep = newAux("funcptr_gep");
                sb.append("  ").append(funcPtrGep).append(" = getelementptr i8*, i8* ").append(vtableLoad)
                        .append(", i32 ").append(idx).append("\n");
                String funcPtr = newAux("funcptr");
                sb.append("  ").append(funcPtr).append(" = load i8*, i8* ").append(funcPtrGep).append("\n");

                // Cast to the proper function type
                String funcType = LlvmRuntime.getFunctionType(sig, typeMapper);
                String funcPtrCast = newAux("fptrcast");
                sb.append("  ").append(funcPtrCast).append(" = bitcast i8* ").append(funcPtr)
                        .append(" to ").append(funcType).append("\n");

                // Arguments: receiver + the rest (the callee constant is skipped)
                StringBuilder argList = new StringBuilder();
                for (int i = 0; i < operands.size(); i++) {
                    if (i == 1) continue;
                    if (!argList.isEmpty()) argList.append(", ");
                    argList.append(typeMapper.toLlvmType(operands.get(i).getType()))
                            .append(" ").append(valueMapper.getValue(operands.get(i)));
                }
                emitCall(sb, retType, resultName, funcPtrCast, argList.toString(), ranges);
                break;
            }

            case STATIC_CALL:
            case SPECIAL_CALL:
            case CALL: {
                String calleeName = extractCalleeName(inst);
                if (calleeName == null) break;
                // Look up the function by its original name (as written in the constant)
                Function calleeFunc = module.getFunction(calleeName);
                String mangledCallee;
                if (calleeFunc != null) {
                    // Native functions already have their final name (__jnative_...),
                    // the rest are mangled the same way as at definition (emitFunction)
                    String funcName = calleeFunc.getName();
                    mangledCallee = funcName.startsWith("__jnative_")
                            ? funcName
                            : LlvmRuntime.mangleFunction(funcName);
                } else {
                    // Native methods are registered under the name __jnative_ + mangled
                    String nativeCandidate = "__jnative_" + LlvmRuntime.mangleCallable(calleeName);
                    if (module.getFunction(nativeCandidate) != null) {
                        mangledCallee = nativeCandidate;
                    } else {
                        mangledCallee = LlvmRuntime.mangleCallable(calleeName);
                    }
                }
                List<Value> args = getCallArguments(inst);
                StringBuilder argList = new StringBuilder();
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) argList.append(", ");
                    argList.append(typeMapper.toLlvmType(args.get(i).getType()))
                           .append(" ").append(valueMapper.getValue(args.get(i)));
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
                // Store vtable pointer at offset 0
                String vtableName = globalEmitter.getVtableName(className);
                if (vtableName != null) {
                    String vtablePtr = newAux("vtableptr");
                    sb.append("  ").append(vtablePtr).append(" = bitcast [").append(globalEmitter.getTotalMethods())
                        .append(" x i8*]* ").append(vtableName).append(" to i8*\n");
                    // --- FIX: create a temporary for the bitcast of the object pointer ---
                    String objPtrCast = newAux("objptrcast");
                    sb.append("  ").append(objPtrCast).append(" = bitcast ").append(structType)
                        .append("* ").append(resultName).append(" to i8**\n");
                    sb.append("  store i8* ").append(vtablePtr).append(", i8** ").append(objPtrCast).append("\n");
                }
                break;
            }

            case NEW_ARRAY: {
                // Operands: [0] – length, [1] – constant with the element type
                if (inst.getOperands().size() < 2) break;
                Value sizeVal = inst.getOperands().get(0);
                Value elemTypeConst = inst.getOperands().get(1);
                if (!(elemTypeConst instanceof Constant)) break;
                Type elemType = elemTypeFromConst(((Constant) elemTypeConst).getValue().toString());
                int elemSize = getElementSize(elemType);
                String sizeRef = valueMapper.getValue(sizeVal);
                // Total size: 4 (length header) + elemSize * length
                String totalSize = newAux("total_size");
                sb.append("  ").append(totalSize).append(" = mul i32 ")
                        .append(sizeRef).append(", ").append(elemSize).append("\n");
                String totalSize64 = newAux("total_size64");
                sb.append("  ").append(totalSize64).append(" = zext i32 ").append(totalSize).append(" to i64\n");
                String allocSize = newAux("alloc_size");
                sb.append("  ").append(allocSize).append(" = add i64 ").append(totalSize64).append(", 4\n");
                String allocReg = newAux("alloc");
                sb.append("  ").append(allocReg).append(" = call i8* @malloc(i64 ").append(allocSize).append(")\n");
                // Write the length into the first 4 bytes
                String lenPtr = newAux("lenptr");
                sb.append("  ").append(lenPtr).append(" = bitcast i8* ").append(allocReg).append(" to i32*\n");
                sb.append("  store i32 ").append(sizeRef).append(", i32* ").append(lenPtr).append("\n");
                sb.append("  ").append(resultName).append(" = bitcast i8* ").append(allocReg)
                        .append(" to ").append(typeMapper.toLlvmType(inst.getResult().getType())).append("\n");
                break;
            }

            case MULTI_NEW_ARRAY: {
                // Operands: [0] – constant with the type descriptor, [1..n] – sizes
                // from the outer dimension to the inner one
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

                // Allocate a temporary array of sizes (dims * 4 bytes)
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
                    sb.append("  store i32 ").append(valueMapper.getValue(sizeValues.get(i)))
                            .append(", i32* ").append(ptr).append("\n");
                }

                // The runtime function is fully implemented in LLVM IR (generateRuntimeStubs):
                // it recursively allocates nested arrays with length headers
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
                String objRef = valueMapper.getValue(obj);
                sb.append("  call void @free(i8* ").append(objRef).append(")\n");
                break;
            }

            case JSR: {
                // Subroutine return address – blockaddress of the return block
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
                        .append(valueMapper.getValue(obj)).append(")\n");
                break;
            }

            case MONITOR_EXIT: {
                Value obj = inst.getOperands().getFirst();
                emitNullCheck(sb, obj, ranges);
                sb.append("  call void @__jnative_monitor_exit(i8* ")
                        .append(valueMapper.getValue(obj)).append(")\n");
                break;
            }

            case INSTANCEOF: {
                Value obj = inst.getOperands().getFirst();
                String typeName = extractTypeName(inst);
                String typeInfoName = globalEmitter.getTypeInfoName(typeName);
                if (typeInfoName == null) {
                    // fallback (external or unknown type)
                    sb.append("  ; WARNING: no typeInfo for ").append(typeName).append("\n");
                    sb.append("  ").append(resultName).append(" = call i1 @__jnative_instanceof(i8* ")
                            .append(valueMapper.getValue(obj)).append(", i8** null)\n");
                } else {
                    sb.append("  ").append(resultName).append(" = call i1 @__jnative_instanceof(i8* ")
                            .append(valueMapper.getValue(obj)).append(", i8** ")
                            .append(typeInfoName).append(")\n");
                }
                break;
            }

            case CHECKCAST: {
                Value obj = inst.getOperands().getFirst();
                String typeName = extractTypeName(inst);
                String typeInfoName = globalEmitter.getTypeInfoName(typeName);
                if (typeInfoName == null) {
                    // fallback: always succeeds
                    sb.append("  ; WARNING: no typeInfo for ").append(typeName).append(", checkcast skipped\n");
                    sb.append("  ").append(resultName).append(" = bitcast i8* ").append(valueMapper.getValue(obj))
                            .append(" to ").append(typeMapper.toLlvmType(inst.getResult().getType())).append("\n");
                } else {
                    String okReg = newAux("ok");
                    String failLabel = newLabel("check_fail");
                    String okLabel = newLabel("check_ok");
                    sb.append("  ").append(okReg).append(" = call i1 @__jnative_instanceof(i8* ")
                            .append(valueMapper.getValue(obj)).append(", i8** ")
                            .append(typeInfoName).append(")\n");
                    sb.append("  br i1 ").append(okReg)
                            .append(", label %").append(okLabel)
                            .append(", label %").append(failLabel).append("\n");
                    sb.append(failLabel).append(":\n");
                    emitThrowHelper(sb, "@__jnative_throw_class_cast_exception", ranges);
                    sb.append(okLabel).append(":\n");
                    sb.append("  ").append(resultName).append(" = bitcast i8* ").append(valueMapper.getValue(obj))
                            .append(" to ").append(typeMapper.toLlvmType(inst.getResult().getType())).append("\n");
                }
                break;
            }

            case ARRAYLENGTH: {
                // The length is read from the header (first 4 bytes)
                Value arr = inst.getOperands().getFirst();
                String arrRef = valueMapper.getValue(arr);
                emitNullCheck(sb, arr, ranges);
                String lenPtr = newAux("lenptr");
                sb.append("  ").append(lenPtr).append(" = bitcast i8* ").append(arrRef).append(" to i32*\n");
                sb.append("  ").append(resultName).append(" = load i32, i32* ").append(lenPtr).append("\n");
                break;
            }

            case ALOAD: {
                // Load an array element: arr[index]
                if (inst.getOperands().size() < 2) break;
                Value arr = inst.getOperands().get(0);
                Value idx = inst.getOperands().get(1);
                String arrRef = valueMapper.getValue(arr);
                String idxRef = valueMapper.getValue(idx);
                emitNullCheck(sb, arr, ranges);
                emitBoundsCheck(sb, arr, idx, ranges);
                // Element address: header (4) + idx * elemSize
                Type elemType = inst.getResult().getType();
                int elemSize = getElementSize(elemType);
                String offset = newAux("offset");
                sb.append("  ").append(offset).append(" = mul i32 ")
                        .append(idxRef).append(", ").append(elemSize).append("\n");
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
                // Store an array element: arr[index] = value
                if (inst.getOperands().size() < 3) break;
                Value arr = inst.getOperands().get(0);
                Value idx = inst.getOperands().get(1);
                Value val = inst.getOperands().get(2);
                String arrRef = valueMapper.getValue(arr);
                String idxRef = valueMapper.getValue(idx);
                String valRef = valueMapper.getValue(val);
                emitNullCheck(sb, arr, ranges);
                emitBoundsCheck(sb, arr, idx, ranges);
                Type elemType = val.getType();
                int elemSize = getElementSize(elemType);
                String offset = newAux("offset");
                sb.append("  ").append(offset).append(" = mul i32 ")
                        .append(idxRef).append(", ").append(elemSize).append("\n");
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
                // Each operand corresponds to a predecessor block in the same order
                BasicBlock parent = inst.getParent();
                List<BasicBlock> preds = parent != null ? parent.getPredecessors() : new ArrayList<>();
                sb.append("  ").append(resultName).append(" = phi ");
                sb.append(typeMapper.toLlvmType(inst.getResult().getType())).append(" ");
                for (int i = 0; i < inst.getOperands().size(); i++) {
                    if (i > 0) sb.append(", ");
                    Value phiOp = inst.getOperands().get(i);
                    String valRef = valueMapper.getValue(phiOp);
                    String blockLabel = (i < preds.size()) ? llvmLabel(preds.get(i)) : "unknown";
                    sb.append("[ ").append(valRef).append(", %").append(blockLabel).append(" ]");
                }
                sb.append("\n");
                break;
            }

            default:
                sb.append("  ; unsupported opcode: ").append(op).append("\n");
        }
        return sb.toString();
    }

    /**
     * Emits a call (direct via @func or indirect via the funcCallee register).
     * Inside a try-range the call is guarded by setjmp contexts: an exception from
     * the called method is caught by the chain of catch handlers.
     */
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

    /**
     * Emits a division-by-zero check: ArithmeticException.
     */
    private void emitDivByZeroCheck(StringBuilder sb, Value divisor, List<TryCatchRange> ranges) {
        String ty = typeMapper.toLlvmType(divisor.getType());
        String chk = newAux("div_chk");
        String throwBlk = newLabel("throw_divzero");
        String cont = newLabel("div_ok");
        sb.append("  ").append(chk).append(" = icmp ne ").append(ty).append(" ")
                .append(valueMapper.getValue(divisor)).append(", 0\n");
        sb.append("  br i1 ").append(chk)
                .append(", label %").append(cont)
                .append(", label %").append(throwBlk).append("\n");
        sb.append(throwBlk).append(":\n");
        emitThrowHelper(sb, "@__jnative_throw_arithmetic_exception", ranges);
        sb.append(cont).append(":\n");
    }

    // ----- Exception model based on setjmp/longjmp -----

    /**
     * Guards a throwing operation with try-range contexts.
     * <p>
     * Scheme for ranges r0..r(k-1) (r0 has the highest priority):
     * <pre>
     *   br label %guard
     * guard:
     *   ; push in reverse order: r0 ends up on top of the handler stack
     *   push_catch(jmp_buf_(k-1), ti_(k-1)) ... push_catch(jmp_buf_0, ti_0)
     *   %ret = _setjmp(jmp_buf_0)
     *   br i1 (%ret == 0), label %body, label %catch
     * body:
     *   <operation>
     *   pop_catch() x k          ; normal path
     *   br label %cont
     * catch:
     *   %exc = get_exception_object()
     *   ; chain of type checks; on mismatch one context is popped
     *   match(r0) ? hit0 : miss0
     * miss_i: pop_catch() x 1; match(r(i+1)) ? hit(i+1) : miss(i+1)
     * hit_i:  pop_catch() x (k-i); br label %handler_i
     * miss_(k-1): pop_catch() x 1; throw_exception(%exc); unreachable  ; rethrow further
     * cont:
     * </pre>
     *
     * @param neverReturnsNormally true for throwing operations without a normal path
     */
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
                // catch-all / finally / type without typeInfo (external) – conservative match
                sb.append("  br label %").append(hitBlk).append("\n");
            }
            sb.append(hitBlk).append(":\n");
            sb.append("  call void @__jnative_pop_catch()\n".repeat(k - i));
            BasicBlock handler = handlerBlockByRange.get(r);
            sb.append("  br label %").append(llvmLabel(handler)).append("\n");
        }
        // No catch matched: pop the last context and rethrow further
        sb.append(missLabels[k - 1]).append(":\n");
        sb.append("  call void @__jnative_pop_catch()\n");
        sb.append("  call void @__jnative_throw_exception(i8* ").append(exc).append(")\n");
        sb.append("  unreachable\n");

        if (contBlk != null) {
            sb.append(contBlk).append(":\n");
        }
    }

    /**
     * The typeInfo operand for catch: the name of the global type table, or null
     * (catch-all / finally / external type).
     */
    private String catchTypeInfoOperand(TryCatchRange r) {
        return r.type != null ? globalEmitter.getTypeInfoName(r.type) : null;
    }

    private String emitTerminator(Terminator term, List<TryCatchRange> ranges) {
        StringBuilder sb = new StringBuilder();
        if (term instanceof ReturnTerminator rt) {
            if (rt.getValue() != null) {
                sb.append("  ret ").append(typeMapper.toLlvmType(rt.getValue().getType()))
                        .append(" ").append(valueMapper.getValue(rt.getValue())).append("\n");
            } else {
                sb.append("  ret void\n");
            }
        } else if (term instanceof BranchTerminator bt) {
            sb.append("  br label %").append(llvmLabel(bt.getTarget())).append("\n");
        } else if (term instanceof CondBranchTerminator cbt) {
            String cond = valueMapper.getValue(cbt.getCondition());
            sb.append("  br i1 ").append(cond).append(", label %")
                    .append(llvmLabel(cbt.getTrueTarget())).append(", label %")
                    .append(llvmLabel(cbt.getFalseTarget())).append("\n");
        } else if (term instanceof ThrowTerminator tt) {
            Value exc = tt.getException();
            if (ranges.isEmpty()) {
                if (exc != null) {
                    sb.append("  call void @__jnative_throw_exception(i8* ")
                            .append(valueMapper.getValue(exc)).append(")\n");
                } else {
                    sb.append("  call void @__jnative_throw_null_pointer_exception()\n");
                }
                sb.append("  unreachable\n");
            } else {
                // athrow inside try: the exception must be catchable
                final Value excVal = exc;
                emitTryGuard(sb, ranges, inner -> {
                    if (excVal != null) {
                        inner.append("  call void @__jnative_throw_exception(i8* ")
                                .append(valueMapper.getValue(excVal)).append(")\n");
                    } else {
                        inner.append("  call void @__jnative_throw_null_pointer_exception()\n");
                    }
                }, true);
            }
        } else if (term instanceof LookupSwitchTerminator || term instanceof TableSwitchTerminator) {
            Value key;
            if (term instanceof LookupSwitchTerminator) {
                key = ((LookupSwitchTerminator) term).getKey();
            } else {
                key = ((TableSwitchTerminator) term).getKey();
            }
            sb.append("  switch i32 ").append(valueMapper.getValue(key)).append(", label %default [\n");
            if (term instanceof LookupSwitchTerminator lst) {
                for (int i = 0; i < lst.getKeys().length; i++) {
                    sb.append("    i32 ").append(lst.getKeys()[i]).append(", label %")
                            .append(llvmLabel(lst.getTargetsArray()[i])).append("\n");
                }
                sb.append("  ]\n");
            } else {
                TableSwitchTerminator tst = (TableSwitchTerminator) term;
                for (int i = 0; i < tst.getTargetsArray().length; i++) {
                    sb.append("    i32 ").append(tst.getMin() + i).append(", label %")
                            .append(llvmLabel(tst.getTargetsArray()[i])).append("\n");
                }
                sb.append("  ]\n");
            }
        } else if (term instanceof IndirectBranchTerminator ibt) {
            List<BasicBlock> targets = ibt.getPossibleTargets();
            if (targets.isEmpty()) {
                sb.append("  unreachable\n");
            } else {
                String blockAddr = valueMapper.getValue(ibt.getTargetBlock());
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

    private String mapArithOp(Opcode op) {
        return switch (op) {
            case ADD -> "add";
            case SUB -> "sub";
            case MUL -> "mul";
            case DIV -> "sdiv";
            case REM -> "srem";
            case AND -> "and";
            case OR -> "or";
            case XOR -> "xor";
            case SHL -> "shl";
            case SHR -> "ashr";
            case USHR -> "lshr";
            default -> "add";
        };
    }

    private String mapCmpOp(Opcode op) {
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

    private String castToLlvm(Type dest) {
        if (dest == Type.LONG) return "sext";
        if (dest == Type.FLOAT || dest == Type.DOUBLE) return "sitofp";
        if (dest.isPrimitive()) return "trunc";
        return "bitcast";
    }

    private String extractFieldName(Instruction inst) {
        int fieldIdx = (inst.getOpcode() == Opcode.GET_STATIC || inst.getOpcode() == Opcode.PUT_STATIC) ? 0 : 1;
        if (inst.getOperands().size() > fieldIdx) {
            Value v = inst.getOperands().get(fieldIdx);
            if (v instanceof Constant c && c.getType().isReference()) {
                return c.getValue().toString();
            }
        }
        return "unknown";
    }

    private String extractCalleeName(Instruction inst) {
        if (!inst.getOperands().isEmpty()) {
            Value v = inst.getOperands().getFirst();
            if (v instanceof Constant c && c.getType().isReference()) {
                return c.getValue().toString();
            }
        }
        return null;
    }

    private String extractTypeName(Instruction inst) {
        // For NEW, INSTANCEOF, CHECKCAST – the first reference constant among the operands
        for (Value v : inst.getOperands()) {
            if (v instanceof Constant c && c.getType().isReference()) {
                return c.getValue().toString();
            }
        }
        return "java/lang/Object";
    }

    private String extractClassName(Value v) {
        if (v.getType().isReference()) {
            return v.getType().getClassName();
        } else if (v.getType().isArray()) {
            Type elem = v.getType().getElementType();
            if (elem.isReference()) return elem.getClassName();
            else return "java/lang/Object"; // primitive array
        }
        return "java/lang/Object";
    }

    private List<Value> getCallArguments(Instruction inst) {
        List<Value> args = new ArrayList<>();
        boolean skipFirst = true;
        for (Value op : inst.getOperands()) {
            if (skipFirst) { skipFirst = false; continue; }
            args.add(op);
        }
        return args;
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

    private String constantToLlvmLiteral(Constant c) {
        Object val = c.getValue();
        if (val == null) return "null";
        Type type = c.getType();
        if (type == Type.INT) return val.toString();
        if (type == Type.LONG) return val.toString();
        if (type == Type.FLOAT) return val.toString() + "f";
        if (type == Type.DOUBLE) return val.toString();
        if (type == Type.BOOLEAN) return ((Boolean) val) ? "true" : "false";
        // For reference types (strings, class names) we return null for now,
        // since in this context they must not be used as values.
        // Global string creation can be implemented if needed.
        return "null";
    }


}

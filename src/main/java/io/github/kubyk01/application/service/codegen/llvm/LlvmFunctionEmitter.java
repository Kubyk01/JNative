package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.domain.analyzer.ir.BasicBlock;
import io.github.kubyk01.domain.analyzer.ir.BranchTerminator;
import io.github.kubyk01.domain.analyzer.ir.CondBranchTerminator;
import io.github.kubyk01.domain.analyzer.ir.Constant;
import io.github.kubyk01.domain.analyzer.ir.Function;
import io.github.kubyk01.domain.analyzer.ir.Instruction;
import io.github.kubyk01.domain.analyzer.ir.LookupSwitchTerminator;
import io.github.kubyk01.domain.analyzer.ir.Module;
import io.github.kubyk01.domain.analyzer.ir.Opcode;
import io.github.kubyk01.domain.analyzer.ir.Parameter;
import io.github.kubyk01.domain.analyzer.ir.ReturnTerminator;
import io.github.kubyk01.domain.analyzer.ir.TableSwitchTerminator;
import io.github.kubyk01.domain.analyzer.ir.Terminator;
import io.github.kubyk01.domain.analyzer.ir.ThrowTerminator;
import io.github.kubyk01.domain.analyzer.ir.Type;
import io.github.kubyk01.domain.analyzer.ir.Value;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
public class LlvmFunctionEmitter {

    private final Module module;
    private final LlvmTypeMapper typeMapper;
    private final LlvmGlobalEmitter globalEmitter;

    private final LlvmValueMapper valueMapper = new LlvmValueMapper();
    private int tmpCounter = 0;
    private int labelCounter = 0;

    public String emitFunction(Function func) {
        StringBuilder sb = new StringBuilder();
        String funcName = LlvmRuntime.mangleFunction(func.getName());
        sb.append("define ").append(typeMapper.toLlvmType(func.getReturnType()))
                .append(" @").append(funcName).append("(");

        // Parameters
        List<Parameter> params = func.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(typeMapper.toLlvmType(params.get(i).getType()))
                    .append(" %param_").append(i);
        }
        sb.append(") {\n");

        // Create allocas for all local variables (temporaries and locals).
        // To do this, collect all local variables used in the function
        Set<Integer> usedLocals = collectUsedLocals(func);
        for (int idx : usedLocals) {
            Type type = inferLocalType(func, idx);
            sb.append("  %local_").append(idx).append(" = alloca ")
                    .append(typeMapper.toLlvmType(type)).append(", align 8\n");
        }

        // Initialize valueMapper: parameters are already mapped to %param_i
        valueMapper.clear();
        for (int i = 0; i < params.size(); i++) {
            valueMapper.setValue(params.get(i), "%param_" + i);
        }

        // Generate blocks
        for (BasicBlock block : func.getBlocks()) {
            sb.append(emitBlock(block));
        }

        sb.append("}\n\n");
        return sb.toString();
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
        // Search among parameters
        for (Parameter p : func.getParameters()) {
            if (p.getIndex() == idx) return p.getType();
        }
        // Search among STORE instructions
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

    private String emitBlock(BasicBlock block) {
        StringBuilder sb = new StringBuilder();
        String blockLabel = block.getLabel();
        String safeLabel = blockLabel.replaceAll("[^a-zA-Z0-9_]", "_");
        if (block == block.getFunction().getEntryBlock()) {
            sb.append("entry:\n");
        } else {
            sb.append(safeLabel).append(":\n");
        }

        for (Instruction inst : block.getInstructions()) {
            sb.append(emitInstruction(inst));
        }

        Terminator term = block.getTerminator();
        if (term != null) {
            sb.append(emitTerminator(term));
        } else {
            sb.append("  ret void\n");
        }
        return sb.toString();
    }

    /**
     * Unique name for an auxiliary register inside a function.
     * Required so that repeated auxiliary values (%gep etc.)
     * do not break SSA name uniqueness in LLVM.
     */
    private String newAux(String prefix) {
        return "%" + prefix + "_" + (tmpCounter++);
    }

    private String emitInstruction(Instruction inst) {
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
                String valRef = valueMapper.getValue(stored);
                String llvmType = typeMapper.toLlvmType(stored.getType());
                sb.append("  store ").append(llvmType).append(" ").append(valRef)
                        .append(", ").append(llvmType).append("* %local_").append(idx).append("\n");
                break;
            }
            case ADD: case SUB: case MUL: case DIV: case REM:
            case AND: case OR: case XOR: case SHL: case SHR: case USHR: {
                Value left = inst.getOperands().get(0);
                Value right = inst.getOperands().get(1);
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
                if (val.getType() == Type.REFERENCE && dest == Type.REFERENCE) {
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
                String structType = globalEmitter.getStructName(extractClassName(base));
                int offset = globalEmitter.getFieldOffset(extractClassName(base), fieldName);
                String gep = newAux("gep");
                // Use a GEP with a zero index and an offset
                sb.append("  ").append(gep).append(" = getelementptr inbounds ")
                        .append(structType).append(", ")
                        .append(structType).append("* ").append(baseRef)
                        .append(", i32 0, i32 ").append(offset/8).append("\n");
                sb.append("  ").append(resultName).append(" = load ")
                        .append(typeMapper.toLlvmType(inst.getResult().getType())).append(", ")
                        .append(typeMapper.toLlvmType(inst.getResult().getType())).append("* ").append(gep).append("\n");
                break;
            }
            case PUT_FIELD: {
                Value base = inst.getOperands().get(0);
                Value rhs = inst.getOperands().get(2);
                String fieldName = extractFieldName(inst);
                String baseRef = valueMapper.getValue(base);
                String rhsRef = valueMapper.getValue(rhs);
                String structType = globalEmitter.getStructName(extractClassName(base));
                int offset = globalEmitter.getFieldOffset(extractClassName(base), fieldName);
                String gep = newAux("gep");
                sb.append("  ").append(gep).append(" = getelementptr inbounds ")
                        .append(structType).append(", ")
                        .append(structType).append("* ").append(baseRef)
                        .append(", i32 0, i32 ").append(offset/8).append("\n");
                sb.append("  store ").append(typeMapper.toLlvmType(rhs.getType()))
                        .append(" ").append(rhsRef).append(", ")
                        .append(typeMapper.toLlvmType(rhs.getType())).append("* ").append(gep).append("\n");
                break;
            }
            case GET_STATIC: {
                String fieldName = extractFieldName(inst);
                String globalName = "gv_" + fieldName.replace('.', '_').replace('/', '_');
                sb.append("  ").append(resultName).append(" = load ")
                        .append(typeMapper.toLlvmType(inst.getResult().getType())).append(", ")
                        .append(typeMapper.toLlvmType(inst.getResult().getType())).append("* @")
                        .append(globalName).append("\n");
                break;
            }
            case PUT_STATIC: {
                Value rhs = inst.getOperands().get(1);
                String fieldName = extractFieldName(inst);
                String globalName = "gv_" + fieldName.replace('.', '_').replace('/', '_');
                sb.append("  store ").append(typeMapper.toLlvmType(rhs.getType()))
                        .append(" ").append(valueMapper.getValue(rhs)).append(", ")
                        .append(typeMapper.toLlvmType(rhs.getType())).append("* @")
                        .append(globalName).append("\n");
                break;
            }
            case CALL: case VIRTUAL_CALL: case INTERFACE_CALL: case STATIC_CALL: case SPECIAL_CALL: {
                String calleeName = extractCalleeName(inst);
                if (calleeName == null) break;
                List<Value> args = getCallArguments(inst);
                StringBuilder argList = new StringBuilder();
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) argList.append(", ");
                    argList.append(typeMapper.toLlvmType(args.get(i).getType()))
                            .append(" ").append(valueMapper.getValue(args.get(i)));
                }
                Type retType = inst.getResult() != null ? inst.getResult().getType() : Type.VOID;
                String retLlvm = typeMapper.toLlvmType(retType);
                if (retType != Type.VOID) {
                    sb.append("  ").append(resultName).append(" = call ")
                            .append(retLlvm).append(" @").append(calleeName)
                            .append("(").append(argList).append(")\n");
                } else {
                    sb.append("  call ").append(retLlvm).append(" @").append(calleeName)
                            .append("(").append(argList).append(")\n");
                }
                break;
            }
            case NEW: {
                String className = extractTypeName(inst);
                String structType = globalEmitter.getStructName(className);
                String sizeReg = newAux("size");
                String allocReg = newAux("alloc");
                // Allocate memory for the struct
                sb.append("  ").append(sizeReg).append(" = call i64 @llvm.objectsize.i64.p0i8(i8* null, i1 true)\n"); // simplified
                sb.append("  ").append(allocReg).append(" = call i8* @malloc(i64 ptrtoint (").append(structType).append("* getelementptr (").append(structType).append(", ").append(structType).append("* null, i32 1) to i64))\n");
                sb.append("  ").append(resultName).append(" = bitcast i8* ").append(allocReg).append(" to ").append(structType).append("*\n");
                break;
            }
            case NEW_ARRAY: case MULTI_NEW_ARRAY: {
                // Simplified for now: allocate memory for an array with a length header
                String arrReg = newAux("arr");
                sb.append("  ").append(arrReg).append(" = call i8* @malloc(i64 16)\n");
                sb.append("  ").append(resultName).append(" = bitcast i8* ").append(arrReg).append(" to ").append(typeMapper.toLlvmType(inst.getResult().getType())).append("\n");
                break;
            }
            case FREE: {
                Value obj = inst.getOperands().getFirst();
                String objRef = valueMapper.getValue(obj);
                sb.append("  call void @free(i8* ").append(objRef).append(")\n");
                break;
            }
            case MONITOR_ENTER: {
                Value obj = inst.getOperands().getFirst();
                sb.append("  call void @__jnative_monitor_enter(i8* ").append(valueMapper.getValue(obj)).append(")\n");
                break;
            }
            case MONITOR_EXIT: {
                Value obj = inst.getOperands().getFirst();
                sb.append("  call void @__jnative_monitor_exit(i8* ").append(valueMapper.getValue(obj)).append(")\n");
                break;
            }
            case INSTANCEOF: {
                // Runtime function call
                Value obj = inst.getOperands().getFirst();
                String typeName = extractTypeName(inst);
                sb.append("  ").append(resultName).append(" = call i1 @__jnative_instanceof(i8* ")
                        .append(valueMapper.getValue(obj)).append(", i8* getelementptr inbounds ([")
                        .append(typeName.length()).append(" x i8], [")
                        .append(typeName.length()).append(" x i8]* @.str.").append(typeName.replace('/', '_'))
                        .append(", i32 0, i32 0))\n");
                break;
            }
            case CHECKCAST: {
                // Similarly, but with a check and an abort call on failure
                Value obj = inst.getOperands().getFirst();
                String typeName = extractTypeName(inst);
                String okReg = newAux("ok");
                String failLabel = "check_fail_" + (labelCounter++);
                String okLabel = "check_ok_" + labelCounter;
                sb.append("  ").append(okReg).append(" = call i1 @__jnative_instanceof(i8* ")
                        .append(valueMapper.getValue(obj)).append(", i8* getelementptr inbounds ([")
                        .append(typeName.length()).append(" x i8], [")
                        .append(typeName.length()).append(" x i8]* @.str.").append(typeName.replace('/', '_'))
                        .append(", i32 0, i32 0))\n");
                sb.append("  br i1 ").append(okReg).append(", label %").append(okLabel).append(", label %").append(failLabel).append("\n");
                sb.append(failLabel).append(":\n");
                sb.append("  call void @abort()\n");
                sb.append("  unreachable\n");
                sb.append(okLabel).append(":\n");
                sb.append("  ").append(resultName).append(" = bitcast i8* ").append(valueMapper.getValue(obj))
                        .append(" to ").append(typeMapper.toLlvmType(inst.getResult().getType())).append("\n");
                break;
            }
            case ARRAYLENGTH: {
                Value arr = inst.getOperands().getFirst();
                String arrRef = valueMapper.getValue(arr);
                String lenPtr = newAux("lenptr");
                // Assume the length is stored at offset 0 from the pointer
                sb.append("  ").append(lenPtr).append(" = bitcast i8* ").append(arrRef).append(" to i32*\n");
                sb.append("  ").append(resultName).append(" = load i32, i32* ").append(lenPtr).append("\n");
                break;
            }
            case ALOAD: {
                Value arr = inst.getOperands().get(0);
                Value idx = inst.getOperands().get(1);
                String arrRef = valueMapper.getValue(arr);
                String idxRef = valueMapper.getValue(idx);
                String gep = newAux("gep");
                String gep2 = newAux("gep2");
                // Offset: header (4 bytes) + idx * element size (8 bytes for references)
                sb.append("  ").append(gep).append(" = getelementptr inbounds i8, i8* ").append(arrRef)
                        .append(", i64 4)\n");
                sb.append("  ").append(gep2).append(" = getelementptr inbounds i8*, i8** bitcast (i8* ").append(gep).append(" to i8**), i32 ")
                        .append(idxRef).append("\n");
                sb.append("  ").append(resultName).append(" = load i8*, i8** ").append(gep2).append("\n");
                break;
            }
            case ASTORE: {
                Value arr = inst.getOperands().get(0);
                Value idx = inst.getOperands().get(1);
                Value val = inst.getOperands().get(2);
                String arrRef = valueMapper.getValue(arr);
                String idxRef = valueMapper.getValue(idx);
                String valRef = valueMapper.getValue(val);
                String gep = newAux("gep");
                String gep2 = newAux("gep2");
                sb.append("  ").append(gep).append(" = getelementptr inbounds i8, i8* ").append(arrRef)
                        .append(", i64 4)\n");
                sb.append("  ").append(gep2).append(" = getelementptr inbounds i8*, i8** bitcast (i8* ").append(gep).append(" to i8**), i32 ")
                        .append(idxRef).append("\n");
                sb.append("  store i8* ").append(valRef).append(", i8** ").append(gep2).append("\n");
                break;
            }
            case PHI: {
                // PHI requires a list of (value, block) pairs. We must assemble them from the operands.
                // In our IR each operand corresponds to a predecessor block in the same order.
                BasicBlock parent = inst.getParent();
                List<BasicBlock> preds = parent != null ? parent.getPredecessors() : new ArrayList<>();
                sb.append("  ").append(resultName).append(" = phi ");
                sb.append(typeMapper.toLlvmType(inst.getResult().getType())).append(" ");
                for (int i = 0; i < inst.getOperands().size(); i++) {
                    if (i > 0) sb.append(", ");
                    Value phiOp = inst.getOperands().get(i);
                    String valRef = valueMapper.getValue(phiOp);
                    String blockLabel = (i < preds.size()) ? preds.get(i).getLabel() : "unknown";
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

    private String emitTerminator(Terminator term) {
        StringBuilder sb = new StringBuilder();
        if (term instanceof ReturnTerminator rt) {
            if (rt.getValue() != null) {
                sb.append("  ret ").append(typeMapper.toLlvmType(rt.getValue().getType()))
                        .append(" ").append(valueMapper.getValue(rt.getValue())).append("\n");
            } else {
                sb.append("  ret void\n");
            }
        } else if (term instanceof BranchTerminator bt) {
            String target = bt.getTarget().getLabel();
            sb.append("  br label %").append(target).append("\n");
        } else if (term instanceof CondBranchTerminator cbt) {
            String cond = valueMapper.getValue(cbt.getCondition());
            String trueTarget = cbt.getTrueTarget().getLabel();
            String falseTarget = cbt.getFalseTarget().getLabel();
            sb.append("  br i1 ").append(cond).append(", label %")
                    .append(trueTarget).append(", label %").append(falseTarget).append("\n");
        } else if (term instanceof ThrowTerminator) {
            sb.append("  call void @abort()\n");
            sb.append("  unreachable\n");
        } else if (term instanceof LookupSwitchTerminator || term instanceof TableSwitchTerminator) {
            // Generate switch
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
                            .append(lst.getTargetsArray()[i].getLabel()).append("\n");
                }
                sb.append("  ]\n");
            } else {
                TableSwitchTerminator tst = (TableSwitchTerminator) term;
                for (int i = 0; i < tst.getTargetsArray().length; i++) {
                    sb.append("    i32 ").append(tst.getMin() + i).append(", label %")
                            .append(tst.getTargetsArray()[i].getLabel()).append("\n");
                }
                sb.append("  ]\n");
            }
        } else {
            sb.append("  ; unknown terminator\n");
        }
        return sb.toString();
    }

    // Helper methods

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
        return switch (dest) {
            case BOOLEAN, BYTE, SHORT, CHAR, INT -> "trunc";
            case LONG -> "sext";
            case FLOAT, DOUBLE -> "sitofp";
            default -> "bitcast";
        };
    }

    private String extractFieldName(Instruction inst) {
        int fieldIdx = (inst.getOpcode() == Opcode.GET_STATIC || inst.getOpcode() == Opcode.PUT_STATIC) ? 0 : 1;
        if (inst.getOperands().size() > fieldIdx) {
            Value v = inst.getOperands().get(fieldIdx);
            if (v instanceof Constant c && c.getType() == Type.REFERENCE) {
                return c.getValue().toString();
            }
        }
        return "unknown";
    }

    private String extractCalleeName(Instruction inst) {
        if (!inst.getOperands().isEmpty()) {
            Value v = inst.getOperands().getFirst();
            if (v instanceof Constant c && c.getType() == Type.REFERENCE) {
                return c.getValue().toString();
            }
        }
        return null;
    }

    private String extractTypeName(Instruction inst) {
        // For NEW, INSTANCEOF, CHECKCAST
        if (!inst.getOperands().isEmpty()) {
            Value v = inst.getOperands().getFirst();
            if (v instanceof Constant c && c.getType() == Type.REFERENCE) {
                return c.getValue().toString();
            }
        }
        return "java/lang/Object";
    }

    private String extractClassName(Value v) {
        // For GET_FIELD/PUT_FIELD the base is a reference to an object; we need the class name
        // from its type. Simplified: we would need type metadata, which we do not have,
        // so for now return "java/lang/Object"
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
}

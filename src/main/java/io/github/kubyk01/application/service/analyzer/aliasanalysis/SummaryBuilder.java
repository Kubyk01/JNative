package io.github.kubyk01.application.service.analyzer.aliasanalysis;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class SummaryBuilder {

    private final Module module;
    private final Map<String, FunctionSummary> summaries = new HashMap<>();

    public SummaryBuilder(Module module) {
        this.module = module;
    }

    public Map<String, FunctionSummary> build() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Function func : module.getFunctions()) {
                if (func.getEntryBlock() == null) continue;
                FunctionSummary newSum = analyzeFunction(func);
                FunctionSummary oldSum = summaries.get(func.getName());
                if (oldSum == null || !oldSum.equals(newSum)) {
                    summaries.put(func.getName(), newSum);
                    changed = true;
                }
            }
        }
        return summaries;
    }

    private FunctionSummary analyzeFunction(Function func) {
        Map<Value, Set<AllocationSite>> localPointsTo = new HashMap<>();
        Map<Integer, Set<AllocationSite>> paramPointsTo = new HashMap<>();
        for (Parameter p : func.getParameters()) {
            paramPointsTo.put(p.getIndex(), new HashSet<>());
        }

        int idx = 0;
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (isAllocation(inst.getOpcode())) {
                    AllocationSite site = AllocationSite.fromInstruction(inst, func.getName(), idx);
                    if (inst.getResult() != null) {
                        Set<AllocationSite> pts = new HashSet<>();
                        pts.add(site);
                        localPointsTo.put(inst.getResult(), pts);
                    }
                }
                idx++;
            }
        }

        Set<Integer> paramsRead = new HashSet<>();
        Set<Integer> paramsWritten = new HashSet<>();
        Set<Integer> paramsEscaped = new HashSet<>();
        Set<Integer> paramsReturned = new HashSet<>();
        Set<Integer> paramsDestroyed = new HashSet<>();
        Set<String> fieldsRead = new HashSet<>();
        Set<String> fieldsWritten = new HashSet<>();
        // flags: [returnsObject, readsStaticFields, writesStaticFields, escapesGlobally]
        // boolean[] instead of separate booleans – primitives are passed by value
        boolean[] flags = new boolean[4];

        Set<AllocationSite> returnedAllocations = new HashSet<>();
        Map<Integer, Map<String, Set<AllocationSite>>> paramsFieldWrites = new HashMap<>();
        Map<String, Set<AllocationSite>> staticFieldWrites = new HashMap<>();

        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                processInstruction(inst, func, localPointsTo, paramPointsTo,
                        paramsRead, paramsWritten, paramsEscaped, paramsReturned,
                        fieldsRead, fieldsWritten, flags,
                    paramsFieldWrites, staticFieldWrites,
                        paramsDestroyed);
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                processTerminator(term, localPointsTo,
                        paramsEscaped, paramsReturned, flags, returnedAllocations);
            }
        }

        return FunctionSummary.builder()
                .paramsRead(paramsRead)
                .paramsWritten(paramsWritten)
                .paramsEscaped(paramsEscaped)
                .paramsReturned(paramsReturned)
                .paramsDestroyed(paramsDestroyed)
                .fieldsRead(fieldsRead)
                .fieldsWritten(fieldsWritten)
                .returnsObject(flags[0])
                .readsStaticFields(flags[1])
                .writesStaticFields(flags[2])
                .escapesGlobally(flags[3])
                .returnedAllocations(returnedAllocations)
                .paramsFieldWrites(paramsFieldWrites)
                .staticFieldWrites(staticFieldWrites)
                .build();
    }

    private void processInstruction(Instruction inst, Function func,
                                    Map<Value, Set<AllocationSite>> localPointsTo,
                                    Map<Integer, Set<AllocationSite>> paramPointsTo,
                                    Set<Integer> paramsRead, Set<Integer> paramsWritten,
                                    Set<Integer> paramsEscaped, Set<Integer> paramsReturned,
                                    Set<String> fieldsRead, Set<String> fieldsWritten,
                                    boolean[] flags,
                                    Map<Integer, Map<String, Set<AllocationSite>>> paramsFieldWrites,
                                    Map<String, Set<AllocationSite>> staticFieldWrites,
                                    Set<Integer> paramsDestroyed) {
        Opcode op = inst.getOpcode();
        switch (op) {
            case LOAD: {
                int idx = inst.getLocalIndex();
                if (idx >= 0 && idx < func.getParameters().size()) {
                    paramsRead.add(idx);
                    Set<AllocationSite> pts = paramPointsTo.getOrDefault(idx, new HashSet<>());
                    if (inst.getResult() != null) {
                        localPointsTo.put(inst.getResult(), new HashSet<>(pts));
                    }
                }
                break;
            }
            case STORE: {
                if (!inst.getOperands().isEmpty()) {
                    Value stored = inst.getOperands().getFirst();
                    int idx = inst.getLocalIndex();
                    Set<AllocationSite> pts = localPointsTo.getOrDefault(stored, new HashSet<>());
                    if (idx >= 0 && idx < func.getParameters().size()) {
                        paramsWritten.add(idx);
                        paramPointsTo.put(idx, new HashSet<>(pts));
                    } else {
                        if (inst.getResult() != null) {
                            localPointsTo.put(inst.getResult(), new HashSet<>(pts));
                        }
                    }
                }
                break;
            }
            case GET_FIELD: {
                if (inst.getOperands().size() >= 2) {
                    String field = extractFieldName(inst);
                    fieldsRead.add(field);
                }
                break;
            }
            case PUT_FIELD: {
                if (inst.getOperands().size() >= 3) {
                    Value base = inst.getOperands().get(0);
                    Value rhs = inst.getOperands().get(2);
                    String field = extractFieldName(inst);
                    fieldsWritten.add(field);
                    Set<AllocationSite> rhsPts = localPointsTo.getOrDefault(rhs, new HashSet<>());
                    if (base instanceof Parameter p) {
                        int pidx = p.getIndex();
                        paramsWritten.add(pidx);
                        paramsEscaped.add(pidx);
                        Map<String, Set<AllocationSite>> fieldMap =
                                paramsFieldWrites.computeIfAbsent(pidx, k -> new HashMap<>());
                        fieldMap.computeIfAbsent(field, k -> new HashSet<>()).addAll(rhsPts);
                    }
                    if (rhs instanceof Parameter p) {
                        paramsEscaped.add(p.getIndex());
                    }
                }
                break;
            }
            case ALOAD: {
                // array load: result = array[index]
                if (inst.getOperands().size() >= 2) {
                    Value array = inst.getOperands().getFirst();
                    Value result = inst.getResult();
                    if (result != null) {
                        // We don't have precise field points-to for arrays in summary,
                        // so we conservatively assume the result can point to any allocation site
                        // that may be stored in this array. Since we don't track per-element
                        // points-to in localPointsTo, we use UNKNOWN.
                        Set<AllocationSite> pts = new HashSet<>();
                        pts.add(AllocationSite.UNKNOWN);
                        localPointsTo.put(result, pts);
                        // Also mark that the function reads array elements (field "[]")
                        fieldsRead.add("[]");
                    }
                    // If the array is a parameter, mark it as read
                    if (array instanceof Parameter p) {
                        paramsRead.add(p.getIndex());
                    }
                }
                break;
            }
            case ASTORE: {
                // Writing to an array element: similar to PUT_FIELD
                if (inst.getOperands().size() >= 3) {
                    Value array = inst.getOperands().get(0);
                    Value value = inst.getOperands().get(2);
                    Set<AllocationSite> valuePts = localPointsTo.getOrDefault(value, new HashSet<>());
                    if (array instanceof Parameter p) {
                        int pidx = p.getIndex();
                        paramsWritten.add(pidx);
                        paramsEscaped.add(pidx);
                        // Store valuePts as written to field "[]" of this parameter
                        Map<String, Set<AllocationSite>> fieldMap =
                                paramsFieldWrites.computeIfAbsent(pidx, k -> new HashMap<>());
                        fieldMap.computeIfAbsent("[]", k -> new HashSet<>()).addAll(valuePts);
                    }
                    if (value instanceof Parameter p) {
                        paramsEscaped.add(p.getIndex());
                    }
                    // Also mark that we write to a field (array element)
                    fieldsWritten.add("[]");
                }
                break;
            }
            case GET_STATIC: {
                flags[1] = true; // readsStaticFields
                break;
            }
            case PUT_STATIC: {
                flags[2] = true; // writesStaticFields
                flags[3] = true; // escapesGlobally
                String field = extractFieldName(inst);
                // Layout PUT_STATIC: [fieldConst, val] – rhs is located in operand 1
                if (inst.getOperands().size() >= 2) {
                    Value rhs = inst.getOperands().get(1);
                    Set<AllocationSite> rhsPts = localPointsTo.getOrDefault(rhs, new HashSet<>());
                    staticFieldWrites.computeIfAbsent(field, k -> new HashSet<>()).addAll(rhsPts);
                }
                break;
            }
            case CALL:
            case VIRTUAL_CALL:
            case INTERFACE_CALL:
            case STATIC_CALL:
            case SPECIAL_CALL: {
                processCall(inst, localPointsTo,
                        paramsRead, paramsWritten, paramsEscaped, paramsReturned,
                        flags, paramsFieldWrites, staticFieldWrites, paramsDestroyed);
                break;
            }
            default:
                // Other instructions do not affect the summary
        }
    }

    private void processCall(Instruction callInst,
                             Map<Value, Set<AllocationSite>> localPointsTo,
                             Set<Integer> paramsRead, Set<Integer> paramsWritten,
                             Set<Integer> paramsEscaped, Set<Integer> paramsReturned,
                             boolean[] flags,
                             Map<Integer, Map<String, Set<AllocationSite>>> paramsFieldWrites,
                             Map<String, Set<AllocationSite>> staticFieldWrites,
                             Set<Integer> paramsDestroyed) {
        String calleeName = extractCalleeName(callInst);
        if (calleeName == null) return;

        // If this is a direct destructor call
        if (calleeName.startsWith("__destruct_")) {
            List<Value> args = getCallArguments(callInst);
            if (!args.isEmpty()) {
                Value arg = args.getFirst();
                if (arg instanceof Parameter p) {
                    paramsDestroyed.add(p.getIndex());
                }
            }
            return;
        }

        FunctionSummary calleeSum = summaries.get(calleeName);
        if (calleeSum == null) {
            List<Value> args = getCallArguments(callInst);
            for (Value arg : args) {
                if (arg instanceof Parameter p) {
                    paramsEscaped.add(p.getIndex());
                }
            }
            if (callInst.getResult() != null) {
                flags[0] = true; // returnsObject
            }
            return;
        }

        List<Value> args = getCallArguments(callInst);
        for (int i = 0; i < args.size(); i++) {
            Value arg = args.get(i);
            if (calleeSum.getParamsRead().contains(i)) {
                if (arg instanceof Parameter p) paramsRead.add(p.getIndex());
            }
            if (calleeSum.getParamsWritten().contains(i)) {
                if (arg instanceof Parameter p) paramsWritten.add(p.getIndex());
            }
            if (calleeSum.getParamsEscaped().contains(i)) {
                if (arg instanceof Parameter p) paramsEscaped.add(p.getIndex());
            }
            if (calleeSum.getParamsReturned().contains(i)) {
                if (arg instanceof Parameter p) paramsReturned.add(p.getIndex());
            }
            if (calleeSum.getParamsDestroyed().contains(i)) {
                if (arg instanceof Parameter p) paramsDestroyed.add(p.getIndex());
            }
        }

        for (Map.Entry<Integer, Map<String, Set<AllocationSite>>> entry : calleeSum.getParamsFieldWrites().entrySet()) {
            int paramIndex = entry.getKey();
            if (paramIndex < args.size()) {
                Value arg = args.get(paramIndex);
                if (arg instanceof Parameter p) {
                    int localIdx = p.getIndex();
                    Map<String, Set<AllocationSite>> destMap =
                            paramsFieldWrites.computeIfAbsent(localIdx, k -> new HashMap<>());
                    for (Map.Entry<String, Set<AllocationSite>> fe : entry.getValue().entrySet()) {
                        destMap.computeIfAbsent(fe.getKey(), k -> new HashSet<>()).addAll(fe.getValue());
                    }
                    paramsEscaped.add(localIdx);
                }
            }
        }

        for (Map.Entry<String, Set<AllocationSite>> entry : calleeSum.getStaticFieldWrites().entrySet()) {
            staticFieldWrites.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).addAll(entry.getValue());
        }

        if (calleeSum.isWritesStaticFields()) {
            flags[2] = true;
            flags[3] = true;
        }
        if (calleeSum.isReadsStaticFields()) {
            flags[1] = true;
        }
        if (calleeSum.isEscapesGlobally()) {
            flags[3] = true;
        }

        Value ret = callInst.getResult();
        if (ret != null && (calleeSum.isReturnsObject() || !calleeSum.getReturnedAllocations().isEmpty())) {
            flags[0] = true; // returnsObject
            Set<AllocationSite> retPts = new HashSet<>(calleeSum.getReturnedAllocations());
            for (int i = 0; i < args.size(); i++) {
                if (calleeSum.getParamsReturned().contains(i)) {
                    Value arg = args.get(i);
                    retPts.addAll(localPointsTo.getOrDefault(arg, new HashSet<>()));
                }
            }
            localPointsTo.put(ret, retPts);
        }
    }

    private void processTerminator(Terminator term,
                                   Map<Value, Set<AllocationSite>> localPointsTo,
                                   Set<Integer> paramsEscaped, Set<Integer> paramsReturned,
                                   boolean[] flags,
                                   Set<AllocationSite> returnedAllocations) {
        if (term instanceof ReturnTerminator rt) {
            Value retVal = rt.getValue();
            if (retVal != null && retVal.getType() != Type.VOID) {
                flags[0] = true; // returnsObject
                Set<AllocationSite> pts = localPointsTo.getOrDefault(retVal, new HashSet<>());
                returnedAllocations.addAll(pts);
                if (retVal instanceof Parameter p) {
                    paramsEscaped.add(p.getIndex());
                    paramsReturned.add(p.getIndex());
                }
            }
        }
    }

    private boolean isAllocation(Opcode op) {
        return op == Opcode.NEW || op == Opcode.NEW_ARRAY || op == Opcode.MULTI_NEW_ARRAY;
    }

    /**
     * The field name depends on the opcode: for GET_FIELD/PUT_FIELD the field constant is in operand 1,
     * for GET_STATIC/PUT_STATIC – in operand 0.
     * The full name (including the class) is returned, which prevents name collisions
     * between static fields of different classes.
     */
    private String extractFieldName(Instruction inst) {
        int fieldIdx = (inst.getOpcode() == Opcode.GET_STATIC || inst.getOpcode() == Opcode.PUT_STATIC) ? 0 : 1;
        if (inst.getOperands().size() > fieldIdx) {
            Value v = inst.getOperands().get(fieldIdx);
            if (v instanceof Constant c && c.getType().isReference()) {
                return c.getValue().toString(); // full name, e.g. "java/lang/System.out"
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

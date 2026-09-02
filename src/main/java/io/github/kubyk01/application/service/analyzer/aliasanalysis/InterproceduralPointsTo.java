package io.github.kubyk01.application.service.analyzer.aliasanalysis;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToGraph;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;

@Slf4j
public class InterproceduralPointsTo {

    private final Module module;
    private final Map<String, FunctionSummary> summaries;
    private final PointsToGraph graph = new PointsToGraph();
    private final Map<Value, AllocationSite> allocationSites = new HashMap<>();
    private boolean changed = true;

    public InterproceduralPointsTo(Module module, Map<String, FunctionSummary> summaries) {
        this.module = module;
        this.summaries = summaries;
    }

    public PointsToGraph analyze() {
        collectAllocationSites();
        for (Function func : module.getFunctions()) {
            for (Parameter p : func.getParameters()) {
                graph.get(p);
            }
        }
        while (changed) {
            changed = false;
            for (Function func : module.getFunctions()) {
                if (func.getEntryBlock() == null) continue;
                processFunction(func);
            }
        }
        return graph;
    }

    private void collectAllocationSites() {
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            int idx = 0;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (isAllocation(inst.getOpcode())) {
                        AllocationSite site = AllocationSite.fromInstruction(inst, func.getName(), idx);
                        allocationSites.put(inst.getResult(), site);
                        // keep the site -> value mapping
                        graph.putAllocationSite(site, inst.getResult());
                        graph.add(inst.getResult(), site);
                    }
                    idx++;
                }
            }
        }
    }

    private boolean isAllocation(Opcode op) {
        return op == Opcode.NEW || op == Opcode.NEW_ARRAY || op == Opcode.MULTI_NEW_ARRAY;
    }

    private void processFunction(Function func) {
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                processInstruction(inst, func);
            }
            Terminator term = block.getTerminator();
            if (term != null) processTerminator();
        }
    }

    private void processInstruction(Instruction inst, Function currentFunc) {
        Opcode op = inst.getOpcode();
        switch (op) {
            case LOAD:
                break;
            case STORE: {
                if (!inst.getOperands().isEmpty()) {
                    Value stored = inst.getOperands().getFirst();
                    Value local = inst.getResult();
                    if (local != null && stored != null) {
                        changed |= graph.merge(local, graph.get(stored));
                    }
                }
                break;
            }
            case GET_FIELD: {
                if (inst.getOperands().size() >= 2) {
                    Value base = inst.getOperands().getFirst();
                    Value result = inst.getResult();
                    if (result != null) {
                        String field = extractFieldName(inst);
                        PointsToSet basePts = graph.get(base);
                        PointsToSet fieldPts = graph.getFieldPointsToForSites(basePts, field);
                        changed |= graph.merge(result, fieldPts);
                    }
                }
                break;
            }
            case PUT_FIELD: {
                if (inst.getOperands().size() >= 3) {
                    Value base = inst.getOperands().get(0);
                    Value rhs = inst.getOperands().get(2);
                    String field = extractFieldName(inst);
                    PointsToSet basePts = graph.get(base);
                    PointsToSet rhsPts = graph.get(rhs);
                    if (basePts.isEmpty()) {
                        graph.mergeFieldPointsTo(AllocationSite.UNKNOWN, field, rhsPts);
                    } else {
                        for (AllocationSite site : basePts.getSites()) {
                            graph.mergeFieldPointsTo(site, field, rhsPts);
                        }
                    }
                }
                break;
            }
            case ALOAD: {
                // array load: result = array[index]  (operands: array, index)
                if (inst.getOperands().size() >= 2) {
                    Value array = inst.getOperands().getFirst();
                    Value result = inst.getResult();
                    if (result != null) {
                        PointsToSet arrayPts = graph.get(array);
                        PointsToSet elemPts = graph.getFieldPointsToForSites(arrayPts, "[]");
                        changed |= graph.merge(result, elemPts);
                    }
                }
                break;
            }
            case ASTORE: {
                // array store: array[index] = value  (operands: array, index, value)
                if (inst.getOperands().size() >= 3) {
                    Value array = inst.getOperands().get(0);
                    Value value = inst.getOperands().get(2);
                    PointsToSet arrayPts = graph.get(array);
                    PointsToSet valuePts = graph.get(value);
                    if (arrayPts.isEmpty()) {
                        graph.mergeArrayElementPointsTo(AllocationSite.UNKNOWN, valuePts);
                    } else {
                        for (AllocationSite site : arrayPts.getSites()) {
                            graph.mergeArrayElementPointsTo(site, valuePts);
                        }
                    }
                }
                break;
            }
            case GET_STATIC: {
                Value result = inst.getResult();
                if (result != null) {
                    String field = extractFieldName(inst);
                    PointsToSet fieldPts = graph.getStaticFieldPointsTo(field);
                    changed |= graph.merge(result, fieldPts);
                }
                break;
            }
            case PUT_STATIC: {
                // Layout PUT_STATIC: [fieldConst, val]
                if (inst.getOperands().size() >= 2) {
                    Value rhs = inst.getOperands().get(1);
                    String field = extractFieldName(inst);
                    PointsToSet rhsPts = graph.get(rhs);
                    graph.mergeStaticFieldPointsTo(field, rhsPts);
                }
                break;
            }
            case CALL:
            case VIRTUAL_CALL:
            case INTERFACE_CALL:
            case STATIC_CALL:
            case SPECIAL_CALL: {
                processCall(inst, currentFunc);
                break;
            }
            default:
                // other instructions do not affect points-to
        }
    }

    private void processCall(Instruction callInst, Function currentFunc) {
        String calleeName = extractCalleeName(callInst);
        if (calleeName == null) return;
        FunctionSummary summary = summaries.get(calleeName);
        if (summary == null) {
            Value ret = callInst.getResult();
            if (ret != null) {
                PointsToSet pts = new PointsToSet();
                pts.add(AllocationSite.UNKNOWN);
                changed |= graph.merge(ret, pts);
            }
            return;
        }

        List<Value> args = getCallArguments(callInst);
        Value returnValue = callInst.getResult();

        // 1. Handling of the return value
        if (returnValue != null) {
            PointsToSet resultPts = new PointsToSet();
            for (AllocationSite site : summary.getReturnedAllocations()) {
                resultPts.add(site);
            }
            for (int i = 0; i < args.size(); i++) {
                if (summary.getParamsReturned().contains(i)) {
                    resultPts.addAll(graph.get(args.get(i)));
                }
            }
            if (!resultPts.isEmpty()) {
                changed |= graph.merge(returnValue, resultPts);
            } else if (summary.isReturnsObject()) {
                resultPts.add(AllocationSite.UNKNOWN);
                changed |= graph.merge(returnValue, resultPts);
            }
        }

        // 2. Handling of writes to parameter fields with exact written sites
        for (Map.Entry<Integer, Map<String, Set<AllocationSite>>> entry : summary.getParamsFieldWrites().entrySet()) {
            int paramIndex = entry.getKey();
            if (paramIndex >= args.size()) continue;
            Value arg = args.get(paramIndex);
            PointsToSet argPts = graph.get(arg);
            if (argPts.isEmpty()) continue;
            for (Map.Entry<String, Set<AllocationSite>> fe : entry.getValue().entrySet()) {
                String field = fe.getKey();
                Set<AllocationSite> writtenSites = fe.getValue();
                for (AllocationSite baseSite : argPts.getSites()) {
                    PointsToSet ptsToWrite = new PointsToSet();
                    for (AllocationSite site : writtenSites) {
                        ptsToWrite.add(site);
                    }
                    // If UNKNOWN is among the written sites - conservatively add all module sites
                    if (writtenSites.contains(AllocationSite.UNKNOWN)) {
                        for (AllocationSite site : allocationSites.values()) {
                            ptsToWrite.add(site);
                        }
                    }
                    graph.mergeFieldPointsTo(baseSite, field, ptsToWrite);
                }
            }
        }

        // 3. Handling of writes to static fields with exact written sites
        for (Map.Entry<String, Set<AllocationSite>> entry : summary.getStaticFieldWrites().entrySet()) {
            String field = entry.getKey();
            Set<AllocationSite> writtenSites = entry.getValue();
            PointsToSet ptsToWrite = new PointsToSet();
            for (AllocationSite site : writtenSites) {
                ptsToWrite.add(site);
            }
            if (writtenSites.contains(AllocationSite.UNKNOWN)) {
                for (AllocationSite site : allocationSites.values()) {
                    ptsToWrite.add(site);
                }
            }
            graph.mergeStaticFieldPointsTo(field, ptsToWrite);
        }
    }

    private void processTerminator() {
        // terminator instructions do not change points-to
    }
}

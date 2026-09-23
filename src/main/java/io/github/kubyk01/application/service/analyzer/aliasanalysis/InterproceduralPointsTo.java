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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;
import static io.github.kubyk01.util.LlvmUtil.isAllocation;

@Slf4j
public class InterproceduralPointsTo {

    private static final long MAX_EVALUATIONS = 1_000_000L;
    private final Module module;
    private final Map<String, FunctionSummary> summaries;
    private final PointsToGraph graph = new PointsToGraph();
    private final Map<Value, AllocationSite> allocationSites = new HashMap<>();

    private boolean changed;

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

        // ---- Caller map: callee constant → set of caller functions. ----
        Map<String, Set<Function>> callers = new HashMap<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    String callee = extractCalleeName(inst);
                    if (callee != null) {
                        callers.computeIfAbsent(callee, k -> new HashSet<>()).add(func);
                    }
                }
            }
        }

        // ---- Worklist initialisation ----------------------------------
        Deque<Function> worklist = new ArrayDeque<>();
        Set<Function> inWorklist = new HashSet<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            worklist.add(func);
            inWorklist.add(func);
        }

        long processed = 0;
        long enqueued = 0;
        long lastLogEvals = 0;

        while (!worklist.isEmpty()) {
            if (processed >= MAX_EVALUATIONS) {
                System.err.println("PointsTo: WARNING: worklist exceeded "
                    + MAX_EVALUATIONS + " evaluations; "
                    + worklist.size() + " functions still queued. "
                    + "Stopping (results may be imprecise).");
                break;
            }

            Function func = worklist.poll();
            inWorklist.remove(func);
            processed++;

            changed = false;
            processFunction(func);

            if (changed) {
                Set<Function> funcCallers = callers.get(func.getName());
                if (funcCallers != null) {
                    for (Function caller : funcCallers) {
                        if (inWorklist.add(caller)) {
                            worklist.add(caller);
                            enqueued++;
                        }
                    }
                }
            }

            if (processed - lastLogEvals >= 100_000) {
                lastLogEvals = processed;
                System.out.println("PointsTo: " + processed
                    + " evaluations, " + enqueued + " enqueues, "
                    + worklist.size() + " queued");
            }
        }

        System.out.println("PointsTo: converged after " + processed
            + " function evaluations, " + enqueued + " enqueues");
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
                        graph.putAllocationSite(site, inst.getResult());
                        graph.add(inst.getResult(), site);
                    }
                    idx++;
                }
            }
        }
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
            case PHI: {
                Value result = inst.getResult();
                if (result == null) break;
                for (Value operand : inst.getOperands()) {
                    if (operand == null) continue;
                    changed |= graph.merge(result, graph.get(operand));
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

        // 1. Return value
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

        // 2. Parameter field writes — coarsened summary.
        //
        // The callee's summary carries only the set of field names it
        // writes into each parameter, not the exact set of sites. We
        // therefore widen the field's points-to on every base site
        // reachable from the caller's argument to UNKNOWN. This is
        // sound (no site is ever omitted) and finite (a single UNKNOWN
        // entry per (base site, field) pair).
        for (Map.Entry<Integer, Set<String>> entry
            : summary.getParamsFieldWrites().entrySet()) {
            int paramIndex = entry.getKey();
            if (paramIndex >= args.size()) continue;
            Value arg = args.get(paramIndex);
            PointsToSet argPts = graph.get(arg);
            if (argPts.isEmpty()) continue;
            for (String field : entry.getValue()) {
                PointsToSet ptsToWrite = new PointsToSet();
                ptsToWrite.add(AllocationSite.UNKNOWN);
                for (AllocationSite baseSite : argPts.getSites()) {
                    graph.mergeFieldPointsTo(baseSite, field, ptsToWrite);
                }
            }
        }

        // 3. Static field writes — coarsened summary. Same widening.
        for (String field : summary.getStaticFieldWrites()) {
            PointsToSet ptsToWrite = new PointsToSet();
            ptsToWrite.add(AllocationSite.UNKNOWN);
            graph.mergeStaticFieldPointsTo(field, ptsToWrite);
        }
    }

    private void processTerminator() {
        // terminator instructions do not change points-to
    }
}
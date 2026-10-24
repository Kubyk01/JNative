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
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.util.LlvmUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.kubyk01.util.LlvmUtil.canonicalCalleeKey;
import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;
import static io.github.kubyk01.util.LlvmUtil.isAllocation;
import static io.github.kubyk01.util.LlvmUtil.isLazyClinitWrapper;

@Slf4j
public class InterproceduralPointsTo {

    private static final long MAX_EVALUATIONS = 1_000_000L;
    private static final long PROGRESS_LOG_INTERVAL = 1_000L;
    private static final int EVAL_BATCH_SIZE = 2048;

    private static final PointsToSet UNKNOWN_SINGLETON = makeUnknownSingleton();

    private static PointsToSet makeUnknownSingleton() {
        PointsToSet s = PointsToSet.local();
        s.add(AllocationSite.UNKNOWN);
        return s;
    }

    private final Module module;
    private final Map<String, FunctionSummary> summaries;
    private final Scheduler scheduler;
    private final PointsToGraph graph = new PointsToGraph();

    private final Map<Function, Map<Integer, PointsToSet>> localSlots =
        new ConcurrentHashMap<>();

    /**
     * Cache of callee Function lookups, so that the inner loop of
     * {@link #processCall} does not hash the mangled name for every
     * single argument.
     */
    private final Map<String, Function> calleeCache = new ConcurrentHashMap<>();

    public InterproceduralPointsTo(Module module,
                                   Map<String, FunctionSummary> summaries,
                                   Scheduler scheduler) {
        this.module = module;
        this.summaries = summaries;
        this.scheduler = (scheduler != null) ? scheduler : Schedulers.single();
    }

    private Map<Integer, PointsToSet> slotsFor(Function func) {
        return localSlots.computeIfAbsent(func, k -> new HashMap<>());
    }

    private static final class EvalContext {
        boolean changed;
        boolean slotsChanged;
        final Set<Function> calleesToReenqueue = new LinkedHashSet<>();
    }

    private record EvaluationResult(boolean changed,
                                    boolean slotsChanged,
                                    Set<Function> calleesToReenqueue) {}

    private EvaluationResult evaluateFunction(Function func) {
        EvalContext ctx = new EvalContext();
        processFunction(func, ctx);
        return new EvaluationResult(ctx.changed, ctx.slotsChanged, ctx.calleesToReenqueue);
    }

    public PointsToGraph analyze() {
        collectAllocationSites();

        // ---- Caller map ----
        Map<String, Set<Function>> callers = new HashMap<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    String callee = extractCalleeName(inst);
                    if (callee == null) continue;
                    String calleeKey = canonicalCalleeKey(callee);
                    if (isLazyClinitWrapper(calleeKey)) continue;
                    callers.computeIfAbsent(calleeKey, k -> new HashSet<>()).add(func);
                }
            }
        }

        // ---- Worklist ----
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

            List<Function> batch = new ArrayList<>(
                Math.min(EVAL_BATCH_SIZE, worklist.size()));
            while (!worklist.isEmpty() && batch.size() < EVAL_BATCH_SIZE) {
                Function f = worklist.poll();
                inWorklist.remove(f);
                batch.add(f);
            }

            List<EvaluationResult> results = LlvmUtil.parallelMapOrdered(
                batch, scheduler, this::evaluateFunction);

            for (int i = 0; i < batch.size(); i++) {
                Function func = batch.get(i);
                EvaluationResult r = results.get(i);
                processed++;

                if (r.slotsChanged()) {
                    if (inWorklist.add(func)) {
                        worklist.add(func);
                        enqueued++;
                    }
                }
                if (r.changed()) {
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
                // NEW: precisely propagated parameters mean the callee
                // needs re-evaluation to see the new points-to sets.
                for (Function callee : r.calleesToReenqueue()) {
                    if (inWorklist.add(callee)) {
                        worklist.add(callee);
                        enqueued++;
                    }
                }
            }

            if (processed - lastLogEvals >= PROGRESS_LOG_INTERVAL) {
                lastLogEvals = processed;
                System.out.println("PointsTo: " + processed
                    + " evaluations, " + enqueued + " enqueues, "
                    + worklist.size() + " queued, last batch: "
                    + batch.getLast().getName());
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
                        AllocationSite site = AllocationSite.fromInstruction(
                            inst, func.getName(), idx);
                        graph.putAllocationSite(site, inst.getResult());
                        graph.add(inst.getResult(), site);
                    }
                    idx++;
                }
            }
        }
    }

    private void processFunction(Function func, EvalContext ctx) {
        Map<Integer, PointsToSet> slots = slotsFor(func);

        // Seed slots with parameters. The parameter's points-to set is
        // populated by the caller via processCall's precise propagation.
        for (Parameter p : func.getParameters()) {
            PointsToSet paramPts = graph.get(p);
            if (paramPts.isEmpty()) continue;
            PointsToSet slotPts = slots.computeIfAbsent(
                p.getIndex(), k -> PointsToSet.local());
            int oldSize = slotPts.getSites().size();
            slotPts.addAll(paramPts);
            if (slotPts.getSites().size() > oldSize) {
                ctx.slotsChanged = true;
            }
        }

        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                processInstruction(inst, func, slots, ctx);
            }
        }
    }

    private void processInstruction(Instruction inst,
                                    Function currentFunc,
                                    Map<Integer, PointsToSet> slots,
                                    EvalContext ctx) {
        Opcode op = inst.getOpcode();
        switch (op) {
            case LOAD: {
                Value result = inst.getResult();
                if (result == null) break;
                int idx = inst.getLocalIndex();
                if (idx < 0) break;
                PointsToSet slotPts = slots.get(idx);
                if (slotPts != null && !slotPts.isEmpty()) {
                    ctx.changed |= graph.merge(result, slotPts);
                }
                break;
            }
            case STORE: {
                if (inst.getOperands().isEmpty()) break;
                Value stored = inst.getOperands().getFirst();
                if (stored == null) break;

                PointsToSet storedPts = graph.get(stored);

                Value local = inst.getResult();
                if (local != null) {
                    ctx.changed |= graph.merge(local, storedPts);
                }

                int idx = inst.getLocalIndex();
                if (idx >= 0) {
                    PointsToSet slotPts = slots.computeIfAbsent(
                        idx, k -> PointsToSet.local());
                    int oldSize = slotPts.getSites().size();
                    slotPts.addAll(storedPts);
                    if (slotPts.getSites().size() > oldSize) {
                        ctx.slotsChanged = true;
                    }
                }
                break;
            }
            case PHI: {
                Value result = inst.getResult();
                if (result == null) break;
                for (Value operand : inst.getOperands()) {
                    if (operand == null) continue;
                    ctx.changed |= graph.merge(result, graph.get(operand));
                }
                break;
            }
            case GET_FIELD: {
                if (inst.getOperands().size() < 2) break;
                Value base = inst.getOperands().getFirst();
                Value result = inst.getResult();
                if (result == null) break;
                String field = extractFieldName(inst);
                PointsToSet basePts = graph.get(base);
                PointsToSet fieldPts =
                    graph.getFieldPointsToForSites(basePts, field);
                ctx.changed |= graph.merge(result, fieldPts);
                break;
            }
            case PUT_FIELD: {
                if (inst.getOperands().size() < 3) break;
                Value base = inst.getOperands().get(0);
                Value rhs  = inst.getOperands().get(2);
                String field = extractFieldName(inst);
                PointsToSet basePts = graph.get(base);
                PointsToSet rhsPts  = graph.get(rhs);
                if (rhsPts.isEmpty()) break;
                if (basePts.isEmpty()) {
                    ctx.changed |= graph.mergeFieldPointsTo(
                        AllocationSite.UNKNOWN, field, rhsPts);
                } else {
                    for (AllocationSite site : basePts.getSites()) {
                        ctx.changed |= graph.mergeFieldPointsTo(
                            site, field, rhsPts);
                    }
                }
                break;
            }
            case ALOAD: {
                if (inst.getOperands().size() < 2) break;
                Value array = inst.getOperands().getFirst();
                Value result = inst.getResult();
                if (result == null) break;
                PointsToSet arrayPts = graph.get(array);
                PointsToSet elemPts = graph.getFieldPointsToForSites(
                    arrayPts, "[]");
                ctx.changed |= graph.merge(result, elemPts);
                break;
            }
            case ASTORE: {
                if (inst.getOperands().size() < 3) break;
                Value array = inst.getOperands().get(0);
                Value value = inst.getOperands().get(2);
                PointsToSet arrayPts = graph.get(array);
                PointsToSet valuePts = graph.get(value);
                if (valuePts.isEmpty()) break;
                if (arrayPts.isEmpty()) {
                    ctx.changed |= graph.mergeArrayElementPointsTo(
                        AllocationSite.UNKNOWN, valuePts);
                } else {
                    for (AllocationSite site : arrayPts.getSites()) {
                        ctx.changed |= graph.mergeArrayElementPointsTo(
                            site, valuePts);
                    }
                }
                break;
            }
            case GET_STATIC: {
                Value result = inst.getResult();
                if (result == null) break;
                String field = extractFieldName(inst);
                PointsToSet fieldPts = graph.getStaticFieldPointsTo(field);
                ctx.changed |= graph.merge(result, fieldPts);
                break;
            }
            case PUT_STATIC: {
                if (inst.getOperands().size() < 2) break;
                Value rhs = inst.getOperands().get(1);
                String field = extractFieldName(inst);
                PointsToSet rhsPts = graph.get(rhs);
                ctx.changed |= graph.mergeStaticFieldPointsTo(field, rhsPts);
                break;
            }
            case CALL:
            case VIRTUAL_CALL:
            case INTERFACE_CALL:
            case STATIC_CALL:
            case SPECIAL_CALL: {
                processCall(inst, currentFunc, slots, ctx);
                break;
            }
            default:
        }
    }

    private void processCall(Instruction callInst,
                             Function currentFunc,
                             Map<Integer, PointsToSet> slots,
                             EvalContext ctx) {
        String calleeName = extractCalleeName(callInst);
        if (calleeName == null) return;
        String calleeKey = canonicalCalleeKey(calleeName);

        if (isLazyClinitWrapper(calleeKey)) {
            return;
        }

        FunctionSummary summary = summaries.get(calleeKey);
        if (summary == null) {
            Value ret = callInst.getResult();
            if (ret != null) {
                ctx.changed |= graph.merge(ret, UNKNOWN_SINGLETON);
            }
            return;
        }

        List<Value> args = getCallArguments(callInst);
        Value returnValue = callInst.getResult();

        // ------------------------------------------------------------------
        // Precise parameter propagation
        //
        // For every call site, propagate the caller's argument points-to
        // into the callee's corresponding Parameter object. This is what
        // makes the interprocedural field-sensitivity work: when a caller
        // passes a concrete Holder site S to a callee that then writes
        // into a field of its parameter, the write lands in S.payload —
        // not in UNKNOWN.payload — and later lifetime analysis on S can
        // see it.
        //
        // The propagation is direct: after it, the callee is added to
        // calleesToReenqueue so the worklist re-evaluates the callee with
        // the new parameter points-to sets. Convergence is guaranteed
        // because points-to sets grow monotonically.
        //
        // Aliases (native overrides) are skipped: their Function's
        // parameter list belongs to the C implementation, not to the
        // Java-level method, so the mapping args[i] -> params[i] may be
        // wrong. The summary-based widening remains in place for those
        // (as it does for external callees).
        // ------------------------------------------------------------------
        Function calleeFunc = lookupCallee(calleeKey);
        if (calleeFunc != null && calleeFunc.getName().equals(calleeKey)) {
            List<Parameter> calleeParams = calleeFunc.getParameters();
            int n = Math.min(args.size(), calleeParams.size());
            for (int i = 0; i < n; i++) {
                PointsToSet argPts = graph.get(args.get(i));
                if (argPts.isEmpty()) continue;
                if (graph.merge(calleeParams.get(i), argPts)) {
                    ctx.calleesToReenqueue.add(calleeFunc);
                }
            }
        }

        // ---- 1. Return value ----
        if (returnValue != null) {
            boolean producedAnySite = false;

            for (AllocationSite site : summary.getReturnedAllocations()) {
                ctx.changed |= graph.addSite(returnValue, site);
                producedAnySite = true;
            }

            for (int i = 0; i < args.size(); i++) {
                if (!summary.getParamsReturned().contains(i)) continue;
                PointsToSet argPts = graph.getIfPresent(args.get(i));
                if (argPts == null || argPts.isEmpty()) continue;
                ctx.changed |= graph.merge(returnValue, argPts);
                producedAnySite = true;
            }

            if (!producedAnySite && summary.isReturnsObject()) {
                ctx.changed |= graph.merge(returnValue, UNKNOWN_SINGLETON);
            }
        }

        // ---- 2. Static field writes ----
        //
        // Only static fields are conservatively widened here. Instance
        // fields of parameters are handled by precise propagation above
        // (which also populates them via processInstruction PUT_FIELD in
        // the callee). Widening instance fields to UNKNOWN is
        // intentionally omitted: it would defeat the precise propagation
        // and prevent field-overwrite destructor insertion.
        for (String field : summary.getStaticFieldWrites()) {
            if (graph.staticFieldContainsUnknown(field)) continue;
            ctx.changed |= graph.mergeStaticFieldPointsTo(
                field, UNKNOWN_SINGLETON);
        }
    }

    private Function lookupCallee(String calleeKey) {
        Function cached = calleeCache.get(calleeKey);
        if (cached != null) return cached;
        Function found = module.getFunction(calleeKey);
        if (found != null) {
            calleeCache.put(calleeKey, found);
        }
        return found;
    }
}
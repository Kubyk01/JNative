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

    /**
     * Number of functions in one batch.
     *
     * <p>2048 was chosen so that on a machine with 8 workers each worker
     * receives ~256 items per batch: large enough to amortise the batch
     * dispatch, small enough that a single slow function does not leave
     * the other seven workers idle for the entire batch.</p>
     */
    private static final int EVAL_BATCH_SIZE = 2048;

    /**
     * Shared, never-mutated {@code PointsToSet} containing only
     * {@link AllocationSite#UNKNOWN}.
     *
     * <p>Used by the two coarsened widening loops in
     * {@link #processCall} as the source set for "this field now holds
     * an unknown site". The instance is never mutated after
     * construction; every use passes it as the {@code source} argument
     * of {@code mergeFieldPointsTo} / {@code mergeStaticFieldPointsTo},
     * and those only ever read from it.</p>
     */
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
    private final Map<Value, AllocationSite> allocationSites = new HashMap<>();

    /**
     * Local slot state, one inner map per function.
     *
     * <p>The outer map is a {@link ConcurrentHashMap} because several
     * workers call {@link #slotsFor(Function)} concurrently for
     * different functions during a batch. The inner maps are plain
     * {@code HashMap}s, safe because of the one-evaluating-thread-per-
     * function invariant enforced by {@link #analyze}.</p>
     */
    private final Map<Function, Map<Integer, PointsToSet>> localSlots =
            new ConcurrentHashMap<>();

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

    /**
     * Per-evaluation mutable state. Allocated once per
     * {@link #evaluateFunction} call, so the two change flags are not
     * shared across threads running in the same batch.
     */
    private static final class EvalContext {
        boolean changed;
        boolean slotsChanged;
    }

    private record EvaluationResult(boolean changed, boolean slotsChanged) {}

    private EvaluationResult evaluateFunction(Function func) {
        EvalContext ctx = new EvalContext();
        processFunction(func, ctx);
        return new EvaluationResult(ctx.changed, ctx.slotsChanged);
    }

    public PointsToGraph analyze() {
        collectAllocationSites();

        // ---- Caller map: callee constant -> set of caller functions. ----
        //
        // Lazy <clinit> wrappers are excluded: their callers number in
        // the tens of thousands on a full JDK image, and a wrapper's
        // summary never changes in a way that could affect a caller.
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

            // ---- Build one batch ----------------------------------
            List<Function> batch = new ArrayList<>(
                    Math.min(EVAL_BATCH_SIZE, worklist.size()));
            while (!worklist.isEmpty() && batch.size() < EVAL_BATCH_SIZE) {
                Function f = worklist.poll();
                inWorklist.remove(f);
                batch.add(f);
            }

            // ---- Evaluate the batch in parallel -------------------
            List<EvaluationResult> results = LlvmUtil.parallelMapOrdered(
                    batch, scheduler, this::evaluateFunction);

            // ---- Sequential commit of change flags ----------------
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
                        allocationSites.put(inst.getResult(), site);
                        graph.putAllocationSite(site, inst.getResult());
                        graph.add(inst.getResult(), site);
                    }
                    idx++;
                }
            }
        }
    }

    /**
     * Evaluates one function against the current snapshot of the graph.
     *
     * <p>The slot map is looked up exactly once per function evaluation
     * and threaded into every instruction that needs it. The previous
     * revision called {@link #slotsFor(Function)} from inside
     * {@code processInstruction} for every {@code LOAD} and every
     * {@code STORE}, which forced a {@code ConcurrentHashMap}
     * {@code computeIfAbsent} — and its internal locking — into the
     * innermost loop of the analysis. On the JDK closure with roughly
     * half a million {@code LOAD}/{@code STORE} instructions per
     * points-to fixed-point iteration, that single change is what
     * brought the analysis phase's user-time back under control.</p>
     */
    private void processFunction(Function func, EvalContext ctx) {
        Map<Integer, PointsToSet> slots = slotsFor(func);

        // Seed the slots with the parameters. Parameters are never
        // materialised through STOREs in SSA-IR: MethodTranslator puts
        // the parameter straight into Frame.local and every read
        // becomes a direct reference to the parameter's SSA value. A
        // LOAD that survives on an "unsafe" path therefore reads a
        // slot that has no STORE in the function body, and without
        // this seeding such a LOAD would observe the shared empty set
        // and break the downstream escape and lifetime analyses.
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
            // processTerminator() does not affect the points-to graph:
            // terminators carry only control-flow operands, and none of
            // them introduce or propagate allocation sites.
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

                // SSA channel: the result of a STORE is the new SSA
                // version of the slot, and the information propagates
                // into it directly. Covers every safe slot, where the
                // SSA transformer removed the LOAD.
                Value local = inst.getResult();
                if (local != null) {
                    ctx.changed |= graph.merge(local, storedPts);
                }

                // Local-slot channel: accumulate the same set in the
                // slot's bucket in parallel so that any LOAD that
                // survived on an unsafe path can read it back.
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
        // slots is currently unused by this method, but the parameter is
        // kept so that a future extension (e.g. propagating callee
        // summaries into caller-local slots) does not have to re-thread
        // it through every call site.

        String calleeName = extractCalleeName(callInst);
        if (calleeName == null) return;
        String calleeKey = canonicalCalleeKey(calleeName);

        // Lazy <clinit> wrappers are skipped here for the same reason
        // they are skipped in the caller map: their summary is trivial
        // (void, no args, only static-field writes to their own class)
        // and cannot affect the caller's points-to graph.
        if (isLazyClinitWrapper(calleeKey)) {
            return;
        }

        FunctionSummary summary = summaries.get(calleeKey);
        if (summary == null) {
            // External or not-yet-analysed callee: conservatively widen
            // the call's result to UNKNOWN. The shared singleton avoids
            // a fresh PointsToSet allocation per call site.
            Value ret = callInst.getResult();
            if (ret != null) {
                ctx.changed |= graph.merge(ret, UNKNOWN_SINGLETON);
            }
            return;
        }

        List<Value> args = getCallArguments(callInst);
        Value returnValue = callInst.getResult();

        // ---- 1. Return value ------------------------------------
        //
        // The union of the callee's concrete returned allocations and
        // its parameter-returned argument sets is produced directly on
        // the result value's own points-to set. addSite / merge both
        // return a change flag, so the intermediate PointsToSet that
        // the previous revision allocated per call site is not needed.
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

        // ---- 2. Parameter field writes --------------------------
        //
        // The callee's summary carries only the set of field names it
        // writes into each parameter. Widen the field's points-to on
        // every base site reachable from the caller's argument to
        // UNKNOWN. The fast-path skip below makes re-evaluation of a
        // function that has not changed cheap: one hash lookup per
        // (base site, field) pair, no allocation, no iteration.
        for (Map.Entry<Integer, Set<String>> entry
                : summary.getParamsFieldWrites().entrySet()) {
            int paramIndex = entry.getKey();
            if (paramIndex >= args.size()) continue;
            Value arg = args.get(paramIndex);
            PointsToSet argPts = graph.getIfPresent(arg);
            if (argPts == null || argPts.isEmpty()) continue;
            for (String field : entry.getValue()) {
                for (AllocationSite baseSite : argPts.getSites()) {
                    if (graph.fieldContainsUnknown(baseSite, field)) {
                        continue;
                    }
                    ctx.changed |= graph.mergeFieldPointsTo(
                            baseSite, field, UNKNOWN_SINGLETON);
                }
            }
        }

        // ---- 3. Static field writes -----------------------------
        for (String field : summary.getStaticFieldWrites()) {
            if (graph.staticFieldContainsUnknown(field)) {
                continue;
            }
            ctx.changed |= graph.mergeStaticFieldPointsTo(
                    field, UNKNOWN_SINGLETON);
        }
    }
}

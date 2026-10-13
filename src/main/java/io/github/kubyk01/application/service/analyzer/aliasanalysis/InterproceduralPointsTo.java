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
     * <p>Both coarsened widening loops in {@link #processCall} write
     * this exact set into every {@code (base-site, field)} pair that a
     * callee's summary reports as written. The pre-fix code allocated
     * a fresh {@code PointsToSet} for every {@code (param, field)} pair
     * on every call site; on a full JDK image that is hundreds of
     * thousands of short-lived HashSets per pass, and the pass runs
     * several times as the worklist re-enqueues callers.</p>
     *
     * <p>The instance is <b>never</b> mutated after construction. Every
     * use below passes it as the {@code source} argument of
     * {@code mergeFieldPointsTo} / {@code mergeStaticFieldPointsTo}, and
     * those methods only ever read from it (via {@code addAll}). The
     * Java type system cannot express "read-only view of a mutable
     * set", so the invariant is documented here and must be preserved
     * by any future edit to this file: never call {@code add},
     * {@code addAll}, or any other mutator on this object.</p>
     */
    private static final PointsToSet UNKNOWN_SINGLETON = makeUnknownSingleton();

    private static PointsToSet makeUnknownSingleton() {
        PointsToSet s = new PointsToSet();
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
     * different functions during a batch; a plain {@code HashMap} would
     * corrupt its table under concurrent {@code computeIfAbsent}, the
     * same way the plain {@code HashSet} in {@code PointsToSet} would
     * have.</p>
     *
     * <p>The inner maps are plain {@code HashMap}s. This is safe because
     * of an invariant enforced by {@link #analyze}: at any instant at
     * most one thread is evaluating a given function, because
     * {@code inWorklist} prevents a function from appearing twice in
     * the worklist and the batch-building loop pops each item exactly
     * once. The thread that owns the function for the duration of its
     * evaluation is therefore the only thread that can touch its inner
     * slot map.</p>
     */
    private final Map<Function, Map<Integer, PointsToSet>> localSlots =
            new ConcurrentHashMap<>();

    /**
     * Full constructor. The {@code scheduler} is borrowed, not owned:
     * the orchestrator disposes it after the analysis pipeline is
     * complete. Passing {@code null} collapses to
     * {@link Schedulers#single()}, which makes the class usable from
     * unit tests that do not care about parallelism.
     */
    public InterproceduralPointsTo(Module module,
                                   Map<String, FunctionSummary> summaries,
                                   Scheduler scheduler) {
        this.module = module;
        this.summaries = summaries;
        this.scheduler = (scheduler != null) ? scheduler : Schedulers.single();
    }

    /**
     * Convenience constructor: runs on {@link Schedulers#single()}.
     * Kept for tests and for any caller that does not care about
     * parallelism; the two-argument form was the previous public API
     * and remains valid.
     */
    public InterproceduralPointsTo(Module module,
                                   Map<String, FunctionSummary> summaries) {
        this(module, summaries, Schedulers.single());
    }

    private Map<Integer, PointsToSet> slotsFor(Function func) {
        return localSlots.computeIfAbsent(func, k -> new HashMap<>());
    }

    /**
     * Per-evaluation mutable state.
     *
     * <p>Before parallelisation these two flags were instance fields of
     * {@code InterproceduralPointsTo}, which was safe because there was
     * exactly one evaluation in flight at a time. With a batch of
     * evaluations running concurrently, sharing instance fields would
     * corrupt them. The context is therefore allocated per evaluation
     * and threaded through every method that needs to record a change;
     * the aggregated result of the two flags is what the batch loop
     * uses to update the worklist.</p>
     */
    private static final class EvalContext {
        boolean changed;
        boolean slotsChanged;
    }

    /**
     * Aggregated result of one function evaluation.
     */
    private record EvaluationResult(boolean changed, boolean slotsChanged) {}

    private EvaluationResult evaluateFunction(Function func) {
        EvalContext ctx = new EvalContext();
        processFunction(func, ctx);
        return new EvaluationResult(ctx.changed, ctx.slotsChanged);
    }

    public PointsToGraph analyze() {
        collectAllocationSites();

        for (Function func : module.getFunctions()) {
            for (Parameter p : func.getParameters()) {
                graph.get(p);
            }
        }

        // ---- Caller map: callee constant → set of caller functions. ----
        //
        // Lazy <clinit> wrappers are excluded from this map for the same
        // reason they are excluded from the SummaryBuilder caller map:
        // their callers number in the tens of thousands on a full JDK
        // image, and a wrapper's summary never changes in a way that
        // could affect a caller (its body is a fixed sequence of
        // void-returning calls with zero arguments). See
        // LlvmUtil.isLazyClinitWrapper and the equivalent skip in
        // SummaryBuilder.build().
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
            //
            // The batch is at most EVAL_BATCH_SIZE functions. Each item
            // is removed from inWorklist before being put in the batch,
            // so no function can appear twice in the same batch and no
            // function can be re-added to the worklist while its own
            // evaluation is still running. That, together with the
            // fact that the batch loop fully drains before the next
            // batch is built, is what makes the "one function, one
            // thread at a time" invariant hold for localSlots and for
            // every value-level points-to set inside this function's IR.
            List<Function> batch = new ArrayList<>(
                    Math.min(EVAL_BATCH_SIZE, worklist.size()));
            while (!worklist.isEmpty() && batch.size() < EVAL_BATCH_SIZE) {
                Function f = worklist.poll();
                inWorklist.remove(f);
                batch.add(f);
            }

            // ---- Evaluate the batch in parallel -------------------
            //
            // NOTE (fix): this previously used
            //
            //     Flux.fromIterable(batch)
            //         .flatMapSequential(
            //             f -> Mono.fromCallable(() -> evaluateFunction(f))
            //                     .subscribeOn(scheduler),
            //             EVAL_MAX_CONCURRENCY)
            //
            // which on the same 8-core machine only reached ~2 busy cores
            // (top: %CPU 220). flatMapSequential+subscribeOn allocates one
            // InnerSubscriber and one Mono wrapper per item, keeps an
            // ordering buffer, and funnels every subscription through
            // scheduler.createWorker(); the per-item overhead of that path
            // dominates the actual work for the small per-function
            // workloads this pass produces.
            //
            // The pipeline below is the one SummaryBuilder,
            // EscapeSummaryBuilder, InterproceduralEscape and
            // LifetimeAnalyzer already use. It partitions the source once
            // into N rails (one per CPU), binds each rail to a single
            // worker of the scheduler for the whole batch, and processes
            // items with zero per-item allocation. On the same input it
            // saturates all available cores.
            List<EvaluationResult> results = LlvmUtil.parallelMapOrdered(
                    batch, scheduler, this::evaluateFunction);
            // parallelMapOrdered already validates the result size.

            // ---- Sequential commit of change flags ----------------
            //
            // This is the only place in the parallel section that
            // mutates the worklist. Doing it on the main thread means
            // inWorklist does not have to be thread-safe: the two-set
            // idiom (add returns true iff the element was absent)
            // remains correct without an extra synchronization layer.
            for (int i = 0; i < batch.size(); i++) {
                Function func = batch.get(i);
                EvaluationResult r = results.get(i);
                processed++;

                // If the slots of this very function grew, a LOAD of
                // the same function may appear before the
                // corresponding STORE in the block traversal order
                // (back edge or non-topological block order) and
                // therefore may not have seen the update yet.
                // Re-enqueuing the function guarantees that every LOAD
                // ends up seeing the up-to-date slot contents.
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

    private void processFunction(Function func, EvalContext ctx) {
        // Seed the slots with the parameters.
        //
        // Parameters are never materialised through STOREs in SSA-IR:
        // MethodTranslator puts the parameter straight into Frame.local
        // and every read becomes a direct reference to the parameter's
        // SSA value. Therefore any LOAD that survives on an "unsafe" path
        // (unsafeLocals) reads a slot that has no STORE in the function
        // body at all. Without this seeding such a LOAD would get an
        // empty points-to set and would break escape/liveness again.
        Map<Integer, PointsToSet> slots = slotsFor(func);
        for (Parameter p : func.getParameters()) {
            PointsToSet paramPts = graph.get(p);
            if (paramPts.isEmpty()) continue;
            PointsToSet slotPts = slots.computeIfAbsent(
                    p.getIndex(), k -> new PointsToSet());
            int oldSize = slotPts.getSites().size();
            slotPts.addAll(paramPts);
            if (slotPts.getSites().size() > oldSize) {
                ctx.slotsChanged = true;
            }
        }

        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                processInstruction(inst, func, ctx);
            }
            Terminator term = block.getTerminator();
            if (term != null) processTerminator();
        }
    }

    private void processInstruction(Instruction inst, Function currentFunc,
                                    EvalContext ctx) {
        Opcode op = inst.getOpcode();
        switch (op) {
            case LOAD: {
                // Carry the points-to set from the slot to the result of
                // the LOAD. This is critical for the "unsafe" LOADs that
                // the SSA transformer keeps in the IR after a block with
                // a potentially throwing instruction (see
                // SSATransformer.identifyUnsafeLocals).
                //
                // Without this branch the result of a LOAD always has an
                // empty points-to set, and the downstream analyses
                // (escape, liveness, lifetime) treat an object read back
                // through a LOAD as dead right after its first write
                // into the slot. That is exactly how the use-after-free
                // in ProviderConfig.getProvider arose.
                Value result = inst.getResult();
                if (result != null) {
                    int idx = inst.getLocalIndex();
                    if (idx >= 0) {
                        PointsToSet slotPts = slotsFor(currentFunc).get(idx);
                        if (slotPts != null && !slotPts.isEmpty()) {
                            ctx.changed |= graph.merge(result, slotPts);
                        }
                    }
                }
                break;
            }
            case STORE: {
                if (!inst.getOperands().isEmpty()) {
                    Value stored = inst.getOperands().getFirst();
                    if (stored != null) {
                        PointsToSet storedPts = graph.get(stored);

                        // SSA channel: the result of a STORE is the new
                        // SSA version of the slot and the information
                        // propagates into it directly. This covers every
                        // safe slot, where the SSA transformer has
                        // removed the LOAD.
                        Value local = inst.getResult();
                        if (local != null) {
                            ctx.changed |= graph.merge(local, storedPts);
                        }

                        // Local-slot channel: accumulate the same set in
                        // the slot's bucket in parallel so that the
                        // LOADs which survived can read it back.
                        int idx = inst.getLocalIndex();
                        if (idx >= 0) {
                            PointsToSet slotPts = slotsFor(currentFunc)
                                    .computeIfAbsent(idx, k -> new PointsToSet());
                            int oldSize = slotPts.getSites().size();
                            slotPts.addAll(storedPts);
                            if (slotPts.getSites().size() > oldSize) {
                                ctx.slotsChanged = true;
                            }
                        }
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
                if (inst.getOperands().size() >= 2) {
                    Value base = inst.getOperands().getFirst();
                    Value result = inst.getResult();
                    if (result != null) {
                        String field = extractFieldName(inst);
                        PointsToSet basePts = graph.get(base);
                        PointsToSet fieldPts =
                                graph.getFieldPointsToForSites(basePts, field);
                        ctx.changed |= graph.merge(result, fieldPts);
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
                        ctx.changed |= graph.mergeFieldPointsTo(
                                AllocationSite.UNKNOWN, field, rhsPts);
                    } else {
                        for (AllocationSite site : basePts.getSites()) {
                            ctx.changed |= graph.mergeFieldPointsTo(
                                    site, field, rhsPts);
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
                        PointsToSet elemPts = graph.getFieldPointsToForSites(
                                arrayPts, "[]");
                        ctx.changed |= graph.merge(result, elemPts);
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
                        ctx.changed |= graph.mergeArrayElementPointsTo(
                                AllocationSite.UNKNOWN, valuePts);
                    } else {
                        for (AllocationSite site : arrayPts.getSites()) {
                            ctx.changed |= graph.mergeArrayElementPointsTo(
                                    site, valuePts);
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
                    ctx.changed |= graph.merge(result, fieldPts);
                }
                break;
            }
            case PUT_STATIC: {
                if (inst.getOperands().size() >= 2) {
                    Value rhs = inst.getOperands().get(1);
                    String field = extractFieldName(inst);
                    PointsToSet rhsPts = graph.get(rhs);
                    ctx.changed |= graph.mergeStaticFieldPointsTo(field, rhsPts);
                }
                break;
            }
            case CALL:
            case VIRTUAL_CALL:
            case INTERFACE_CALL:
            case STATIC_CALL:
            case SPECIAL_CALL: {
                processCall(inst, currentFunc, ctx);
                break;
            }
            default:
        }
    }

    private void processCall(Instruction callInst, Function currentFunc,
                             EvalContext ctx) {
        String calleeName = extractCalleeName(callInst);
        if (calleeName == null) return;
        String calleeKey = canonicalCalleeKey(calleeName);

        // Skip lazy <clinit> wrappers here as well. The caller-map
        // construction above and SummaryBuilder.processCall both already
        // skip lazy-clinit wrappers, but this method did not, so a call
        // site of the form
        //
        //     fn___lazy_clinit_run_<C>()
        //
        // would still resolve to the wrapper's (trivial, void,
        // no-argument) summary and pass through the coarsened widening
        // loops below with zero args and zero parameter-write entries.
        // That is wasteful on every one of the tens of thousands of
        // trigger sites that the LazyClinitInstrumenter inserts on a
        // full JDK image, and it is also incorrect in the same way that
        // it would be for the caller map: a wrapper's summary cannot
        // affect its caller's points-to graph, because every one of the
        // wrapper's instructions either returns void or writes to a
        // static field of the wrapper's own class.
        if (isLazyClinitWrapper(calleeKey)) {
            return;
        }

        FunctionSummary summary = summaries.get(calleeKey);
        if (summary == null) {
            // External or not-yet-analysed callee: conservatively widen
            // the call's result to UNKNOWN. The shared singleton avoids
            // a fresh PointsToSet allocation per call site; this path
            // is taken by every external call and is one of the hottest
            // allocation sites in the pre-fix code.
            Value ret = callInst.getResult();
            if (ret != null) {
                ctx.changed |= graph.merge(ret, UNKNOWN_SINGLETON);
            }
            return;
        }

        List<Value> args = getCallArguments(callInst);
        Value returnValue = callInst.getResult();

        // 1. Return value
        //
        // The previous revision allocated a fresh PointsToSet per call
        // site just to hold the callee's return-set before merging it
        // into the result's points-to set. On a function with thousands
        // of calls that is millions of short-lived ConcurrentHashMaps —
        // and the allocation shows up as a measurable share of the
        // worker threads' hot stack, since every PointsToSet wraps its
        // own ConcurrentHashMap.KeySetView.
        //
        // The set is not needed: addSite() and merge() both return a
        // change flag, so the union can be produced directly on the
        // result value's own points-to set. The only semantic
        // requirement is to know whether anything was added at all, so
        // that the "fall back to UNKNOWN when the callee returns an
        // object but carries no concrete returned allocations and no
        // parameter-returned arguments" rule still fires.
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

        // 2. Parameter field writes — coarsened summary.
        //
        // The callee's summary carries only the set of field names it
        // writes into each parameter, not the exact set of sites. We
        // therefore widen the field's points-to on every base site
        // reachable from the caller's argument to UNKNOWN. This is
        // sound (no site is ever omitted) and finite (a single UNKNOWN
        // entry per (base site, field) pair).
        //
        // The per-(base-site, field) fast-path skip below is what makes
        // re-evaluation of a function that has not changed new
        // information cheap: the cost drops to one hash lookup per
        // pair, with no allocation, no iteration, and no computeIfAbsent
        // on the map itself. On the observed workload that is the
        // difference between 350 seconds of single-threaded CPU time
        // and a sub-second pass.
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

        // 3. Static field writes — coarsened summary. Same widening,
        // same fast-path skip, same shared singleton.
        for (String field : summary.getStaticFieldWrites()) {
            if (graph.staticFieldContainsUnknown(field)) {
                continue;
            }
            ctx.changed |= graph.mergeStaticFieldPointsTo(
                    field, UNKNOWN_SINGLETON);
        }
    }

    private void processTerminator() {
        // terminator instructions do not change points-to
    }
}
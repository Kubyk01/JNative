package io.github.kubyk01.application.service.analyzer.escapeanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
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
import static io.github.kubyk01.util.LlvmUtil.isAllocation;
import static io.github.kubyk01.util.LlvmUtil.isLazyClinitWrapper;

/**
 * Interprocedural escape fixed point: runs IntraproceduralEscape over every
 * live function and merges the results into a global site->status map.
 *
 * Two earlier gaps:
 *   1. IntraproceduralEscape started every site at STACK and read only its
 *      own map, so a site promoted by another function was invisible to
 *      its allocator on re-run. Fix: workers seed from the shared
 *      globalStatus map, and getMaxStatus takes max(local, seed).
 *   2. When a site was upgraded, only its callers were re-enqueued; the
 *      allocator (whose sitesReachableFromParams contributions had to be
 *      recomputed against the new status) was not. Fix: enqueue both
 *      the allocator and the callers of the observing function.
 *
 * The lattice is monotone (EscapeStatus.ordinal only grows), so the loop
 * terminates in O(sites x lattice height). globalStatus is a
 * ConcurrentHashMap: workers read it, the main thread writes after each
 * batch. Missing a write in one iteration is safe — every upgrade
 * re-enqueues everything that depends on it.
 */
@Slf4j
public class InterproceduralEscape {

    /**
     * Maximum number of functions processed in one parallel batch.
     * Matches the constant used by {@link io.github.kubyk01.application.service.analyzer.aliasanalysis.InterproceduralPointsTo}
     * so that the two fixed-point passes have the same memory footprint
     * profile. Without this limit the very first batch on a full JDK
     * closure is the entire reachable set (tens of thousands of
     * functions) and each element is a freshly built
     * {@code Map<AllocationSite, EscapeStatus>}.
     */
    private static final int EVAL_BATCH_SIZE = 2048;

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final Map<String, EscapeSummary> summaries;
    private final DependencyResolver resolver;
    private final Scheduler scheduler;

    /**
     * Global best-known escape status per allocation site, shared across
     * every worker that evaluates an {@link IntraproceduralEscape}.
     *
     * <p>Concurrent because it is read by workers (through
     * {@link IntraproceduralEscape}'s seed mechanism and its
     * {@code getMaxStatus} fallback) and written by the main thread
     * after every batch. Iteration order is not observed anywhere, so
     * the weak consistency of {@link ConcurrentHashMap} is sufficient:
     * a worker that misses the latest status in one iteration will
     * observe it in the next, because every upgraded site re-enqueues
     * both its allocator and all callers of the function that first
     * observed the upgrade.</p>
     */
    private final Map<AllocationSite, EscapeStatus> globalStatus =
            new ConcurrentHashMap<>();

    public InterproceduralEscape(Module module,
                                 AliasAnalysisResult aliasResult,
                                 Map<String, EscapeSummary> summaries,
                                 DependencyResolver resolver) {
        this(module, aliasResult, summaries, resolver, Schedulers.single());
    }

    public InterproceduralEscape(Module module,
                                 AliasAnalysisResult aliasResult,
                                 Map<String, EscapeSummary> summaries,
                                 DependencyResolver resolver,
                                 Scheduler scheduler) {
        this.module = module;
        this.aliasResult = aliasResult;
        this.summaries = summaries;
        this.resolver = resolver;
        this.scheduler = (scheduler != null) ? scheduler : Schedulers.single();
    }

    /**
     * Runs the interprocedural fixed point and returns the final
     * site-to-status map.
     *
     * <p>The returned map is the {@link #globalStatus} field itself, not
     * a copy. Callers treat it as read-only after this method returns;
     * that convention is documented here and enforced only by the fact
     * that nothing in the codebase writes to the map after the fixed
     * point converges.</p>
     */
    public Map<AllocationSite, EscapeStatus> analyze() {

        // ------------------------------------------------------------------
        // Seed every allocation site to STACK.
        //
        // This is the lattice bottom. The fixed point only ever raises a
        // site's status, so the seed determines the "unreached" default
        // that intraprocedural analysis starts from.
        // ------------------------------------------------------------------
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (isAllocation(inst.getOpcode()) && inst.getResult() != null) {
                        PointsToSet pts = aliasResult.getPointsTo(inst.getResult());
                        for (AllocationSite site : pts.getSites()) {
                            globalStatus.put(site, EscapeStatus.STACK);
                        }
                    }
                }
            }
        }

        List<Function> live = new ArrayList<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() != null) live.add(f);
        }
        if (live.isEmpty()) return globalStatus;

        // ------------------------------------------------------------------
        // Reverse call graph.
        //
        // Maps a mangled callee name to every function that calls it. Used
        // to re-enqueue callers when a function's own contribution to the
        // global status map grows. Lazy-<clinit> wrappers are excluded:
        // their caller edges are tens of thousands of trigger sites that
        // never observe a change in the wrapper's own site statuses.
        // ------------------------------------------------------------------
        Map<String, Set<Function>> callersOf = new HashMap<>();
        for (Function f : live) {
            for (BasicBlock b : f.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    String callee = extractCalleeName(inst);
                    if (callee == null) continue;
                    String calleeKey = canonicalCalleeKey(callee);
                    if (isLazyClinitWrapper(calleeKey)) continue;
                    callersOf.computeIfAbsent(calleeKey, k -> new HashSet<>()).add(f);
                }
            }
        }

        // ------------------------------------------------------------------
        // Allocator index.
        //
        // AllocationSite.getMethodName() returns the mangled name of the
        // function whose NEW / NEW_ARRAY / MULTI_NEW_ARRAY produced the
        // site (see AllocationSite.fromInstruction and the call to it in
        // InterproceduralPointsTo.collectAllocationSites). This map is
        // the inverse: mangled function name -> Function object. Used
        // to re-enqueue the allocator when one of its sites is upgraded.
        // ------------------------------------------------------------------
        Map<String, Function> allocatorByMethodName = new HashMap<>(live.size() * 2);
        for (Function f : live) {
            allocatorByMethodName.put(f.getName(), f);
        }

        Deque<Function> worklist = new ArrayDeque<>(live);
        Set<Function> inWorklist = new HashSet<>(live);

        long processed = 0;
        long lastLog = 0;

        while (!worklist.isEmpty()) {
            // ------------------------------------------------------------------
            // Pull one batch off the worklist and evaluate in parallel.
            //
            // Each IntraproceduralEscape instance receives the shared
            // globalStatus map as its seed. This is the mechanism that
            // makes an externally-promoted site visible to its allocator
            // on the next run: the allocator seeds every local site from
            // globalStatus before its own fixed point, so it sees the
            // promotion and can propagate it further through its own
            // sitesReachableFromParams contributions.
            // ------------------------------------------------------------------
            List<Function> batch = new ArrayList<>(EVAL_BATCH_SIZE);
            while (!worklist.isEmpty() && batch.size() < EVAL_BATCH_SIZE) {
                Function f = worklist.poll();
                inWorklist.remove(f);
                batch.add(f);
            }

            List<Map<AllocationSite, EscapeStatus>> results =
                    LlvmUtil.parallelMapOrdered(
                            batch, scheduler,
                            func -> new IntraproceduralEscape(
                                    func, aliasResult, summaries, resolver, globalStatus).analyze());

            // ------------------------------------------------------------------
            // Sequential commit.
            //
            // For every site whose local status exceeds the global status,
            // upgrade the global status and re-enqueue:
            //
            //   1. The site's allocator, so its sitesReachableFromParams
            //      propagation is recomputed against the new status.
            //      Without this, a site promoted from outside its
            //      allocator function would be visible on the allocator's
            //      next run (through the seed) but nothing would trigger
            //      that run.
            //
            //   2. All callers of the function that first observed the
            //      upgrade, so their own sitesReachableFromParams
            //      propagation can pick up the newly-promoted sites.
            // ------------------------------------------------------------------
            for (int i = 0; i < batch.size(); i++) {
                Function func = batch.get(i);
                Map<AllocationSite, EscapeStatus> localStatus = results.get(i);
                processed++;

                boolean functionChanged = false;
                for (Map.Entry<AllocationSite, EscapeStatus> entry : localStatus.entrySet()) {
                    AllocationSite site = entry.getKey();
                    EscapeStatus old = globalStatus.getOrDefault(site, EscapeStatus.STACK);
                    if (entry.getValue().ordinal() > old.ordinal()) {
                        globalStatus.put(site, entry.getValue());
                        functionChanged = true;

                        Function allocatorFunc = allocatorByMethodName.get(site.getMethodName());
                        if (allocatorFunc != null
                                && allocatorFunc.getEntryBlock() != null
                                && inWorklist.add(allocatorFunc)) {
                            worklist.add(allocatorFunc);
                        }
                    }
                }

                if (functionChanged) {
                    Set<Function> callers = callersOf.get(func.getName());
                    if (callers != null) {
                        for (Function caller : callers) {
                            if (inWorklist.add(caller)) worklist.add(caller);
                        }
                    }
                }
            }

            if (processed - lastLog >= 5_000) {
                lastLog = processed;
                System.out.println("InterproceduralEscape: " + processed
                        + " evaluations, " + worklist.size() + " queued");
            }
        }

        System.out.println("InterproceduralEscape: converged after "
                + processed + " function evaluations");
        return globalStatus;
    }
}
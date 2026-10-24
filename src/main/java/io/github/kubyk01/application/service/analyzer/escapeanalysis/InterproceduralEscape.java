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
 * Interprocedural escape analysis driver.
 *
 * <h2>What this class does</h2>
 *
 * <p>Runs {@link IntraproceduralEscape} over every live function in the
 * module and merges the per-function results into a single global map
 * from allocation site to escape status. The map produced here is
 * consumed by {@link io.github.kubyk01.application.service.analyzer.lifetime.LifetimeAnalyzer}
 * (to decide whether a per-use destruction point is legitimate) and by
 * {@link io.github.kubyk01.application.service.optimizer.DestructorInserter}
 * (to decide whether to emit a {@code free} and a null-out at a field
 * overwrite).</p>
 *
 * <h2>What changed relative to the previous revision</h2>
 *
 * <p>The previous revision had two structural gaps that made the
 * transitive {@code sitesReachableFromParams} mechanism introduced in
 * {@link EscapeSummaryBuilder} ineffective for anything but the simplest
 * single-function cases.</p>
 *
 * <h3>Gap 1 — the shared status map was not visible to workers</h3>
 *
 * <p>{@link IntraproceduralEscape#analyze()} starts from a per-function
 * map in which every local allocation site is {@code STACK}, and
 * {@link IntraproceduralEscape#getMaxStatus} reads <b>only</b> that
 * per-function map. A site allocated by a caller was therefore invisible
 * to the callee: the callee saw {@code STACK}, regardless of how far the
 * caller had promoted the site. The concrete failure this produced was
 * the {@code BufferedWriter.cb} case described at the top of
 * {@link EscapeSummaryBuilder}'s javadoc: {@code PrintStream.<init>}
 * allocated {@code BufferedWriter}, promoted it to {@code HEAP} via
 * {@code PUT_FIELD this.textOut}, and later {@code System.<clinit>}
 * promoted {@code PrintStream} to {@code GLOBAL} — but neither the
 * {@code BufferedWriter} site nor the {@code cb} site inside it ever
 * saw the {@code GLOBAL} promotion, because every re-run of their
 * allocator started from a fresh {@code STACK}.</p>
 *
 * <p>The fix is that workers now receive the shared {@link #globalStatus}
 * map and seed their local {@code siteStatus} from it. A site promoted
 * to {@code GLOBAL} by any function in any batch is immediately visible
 * to every subsequent run of its allocator, and to any run of any
 * function whose {@code valueEscapeStatus} / {@code getMaxStatus}
 * consults it.</p>
 *
 * <h3>Gap 2 — the allocator was never re-enqueued</h3>
 *
 * <p>When a site's global status was upgraded, only its <em>callers</em>
 * were re-enqueued. That is the correct direction for propagating the
 * upgrade upward through the call graph, but it does not cover the case
 * where the upgrade must also propagate <em>downward</em>: the
 * allocator of the upgraded site is the function that computed its own
 * {@code sitesReachableFromParams} contributions, and those
 * contributions are only recomputed when the allocator itself is
 * re-analysed. Without re-enqueueing the allocator, a promotion observed
 * by an unrelated caller could be lost on the next run of the allocator,
 * because the allocator would re-derive its (now stale) contributions
 * from the {@code STACK}-initialized local map.</p>
 *
 * <p>The fix is that every upgrade re-enqueues both the site's allocator
 * and all callers of the function that first observed the upgrade. The
 * worklist therefore terminates at the lattice fixed point of both
 * propagation directions simultaneously.</p>
 *
 * <h2>Why a fixed point is required and how it terminates</h2>
 *
 * <p>The interprocedural analysis is a monotone dataflow problem: each
 * site's status only ever increases (by {@link EscapeStatus#ordinal()}),
 * and each function's contribution to the shared map only ever grows.
 * The lattice has finite height (six levels of {@link EscapeStatus}),
 * the number of sites is finite, and each batch either commits at least
 * one upgrade or terminates the loop. The loop therefore converges in
 * O(sites × lattice height) iterations, bounded in practice by the
 * number of distinct site-upgrade chains in the module.</p>
 *
 * <p>Concurrency: {@link #globalStatus} is a {@link ConcurrentHashMap}.
 * Workers read it during their analysis (for seeding and for the
 * {@code getMaxStatus} fallback) while the main thread writes it after
 * every batch. Iteration order is not observed anywhere, so the weak
 * consistency of {@code ConcurrentHashMap} is sufficient — a worker that
 * misses a status in one iteration will observe it in the next, because
 * every upgrade re-enqueues the allocator and all callers.</p>
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
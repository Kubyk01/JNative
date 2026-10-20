package io.github.kubyk01.application.service.analyzer.escapeanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.ir.*;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.util.LlvmUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.*;

import static io.github.kubyk01.util.LlvmUtil.canonicalCalleeKey;
import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.isAllocation;
import static io.github.kubyk01.util.LlvmUtil.isLazyClinitWrapper;

@Slf4j
public class InterproceduralEscape {

    private static final int EVAL_BATCH_SIZE = 2048;

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final Map<String, EscapeSummary> summaries;
    private final DependencyResolver resolver;
    private final Scheduler scheduler;
    private final Map<AllocationSite, EscapeStatus> globalStatus = new HashMap<>();
    private final Map<AllocationSite, Function> siteToFunction = new HashMap<>();

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

    public Map<AllocationSite, EscapeStatus> analyze() {
        // Seed: every allocation site observed in the module starts as
        // STACK. The site-to-function map lets propagation enqueue the
        // defining function when a site's status improves.
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (isAllocation(inst.getOpcode()) && inst.getResult() != null) {
                        PointsToSet pts = aliasResult.getPointsTo(inst.getResult());
                        for (AllocationSite site : pts.getSites()) {
                            globalStatus.put(site, EscapeStatus.STACK);
                            siteToFunction.put(site, func);
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

        Deque<Function> worklist = new ArrayDeque<>(live);
        Set<Function> inWorklist = new HashSet<>(live);

        long processed = 0;
        long lastLog = 0;

        while (!worklist.isEmpty()) {
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
                        func, aliasResult, summaries, resolver).analyze());

            for (int i = 0; i < batch.size(); i++) {
                Function func = batch.get(i);
                Map<AllocationSite, EscapeStatus> localStatus = results.get(i);
                processed++;

                boolean functionChanged = false;
                for (Map.Entry<AllocationSite, EscapeStatus> entry : localStatus.entrySet()) {
                    AllocationSite site = entry.getKey();
                    EscapeStatus newEsc = entry.getValue();
                    EscapeStatus old = globalStatus.getOrDefault(site, EscapeStatus.STACK);
                    if (newEsc.ordinal() > old.ordinal()) {
                        globalStatus.put(site, newEsc);
                        functionChanged = true;
                        propagateThroughFields(site, newEsc, worklist, inWorklist);
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

    /**
     * Transitive escape propagation through heap reachability.
     *
     * <p>If a site {@code A} reaches status {@code S}, every site stored
     * into any field of {@code A} reaches status at least {@code S}, and
     * the same applies recursively. Without this, a {@code MethodType}
     * stored into {@code VarForm.types[i]} would remain {@code STACK} even
     * after {@code VarForm} became {@code GLOBAL} via a static {@code VarHandle},
     * causing its destructor to free the ptypes array while a live reference
     * remained.</p>
     *
     * <p>The propagation is monotone: a site's status only ever grows, so
     * the recursive walk terminates after at most one pass per escape level
     * per site. Improved sites are pushed onto the worklist so that the
     * defining function's {@link IntraproceduralEscape} runs again with the
     * new status available for reading through {@code seedFromOwnSummary}.</p>
     *
     * <p>{@link AllocationSite#UNKNOWN} is skipped: it represents an
     * "external source" whose own escape status is unknowable.</p>
     */
    private void propagateThroughFields(AllocationSite startSite,
                                        EscapeStatus status,
                                        Deque<Function> worklist,
                                        Set<Function> inWorklist) {
        Deque<AllocationSite> work = new ArrayDeque<>();
        work.push(startSite);
        Set<AllocationSite> visited = new HashSet<>();
        visited.add(startSite);

        while (!work.isEmpty()) {
            AllocationSite cur = work.poll();
            Map<String, PointsToSet> fields =
                aliasResult.getGraph().getFieldPointsToMap(cur);
            if (fields == null) continue;
            for (PointsToSet pts : fields.values()) {
                for (AllocationSite fieldSite : pts.getSites()) {
                    if (fieldSite == AllocationSite.UNKNOWN) continue;
                    if (!visited.add(fieldSite)) continue;

                    EscapeStatus old = globalStatus.getOrDefault(
                        fieldSite, EscapeStatus.STACK);
                    if (status.ordinal() > old.ordinal()) {
                        globalStatus.put(fieldSite, status);

                        Function f = siteToFunction.get(fieldSite);
                        if (f != null && inWorklist.add(f)) {
                            worklist.add(f);
                        }
                        work.push(fieldSite);
                    }
                }
            }
        }
    }
}
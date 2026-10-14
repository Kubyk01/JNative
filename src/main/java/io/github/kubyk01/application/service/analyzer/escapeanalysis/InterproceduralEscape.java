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

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final Map<String, EscapeSummary> summaries;
    private final DependencyResolver resolver;
    private final Scheduler scheduler;
    private final Map<AllocationSite, EscapeStatus> globalStatus = new HashMap<>();

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

        // Reverse call graph: when a function's local status grows, only
        // its callers need to be re-analyzed. Building this once avoids
        // re-deriving it (and the module-wide scans that would otherwise
        // be required) on every iteration.
        Map<String, Set<Function>> callersOf = new HashMap<>();
        for (Function f : live) {
            for (BasicBlock b : f.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    String callee = extractCalleeName(inst);
                    if (callee == null) continue;
                    String calleeKey = canonicalCalleeKey(callee);

                    // See the comment on LlvmUtil.isLazyClinitWrapper.
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
            List<Function> batch = new ArrayList<>();
            while (!worklist.isEmpty()) {
                Function f = worklist.poll();
                inWorklist.remove(f);
                batch.add(f);
            }

            List<Map<AllocationSite, EscapeStatus>> results =
                LlvmUtil.parallelMapOrdered(
                    batch, scheduler,
                    func -> new IntraproceduralEscape(
                            func, aliasResult, summaries, resolver).analyze());
            // parallelMapOrdered already validates the result size.

            for (int i = 0; i < batch.size(); i++) {
                Function func = batch.get(i);
                Map<AllocationSite, EscapeStatus> localStatus = results.get(i);
                processed++;

                boolean functionChanged = false;
                for (Map.Entry<AllocationSite, EscapeStatus> entry : localStatus.entrySet()) {
                    EscapeStatus old = globalStatus.getOrDefault(
                        entry.getKey(), EscapeStatus.STACK);
                    if (entry.getValue().ordinal() > old.ordinal()) {
                        globalStatus.put(entry.getKey(), entry.getValue());
                        functionChanged = true;
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

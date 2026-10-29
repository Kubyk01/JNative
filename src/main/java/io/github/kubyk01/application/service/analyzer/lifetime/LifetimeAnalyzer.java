package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.application.service.analyzer.ssa.DominatorTree;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.ir.*;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.util.LlvmUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.*;

@Slf4j
public class LifetimeAnalyzer {

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final EscapeAnalysisResult escapeResult;
    private final Map<String, FunctionSummary> summaries;
    private Scheduler scheduler = Schedulers.single();

    public LifetimeAnalyzer(Module module,
                            AliasAnalysisResult aliasResult,
                            EscapeAnalysisResult escapeResult,
                            Map<String, FunctionSummary> summaries) {
        this.module = module;
        this.aliasResult = aliasResult;
        this.escapeResult = escapeResult;
        this.summaries = summaries;
    }

    public void setScheduler(Scheduler scheduler) {
        if (scheduler != null) this.scheduler = scheduler;
    }

    // ==================================================================
    //  Result record for one function
    // ==================================================================

    private record FunctionBatchResult(
        Map<AllocationSite, Set<DestructionPoint>> destructionPoints,
        Set<AllocationSite> unresolved,
        Map<Instruction, Boolean> fieldOverwriteLivenessGates
    ) {}

    // ==================================================================
    //  Public entry point
    // ==================================================================

    public LifetimeAnalysisResult analyze(Map<AllocationSite, Value> siteToValue) {
        // ---- 1. Collect sites (parallel over functions) ----------
        AllocationSiteCollector collector =
            new AllocationSiteCollector(module, aliasResult, scheduler);
        List<AllocationSite> allSites = collector.collect();

        // ---- 2. Cyclic reference detection ------------------------
        CyclicReferenceDetector cyclicDetector =
            new CyclicReferenceDetector(aliasResult.getGraph(), allSites);
        Set<AllocationSite> cyclicSites = cyclicDetector.detect();

        // ---- 3. Group candidate sites by allocator function -------
        Map<Function, List<AllocationSite>> sitesByFunction = new HashMap<>();
        for (AllocationSite site : allSites) {
            if (cyclicSites.contains(site)) continue;
            Function func = module.getFunction(site.getMethodName());
            if (func == null || func.getEntryBlock() == null) continue;
            sitesByFunction.computeIfAbsent(func, k -> new ArrayList<>()).add(site);
        }

        List<Function> liveFunctions = new ArrayList<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() != null) liveFunctions.add(f);
        }

        // ---- 4. Diagnostic: estimate peak bitset footprint ------
        //
        // Emitted before any allocation so that a pathological input
        // is visible in the log rather than as an OOM trace.
        long estimatedPeak = 0L;
        for (Function f : liveFunctions) {
            long bytes = estimateFunctionBitsetBytes(f, sitesByFunction.get(f));
            if (bytes > estimatedPeak) estimatedPeak = bytes;
        }
        log.info("Liveness: {} live function(s), {} allocation site(s), "
                + "per-function peak bitset estimate ≈ {} bytes",
            liveFunctions.size(), allSites.size(), estimatedPeak);

        // ---- 5. Per-function batch, in parallel -------------------
        //
        // Each task creates its own per-function state, consumes it,
        // and returns only the durable result. No per-function liveness
        // object escapes the lambda.
        LivenessAnalyzer livenessAnalyzer =
            new LivenessAnalyzer(module, aliasResult, summaries);

        List<FunctionBatchResult> batches = LlvmUtil.awaitFlux(
            Flux.fromIterable(liveFunctions)
                .parallel()
                .runOn(scheduler)
                .map(func -> processFunction(func,
                    sitesByFunction.getOrDefault(func, List.of()),
                    cyclicSites, livenessAnalyzer, siteToValue))
                .sequential());

        // ---- 6. Merge -------------------------------------------
        LifetimeAnalysisResult result = new LifetimeAnalysisResult();
        result.getUnresolved().addAll(cyclicSites);

        Map<Instruction, Boolean> allGates = new HashMap<>();
        if (batches != null) {
            for (FunctionBatchResult batch : batches) {
                for (Map.Entry<AllocationSite, Set<DestructionPoint>> e
                    : batch.destructionPoints().entrySet()) {
                    result.getDestructionPoints()
                        .computeIfAbsent(e.getKey(), k -> new HashSet<>())
                        .addAll(e.getValue());
                }
                result.getUnresolved().addAll(batch.unresolved());
                allGates.putAll(batch.fieldOverwriteLivenessGates());
            }
        }
        result.setFieldOverwriteLivenessGates(allGates);

        // ---- 7. Release the value-level points-to cache -----------
        //
        // Nothing downstream queries it. Keeping it alive would defeat
        // the memory savings of per-function processing.
        livenessAnalyzer.clearCaches();

        return result;
    }

    // ==================================================================
    //  Per-function batch
    // ==================================================================

    private FunctionBatchResult processFunction(Function func,
                                                List<AllocationSite> sitesInFunc,
                                                Set<AllocationSite> cyclicSites,
                                                LivenessAnalyzer livenessAnalyzer,
                                                Map<AllocationSite, Value> siteToValue) {
        // 1. Liveness for this function only.
        LivenessAnalyzer.FunctionLiveness liveness =
            livenessAnalyzer.computeForFunction(func);

        // 2. Dominator tree and natural loops.
        DominatorTree dom = new DominatorTree(func);
        Map<BasicBlock, Set<BasicBlock>> loops = detectNaturalLoops(func, dom);

        // 3. Value-level last-use map, computed exactly once.
        Map<Value, List<LivenessAnalyzer.ValueLastUse>> lastUses =
            livenessAnalyzer.computeValueLastUses(func);

        // 4. Per-site destruction points.
        Map<AllocationSite, Set<DestructionPoint>> points = new HashMap<>();
        Set<AllocationSite> unresolved = new HashSet<>();
        for (AllocationSite site : sitesInFunc) {
            if (cyclicSites.contains(site)) continue;
            SiteResult r = computeSite(site, func, dom, loops, liveness,
                lastUses, siteToValue);
            if (r.unresolved()) {
                unresolved.add(site);
            } else if (!r.points().isEmpty()) {
                points.put(site, new HashSet<>(r.points()));
            }
        }

        // 5. Field-overwrite liveness gates.
        //
        // DestructorInserter will need "is the previous field value dead?"
        // for every PUT_FIELD in this function. The liveness part of that
        // answer is evaluated here while liveness is in memory; only the
        // boolean result is retained.
        Map<Instruction, Boolean> gates = new HashMap<>();
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() != Opcode.PUT_FIELD) continue;
                if (!LivenessAnalyzer.FunctionLiveness.isReferenceRhs(inst)) continue;
                if (LivenessAnalyzer.FunctionLiveness.isOuterInstanceField(
                    LivenessAnalyzer.FunctionLiveness.fieldNameOf(inst))) continue;
                gates.put(inst, computeFieldLivenessGate(inst, block, liveness));
            }
        }

        // The local `liveness` object goes out of scope here and is
        // collectable as soon as the worker thread moves on.
        return new FunctionBatchResult(points, unresolved, gates);
    }

    // ==================================================================
    //  Field overwrite liveness gate
    // ==================================================================

    /**
     * Evaluates the liveness half of {@code DestructorInserter}'s
     * {@code oldValueIsDead} predicate:
     *
     * <pre>
     *   gate == true
     *     ⟺  fieldSites non-empty ∧ ¬containsUnknown
     *         ∧ liveness info exists for block
     *         ∧ no site in fieldSites is live-out of block
     * </pre>
     *
     * <p>The other half of the predicate — "no later instruction in the
     * same block reads the field value" — is evaluated by
     * {@code DestructorInserter} at instrumentation time, since it does
     * not consult liveness and does not benefit from being precomputed.</p>
     */
    private boolean computeFieldLivenessGate(Instruction putField,
                                             BasicBlock block,
                                             LivenessAnalyzer.FunctionLiveness liveness) {
        if (!liveness.hasLiveOut(block)) return false;

        PointsToSet fieldSites =
            LivenessAnalyzer.FunctionLiveness.fieldSitesOf(putField,
                new LivenessAnalyzer(module, aliasResult, summaries));
        // FunctionLiveness.fieldSitesOf creates a temporary LivenessAnalyzer
        // wrapper to reach aliasResult. Substitute a direct access:
        if (fieldSites == null) return false;

        if (fieldSites.isEmpty() || fieldSites.containsUnknown()) return false;

        for (AllocationSite site : fieldSites.getSites()) {
            if (site == AllocationSite.UNKNOWN) continue;
            if (liveness.isLiveOut(block, site)) return false;
        }
        return true;
    }

    // ==================================================================
    //  Diagnostic estimate
    // ==================================================================

    private long estimateFunctionBitsetBytes(Function func,
                                             List<AllocationSite> sitesInFunc) {
        if (sitesInFunc == null || sitesInFunc.isEmpty()) return 0L;
        // Upper bound on S_f; the real local set may include sites
        // reachable through fields, but the estimate is an upper bound
        // used only for logging and does not affect correctness.
        int upperSites = sitesInFunc.size();
        int upperWords = (upperSites + 63) >>> 6;
        return (long) func.getBlocks().size() * upperWords * 16L;
    }

    // ==================================================================
    //  computeSite — the per-site destruction-point logic
    // ==================================================================

    private record SiteResult(AllocationSite site,
                              List<DestructionPoint> points,
                              boolean unresolved) {}

    private SiteResult computeSite(AllocationSite site,
                                   Function func,
                                   DominatorTree dom,
                                   Map<BasicBlock, Set<BasicBlock>> loops,
                                   LivenessAnalyzer.FunctionLiveness liveness,
                                   Map<Value, List<LivenessAnalyzer.ValueLastUse>> valueLastUses,
                                   Map<AllocationSite, Value> siteToValue) {
        EscapeStatus status = escapeResult.getSiteStatus(site);
        if (status == EscapeStatus.GLOBAL
            || status == EscapeStatus.THREAD
            || status == EscapeStatus.RETURN) {
            return new SiteResult(site, List.of(), false);
        }

        Value allocVal = siteToValue.get(site);
        if (allocVal == null) return new SiteResult(site, List.of(), false);

        Set<Value> roots = collectAliases(site, func, allocVal);

        List<DestructionPoint> points = new ArrayList<>();
        for (Value root : roots) {
            List<LivenessAnalyzer.ValueLastUse> uses = valueLastUses.get(root);
            if (uses == null || uses.isEmpty()) continue;

            LivenessAnalyzer.ValueLastUse last = uses.getLast();
            BasicBlock block = last.block();

            if (!valueAvailableAt(root, block, dom)) continue;

            if (!liveness.isLiveIn(block, site)) continue;
            if (liveness.isLiveOut(block, site)) continue;

            Instruction before = null;
            List<Instruction> insts = block.getInstructions();
            int idx = last.instructionIndex();
            if (idx >= 0 && idx + 1 < insts.size()) before = insts.get(idx + 1);
            points.add(new DestructionPoint(block, before, root));
        }

        List<DestructionPoint> filtered = filterPointsInLoops(points, site, loops, liveness);

        if (filtered.isEmpty()) {
            List<BasicBlock> returnBlocks = new ArrayList<>();
            for (BasicBlock block : func.getBlocks()) {
                if (block.getTerminator() instanceof ReturnTerminator) {
                    returnBlocks.add(block);
                }
            }
            if (returnBlocks.isEmpty()) return new SiteResult(site, List.of(), true);
            BasicBlock lca = dom.getLCA(returnBlocks);
            if (lca == null) return new SiteResult(site, List.of(), true);
            if (!valueAvailableAt(allocVal, lca, dom)) return new SiteResult(site, List.of(), true);
            return new SiteResult(site,
                List.of(new DestructionPoint(lca, null, allocVal)), false);
        }

        return new SiteResult(site, dedupPoints(filtered, dom), false);
    }

    private boolean valueAvailableAt(Value v, BasicBlock block, DominatorTree dom) {
        switch (v) {
            case null -> { return false; }
            case Constant ignored -> { return true; }
            case Parameter ignored -> { return true; }
            case UndefinedValue ignored -> { return true; }
            case Temporary t -> {
                Instruction def = t.getDefiningInstruction();
                if (def == null) return false;
                BasicBlock defBlock = def.getParent();
                if (defBlock == null) return false;
                if (defBlock == block) return true;
                return dom.dominates(defBlock, block);
            }
            default -> { }
        }
        return false;
    }

    private Set<Value> collectAliases(AllocationSite site, Function func, Value allocVal) {
        Set<Value> aliases = new LinkedHashSet<>();
        aliases.add(allocVal);
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                Value result = inst.getResult();
                if (result != null && pointsToSite(result, site)) aliases.add(result);
                for (Value op : inst.getOperands()) {
                    if (op != null && pointsToSite(op, site)) aliases.add(op);
                }
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                for (Value op : terminatorOperands(term)) {
                    if (op != null && pointsToSite(op, site)) aliases.add(op);
                }
            }
        }
        for (Parameter p : func.getParameters()) {
            if (pointsToSite(p, site)) aliases.add(p);
        }
        return aliases;
    }

    private boolean pointsToSite(Value v, AllocationSite site) {
        return aliasResult.getPointsTo(v).getSites().contains(site);
    }

    private List<Value> terminatorOperands(Terminator term) {
        List<Value> out = new ArrayList<>(2);
        if (term instanceof ReturnTerminator rt)               { if (rt.getValue() != null) out.add(rt.getValue()); }
        else if (term instanceof ThrowTerminator tt)           { if (tt.getException() != null) out.add(tt.getException()); }
        else if (term instanceof CondBranchTerminator cbt)     { if (cbt.getCondition() != null) out.add(cbt.getCondition()); }
        else if (term instanceof LookupSwitchTerminator lst)   { if (lst.getKey() != null) out.add(lst.getKey()); }
        else if (term instanceof TableSwitchTerminator tst)    { if (tst.getKey() != null) out.add(tst.getKey()); }
        else if (term instanceof IndirectBranchTerminator ibt) { if (ibt.getTargetBlock() != null) out.add(ibt.getTargetBlock()); }
        return out;
    }

    private List<DestructionPoint> filterPointsInLoops(List<DestructionPoint> points,
                                                       AllocationSite site,
                                                       Map<BasicBlock, Set<BasicBlock>> loops,
                                                       LivenessAnalyzer.FunctionLiveness liveness) {
        if (points.isEmpty() || loops == null || loops.isEmpty()) return points;
        List<DestructionPoint> filtered = new ArrayList<>();
        for (DestructionPoint p : points) {
            boolean inLiveLoop = false;
            for (Map.Entry<BasicBlock, Set<BasicBlock>> e : loops.entrySet()) {
                if (e.getValue().contains(p.getBlock())
                    && liveness.isLiveIn(e.getKey(), site)) {
                    inLiveLoop = true;
                    break;
                }
            }
            if (!inLiveLoop) filtered.add(p);
        }
        return filtered;
    }

    private List<DestructionPoint> dedupPoints(List<DestructionPoint> points,
                                               DominatorTree dom) {
        List<DestructionPoint> kept = new ArrayList<>();
        Set<BasicBlock> seen = new HashSet<>();
        for (DestructionPoint p : points) {
            if (!seen.add(p.getBlock())) continue;
            boolean dominated = false;
            for (DestructionPoint q : points) {
                if (q.getBlock() != p.getBlock()
                    && dom.dominates(q.getBlock(), p.getBlock())) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) kept.add(p);
        }
        return kept;
    }

    private Map<BasicBlock, Set<BasicBlock>> detectNaturalLoops(Function func,
                                                                DominatorTree domTree) {
        Map<BasicBlock, Set<BasicBlock>> loops = new HashMap<>();
        for (BasicBlock block : func.getBlocks()) {
            for (BasicBlock pred : block.getPredecessors()) {
                if (domTree.dominates(block, pred)) {
                    Set<BasicBlock> body = new HashSet<>();
                    findLoopBody(block, pred, body);
                    loops.put(block, body);
                }
            }
        }
        return loops;
    }

    private void findLoopBody(BasicBlock header, BasicBlock latch, Set<BasicBlock> body) {
        Deque<BasicBlock> stack = new ArrayDeque<>();
        stack.push(latch);
        body.add(header);
        while (!stack.isEmpty()) {
            BasicBlock block = stack.pop();
            if (!body.add(block)) continue;
            for (BasicBlock pred : block.getPredecessors()) {
                if (!body.contains(pred)) stack.push(pred);
            }
        }
    }
}
package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.application.service.analyzer.ssa.DominatorTree;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.ir.*;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.util.LlvmUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class LifetimeAnalyzer {

    private static final int PROGRESS_EVERY = 20_000;

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

    public LifetimeAnalysisResult analyze(Map<AllocationSite, Value> siteToValue) {
        LifetimeAnalysisResult result = new LifetimeAnalysisResult();

        // -------- 1. Collect sites (parallel over functions) -----
        AllocationSiteCollector collector =
                new AllocationSiteCollector(module, aliasResult, scheduler);
        List<AllocationSite> allSites = collector.collect();
        System.out.println("Lifetime: " + allSites.size() + " allocation sites");

        // -------- 2. Cyclic reference detection --------
        CyclicReferenceDetector cyclicDetector =
                new CyclicReferenceDetector(aliasResult.getGraph(), allSites);
        Set<AllocationSite> cyclicSites = cyclicDetector.detect();
        result.getUnresolved().addAll(cyclicSites);
        System.out.println("Lifetime: " + cyclicSites.size() + " cyclic site(s)");

        // -------- 3. Liveness (now internally parallel) --------
        LivenessAnalyzer livenessAnalyzer =
                new LivenessAnalyzer(module, aliasResult, summaries);
        livenessAnalyzer.setScheduler(scheduler);
        livenessAnalyzer.computeLiveSets(allSites);

        // -------- 3b. Publish live-out per block (read-only views) --------
        Map<BasicBlock, Set<AllocationSite>> liveOutMap = livenessAnalyzer.getLiveOutMap();
        result.setLiveOut(liveOutMap != null ? liveOutMap : Map.of());

        // -------- 4. Dominator trees + natural loops, in parallel --------
        List<Function> live = new ArrayList<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() != null) live.add(f);
        }

        Map<Function, DominatorTree> domTrees = new ConcurrentHashMap<>();
        Map<Function, Map<BasicBlock, Set<BasicBlock>>> loopHeaders =
                new ConcurrentHashMap<>();

        LlvmUtil.awaitMono(Flux.fromIterable(live)
                .parallel()
                .runOn(scheduler)
                .doOnNext(func -> {
                    DominatorTree domTree = new DominatorTree(func);
                    domTrees.put(func, domTree);
                    loopHeaders.put(func, detectNaturalLoops(func, domTree));
                })
                .sequential()
                .then()
        );
        System.out.println("Lifetime: dominator trees for " + live.size() + " functions");

        // -------- 5. Per-site analysis, in parallel over functions --------
        //
        // Sites are grouped by their allocating function so that the
        // function is scanned once for aliases and once for last uses,
        // instead of once per site. Each group reads only frozen
        // aliasResult / escapeResult / liveness and the precomputed
        // dominator/loop structures, so groups are independent.
        Set<AllocationSite> skip = result.getUnresolved();

        Map<String, List<AllocationSite>> byFunction = new HashMap<>();
        for (AllocationSite site : allSites) {
            if (skip.contains(site)) continue;
            byFunction.computeIfAbsent(site.getMethodName(), k -> new ArrayList<>()).add(site);
        }
        List<List<AllocationSite>> groups = new ArrayList<>(byFunction.values());

        // Each worker accumulates its own per-site results, then the
        // main thread merges them sequentially. That keeps the result
        // maps as plain HashMaps (no concurrency concerns) while still
        // running the analysis itself in parallel.
        int totalGroups = groups.size();
        AtomicInteger doneGroups = new AtomicInteger();
        List<List<SiteResult>> groupResults = LlvmUtil.awaitFlux(
                Flux.fromIterable(groups)
                        .parallel()
                        .runOn(scheduler)
                        .map(group -> {
                            List<SiteResult> r = computeFunctionSites(group, domTrees,
                                    loopHeaders, livenessAnalyzer, siteToValue);
                            int n = doneGroups.incrementAndGet();
                            if (n % PROGRESS_EVERY == 0) {
                                System.out.println("Lifetime: per-site analysis " + n + "/"
                                        + totalGroups + " functions");
                            }
                            return r;
                        })
                        .sequential()
        );

        if (groupResults != null) {
            for (List<SiteResult> srs : groupResults) {
                for (SiteResult sr : srs) {
                    if (sr.unresolved()) {
                        result.getUnresolved().add(sr.site());
                    } else {
                        for (DestructionPoint point : sr.points()) {
                            result.getDestructionPoints()
                                    .computeIfAbsent(sr.site(), k -> new HashSet<>())
                                    .add(point);
                        }
                    }
                }
            }
        }
        System.out.println("Lifetime: done, " + result.getDestructionPoints().size()
                + " site(s) with destruction points, " + result.getUnresolved().size()
                + " unresolved");

        return result;
    }

    /**
     * Runs {@link #computeSite} for every site allocated in one function.
     * All sites in {@code group} share the same method name.
     */
    private List<SiteResult> computeFunctionSites(List<AllocationSite> group,
                                                  Map<Function, DominatorTree> domTrees,
                                                  Map<Function, Map<BasicBlock, Set<BasicBlock>>> loopHeaders,
                                                  LivenessAnalyzer livenessAnalyzer,
                                                  Map<AllocationSite, Value> siteToValue) {
        List<SiteResult> out = new ArrayList<>(group.size());
        List<AllocationSite> pending = new ArrayList<>(group.size());

        // GLOBAL / THREAD / RETURN: object outlives the frame, so no per-use
        // destruction point is legal.
        //
        // HEAP: per-use destruction point. Such objects are only reachable
        // through a field of a container; if the container is STACK, the frame
        // teardown does not walk its fields, so the object must be freed at its
        // last use (paired with a field null-out in insertDestructorCall, so the
        // container's destructor cannot double-free it).
        //
        // STACK: skipped — materialised via alloca, released by frame teardown.
        // UNKNOWN: treated conservatively as freeable.
        for (AllocationSite site : group) {
            EscapeStatus status = escapeResult.getSiteStatus(site);
            if (status == EscapeStatus.GLOBAL
                    || status == EscapeStatus.THREAD
                    || status == EscapeStatus.RETURN) {
                out.add(new SiteResult(site, List.of(), false));
            } else {
                pending.add(site);
            }
        }
        if (pending.isEmpty()) return out;

        Function func = module.getFunction(group.getFirst().getMethodName());
        DominatorTree dom = (func == null || func.getEntryBlock() == null)
                ? null : domTrees.get(func);
        if (dom == null) {
            for (AllocationSite site : pending) out.add(new SiteResult(site, List.of(), false));
            return out;
        }
        Map<BasicBlock, Set<BasicBlock>> loops = loopHeaders.get(func);

        Map<AllocationSite, Value> allocVals = new HashMap<>();
        List<AllocationSite> withValue = new ArrayList<>(pending.size());
        for (AllocationSite site : pending) {
            Value allocVal = siteToValue.get(site);
            if (allocVal == null) {
                out.add(new SiteResult(site, List.of(), false));
            } else {
                allocVals.put(site, allocVal);
                withValue.add(site);
            }
        }
        if (withValue.isEmpty()) return out;

        Map<AllocationSite, Set<Value>> aliases = collectAliases(withValue, func, allocVals);
        Map<Value, List<LivenessAnalyzer.ValueLastUse>> valueLastUses =
                livenessAnalyzer.computeValueLastUses(func);

        for (AllocationSite site : withValue) {
            out.add(computeSite(site, func, dom, loops, allocVals.get(site),
                    aliases.get(site), valueLastUses, livenessAnalyzer));
        }
        return out;
    }

    private SiteResult computeSite(AllocationSite site,
                                   Function func,
                                   DominatorTree dom,
                                   Map<BasicBlock, Set<BasicBlock>> loops,
                                   Value allocVal,
                                   Set<Value> roots,
                                   Map<Value, List<LivenessAnalyzer.ValueLastUse>> valueLastUses,
                                   LivenessAnalyzer livenessAnalyzer) {
        // Any value that may point to the site is a legal "root" for the
        // destructor call (roots); pick the last syntactic use of each.
        List<DestructionPoint> points = new ArrayList<>();
        for (Value root : roots) {
            List<LivenessAnalyzer.ValueLastUse> uses = valueLastUses.get(root);
            if (uses == null || uses.isEmpty()) continue;

            // The list is built in block/instruction order, so the final
            // entry is the root's last syntactic use.
            LivenessAnalyzer.ValueLastUse last = uses.getLast();
            BasicBlock block = last.block();

            // ------------------------------------------------------------------
            // The destructor call is inserted at the last-use site and takes
            // the root value as its argument. For the call to be well-formed
            // IR, the root's definition must dominate that block. When it does
            // not — a case reachable through an over-approximated CFG edge or
            // through a value that only survives on some paths — the call
            // cannot be emitted here. Skipping the destruction point leaves
            // the object unfreed until process exit (or, when the object is
            // reachable from a static field, until @__jnative_shutdown), which
            // is strictly better than producing IR the LLVM verifier rejects.
            // ------------------------------------------------------------------
            if (!valueAvailableAt(root, block, dom)) {
                continue;
            }

            // A real death requires the site to be live on entry to the
            // block but not live on exit. If it is still live on exit the
            // value flows onward and this use is not a death site.
            Set<AllocationSite> liveIn = livenessAnalyzer.getLiveIn(block);
            Set<AllocationSite> liveOut = livenessAnalyzer.getLiveOut(block);
            if (!liveIn.contains(site) || liveOut.contains(site)) continue;

            Instruction before = null;
            List<Instruction> insts = block.getInstructions();
            int idx = last.instructionIndex();

            // ------------------------------------------------------------------
            // The destructor must run AFTER the value's last use, not before it.
            //
            // DestructionPoint.beforeInstruction has the semantics "insert the
            // destructor call immediately before this instruction"; a null
            // value means "at the end of the block, before the terminator".
            // The value at `idx` is the last instruction that consumes the
            // root, so the destructor must be placed at `idx + 1` -- i.e.
            // before whatever instruction sits at `idx + 1`, or at the end of
            // the block when `idx` is the final instruction.
            //
            // Placing the destructor at `idx` (the previous behaviour) puts
            // it *before* the last use. Since a destructor for a class with
            // no reference-typed fields is emitted as `free(obj)`, and
            // DestructorSimplifier.inlineDestructors() collapses trivial
            // destructor calls into an in-place FREE instruction, the
            // emitted IR contains
            //
            //     free(obj);   // destructor, placed at index idx
            //     use(obj);    // last use, shifted to index idx + 1
            //
            // and the object is released the moment before its final
            // consumption. The LLVM verifier accepts this IR -- the
            // definition of obj does dominate the call site -- so the bug
            // is silent until the freed memory is re-used by another
            // allocation and the consumer dereferences the object's
            // overwritten vtable word. The observed crash was
            //
            //     SIGSEGV at java.security.AccessController.executePrivileged
            //     vtable word of the receiver contained the string
            //     "/usr/lib" written by an unrelated allocation that had
            //     reused the freed chunk.
            //
            // The check below is stated in terms of `idx + 1 < insts.size()`
            // rather than the equivalent `idx + 1 <= insts.size() - 1` so
            // that the compiler emits a single comparison against the size
            // of the instruction list. Both forms are equivalent, and both
            // leave `before == null` when the last use is the final
            // instruction of the block, or when it is the terminator (whose
            // index is insts.size()).
            // ------------------------------------------------------------------
            if (idx >= 0 && idx + 1 < insts.size()) {
                before = insts.get(idx + 1);
            }

            points.add(new DestructionPoint(block, before, root));
        }

        List<DestructionPoint> filtered =
                filterPointsInLoops(points, site, loops, livenessAnalyzer);

        if (filtered.isEmpty()) {
            // No provably-dead last use. Fall back to the common
            // post-return point so the object is still released on normal
            // exit; this mirrors the pre-existing conservative behaviour.
            List<BasicBlock> returnBlocks = new ArrayList<>();
            for (BasicBlock block : func.getBlocks()) {
                if (block.getTerminator() instanceof ReturnTerminator) {
                    returnBlocks.add(block);
                }
            }
            if (returnBlocks.isEmpty()) {
                return new SiteResult(site, List.of(), true);
            }
            BasicBlock lca = dom.getLCA(returnBlocks);
            if (lca == null) {
                return new SiteResult(site, List.of(), true);
            }

            // Dominance check for the fallback path. The main path already filtered
            // by valueAvailableAt; the fallback uses allocVal directly, so it must
            // re-apply the same check. Otherwise an allocation in a branch or
            // try-guarded body whose merge point lies above the returns produces
            // a DestructionPoint with a non-dominating operand, and clang rejects
            // the module with "Instruction does not dominate all uses!".
            // Such sites are marked unresolved and left to shutdown reclamation.
            if (!valueAvailableAt(allocVal, lca, dom)) {
                return new SiteResult(site, List.of(), true);
            }

            return new SiteResult(site,
                    List.of(new DestructionPoint(lca, null, allocVal)), false);
        }

        return new SiteResult(site, dedupPoints(filtered, dom), false);
    }

    private record SiteResult(AllocationSite site,
                              List<DestructionPoint> points,
                              boolean unresolved) {}

    /**
     * Returns true when {@code v} can be legally named as an operand of an
     * instruction placed anywhere in {@code block}.
     *
     * <p>For the IR produced by
     * {@link io.github.kubyk01.application.service.analyzer.ssa.SSATransformer},
     * this is exactly the LLVM dominance rule: the defining instruction of
     * a value must dominate every instruction that uses it. A value defined
     * in a block that does not dominate {@code block} cannot be used there,
     * and any attempt to emit such a use produces IR that clang rejects
     * with</p>
     *
     * <pre>
     *   Instruction does not dominate all uses!
     * </pre>
     *
     * <p>Constants and parameters are always considered available: a
     * constant is an immediate operand, and every parameter is written
     * into its slot in the entry block, which dominates every block of
     * the function. For a {@link io.github.kubyk01.domain.ir.Temporary}
     * the defining instruction's parent block is compared against
     * {@code block} through the dominator tree. A temporary with no
     * defining instruction — an artifact of an earlier pass that has not
     * (yet) resolved it — is treated as unavailable, which is the
     * conservative choice.</p>
     */
    private boolean valueAvailableAt(Value v, BasicBlock block, DominatorTree dom) {
        switch (v) {
            case null -> {
                return false;
            }
            case Constant ignored2 -> {
                return true;
            }
            case Parameter ignored1 -> {
                return true;
            }
            case UndefinedValue ignored -> {
                return true;
            }
            case Temporary t -> {
                Instruction def = t.getDefiningInstruction();
                if (def == null) return false;
                BasicBlock defBlock = def.getParent();
                if (defBlock == null) return false;
                if (defBlock == block) return true;
                return dom.dominates(defBlock, block);
            }
            default -> {
            }
        }

        return false;
    }

    /**
     * For each site in {@code sites} (all allocated in {@code func}): the IR
     * values of {@code func} whose points-to set contains the site — the
     * allocation itself first, then every aliasing temporary, operand,
     * terminator operand and parameter in scan order. One scan of the
     * function serves all its sites; per site, the order of values is the
     * same as scanning the function for that site alone.
     */
    private Map<AllocationSite, Set<Value>> collectAliases(List<AllocationSite> sites,
                                                           Function func,
                                                           Map<AllocationSite, Value> allocVals) {
        int k = sites.size();
        AllocationSite[] byIndex = sites.toArray(new AllocationSite[0]);
        Arrays.sort(byIndex, Comparator.comparingInt(AllocationSite::getId));
        int[] ids = new int[k];
        List<Set<Value>> sets = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            ids[i] = byIndex[i].getId();
            Set<Value> aliases = new LinkedHashSet<>();
            aliases.add(allocVals.get(byIndex[i]));
            sets.add(aliases);
        }

        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                addAlias(inst.getResult(), ids, sets);
                for (Value op : inst.getOperands()) addAlias(op, ids, sets);
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                for (Value op : terminatorOperands(term)) addAlias(op, ids, sets);
            }
        }
        for (Parameter p : func.getParameters()) addAlias(p, ids, sets);

        Map<AllocationSite, Set<Value>> out = new HashMap<>(k * 2);
        for (int i = 0; i < k; i++) out.put(byIndex[i], sets.get(i));
        return out;
    }

    /** Adds {@code v} to the alias set of every site in {@code ids} (sorted) that it may point to. */
    private void addAlias(Value v, int[] ids, List<Set<Value>> sets) {
        if (v == null) return;
        PointsToSet pts = aliasResult.getPointsTo(v);
        if (pts.isEmpty()) return;
        // Walk the smaller side: the function's sites or the value's points-to set.
        if (pts.size() <= ids.length) {
            for (int id : pts.rawIds()) {
                int i = Arrays.binarySearch(ids, id);
                if (i >= 0) sets.get(i).add(v);
            }
        } else {
            for (int i = 0; i < ids.length; i++) {
                if (pts.containsId(ids[i])) sets.get(i).add(v);
            }
        }
    }

    private List<Value> terminatorOperands(Terminator term) {
        List<Value> out = new ArrayList<>();
        if (term instanceof ReturnTerminator) {
            Value v = ((ReturnTerminator) term).getValue();
            if (v != null) out.add(v);
        } else if (term instanceof ThrowTerminator) {
            Value v = ((ThrowTerminator) term).getException();
            if (v != null) out.add(v);
        } else if (term instanceof CondBranchTerminator) {
            Value v = ((CondBranchTerminator) term).getCondition();
            if (v != null) out.add(v);
        } else if (term instanceof LookupSwitchTerminator) {
            Value v = ((LookupSwitchTerminator) term).getKey();
            if (v != null) out.add(v);
        } else if (term instanceof TableSwitchTerminator) {
            Value v = ((TableSwitchTerminator) term).getKey();
            if (v != null) out.add(v);
        } else if (term instanceof IndirectBranchTerminator) {
            Value v = ((IndirectBranchTerminator) term).getTargetBlock();
            if (v != null) out.add(v);
        }
        return out;
    }

    /**
     * Removes points that fall inside a natural loop whose header still has
     * the site live-in. Freeing there would run the destructor on an object
     * that is used again on the next iteration.
     */
    private List<DestructionPoint> filterPointsInLoops(
            List<DestructionPoint> points,
            AllocationSite site,
            Map<BasicBlock, Set<BasicBlock>> loops,
            LivenessAnalyzer livenessAnalyzer) {
        if (points.isEmpty() || loops == null || loops.isEmpty()) return points;

        List<DestructionPoint> filtered = new ArrayList<>();
        for (DestructionPoint p : points) {
            boolean inLiveLoop = false;
            for (Map.Entry<BasicBlock, Set<BasicBlock>> e : loops.entrySet()) {
                if (e.getValue().contains(p.getBlock())
                        && livenessAnalyzer.getLiveIn(e.getKey()).contains(site)) {
                    inLiveLoop = true;
                    break;
                }
            }
            if (!inLiveLoop) filtered.add(p);
        }
        return filtered;
    }

    /**
     * Collapses candidate points: at most one per block, and no point whose
     * block is dominated by another candidate's block (the dominating block
     * is the earlier death site, so the dominated one is unreachable).
     */
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

    private void findLoopBody(BasicBlock header, BasicBlock latch,
                              Set<BasicBlock> body) {
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
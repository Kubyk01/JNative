package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.application.service.analyzer.ssa.DominatorTree;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
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

    public LifetimeAnalysisResult analyze(Map<AllocationSite, Value> siteToValue) {
        LifetimeAnalysisResult result = new LifetimeAnalysisResult();

        // -------- 1. Collect sites (parallel over functions) -----
        AllocationSiteCollector collector =
                new AllocationSiteCollector(module, aliasResult, scheduler);
        List<AllocationSite> allSites = collector.collect();

        // -------- 2. Cyclic reference detection --------
        CyclicReferenceDetector cyclicDetector =
                new CyclicReferenceDetector(aliasResult.getGraph(), allSites);
        Set<AllocationSite> cyclicSites = cyclicDetector.detect();
        result.getUnresolved().addAll(cyclicSites);

        // -------- 3. Liveness (now internally parallel) --------
        LivenessAnalyzer livenessAnalyzer =
                new LivenessAnalyzer(module, aliasResult, summaries);
        livenessAnalyzer.setScheduler(scheduler);
        livenessAnalyzer.computeLiveSets(allSites);

        // -------- 3b. Publish live-out per block -------------------------
        //
        // DestructorInserter.instrumentFieldOverwrites() consumes this map
        // to decide whether the previous value of a reference-typed field
        // is provably dead at a PUT_FIELD. The gate is:
        //
        //   emit the release iff no allocation site that could currently
        //   be in the field is live on exit from the block AND no
        //   subsequent instruction in the same block (or its terminator)
        //   reads such a site.
        //
        // The block-level part of that gate is exactly what live-out
        // provides. Getting it wrong in either direction is fatal:
        //
        //   - Emitting without live-out information frees objects that
        //     are still reachable through locals, parameters, other
        //     fields, or array elements. The concrete counterexample is
        //     java.util.HashMap.resize():
        //
        //         Node<K,V>[] oldTab = table;
        //         ...
        //         table = newTab;
        //         if (oldTab != null) {
        //             for (int j = 0; j < oldCap; ++j) {
        //                 e = oldTab[j];          // reads freed memory
        //                 ...
        //             }
        //         }
        //
        //     `oldTab` is still live in the local variable across the
        //     `table = newTab` store, so releasing the old table at the
        //     store corrupts the loop that follows. The observed
        //     AIOOBE — "Array index out of bounds in
        //     java.util.HashMap.resize" — was the emitter's bounds
        //     check firing on a garbage length word read from a
        //     recycled chunk.
        //
        //   - Not emitting at all leaves HEAP-escape sites with no
        //     destruction point (LifetimeAnalyzer.computeSite
        //     short-circuits on EscapeStatus.HEAP) and therefore leaks
        //     every intermediate value stored into an overwritten field.
        //     TestHeap's `h.payload = new Payload(...)` in a loop is
        //     the canonical case: without the pass, 9 999 999 Payloads
        //     are orphaned with no GC to reclaim them.
        //
        // The snapshot below includes one entry per block of every live
        // function — including terminal blocks, whose live-out is an
        // empty set. That "present but empty" state is what
        // DestructorInserter's gate distinguishes from "absent":
        //
        //     entry is empty set  -> nothing is live after this block,
        //                             so releasing the field's previous
        //                             value is safe.
        //     entry is absent     -> no liveness information for this
        //                             block (should not happen on a
        //                             well-formed module, but a future
        //                             pass that adds blocks after the
        //                             analysis could produce it).
        //                             Conservative: do not emit.
        //
        // The copy is shallow: the sets are read-only after
        // computeLiveSets() returns, so putting them into a plain
        // HashMap is both cheap and correct.
        // ------------------------------------------------------------------
        Map<BasicBlock, Set<AllocationSite>> liveOutSnapshot = new HashMap<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() == null) continue;
            for (BasicBlock b : f.getBlocks()) {
                liveOutSnapshot.put(b, livenessAnalyzer.getLiveOut(b));
            }
        }
        result.setLiveOut(liveOutSnapshot);

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

        // -------- 5. Per-site analysis, in parallel --------
        //
        // Each site writes to its own entry in the destination maps and
        // reads only from frozen aliasResult / escapeResult / liveness
        // and the precomputed dominator/loop structures. Independent
        // across sites.
        Set<AllocationSite> skip = result.getUnresolved();

        List<AllocationSite> candidates = new ArrayList<>(allSites.size());
        for (AllocationSite site : allSites) {
            if (skip.contains(site)) continue;
            candidates.add(site);
        }

        // Each worker accumulates its own per-site results, then the
        // main thread merges them sequentially. That keeps the result
        // maps as plain HashMaps (no concurrency concerns) while still
        // running the analysis itself in parallel.
        List<SiteResult> siteResults = LlvmUtil.awaitFlux(
                Flux.fromIterable(candidates)
                        .parallel()
                        .runOn(scheduler)
                        .map(site -> computeSite(site, domTrees, loopHeaders,
                                livenessAnalyzer, siteToValue))
                        .sequential()
        );

        if (siteResults != null) {
            for (SiteResult sr : siteResults) {
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

        return result;
    }

    private SiteResult computeSite(AllocationSite site,
                                   Map<Function, DominatorTree> domTrees,
                                   Map<Function, Map<BasicBlock, Set<BasicBlock>>> loopHeaders,
                                   LivenessAnalyzer livenessAnalyzer,
                                   Map<AllocationSite, Value> siteToValue) {
        EscapeStatus status = escapeResult.getSiteStatus(site);

        // ------------------------------------------------------------------
        // Ownership rule for HEAP sites.
        //
        // A HEAP allocation is one whose only remaining reference is inside
        // a field of another object. That owner is the sole entity entitled
        // to release the storage: DestructorInserter.createClassDestructor
        // already emits a destructor that walks every reference-typed field
        // of the owner and calls the field's destructor recursively, and
        // the single-free rule (delegatedToSuper) guarantees that the
        // object is freed exactly once along that chain.
        //
        // Emitting a per-use destruction point for a HEAP site would create
        // a second, competing owner of the same allocation -- the store
        // site. Its destructor runs while the real owner still holds a
        // reference to the object, and the results are exactly the two
        // failures observed in the previous two runs:
        //
        //   1. Use-after-free: the owner's field points at freed memory,
        //      and the first read through the field observes whatever the
        //      allocator wrote into the recycled chunk. On the run that
        //      produced this report the recycled chunk happened to contain
        //      the string "/usr/lib", which was then interpreted as a vtable
        //      by AccessController.executePrivileged.
        //
        //   2. Double free: the owner's destructor walks the field and
        //      frees the same storage again, which glibc's tcache detects
        //      and turns into a "free(): double free detected in tcache 2"
        //      abort.
        //
        // The reproduced crash was the deep form of the same defect:
        //
        //     ReferenceQueue.<init>:
        //         new ReentrantLock()               -> stored into this.lock
        //             ReentrantLock.<init>:
        //                 new NonfairSync()         -> stored into this.sync
        //                 per-use destructor for the Sync       (wrong)
        //         per-use destructor for the Lock           (wrong)
        //     ... later, an owner's destructor walks the field ...
        //     __destruct_ReentrantLock -> __destruct_Sync -> __destruct_AQS
        //         -> __destruct_AOS loads the (already freed) field
        //         -> loads a garbage exclusiveOwnerThread
        //         -> calls __destruct_java_lang_Thread(garbage)
        //         -> SIGSEGV
        //
        // The correct treatment of a HEAP site is therefore the same as for
        // GLOBAL / THREAD / RETURN: its lifetime is bounded by an entity
        // outside the destruction-point machinery, and no per-use destructor
        // is emitted for it. Storage is reclaimed through the owner's
        // destructor chain, which is the only correct owner of a HEAP
        // object.
        //
        // The per-store release that DestructorInserter emits at every
        // reference-typed PUT_FIELD is what closes the "overwritten field"
        // hole in this rule: when the container's field is overwritten,
        // the previous value's only remaining owner (the field) is
        // replaced, so the previous value must be released at the store.
        // That release is gated by the live-out map published above, so
        // it only fires when nothing else can observe the previous value.
        // This is what makes TestHeap's loop non-leaking without breaking
        // HashMap.resize's "oldTab" pattern.
        //
        // STACK is intentionally not listed here. A STACK object that is
        // used as an object is materialised via alloca (see
        // LlvmFunctionEmitter's NEW case and ScalarReplacer's first pass),
        // and its storage is released by the frame teardown. The
        // DestructorInserter.insert() method filters STACK sites with the
        // same reasoning, one layer later.
        //
        // UNKNOWN is likewise not listed. It is the default status returned
        // by EscapeAnalysisResult.getSiteStatus for a site that the
        // analysis did not classify at all; treating such a site as
        // potentially free-able at its last use preserves the previous
        // conservative behaviour and cannot introduce the HEAP double-free,
        // because an UNKNOWN site was never observed being stored into
        // another object's field.
        // ------------------------------------------------------------------
        if (status == EscapeStatus.GLOBAL
                || status == EscapeStatus.THREAD
                || status == EscapeStatus.RETURN
                || status == EscapeStatus.HEAP) {
            return new SiteResult(site, List.of(), false);
        }

        Function func = module.getFunction(site.getMethodName());
        if (func == null || func.getEntryBlock() == null) {
            return new SiteResult(site, List.of(), false);
        }

        DominatorTree dom = domTrees.get(func);
        if (dom == null) return new SiteResult(site, List.of(), false);

        Map<BasicBlock, Set<BasicBlock>> loops = loopHeaders.get(func);

        Value allocVal = siteToValue.get(site);
        if (allocVal == null) return new SiteResult(site, List.of(), false);

        // Any value that may point to the site is a legal "root" for the
        // destructor call; pick the last syntactic use of each.
        Set<Value> roots = collectAliases(site, func, allocVal);

        Map<Value, List<LivenessAnalyzer.ValueLastUse>> valueLastUses =
                livenessAnalyzer.computeValueLastUses(func);

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

            // ------------------------------------------------------------------
            // Dominance check for the fallback path.
            //
            // The main path above already filters out candidates whose root
            // value is not available at the insertion block (see
            // valueAvailableAt). The fallback path bypasses that filter and
            // uses the raw allocation value directly, so it must re-apply the
            // same check here. Without it, an allocation performed in a
            // block that does not dominate the LCA of the return blocks —
            // for example, an allocation inside a branch or inside a
            // try-guarded body whose merge point lies above the returns —
            // produces a DestructionPoint whose objectRef cannot legally be
            // named at the insertion site. The LLVM backend then rejects the
            // module with:
            //
            //   Instruction does not dominate all uses!
            //     %tmp = bitcast ptr %alloc to ptr
            //     call void @__destruct_X(ptr %tmp)
            //
            // Observed on ObjectInputStream$SerialCallbackContext inside
            // ObjectInputStream.readOrdinaryObject and on the two HashSets
            // inside ObjectInputStream$BlockDataInputStream, all three of
            // which are created inside a try-guard body and whose only
            // legal insertion point would be inside that guarded body, not
            // at the LCA of the returns.
            //
            // Treating such an allocation as unresolved is the correct
            // conservative answer: the analysis has no safe point at which
            // it can release the object, so the object is left to the
            // runtime's own shutdown path, exactly as a cyclic object is.
            // ------------------------------------------------------------------
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
     * All IR values in {@code func} whose points-to set contains
     * {@code site}: the allocation itself, any aliasing temporary, and any
     * parameter or field read that may refer to it.
     */
    private Set<Value> collectAliases(AllocationSite site,
                                      Function func,
                                      Value allocVal) {
        Set<Value> aliases = new LinkedHashSet<>();
        aliases.add(allocVal);
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                Value result = inst.getResult();
                if (result != null && pointsToSite(result, site)) {
                    aliases.add(result);
                }
                for (Value op : inst.getOperands()) {
                    if (op != null && pointsToSite(op, site)) {
                        aliases.add(op);
                    }
                }
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                for (Value op : terminatorOperands(term)) {
                    if (op != null && pointsToSite(op, site)) {
                        aliases.add(op);
                    }
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
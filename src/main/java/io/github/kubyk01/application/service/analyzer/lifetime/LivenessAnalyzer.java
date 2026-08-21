package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.ir.BasicBlock;
import io.github.kubyk01.domain.analyzer.ir.CondBranchTerminator;
import io.github.kubyk01.domain.analyzer.ir.Function;
import io.github.kubyk01.domain.analyzer.ir.Instruction;
import io.github.kubyk01.domain.analyzer.ir.LookupSwitchTerminator;
import io.github.kubyk01.domain.analyzer.ir.Module;
import io.github.kubyk01.domain.analyzer.ir.ReturnTerminator;
import io.github.kubyk01.domain.analyzer.ir.TableSwitchTerminator;
import io.github.kubyk01.domain.analyzer.ir.Terminator;
import io.github.kubyk01.domain.analyzer.ir.ThrowTerminator;
import io.github.kubyk01.domain.analyzer.ir.Value;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Computes liveness for all allocation sites of the module simultaneously.
 * Instead of per-block boolean flags, sets of live sites are used,
 * which reduces the analysis to a single fixed-point iteration for all sites at once
 * instead of O(N * B) when analyzing each site separately.
 *
 * Optimizations:
 * - points-to sets of values are cached (the graph is already stable after alias analysis);
 * - a single data-flow pass for all sites;
 * - block use-sets are collected in one instruction walk.
 */
@RequiredArgsConstructor
public class LivenessAnalyzer {

    private final Module module;
    private final AliasAnalysisResult aliasResult;

    // Points-to cache per Value (identity semantics, same as PointsToGraph)
    private final Map<Value, Set<AllocationSite>> pointsToCache = new HashMap<>();

    private Map<BasicBlock, Set<AllocationSite>> liveInGlobal;
    private Map<BasicBlock, Set<AllocationSite>> liveOutGlobal;

    /**
     * Cached access to the points-to set of a value.
     */
    private Set<AllocationSite> getPointsTo(Value v) {
        if (v == null) return Collections.emptySet();
        return pointsToCache.computeIfAbsent(v, key -> aliasResult.getPointsTo(key).getSites());
    }

    /**
     * Runs liveness analysis for all given sites simultaneously.
     * The result is stored in the liveInGlobal/liveOutGlobal fields.
     */
    public void computeLiveSets(Collection<AllocationSite> allSites) {
        // For O(1) site filtering
        Set<AllocationSite> allowed = new HashSet<>(allSites);

        // 1. Collect the use-set for each block
        Map<BasicBlock, Set<AllocationSite>> use = new HashMap<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                Set<AllocationSite> blockUse = new HashSet<>();
                for (Instruction inst : block.getInstructions()) {
                    blockUse.addAll(getAllocationSitesUsed(inst, allowed));
                }
                Terminator term = block.getTerminator();
                if (term != null) {
                    blockUse.addAll(getAllocationSitesUsed(term, allowed));
                }
                use.put(block, blockUse);
            }
        }

        // 2. Initialization
        liveInGlobal = new HashMap<>();
        liveOutGlobal = new HashMap<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                liveInGlobal.put(block, new HashSet<>());
                liveOutGlobal.put(block, new HashSet<>());
            }
        }

        // 3. Fixed point
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Function func : module.getFunctions()) {
                if (func.getEntryBlock() == null) continue;
                for (BasicBlock block : func.getBlocks()) {
                    // liveOut[B] = ∪ liveIn[succ]
                    Set<AllocationSite> newLiveOut = new HashSet<>();
                    for (BasicBlock succ : block.getSuccessors()) {
                        newLiveOut.addAll(liveInGlobal.get(succ));
                    }
                    if (!newLiveOut.equals(liveOutGlobal.get(block))) {
                        liveOutGlobal.put(block, newLiveOut);
                        changed = true;
                    }

                    // liveIn[B] = use[B] ∪ liveOut[B]
                    // def[B] is empty: an allocation site is never redefined
                    Set<AllocationSite> newLiveIn = new HashSet<>(use.get(block));
                    newLiveIn.addAll(liveOutGlobal.get(block));
                    if (!newLiveIn.equals(liveInGlobal.get(block))) {
                        liveInGlobal.put(block, newLiveIn);
                        changed = true;
                    }
                }
            }
        }
    }

    /**
     * Live sites at the entry of the block (after computeLiveSets).
     */
    public Set<AllocationSite> getLiveIn(BasicBlock block) {
        return liveInGlobal != null ? liveInGlobal.getOrDefault(block, Collections.emptySet())
                                    : Collections.emptySet();
    }

    /**
     * Live sites at the exit of the block (after computeLiveSets).
     */
    public Set<AllocationSite> getLiveOut(BasicBlock block) {
        return liveOutGlobal != null ? liveOutGlobal.getOrDefault(block, Collections.emptySet())
                                     : Collections.emptySet();
    }

    private Set<AllocationSite> getAllocationSitesUsed(Instruction inst, Set<AllocationSite> allowed) {
        Set<AllocationSite> result = new HashSet<>();
        for (Value operand : inst.getOperands()) {
            if (operand == null) continue;
            for (AllocationSite site : getPointsTo(operand)) {
                if (allowed.contains(site)) {
                    result.add(site);
                }
            }
        }
        return result;
    }

    private Set<AllocationSite> getAllocationSitesUsed(Terminator term, Set<AllocationSite> allowed) {
        Set<AllocationSite> result = new HashSet<>();
        Value used = null;
        if (term instanceof ReturnTerminator) {
            used = ((ReturnTerminator) term).getValue();
        } else if (term instanceof ThrowTerminator) {
            used = ((ThrowTerminator) term).getException();
        } else if (term instanceof CondBranchTerminator) {
            used = ((CondBranchTerminator) term).getCondition();
        } else if (term instanceof LookupSwitchTerminator) {
            used = ((LookupSwitchTerminator) term).getKey();
        } else if (term instanceof TableSwitchTerminator) {
            used = ((TableSwitchTerminator) term).getKey();
        }
        if (used != null) {
            for (AllocationSite site : getPointsTo(used)) {
                if (allowed.contains(site)) {
                    result.add(site);
                }
            }
        }
        return result;
    }
}

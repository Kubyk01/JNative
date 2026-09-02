package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.application.service.analyzer.ssa.DominatorTree;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class LifetimeAnalyzer {

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final EscapeAnalysisResult escapeResult;
    private final Map<String, FunctionSummary> summaries;

    public LifetimeAnalyzer(Module module, AliasAnalysisResult aliasResult,
                            EscapeAnalysisResult escapeResult,
                            Map<String, FunctionSummary> summaries) {
        this.module = module;
        this.aliasResult = aliasResult;
        this.escapeResult = escapeResult;
        this.summaries = summaries;
    }

    public LifetimeAnalysisResult analyze(Map<AllocationSite, Value> siteToValue) {
        LifetimeAnalysisResult result = new LifetimeAnalysisResult();

        // 1. Collect all allocation sites
        AllocationSiteCollector collector = new AllocationSiteCollector(module, aliasResult);
        List<AllocationSite> allSites = collector.collect();

        // 2. Cyclic reference detection
        CyclicReferenceDetector cyclicDetector = new CyclicReferenceDetector(aliasResult.getGraph(), allSites);
        Set<AllocationSite> cyclicSites = cyclicDetector.detect();
        result.getUnresolved().addAll(cyclicSites);

        // 3. Liveness analysis
        LivenessAnalyzer livenessAnalyzer = new LivenessAnalyzer(module, aliasResult, summaries);
        livenessAnalyzer.computeLiveSets(allSites);

        // 4. Build dominator trees and loop detection for each function
        Map<Function, DominatorTree> domTrees = new HashMap<>();
        Map<Function, Map<BasicBlock, Set<BasicBlock>>> loopHeaders = new HashMap<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() != null) {
                DominatorTree domTree = new DominatorTree(func);
                domTrees.put(func, domTree);
                // Detect natural loops
                loopHeaders.put(func, detectNaturalLoops(func, domTree));
            }
        }

        // 5. For each site, compute a single destruction point
        for (AllocationSite site : allSites) {
            if (result.getUnresolved().contains(site)) continue;

            EscapeStatus status = escapeResult.getSiteStatus(site);
            if (status == EscapeStatus.GLOBAL || status == EscapeStatus.THREAD || status == EscapeStatus.RETURN) {
                continue;  // do not insert a destructor; the object outlives the function
            }

            // Find the function where the site is created
            Function func = module.getFunction(site.getMethodName());
            if (func == null || func.getEntryBlock() == null) continue;

            DominatorTree domTree = domTrees.get(func);
            if (domTree == null) continue;

            Map<BasicBlock, Set<BasicBlock>> loops = loopHeaders.get(func);

            // Collect all blocks where the site dies (liveIn contains it, liveOut does not)
            Set<BasicBlock> deathBlocks = new HashSet<>();
            for (BasicBlock block : func.getBlocks()) {
                Set<AllocationSite> liveIn = livenessAnalyzer.getLiveIn(block);
                Set<AllocationSite> liveOut = livenessAnalyzer.getLiveOut(block);

                if (liveIn.contains(site) && !liveOut.contains(site)) {
                    // Check whether the object is returned from this block
                    Terminator term = block.getTerminator();
                    boolean isReturned = false;
                    if (term instanceof ReturnTerminator) {
                        Value retVal = ((ReturnTerminator) term).getValue();
                        if (retVal != null && aliasResult.getPointsTo(retVal).getSites().contains(site)) {
                            isReturned = true;
                        }
                    }
                    if (!isReturned) {
                        deathBlocks.add(block);
                    }
                }
            }

            // Filter deathBlocks that are inside loops where the object is live on loop entry
            Set<BasicBlock> filteredDeathBlocks = filterDeathBlocksInLoops(deathBlocks, site, loops, livenessAnalyzer);

            if (filteredDeathBlocks.isEmpty()) {
                // The object does not die in any block - it may live until the end of the function.
                // In that case we must destroy it before every return,
                // but to avoid multiple points we compute the LCA of all returns.
                // If an LCA exists, insert there; otherwise mark as unresolved.
                List<BasicBlock> returnBlocks = new ArrayList<>();
                for (BasicBlock block : func.getBlocks()) {
                    if (block.getTerminator() instanceof ReturnTerminator) {
                        returnBlocks.add(block);
                    }
                }
                if (!returnBlocks.isEmpty()) {
                    BasicBlock lca = domTree.getLCA(returnBlocks);
                    if (lca != null) {
                        Value objRef = siteToValue.get(site);
                        if (objRef != null) {
                            DestructionPoint dp = new DestructionPoint(lca, null, objRef);
                            result.getDestructionPoints()
                                    .computeIfAbsent(site, k -> new HashSet<>())
                                    .add(dp);
                        }
                    } else {
                        result.getUnresolved().add(site);
                    }
                } else {
                    // The function never returns (e.g., an infinite loop)
                    // No destruction required.
                }
                continue;
            }

            // Compute the LCA of filtered death blocks
            BasicBlock lca = domTree.getLCA(new ArrayList<>(filteredDeathBlocks));
            if (lca == null) {
                // No common dominator - unresolved
                result.getUnresolved().add(site);
                continue;
            }

            // Insert the destructor at the end of the LCA block
            Value objRef = siteToValue.get(site);
            if (objRef != null) {
                DestructionPoint dp = new DestructionPoint(lca, null, objRef);
                result.getDestructionPoints()
                        .computeIfAbsent(site, k -> new HashSet<>())
                        .add(dp);
            }
        }

        return result;
    }

    /**
     * Detects natural loops in a function using dominator information.
     * Returns a map from loop header to the set of blocks in the loop body (including the header).
     */
    private Map<BasicBlock, Set<BasicBlock>> detectNaturalLoops(Function func, DominatorTree domTree) {
        Map<BasicBlock, Set<BasicBlock>> loops = new HashMap<>();
        for (BasicBlock block : func.getBlocks()) {
            for (BasicBlock pred : block.getPredecessors()) {
                // Check if edge pred -> block is a back edge: block dominates pred
                if (domTree.dominates(block, pred)) {
                    // block is a loop header, pred is a latch
                    Set<BasicBlock> body = new HashSet<>();
                    findLoopBody(block, pred, body);
                    loops.put(block, body);
                }
            }
        }
        return loops;
    }

    /**
     * Computes the set of blocks that belong to the loop with given header and latch.
     */
    private void findLoopBody(BasicBlock header, BasicBlock latch, Set<BasicBlock> body) {
        Deque<BasicBlock> stack = new ArrayDeque<>();
        stack.push(latch);
        body.add(header);
        while (!stack.isEmpty()) {
            BasicBlock block = stack.pop();
            if (!body.add(block)) continue;
            for (BasicBlock pred : block.getPredecessors()) {
                if (!body.contains(pred)) {
                    stack.push(pred);
                }
            }
        }
    }

    /**
     * Filters deathBlocks: if a deathBlock is inside a loop and the allocation site is live
     * on entry to the loop header, then that deathBlock is ignored because the object
     * may survive across loop iterations.
     */
    private Set<BasicBlock> filterDeathBlocksInLoops(Set<BasicBlock> deathBlocks,
                                                     AllocationSite site,
                                                     Map<BasicBlock, Set<BasicBlock>> loops,
                                                     LivenessAnalyzer livenessAnalyzer) {
        Set<BasicBlock> result = new HashSet<>(deathBlocks);

        for (Map.Entry<BasicBlock, Set<BasicBlock>> entry : loops.entrySet()) {
            BasicBlock header = entry.getKey();
            Set<BasicBlock> body = entry.getValue();

            // Check if the site is live on entry to the loop header
            boolean liveOnEntry = livenessAnalyzer.getLiveIn(header).contains(site);
            if (liveOnEntry) {
                // Remove all deathBlocks that are inside this loop body
                for (BasicBlock block : body) {
                    result.remove(block);
                }
            }
        }

        return result;
    }
}

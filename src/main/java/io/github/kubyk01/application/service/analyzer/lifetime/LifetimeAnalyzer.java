package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.application.service.analyzer.ssa.DominatorTree;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.ir.BasicBlock;
import io.github.kubyk01.domain.analyzer.ir.Function;
import io.github.kubyk01.domain.analyzer.ir.Module;
import io.github.kubyk01.domain.analyzer.ir.ReturnTerminator;
import io.github.kubyk01.domain.analyzer.ir.Terminator;
import io.github.kubyk01.domain.analyzer.ir.Value;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
@RequiredArgsConstructor
public class LifetimeAnalyzer {

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final EscapeAnalysisResult escapeResult;

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
        LivenessAnalyzer livenessAnalyzer = new LivenessAnalyzer(module, aliasResult);
        livenessAnalyzer.computeLiveSets(allSites);

        // 4. Build dominator trees for each function
        Map<Function, DominatorTree> domTrees = new HashMap<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() != null) {
                domTrees.put(func, new DominatorTree(func));
            }
        }

        // 5. For each site, compute a single destruction point
        for (AllocationSite site : allSites) {
            if (result.getUnresolved().contains(site)) continue;

            EscapeStatus status = escapeResult.getSiteStatus(site);
            if (status == EscapeStatus.GLOBAL || status == EscapeStatus.THREAD) continue;

            // Find the function where the site is created
            Function func = module.getFunction(site.getMethodName());
            if (func == null || func.getEntryBlock() == null) continue;

            DominatorTree domTree = domTrees.get(func);
            if (domTree == null) continue;

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

            if (deathBlocks.isEmpty()) {
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

            // Compute the LCA of all death blocks
            BasicBlock lca = domTree.getLCA(new ArrayList<>(deathBlocks));
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
}

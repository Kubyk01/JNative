package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.github.kubyk01.util.LlvmUtil.isAllocation;

@RequiredArgsConstructor
public class AllocationSiteCollector {

    private final Module module;
    private final AliasAnalysisResult aliasResult;

    /**
     * Collects all allocation sites from all functions of the module.
     */
    public List<AllocationSite> collect() {
        Set<AllocationSite> sites = new HashSet<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (isAllocation(inst.getOpcode()) && inst.getResult() != null) {
                        sites.addAll(aliasResult.getPointsTo(inst.getResult()).getSites());
                    }
                }
            }
        }
        return new ArrayList<>(sites);
    }

}

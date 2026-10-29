package io.github.kubyk01.domain.analyzer.lifetime;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.ir.Instruction;
import lombok.Data;

import java.util.*;

@Data
public class LifetimeAnalysisResult {
    private final Map<AllocationSite, Set<DestructionPoint>> destructionPoints = new HashMap<>();
    private final Set<AllocationSite> unresolved = new HashSet<>();

    private Map<Instruction, Boolean> fieldOverwriteLivenessGates = Collections.emptyMap();

    public boolean isFieldOverwriteLivenessGatePassed(Instruction pf) {
        Boolean gate = fieldOverwriteLivenessGates.get(pf);
        return gate != null && gate;
    }
}
package io.github.kubyk01.domain.analyzer.lifetime;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import lombok.Data;

import java.util.*;

@Data
public class LifetimeAnalysisResult {
    private final Map<AllocationSite, Set<DestructionPoint>> destructionPoints = new HashMap<>();
    private final Set<AllocationSite> unresolved = new HashSet<>(); // cyclic or non-deterministic
}

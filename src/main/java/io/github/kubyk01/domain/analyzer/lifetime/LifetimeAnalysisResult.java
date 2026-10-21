package io.github.kubyk01.domain.analyzer.lifetime;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.ir.BasicBlock;
import lombok.Data;
import lombok.Setter;

import java.util.*;

@Data
public class LifetimeAnalysisResult {
    private final Map<AllocationSite, Set<DestructionPoint>> destructionPoints = new HashMap<>();
    private final Set<AllocationSite> unresolved = new HashSet<>();

    /**
     * Live-out allocation-site set per basic block, computed by
     * LivenessAnalyzer over the whole-module points-to graph.
     *
     * <p>Consumed by DestructorInserter.instrumentFieldOverwrites() as
     * the safety gate for field-overwrite releases. An entry exists for
     * every basic block of every live function; a terminal block's
     * entry is an empty set (that is a legitimate state, not "no
     * information"). A block whose entry is absent is treated as
     * "unknown" and causes the instrumentation pass to skip that
     * block — the conservative direction, because emitting a release
     * without liveness information is exactly the HashMap.resize
     * use-after-free the gate exists to prevent.</p>
     */
    @Setter
    private Map<BasicBlock, Set<AllocationSite>> liveOut = new HashMap<>(); // cyclic or non-deterministic
}

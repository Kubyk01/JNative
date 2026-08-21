package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToGraph;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import lombok.RequiredArgsConstructor;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects cyclic references in the object graph using field-sensitive points-to information.
 * If an object may have a field (directly or indirectly) pointing to itself, it is marked as cyclic.
 */
@RequiredArgsConstructor
public class CyclicReferenceDetector {

    private final PointsToGraph pointsToGraph;
    private final List<AllocationSite> allSites;

    public Set<AllocationSite> detect() {
        Set<AllocationSite> cyclic = new HashSet<>();
        for (AllocationSite site : allSites) {
            if (reaches(site, site, new HashSet<>())) {
                cyclic.add(site);
            }
        }
        return cyclic;
    }

    /**
     * Checks whether current can reach target through a chain of fields.
     * visited holds already explored nodes to avoid re-traversal and infinite loops.
     */
    private boolean reaches(AllocationSite current, AllocationSite target, Set<AllocationSite> visited) {
        if (!visited.add(current)) return false;

        Map<String, PointsToSet> fieldMap = pointsToGraph.getFieldPointsToMap(current);
        if (fieldMap == null) return false;

        for (PointsToSet pts : fieldMap.values()) {
            for (AllocationSite next : pts.getSites()) {
                if (next.equals(target)) return true;
                if (reaches(next, target, visited)) return true;
            }
        }
        return false;
    }
}

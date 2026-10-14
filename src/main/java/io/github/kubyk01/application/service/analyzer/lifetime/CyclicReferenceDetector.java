package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToGraph;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import lombok.RequiredArgsConstructor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects cyclic references in the field-sensitive points-to graph using
 * Tarjan's strongly-connected-components algorithm.
 *
 * <p>The previous implementation launched a separate DFS from every allocation
 * site, which is O(N*M) for N ~ tens of thousands of sites on a full JDK
 * closure. That was the dominant wall-clock phase after escape analysis and it
 * serialised the whole lifetime stage (the parallel phases waited for it on the
 * CountDownLatch inside {@code LlvmUtil.awaitMono}).</p>
 *
 * <p>Tarjan computes all SCCs in a single O(V+E) pass. A site counts as cyclic
 * when it lies in an SCC of size &gt; 1, or when it has a self-loop (an SCC of
 * size 1 with an edge to itself).</p>
 */
@RequiredArgsConstructor
public class CyclicReferenceDetector {

    private final PointsToGraph pointsToGraph;
    private final List<AllocationSite> allSites;

    public Set<AllocationSite> detect() {
        Map<AllocationSite, Set<AllocationSite>> successors = buildSuccessors();
        return tarjanCyclicSites(successors);
    }

    /**
     * Builds the adjacency list: site -> set of sites reachable through any
     * field in a single step. UNKNOWN is excluded: it represents an "external
     * source", an edge into it cannot close a cycle because UNKNOWN has no
     * outgoing edges into user objects.
     */
    private Map<AllocationSite, Set<AllocationSite>> buildSuccessors() {
        Map<AllocationSite, Set<AllocationSite>> successors =
            new HashMap<>(Math.max(16, allSites.size() * 2));

        for (AllocationSite site : allSites) {
            Map<String, PointsToSet> fieldMap =
                pointsToGraph.getFieldPointsToMap(site);
            if (fieldMap == null || fieldMap.isEmpty()) {
                successors.put(site, Collections.emptySet());
                continue;
            }
            Set<AllocationSite> succ = new LinkedHashSet<>();
            for (PointsToSet pts : fieldMap.values()) {
                for (AllocationSite next : pts.getSites()) {
                    if (next == null) continue;
                    if (next.equals(AllocationSite.UNKNOWN)) continue;
                    succ.add(next);
                }
            }
            successors.put(site, succ.isEmpty()
                ? Collections.emptySet()
                : succ);
        }
        return successors;
    }

    /**
     * Iterative Tarjan. The recursion is emulated with an explicit frame stack,
     * because on a full JDK the field points-to graph can contain a path tens
     * of thousands of edges deep and the recursive variant would overflow the
     * JVM stack.
     */
    private Set<AllocationSite> tarjanCyclicSites(
            Map<AllocationSite, Set<AllocationSite>> successors) {

        Map<AllocationSite, Integer> index   = new HashMap<>(allSites.size() * 2);
        Map<AllocationSite, Integer> lowlink = new HashMap<>(allSites.size() * 2);
        Deque<AllocationSite> sccStack = new ArrayDeque<>();
        Set<AllocationSite>   onStack  = new HashSet<>();
        Set<AllocationSite>   cyclic   = new HashSet<>();
        int[] counter = {0};

        for (AllocationSite start : allSites) {
            if (index.containsKey(start)) continue;

            Deque<Frame> dfsStack = new ArrayDeque<>();
            dfsStack.push(new Frame(start));

            while (!dfsStack.isEmpty()) {
                Frame frame = dfsStack.peek();
                AllocationSite v = frame.node;

                if (frame.iter == null) {
                    index.put(v, counter[0]);
                    lowlink.put(v, counter[0]);
                    counter[0]++;
                    sccStack.push(v);
                    onStack.add(v);
                    frame.iter = successors
                        .getOrDefault(v, Collections.emptySet())
                        .iterator();
                }

                boolean descended = false;
                while (frame.iter.hasNext()) {
                    AllocationSite w = frame.iter.next();
                    if (!index.containsKey(w)) {
                        dfsStack.push(new Frame(w));
                        descended = true;
                        break;
                    }
                    if (onStack.contains(w)) {
                        int newLow = Math.min(lowlink.get(v), index.get(w));
                        lowlink.put(v, newLow);
                    }
                }
                if (descended) continue;

                dfsStack.pop();
                if (!dfsStack.isEmpty()) {
                    AllocationSite parent = dfsStack.peek().node;
                    int newLow = Math.min(
                        lowlink.get(parent), lowlink.get(v));
                    lowlink.put(parent, newLow);
                }

                if (lowlink.get(v).equals(index.get(v))) {
                    List<AllocationSite> scc = new ArrayList<>();
                    AllocationSite w;
                    do {
                        w = sccStack.pop();
                        onStack.remove(w);
                        scc.add(w);
                    } while (!w.equals(v));

                    if (scc.size() > 1) {
                        cyclic.addAll(scc);
                    } else {
                        AllocationSite single = scc.getFirst();
                        Set<AllocationSite> succ = successors.get(single);
                        if (succ != null && succ.contains(single)) {
                            cyclic.add(single);
                        }
                    }
                }
            }
        }

        return cyclic;
    }

    private static final class Frame {
        final AllocationSite node;
        Iterator<AllocationSite> iter;

        Frame(AllocationSite node) {
            this.node = node;
        }
    }
}

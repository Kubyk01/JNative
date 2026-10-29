package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToGraph;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.IdentityHashMap;

/**
 * Detects cyclic references in the field-sensitive points-to graph using
 * Tarjan's strongly-connected-components algorithm.
 *
 * <h2>Implementation notes</h2>
 *
 * The pass runs on integer site ids throughout. Every node's Tarjan
 * state (index, lowlink, on-stack flag, SCC stack) lives in primitive
 * arrays sized to the site count; the DFS stack itself is an explicit
 * array-based stack of frames, each holding a {@code int[]} of
 * successor ids built lazily on first visit and released when the frame
 * is popped. As a result:
 *
 * <ul>
 *   <li>No {@code AllocationSite.hashCode()} is called per edge — the
 *       one-time {@code IdentityHashMap} conversion of the input sites
 *       to ids covers every subsequent lookup, and every site coming
 *       out of a {@link PointsToSet} is the same object that
 *       {@code AllocationSiteCollector} put into {@code allSites}.</li>
 *   <li>No per-node {@code HashSet} or {@code LinkedHashMap} is
 *       allocated.</li>
 *   <li>Per-edge cost is a pointer-free array read plus two int
 *       comparisons, versus the previous implementation's
 *       {@code HashMap.containsKey} + {@code HashSet.contains}.</li>
 * </ul>
 *
 * <p>Peak extra memory is bounded by the DFS depth times the average
 * out-degree, which for the JDK points-to graphs observed in practice
 * is a few megabytes — versus the hundreds of megabytes that the
 * previous {@code LinkedHashSet<AllocationSite>}-per-node
 * representation consumed on the same input.</p>
 */
@RequiredArgsConstructor
public class CyclicReferenceDetector {

    private static final int[] EMPTY_INT = new int[0];
    private static final int UNVISITED = -1;

    private final PointsToGraph pointsToGraph;
    private final List<AllocationSite> allSites;

    public Set<AllocationSite> detect() {
        return tarjanCyclicSites();
    }

    private Set<AllocationSite> tarjanCyclicSites() {
        final int n = allSites.size();
        if (n == 0) return Collections.emptySet();

        // ---- Site identity → dense id --------------------------------
        //
        // IdentityHashMap is valid here because every AllocationSite
        // that appears inside a PointsToSet is the exact object created
        // once by AllocationSite.fromInstruction during points-to
        // analysis, and allSites is collected from those sets.
        IdentityHashMap<AllocationSite, Integer> idOf =
            new IdentityHashMap<>(n * 2);
        for (int i = 0; i < n; i++) idOf.put(allSites.get(i), i);

        // ---- Per-node Tarjan state (primitive arrays) ----------------
        final int[]    index   = new int[n];
        final int[]    lowlink = new int[n];
        final boolean[] onStack = new boolean[n];
        Arrays.fill(index, UNVISITED);

        final int[] sccStack = new int[n];
        int sccTop = 0;

        // ---- Explicit DFS stack -------------------------------------
        // dfsNode[k] is the node at depth k.
        // dfsAdj[k] is its successor id array (built on first visit).
        // dfsAdjPos[k] is the position within that array.
        final int[]   dfsNode   = new int[n];
        final int[][] dfsAdj    = new int[n][];
        final int[]   dfsAdjPos = new int[n];
        int dfsTop;

        final boolean[] cyclic = new boolean[n];
        final int[] counter = {0};

        for (int start = 0; start < n; start++) {
            if (index[start] != UNVISITED) continue;

            dfsTop = 0;
            dfsNode[dfsTop] = start;
            dfsAdj[dfsTop] = null;
            dfsAdjPos[dfsTop] = 0;
            dfsTop++;

            while (dfsTop > 0) {
                final int v = dfsNode[dfsTop - 1];
                int[] succ = dfsAdj[dfsTop - 1];

                if (succ == null) {
                    // First visit: assign pre-order index / lowlink, push
                    // onto the SCC stack, and materialise successors.
                    index[v] = counter[0];
                    lowlink[v] = counter[0];
                    counter[0]++;
                    sccStack[sccTop++] = v;
                    onStack[v] = true;
                    succ = buildSuccessorIds(v, idOf);
                    dfsAdj[dfsTop - 1] = succ;
                }

                int pos = dfsAdjPos[dfsTop - 1];
                boolean descended = false;
                while (pos < succ.length) {
                    int w = succ[pos++];
                    if (index[w] == UNVISITED) {
                        dfsAdjPos[dfsTop - 1] = pos;
                        dfsNode[dfsTop] = w;
                        dfsAdj[dfsTop] = null;
                        dfsAdjPos[dfsTop] = 0;
                        dfsTop++;
                        descended = true;
                        break;
                    }
                    if (onStack[w] && index[w] < lowlink[v]) {
                        lowlink[v] = index[w];
                    }
                }
                if (descended) continue;

                // All successors visited: finish v.
                dfsAdjPos[dfsTop - 1] = pos;
                dfsAdj[dfsTop - 1] = null;   // release memory
                dfsTop--;

                if (dfsTop > 0) {
                    int parent = dfsNode[dfsTop - 1];
                    if (lowlink[v] < lowlink[parent]) lowlink[parent] = lowlink[v];
                }

                if (lowlink[v] != index[v]) continue;

                // Pop the SCC.
                int sccStart = sccTop;
                int w;
                do {
                    w = sccStack[--sccTop];
                    onStack[w] = false;
                } while (w != v);
                int sccSize = sccStart - sccTop;

                if (sccSize > 1) {
                    for (int i = sccTop; i < sccStart; i++) cyclic[sccStack[i]] = true;
                } else if (hasSelfLoop(v, idOf)) {
                    cyclic[v] = true;
                }
            }
        }

        if (cyclic.length == 0) return Collections.emptySet();
        Set<AllocationSite> result = new HashSet<>();
        for (int i = 0; i < n; i++) {
            if (cyclic[i]) result.add(allSites.get(i));
        }
        return result;
    }

    // ==================================================================
    //  Adjacency construction
    // ==================================================================

    private int[] buildSuccessorIds(int v, IdentityHashMap<AllocationSite, Integer> idOf) {
        AllocationSite site = allSites.get(v);
        Map<String, PointsToSet> fieldMap = pointsToGraph.getFieldPointsToMap(site);
        if (fieldMap == null || fieldMap.isEmpty()) return EMPTY_INT;

        // Grow a primitive int[] on demand. Typical out-degrees are
        // single digits, so the fast path allocates one 8-element
        // buffer per node.
        int[] buf = new int[8];
        int len = 0;
        for (PointsToSet pts : fieldMap.values()) {
            if (pts.isEmpty()) continue;
            for (AllocationSite succ : pts.getSites()) {
                if (succ == null || succ == AllocationSite.UNKNOWN) continue;
                Integer sid = idOf.get(succ);
                if (sid == null) continue;
                if (len == buf.length) buf = Arrays.copyOf(buf, len * 2);
                buf[len++] = sid;
            }
        }
        if (len == 0) return EMPTY_INT;
        return len == buf.length ? buf : Arrays.copyOf(buf, len);
    }

    private boolean hasSelfLoop(int v, IdentityHashMap<AllocationSite, Integer> idOf) {
        AllocationSite site = allSites.get(v);
        Map<String, PointsToSet> fieldMap = pointsToGraph.getFieldPointsToMap(site);
        if (fieldMap == null || fieldMap.isEmpty()) return false;
        for (PointsToSet pts : fieldMap.values()) {
            if (pts.isEmpty()) continue;
            for (AllocationSite succ : pts.getSites()) {
                if (succ == null || succ == AllocationSite.UNKNOWN) continue;
                Integer sid = idOf.get(succ);
                if (sid != null && sid == v) return true;
            }
        }
        return false;
    }
}

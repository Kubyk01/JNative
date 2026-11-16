package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FieldCell;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToGraph;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds cyclic sites in the field points-to graph via iterative Tarjan SCC.
 *
 * <p>Successors are streamed straight from the graph's per-site field cells
 * (never materialised as an adjacency list: on a full JDK closure the graph
 * has hundreds of millions of edges). All per-node state lives in arrays
 * indexed by the dense {@link AllocationSite#getId()}, so the walk does no
 * hashing or boxing. Recursion is emulated with an explicit frame stack
 * (cell index + position inside that cell's id array) so deep paths do not
 * overflow. {@link AllocationSite#UNKNOWN} has no outgoing edges and is
 * skipped.</p>
 */
@RequiredArgsConstructor
public class CyclicReferenceDetector {

    private final PointsToGraph pointsToGraph;
    private final List<AllocationSite> allSites;

    public Set<AllocationSite> detect() {
        return tarjanCyclicSites();
    }

    private Set<AllocationSite> tarjanCyclicSites() {
        int n = AllocationSite.count();
        int unknown = AllocationSite.UNKNOWN.getId();
        int[] index = new int[n];
        Arrays.fill(index, -1);
        int[] low = new int[n];
        boolean[] onStack = new boolean[n];
        int[] sccStack = new int[n];
        int[] fNode = new int[n];
        int[] fCell = new int[n];
        int[] fPos = new int[n];
        Set<AllocationSite> cyclic = new HashSet<>();
        int counter = 0, ssp = 0;

        for (AllocationSite startSite : allSites) {
            int start = startSite.getId();
            if (index[start] >= 0) continue;
            int fsp = 0;
            index[start] = low[start] = counter++;
            sccStack[ssp++] = start;
            onStack[start] = true;
            fNode[fsp] = start; fCell[fsp] = 0; fPos[fsp] = 0; fsp++;

            while (fsp > 0) {
                int f = fsp - 1;
                int v = fNode[f];
                FieldCell[] cells = pointsToGraph.fieldCells(v);
                boolean descended = false;
                while (fCell[f] < cells.length) {
                    int[] ids = cells[fCell[f]].get().rawIds();
                    if (fPos[f] >= ids.length) { fCell[f]++; fPos[f] = 0; continue; }
                    int w = ids[fPos[f]++];
                    if (w == unknown) continue;
                    if (index[w] < 0) {
                        index[w] = low[w] = counter++;
                        sccStack[ssp++] = w;
                        onStack[w] = true;
                        fNode[fsp] = w; fCell[fsp] = 0; fPos[fsp] = 0; fsp++;
                        descended = true;
                        break;
                    }
                    if (onStack[w]) low[v] = Math.min(low[v], index[w]);
                }
                if (descended) continue;

                fsp--;
                if (fsp > 0) {
                    int parent = fNode[fsp - 1];
                    low[parent] = Math.min(low[parent], low[v]);
                }
                if (low[v] != index[v]) continue;

                int size = 0, w;
                do {
                    w = sccStack[--ssp];
                    onStack[w] = false;
                    size++;
                } while (w != v);
                if (size > 1) {
                    for (int k = ssp; k < ssp + size; k++) cyclic.add(AllocationSite.byId(sccStack[k]));
                } else if (hasSelfLoop(v)) {
                    cyclic.add(AllocationSite.byId(v));
                }
            }
        }
        return cyclic;
    }

    /** True iff any field (or array element, field "[]") of the site points back at it. */
    private boolean hasSelfLoop(int site) {
        for (FieldCell c : pointsToGraph.fieldCells(site)) {
            if (c.get().containsId(site)) return true;
        }
        return false;
    }
}

package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FieldCell;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToGraph;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.util.LlvmUtil;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds cyclic sites in the field points-to graph via iterative Tarjan SCC.
 *
 * <p>All per-node state lives in arrays
 * indexed by the dense {@link AllocationSite#getId()}, so the walk does no
 * hashing or boxing. Recursion is emulated with an explicit frame stack
 * so deep paths do not
 * overflow. {@link AllocationSite#UNKNOWN} has no outgoing edges and is
 * skipped.</p>
 */
@RequiredArgsConstructor
public class CyclicReferenceDetector {

    private final PointsToGraph pointsToGraph;
    private final List<AllocationSite> allSites;
    @Setter
    private Scheduler scheduler;

    private static final int[] NO_SUCCS = new int[0];
    private static final ThreadLocal<BitSet> SEEN = ThreadLocal.withInitial(BitSet::new);

    public Set<AllocationSite> detect() {
        return tarjanCyclicSites();
    }

    private Set<AllocationSite> tarjanCyclicSites() {
        int n = AllocationSite.count();
        int unknown = AllocationSite.UNKNOWN.getId();
        int[][] succs = new int[n][];
        boolean[] selfLoop = new boolean[n];
        int chunks = Math.max(1, Math.min(n, Runtime.getRuntime().availableProcessors() * 64));
        List<int[]> ranges = new ArrayList<>(chunks);
        for (int c = 0; c < chunks; c++) {
            ranges.add(new int[] { (int) ((long) n * c / chunks), (int) ((long) n * (c + 1) / chunks) });
        }
        LlvmUtil.parallelMapOrdered(ranges, scheduler, r -> {
            BitSet seen = SEEN.get();
            int[] buf = new int[16];
            for (int v = r[0]; v < r[1]; v++) {
                int k = 0;
                boolean self = false;
                for (FieldCell cell : pointsToGraph.fieldCells(v)) {
                    PointsToSet ids = cell.get();
                    for (long c = ids.cursor(); ids.has(c); c = ids.next(c)) {
                        int w = ids.id(c);
                        if (w == v) self = true;
                        if (w == unknown || seen.get(w)) continue;
                        seen.set(w);
                        if (k == buf.length) buf = Arrays.copyOf(buf, k << 1);
                        buf[k++] = w;
                    }
                }
                for (int i = 0; i < k; i++) seen.clear(buf[i]);
                succs[v] = k == 0 ? NO_SUCCS : Arrays.copyOf(buf, k);
                selfLoop[v] = self;
            }
            return Boolean.TRUE;
        });

        int[] index = new int[n];
        Arrays.fill(index, -1);
        int[] low = new int[n];
        boolean[] onStack = new boolean[n];
        int[] sccStack = new int[n];
        int[] fNode = new int[n];
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
            fNode[fsp] = start;
            fPos[fsp] = 0;
            fsp++;

            while (fsp > 0) {
                int f = fsp - 1;
                int v = fNode[f];
                int[] out = succs[v];
                boolean descended = false;
                while (fPos[f] < out.length) {
                    int w = out[fPos[f]++];
                    if (index[w] < 0) {
                        index[w] = low[w] = counter++;
                        sccStack[ssp++] = w;
                        onStack[w] = true;
                        fNode[fsp] = w;
                        fPos[fsp] = 0;
                        fsp++;
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
                } else if (selfLoop[v]) {
                    cyclic.add(AllocationSite.byId(v));
                }
            }
        }
        return cyclic;
    }
}

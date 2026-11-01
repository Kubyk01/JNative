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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Finds cyclic sites in the field points-to graph via iterative Tarjan SCC.
 *
 * The previous revision built a full Map<Site, Set<Site>> adjacency list
 * up front; on a full JDK closure the graph has hundreds of millions of
 * edges and materialising them caused OOM. This revision streams
 * successors from PointsToGraph.getFieldPointsToMap directly via
 * SuccessorIterator, and replaces the self-loop membership test with
 * hasSelfLoop. The pass is O(V) in memory; the set of cyclic sites is
 * identical to the adjacency-list version. Recursion is emulated with an
 * explicit frame stack so deep hierarchies do not overflow.
 */
@RequiredArgsConstructor
public class CyclicReferenceDetector {

    private final PointsToGraph pointsToGraph;
    private final List<AllocationSite> allSites;

    public Set<AllocationSite> detect() {
        return tarjanCyclicSites();
    }

    /**
     * Iterative Tarjan over the field-sensitivity graph, computing SCCs
     * without materialising the adjacency list.
     *
     * <p>The recursion is emulated with an explicit frame stack, because on
     * a full JDK the field points-to graph can contain a path tens of
     * thousands of edges deep and the recursive variant would overflow the
     * JVM stack.</p>
     *
     * <p>Each frame owns its own {@link SuccessorIterator} over the current
     * node's field points-to sets, so the successor sequence is generated
     * on demand and never stored. The iterator for a frame is created
     * exactly once, on the frame's first visit, and advanced exactly once
     * per DFS step thereafter.</p>
     */
    private Set<AllocationSite> tarjanCyclicSites() {
        final int initialCapacity = Math.max(16, allSites.size());

        Map<AllocationSite, Integer> index   = new HashMap<>(initialCapacity);
        Map<AllocationSite, Integer> lowlink = new HashMap<>(initialCapacity);
        Deque<AllocationSite> sccStack = new ArrayDeque<>();
        Set<AllocationSite>   onStack  = new HashSet<>(initialCapacity);
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
                    // First visit: assign the pre-order index and lowlink,
                    // push onto the SCC stack, and open the stream of
                    // successors.
                    index.put(v, counter[0]);
                    lowlink.put(v, counter[0]);
                    counter[0]++;
                    sccStack.push(v);
                    onStack.add(v);
                    frame.iter = new SuccessorIterator(
                        pointsToGraph.getFieldPointsToMap(v));
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
                if (descended) {
                    continue;
                }

                // All successors visited: finish v. Propagate its lowlink
                // up to the parent frame, then check whether v is the root
                // of a new SCC.
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
                        if (hasSelfLoop(single)) {
                            cyclic.add(single);
                        }
                    }
                }
            }
        }

        return cyclic;
    }

    /**
     * Returns true iff {@code site} is a successor of itself in the
     * field-sensitivity graph.
     *
     * <p>This is the streaming replacement for the previous
     * {@code successors.get(single).contains(single)} check. A self-loop
     * occurs when any field of {@code site} (or any array element of
     * {@code site}, which the graph records under the {@code "[]"} field
     * key) points back at {@code site}. {@link AllocationSite#UNKNOWN} is
     * deliberately ignored: it has no outgoing edges, so it can never
     * close a cycle.</p>
     */
    private boolean hasSelfLoop(AllocationSite site) {
        Map<String, PointsToSet> fieldMap =
            pointsToGraph.getFieldPointsToMap(site);
        if (fieldMap == null || fieldMap.isEmpty()) {
            return false;
        }
        for (PointsToSet pts : fieldMap.values()) {
            if (pts.getSites().contains(site)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One level of the explicit DFS stack used in place of the call stack.
     *
     * <p>The {@code iter} field is {@code null} until the frame is first
     * visited; on that visit it is initialised with a
     * {@link SuccessorIterator} over the node's field points-to map, so
     * the successor sequence is generated lazily and never stored.</p>
     */
    private static final class Frame {
        final AllocationSite node;
        Iterator<AllocationSite> iter;

        Frame(AllocationSite node) {
            this.node = node;
        }
    }

    /**
     * Streams a node's successors directly from its field points-to map,
     * filtering out {@link AllocationSite#UNKNOWN} (which has no outgoing
     * edges and therefore cannot close a cycle).
     *
     * <p>The iteration order is the iteration order of
     * {@link PointsToGraph#getFieldPointsToMap(AllocationSite)}'s inner
     * maps — that is, the insertion order into each {@code PointsToSet}.
     * That order is weakly consistent under concurrent modification
     * (the underlying {@code PointsToSet}s are
     * {@code ConcurrentHashMap}-backed, see {@link PointsToSet#shared()}),
     * which is safe here: by the time {@code CyclicReferenceDetector}
     * runs, the points-to fixed point has already converged and no further
     * writes occur. The iterator is therefore effectively read-only and
     * its order is deterministic across runs.</p>
     *
     * <p>Duplicates in the yielded sequence are possible when two fields
     * of the same site point at the same target. They are harmless for
     * Tarjan: the DFS re-encounters a visited node and skips it in the
     * {@code index.containsKey(w)} branch. Deduplicating was the reason
     * the previous implementation used a {@code LinkedHashSet} per site;
     * that is exactly the allocation this class now avoids.</p>
     */
    private static final class SuccessorIterator
        implements Iterator<AllocationSite> {

        private final Iterator<PointsToSet> fieldValues;
        private Iterator<AllocationSite> current;
        private AllocationSite next;

        SuccessorIterator(Map<String, PointsToSet> fieldMap) {
            this.fieldValues = (fieldMap == null || fieldMap.isEmpty())
                ? Collections.emptyIterator()
                : fieldMap.values().iterator();
        }

        @Override
        public boolean hasNext() {
            if (next != null) {
                return true;
            }
            while (true) {
                if (current != null && current.hasNext()) {
                    AllocationSite s = current.next();
                    if (s != null && !s.equals(AllocationSite.UNKNOWN)) {
                        next = s;
                        return true;
                    }
                }
                if (fieldValues.hasNext()) {
                    current = fieldValues.next().getSites().iterator();
                } else {
                    return false;
                }
            }
        }

        @Override
        public AllocationSite next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            AllocationSite s = next;
            next = null;
            return s;
        }
    }
}
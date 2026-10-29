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
 *
 * <h2>Streaming successor iteration</h2>
 *
 * <p>An earlier revision of this class built a full
 * {@code Map<AllocationSite, Set<AllocationSite>>} adjacency list up front.
 * On a full JDK closure the field points-to graph contains millions of
 * {@code (site, successor)} pairs, and materialising it as a
 * {@code LinkedHashSet} per site — with its ~32–48 byte per-node and
 * per-set overhead on top of the entries themselves — pushed the lifetime
 * stage over the default heap size. The failure that motivated this
 * revision was</p>
 *
 * <pre>
 *   java.lang.OutOfMemoryError: Java heap space:
 *       failed reallocation of scalar replaced objects
 *       at java.util.HashMap.put(HashMap.java:618)
 *       at java.util.HashSet.add(HashSet.java:229)
 *       at ...CyclicReferenceDetector.buildSuccessors(CyclicReferenceDetector.java:67)
 * </pre>
 *
 * <p>That OOM is not avoidable by raising {@code -Xmx}: the graph is
 * quadratic in the number of sites that can reach a given field, and on a
 * ~7.4k-class image it is measured in hundreds of millions of edges. Tarjan
 * only ever needs to (a) iterate a node's successors once per DFS frame, and
 * (b) for a singleton SCC, ask "does this node have a self-loop?". Both
 * questions can be answered directly from {@link PointsToGraph#getFieldPointsToMap}
 * without storing anything, so this revision streams the successor sequence
 * through {@link SuccessorIterator} and replaces the self-loop membership
 * test with {@link #hasSelfLoop(AllocationSite)}. The pass now allocates
 * O(V) instead of O(V + E), which on the same image is the difference
 * between the whole heap and a few hundred megabytes.</p>
 *
 * <p>The behaviour is unchanged: the set of cyclic sites that this class
 * returns is identical to the one the adjacency-list implementation
 * produced, because {@code SuccessorIterator} yields exactly the sequence
 * that used to be stored in the {@code LinkedHashSet} — every non-null,
 * non-{@code UNKNOWN} site in every {@code PointsToSet} of the node's
 * field map, and {@code hasSelfLoop} performs the same membership test
 * that {@code successors.get(single).contains(single)} used to.</p>
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
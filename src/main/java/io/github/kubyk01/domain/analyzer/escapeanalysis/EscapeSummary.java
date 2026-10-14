package io.github.kubyk01.domain.analyzer.escapeanalysis;

import lombok.Builder;
import lombok.Data;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Data
@Builder
public class EscapeSummary {
    @Builder.Default
    private Set<Integer> paramsEscaped = new HashSet<>();
    @Builder.Default
    private Set<Integer> paramsReturned = new HashSet<>();
    private boolean returnsObject;
    private boolean escapesGlobally;
    private boolean createsThread;
    @Builder.Default
    private Set<String> fieldsEscaped = new HashSet<>();

    /**
     * Parameter-to-parameter escape edges.
     *
     * <p>For each key {@code i}, {@code paramEscapeEdges.get(i)} is the set
     * of parameter indices {@code j} such that a value passed as parameter
     * {@code i} may be stored into an object reachable from parameter
     * {@code j}. Concretely, {@code this.map = map} inside a constructor
     * produces the edge {@code map -> this}.</p>
     *
     * <p>The edge is transitive in effect through repeated analysis: when
     * the callee's parameter {@code j} itself escapes into the callee's
     * parameter {@code k}, the caller builds the composed edge
     * {@code i -> k} on the next pass.</p>
     *
     * <p>Why this exists: without it, the summary of a constructor such as
     * {@code CollectionView.<init>(map)} says only "parameter map escapes
     * to heap". The caller then marks the map's allocation site
     * {@code HEAP}, but never learns that the map is reachable from the
     * receiver. When the receiver is subsequently returned, the map's
     * status should be promoted to {@code RETURN} -- otherwise the
     * destructor pass inserts a per-use destructor for an object the
     * caller still owns. The bug this closes is the use-after-free in
     * {@code ConcurrentHashMap.newKeySet()}: the freshly-created map is
     * returned inside a {@code KeySetView}, yet was being freed before
     * the view was handed back to the caller.</p>
     */
    @Builder.Default
    private Map<Integer, Set<Integer>> paramEscapeEdges = new HashMap<>();

    /**
     * Monotone join. ⊥ is the empty summary (all sets empty, all flags
     * false) and ⊤ is "every parameter escapes, every field escapes, all
     * flags true". Merging only ever grows the stored value, which is what
     * makes the SCC fixed point converge instead of oscillating.
     *
     * <p>Idempotent (merging the same summary twice is a no-op),
     * commutative and associative, as a join must be.</p>
     */
    public void merge(EscapeSummary other) {
        paramsEscaped.addAll(other.paramsEscaped);
        paramsReturned.addAll(other.paramsReturned);
        fieldsEscaped.addAll(other.fieldsEscaped);
        returnsObject = returnsObject || other.returnsObject;
        escapesGlobally = escapesGlobally || other.escapesGlobally;
        createsThread = createsThread || other.createsThread;

        for (Map.Entry<Integer, Set<Integer>> e
                : other.paramEscapeEdges.entrySet()) {
            paramEscapeEdges
                .computeIfAbsent(e.getKey(), k -> new HashSet<>())
                .addAll(e.getValue());
        }
    }
}

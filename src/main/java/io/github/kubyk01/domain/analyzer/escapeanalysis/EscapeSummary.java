package io.github.kubyk01.domain.analyzer.escapeanalysis;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
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
     * Params stored into a static field. Exact subset of paramsEscaped.
     * Needed because escapesGlobally is a coarse "somewhere a value was
     * written to a static"; this pinpoints which argument, so only that one
     * is promoted to GLOBAL (the coarse flag promoted every escaped argument,
     * turning local StringBuilders in Object.toString into GLOBALs).
     */
    @Builder.Default
    private Set<Integer> paramsEscapedToStatic = new HashSet<>();

    /**
     * Param-to-param escape edges. i -> j means "value passed as param i may
     * be stored into an object reachable from param j". Threaded through
     * repeated analysis, so callee j->k composes into caller i->k.
     * Without it, `this.map = map` in a constructor only says "map escapes
     * to heap"; when the receiver is returned, the map must be promoted to
     * RETURN too (ConcurrentHashMap.newKeySet use-after-free).
     */
    @Builder.Default
    private Map<Integer, Set<Integer>> paramEscapeEdges = new HashMap<>();

    /**
     * Params reachable from the returned value. Distinct from paramsReturned
     * ("returned value IS the parameter"): this is "the parameter is reachable
     * from the returned value". Used to build args[i] -> return edges in the
     * caller's fixed point.
     */
    @Builder.Default
    private Set<Integer> paramsEscapedToReturn = new HashSet<>();

    /**
     * Sites reachable from each formal parameter at function exit. If the
     * argument has status S, every listed site must have status >=
     * max(HEAP, S). Filled by EscapeSummaryBuilder, consumed by
     * IntraproceduralEscape.processCall in the caller.
     */
    @Builder.Default
    private Map<Integer, Set<AllocationSite>> sitesReachableFromParams = new HashMap<>();

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
        paramsEscapedToStatic.addAll(other.paramsEscapedToStatic);
        paramsEscapedToReturn.addAll(other.paramsEscapedToReturn);
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

        for (Map.Entry<Integer, Set<AllocationSite>> e
                : other.sitesReachableFromParams.entrySet()) {
            sitesReachableFromParams
                .computeIfAbsent(e.getKey(), k -> new HashSet<>())
                .addAll(e.getValue());
        }
    }
}

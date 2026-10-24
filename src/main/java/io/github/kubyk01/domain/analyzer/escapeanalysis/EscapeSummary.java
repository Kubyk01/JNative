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
     * Parameters whose value is stored into a <b>static</b> field.
     *
     * <p>This is an exact subset of {@link #paramsEscaped}:
     * {@code paramsEscapedToStatic ⊆ paramsEscaped}.</p>
     *
     * <p>Why a separate set is needed: {@link #escapesGlobally} is a
     * coarse flag meaning "somewhere in the transitive closure of this
     * function a value was written into a static field". It does not say
     * <em>which</em> parameter ended up there. Treating every escaped
     * argument as {@code GLOBAL} on the strength of that flag is exactly
     * the error that turned a local {@code StringBuilder} inside
     * {@code Object.toString} into a {@code GLOBAL} allocation. With an
     * exact set, only the argument that really landed in a static field
     * is promoted to {@code GLOBAL}; the others stay {@code HEAP}.</p>
     */
    @Builder.Default
    private Set<Integer> paramsEscapedToStatic = new HashSet<>();

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
     * Parameters that are <b>reachable from the returned value</b>
     * (the param &rarr; return edge).
     *
     * <p>Semantics: for each {@code i ∈ paramsEscapedToReturn}, the object
     * that was passed as parameter {@code i} can be reached by following
     * references that start at the returned value.</p>
     *
     * <p>Example: {@code Map.entry(k, v)} returns a {@code KeyValueHolder}
     * whose {@code key}/{@code value} fields hold {@code k}/{@code v}. Then
     * {@code Map.entry.paramsEscapedToReturn = {0, 1}}.</p>
     *
     * <p>How it is used. In {@code IntraproceduralEscape.processCall} an
     * edge {@code args[i] -> returnValue} is added to
     * {@code localEscapeEdges} for every {@code i ∈ paramsEscapedToReturn}.
     * The fixed point then raises the status of the argument to the status
     * of the returned value. If the returned value becomes {@code HEAP}
     * (for example because the object is stored in a field of some
     * container), the argument becomes {@code HEAP} as well, and its
     * allocation no longer receives a per-use destructor.</p>
     *
     * <p>{@link #paramsReturned} means "the returned value <b>is</b> an alias
     * of parameter {@code i}" (the parameter <b>is</b> the returned value).</p>
     */
    @Builder.Default
    private Set<Integer> paramsEscapedToReturn = new HashSet<>();

    /**
     * For every formal parameter {@code i} — the set of allocation sites
     * that are reachable from the value passed as parameter {@code i} when
     * the function exits.
     *
     * <p>Semantics: if the argument {@code args[i]} has escape status
     * {@code S} in the caller, then every site in
     * {@code sitesReachableFromParams.get(i)} must have a status of at
     * least {@code max(HEAP, S)}.</p>
     *
     * <p>This is the missing link. The intraprocedural escape analysis
     * sees that {@code this.lock = new ReentrantLock()} puts a fresh site
     * into a field of the parameter {@code this}, but it does not know what
     * the caller does with {@code this}. If the caller returns {@code this},
     * the {@code ReentrantLock} has to be {@code RETURN} and not
     * {@code HEAP}; otherwise the lifetime analysis places a destructor at
     * the {@code PUT_FIELD} point, frees the lock and nulls out the field,
     * leaving a live object with a null field.</p>
     *
     * <p>The field is filled in by
     * {@link io.github.kubyk01.application.service.analyzer.escapeanalysis.EscapeSummaryBuilder}
     * and read in
     * {@link io.github.kubyk01.application.service.analyzer.escapeanalysis.IntraproceduralEscape#processCall}
     * of the calling function.</p>
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

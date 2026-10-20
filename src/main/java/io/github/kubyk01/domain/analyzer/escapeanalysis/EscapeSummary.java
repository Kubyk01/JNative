package io.github.kubyk01.domain.analyzer.escapeanalysis;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Escape summary of one function: what escapes does it perform on its
 * parameters and return value?
 *
 * <h2>Escape classes</h2>
 *
 * A reference parameter {@code i} is recorded in exactly one of the three
 * escape sets below, depending on where it ends up. Multiple classes may
 * apply simultaneously; consumers should take the ordinal maximum.
 *
 * <ul>
 *   <li>{@link #paramsEscapedToHeap}: stored into a heap object's field
 *       (via {@code putfield}, {@code astore}, or a call whose callee's
 *       summary records the same).</li>
 *   <li>{@link #paramsEscapedToStatic}: stored into a static field (via
 *       {@code putstatic} or a call whose callee's summary records the
 *       same). This is the only escape class that justifies
 *       {@link EscapeStatus#GLOBAL}.</li>
 *   <li>{@link #paramsEscapedToThread}: handed to another thread (via
 *       {@code ExecutorService.submit}, {@code ForkJoinPool.submit},
 *       {@code CompletableFuture.supplyAsync}, {@code Thread.start}, or a
 *       callee whose summary records the same).</li>
 * </ul>
 *
 * <p>Note that <b>returning a parameter is not itself an escape</b>. A
 * function {@code f(x) { return x; }} is not claiming that {@code x}
 * escapes into heap, static, or thread state. It is claiming only that the
 * return value is an alias of {@code x}. The caller decides whether that
 * alias actually escapes, based on its own use of the return value.
 * {@link #paramsReturned} records this relationship, and consumers must
 * NOT promote the corresponding argument on the basis of this set alone.</p>
 *
 * <h2>Parameter-to-parameter escape edges</h2>
 *
 * {@link #paramEscapeEdges} records transitive parameter escapes that are
 * conditional on another parameter's fate. If {@code p.field = q} and both
 * are parameters, the edge {@code q -> p} says: "q is reachable from p, so
 * if p escapes, q escapes too". The caller maps both indices to its own
 * argument values and records the same edge locally; the intraprocedural
 * fixed point then propagates escape statuses along the edge.
 *
 * <h2>Unresolved</h2>
 *
 * {@link #unresolved} is {@code true} for a native function whose C body
 * could not be analysed (recursive call into a helper with no summary, an
 * unparseable source, or a call into a library function the analyser does
 * not model). Callers must treat such a summary as "everything escapes":
 * the {@link NativeBodyEscapeAnalyzer}-based fallback in
 * {@link io.github.kubyk01.application.service.analyzer.escapeanalysis.EscapeSummaryBuilder#processCall}
 * explicitly checks this flag before trusting the summary.
 */
@Data
@Builder
public class EscapeSummary {

    @Builder.Default
    private Set<Integer> paramsRead = new HashSet<>();

    @Builder.Default
    private Set<Integer> paramsWritten = new HashSet<>();

    @Builder.Default
    private Set<Integer> paramsDestroyed = new HashSet<>();

    /** Parameters stored into a heap object's field. */
    @Builder.Default
    private Set<Integer> paramsEscapedToHeap = new HashSet<>();

    /** Parameters stored into a static field. The only GLOBAL justification. */
    @Builder.Default
    private Set<Integer> paramsEscapedToStatic = new HashSet<>();

    /** Parameters handed to another thread. */
    @Builder.Default
    private Set<Integer> paramsEscapedToThread = new HashSet<>();

    /**
     * Parameters aliased with the return value. NOT an escape class on its
     * own: the caller decides whether the returned alias actually escapes.
     */
    @Builder.Default
    private Set<Integer> paramsReturned = new HashSet<>();

    @Builder.Default
    private Set<String> fieldsRead = new HashSet<>();
    @Builder.Default
    private Set<String> fieldsWritten = new HashSet<>();

    private boolean returnsObject;

    /** True iff a native body could not be analysed; consumers fall back. */
    private boolean unresolved;

    @Builder.Default
    private Set<AllocationSite> returnedAllocations = new HashSet<>();

    @Builder.Default
    private Map<Integer, Set<String>> paramsFieldWrites = new HashMap<>();

    @Builder.Default
    private Set<String> staticFieldWrites = new HashSet<>();

    /**
     * Parameter-to-parameter edges: {@code i -> j} means a value passed as
     * parameter {@code i} is stored into a field reachable from parameter
     * {@code j}. The caller maps both indices to its own argument values.
     */
    @Builder.Default
    private Map<Integer, Set<Integer>> paramEscapeEdges = new HashMap<>();

    /** Union of the three escape sets. For convenience only. */
    public Set<Integer> paramsEscaped() {
        Set<Integer> r = new HashSet<>(paramsEscapedToHeap);
        r.addAll(paramsEscapedToStatic);
        r.addAll(paramsEscapedToThread);
        return r;
    }

    /**
     * Monotone join. ⊥ is the empty summary (all sets empty, all flags
     * false). Merging only ever grows the stored value, which is what makes
     * the SCC fixed point converge instead of oscillating.
     */
    public void merge(EscapeSummary other) {
        if (other == null) return;
        paramsRead.addAll(other.paramsRead);
        paramsWritten.addAll(other.paramsWritten);
        paramsDestroyed.addAll(other.paramsDestroyed);
        paramsEscapedToHeap.addAll(other.paramsEscapedToHeap);
        paramsEscapedToStatic.addAll(other.paramsEscapedToStatic);
        paramsEscapedToThread.addAll(other.paramsEscapedToThread);
        paramsReturned.addAll(other.paramsReturned);
        fieldsRead.addAll(other.fieldsRead);
        fieldsWritten.addAll(other.fieldsWritten);
        returnedAllocations.addAll(other.returnedAllocations);
        staticFieldWrites.addAll(other.staticFieldWrites);
        for (Map.Entry<Integer, Set<String>> e : other.paramsFieldWrites.entrySet()) {
            paramsFieldWrites.computeIfAbsent(e.getKey(), k -> new HashSet<>())
                .addAll(e.getValue());
        }
        for (Map.Entry<Integer, Set<Integer>> e : other.paramEscapeEdges.entrySet()) {
            paramEscapeEdges.computeIfAbsent(e.getKey(), k -> new HashSet<>())
                .addAll(e.getValue());
        }
        returnsObject = returnsObject || other.returnsObject;
        unresolved = unresolved || other.unresolved;
    }
}
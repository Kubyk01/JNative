package io.github.kubyk01.domain.analyzer.escapeanalysis;

import lombok.Builder;
import lombok.Data;
import java.util.HashSet;
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
     * Monotone join. ⊥ is the empty summary (all sets empty, all flags false)
     * and ⊤ is "every parameter escapes, every field escapes, all flags
     * true". Merging only ever grows the stored value, which is what makes the
     * SCC fixed point converge instead of oscillating.
     *
     * <p>Idempotent (merging the same summary twice is a no-op), commutative
     * and associative, as a join must be.</p>
     */
    public void merge(EscapeSummary other) {
        paramsEscaped.addAll(other.paramsEscaped);
        paramsReturned.addAll(other.paramsReturned);
        fieldsEscaped.addAll(other.fieldsEscaped);
        returnsObject = returnsObject || other.returnsObject;
        escapesGlobally = escapesGlobally || other.escapesGlobally;
        createsThread = createsThread || other.createsThread;
    }
}

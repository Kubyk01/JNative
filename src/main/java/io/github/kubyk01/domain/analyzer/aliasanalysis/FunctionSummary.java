package io.github.kubyk01.domain.analyzer.aliasanalysis;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Data
@Builder
public class FunctionSummary {
    @Builder.Default
    private Set<Integer> paramsRead = new HashSet<>();
    @Builder.Default
    private Set<Integer> paramsWritten = new HashSet<>();
    @Builder.Default
    private Set<Integer> paramsEscaped = new HashSet<>();
    @Builder.Default
    private Set<Integer> paramsReturned = new HashSet<>();
    @Builder.Default
    private Set<Integer> paramsDestroyed = new HashSet<>();
    @Builder.Default
    private Set<String> fieldsRead = new HashSet<>();
    @Builder.Default
    private Set<String> fieldsWritten = new HashSet<>();

    private boolean returnsObject;
    private boolean readsStaticFields;
    private boolean writesStaticFields;
    private boolean escapesGlobally;

    @Builder.Default
    private Set<AllocationSite> returnedAllocations = new HashSet<>();
    @Builder.Default
    private Map<Integer, Set<String>> paramsFieldWrites = new HashMap<>();
    @Builder.Default
    private Set<String> staticFieldWrites = new HashSet<>();

    public void merge(FunctionSummary other) {
        paramsRead.addAll(other.paramsRead);
        paramsWritten.addAll(other.paramsWritten);
        paramsEscaped.addAll(other.paramsEscaped);
        paramsReturned.addAll(other.paramsReturned);
        paramsDestroyed.addAll(other.paramsDestroyed);
        fieldsRead.addAll(other.fieldsRead);
        fieldsWritten.addAll(other.fieldsWritten);
        returnedAllocations.addAll(other.returnedAllocations);

        for (Map.Entry<Integer, Set<String>> e : other.paramsFieldWrites.entrySet()) {
            paramsFieldWrites.computeIfAbsent(e.getKey(), k -> new HashSet<>())
                .addAll(e.getValue());
        }
        staticFieldWrites.addAll(other.staticFieldWrites);

        returnsObject = returnsObject || other.returnsObject;
        readsStaticFields = readsStaticFields || other.readsStaticFields;
        writesStaticFields = writesStaticFields || other.writesStaticFields;
        escapesGlobally = escapesGlobally || other.escapesGlobally;
    }
}
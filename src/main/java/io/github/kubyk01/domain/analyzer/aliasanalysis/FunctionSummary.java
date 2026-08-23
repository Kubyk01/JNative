package io.github.kubyk01.domain.analyzer.aliasanalysis;

import lombok.Builder;
import lombok.Data;
import java.util.*;

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
    private Map<Integer, Map<String, Set<AllocationSite>>> paramsFieldWrites = new HashMap<>();
    @Builder.Default
    private Map<String, Set<AllocationSite>> staticFieldWrites = new HashMap<>();

    public void merge(FunctionSummary other) {
        paramsRead.addAll(other.paramsRead);
        paramsWritten.addAll(other.paramsWritten);
        paramsEscaped.addAll(other.paramsEscaped);
        paramsReturned.addAll(other.paramsReturned);
        paramsDestroyed.addAll(other.paramsDestroyed);
        fieldsRead.addAll(other.fieldsRead);
        fieldsWritten.addAll(other.fieldsWritten);
        returnedAllocations.addAll(other.returnedAllocations);

        for (Map.Entry<Integer, Map<String, Set<AllocationSite>>> e : other.paramsFieldWrites.entrySet()) {
            Map<String, Set<AllocationSite>> map = paramsFieldWrites.computeIfAbsent(e.getKey(), k -> new HashMap<>());
            for (Map.Entry<String, Set<AllocationSite>> fe : e.getValue().entrySet()) {
                map.computeIfAbsent(fe.getKey(), k -> new HashSet<>()).addAll(fe.getValue());
            }
        }
        for (Map.Entry<String, Set<AllocationSite>> e : other.staticFieldWrites.entrySet()) {
            staticFieldWrites.computeIfAbsent(e.getKey(), k -> new HashSet<>()).addAll(e.getValue());
        }

        returnsObject = returnsObject || other.returnsObject;
        readsStaticFields = readsStaticFields || other.readsStaticFields;
        writesStaticFields = writesStaticFields || other.writesStaticFields;
        escapesGlobally = escapesGlobally || other.escapesGlobally;
    }
}

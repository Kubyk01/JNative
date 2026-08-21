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
}

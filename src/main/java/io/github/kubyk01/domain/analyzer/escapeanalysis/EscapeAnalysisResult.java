package io.github.kubyk01.domain.analyzer.escapeanalysis;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.ir.Value;

import java.util.HashMap;
import java.util.Map;

public class EscapeAnalysisResult {
    private final Map<AllocationSite, EscapeStatus> siteStatus = new HashMap<>();
    private final Map<Value, EscapeStatus> valueStatus = new HashMap<>();

    public void setSiteStatus(AllocationSite site, EscapeStatus status) {
        siteStatus.put(site, status);
    }

    public EscapeStatus getSiteStatus(AllocationSite site) {
        return siteStatus.getOrDefault(site, EscapeStatus.UNKNOWN);
    }

    public void setValueStatus(Value v, EscapeStatus status) {
        valueStatus.put(v, status);
    }

    public EscapeStatus getValueStatus(Value v) {
        return valueStatus.getOrDefault(v, EscapeStatus.UNKNOWN);
    }

    public boolean isStackAllocable(AllocationSite site) {
        return getSiteStatus(site) == EscapeStatus.STACK;
    }
}

package io.github.kubyk01.domain.analyzer.escapeanalysis;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Value;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

public class EscapeAnalysisResult {

    public record CallShape(long retainedArgs, long returnedArgs, boolean freshReturn) {

        public static boolean has(long mask, int index) {
            return (mask & (1L << Math.min(index, 63))) != 0;
        }

        public static long bit(int index) {
            return 1L << Math.min(index, 63);
        }

        CallShape join(CallShape other) {
            return new CallShape(retainedArgs | other.retainedArgs, returnedArgs | other.returnedArgs,
                freshReturn && other.freshReturn);
        }
    }

    private final Map<String, CallShape> directShapes = new HashMap<>();
    private final Map<String, CallShape> dispatchShapes = new HashMap<>();
    private final Map<Instruction, CallShape> siteShapes = new IdentityHashMap<>();
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

    public void putCallShape(boolean dispatch, String callee, CallShape shape) {
        (dispatch ? dispatchShapes : directShapes).merge(callee, shape, CallShape::join);
    }

    public void putSiteShape(Instruction call, CallShape shape) {
        siteShapes.merge(call, shape, CallShape::join);
    }

    public CallShape getCallShape(Instruction call, String callee) {
        CallShape site = siteShapes.get(call);
        if (site != null) return site;
        if (callee == null) return null;
        Opcode op = call.getOpcode();
        boolean dispatch = op == Opcode.VIRTUAL_CALL || op == Opcode.INTERFACE_CALL;
        return (dispatch ? dispatchShapes : directShapes).get(callee);
    }

    public EscapeAnalysisResult merge(EscapeAnalysisResult other) {
        other.directShapes.forEach((k, v) -> directShapes.merge(k, v, CallShape::join));
        other.dispatchShapes.forEach((k, v) -> dispatchShapes.merge(k, v, CallShape::join));
        other.siteShapes.forEach((k, v) -> siteShapes.merge(k, v, CallShape::join));
        other.siteStatus.forEach((site, status) -> siteStatus.merge(site, status, EscapeAnalysisResult::join));
        other.valueStatus.forEach((value, status) -> valueStatus.merge(value, status, EscapeAnalysisResult::join));
        return this;
    }

    private static EscapeStatus join(EscapeStatus a, EscapeStatus b) {
        if (a == EscapeStatus.UNKNOWN) return b;
        if (b == EscapeStatus.UNKNOWN) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}

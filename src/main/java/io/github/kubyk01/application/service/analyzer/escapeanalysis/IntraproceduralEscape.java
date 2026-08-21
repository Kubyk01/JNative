package io.github.kubyk01.application.service.analyzer.escapeanalysis;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.analyzer.ir.BasicBlock;
import io.github.kubyk01.domain.analyzer.ir.Constant;
import io.github.kubyk01.domain.analyzer.ir.Function;
import io.github.kubyk01.domain.analyzer.ir.Instruction;
import io.github.kubyk01.domain.analyzer.ir.Opcode;
import io.github.kubyk01.domain.analyzer.ir.ReturnTerminator;
import io.github.kubyk01.domain.analyzer.ir.Terminator;
import io.github.kubyk01.domain.analyzer.ir.Type;
import io.github.kubyk01.domain.analyzer.ir.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class IntraproceduralEscape {

    private final Function function;
    private final AliasAnalysisResult aliasResult;
    private final Map<String, EscapeSummary> summaries;
    private final Map<AllocationSite, EscapeStatus> siteStatus = new HashMap<>();
    private final Map<AllocationSite, Map<String, EscapeStatus>> fieldStatus = new HashMap<>();

    public IntraproceduralEscape(Function function, AliasAnalysisResult aliasResult,
                                 Map<String, EscapeSummary> summaries) {
        this.function = function;
        this.aliasResult = aliasResult;
        this.summaries = summaries;
    }

    public Map<AllocationSite, EscapeStatus> analyze() {
        // Initialization: all allocation sites start as STACK
        for (BasicBlock block : function.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (isAllocation(inst.getOpcode()) && inst.getResult() != null) {
                    PointsToSet pts = aliasResult.getPointsTo(inst.getResult());
                    for (AllocationSite site : pts.getSites()) {
                        siteStatus.put(site, EscapeStatus.STACK);
                    }
                }
            }
        }

        // Instruction analysis
        for (BasicBlock block : function.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                processInstruction(inst);
            }
            Terminator term = block.getTerminator();
            if (term != null) processTerminator(term);
        }

        return siteStatus;
    }

    private void processInstruction(Instruction inst) {
        Opcode op = inst.getOpcode();
        switch (op) {
            case PUT_FIELD: {
                if (inst.getOperands().size() >= 3) {
                    Value base = inst.getOperands().get(0);
                    Value rhs = inst.getOperands().get(2);
                    String field = extractFieldName(inst);

                    // base and rhs escape at least to HEAP
                    markEscaped(base, EscapeStatus.HEAP);
                    markEscaped(rhs, EscapeStatus.HEAP);

                    // Update the field status for base objects (if known)
                    PointsToSet basePts = aliasResult.getPointsTo(base);
                    for (AllocationSite site : basePts.getSites()) {
                        fieldStatus.computeIfAbsent(site, k -> new HashMap<>())
                                   .put(field, EscapeStatus.HEAP);
                    }
                    // If base is unknown, conservatively mark the field for UNKNOWN
                    if (basePts.isEmpty()) {
                        fieldStatus.computeIfAbsent(AllocationSite.UNKNOWN, k -> new HashMap<>())
                                   .put(field, EscapeStatus.HEAP);
                    }
                }
                break;
            }
            case GET_FIELD: {
                if (inst.getOperands().size() >= 2) {
                    Value base = inst.getOperands().getFirst();
                    Value result = inst.getResult();
                    String field = extractFieldName(inst);

                    EscapeStatus baseStatus = getMaxStatus(base);
                    PointsToSet basePts = aliasResult.getPointsTo(base);
                    // Get the objects that may be stored in this field (exact set)
                    PointsToSet fieldPts = aliasResult.getFieldPointsToForSites(basePts, field);

                    // Field status for each allocation site in fieldPts
                    for (AllocationSite site : fieldPts.getSites()) {
                        EscapeStatus current = siteStatus.getOrDefault(site, EscapeStatus.STACK);
                        EscapeStatus newStatus = current;
                        if (baseStatus.ordinal() > newStatus.ordinal()) {
                            newStatus = baseStatus;
                        }
                        // Also take into account the saved field status (if the field was written earlier)
                        Map<String, EscapeStatus> fieldMap = fieldStatus.get(site);
                        if (fieldMap != null) {
                            EscapeStatus fieldStat = fieldMap.getOrDefault(field, EscapeStatus.STACK);
                            if (fieldStat.ordinal() > newStatus.ordinal()) {
                                newStatus = fieldStat;
                            }
                        }
                        if (newStatus.ordinal() > current.ordinal()) {
                            siteStatus.put(site, newStatus);
                        }
                    }

                    // If result is not null, assign it the maximum status among the objects in the field
                    if (result != null) {
                        EscapeStatus resultStatus = EscapeStatus.STACK;
                        for (AllocationSite site : fieldPts.getSites()) {
                            EscapeStatus st = siteStatus.getOrDefault(site, EscapeStatus.STACK);
                            if (st.ordinal() > resultStatus.ordinal()) resultStatus = st;
                        }
                        if (baseStatus.ordinal() > resultStatus.ordinal()) resultStatus = baseStatus;
                        markEscaped(result, resultStatus);
                    }
                }
                break;
            }
            case PUT_STATIC: {
                if (!inst.getOperands().isEmpty()) {
                    Value rhs = inst.getOperands().getFirst();
                    markEscaped(rhs, EscapeStatus.GLOBAL);
                }
                break;
            }
            case GET_STATIC: {
                Value result = inst.getResult();
                if (result != null) {
                    // Static fields may hold any objects - conservatively GLOBAL
                    markEscaped(result, EscapeStatus.GLOBAL);
                }
                break;
            }
            case CALL:
            case VIRTUAL_CALL:
            case INTERFACE_CALL:
            case STATIC_CALL:
            case SPECIAL_CALL: {
                processCall(inst);
                break;
            }
            default:
                // other instructions have no effect
        }
    }

    private void processCall(Instruction callInst) {
        String calleeName = extractCalleeName(callInst);
        if (calleeName == null) return;

        // Thread creation detection
        if (calleeName.startsWith("java/util/concurrent/ExecutorService.submit") ||
            calleeName.startsWith("java/util/concurrent/ForkJoinPool.submit") ||
            calleeName.startsWith("java/util/concurrent/CompletableFuture.supplyAsync") ||
            calleeName.startsWith("java/lang/Thread.start()V")) {
            List<Value> args = getCallArguments(callInst);
            for (Value arg : args) markEscaped(arg, EscapeStatus.THREAD);
            Value ret = callInst.getResult();
            if (ret != null) markEscaped(ret, EscapeStatus.THREAD);
            return;
        }

        EscapeSummary summary = summaries.get(calleeName);
        if (summary == null) {
            // External call - conservatively all arguments become HEAP
            List<Value> args = getCallArguments(callInst);
            for (Value arg : args) markEscaped(arg, EscapeStatus.HEAP);
            Value ret = callInst.getResult();
            if (ret != null && ret.getType() != Type.VOID) markEscaped(ret, EscapeStatus.HEAP);
            return;
        }

        List<Value> args = getCallArguments(callInst);
        Value returnValue = callInst.getResult();

        for (int i = 0; i < args.size(); i++) {
            if (summary.getParamsEscaped().contains(i)) {
                markEscaped(args.get(i), EscapeStatus.HEAP);
            }
        }

        if (summary.isEscapesGlobally()) {
            for (Value arg : args) markEscaped(arg, EscapeStatus.GLOBAL);
            if (returnValue != null) markEscaped(returnValue, EscapeStatus.GLOBAL);
        }

        // If a function returns an object, it escapes via return
        if (summary.isReturnsObject() && returnValue != null) {
            markEscaped(returnValue, EscapeStatus.HEAP);
        }
    }

    private void processTerminator(Terminator term) {
        if (term instanceof ReturnTerminator rt) {
            Value retVal = rt.getValue();
            if (retVal != null && retVal.getType() != Type.VOID) {
                markEscaped(retVal, EscapeStatus.HEAP);
            }
        }
    }

    private void markEscaped(Value v, EscapeStatus status) {
        if (v == null) return;
        PointsToSet pts = aliasResult.getPointsTo(v);
        for (AllocationSite site : pts.getSites()) {
            markEscaped(site, status);
        }
    }

    private void markEscaped(AllocationSite site, EscapeStatus status) {
        EscapeStatus current = siteStatus.getOrDefault(site, EscapeStatus.STACK);
        if (status.ordinal() > current.ordinal()) {
            siteStatus.put(site, status);
        }
    }

    private EscapeStatus getMaxStatus(Value v) {
        EscapeStatus max = EscapeStatus.STACK;
        PointsToSet pts = aliasResult.getPointsTo(v);
        for (AllocationSite site : pts.getSites()) {
            EscapeStatus st = siteStatus.getOrDefault(site, EscapeStatus.STACK);
            if (st.ordinal() > max.ordinal()) max = st;
        }
        return max;
    }

    private boolean isAllocation(Opcode op) {
        return op == Opcode.NEW || op == Opcode.NEW_ARRAY || op == Opcode.MULTI_NEW_ARRAY;
    }

    private String extractFieldName(Instruction inst) {
        if (inst.getOperands().size() >= 2) {
            Value v = inst.getOperands().get(1);
            if (v instanceof Constant c && c.getType() == Type.REFERENCE) {
                String val = c.getValue().toString();
                int dot = val.lastIndexOf('.');
                return dot >= 0 ? val.substring(dot + 1) : val;
            }
        }
        return "unknown";
    }

    private String extractCalleeName(Instruction inst) {
        if (!inst.getOperands().isEmpty()) {
            Value v = inst.getOperands().getFirst();
            if (v instanceof Constant c && c.getType() == Type.REFERENCE) {
                return c.getValue().toString();
            }
        }
        return null;
    }

    private List<Value> getCallArguments(Instruction inst) {
        List<Value> args = new ArrayList<>();
        boolean skipFirst = true;
        for (Value op : inst.getOperands()) {
            if (skipFirst) { skipFirst = false; continue; }
            args.add(op);
        }
        return args;
    }
}

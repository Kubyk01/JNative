package io.github.kubyk01.application.service.analyzer.escapeanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.Opcodes;

import java.util.*;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldOwnerAndName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;
import static io.github.kubyk01.util.LlvmUtil.isAllocation;

@Slf4j
public class IntraproceduralEscape {

    private final Function function;
    private final AliasAnalysisResult aliasResult;
    private final Map<String, EscapeSummary> summaries;
    private final DependencyResolver resolver;
    private final Map<AllocationSite, EscapeStatus> siteStatus = new HashMap<>();
    private final Map<AllocationSite, Map<String, EscapeStatus>> fieldStatus = new HashMap<>();

    public IntraproceduralEscape(Function function, AliasAnalysisResult aliasResult,
                                 Map<String, EscapeSummary> summaries, DependencyResolver resolver) {
        this.function = function;
        this.aliasResult = aliasResult;
        this.summaries = summaries;
        this.resolver = resolver;
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
                    String[] ownerAndField = extractFieldOwnerAndName(inst);
                    String owner = ownerAndField[0];
                    String fieldName = ownerAndField[1];

                    // base and rhs escape at least to HEAP
                    markEscaped(base, EscapeStatus.HEAP);
                    markEscaped(rhs, EscapeStatus.HEAP);

                    // Check if field is volatile
                    if (isVolatileField(owner, fieldName)) {
                        // volatile field makes base and rhs globally visible
                        markEscaped(base, EscapeStatus.GLOBAL);
                        markEscaped(rhs, EscapeStatus.GLOBAL);
                    }

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
                    String[] ownerAndField = extractFieldOwnerAndName(inst);
                    String owner = ownerAndField[0];
                    String fieldName = ownerAndField[1];

                    EscapeStatus baseStatus = getMaxStatus(base);
                    PointsToSet basePts = aliasResult.getPointsTo(base);
                    // Get the objects that may be stored in this field (exact set)
                    PointsToSet fieldPts = aliasResult.getFieldPointsToForSites(basePts, field);

                    // Check if volatile
                    if (isVolatileField(owner, fieldName)) {
                        // volatile field makes base globally visible
                        markEscaped(base, EscapeStatus.GLOBAL);
                        // Also mark result as GLOBAL conservatively
                        if (result != null) markEscaped(result, EscapeStatus.GLOBAL);
                    }

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
            case ALOAD: {
                if (inst.getOperands().size() >= 2) {
                    Value array = inst.getOperands().getFirst();
                    Value result = inst.getResult();
                    // The result escapes at least as much as the array (if array escapes, elements escape too)
                    EscapeStatus arrayStatus = getMaxStatus(array);
                    if (result != null) {
                        markEscaped(result, arrayStatus);
                        // Also, elements may escape via the result, so we propagate upward
                        PointsToSet arrayPts = aliasResult.getPointsTo(array);
                        for (AllocationSite site : arrayPts.getSites()) {
                            EscapeStatus current = siteStatus.getOrDefault(site, EscapeStatus.STACK);
                            if (arrayStatus.ordinal() > current.ordinal()) {
                                siteStatus.put(site, arrayStatus);
                            }
                        }
                    }
                }
                break;
            }
            case ASTORE: {
                if (inst.getOperands().size() >= 3) {
                    Value array = inst.getOperands().get(0);
                    Value value = inst.getOperands().get(2);
                    // The value escapes to HEAP because it's stored in an array
                    markEscaped(value, EscapeStatus.HEAP);
                    // The array itself may escape if it holds escaping objects, but we don't promote array
                    // unless the value is a parameter that escapes.
                    // Also, the array's elements get the status of the value.
                    PointsToSet arrayPts = aliasResult.getPointsTo(array);
                    for (AllocationSite site : arrayPts.getSites()) {
                        // record that the array's element field may contain escaping objects
                        Map<String, EscapeStatus> fieldMap = fieldStatus.computeIfAbsent(site, k -> new HashMap<>());
                        fieldMap.put("[]", EscapeStatus.HEAP);
                    }
                    if (arrayPts.isEmpty()) {
                        fieldStatus.computeIfAbsent(AllocationSite.UNKNOWN, k -> new HashMap<>())
                                   .put("[]", EscapeStatus.HEAP);
                    }
                }
                break;
            }
            case MONITOR_ENTER:
            case MONITOR_EXIT: {
                if (!inst.getOperands().isEmpty()) {
                    Value obj = inst.getOperands().getFirst();
                    // Synchronizing on an object makes it globally visible
                    markEscaped(obj, EscapeStatus.GLOBAL);
                }
                break;
            }
            case PUT_STATIC: {
                // Layout PUT_STATIC: [fieldConst, val].
                if (inst.getOperands().size() >= 2) {
                    Value rhs = inst.getOperands().get(1);
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
            if (summary.getParamsReturned().contains(i)) {
                markEscaped(args.get(i), EscapeStatus.RETURN);
            }
        }

        if (summary.isEscapesGlobally()) {
            for (Value arg : args) markEscaped(arg, EscapeStatus.GLOBAL);
            if (returnValue != null) markEscaped(returnValue, EscapeStatus.GLOBAL);
        }

        // If a function returns an object, it escapes via return
        if (summary.isReturnsObject() && returnValue != null) {
            markEscaped(returnValue, EscapeStatus.RETURN);
        }
    }

    private void processTerminator(Terminator term) {
        if (term instanceof ReturnTerminator rt) {
            Value retVal = rt.getValue();
            if (retVal != null && retVal.getType() != Type.VOID) {
                markEscaped(retVal, EscapeStatus.RETURN);
            }
        } else if (term instanceof ThrowTerminator tt) {
            Value exc = tt.getException();
            if (exc != null) {
                // The exception escapes the current method: either it is
                // caught higher up the stack, or the runtime's throw helper
                // takes ownership of it. Either way it must not be destroyed
                // (or scalar-replaced away) inside this function.
                markEscaped(exc, EscapeStatus.RETURN);
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


    private boolean isVolatileField(String owner, String fieldName) {
        if (owner == null || owner.isEmpty() || fieldName == null || fieldName.isEmpty()) return false;
        FieldNode field = resolver.getField(owner, fieldName);
        return field != null && (field.getAccess() & Opcodes.ACC_VOLATILE) != 0;
    }

}

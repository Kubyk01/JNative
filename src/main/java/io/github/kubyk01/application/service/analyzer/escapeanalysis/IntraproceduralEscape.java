package io.github.kubyk01.application.service.analyzer.escapeanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.ir.*;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.Opcodes;

import java.util.*;

import static io.github.kubyk01.util.LlvmUtil.canonicalCalleeKey;
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
    private final NativeBodyEscapeAnalyzer nativeAnalyzer;

    /**
     * Local parameter-to-parameter escape edges: {@code from -> {to}} means
     * "the object referenced by {@code from} is reachable from {@code to}".
     * Used by the fixed point to propagate {@code to}'s status to {@code from}.
     */
    private final Map<Value, Set<Value>> localEscapeEdges = new HashMap<>();

    private boolean changed;

    public IntraproceduralEscape(Function function,
                                 AliasAnalysisResult aliasResult,
                                 Map<String, EscapeSummary> summaries,
                                 DependencyResolver resolver) {
        this.function = function;
        this.aliasResult = aliasResult;
        this.summaries = summaries;
        this.resolver = resolver;
        this.nativeAnalyzer = new NativeBodyEscapeAnalyzer(resolver);
    }

    public Map<AllocationSite, EscapeStatus> analyze() {
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

        seedFromOwnSummary();

        final int MAX_ITERATIONS = 64;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            changed = false;
            for (BasicBlock block : function.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    processInstruction(inst);
                }
                Terminator term = block.getTerminator();
                if (term != null) processTerminator(term);
            }

            for (Map.Entry<Value, Set<Value>> e : localEscapeEdges.entrySet()) {
                Value from = e.getKey();
                EscapeStatus fromEsc = getMaxStatus(from);
                for (Value to : e.getValue()) {
                    EscapeStatus toEsc = valueEscapeStatus(to);
                    if (toEsc.ordinal() > fromEsc.ordinal()) {
                        markEscaped(from, toEsc);
                        fromEsc = toEsc;
                    }
                }
            }

            if (!changed) break;
        }

        return siteStatus;
    }

    /**
     * Seeds parameter escape statuses from the current function's own
     * summary. The summary was computed by {@link EscapeSummaryBuilder} and
     * encodes exactly the facts the intraprocedural fixed point needs to
     * resolve local allocations reachable from those parameters.
     *
     * <p>Without this seeding, {@code p}'s status inside the function would
     * default to {@link EscapeStatus#STACK} even when the summary says the
     * parameter escapes to heap or global in its caller's context. A local
     * allocation {@code c} stored in {@code p.field} would then wrongly
     * be classified STACK.</p>
     */
    private void seedFromOwnSummary() {
        EscapeSummary self = summaries.get(function.getName());
        if (self == null) return;

        for (Parameter p : function.getParameters()) {
            int ord = p.getOrdinal();
            EscapeStatus seed = EscapeStatus.STACK;
            if (self.getParamsEscapedToThread().contains(ord)) {
                seed = maxEscape(seed, EscapeStatus.THREAD);
            }
            if (self.getParamsEscapedToStatic().contains(ord)) {
                seed = maxEscape(seed, EscapeStatus.GLOBAL);
            }
            if (self.getParamsEscapedToHeap().contains(ord)) {
                seed = maxEscape(seed, EscapeStatus.HEAP);
            }
            if (self.getParamsReturned().contains(ord)) {
                // A returned parameter is aliased with the return value.
                // The callee's local allocations reachable from it must be
                // at least RETURN-escaped, because the caller's subsequent
                // use of the return value decides their fate.
                seed = maxEscape(seed, EscapeStatus.RETURN);
            }
            if (seed != EscapeStatus.STACK) {
                markEscaped(p, seed);
            }
        }

        for (Map.Entry<Integer, Set<Integer>> e : self.getParamEscapeEdges().entrySet()) {
            Parameter from = findParameterByOrdinal(function, e.getKey());
            if (from == null) continue;
            for (int toOrd : e.getValue()) {
                Parameter to = findParameterByOrdinal(function, toOrd);
                if (to == null) continue;
                localEscapeEdges.computeIfAbsent(from, k -> new HashSet<>()).add(to);
            }
        }
    }

    private static Parameter findParameterByOrdinal(Function f, int ordinal) {
        for (Parameter p : f.getParameters()) {
            if (p.getOrdinal() == ordinal) return p;
        }
        return null;
    }

    private void processInstruction(Instruction inst) {
        Opcode op = inst.getOpcode();
        switch (op) {
            case PUT_FIELD    -> processPutField(inst);
            case GET_FIELD    -> processGetField(inst);
            case ALOAD        -> processALoad(inst);
            case ASTORE       -> processAStore(inst);
            case MONITOR_ENTER, MONITOR_EXIT -> { /* monitor does not escape */ }
            case PUT_STATIC   -> processPutStatic(inst);
            case GET_STATIC   -> processGetStatic(inst);
            case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL ->
                processCall(inst);
            default -> { }
        }
    }

    private void processPutField(Instruction inst) {
        if (inst.getOperands().size() < 3) return;

        Value base = inst.getOperands().get(0);
        Value rhs  = inst.getOperands().get(2);
        String field = extractFieldName(inst);
        String[] ownerAndField = extractFieldOwnerAndName(inst);
        String owner = ownerAndField[0];
        String fieldName = ownerAndField[1];

        EscapeStatus baseEsc = valueEscapeStatus(base);
        markEscaped(rhs, baseEsc);

        EscapeStatus rhsEsc   = valueEscapeStatus(rhs);
        EscapeStatus fieldEsc = maxEscape(baseEsc, rhsEsc);

        PointsToSet basePts = aliasResult.getPointsTo(base);
        if (basePts.isEmpty() && base instanceof Parameter) {
            updateFieldStatus(AllocationSite.UNKNOWN, field,
                maxEscape(fieldEsc, EscapeStatus.HEAP));
        } else {
            for (AllocationSite site : basePts.getSites()) {
                updateFieldStatus(site, field, fieldEsc);
            }
        }

        if (isVolatileField(owner, fieldName) && rhs != null) {
            markEscaped(rhs, EscapeStatus.GLOBAL);
        }
    }

    private void processGetField(Instruction inst) {
        if (inst.getOperands().size() < 2) return;
        Value base = inst.getOperands().getFirst();
        Value result = inst.getResult();
        if (result == null) return;

        String field = extractFieldName(inst);
        String[] ownerAndField = extractFieldOwnerAndName(inst);

        EscapeStatus baseEsc = valueEscapeStatus(base);
        PointsToSet   basePts = aliasResult.getPointsTo(base);

        EscapeStatus resultEsc = baseEsc;
        for (AllocationSite site : basePts.getSites()) {
            Map<String, EscapeStatus> map = fieldStatus.get(site);
            if (map != null) {
                resultEsc = maxEscape(resultEsc,
                    map.getOrDefault(field, EscapeStatus.STACK));
            }
        }
        if (basePts.isEmpty() && base instanceof Parameter) {
            Map<String, EscapeStatus> map = fieldStatus.get(AllocationSite.UNKNOWN);
            if (map != null) {
                resultEsc = maxEscape(resultEsc,
                    map.getOrDefault(field, EscapeStatus.STACK));
            }
            resultEsc = maxEscape(resultEsc, EscapeStatus.HEAP);
        }

        PointsToSet fieldPts = aliasResult.getFieldPointsToForSites(basePts, field);
        for (AllocationSite site : fieldPts.getSites()) {
            markEscaped(site, resultEsc);
        }

        markEscaped(result, resultEsc);

        if (isVolatileField(ownerAndField[0], ownerAndField[1])) {
            markEscaped(result, EscapeStatus.GLOBAL);
        }
    }

    private void processALoad(Instruction inst) {
        if (inst.getOperands().size() < 2) return;
        Value array = inst.getOperands().getFirst();
        Value result = inst.getResult();
        if (result == null) return;

        EscapeStatus arrayEsc = valueEscapeStatus(array);
        PointsToSet arrayPts  = aliasResult.getPointsTo(array);
        PointsToSet elemPts   = aliasResult.getFieldPointsToForSites(arrayPts, "[]");

        for (AllocationSite site : elemPts.getSites()) {
            markEscaped(site, arrayEsc);
        }
        markEscaped(result, arrayEsc);
    }

    private void processAStore(Instruction inst) {
        if (inst.getOperands().size() < 3) return;
        Value array = inst.getOperands().get(0);
        Value value = inst.getOperands().get(2);
        if (value == null) return;

        EscapeStatus arrayEsc = valueEscapeStatus(array);
        markEscaped(value, arrayEsc);

        EscapeStatus valueEsc = valueEscapeStatus(value);
        EscapeStatus fieldEsc = maxEscape(arrayEsc, valueEsc);

        PointsToSet arrayPts = aliasResult.getPointsTo(array);
        if (arrayPts.isEmpty() && array instanceof Parameter) {
            updateFieldStatus(AllocationSite.UNKNOWN, "[]",
                maxEscape(fieldEsc, EscapeStatus.HEAP));
        } else {
            for (AllocationSite site : arrayPts.getSites()) {
                updateFieldStatus(site, "[]", fieldEsc);
            }
        }
    }

    private void processPutStatic(Instruction inst) {
        if (inst.getOperands().size() < 2) return;
        Value rhs = inst.getOperands().get(1);
        if (rhs != null) markEscaped(rhs, EscapeStatus.GLOBAL);
    }

    private void processGetStatic(Instruction inst) {
        Value result = inst.getResult();
        if (result == null) return;
        markEscaped(result, EscapeStatus.GLOBAL);
    }

    private void processCall(Instruction callInst) {
        String calleeName = extractCalleeName(callInst);
        if (calleeName == null) return;

        if (calleeName.startsWith("java/util/concurrent/ExecutorService.submit")
            || calleeName.startsWith("java/util/concurrent/ForkJoinPool.submit")
            || calleeName.startsWith("java/util/concurrent/CompletableFuture.supplyAsync")
            || calleeName.startsWith("java/lang/Thread.start()V")) {
            for (Value arg : getCallArguments(callInst)) {
                markEscaped(arg, EscapeStatus.THREAD);
            }
            Value ret = callInst.getResult();
            if (ret != null) markEscaped(ret, EscapeStatus.THREAD);
            return;
        }

        String calleeKey = canonicalCalleeKey(calleeName);
        EscapeSummary summary = summaries.get(calleeKey);
        if (summary == null) {
            EscapeSummary computed = nativeAnalyzer.summaryFor(calleeName);
            if (computed != null && !computed.isUnresolved()) {
                summary = computed;
            } else {
                for (Value arg : getCallArguments(callInst)) {
                    markEscaped(arg, EscapeStatus.HEAP);
                }
                Value ret = callInst.getResult();
                if (ret != null && ret.getType() != Type.VOID) {
                    markEscaped(ret, EscapeStatus.HEAP);
                }
                return;
            }
        }

        List<Value> args = getCallArguments(callInst);
        Value returnValue = callInst.getResult();

        for (int i = 0; i < args.size(); i++) {
            Value arg = args.get(i);
            EscapeStatus status = EscapeStatus.STACK;
            if (summary.getParamsEscapedToThread().contains(i)) {
                status = maxEscape(status, EscapeStatus.THREAD);
            }
            if (summary.getParamsEscapedToStatic().contains(i)) {
                status = maxEscape(status, EscapeStatus.GLOBAL);
            }
            if (summary.getParamsEscapedToHeap().contains(i)) {
                status = maxEscape(status, EscapeStatus.HEAP);
            }
            if (status != EscapeStatus.STACK) {
                markEscaped(arg, status);
            }
            // paramsReturned is deliberately NOT propagated as RETURN here:
            // the callee returning the argument tells us only that the return
            // value aliases the argument, not that the argument escapes. The
            // caller's subsequent use of the return value determines whether
            // the argument actually escapes.
        }

        if (summary.isReturnsObject() && returnValue != null) {
            // The callee's own analysis has already classified every
            // returned allocation site. Nothing to promote here.
        }

        for (Map.Entry<Integer, Set<Integer>> e : summary.getParamEscapeEdges().entrySet()) {
            int fromIdx = e.getKey();
            if (fromIdx >= args.size()) continue;
            Value fromArg = args.get(fromIdx);
            if (fromArg == null) continue;
            for (int toIdx : e.getValue()) {
                if (toIdx >= args.size()) continue;
                Value toArg = args.get(toIdx);
                if (toArg == null || toArg == fromArg) continue;
                localEscapeEdges
                    .computeIfAbsent(fromArg, k -> new HashSet<>())
                    .add(toArg);
            }
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
                markEscaped(exc, EscapeStatus.RETURN);
            }
        }
    }

    private EscapeStatus valueEscapeStatus(Value v) {
        if (v == null) return EscapeStatus.STACK;
        EscapeStatus status = getMaxStatus(v);
        if (v instanceof Parameter) {
            status = maxEscape(status, EscapeStatus.HEAP);
        }
        return status;
    }

    private EscapeStatus getMaxStatus(Value v) {
        if (v == null) return EscapeStatus.STACK;
        EscapeStatus max = EscapeStatus.STACK;
        for (AllocationSite site : aliasResult.getPointsTo(v).getSites()) {
            EscapeStatus st = siteStatus.getOrDefault(site, EscapeStatus.STACK);
            if (st.ordinal() > max.ordinal()) max = st;
        }
        return max;
    }

    private void markEscaped(Value v, EscapeStatus status) {
        if (v == null) return;
        for (AllocationSite site : aliasResult.getPointsTo(v).getSites()) {
            markEscaped(site, status);
        }
    }

    private void markEscaped(AllocationSite site, EscapeStatus status) {
        EscapeStatus current = siteStatus.getOrDefault(site, EscapeStatus.STACK);
        if (status.ordinal() > current.ordinal()) {
            siteStatus.put(site, status);
            changed = true;
        }
    }

    private void updateFieldStatus(AllocationSite site, String field,
                                   EscapeStatus status) {
        Map<String, EscapeStatus> map =
            fieldStatus.computeIfAbsent(site, k -> new HashMap<>());
        EscapeStatus existing = map.getOrDefault(field, EscapeStatus.STACK);
        if (status.ordinal() > existing.ordinal()) {
            map.put(field, status);
        }
    }

    private EscapeStatus maxEscape(EscapeStatus a, EscapeStatus b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private boolean isVolatileField(String owner, String fieldName) {
        if (owner == null || owner.isEmpty() || fieldName == null || fieldName.isEmpty()) return false;
        FieldNode field = resolver.getField(owner, fieldName);
        return field != null && (field.getAccess() & Opcodes.ACC_VOLATILE) != 0;
    }
}
package io.github.kubyk01.application.service.analyzer.aliasanalysis;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToGraph;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;
import static io.github.kubyk01.util.LlvmUtil.isAllocation;

@Slf4j
public class InterproceduralPointsTo {

    private static final long MAX_EVALUATIONS = 1_000_000L;
    private final Module module;
    private final Map<String, FunctionSummary> summaries;
    private final PointsToGraph graph = new PointsToGraph();
    private final Map<Value, AllocationSite> allocationSites = new HashMap<>();

    private boolean changed;

    /**
     * Separate flag telling that the contents of local slots changed during
     * the current evaluation of a function. Slots are not {@link Value}s, so
     * {@link PointsToGraph#merge(Value, PointsToSet)} does not track them and
     * this flag has to be maintained manually.
     *
     * <p>This flag makes the main loop re-enqueue the very same function
     * (see {@code analyze()}), because a LOAD that appears earlier than the
     * matching STORE in the block traversal order cannot see the updated slot
     * within a single evaluation.</p>
     */
    private boolean slotsChanged;

    /**
     * State of the local slots, per function. Key: the function, value: a
     * map of "JVM slot index → union of the points-to sets of every value
     * ever stored into that slot".
     *
     * <p>This is the second (non-SSA) propagation channel needed by LOAD
     * instructions that the SSA transformer keeps in the IR as "unsafe" (see
     * {@code SSATransformer.identifyUnsafeLocals}). Such LOADs read from the
     * slot rather than from an SSA version, and without this channel their
     * result would always have an empty points-to set.</p>
     */
    private final Map<Function, Map<Integer, PointsToSet>> localSlots = new HashMap<>();

    private Map<Integer, PointsToSet> slotsFor(Function func) {
        return localSlots.computeIfAbsent(func, k -> new HashMap<>());
    }

    public InterproceduralPointsTo(Module module, Map<String, FunctionSummary> summaries) {
        this.module = module;
        this.summaries = summaries;
    }

    public PointsToGraph analyze() {
        collectAllocationSites();

        for (Function func : module.getFunctions()) {
            for (Parameter p : func.getParameters()) {
                graph.get(p);
            }
        }

        // ---- Caller map: callee constant → set of caller functions. ----
        Map<String, Set<Function>> callers = new HashMap<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    String callee = extractCalleeName(inst);
                    if (callee != null) {
                        callers.computeIfAbsent(callee, k -> new HashSet<>()).add(func);
                    }
                }
            }
        }

        // ---- Worklist initialisation ----------------------------------
        Deque<Function> worklist = new ArrayDeque<>();
        Set<Function> inWorklist = new HashSet<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            worklist.add(func);
            inWorklist.add(func);
        }

        long processed = 0;
        long enqueued = 0;
        long lastLogEvals = 0;

        while (!worklist.isEmpty()) {
            if (processed >= MAX_EVALUATIONS) {
                System.err.println("PointsTo: WARNING: worklist exceeded "
                    + MAX_EVALUATIONS + " evaluations; "
                    + worklist.size() + " functions still queued. "
                    + "Stopping (results may be imprecise).");
                break;
            }

            Function func = worklist.poll();
            inWorklist.remove(func);
            processed++;

            changed = false;
            slotsChanged = false;
            processFunction(func);

            if (changed || slotsChanged) {
                // If the slots of this very function grew, a LOAD of the same
                // function may appear before the corresponding STORE in the
                // block traversal order (back edge or non-topological block
                // order) and therefore may not have seen the update yet.
                // Re-enqueuing the function guarantees that every LOAD ends up
                // seeing the up-to-date slot contents.
                if (slotsChanged) {
                    if (inWorklist.add(func)) {
                        worklist.add(func);
                        enqueued++;
                    }
                }
                if (changed) {
                    Set<Function> funcCallers = callers.get(func.getName());
                    if (funcCallers != null) {
                        for (Function caller : funcCallers) {
                            if (inWorklist.add(caller)) {
                                worklist.add(caller);
                                enqueued++;
                            }
                        }
                    }
                }
            }

            if (processed - lastLogEvals >= 100_000) {
                lastLogEvals = processed;
                System.out.println("PointsTo: " + processed
                    + " evaluations, " + enqueued + " enqueues, "
                    + worklist.size() + " queued");
            }
        }

        System.out.println("PointsTo: converged after " + processed
            + " function evaluations, " + enqueued + " enqueues");
        return graph;
    }

    private void collectAllocationSites() {
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            int idx = 0;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (isAllocation(inst.getOpcode())) {
                        AllocationSite site = AllocationSite.fromInstruction(inst, func.getName(), idx);
                        allocationSites.put(inst.getResult(), site);
                        graph.putAllocationSite(site, inst.getResult());
                        graph.add(inst.getResult(), site);
                    }
                    idx++;
                }
            }
        }
    }

    private void processFunction(Function func) {
        // Seed the slots with the parameters.
        //
        // Parameters are never materialised through STOREs in SSA-IR:
        // MethodTranslator puts the parameter straight into Frame.local and
        // every read becomes a direct reference to the parameter's SSA
        // value. Therefore any LOAD that survives on an "unsafe" path
        // (unsafeLocals) reads a slot that has no STORE in the function body
        // at all. Without this seeding such a LOAD would get an empty
        // points-to set and would break escape/liveness again.
        Map<Integer, PointsToSet> slots = slotsFor(func);
        for (Parameter p : func.getParameters()) {
            PointsToSet paramPts = graph.get(p);
            if (paramPts.isEmpty()) continue;
            PointsToSet slotPts = slots.computeIfAbsent(
                p.getIndex(), k -> new PointsToSet());
            int oldSize = slotPts.getSites().size();
            slotPts.addAll(paramPts);
            if (slotPts.getSites().size() > oldSize) {
                slotsChanged = true;
            }
        }

        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                processInstruction(inst, func);
            }
            Terminator term = block.getTerminator();
            if (term != null) processTerminator();
        }
    }

    private void processInstruction(Instruction inst, Function currentFunc) {
        Opcode op = inst.getOpcode();
        switch (op) {
            case LOAD: {
                // Carry the points-to set from the slot to the result of the
                // LOAD. This is critical for the "unsafe" LOADs that the SSA
                // transformer keeps in the IR after a block with a potentially
                // throwing instruction (see SSATransformer.identifyUnsafeLocals).
                //
                // Without this branch the result of a LOAD always has an empty
                // points-to set, and the downstream analyses (escape, liveness,
                // lifetime) treat an object read back through a LOAD as dead
                // right after its first write into the slot. That is exactly how
                // the use-after-free in ProviderConfig.getProvider arose.
                Value result = inst.getResult();
                if (result != null) {
                    int idx = inst.getLocalIndex();
                    if (idx >= 0) {
                        PointsToSet slotPts = slotsFor(currentFunc).get(idx);
                        if (slotPts != null && !slotPts.isEmpty()) {
                            changed |= graph.merge(result, slotPts);
                        }
                    }
                }
                break;
            }
            case STORE: {
                if (!inst.getOperands().isEmpty()) {
                    Value stored = inst.getOperands().getFirst();
                    if (stored != null) {
                        PointsToSet storedPts = graph.get(stored);

                        // SSA channel: the result of a STORE is the new SSA
                        // version of the slot and the information propagates
                        // into it directly. This covers every safe slot, where
                        // the SSA transformer has removed the LOAD.
                        Value local = inst.getResult();
                        if (local != null) {
                            changed |= graph.merge(local, storedPts);
                        }

                        // Local-slot channel: accumulate the same set in the
                        // slot's bucket in parallel so that the LOADs which
                        // survived can read it back.
                        int idx = inst.getLocalIndex();
                        if (idx >= 0) {
                            PointsToSet slotPts = slotsFor(currentFunc)
                                .computeIfAbsent(idx, k -> new PointsToSet());
                            int oldSize = slotPts.getSites().size();
                            slotPts.addAll(storedPts);
                            if (slotPts.getSites().size() > oldSize) {
                                slotsChanged = true;
                            }
                        }
                    }
                }
                break;
            }
            case PHI: {
                Value result = inst.getResult();
                if (result == null) break;
                for (Value operand : inst.getOperands()) {
                    if (operand == null) continue;
                    changed |= graph.merge(result, graph.get(operand));
                }
                break;
            }
            case GET_FIELD: {
                if (inst.getOperands().size() >= 2) {
                    Value base = inst.getOperands().getFirst();
                    Value result = inst.getResult();
                    if (result != null) {
                        String field = extractFieldName(inst);
                        PointsToSet basePts = graph.get(base);
                        PointsToSet fieldPts = graph.getFieldPointsToForSites(basePts, field);
                        changed |= graph.merge(result, fieldPts);
                    }
                }
                break;
            }
            case PUT_FIELD: {
                if (inst.getOperands().size() >= 3) {
                    Value base = inst.getOperands().get(0);
                    Value rhs = inst.getOperands().get(2);
                    String field = extractFieldName(inst);
                    PointsToSet basePts = graph.get(base);
                    PointsToSet rhsPts = graph.get(rhs);
                    if (basePts.isEmpty()) {
                        graph.mergeFieldPointsTo(AllocationSite.UNKNOWN, field, rhsPts);
                    } else {
                        for (AllocationSite site : basePts.getSites()) {
                            graph.mergeFieldPointsTo(site, field, rhsPts);
                        }
                    }
                }
                break;
            }
            case ALOAD: {
                if (inst.getOperands().size() >= 2) {
                    Value array = inst.getOperands().getFirst();
                    Value result = inst.getResult();
                    if (result != null) {
                        PointsToSet arrayPts = graph.get(array);
                        PointsToSet elemPts = graph.getFieldPointsToForSites(arrayPts, "[]");
                        changed |= graph.merge(result, elemPts);
                    }
                }
                break;
            }
            case ASTORE: {
                if (inst.getOperands().size() >= 3) {
                    Value array = inst.getOperands().get(0);
                    Value value = inst.getOperands().get(2);
                    PointsToSet arrayPts = graph.get(array);
                    PointsToSet valuePts = graph.get(value);
                    if (arrayPts.isEmpty()) {
                        graph.mergeArrayElementPointsTo(AllocationSite.UNKNOWN, valuePts);
                    } else {
                        for (AllocationSite site : arrayPts.getSites()) {
                            graph.mergeArrayElementPointsTo(site, valuePts);
                        }
                    }
                }
                break;
            }
            case GET_STATIC: {
                Value result = inst.getResult();
                if (result != null) {
                    String field = extractFieldName(inst);
                    PointsToSet fieldPts = graph.getStaticFieldPointsTo(field);
                    changed |= graph.merge(result, fieldPts);
                }
                break;
            }
            case PUT_STATIC: {
                if (inst.getOperands().size() >= 2) {
                    Value rhs = inst.getOperands().get(1);
                    String field = extractFieldName(inst);
                    PointsToSet rhsPts = graph.get(rhs);
                    graph.mergeStaticFieldPointsTo(field, rhsPts);
                }
                break;
            }
            case CALL:
            case VIRTUAL_CALL:
            case INTERFACE_CALL:
            case STATIC_CALL:
            case SPECIAL_CALL: {
                processCall(inst, currentFunc);
                break;
            }
            default:
        }
    }

    private void processCall(Instruction callInst, Function currentFunc) {
        String calleeName = extractCalleeName(callInst);
        if (calleeName == null) return;
        FunctionSummary summary = summaries.get(calleeName);
        if (summary == null) {
            Value ret = callInst.getResult();
            if (ret != null) {
                PointsToSet pts = new PointsToSet();
                pts.add(AllocationSite.UNKNOWN);
                changed |= graph.merge(ret, pts);
            }
            return;
        }

        List<Value> args = getCallArguments(callInst);
        Value returnValue = callInst.getResult();

        // 1. Return value
        if (returnValue != null) {
            PointsToSet resultPts = new PointsToSet();
            for (AllocationSite site : summary.getReturnedAllocations()) {
                resultPts.add(site);
            }
            for (int i = 0; i < args.size(); i++) {
                if (summary.getParamsReturned().contains(i)) {
                    resultPts.addAll(graph.get(args.get(i)));
                }
            }
            if (!resultPts.isEmpty()) {
                changed |= graph.merge(returnValue, resultPts);
            } else if (summary.isReturnsObject()) {
                resultPts.add(AllocationSite.UNKNOWN);
                changed |= graph.merge(returnValue, resultPts);
            }
        }

        // 2. Parameter field writes — coarsened summary.
        //
        // The callee's summary carries only the set of field names it
        // writes into each parameter, not the exact set of sites. We
        // therefore widen the field's points-to on every base site
        // reachable from the caller's argument to UNKNOWN. This is
        // sound (no site is ever omitted) and finite (a single UNKNOWN
        // entry per (base site, field) pair).
        for (Map.Entry<Integer, Set<String>> entry
            : summary.getParamsFieldWrites().entrySet()) {
            int paramIndex = entry.getKey();
            if (paramIndex >= args.size()) continue;
            Value arg = args.get(paramIndex);
            PointsToSet argPts = graph.get(arg);
            if (argPts.isEmpty()) continue;
            for (String field : entry.getValue()) {
                PointsToSet ptsToWrite = new PointsToSet();
                ptsToWrite.add(AllocationSite.UNKNOWN);
                for (AllocationSite baseSite : argPts.getSites()) {
                    graph.mergeFieldPointsTo(baseSite, field, ptsToWrite);
                }
            }
        }

        // 3. Static field writes — coarsened summary. Same widening.
        for (String field : summary.getStaticFieldWrites()) {
            PointsToSet ptsToWrite = new PointsToSet();
            ptsToWrite.add(AllocationSite.UNKNOWN);
            graph.mergeStaticFieldPointsTo(field, ptsToWrite);
        }
    }

    private void processTerminator() {
        // terminator instructions do not change points-to
    }
}
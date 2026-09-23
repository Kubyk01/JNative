package io.github.kubyk01.application.service.analyzer.aliasanalysis;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;
import static io.github.kubyk01.util.LlvmUtil.isAllocation;

@Slf4j
public class SummaryBuilder {

    private static final int MAX_SCC_ITERATIONS = 2000;

    private final Module module;
    private final Map<String, FunctionSummary> summaries = new ConcurrentHashMap<>();
    private final Scheduler scheduler;

    public SummaryBuilder(Module module, Scheduler scheduler) {
        this.module = module;
        this.scheduler = scheduler;
    }

    public Map<String, FunctionSummary> build() {
        List<Function> allLive = new ArrayList<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() != null) {
                allLive.add(f);
            }
        }
        if (allLive.isEmpty()) {
            return summaries;
        }

        // ---- Call graph ------------------------------------------------
        //
        // byName   : mangled function name -> Function, for callee lookup
        // callees  : Function -> distinct callee Functions that live in the
        //            module (external callees are handled conservatively by
        //            processCall and do not participate in propagation)
        // callers  : mangled callee name -> set of caller Functions, used to
        //            re-enqueue intra-SCC dependents when a summary changes
        //
        // The callee lists are pre-deduplicated so that a function with a
        // hundred calls to the same target does not inflate the SCC walk.
        Map<String, Function> byName = new HashMap<>(allLive.size() * 2);
        for (Function f : allLive) {
            byName.put(f.getName(), f);
        }

        Map<Function, List<Function>> callees = new HashMap<>(allLive.size() * 2);
        Map<String, Set<Function>> callers = new HashMap<>(allLive.size() * 2);

        for (Function f : allLive) {
            List<Function> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (BasicBlock block : f.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    String calleeName = extractCalleeName(inst);
                    if (calleeName == null || !seen.add(calleeName)) {
                        continue;
                    }
                    Function callee = byName.get(calleeName);
                    if (callee != null) {
                        out.add(callee);
                    }
                    callers.computeIfAbsent(calleeName, k -> new HashSet<>(4)).add(f);
                }
            }
            callees.put(f, out);
        }

        // ---- SCCs in reverse topological order -------------------------
        //
        // Tarjan's algorithm outputs each SCC the moment its root's DFS
        // frame unwinds. Because the DFS only unwinds a frame after every
        // reachable node has been visited, the SCCs come out with every
        // successor SCC already emitted. With caller→callee edges this is
        // the bottom-up order the fixed point requires.
        List<List<Function>> sccs = computeSCCs(allLive, callees);

        // ---- Process SCCs bottom-up -----------------------------------
        int totalProcessed = 0;
        int maxSccSize = 0;
        for (List<Function> scc : sccs) {
            if (scc.size() > maxSccSize) maxSccSize = scc.size();
            totalProcessed += processSCC(scc, callers);
        }

        System.out.println("SummaryBuilder: converged after " + sccs.size()
            + " SCC(s) (largest " + maxSccSize + " function(s)), "
            + totalProcessed + " total evaluations, "
            + summaries.size() + " summaries");
        return summaries;
    }

    // =====================================================================
    //  Per-SCC fixed point
    // =====================================================================

    /**
     * Runs the local fixed point for one SCC. All callees outside the SCC
     * already have their final summaries in {@link #summaries} (guaranteed
     * by the Tarjan ordering), so the only functions that can invalidate a
     * summary here are the SCC's own members, and only they are re-enqueued.
     *
     * @return the number of {@link #analyzeFunction} invocations performed.
     */
    private int processSCC(List<Function> scc, Map<String, Set<Function>> callers) {
        if (scc.isEmpty()) {
            return 0;
        }

        // Fast path: a singleton with no self-edge (no self-recursion) can
        // be committed on the first pass. Every one of the 24000+ functions
        // in a typical module falls into this case, so making it explicit
        // keeps the per-function overhead at one HashSet alloc and one
        // analyzeFunction call.
        if (scc.size() == 1) {
            Function f = scc.getFirst();
            FunctionSummary newSum = analyzeFunction(f);
            FunctionSummary oldSum = summaries.get(f.getName());
            if (oldSum == null || !oldSum.equals(newSum)) {
                summaries.put(f.getName(), newSum);
            }
            return 1;
        }

        Set<Function> sccSet = new HashSet<>(scc);
        Set<Function> pending = new HashSet<>(scc);
        int totalProcessed = 0;
        int iteration = 0;

        while (!pending.isEmpty()) {
            iteration++;
            if (iteration > MAX_SCC_ITERATIONS) {
                System.err.println("SummaryBuilder: WARNING: SCC of size "
                    + scc.size() + " did not converge after "
                    + MAX_SCC_ITERATIONS + " iterations; "
                    + pending.size() + " functions still changing. "
                    + "Continuing with the current summaries "
                    + "(some field-write information may be missing).");
                break;
            }

            List<Function> batch = new ArrayList<>(pending);
            pending.clear();
            totalProcessed += batch.size();

            // Parallel analysis of the batch against the current snapshot
            // of callee summaries. The .block() barrier guarantees that no
            // worker observes a partially committed state.
            List<FunctionSummary> fresh = Flux.fromIterable(batch)
                .parallel()
                .runOn(scheduler)
                .map(this::analyzeFunction)
                .sequential()
                .collectList()
                .block();

            if (fresh == null || fresh.size() != batch.size()) {
                throw new IllegalStateException(
                    "SummaryBuilder: reactor pipeline returned "
                        + (fresh == null
                        ? "null"
                        : fresh.size() + " summaries for "
                        + batch.size() + " functions"));
            }

            // Sequential commit: the only place summaries are written.
            // Re-enqueue only callers that belong to this SCC; callers in
            // other SCCs will observe the final summary when their own SCC
            // is processed later, so re-enqueuing them here would be wasted
            // work and could not terminate until every SCC had been visited.
            int committed = 0;
            for (int i = 0; i < batch.size(); i++) {
                Function f = batch.get(i);
                FunctionSummary newSum = fresh.get(i);
                FunctionSummary oldSum = summaries.get(f.getName());
                if (oldSum == null || !oldSum.equals(newSum)) {
                    summaries.put(f.getName(), newSum);
                    committed++;

                    Set<Function> funcCallers = callers.get(f.getName());
                    if (funcCallers != null) {
                        for (Function caller : funcCallers) {
                            if (sccSet.contains(caller)) {
                                pending.add(caller);
                            }
                        }
                    }
                }
            }

            // No summary changed in this pass: the SCC is at its fixed point.
            if (committed == 0) {
                break;
            }
        }

        return totalProcessed;
    }

    // =====================================================================
    //  Tarjan's SCC algorithm — iterative, to survive deep JDK hierarchies
    // =====================================================================

    /**
     * One level of the explicit DFS stack used in place of the call stack.
     * The iterator field holds the position within the current node's
     * successor list so that a resumed frame picks up exactly where it left
     * off.
     */
    private static final class SCCFrame {
        final Function node;
        Iterator<Function> iter;

        SCCFrame(Function node) {
            this.node = node;
        }
    }

    /**
     * Tarjan's strongly-connected-components algorithm in its iterative
     * form. The recursion is emulated with an explicit {@link Deque} of
     * {@link SCCFrame}s so that call graphs deeper than the JVM's default
     * call stack do not overflow — a full JDK dependency graph routinely
     * exceeds 10&nbsp;000 frames of depth.
     *
     * <p>The output order is the reverse topological order of the
     * condensation DAG: sink SCCs (no outgoing edges to unvisited nodes)
     * are emitted first, and each SCC is emitted only after every SCC
     * reachable from it has already been emitted. With caller→callee
     * edges this is the order the fixed point needs.</p>
     */
    private List<List<Function>> computeSCCs(List<Function> functions,
                                             Map<Function, List<Function>> callees) {
        Map<Function, Integer> index = new HashMap<>(functions.size() * 2);
        Map<Function, Integer> lowlink = new HashMap<>(functions.size() * 2);
        Deque<Function> sccStack = new ArrayDeque<>();
        Set<Function> onStack = new HashSet<>();
        List<List<Function>> sccs = new ArrayList<>();
        int[] counter = {0};

        for (Function start : functions) {
            if (index.containsKey(start)) {
                continue;
            }

            Deque<SCCFrame> dfsStack = new ArrayDeque<>();
            dfsStack.push(new SCCFrame(start));

            while (!dfsStack.isEmpty()) {
                SCCFrame frame = dfsStack.peek();
                Function v = frame.node;

                if (frame.iter == null) {
                    // First visit: assign the pre-order index and lowlink,
                    // push onto the SCC stack, and start walking successors.
                    index.put(v, counter[0]);
                    lowlink.put(v, counter[0]);
                    counter[0]++;
                    sccStack.push(v);
                    onStack.add(v);
                    frame.iter = callees.getOrDefault(v, Collections.emptyList())
                        .iterator();
                }

                boolean descended = false;
                while (frame.iter.hasNext()) {
                    Function w = frame.iter.next();
                    if (!index.containsKey(w)) {
                        dfsStack.push(new SCCFrame(w));
                        descended = true;
                        break;
                    }
                    if (onStack.contains(w)) {
                        int newLow = Math.min(lowlink.get(v), index.get(w));
                        lowlink.put(v, newLow);
                    }
                }
                if (descended) {
                    continue;
                }

                // All successors visited: finish v. Propagate its lowlink
                // up to the parent frame, then check whether v is the root
                // of a new SCC.
                dfsStack.pop();
                if (!dfsStack.isEmpty()) {
                    Function parent = dfsStack.peek().node;
                    int newLow = Math.min(lowlink.get(parent), lowlink.get(v));
                    lowlink.put(parent, newLow);
                }

                if (lowlink.get(v).equals(index.get(v))) {
                    List<Function> scc = new ArrayList<>();
                    Function w;
                    do {
                        w = sccStack.pop();
                        onStack.remove(w);
                        scc.add(w);
                    } while (w != v);
                    sccs.add(scc);
                }
            }
        }

        return sccs;
    }

    // =====================================================================
    //  Per-function summary analysis (semantics unchanged)
    // =====================================================================

    /**
     * Computes the summary of one function against the current snapshot of
     * callee summaries. Called once per function per pass within its SCC;
     * deterministic given the snapshot, which is what makes the SCC
     * fixed point converge.
     *
     * <p>The instruction walk has two phases that are <em>not</em>
     * interchangeable and must not be fused:</p>
     *
     * <ol>
     *   <li>Seed {@code localPointsTo} with every allocation site produced
     *       by the function. This is what gives a NEW's result a non-empty
     *       points-to set before any instruction reads it, and it is what
     *       lets {@code returnedAllocations} record concrete sites rather
     *       than falling back to the UNKNOWN sentinel.</li>
     *   <li>Walk the instructions in block order, processing each one.
     *       The block order is the function's own order — the same order
     *       {@code InterproceduralPointsTo} uses — so the two analyses
     *       agree on which value reaches which instruction.</li>
     * </ol>
     */
    private FunctionSummary analyzeFunction(Function func) {
        Map<Value, Set<AllocationSite>> localPointsTo = new HashMap<>();
        Map<Integer, Set<AllocationSite>> paramPointsTo = new HashMap<>();
        for (Parameter p : func.getParameters()) {
            paramPointsTo.put(p.getIndex(), new HashSet<>());
        }

        // Phase 1: seed allocation sites.
        int idx = 0;
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (isAllocation(inst.getOpcode())) {
                    AllocationSite site = AllocationSite.fromInstruction(
                        inst, func.getName(), idx);
                    if (inst.getResult() != null) {
                        Set<AllocationSite> pts = new HashSet<>();
                        pts.add(site);
                        localPointsTo.put(inst.getResult(), pts);
                    }
                }
                idx++;
            }
        }

        Set<Integer> paramsRead = new HashSet<>();
        Set<Integer> paramsWritten = new HashSet<>();
        Set<Integer> paramsEscaped = new HashSet<>();
        Set<Integer> paramsReturned = new HashSet<>();
        Set<Integer> paramsDestroyed = new HashSet<>();
        Set<String> fieldsRead = new HashSet<>();
        Set<String> fieldsWritten = new HashSet<>();
        boolean[] flags = new boolean[4];

        Set<AllocationSite> returnedAllocations = new HashSet<>();
        Map<Integer, Set<String>> paramsFieldWrites = new HashMap<>();
        Set<String> staticFieldWrites = new HashSet<>();

        // Phase 2: instruction walk.
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                processInstruction(inst, func, localPointsTo, paramPointsTo,
                    paramsRead, paramsWritten, paramsEscaped, paramsReturned,
                    fieldsRead, fieldsWritten, flags,
                    paramsFieldWrites, staticFieldWrites,
                    paramsDestroyed);
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                processTerminator(term, localPointsTo,
                    paramsEscaped, paramsReturned, flags, returnedAllocations);
            }
        }

        return FunctionSummary.builder()
            .paramsRead(paramsRead)
            .paramsWritten(paramsWritten)
            .paramsEscaped(paramsEscaped)
            .paramsReturned(paramsReturned)
            .paramsDestroyed(paramsDestroyed)
            .fieldsRead(fieldsRead)
            .fieldsWritten(fieldsWritten)
            .returnsObject(flags[0])
            .readsStaticFields(flags[1])
            .writesStaticFields(flags[2])
            .escapesGlobally(flags[3])
            .returnedAllocations(returnedAllocations)
            .paramsFieldWrites(paramsFieldWrites)
            .staticFieldWrites(staticFieldWrites)
            .build();
    }

    private void processInstruction(Instruction inst, Function func,
                                    Map<Value, Set<AllocationSite>> localPointsTo,
                                    Map<Integer, Set<AllocationSite>> paramPointsTo,
                                    Set<Integer> paramsRead, Set<Integer> paramsWritten,
                                    Set<Integer> paramsEscaped, Set<Integer> paramsReturned,
                                    Set<String> fieldsRead, Set<String> fieldsWritten,
                                    boolean[] flags,
                                    Map<Integer, Set<String>> paramsFieldWrites,
                                    Set<String> staticFieldWrites,
                                    Set<Integer> paramsDestroyed) {
        Opcode op = inst.getOpcode();
        switch (op) {
            case LOAD: {
                int idx = inst.getLocalIndex();
                if (idx >= 0 && idx < func.getParameters().size()) {
                    paramsRead.add(idx);
                    Set<AllocationSite> pts =
                        paramPointsTo.getOrDefault(idx, new HashSet<>());
                    if (inst.getResult() != null) {
                        localPointsTo.put(inst.getResult(), new HashSet<>(pts));
                    }
                }
                break;
            }
            case STORE: {
                if (!inst.getOperands().isEmpty()) {
                    Value stored = inst.getOperands().getFirst();
                    int idx = inst.getLocalIndex();
                    Set<AllocationSite> pts =
                        localPointsTo.getOrDefault(stored, new HashSet<>());
                    if (idx >= 0 && idx < func.getParameters().size()) {
                        paramsWritten.add(idx);
                        paramPointsTo.put(idx, new HashSet<>(pts));
                    } else {
                        if (inst.getResult() != null) {
                            localPointsTo.put(inst.getResult(), new HashSet<>(pts));
                        }
                    }
                }
                break;
            }
            case GET_FIELD: {
                if (inst.getOperands().size() >= 2) {
                    String field = extractFieldName(inst);
                    fieldsRead.add(field);
                }
                break;
            }
            case PUT_FIELD: {
                if (inst.getOperands().size() >= 3) {
                    Value base = inst.getOperands().get(0);
                    Value rhs = inst.getOperands().get(2);
                    String field = extractFieldName(inst);
                    fieldsWritten.add(field);

                    // Coarsened: record only the field name; the site set
                    // that used to be added here is left to
                    // InterproceduralPointsTo, which widens the field's
                    // points-to to UNKNOWN when it sees a write reported
                    // through this summary.
                    if (base instanceof Parameter p) {
                        int pidx = p.getIndex();
                        paramsWritten.add(pidx);
                        paramsEscaped.add(pidx);
                        paramsFieldWrites
                            .computeIfAbsent(pidx, k -> new HashSet<>())
                            .add(field);
                    }
                    if (rhs instanceof Parameter p) {
                        paramsEscaped.add(p.getIndex());
                    }
                }
                break;
            }
            case ALOAD: {
                if (inst.getOperands().size() >= 2) {
                    Value array = inst.getOperands().getFirst();
                    Value result = inst.getResult();
                    if (result != null) {
                        Set<AllocationSite> pts = new HashSet<>();
                        pts.add(AllocationSite.UNKNOWN);
                        localPointsTo.put(result, pts);
                        fieldsRead.add("[]");
                    }
                    if (array instanceof Parameter p) {
                        paramsRead.add(p.getIndex());
                    }
                }
                break;
            }
            case ASTORE: {
                if (inst.getOperands().size() >= 3) {
                    Value array = inst.getOperands().get(0);
                    Value value = inst.getOperands().get(2);
                    if (array instanceof Parameter p) {
                        int pidx = p.getIndex();
                        paramsWritten.add(pidx);
                        paramsEscaped.add(pidx);
                        paramsFieldWrites
                            .computeIfAbsent(pidx, k -> new HashSet<>())
                            .add("[]");
                    }
                    if (value instanceof Parameter p) {
                        paramsEscaped.add(p.getIndex());
                    }
                    fieldsWritten.add("[]");
                }
                break;
            }
            case GET_STATIC: {
                flags[1] = true;
                break;
            }
            case PUT_STATIC: {
                flags[2] = true;
                flags[3] = true;
                String field = extractFieldName(inst);
                staticFieldWrites.add(field);
                break;
            }
            case CALL:
            case VIRTUAL_CALL:
            case INTERFACE_CALL:
            case STATIC_CALL:
            case SPECIAL_CALL: {
                processCall(inst, localPointsTo,
                    paramsRead, paramsWritten, paramsEscaped, paramsReturned,
                    flags, paramsFieldWrites, staticFieldWrites, paramsDestroyed);
                break;
            }
            default:
        }
    }

    private void processCall(Instruction callInst,
                             Map<Value, Set<AllocationSite>> localPointsTo,
                             Set<Integer> paramsRead, Set<Integer> paramsWritten,
                             Set<Integer> paramsEscaped, Set<Integer> paramsReturned,
                             boolean[] flags,
                             Map<Integer, Set<String>> paramsFieldWrites,
                             Set<String> staticFieldWrites,
                             Set<Integer> paramsDestroyed) {
        String calleeName = extractCalleeName(callInst);
        if (calleeName == null) {
            return;
        }

        if (calleeName.startsWith("__destruct_")) {
            List<Value> args = getCallArguments(callInst);
            if (!args.isEmpty()) {
                Value arg = args.getFirst();
                if (arg instanceof Parameter p) {
                    paramsDestroyed.add(p.getIndex());
                }
            }
            return;
        }

        FunctionSummary calleeSum = summaries.get(calleeName);
        if (calleeSum == null) {
            // External or not-yet-analyzed callee: conservatively assume
            // every argument escapes and the return value is an object.
            List<Value> args = getCallArguments(callInst);
            for (Value arg : args) {
                if (arg instanceof Parameter p) {
                    paramsEscaped.add(p.getIndex());
                }
            }
            if (callInst.getResult() != null) {
                flags[0] = true;
            }
            return;
        }

        List<Value> args = getCallArguments(callInst);
        for (int i = 0; i < args.size(); i++) {
            Value arg = args.get(i);
            if (calleeSum.getParamsRead().contains(i)) {
                if (arg instanceof Parameter p) paramsRead.add(p.getIndex());
            }
            if (calleeSum.getParamsWritten().contains(i)) {
                if (arg instanceof Parameter p) paramsWritten.add(p.getIndex());
            }
            if (calleeSum.getParamsEscaped().contains(i)) {
                if (arg instanceof Parameter p) paramsEscaped.add(p.getIndex());
            }
            if (calleeSum.getParamsReturned().contains(i)) {
                if (arg instanceof Parameter p) paramsReturned.add(p.getIndex());
            }
            if (calleeSum.getParamsDestroyed().contains(i)) {
                if (arg instanceof Parameter p) paramsDestroyed.add(p.getIndex());
            }
        }

        // Propagate the callee's parameter-field-write names through the
        // mapped argument indices. Because both maps hold only field
        // names, the union is small and converges in one step per edge.
        for (Map.Entry<Integer, Set<String>> entry
            : calleeSum.getParamsFieldWrites().entrySet()) {
            int paramIndex = entry.getKey();
            if (paramIndex >= args.size()) continue;
            Value arg = args.get(paramIndex);
            if (arg instanceof Parameter p) {
                int localIdx = p.getIndex();
                Set<String> destSet = paramsFieldWrites
                    .computeIfAbsent(localIdx, k -> new HashSet<>());
                destSet.addAll(entry.getValue());
                paramsEscaped.add(localIdx);
            }
        }

        staticFieldWrites.addAll(calleeSum.getStaticFieldWrites());

        if (calleeSum.isWritesStaticFields()) {
            flags[2] = true;
            flags[3] = true;
        }
        if (calleeSum.isReadsStaticFields()) {
            flags[1] = true;
        }
        if (calleeSum.isEscapesGlobally()) {
            flags[3] = true;
        }

        Value ret = callInst.getResult();
        if (ret != null && (calleeSum.isReturnsObject()
            || !calleeSum.getReturnedAllocations().isEmpty())) {
            flags[0] = true;
            Set<AllocationSite> retPts =
                new HashSet<>(calleeSum.getReturnedAllocations());
            for (int i = 0; i < args.size(); i++) {
                if (calleeSum.getParamsReturned().contains(i)) {
                    Value arg = args.get(i);
                    retPts.addAll(localPointsTo.getOrDefault(arg, new HashSet<>()));
                }
            }
            localPointsTo.put(ret, retPts);
        }
    }

    private void processTerminator(Terminator term,
                                   Map<Value, Set<AllocationSite>> localPointsTo,
                                   Set<Integer> paramsEscaped, Set<Integer> paramsReturned,
                                   boolean[] flags,
                                   Set<AllocationSite> returnedAllocations) {
        if (term instanceof ReturnTerminator rt) {
            Value retVal = rt.getValue();
            if (retVal != null && retVal.getType() != Type.VOID) {
                flags[0] = true;
                Set<AllocationSite> pts =
                    localPointsTo.getOrDefault(retVal, new HashSet<>());
                returnedAllocations.addAll(pts);
                if (retVal instanceof Parameter p) {
                    paramsEscaped.add(p.getIndex());
                    paramsReturned.add(p.getIndex());
                }
            }
        }
    }
}
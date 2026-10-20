package io.github.kubyk01.application.service.analyzer.escapeanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.ir.*;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.util.LlvmUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.kubyk01.util.LlvmUtil.canonicalCalleeKey;
import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldOwnerAndName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;
import static io.github.kubyk01.util.LlvmUtil.isLazyClinitWrapper;

@Slf4j
public class EscapeSummaryBuilder {

    private static final int MAX_SCC_ITERATIONS = 2000;
    private static final int EVAL_BATCH_SIZE = 2048;

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final Scheduler scheduler;
    private final NativeBodyEscapeAnalyzer nativeAnalyzer;
    private final Map<String, EscapeSummary> summaries = new ConcurrentHashMap<>();

    public EscapeSummaryBuilder(Module module,
                                AliasAnalysisResult aliasResult,
                                DependencyResolver resolver) {
        this(module, aliasResult, resolver, Schedulers.single());
    }

    public EscapeSummaryBuilder(Module module,
                                AliasAnalysisResult aliasResult,
                                DependencyResolver resolver,
                                Scheduler scheduler) {
        this.module = module;
        this.aliasResult = aliasResult;
        this.scheduler = (scheduler != null) ? scheduler : Schedulers.single();
        this.nativeAnalyzer = new NativeBodyEscapeAnalyzer(resolver);
    }

    public Map<String, EscapeSummary> build() {
        List<Function> live = new ArrayList<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() != null) live.add(f);
        }
        if (live.isEmpty()) return summaries;

        Map<String, Function> byName = new HashMap<>(live.size() * 2);
        for (Function f : live) byName.put(f.getName(), f);

        Map<Function, List<Function>> callees = new HashMap<>(live.size() * 2);
        Map<String, Set<Function>> callersOf = new HashMap<>(live.size() * 2);

        for (Function f : live) {
            List<Function> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (BasicBlock b : f.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    String callee = extractCalleeName(inst);
                    if (callee == null) continue;
                    String calleeKey = canonicalCalleeKey(callee);
                    if (isLazyClinitWrapper(calleeKey)) continue;
                    if (!seen.add(calleeKey)) continue;
                    Function calleeFunc = byName.get(calleeKey);
                    if (calleeFunc != null) out.add(calleeFunc);
                    callersOf.computeIfAbsent(calleeKey, k -> new HashSet<>(4)).add(f);
                }
            }
            callees.put(f, out);
        }

        List<List<Function>> sccs = computeSCCs(live, callees);

        int totalProcessed = 0;
        int maxSccSize = 0;
        for (List<Function> scc : sccs) {
            if (scc.size() > maxSccSize) maxSccSize = scc.size();
            totalProcessed += processSCC(scc, callersOf);
        }

        System.out.println("EscapeSummaryBuilder: converged after " + sccs.size()
            + " SCC(s) (largest " + maxSccSize + " function(s)), "
            + totalProcessed + " total evaluations, "
            + summaries.size() + " summaries");
        return summaries;
    }

    private int processSCC(List<Function> scc, Map<String, Set<Function>> callersOf) {
        if (scc.isEmpty()) return 0;

        if (scc.size() == 1) {
            Function f = scc.getFirst();
            EscapeSummary newSum = analyzeFunction(f, summaries);
            EscapeSummary oldSum = summaries.get(f.getName());
            if (oldSum != null) newSum.merge(oldSum);
            if (oldSum == null || !oldSum.equals(newSum)) {
                summaries.put(f.getName(), newSum);
            }
            return 1;
        }

        Set<Function> sccSet = new HashSet<>(scc);
        Set<Function> pending = new HashSet<>(scc);
        int totalProcessed = 0;
        int iteration = 0;

        for (Function f : scc) {
            summaries.putIfAbsent(f.getName(), EscapeSummary.builder().build());
        }

        while (!pending.isEmpty()) {
            iteration++;
            if (iteration > MAX_SCC_ITERATIONS) {
                System.err.println("EscapeSummaryBuilder: WARNING: SCC of size "
                    + scc.size() + " did not converge after "
                    + MAX_SCC_ITERATIONS + " iterations; "
                    + pending.size() + " functions still changing.");
                break;
            }

            List<Function> batch = new ArrayList<>(Math.min(EVAL_BATCH_SIZE, pending.size()));
            Iterator<Function> it = pending.iterator();
            while (it.hasNext() && batch.size() < EVAL_BATCH_SIZE) {
                batch.add(it.next());
                it.remove();
            }
            totalProcessed += batch.size();

            List<EscapeSummary> fresh = LlvmUtil.parallelMapOrdered(
                batch, scheduler,
                func -> analyzeFunction(func, summaries));

            int committed = 0;
            for (int i = 0; i < batch.size(); i++) {
                Function f = batch.get(i);
                EscapeSummary newSum = fresh.get(i);
                EscapeSummary oldSum = summaries.get(f.getName());
                if (oldSum != null) newSum.merge(oldSum);
                if (oldSum == null || !oldSum.equals(newSum)) {
                    summaries.put(f.getName(), newSum);
                    committed++;
                    Set<Function> funcCallers = callersOf.get(f.getName());
                    if (funcCallers != null) {
                        for (Function caller : funcCallers) {
                            if (sccSet.contains(caller)) pending.add(caller);
                        }
                    }
                }
            }

            if (committed == 0) break;
        }
        return totalProcessed;
    }

    // ---- Tarjan SCC (unchanged from the original) ---------------------

    private static final class SCCFrame {
        final Function node;
        Iterator<Function> iter;
        SCCFrame(Function node) { this.node = node; }
    }

    private List<List<Function>> computeSCCs(List<Function> functions,
                                             Map<Function, List<Function>> callees) {
        Map<Function, Integer> index = new HashMap<>(functions.size() * 2);
        Map<Function, Integer> lowlink = new HashMap<>(functions.size() * 2);
        Deque<Function> sccStack = new ArrayDeque<>();
        Set<Function> onStack = new HashSet<>();
        List<List<Function>> sccs = new ArrayList<>();
        int[] counter = {0};

        for (Function start : functions) {
            if (index.containsKey(start)) continue;
            Deque<SCCFrame> dfsStack = new ArrayDeque<>();
            dfsStack.push(new SCCFrame(start));

            while (!dfsStack.isEmpty()) {
                SCCFrame frame = dfsStack.peek();
                Function v = frame.node;

                if (frame.iter == null) {
                    index.put(v, counter[0]);
                    lowlink.put(v, counter[0]);
                    counter[0]++;
                    sccStack.push(v);
                    onStack.add(v);
                    frame.iter = callees.getOrDefault(v, Collections.emptyList()).iterator();
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
                        lowlink.put(v, Math.min(lowlink.get(v), index.get(w)));
                    }
                }
                if (descended) continue;

                dfsStack.pop();
                if (!dfsStack.isEmpty()) {
                    Function parent = dfsStack.peek().node;
                    lowlink.put(parent, Math.min(lowlink.get(parent), lowlink.get(v)));
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

    // ---- Per-function summary analysis --------------------------------

    private EscapeSummary analyzeFunction(Function func,
                                          Map<String, EscapeSummary> snapshot) {
        Set<Integer> paramsRead = new HashSet<>();
        Set<Integer> paramsWritten = new HashSet<>();
        Set<Integer> paramsDestroyed = new HashSet<>();
        Set<Integer> paramsEscapedToHeap = new HashSet<>();
        Set<Integer> paramsEscapedToStatic = new HashSet<>();
        Set<Integer> paramsEscapedToThread = new HashSet<>();
        Set<Integer> paramsReturned = new HashSet<>();
        Set<String> fieldsRead = new HashSet<>();
        Set<String> fieldsWritten = new HashSet<>();
        Set<String> staticFieldWrites = new HashSet<>();
        Set<AllocationSite> returnedAllocations = new HashSet<>();
        Map<Integer, Set<String>> paramsFieldWrites = new HashMap<>();
        Map<Integer, Set<Integer>> paramEscapeEdges = new HashMap<>();

        boolean[] flags = new boolean[2]; // [0]=returnsObject, [1]=unresolved

        Map<Integer, Set<Integer>> paramAliases = computeParamAliases(func);
        Map<Value, Value> valueOrigin = new HashMap<>();

        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                processInstruction(inst, func, paramAliases,
                    paramsRead, paramsWritten, paramsDestroyed,
                    paramsEscapedToHeap, paramsEscapedToStatic, paramsEscapedToThread,
                    paramsReturned,
                    fieldsRead, fieldsWritten, flags,
                    paramsFieldWrites, staticFieldWrites,
                    paramEscapeEdges, returnedAllocations, snapshot, valueOrigin);
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                processTerminator(term, paramAliases, paramsReturned,
                    flags, valueOrigin, returnedAllocations);
            }
        }

        EscapeSummary result = EscapeSummary.builder()
            .paramsRead(paramsRead)
            .paramsWritten(paramsWritten)
            .paramsDestroyed(paramsDestroyed)
            .paramsEscapedToHeap(paramsEscapedToHeap)
            .paramsEscapedToStatic(paramsEscapedToStatic)
            .paramsEscapedToThread(paramsEscapedToThread)
            .paramsReturned(paramsReturned)
            .fieldsRead(fieldsRead)
            .fieldsWritten(fieldsWritten)
            .returnsObject(flags[0])
            .unresolved(flags[1])
            .returnedAllocations(returnedAllocations)
            .paramsFieldWrites(paramsFieldWrites)
            .staticFieldWrites(staticFieldWrites)
            .paramEscapeEdges(paramEscapeEdges)
            .build();

        validateSummaryIndices(func, result);
        return result;
    }

    private static void validateSummaryIndices(Function func, EscapeSummary s) {
        int n = func.getParameters().size();
        checkIndices(func, "paramsRead",           s.getParamsRead(),           n);
        checkIndices(func, "paramsWritten",        s.getParamsWritten(),        n);
        checkIndices(func, "paramsDestroyed",      s.getParamsDestroyed(),      n);
        checkIndices(func, "paramsEscapedToHeap",  s.getParamsEscapedToHeap(),  n);
        checkIndices(func, "paramsEscapedToStatic", s.getParamsEscapedToStatic(), n);
        checkIndices(func, "paramsEscapedToThread", s.getParamsEscapedToThread(), n);
        checkIndices(func, "paramsReturned",       s.getParamsReturned(),       n);
        for (int idx : s.getParamsFieldWrites().keySet()) {
            if (idx < 0 || idx >= n) {
                throw new IllegalStateException(
                    "FunctionSummary for " + func.getName()
                        + " has paramsFieldWrites key " + idx
                        + " outside [0, " + n + ")");
            }
        }
        for (int idx : s.getParamEscapeEdges().keySet()) {
            if (idx < 0 || idx >= n) {
                throw new IllegalStateException(
                    "EscapeSummary for " + func.getName()
                        + " has paramEscapeEdges key " + idx
                        + " outside [0, " + n + ")");
            }
            for (int tgt : s.getParamEscapeEdges().get(idx)) {
                if (tgt < 0 || tgt >= n) {
                    throw new IllegalStateException(
                        "EscapeSummary for " + func.getName()
                            + " has paramEscapeEdges target " + tgt
                            + " outside [0, " + n + ")");
                }
            }
        }
    }

    private static void checkIndices(Function func, String name,
                                     Set<Integer> indices, int paramCount) {
        for (int idx : indices) {
            if (idx < 0 || idx >= paramCount) {
                throw new IllegalStateException(
                    "EscapeSummary for " + func.getName() + " has " + name
                        + " index " + idx + " outside [0, " + paramCount + ")");
            }
        }
    }

    private Map<Integer, Set<Integer>> computeParamAliases(Function func) {
        Map<Integer, Set<Integer>> aliases = new HashMap<>();
        List<Parameter> ps = func.getParameters();
        for (int i = 0; i < ps.size(); i++) {
            for (int j = i + 1; j < ps.size(); j++) {
                Parameter p1 = ps.get(i);
                Parameter p2 = ps.get(j);
                if (aliasResult.mayAlias(p1, p2)) {
                    int o1 = p1.getOrdinal();
                    int o2 = p2.getOrdinal();
                    aliases.computeIfAbsent(o1, k -> new HashSet<>()).add(o2);
                    aliases.computeIfAbsent(o2, k -> new HashSet<>()).add(o1);
                }
            }
        }
        return aliases;
    }

    private void processInstruction(Instruction inst, Function func,
                                    Map<Integer, Set<Integer>> paramAliases,
                                    Set<Integer> paramsRead, Set<Integer> paramsWritten,
                                    Set<Integer> paramsDestroyed,
                                    Set<Integer> paramsEscapedToHeap,
                                    Set<Integer> paramsEscapedToStatic,
                                    Set<Integer> paramsEscapedToThread,
                                    Set<Integer> paramsReturned,
                                    Set<String> fieldsRead, Set<String> fieldsWritten,
                                    boolean[] flags,
                                    Map<Integer, Set<String>> paramsFieldWrites,
                                    Set<String> staticFieldWrites,
                                    Map<Integer, Set<Integer>> paramEscapeEdges,
                                    Set<AllocationSite> returnedAllocations,
                                    Map<String, EscapeSummary> snapshot,
                                    Map<Value, Value> valueOrigin) {
        Opcode op = inst.getOpcode();
        switch (op) {
            case LOAD -> { /* parameters are seeded; nothing to do */ }
            case STORE -> { /* slots; nothing to do */ }
            case GET_FIELD -> {
                if (inst.getOperands().size() >= 2) {
                    fieldsRead.add(extractFieldName(inst));
                }
            }
            case PUT_FIELD -> processPutField(inst, paramAliases,
                paramsEscapedToHeap, paramsEscapedToStatic,
                fieldsWritten, paramsFieldWrites, paramEscapeEdges);
            case ALOAD -> processALoad(inst, valueOrigin);
            case ASTORE -> processAStore(inst, paramAliases,
                paramsEscapedToHeap, paramsEscapedToStatic, fieldsWritten);
            case GET_STATIC -> { /* read; no escape */ }
            case PUT_STATIC -> processPutStatic(inst, paramAliases,
                paramsEscapedToStatic, staticFieldWrites);
            case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL ->
                processCall(inst, snapshot,
                    paramsRead, paramsWritten, paramsDestroyed,
                    paramsEscapedToHeap, paramsEscapedToStatic, paramsEscapedToThread,
                    paramsReturned,
                    flags, paramsFieldWrites, staticFieldWrites,
                    paramEscapeEdges, valueOrigin);
            default -> { }
        }
    }

    private void processPutField(Instruction inst,
                                 Map<Integer, Set<Integer>> paramAliases,
                                 Set<Integer> paramsEscapedToHeap,
                                 Set<Integer> paramsEscapedToStatic,
                                 Set<String> fieldsWritten,
                                 Map<Integer, Set<String>> paramsFieldWrites,
                                 Map<Integer, Set<Integer>> paramEscapeEdges) {
        if (inst.getOperands().size() < 3) return;

        Value base = inst.getOperands().get(0);
        Value rhs  = inst.getOperands().get(2);
        String[] of = extractFieldOwnerAndName(inst);
        fieldsWritten.add(of[1]);

        Type rhsType = (rhs != null) ? rhs.getType() : null;
        boolean rhsIsReference = (rhsType == null)
            || rhsType.isReference() || rhsType.isArray()
            || rhsType.isNull() || rhsType.isUnknown();

        if (!rhsIsReference) return;

        if (rhs instanceof Parameter pr) {
            if (base instanceof Parameter bp) {
                // Defer to the edge mechanism: q escapes only if p escapes.
                paramEscapeEdges
                    .computeIfAbsent(pr.getOrdinal(), k -> new HashSet<>())
                    .add(bp.getOrdinal());
                for (int alias : paramAliases.getOrDefault(pr.getOrdinal(), Set.of())) {
                    paramEscapeEdges
                        .computeIfAbsent(alias, k -> new HashSet<>())
                        .add(bp.getOrdinal());
                }
            } else {
                // Base is a local allocation: we cannot determine its escape
                // status from the summary alone (that is IntraproceduralEscape's
                // responsibility), so be conservative and mark the rhs as
                // heap-escaping.
                paramsEscapedToHeap.add(pr.getOrdinal());
                addAliases(pr.getOrdinal(), paramAliases, paramsEscapedToHeap);
            }

            if (base instanceof Parameter bp) {
                // Record the field name on the base parameter (for caller's
                // points-to graph). Not an escape of base itself.
                paramsFieldWrites
                    .computeIfAbsent(bp.getOrdinal(), k -> new HashSet<>())
                    .add(of[1]);
            }
        }
    }

    private void processALoad(Instruction inst, Map<Value, Value> valueOrigin) {
        if (inst.getOperands().size() < 2) return;
        Value array = inst.getOperands().getFirst();
        Value result = inst.getResult();
        if (result != null) valueOrigin.put(result, array);
    }

    private void processAStore(Instruction inst,
                               Map<Integer, Set<Integer>> paramAliases,
                               Set<Integer> paramsEscapedToHeap,
                               Set<Integer> paramsEscapedToStatic,
                               Set<String> fieldsWritten) {
        if (inst.getOperands().size() < 3) return;
        Value value = inst.getOperands().get(2);

        Type vt = (value != null) ? value.getType() : null;
        boolean valueIsReference = (vt == null)
            || vt.isReference() || vt.isArray()
            || vt.isNull() || vt.isUnknown();

        if (valueIsReference && value instanceof Parameter p) {
            paramsEscapedToHeap.add(p.getOrdinal());
            addAliases(p.getOrdinal(), paramAliases, paramsEscapedToHeap);
        }
        fieldsWritten.add("[]");
    }

    private void processPutStatic(Instruction inst,
                                  Map<Integer, Set<Integer>> paramAliases,
                                  Set<Integer> paramsEscapedToStatic,
                                  Set<String> staticFieldWrites) {
        if (inst.getOperands().size() < 2) return;
        Value rhs = inst.getOperands().get(1);
        String field = extractFieldName(inst);
        staticFieldWrites.add(field);

        Type rhsType = (rhs != null) ? rhs.getType() : null;
        boolean rhsIsReference = (rhsType == null)
            || rhsType.isReference() || rhsType.isArray()
            || rhsType.isNull() || rhsType.isUnknown();

        if (rhs instanceof Parameter p && rhsIsReference) {
            paramsEscapedToStatic.add(p.getOrdinal());
            addAliases(p.getOrdinal(), paramAliases, paramsEscapedToStatic);
        }
    }

    private void processCall(Instruction callInst,
                             Map<String, EscapeSummary> snapshot,
                             Set<Integer> paramsRead, Set<Integer> paramsWritten,
                             Set<Integer> paramsDestroyed,
                             Set<Integer> paramsEscapedToHeap,
                             Set<Integer> paramsEscapedToStatic,
                             Set<Integer> paramsEscapedToThread,
                             Set<Integer> paramsReturned,
                             boolean[] flags,
                             Map<Integer, Set<String>> paramsFieldWrites,
                             Set<String> staticFieldWrites,
                             Map<Integer, Set<Integer>> paramEscapeEdges,
                             Map<Value, Value> valueOrigin) {
        String calleeName = extractCalleeName(callInst);
        if (calleeName == null) return;

        if (calleeName.startsWith("__destruct_")) {
            List<Value> args = getCallArguments(callInst);
            if (!args.isEmpty() && args.getFirst() instanceof Parameter p) {
                paramsDestroyed.add(p.getOrdinal());
            }
            return;
        }

        String calleeKey = canonicalCalleeKey(calleeName);
        if (isLazyClinitWrapper(calleeKey)) return;

        // Thread-start special case: the args are handed to another thread,
        // and the callee's return value (a Future/void) may itself be
        // reachable from another thread. Record explicitly to avoid relying
        // on a hard-coded prefix list inside the generic summary path.
        if (calleeName.startsWith("java/util/concurrent/ExecutorService.submit")
            || calleeName.startsWith("java/util/concurrent/ForkJoinPool.submit")
            || calleeName.startsWith("java/util/concurrent/CompletableFuture.supplyAsync")
            || calleeName.startsWith("java/lang/Thread.start()V")) {
            for (Value arg : getCallArguments(callInst)) {
                if (arg instanceof Parameter p) {
                    paramsEscapedToThread.add(p.getOrdinal());
                }
            }
            if (callInst.getResult() != null) {
                flags[0] = true;
            }
            return;
        }

        EscapeSummary calleeSum = snapshot.get(calleeKey);
        if (calleeSum == null) {
            EscapeSummary computed = nativeAnalyzer.summaryFor(calleeName);
            if (computed != null && !computed.isUnresolved()) {
                calleeSum = computed;
            } else {
                // Unknown callee: conservatively assume every reference
                // argument may be stored anywhere the callee has access to.
                for (Value arg : getCallArguments(callInst)) {
                    if (arg instanceof Parameter p) {
                        paramsEscapedToHeap.add(p.getOrdinal());
                    }
                }
                if (callInst.getResult() != null) {
                    flags[0] = true;
                }
                return;
            }
        }

        List<Value> args = getCallArguments(callInst);
        for (int i = 0; i < args.size(); i++) {
            Value arg = args.get(i);
            if (calleeSum.getParamsRead().contains(i)) {
                if (arg instanceof Parameter p) paramsRead.add(p.getOrdinal());
            }
            if (calleeSum.getParamsWritten().contains(i)) {
                if (arg instanceof Parameter p) paramsWritten.add(p.getOrdinal());
            }
            if (calleeSum.getParamsEscapedToHeap().contains(i)) {
                if (arg instanceof Parameter p) {
                    paramsEscapedToHeap.add(p.getOrdinal());
                }
            }
            if (calleeSum.getParamsEscapedToStatic().contains(i)) {
                if (arg instanceof Parameter p) {
                    paramsEscapedToStatic.add(p.getOrdinal());
                }
            }
            if (calleeSum.getParamsEscapedToThread().contains(i)) {
                if (arg instanceof Parameter p) {
                    paramsEscapedToThread.add(p.getOrdinal());
                }
            }
            if (calleeSum.getParamsReturned().contains(i)) {
                if (arg instanceof Parameter p) paramsReturned.add(p.getOrdinal());
            }
            if (calleeSum.getParamsDestroyed().contains(i)) {
                if (arg instanceof Parameter p) paramsDestroyed.add(p.getOrdinal());
            }
        }

        // Parameter-to-parameter edges from the callee, mapped to the caller's
        // own parameter ordinals.
        for (Map.Entry<Integer, Set<Integer>> e : calleeSum.getParamEscapeEdges().entrySet()) {
            int fromIdx = e.getKey();
            if (fromIdx >= args.size()) continue;
            Value fromArg = args.get(fromIdx);
            if (!(fromArg instanceof Parameter fromParam)) continue;
            int fromOrd = fromParam.getOrdinal();
            for (int toIdx : e.getValue()) {
                if (toIdx >= args.size()) continue;
                Value toArg = args.get(toIdx);
                if (!(toArg instanceof Parameter toParam)) continue;
                int toOrd = toParam.getOrdinal();
                paramEscapeEdges
                    .computeIfAbsent(fromOrd, k -> new HashSet<>())
                    .add(toOrd);
            }
        }

        // Field-write names propagated through the call. Retained for
        // InterproceduralPointsTo; not an escape class.
        for (Map.Entry<Integer, Set<String>> e : calleeSum.getParamsFieldWrites().entrySet()) {
            int paramIndex = e.getKey();
            if (paramIndex >= args.size()) continue;
            Value arg = args.get(paramIndex);
            if (arg instanceof Parameter p) {
                int localOrd = p.getOrdinal();
                Set<String> dest = paramsFieldWrites
                    .computeIfAbsent(localOrd, k -> new HashSet<>());
                dest.addAll(e.getValue());
            }
        }

        staticFieldWrites.addAll(calleeSum.getStaticFieldWrites());

        if (calleeSum.isReturnsObject()) {
            flags[0] = true;
        }

        Value ret = callInst.getResult();
        if (ret != null && (calleeSum.isReturnsObject()
            || !calleeSum.getReturnedAllocations().isEmpty())) {
            // The concrete allocation sites returned by the callee are
            // communicated to InterproceduralPointsTo through the summary's
            // returnedAllocations. Nothing to do here for the escape summary
            // of the current function: the return value's fate is decided by
            // the caller of the current function.
        }
    }

    private void processTerminator(Terminator term,
                                   Map<Integer, Set<Integer>> paramAliases,
                                   Set<Integer> paramsReturned,
                                   boolean[] flags,
                                   Map<Value, Value> valueOrigin,
                                   Set<AllocationSite> returnedAllocations) {
        if (term instanceof ReturnTerminator rt) {
            Value retVal = rt.getValue();
            if (retVal != null && retVal.getType() != Type.VOID) {
                flags[0] = true;
                if (retVal instanceof Parameter p) {
                    // Returning a parameter is only an alias relationship.
                    // It is NOT an escape: the caller decides based on its
                    // own use of the return value.
                    paramsReturned.add(p.getOrdinal());
                    addAliases(p.getOrdinal(), paramAliases, paramsReturned);
                } else {
                    Value origin = valueOrigin.get(retVal);
                    if (origin instanceof Parameter p) {
                        paramsReturned.add(p.getOrdinal());
                        addAliases(p.getOrdinal(), paramAliases, paramsReturned);
                    } else {
                        Set<AllocationSite> pts = aliasResult.getPointsTo(retVal).getSites();
                        returnedAllocations.addAll(pts);
                    }
                }
            }
        } else if (term instanceof ThrowTerminator tt) {
            Value exc = tt.getException();
            if (exc != null) {
                if (exc instanceof Parameter p) {
                    paramsEscapedToHeap(p, paramAliases, paramsReturned, returnedAllocations);
                    paramsReturned.add(p.getOrdinal());
                } else {
                    Value origin = valueOrigin.get(exc);
                    if (origin instanceof Parameter p) {
                        paramsReturned.add(p.getOrdinal());
                        addAliases(p.getOrdinal(), paramAliases, paramsReturned);
                    }
                }
            }
        }
    }

    // Convenience: throwing an exception hands the exception to the caller,
    // so an aliased parameter is at least RETURN-escaped.
    private void paramsEscapedToHeap(Parameter p,
                                     Map<Integer, Set<Integer>> paramAliases,
                                     Set<Integer> paramsReturned,
                                     Set<AllocationSite> returnedAllocations) {
        paramsReturned.add(p.getOrdinal());
        addAliases(p.getOrdinal(), paramAliases, paramsReturned);
    }

    private void addAliases(int paramIndex,
                            Map<Integer, Set<Integer>> aliases,
                            Set<Integer> set) {
        Set<Integer> aliasSet = aliases.get(paramIndex);
        if (aliasSet != null) set.addAll(aliasSet);
    }
}
package io.github.kubyk01.application.service.analyzer.escapeanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.ir.*;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.util.LlvmUtil;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.Opcodes;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldOwnerAndName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;

@Slf4j
public class EscapeSummaryBuilder {

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final DependencyResolver resolver;
    private final Scheduler scheduler;
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
        this.resolver = resolver;
        this.scheduler = (scheduler != null) ? scheduler : Schedulers.single();
    }

    public Map<String, EscapeSummary> build() {
        List<Function> live = new ArrayList<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() != null) live.add(f);
        }
        if (live.isEmpty()) return summaries;

        // ---- Build the reverse call graph ONCE --------------------------
        //
        // callersOf: callee mangled name -> set of caller Functions.
        // Building this once is what turns the fixed point from
        // "N full passes over the module" into "only re-analyze the
        // callers whose callee actually changed".
        Map<String, Set<Function>> callersOf = new HashMap<>();
        for (Function f : live) {
            for (BasicBlock b : f.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    String callee = extractCalleeName(inst);
                    if (callee != null) {
                        callersOf.computeIfAbsent(callee, k -> new HashSet<>()).add(f);
                    }
                }
            }
        }

        // ---- Worklist initialised with every function -------------------
        Deque<Function> worklist = new ArrayDeque<>(live);
        Set<Function> inWorklist = new HashSet<>(live);

        long processed = 0;
        long lastLog = 0;

        // Snapshot for the parallel batch: since analyzeFunction reads the
        // callee summaries, we must ensure a stable view. We commit the
        // batch results only after it returns.
        while (!worklist.isEmpty()) {
            // Drain the current wavefront as one parallel batch.
            List<Function> batch = new ArrayList<>();
            Set<Function> nextWaveSet = new HashSet<>();
            while (!worklist.isEmpty()) {
                Function f = worklist.poll();
                inWorklist.remove(f);
                batch.add(f);
            }

            Map<String, EscapeSummary> snapshot = new HashMap<>(summaries);

            List<EscapeSummary> fresh = LlvmUtil.awaitFlux(
                Flux.fromIterable(batch)
                    .parallel()
                    .runOn(scheduler)
                    .map(func -> analyzeFunction(func, snapshot))
                    .sequential()
            );
            if (fresh == null || fresh.size() != batch.size()) {
                throw new IllegalStateException(
                    "EscapeSummaryBuilder: reactor pipeline returned " +
                        (fresh == null ? "null"
                                       : fresh.size() + " summaries for "
                                         + batch.size() + " functions"));
            }

            for (int i = 0; i < batch.size(); i++) {
                Function f = batch.get(i);
                EscapeSummary newSum = fresh.get(i);
                EscapeSummary oldSum = summaries.get(f.getName());
                processed++;

                if (oldSum == null || !oldSum.equals(newSum)) {
                    summaries.put(f.getName(), newSum);

                    // Re-enqueue the callers of this function.
                    Set<Function> callers = callersOf.get(f.getName());
                    if (callers != null) {
                        for (Function caller : callers) {
                            if (inWorklist.add(caller)) {
                                worklist.add(caller);
                                nextWaveSet.add(caller);
                            }
                        }
                    }
                }
            }

            if (processed - lastLog >= 5_000) {
                lastLog = processed;
                System.out.println("EscapeSummaryBuilder: " + processed
                    + " evaluations, " + worklist.size() + " queued");
            }
        }

        System.out.println("EscapeSummaryBuilder: converged after "
            + processed + " function evaluations, "
            + summaries.size() + " summaries");
        return summaries;
    }

    private EscapeSummary analyzeFunction(Function func,
                                          Map<String, EscapeSummary> snapshot) {
        Set<Integer> paramsEscaped = new HashSet<>();
        Set<Integer> paramsReturned = new HashSet<>();
        Set<String> fieldsEscaped = new HashSet<>();
        boolean[] flags = new boolean[3];

        Map<Integer, Set<Integer>> paramAliases = computeParamAliases(func);
        Map<Value, Value> valueOrigin = new HashMap<>();

        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                processInstruction(inst, paramAliases, paramsEscaped,
                    paramsReturned, fieldsEscaped, flags, snapshot, valueOrigin);
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                processTerminator(term, paramAliases, paramsEscaped,
                    paramsReturned, flags, valueOrigin);
            }
        }

        return EscapeSummary.builder()
            .paramsEscaped(paramsEscaped)
            .paramsReturned(paramsReturned)
            .fieldsEscaped(fieldsEscaped)
            .returnsObject(flags[0])
            .escapesGlobally(flags[1])
            .createsThread(flags[2])
            .build();
    }

    private Map<Integer, Set<Integer>> computeParamAliases(Function func) {
        Map<Integer, Set<Integer>> aliases = new HashMap<>();
        for (int i = 0; i < func.getParameters().size(); i++) {
            for (int j = i + 1; j < func.getParameters().size(); j++) {
                Parameter p1 = func.getParameters().get(i);
                Parameter p2 = func.getParameters().get(j);
                if (aliasResult.mayAlias(p1, p2)) {
                    aliases.computeIfAbsent(i, k -> new HashSet<>()).add(j);
                    aliases.computeIfAbsent(j, k -> new HashSet<>()).add(i);
                }
            }
        }
        return aliases;
    }

    private void processInstruction(Instruction inst,
                                    Map<Integer, Set<Integer>> paramAliases,
                                    Set<Integer> paramsEscaped,
                                    Set<Integer> paramsReturned,
                                    Set<String> fieldsEscaped,
                                    boolean[] flags,
                                    Map<String, EscapeSummary> snapshot,
                                    Map<Value, Value> valueOrigin) {
        Opcode op = inst.getOpcode();
        switch (op) {
            case PUT_FIELD -> {
                if (inst.getOperands().size() >= 3) {
                    Value base = inst.getOperands().get(0);
                    Value rhs = inst.getOperands().get(2);
                    String[] of = extractFieldOwnerAndName(inst);
                    if (isVolatileField(of[0], of[1])) {
                        if (base instanceof Parameter p) {
                            paramsEscaped.add(p.getIndex());
                            addAliases(p.getIndex(), paramAliases, paramsEscaped);
                        }
                        if (rhs instanceof Parameter p) {
                            paramsEscaped.add(p.getIndex());
                            addAliases(p.getIndex(), paramAliases, paramsEscaped);
                        }
                        flags[1] = true;
                    } else {
                        if (base instanceof Parameter p) {
                            paramsEscaped.add(p.getIndex());
                            addAliases(p.getIndex(), paramAliases, paramsEscaped);
                        }
                        if (rhs instanceof Parameter p) {
                            paramsEscaped.add(p.getIndex());
                            addAliases(p.getIndex(), paramAliases, paramsEscaped);
                        }
                        Object fn = inst.getOperands().get(1);
                        if (fn instanceof Constant c) fieldsEscaped.add(c.getValue().toString());
                    }
                }
            }
            case GET_FIELD -> {
                if (inst.getOperands().size() >= 2) {
                    Value base = inst.getOperands().getFirst();
                    String[] of = extractFieldOwnerAndName(inst);
                    if (isVolatileField(of[0], of[1])) {
                        if (base instanceof Parameter p) {
                            paramsEscaped.add(p.getIndex());
                            addAliases(p.getIndex(), paramAliases, paramsEscaped);
                        }
                        flags[1] = true;
                    }
                }
            }
            case MONITOR_ENTER, MONITOR_EXIT -> {
                if (!inst.getOperands().isEmpty()) {
                    Value obj = inst.getOperands().getFirst();
                    if (obj instanceof Parameter p) {
                        paramsEscaped.add(p.getIndex());
                        addAliases(p.getIndex(), paramAliases, paramsEscaped);
                        flags[1] = true;
                    }
                }
            }
            case PUT_STATIC -> {
                if (inst.getOperands().size() >= 2) {
                    Value rhs = inst.getOperands().get(1);
                    if (rhs instanceof Parameter p) {
                        paramsEscaped.add(p.getIndex());
                        addAliases(p.getIndex(), paramAliases, paramsEscaped);
                    }
                    flags[1] = true;
                }
            }
            case ALOAD -> {
                if (inst.getOperands().size() >= 2) {
                    Value array = inst.getOperands().getFirst();
                    Value result = inst.getResult();
                    if (result != null) valueOrigin.put(result, array);
                }
            }
            case ASTORE -> {
                if (inst.getOperands().size() >= 3) {
                    Value array = inst.getOperands().get(0);
                    Value value = inst.getOperands().get(2);
                    if (array instanceof Parameter p) {
                        paramsEscaped.add(p.getIndex());
                        addAliases(p.getIndex(), paramAliases, paramsEscaped);
                    }
                    if (value instanceof Parameter p) {
                        paramsEscaped.add(p.getIndex());
                        addAliases(p.getIndex(), paramAliases, paramsEscaped);
                    }
                    fieldsEscaped.add("[]");
                }
            }
            case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL ->
                processCall(inst, paramAliases, paramsEscaped, paramsReturned, flags, snapshot);
            default -> { }
        }
    }

    private void processCall(Instruction callInst,
                             Map<Integer, Set<Integer>> paramAliases,
                             Set<Integer> paramsEscaped,
                             Set<Integer> paramsReturned,
                             boolean[] flags,
                             Map<String, EscapeSummary> snapshot) {
        String calleeName = extractCalleeName(callInst);
        if (calleeName == null) return;

        if (calleeName.startsWith("java/util/concurrent/ExecutorService.submit")
            || calleeName.startsWith("java/util/concurrent/ForkJoinPool.submit")
            || calleeName.startsWith("java/util/concurrent/CompletableFuture.supplyAsync")
            || calleeName.startsWith("java/lang/Thread.start()V")) {
            flags[2] = true;
            for (Value arg : getCallArguments(callInst)) {
                if (arg instanceof Parameter p) {
                    paramsEscaped.add(p.getIndex());
                    addAliases(p.getIndex(), paramAliases, paramsEscaped);
                }
            }
            return;
        }

        EscapeSummary calleeSum = snapshot.get(calleeName);
        if (calleeSum == null) {
            for (Value arg : getCallArguments(callInst)) {
                if (arg instanceof Parameter p) {
                    paramsEscaped.add(p.getIndex());
                    addAliases(p.getIndex(), paramAliases, paramsEscaped);
                }
            }
            Value ret = callInst.getResult();
            if (ret != null && ret.getType() != Type.VOID) flags[0] = true;
            return;
        }

        List<Value> args = getCallArguments(callInst);
        for (int i = 0; i < args.size(); i++) {
            Value arg = args.get(i);
            if (calleeSum.getParamsEscaped().contains(i) && arg instanceof Parameter p) {
                paramsEscaped.add(p.getIndex());
                addAliases(p.getIndex(), paramAliases, paramsEscaped);
            }
            if (calleeSum.getParamsReturned().contains(i) && arg instanceof Parameter p) {
                paramsReturned.add(p.getIndex());
                addAliases(p.getIndex(), paramAliases, paramsReturned);
            }
        }
        if (calleeSum.isEscapesGlobally()) flags[1] = true;
        if (calleeSum.isCreatesThread()) flags[2] = true;
        if (calleeSum.isReturnsObject()) flags[0] = true;
    }

    private void processTerminator(Terminator term,
                                   Map<Integer, Set<Integer>> paramAliases,
                                   Set<Integer> paramsEscaped,
                                   Set<Integer> paramsReturned,
                                   boolean[] flags,
                                   Map<Value, Value> valueOrigin) {
        if (term instanceof ReturnTerminator rt) {
            Value retVal = rt.getValue();
            if (retVal != null && retVal.getType() != Type.VOID) {
                flags[0] = true;
                if (retVal instanceof Parameter p) {
                    paramsEscaped.add(p.getIndex());
                    paramsReturned.add(p.getIndex());
                    addAliases(p.getIndex(), paramAliases, paramsEscaped);
                    addAliases(p.getIndex(), paramAliases, paramsReturned);
                } else {
                    Value origin = valueOrigin.get(retVal);
                    if (origin instanceof Parameter p) {
                        paramsReturned.add(p.getIndex());
                        addAliases(p.getIndex(), paramAliases, paramsReturned);
                    }
                }
            }
        } else if (term instanceof ThrowTerminator tt) {
            Value exc = tt.getException();
            if (exc != null) {
                if (exc instanceof Parameter p) {
                    paramsEscaped.add(p.getIndex());
                    paramsReturned.add(p.getIndex());
                    addAliases(p.getIndex(), paramAliases, paramsEscaped);
                    addAliases(p.getIndex(), paramAliases, paramsReturned);
                } else {
                    Value origin = valueOrigin.get(exc);
                    if (origin instanceof Parameter p) {
                        paramsEscaped.add(p.getIndex());
                        paramsReturned.add(p.getIndex());
                        addAliases(p.getIndex(), paramAliases, paramsEscaped);
                        addAliases(p.getIndex(), paramAliases, paramsReturned);
                    }
                }
            }
        }
    }

    private boolean isVolatileField(String owner, String fieldName) {
        if (owner == null || owner.isEmpty() || fieldName == null || fieldName.isEmpty()) return false;
        FieldNode field = resolver.getField(owner, fieldName);
        return field != null && (field.getAccess() & Opcodes.ACC_VOLATILE) != 0;
    }

    private void addAliases(int paramIndex, Map<Integer, Set<Integer>> aliases, Set<Integer> set) {
        Set<Integer> aliasSet = aliases.get(paramIndex);
        if (aliasSet != null) set.addAll(aliasSet);
    }
}

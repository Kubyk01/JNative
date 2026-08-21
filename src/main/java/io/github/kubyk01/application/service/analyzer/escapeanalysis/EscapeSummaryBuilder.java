package io.github.kubyk01.application.service.analyzer.escapeanalysis;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.analyzer.ir.BasicBlock;
import io.github.kubyk01.domain.analyzer.ir.Constant;
import io.github.kubyk01.domain.analyzer.ir.Function;
import io.github.kubyk01.domain.analyzer.ir.Instruction;
import io.github.kubyk01.domain.analyzer.ir.Module;
import io.github.kubyk01.domain.analyzer.ir.Opcode;
import io.github.kubyk01.domain.analyzer.ir.Parameter;
import io.github.kubyk01.domain.analyzer.ir.ReturnTerminator;
import io.github.kubyk01.domain.analyzer.ir.Terminator;
import io.github.kubyk01.domain.analyzer.ir.Type;
import io.github.kubyk01.domain.analyzer.ir.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class EscapeSummaryBuilder {

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final Map<String, EscapeSummary> summaries = new HashMap<>();

    public EscapeSummaryBuilder(Module module, AliasAnalysisResult aliasResult) {
        this.module = module;
        this.aliasResult = aliasResult;
    }

    public Map<String, EscapeSummary> build() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Function func : module.getFunctions()) {
                if (func.getEntryBlock() == null) continue;
                EscapeSummary newSum = analyzeFunction(func);
                EscapeSummary oldSum = summaries.get(func.getName());
                if (oldSum == null || !oldSum.equals(newSum)) {
                    summaries.put(func.getName(), newSum);
                    changed = true;
                }
            }
        }
        return summaries;
    }

    private EscapeSummary analyzeFunction(Function func) {
        Set<Integer> paramsEscaped = new HashSet<>();
        Set<Integer> paramsReturned = new HashSet<>();
        Set<String> fieldsEscaped = new HashSet<>();
        boolean[] flags = new boolean[3]; // returnsObject, escapesGlobally, createsThread

        // Use aliasResult to determine parameter aliases
        Map<Integer, Set<Integer>> paramAliases = computeParamAliases(func);

        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                processInstruction(inst, func, paramAliases, paramsEscaped, paramsReturned, fieldsEscaped, flags);
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                processTerminator(term, paramAliases, paramsEscaped, paramsReturned, flags);
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
        // If two parameters may point to the same object, they are considered aliases
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

    private void processInstruction(Instruction inst, Function func,
                                    Map<Integer, Set<Integer>> paramAliases,
                                    Set<Integer> paramsEscaped, Set<Integer> paramsReturned,
                                    Set<String> fieldsEscaped, boolean[] flags) {
        Opcode op = inst.getOpcode();
        switch (op) {
            case PUT_FIELD: {
                if (inst.getOperands().size() >= 3) {
                    Value base = inst.getOperands().get(0);
                    Value rhs = inst.getOperands().get(2);
                    // If base is a parameter, it escapes (holds a reference)
                    if (base instanceof Parameter p) {
                        paramsEscaped.add(p.getIndex());
                        addAliases(p.getIndex(), paramAliases, paramsEscaped);
                    }
                    // If rhs is a parameter, it escapes because it is stored into a field
                    if (rhs instanceof Parameter p) {
                        paramsEscaped.add(p.getIndex());
                        addAliases(p.getIndex(), paramAliases, paramsEscaped);
                    }
                    // The field escapes
                    Object fieldName = inst.getOperands().get(1);
                    if (fieldName instanceof Constant c) {
                        fieldsEscaped.add(c.getValue().toString());
                    }
                }
                break;
            }
            case PUT_STATIC: {
                if (!inst.getOperands().isEmpty()) {
                    Value rhs = inst.getOperands().getFirst();
                    if (rhs instanceof Parameter p) {
                        paramsEscaped.add(p.getIndex());
                        addAliases(p.getIndex(), paramAliases, paramsEscaped);
                    }
                    flags[1] = true; // escapesGlobally
                }
                break;
            }
            case CALL:
            case VIRTUAL_CALL:
            case INTERFACE_CALL:
            case STATIC_CALL:
            case SPECIAL_CALL: {
                processCall(inst, paramAliases, paramsEscaped, paramsReturned, flags);
                break;
            }
            default:
                // other instructions do not affect the summary (except return, which is handled in the terminator)
        }
    }

    private void processCall(Instruction callInst,
                             Map<Integer, Set<Integer>> paramAliases,
                             Set<Integer> paramsEscaped, Set<Integer> paramsReturned,
                             boolean[] flags) {
        String calleeName = extractCalleeName(callInst);
        if (calleeName == null) return;

        // Check whether the call creates a thread
        if (calleeName.startsWith("java/util/concurrent/ExecutorService.submit") ||
            calleeName.startsWith("java/util/concurrent/ForkJoinPool.submit") ||
            calleeName.startsWith("java/util/concurrent/CompletableFuture.supplyAsync") ||
            calleeName.startsWith("java/lang/Thread.start()V")) {
            flags[2] = true; // createsThread
            // Arguments (Runnable, Callable) are passed to the thread – they escape
            List<Value> args = getCallArguments(callInst);
            for (Value arg : args) {
                if (arg instanceof Parameter p) {
                    paramsEscaped.add(p.getIndex());
                    addAliases(p.getIndex(), paramAliases, paramsEscaped);
                }
            }
            return;
        }

        EscapeSummary calleeSum = summaries.get(calleeName);
        if (calleeSum == null) {
            // External call without a summary - conservatively mark all arguments as HEAP (i.e. escaping)
            List<Value> args = getCallArguments(callInst);
            for (Value arg : args) {
                if (arg instanceof Parameter p) {
                    paramsEscaped.add(p.getIndex());
                    addAliases(p.getIndex(), paramAliases, paramsEscaped);
                }
            }
            // If an object is returned, assume it escapes too (conservatively)
            Value ret = callInst.getResult();
            if (ret != null && ret.getType() != Type.VOID) {
                flags[0] = true; // returnsObject
                // If a parameter is returned, it escapes as well
                if (ret instanceof Parameter p) {
                    paramsEscaped.add(p.getIndex());
                    addAliases(p.getIndex(), paramAliases, paramsEscaped);
                }
            }
            return;
        }

        // Apply the known summary
        List<Value> args = getCallArguments(callInst);
        for (int i = 0; i < args.size(); i++) {
            Value arg = args.get(i);
            if (calleeSum.getParamsEscaped().contains(i)) {
                if (arg instanceof Parameter p) {
                    paramsEscaped.add(p.getIndex());
                    addAliases(p.getIndex(), paramAliases, paramsEscaped);
                }
            }
            if (calleeSum.getParamsReturned().contains(i)) {
                if (arg instanceof Parameter p) {
                    paramsReturned.add(p.getIndex());
                    addAliases(p.getIndex(), paramAliases, paramsReturned);
                }
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
                                   boolean[] flags) {
        if (term instanceof ReturnTerminator rt) {
            Value retVal = rt.getValue();
            if (retVal != null && retVal.getType() != Type.VOID) {
                flags[0] = true;
                if (retVal instanceof Parameter p) {
                    paramsEscaped.add(p.getIndex());
                    paramsReturned.add(p.getIndex());
                    addAliases(p.getIndex(), paramAliases, paramsEscaped);
                    addAliases(p.getIndex(), paramAliases, paramsReturned);
                }
            }
        }
    }

    private void addAliases(int paramIndex, Map<Integer, Set<Integer>> aliases, Set<Integer> set) {
        Set<Integer> aliasSet = aliases.get(paramIndex);
        if (aliasSet != null) {
            set.addAll(aliasSet);
        }
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

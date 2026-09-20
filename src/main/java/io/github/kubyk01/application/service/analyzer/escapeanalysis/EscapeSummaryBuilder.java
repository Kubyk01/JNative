package io.github.kubyk01.application.service.analyzer.escapeanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.Opcodes;

import java.util.*;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldOwnerAndName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;

@Slf4j
public class EscapeSummaryBuilder {

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final DependencyResolver resolver;
    private final Map<String, EscapeSummary> summaries = new HashMap<>();
    private final Map<Value, Value> valueOrigin = new HashMap<>();

    public EscapeSummaryBuilder(Module module, AliasAnalysisResult aliasResult, DependencyResolver resolver) {
        this.module = module;
        this.aliasResult = aliasResult;
        this.resolver = resolver;
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

        Map<Integer, Set<Integer>> paramAliases = computeParamAliases(func);

        // Clear origin map for this function
        valueOrigin.clear();

        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                processInstruction(inst, paramAliases, paramsEscaped, paramsReturned, fieldsEscaped, flags);
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
                                    Set<Integer> paramsEscaped, Set<Integer> paramsReturned,
                                    Set<String> fieldsEscaped, boolean[] flags) {
        Opcode op = inst.getOpcode();
        switch (op) {
            case PUT_FIELD: {
                if (inst.getOperands().size() >= 3) {
                    Value base = inst.getOperands().get(0);
                    Value rhs = inst.getOperands().get(2);
                    String[] ownerAndField = extractFieldOwnerAndName(inst);
                    String owner = ownerAndField[0];
                    String fieldName = ownerAndField[1];

                    // Check if field is volatile
                    if (isVolatileField(owner, fieldName)) {
                        // volatile field makes base and rhs globally visible
                        if (base instanceof Parameter p) {
                            paramsEscaped.add(p.getIndex());
                            addAliases(p.getIndex(), paramAliases, paramsEscaped);
                        }
                        if (rhs instanceof Parameter p) {
                            paramsEscaped.add(p.getIndex());
                            addAliases(p.getIndex(), paramAliases, paramsEscaped);
                        }
                        flags[1] = true; // escapesGlobally
                    } else {
                        // Normal field handling (existing logic)
                        if (base instanceof Parameter p) {
                            paramsEscaped.add(p.getIndex());
                            addAliases(p.getIndex(), paramAliases, paramsEscaped);
                        }
                        if (rhs instanceof Parameter p) {
                            paramsEscaped.add(p.getIndex());
                            addAliases(p.getIndex(), paramAliases, paramsEscaped);
                        }
                        Object fieldNameObj = inst.getOperands().get(1);
                        if (fieldNameObj instanceof Constant c) {
                            fieldsEscaped.add(c.getValue().toString());
                        }
                    }
                }
                break;
            }
            case GET_FIELD: {
                if (inst.getOperands().size() >= 2) {
                    Value base = inst.getOperands().getFirst();
                    String[] ownerAndField = extractFieldOwnerAndName(inst);
                    String owner = ownerAndField[0];
                    String fieldName = ownerAndField[1];

                    // Check if field is volatile
                    if (isVolatileField(owner, fieldName)) {
                        // Accessing volatile field makes base globally visible
                        if (base instanceof Parameter p) {
                            paramsEscaped.add(p.getIndex());
                            addAliases(p.getIndex(), paramAliases, paramsEscaped);
                        }
                        flags[1] = true; // escapesGlobally
                    }
                }
                break;
            }
            case MONITOR_ENTER:
            case MONITOR_EXIT: {
                if (!inst.getOperands().isEmpty()) {
                    Value obj = inst.getOperands().getFirst();
                    if (obj instanceof Parameter p) {
                        paramsEscaped.add(p.getIndex());
                        addAliases(p.getIndex(), paramAliases, paramsEscaped);
                        flags[1] = true; // synchronizing on object makes it globally visible
                    }
                }
                break;
            }
            case PUT_STATIC: {
                // Layout PUT_STATIC: [fieldConst, val]
                if (inst.getOperands().size() >= 2) {
                    Value rhs = inst.getOperands().get(1);
                    if (rhs instanceof Parameter p) {
                        paramsEscaped.add(p.getIndex());
                        addAliases(p.getIndex(), paramAliases, paramsEscaped);
                    }
                    flags[1] = true; // escapesGlobally
                }
                break;
            }
            case ALOAD: {
                // array load: result = array[index]
                if (inst.getOperands().size() >= 2) {
                    Value array = inst.getOperands().getFirst();
                    Value result = inst.getResult();
                    if (result != null) {
                        // Remember that 'result' originates from 'array'
                        valueOrigin.put(result, array);
                    }
                    // Reading from array does not cause escape by itself.
                    // If the result is returned, it will be handled in processTerminator.
                }
                break;
            }
            case ASTORE: {
                // array store: array[index] = value
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
                    // The array element field escapes
                    fieldsEscaped.add("[]");
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

        if (calleeName.startsWith("java/util/concurrent/ExecutorService.submit") ||
            calleeName.startsWith("java/util/concurrent/ForkJoinPool.submit") ||
            calleeName.startsWith("java/util/concurrent/CompletableFuture.supplyAsync") ||
            calleeName.startsWith("java/lang/Thread.start()V")) {
            flags[2] = true;
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
            List<Value> args = getCallArguments(callInst);
            for (Value arg : args) {
                if (arg instanceof Parameter p) {
                    paramsEscaped.add(p.getIndex());
                    addAliases(p.getIndex(), paramAliases, paramsEscaped);
                }
            }
            Value ret = callInst.getResult();
            if (ret != null && ret.getType() != Type.VOID) {
                flags[0] = true;
            }
            return;
        }

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
                } else {
                    // Check if retVal originates from an array load
                    Value origin = valueOrigin.get(retVal);
                    if (origin instanceof Parameter p) {
                        // Returning an element of a parameter array means the parameter's contents may be returned.
                        // Conservatively mark the parameter as returned.
                        paramsReturned.add(p.getIndex());
                        addAliases(p.getIndex(), paramAliases, paramsReturned);
                    }
                }
            }
        } else if (term instanceof ThrowTerminator tt) {
            // A thrown exception leaves the current method exactly like a
            // returned value does: it is observed by the caller (either by
            // an enclosing try/catch or by the runtime's unhandled-exception
            // reporter). It must therefore not be treated as method-local,
            // or the escape analysis would report its AllocationSite as
            // STACK and ScalarReplacer / DestructorInserter would delete or
            // destroy it while it is still referenced.
            //
            // The same reasoning applies to any parameter that appears in
            // the thrown expression (typically the exception object itself,
            // but also any parameter referenced while building the message
            // or cause chain): throwing it hands it to the caller, so the
            // parameter escapes and may be returned in the sense used by
            // the EscapeSummary contract.
            Value exc = tt.getException();
            if (exc != null) {
                if (exc instanceof Parameter p) {
                    paramsEscaped.add(p.getIndex());
                    paramsReturned.add(p.getIndex());
                    addAliases(p.getIndex(), paramAliases, paramsEscaped);
                    addAliases(p.getIndex(), paramAliases, paramsReturned);
                } else {
                    // The exception value may be derived from a parameter —
                    // for instance it may be an element loaded out of a
                    // parameter array, or a value stashed away by an earlier
                    // ALOAD. Reuse the same origin tracking that the RETURN
                    // branch uses so the parameter that really escaped is
                    // the one recorded.
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
        if (aliasSet != null) {
            set.addAll(aliasSet);
        }
    }

}
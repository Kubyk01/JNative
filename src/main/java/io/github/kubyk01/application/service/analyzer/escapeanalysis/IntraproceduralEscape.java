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

    /** Per-allocation-site escape status. */
    private final Map<AllocationSite, EscapeStatus> siteStatus = new HashMap<>();

    /**
     * Per-(site, field) escape level of the value(s) currently stored
     * in that field. Used by GET_FIELD to propagate the escape level of
     * the read result.
     */
    private final Map<AllocationSite, Map<String, EscapeStatus>> fieldStatus =
            new HashMap<>();

    private final NativeBodyEscapeAnalyzer nativeAnalyzer;

    /**
     * Local parameter-to-parameter escape edges, populated from callee
     * summaries during {@link #processCall}. Each entry `from -> {to}`
     * means "the object referenced by `from` is reachable from the object
     * referenced by `to`". The fixed-point loop in {@link #analyze}
     * propagates `to`'s escape status to `from`, so that when `to`
     * becomes {@code RETURN} the `from` site is promoted to {@code RETURN}
     * as well.
     *
     * <p>The concrete case this closes is {@code ConcurrentHashMap} created
     * in {@code newKeySet()} and stored into a {@code KeySetView} that is
     * returned: the map is reachable from the returned view, so its escape
     * status must be at least {@code RETURN}, and no per-use destructor
     * may be inserted for it. Without this mechanism the map is classified
     * as {@code HEAP}, a destruction point is created for it, and the
     * destructor frees the map before the caller can use the returned
     * view.</p>
     */
    private final Map<Value, Set<Value>> localEscapeEdges = new HashMap<>();

    /** Reset at the start of every fixed-point iteration. */
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

            // Propagate local escape edges. If `from` escapes into `to`
            // (i.e. `from` is reachable from `to`), then `from`'s status is
            // at least `to`'s -- an object reachable from a returned value
            // is itself returned, an object reachable from a global is
            // itself global, and so on. Running this after the instruction
            // walk means the status of `to` reflects every update that
            // occurred in this iteration.
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

    private void processInstruction(Instruction inst) {
        Opcode op = inst.getOpcode();
        switch (op) {
            case PUT_FIELD    -> processPutField(inst);
            case GET_FIELD    -> processGetField(inst);
            case ALOAD        -> processALoad(inst);
            case ASTORE       -> processAStore(inst);
            case MONITOR_ENTER, MONITOR_EXIT -> processMonitor(inst);
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

        // A field write mutates the base in place; it does not make the
        // base escape. Only the stored value becomes reachable from the
        // base, so its escape level is the maximum of the base's own
        // level and whatever the field already held.
        EscapeStatus baseEsc = valueEscapeStatus(base);

        // FIX: a value stored into a field is always at least HEAP.
        //
        // Rationale: even when the base is a fresh STACK allocation, the
        // fields of the owner do not go through destructors when the
        // owner's frame goes away (a STACK object is released together
        // with its frame). The value in the field is therefore orphaned
        // and has to be freed either through the destructors of the owner
        // (when the owner is not STACK) or at its last use (when the owner
        // is STACK). In both cases the status must be at least HEAP — it is
        // the only correct answer.
        //
        // Previously this was `markEscaped(rhs, baseEsc)`, and for a STACK
        // owner the stored value got STACK. That made the DestructorInserter
        // skip the site (STACK sites are filtered out) and the object
        // leaked.
        EscapeStatus rhsPromoted = maxEscape(baseEsc, EscapeStatus.HEAP);
        markEscaped(rhs, rhsPromoted);

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

        // Same reasoning as PUT_FIELD: writing into an array modifies it
        // in place. The array reference does not escape; only the stored
        // value does, and only when the array is externally observable.
        // A value stored into an array is at least HEAP for the same
        // reason as a value stored into a field: the array's elements do
        // not go through destructors when a STACK owner dies.
        EscapeStatus arrayEsc = valueEscapeStatus(array);
        EscapeStatus valuePromoted = maxEscape(arrayEsc, EscapeStatus.HEAP);
        markEscaped(value, valuePromoted);

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

    private void processMonitor(Instruction inst) {
        if (inst.getOperands().isEmpty()) return;
        // Synchronising on an object exposes it to any other thread that
        // synchronises on the same object.
        markEscaped(inst.getOperands().getFirst(), EscapeStatus.GLOBAL);
    }

    private void processPutStatic(Instruction inst) {
        if (inst.getOperands().size() < 2) return;
        Value rhs = inst.getOperands().get(1);
        if (rhs != null) markEscaped(rhs, EscapeStatus.GLOBAL);
    }

    private void processGetStatic(Instruction inst) {
        Value result = inst.getResult();
        if (result == null) return;
        // Any value read from a static field is by definition reachable
        // from every thread that can load the containing class.
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
            // Native (body-less) callee: consult the C-body analyser.
            EscapeSummary computed = nativeAnalyzer.summaryFor(calleeName);
            if (computed != null && !computed.isEscapesGlobally()) {
                summary = computed;
            } else {
                // Unknown callee: conservatively assume every reference
                // argument may be stored anywhere the callee has access to.
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

        // ------------------------------------------------------------------
        // Argument escape propagation.
        //
        // The callee's summary tells us WHICH of its parameters escape and
        // WHERE they escape to:
        //
        //   * getParamsEscapedToStatic() contains i  ->  parameter i really
        //     landed in a static field, so the argument is GLOBAL;
        //   * getParamsEscaped() contains i          ->  parameter i escaped
        //     into the heap (stored into a field of some object), so the
        //     argument is HEAP.
        //
        // The coarse flag summary.isEscapesGlobally() is deliberately NOT
        // used for the argument classification: it only says "somewhere in
        // this callee's transitive body a value was written into global
        // state", not WHICH value. Promoting every escaped argument to
        // GLOBAL on the strength of that flag is exactly the error that
        // turned the local StringBuilder of Object.toString into a GLOBAL
        // allocation. The exact set paramsEscapedToStatic lets us promote
        // only the argument that really became globally reachable.
        //
        //   * An argument the callee RETURNS (its index is in
        //     paramsReturned) is NOT promoted at the call site. The callee
        //     returning a value tells us that the return value is an alias
        //     of that argument; it does NOT tell us that the argument
        //     escapes the caller's frame. The caller's own analysis of
        //     what it does with the return value -- its return
        //     terminator, a field store, another call -- determines
        //     whether the aliased argument actually escapes. The alias
        //     relationship itself is already recorded by
        //     InterproceduralPointsTo.processCall, which merges the return
        //     value's points-to set with every argument named in
        //     paramsReturned.
        //
        //   * The return value itself is NOT promoted to GLOBAL merely
        //     because the callee has isEscapesGlobally() == true, and it
        //     is not promoted to RETURN merely because the callee has
        //     isReturnsObject() == true. If the returned value is an
        //     alias of a static field, its site is already GLOBAL through
        //     GET_STATIC in IntraproceduralEscape. If the returned value
        //     is a fresh allocation, GLOBAL cannot come out of nowhere.
        //
        // The concrete failure the old promotions produced:
        //
        //     Object.toString() {
        //         return getClass().getName() + "@"
        //              + Integer.toHexString(hashCode());
        //     }
        //
        // compiles to a chain of StringBuilder.append calls on a fresh
        // `new StringBuilder()`. The receiver is never returned by
        // toString, never stored in a field, and never passed to an
        // escaping call: it is a local builder whose only role is to be
        // consumed by StringBuilder.toString() at the end of the
        // expression. With the old promotion, the receiver's site was
        // marked RETURN on the first append call. The generated IR then
        // had (a) a calloc for the StringBuilder, (b) no
        // __destruct_java_lang_StringBuilder anywhere, and (c) no free for
        // the returned byte[] -- one StringBuilder plus its internal
        // buffer leaked on every toString() call.
        //
        // The fix is semantic, not a heuristic and not a special case for
        // any particular class or method name. It applies to every callee
        // uniformly: a call never changes the escape status of its
        // arguments on the basis of what the callee returns; only the
        // caller's subsequent use of the return value does.
        // ------------------------------------------------------------------
        for (int i = 0; i < args.size(); i++) {
            if (summary.getParamsEscapedToStatic().contains(i)) {
                markEscaped(args.get(i), EscapeStatus.GLOBAL);
            } else if (summary.getParamsEscaped().contains(i)) {
                markEscaped(args.get(i), EscapeStatus.HEAP);
            }
            // paramsReturned is deliberately NOT propagated as RETURN
            // here. See the block comment above for the full rationale.
        }

        // The return value is deliberately NOT promoted here. See the block
        // comment above for the full rationale.

        // ------------------------------------------------------------------
        // Propagation of the argument's status onto the allocation sites
        // that are reachable from the corresponding formal parameter.
        //
        // If the callee stores fresh allocations into the fields of its
        // parameter i (see EscapeSummary.sitesReachableFromParams), those
        // sites are reachable from the argument that was passed at the exit
        // of the callee. Their status is therefore not below the status of
        // the argument — with one refinement: a STACK argument still gives
        // HEAP, because the fields of a STACK owner do not go through
        // destructors and the object stored in them is orphaned without an
        // explicit free.
        //
        // The case this is needed for:
        //
        //     InternalLock()              // <init>
        //         this.lock = new ReentrantLock();
        //
        //     InternalLock.newLockOrNull()
        //         return new InternalLock();     // this — RETURN
        //
        //     PrintStream.<init>
        //         this.lock = InternalLock.newLockOrNull();
        //
        // Without this block the ReentrantLock is classified as HEAP, the
        // lifetime analysis places a destructor at the PUT_FIELD point and
        // nulls out the field. Calling PrintStream.println then yields the
        // NPE "Cannot invoke jdk.internal.misc.InternalLock.lock(V) because
        // receiver of ReentrantLock.lock()V is null".
        //
        // With this block the ReentrantLock gets RETURN from newLockOrNull
        // (because `this` is returned there) and, on top of that, GLOBAL
        // from PrintStream (because PrintStream leaks into System.out). No
        // destructor is placed at the PUT_FIELD point, the field stays
        // alive, and the object is freed by the ordinary destructor chain
        // at shutdown.
        // ------------------------------------------------------------------
        for (int i = 0; i < args.size(); i++) {
            Set<AllocationSite> sitesInParam =
                summary.getSitesReachableFromParams().get(i);
            if (sitesInParam == null || sitesInParam.isEmpty()) continue;

            EscapeStatus argEsc = valueEscapeStatus(args.get(i));
            // max(HEAP, argEsc): a STACK argument gives HEAP (the site is
            // orphaned in the field), RETURN gives RETURN, GLOBAL gives
            // GLOBAL, THREAD gives THREAD.
            EscapeStatus promoted = maxEscape(EscapeStatus.HEAP, argEsc);
            for (AllocationSite site : sitesInParam) {
                markEscaped(site, promoted);
            }
        }

        // ------------------------------------------------------------------
        // Args-in-return edges.
        //
        // If the callee reports that parameter i is reachable from the
        // returned value (paramsEscapedToReturn), the edge
        //     args[i] -> returnValue
        // is added to localEscapeEdges. The fixed point in analyze() raises
        // the status of args[i] to the status of the return value. If the
        // returned value becomes HEAP (for instance because the object is
        // stored into a field of some container), the argument becomes HEAP
        // as well, and its allocation no longer receives a per-use
        // destructor.
        //
        // This is what closes D2: Map.entry(k, v) returns a
        // KeyValueHolder(k, v), so the callee's paramsEscapedToReturn is
        // {0, 1}. If the Entry later becomes HEAP, then k and v become HEAP
        // as well — regardless of whether paramsEscaped was set for them.
        // ------------------------------------------------------------------
        if (returnValue != null) {
            for (int i = 0; i < args.size(); i++) {
                if (!summary.getParamsEscapedToReturn().contains(i)) continue;
                Value arg = args.get(i);
                if (arg == null || arg == returnValue) continue;
                localEscapeEdges
                    .computeIfAbsent(arg, k -> new HashSet<>())
                    .add(returnValue);
            }
        }

        // Map the callee's parameter-to-parameter edges to the caller's
        // argument values. The escape propagation step inside analyze()
        // turns these into monotone status updates across iterations of
        // the fixed point.
        for (Map.Entry<Integer, Set<Integer>> e
                : summary.getParamEscapeEdges().entrySet()) {
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

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

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
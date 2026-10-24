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

/**
 * Intraprocedural escape analysis: computes, for every allocation site
 * that appears in one function, its best-known escape status.
 *
 * <h2>What changed relative to the previous revision</h2>
 *
 * <p>Three independent changes, all necessary for the transitive
 * interprocedural propagation to work end-to-end. Each one closes a
 * distinct hole that the previous revision left open.</p>
 *
 * <h3>Change 1 — seed from the interprocedural status map</h3>
 *
 * <p>The previous revision seeded every local allocation site to
 * {@link EscapeStatus#STACK} and read statuses exclusively from a
 * per-function map. That meant an externally-promoted site (promoted by
 * some other function's analysis) was invisible to this run of the
 * allocator. The fixed point in
 * {@link InterproceduralEscape#analyze()} could therefore not
 * propagate the promotion back to the sites reachable from the promoted
 * site, because the allocator re-derived its (stale) contributions from
 * a fresh STACK-based map every iteration.</p>
 *
 * <p>The fix is a shared {@link #seedStatus} map that
 * {@link InterproceduralEscape} hands to every worker. It is used in
 * two places:</p>
 *
 * <ul>
 *   <li>{@link #analyze()} seeds {@link #siteStatus} from it, so an
 *       external promotion is visible to the local fixed point from
 *       iteration zero;</li>
 *   <li>{@link #getMaxStatus(Value)} consults it as a lower bound, so
 *       any read of a site's status inside the local fixed point
 *       observes both the local analysis result and the external
 *       promotion.</li>
 * </ul>
 *
 * <h3>Change 2 — a value stored into a field is at minimum HEAP</h3>
 *
 * <p>The previous revision of {@link #processPutField} and
 * {@link #processAStore} computed the promoted status of the stored
 * value as {@code max(baseEsc, rhsEsc)}. When the base was a fresh
 * STACK object — the common {@code this.field = new Foo()} case inside
 * a constructor whose receiver is a stack-allocatable allocation — the
 * promoted status was {@code STACK}. The site then looked like a
 * pure-stack temporary and {@link io.github.kubyk01.application.service.optimizer.DestructorInserter}
 * (which filters STACK sites) never emitted a destructor for it. The
 * object leaked.</p>
 *
 * <p>The fix promotes the stored value to {@code max(baseEsc, HEAP)}.
 * The reasoning: the stored value is now reachable through a field.
 * Even when the field's owner is stack-allocated, the owner's fields are
 * not released by any destructor (the frame teardown releases the
 * owner, not its fields individually), so the value stored in the field
 * is orphaned unless it is explicitly freed at its last use. That is
 * exactly the {@code HEAP} status's semantics, and it is the correct
 * minimum for any value that is stored into any field of any object.</p>
 *
 * <h3>Change 3 — propagate argument status to sites reachable from the
 * parameter</h3>
 *
 * <p>The previous revision never looked at
 * {@link EscapeSummary#getSitesReachableFromParams()}. That meant the
 * transitive site information computed by {@link EscapeSummaryBuilder}
 * was written into the summary but never used by the caller. The
 * consequence was that an object stored into a field of a parameter
 * by the callee (e.g. {@code InternalLock.this.lock = new ReentrantLock()}
 * inside {@code InternalLock.<init>}) was not promoted when the caller
 * promoted the parameter's object to {@code RETURN} or {@code GLOBAL}.</p>
 *
 * <p>The fix is a new propagation block in {@link #processCall}. For
 * every argument {@code i}: if {@code summary.sitesReachableFromParams}
 * contains an entry for {@code i}, then every site in that entry is
 * promoted to {@code max(HEAP, status(args[i]))}. The
 * {@code max(HEAP, …)} floor is the same reasoning as Change 2: the
 * site ended up in a field, so it is at minimum {@code HEAP}. The upper
 * part of the {@code max} is the argument's actual escape status:
 * if the argument is {@code RETURN}, the site is {@code RETURN}; if
 * {@code GLOBAL}, the site is {@code GLOBAL}.</p>
 *
 * <p>Together with the transitive
 * {@code sitesReachableFromParams} computed by
 * {@link EscapeSummaryBuilder}, this closes the {@code BufferedWriter.cb}
 * crash: the {@code char[] cb} site lives three function boundaries
 * away from the {@code System.out} static field that promotes the whole
 * chain to {@code GLOBAL}, and only the combination of transitive
 * summary computation and this propagation block reaches it.</p>
 *
 * <h2>Fixed point</h2>
 *
 * <p>The local analysis iterates until no site's status changes
 * (bounded by {@code MAX_ITERATIONS}). Each iteration walks all
 * instructions, then propagates the {@link #localEscapeEdges} that
 * {@link #processCall} accumulated. Both operations are monotone:
 * {@link #markEscaped(AllocationSite, EscapeStatus)} only ever raises a
 * site's status, and the escape-edge propagation only ever raises the
 * status of {@code from} towards the status of {@code to}. The fixed
 * point therefore exists and is reached in at most
 * {@code latticeHeight × sites × 2} steps.</p>
 */
@Slf4j
public class IntraproceduralEscape {

    private final Function function;
    private final AliasAnalysisResult aliasResult;
    private final Map<String, EscapeSummary> summaries;
    private final DependencyResolver resolver;

    /**
     * Per-allocation-site escape status, computed by this run's local
     * fixed point. Never downgrades a seed value: seeding writes the
     * seed verbatim, and {@link #markEscaped(AllocationSite, EscapeStatus)}
     * uses a strictly-greater comparison.
     */
    private final Map<AllocationSite, EscapeStatus> siteStatus = new HashMap<>();

    /**
     * Per-(site, field) escape level of the value(s) currently stored in
     * that field. Used by {@link #processGetField} to propagate the
     * escape level of a field read to its result.
     */
    private final Map<AllocationSite, Map<String, EscapeStatus>> fieldStatus =
            new HashMap<>();

    private final NativeBodyEscapeAnalyzer nativeAnalyzer;

    /**
     * Local parameter-to-parameter escape edges, populated from callee
     * summaries during {@link #processCall}. Each entry
     * {@code from -> {to}} means "the object referenced by {@code from}
     * is reachable from the object referenced by {@code to}". The
     * fixed-point loop in {@link #analyze} propagates {@code to}'s
     * escape status to {@code from}, so that when {@code to} becomes
     * {@code RETURN} the {@code from} site is promoted to {@code RETURN}
     * as well.
     *
     * <p>The concrete case this closes is {@code ConcurrentHashMap}
     * created in {@code newKeySet()} and stored into a {@code KeySetView}
     * that is returned: the map is reachable from the returned view, so
     * its escape status must be at least {@code RETURN}, and no per-use
     * destructor may be inserted for it. Without this mechanism the map
     * is classified as {@code HEAP}, a destruction point is created for
     * it, and the destructor frees the map before the caller can use the
     * returned view.</p>
     */
    private final Map<Value, Set<Value>> localEscapeEdges = new HashMap<>();

    /**
     * Shared interprocedural status, produced by previous iterations of
     * {@link InterproceduralEscape#analyze()} and handed to every worker
     * via the full constructor. It represents a lower bound on every
     * site's true status: the local fixed point may raise a site above
     * its seed but never below.
     *
     * <p>Read in two places:</p>
     * <ul>
     *   <li>{@link #analyze()} seeds {@link #siteStatus} from it before
     *       the local fixed point begins;</li>
     *   <li>{@link #getMaxStatus(Value)} takes {@code max(local, seed)}
     *       for every site, so an external promotion is visible even
     *       when the local analysis has not (yet) rediscovered it.</li>
     * </ul>
     *
     * <p>Concurrent reads from multiple worker threads are safe because
     * {@link InterproceduralEscape} passes a {@link java.util.concurrent.ConcurrentHashMap}.
     * Writes happen only on the main thread between batches, so this
     * map is effectively read-only from a worker's perspective during
     * the batch.</p>
     */
    private final Map<AllocationSite, EscapeStatus> seedStatus;

    /** Reset at the start of every fixed-point iteration. */
    private boolean changed;

    /**
     * Full constructor used by {@link InterproceduralEscape}. The
     * {@code seedStatus} map is the shared interprocedural status
     * produced by previous iterations; it is consulted during seeding
     * and by {@link #getMaxStatus(Value)}.
     *
     * <p>Passing {@code null} collapses to "no seed", equivalent to the
     * compatibility constructor below.</p>
     */
    public IntraproceduralEscape(Function function,
                                 AliasAnalysisResult aliasResult,
                                 Map<String, EscapeSummary> summaries,
                                 DependencyResolver resolver,
                                 Map<AllocationSite, EscapeStatus> seedStatus) {
        this.function = function;
        this.aliasResult = aliasResult;
        this.summaries = summaries;
        this.resolver = resolver;
        this.nativeAnalyzer = new NativeBodyEscapeAnalyzer(resolver);
        this.seedStatus = (seedStatus != null) ? seedStatus : Map.of();
    }

    public Map<AllocationSite, EscapeStatus> analyze() {
        // ------------------------------------------------------------------
        // Seed every local allocation site from the shared interprocedural
        // status map. A site promoted by any function in any previous batch
        // is immediately visible here, without waiting for the local fixed
        // point to rediscover the promotion through the call graph.
        //
        // The seed is a lower bound only. markEscaped uses strictly-greater
        // comparison, so a seed never downgrades a site's status: if the
        // local analysis finds RETURN and the seed was HEAP, the site stays
        // RETURN; if the local analysis finds STACK and the seed was
        // GLOBAL, the site is promoted to GLOBAL by the very first read of
        // getMaxStatus.
        // ------------------------------------------------------------------
        for (BasicBlock block : function.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (isAllocation(inst.getOpcode()) && inst.getResult() != null) {
                    PointsToSet pts = aliasResult.getPointsTo(inst.getResult());
                    for (AllocationSite site : pts.getSites()) {
                        EscapeStatus seed = seedStatus.getOrDefault(
                                site, EscapeStatus.STACK);
                        siteStatus.put(site, seed);
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

    /**
     * Handles a field write.
     *
     * <p>The base is mutated in place; it does NOT escape. Only the
     * stored value escapes into the field, and it does so at a level
     * that is at least {@link EscapeStatus#HEAP} regardless of the
     * base's own status.</p>
     *
     * <p>The reason for the {@code max(baseEsc, HEAP)} floor on the
     * stored value is subtle and load-bearing. The base may be a fresh
     * STACK allocation whose lifetime is bounded by the current frame.
     * When such an object is destroyed, its fields are NOT walked by any
     * destructor: the frame teardown releases the base's storage and the
     * references held in its fields are simply orphaned. The stored
     * value must therefore be freed at its last use, which is exactly
     * the semantics of the {@code HEAP} status. The previous revision
     * used {@code baseEsc} directly and left the value at {@code STACK},
     * which caused {@link io.github.kubyk01.application.service.optimizer.DestructorInserter}
     * to skip the site entirely (STACK sites are filtered) and the value
     * to leak.</p>
     *
     * <p>Symmetrically, when the base is at {@code RETURN} or
     * {@code GLOBAL}, the stored value must be at that same level: it is
     * reachable from a returned/global object, so its lifetime is bounded
     * by the same entity. {@code max(baseEsc, HEAP)} covers both
     * directions in one expression.</p>
     */
    private void processPutField(Instruction inst) {
        if (inst.getOperands().size() < 3) return;

        Value base = inst.getOperands().get(0);
        Value rhs  = inst.getOperands().get(2);
        String field = extractFieldName(inst);
        String[] ownerAndField = extractFieldOwnerAndName(inst);
        String owner = ownerAndField[0];
        String fieldName = ownerAndField[1];

        EscapeStatus baseEsc = valueEscapeStatus(base);

        // A value stored into a field is at minimum HEAP: even a
        // STACK-allocated owner does not release its fields through any
        // destructor, so the value would be orphaned without an explicit
        // free at its last use.
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

    /**
     * Handles an array store. Same reasoning as
     * {@link #processPutField}: the array is mutated in place, the array
     * reference does not escape, and the stored value escapes at a level
     * that is at least {@code max(arrayEsc, HEAP)} — with the same
     * justification that a value stored into any storage the frame
     * teardown does not walk is at minimum {@code HEAP}.
     */
    private void processAStore(Instruction inst) {
        if (inst.getOperands().size() < 3) return;
        Value array = inst.getOperands().get(0);
        Value value = inst.getOperands().get(2);
        if (value == null) return;

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
        // Propagation through sitesReachableFromParams.
        //
        // For every argument i, the callee's summary lists the allocation
        // sites that were stored into fields of the callee's parameter i
        // during the callee's execution. Those sites are, by construction,
        // now reachable from the argument's object. Their true escape
        // status is therefore at least max(HEAP, status(args[i])):
        //
        //   * the max(…, HEAP) floor is the same reasoning as in
        //     processPutField / processAStore: a value that ended up in
        //     a field is at minimum HEAP, because the field's owner does
        //     not (necessarily) walk its fields through any destructor;
        //
        //   * the upper bound is the argument's own escape status: if
        //     the caller promoted the argument to RETURN or GLOBAL, the
        //     site stored inside it must be RETURN or GLOBAL as well.
        //
        // The concrete chain this closes for BufferedWriter.cb:
        //
        //   BufferedWriter.<init>:
        //       this.cb = new char[8192];
        //       -> sitesReachableFromParams[0] = {cb_site}
        //
        //   PrintStream.<init>:
        //       this.charOut = new OutputStreamWriter(out, charset);
        //       this.textOut = new BufferedWriter(charOut);
        //       -> processCall of new BufferedWriter(...) copies {cb_site}
        //          into localSiteContents of the BufferedWriter site;
        //       -> PUT_FIELD this.textOut = bwSite adds {textOut_site,
        //          cb_site, ...} to sitesReachableFromParams[0].
        //
        //   System.<clinit>:
        //       System.out = new PrintStream(...);
        //       -> PUT_STATIC promotes the PrintStream site to GLOBAL;
        //       -> THIS block promotes every site reachable from the
        //          PrintStream site (textOut_site, cb_site, ...) to
        //          GLOBAL, because PrintStream.<init>'s summary lists
        //          them in sitesReachableFromParams[0].
        //
        // Without this block, the promotion stops at the first function
        // boundary and cb_site remains HEAP; LifetimeAnalyzer then places
        // a destruction point at PUT_FIELD this.cb = ..., DestructorInserter
        // emits a free and a null-out, and the next write to BufferedWriter
        // loads a null array. String.getChars reports "Cannot invoke
        // java.lang.String.getChars(IICIV) because <array> is null".
        //
        // With this block, cb_site is promoted to GLOBAL, LifetimeAnalyzer
        // returns early on GLOBAL (no destruction point), DestructorInserter
        // emits nothing, and BufferedWriter.cb holds a valid array for the
        // rest of the process lifetime.
        // ------------------------------------------------------------------
        for (int i = 0; i < args.size(); i++) {
            Set<AllocationSite> sitesInParam =
                    summary.getSitesReachableFromParams().get(i);
            if (sitesInParam == null || sitesInParam.isEmpty()) continue;

            EscapeStatus argEsc = valueEscapeStatus(args.get(i));
            // max(HEAP, argEsc): a site that ended up in a field is at
            // minimum HEAP; if the argument itself is RETURN or GLOBAL,
            // the site inherits that status.
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

    /**
     * Returns the maximum escape status over all allocation sites the
     * value may point to, taking the local fixed-point result and the
     * shared interprocedural seed as independent lower bounds.
     *
     * <p>The two sources are combined with {@code max}, not with any
     * preference for one over the other:</p>
     *
     * <ul>
     *   <li>the seed reflects promotions observed by any previous
     *       {@link InterproceduralEscape} batch, including promotions
     *       triggered from callers of the current function;</li>
     *   <li>the local map reflects what this run's fixed point has
     *       derived from the current function's own instructions and
     *       from the summaries of its callees.</li>
     * </ul>
     *
     * <p>Both are sound lower bounds on the true escape status of the
     * site, so the max of the two is also a sound lower bound and is
     * the tightest bound derivable from the two sources
     * independently.</p>
     *
     * <p>Without the seed, the {@code BufferedWriter.cb} chain breaks
     * exactly here: the allocator of {@code cb} would compute
     * {@code STACK} on every re-run, regardless of how far its callers
     * had promoted the site.</p>
     */
    private EscapeStatus getMaxStatus(Value v) {
        if (v == null) return EscapeStatus.STACK;
        EscapeStatus max = EscapeStatus.STACK;
        for (AllocationSite site : aliasResult.getPointsTo(v).getSites()) {
            EscapeStatus local = siteStatus.getOrDefault(site, EscapeStatus.STACK);
            EscapeStatus seed  = seedStatus.getOrDefault(site, EscapeStatus.STACK);
            EscapeStatus st = (local.ordinal() >= seed.ordinal()) ? local : seed;
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
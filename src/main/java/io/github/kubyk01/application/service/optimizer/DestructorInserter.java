package io.github.kubyk01.application.service.optimizer;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.ssa.DominatorTree;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.*;
import io.github.kubyk01.util.LlvmUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.Opcodes;

import java.util.*;

@Slf4j
@RequiredArgsConstructor
public class DestructorInserter {

    private final Module module;
    private final DependencyResolver resolver;
    private final LifetimeAnalysisResult lifetimeResult;
    @Getter
    private final Map<AllocationSite, Value> siteToValue;
    private final AliasAnalysisResult aliasResult;
    private final EscapeAnalysisResult escapeResult;

    private final Map<Function, DominatorTree> domTrees = new HashMap<>();

    /**
     * Types with at least one {@code NEW}, {@code NEW_ARRAY} or
     * {@code MULTI_NEW_ARRAY} in the module, closed under the
     * "array-of-array" relation.
     *
     * <p>System-class reference types (String, Integer, Object, …) are
     * excluded: the runtime never generates destructors for them and
     * therefore never needs them as entry points here. Arrays are kept
     * (their own destructors are still emitted), but {@code [LString;}
     * style arrays are downgraded to a trivial "free the array only"
     * destructor — see {@link #createArrayDestructor(Type)}.</p>
     */
    private Set<Type> allocatedTypes = Collections.emptySet();

    public void insert() {
        this.allocatedTypes = collectAllocatedTypes();
        log.debug("DestructorInserter: {} distinct allocated type(s)",
            allocatedTypes.size());

        // ------------------------------------------------------------------
        // Emit one destructor per allocated type. Non-array reference
        // types get a destructor only when they have their own NEW site;
        // arrays always get one, with the array-of-array closure ensuring
        // that the inner-array destructors exist as well.
        //
        // System-class reference types are never in allocatedTypes (see
        // collectAllocatedTypes), so findDestructor returns null for them
        // and no destructor is emitted.
        // ------------------------------------------------------------------
        int generated = 0;
        for (Type type : allocatedTypes) {
            Function dtor = findDestructor(type);
            if (dtor != null) generated++;
        }

        // ------------------------------------------------------------------
        // Field-overwrite instrumentation.
        //
        // Every reference-typed PUT_FIELD is preceded by a release of the
        // previous field value, PROVIDED the whole-program points-to graph
        // and the block-level liveness analysis prove that no live value
        // in this function (or in any successor block) can observe that
        // previous value after the store.
        //
        // Without this pass, HEAP-escape sites have no destruction point
        // at all (LifetimeAnalyzer.computeSite short-circuits on HEAP),
        // and every intermediate value stored into an overwritten field is
        // orphaned with no GC to reclaim it. The TestHeap benchmark's
        // `h.payload = new Payload(...)` in a loop is the canonical case.
        //
        // The liveness gate is what makes the pass safe against
        // HashMap.resize's `table = newTab`, where the old table is still
        // referenced by a local variable in the subsequent loop. Without
        // the gate, that store would free the array that the loop is
        // about to walk, and the loop would read from freed memory.
        //
        // The pass runs after the destructor bodies for every allocated
        // type have been emitted, so the destructor lookup inside
        // collectDestructorTypes is guaranteed to find a matching
        // __destruct_* symbol for every type that has a NEW/NEW_ARRAY in
        // the module.
        // ------------------------------------------------------------------
        int instrumented = instrumentFieldOverwrites(lifetimeResult.getLiveOut());
        if (instrumented > 0) {
            log.info("DestructorInserter: instrumented {} field-overwrite release(s)",
                instrumented);
        }

        // ------------------------------------------------------------------
        // Insert a destructor call at every destruction point. The call
        // is a direct STATIC_CALL to the site type's destructor — the
        // site type is always a concrete class (abstract classes cannot
        // have NEW instructions) or an array type, so this is well-
        // typed. Fields INSIDE the destructor use virtual dispatch (see
        // createClassDestructor); only the top-level site call is
        // static.
        //
        // HEAP sites whose containing field cannot be located are
        // deliberately skipped — see insertDestructorCall() for the full
        // rationale and for the concrete TestHeap crash that motivated
        // the guard.
        // ------------------------------------------------------------------
        for (Map.Entry<AllocationSite, Set<DestructionPoint>> entry
            : lifetimeResult.getDestructionPoints().entrySet()) {

            AllocationSite site = entry.getKey();

            // STACK objects live in the stack frame; their fields are
            // released through the frame teardown, so a per-use
            // destructor is not needed for them.
            if (escapeResult.getSiteStatus(site) == EscapeStatus.STACK) continue;

            Type type = site.getType();
            if (type.isUnknown() || type.isNull()) continue;

            // ------------------------------------------------------------------
            // System-class reference sites are deliberately skipped here.
            //
            // findDestructor(SystemClass) would return null (isManagedType
            // filters them), and the fallback warning below would flood
            // the log with one warning per allocation site for every
            // String, Integer, etc. allocated by the JDK's own bootstrap.
            // The absence of a destructor is not a defect — it is the
            // policy — so the site is silently left for the process-wide
            // lifetime. Arrays are handled by findDestructor via
            // createArrayDestructor and are not covered by this check.
            // ------------------------------------------------------------------
            if (type.isReference()) {
                String cls = type.getClassName();
                if (cls != null && !cls.isEmpty() && cls.charAt(0) != '['
                    && LlvmUtil.isSystemClassName(cls)) {
                    continue;
                }
            }

            Function dtor = findDestructor(type);
            if (dtor == null) {
                log.warn("DestructorInserter: no destructor for allocation "
                    + "site {} — the site will not be freed", site);
                continue;
            }
            for (DestructionPoint dp : entry.getValue()) {
                if (!isLiveReference(dp.getObjectRef())) continue;
                if (!objectRefAvailableAt(dp.getObjectRef(), dp.getBlock())) continue;
                insertDestructorCall(site, dp, dtor);
            }
        }
        log.info("Destructor insertion: {} destructor(s) generated",
            generated);
    }

    // =====================================================================
    //  Field-overwrite instrumentation
    // =====================================================================

    /**
     * Counter that makes the labels of the blocks created by a block split
     * unique within the module.
     */
    private int splitId = 0;

    /**
     * Instruments every PUT_FIELD that stores a reference-typed value into a
     * reference-typed field, whose previous value is provably dead, with a
     * release sequence that calls the concrete destructor of every site the
     * field could have held.
     *
     * <p>Two code shapes are used:</p>
     *
     * <ul>
     *   <li><b>Monomorphic</b> (one concrete destructor for all sites, and
     *       no system-class sites were filtered out):
     *       inline {@code GET_FIELD / icmp / or / select / call} inserted
     *       directly before the PUT_FIELD. No CFG restructuring, no runtime
     *       dispatch, one direct call to {@code __destruct_<Type>}.</li>
     *
     *   <li><b>Polymorphic</b> (two or more distinct destructors, OR one
     *       destructor plus at least one filtered system-class site):
     *       the block is split at the PUT_FIELD, and a static
     *       vtable-dispatch chain is emitted — one {@code icmp} per
     *       distinct vtable, branching to a block that calls the
     *       corresponding concrete destructor. All function pointers are
     *       resolved at compile time.
     *
     *       <p>The polymorphic path is chosen whenever the field's
     *       points-to set contains a system-class reference site, even
     *       when only one user-class destructor exists. Without this, the
     *       monomorphic path would emit a direct call to the sole user
     *       class's destructor and would invoke it on any system-class
     *       value the field actually holds at runtime — a vtable mismatch
     *       that corrupts the heap.</p></li>
     * </ul>
     *
     * @return the number of blocks that were processed
     */
    private int instrumentFieldOverwrites(
        Map<BasicBlock, Set<AllocationSite>> liveOut) {
        if (liveOut == null || liveOut.isEmpty()) {
            log.warn("DestructorInserter: no live-out map was published; "
                + "field-overwrite releases will not be emitted.");
            return 0;
        }

        Deque<BasicBlock> pending = new ArrayDeque<>();
        for (Function func : new ArrayList<>(module.getFunctions())) {
            if (func.getEntryBlock() == null) continue;
            pending.addAll(new ArrayList<>(func.getBlocks()));
        }

        int processed = 0;
        while (!pending.isEmpty()) {
            BasicBlock block = pending.poll();
            BasicBlock tail = instrumentOneBlock(block, liveOut);
            if (tail != null) {
                pending.add(tail);
            }
            processed++;
        }
        return processed;
    }

    /**
     * Processes the PUT_FIELDs of one block.
     *
     * @return the new tail block if the block was split, or {@code null}
     *         if every qualifying PUT_FIELD was handled inline. The caller
     *         must enqueue the returned tail for further processing.
     */
    private BasicBlock instrumentOneBlock(
        BasicBlock block,
        Map<BasicBlock, Set<AllocationSite>> liveOut) {
        List<Instruction> insts = block.getInstructions();
        int i = 0;
        while (i < insts.size()) {
            Instruction inst = insts.get(i);
            if (inst.getOpcode() != Opcode.PUT_FIELD
                || !shouldInstrumentPutField(inst)
                || !oldValueIsDead(inst, block, liveOut)) {
                i++;
                continue;
            }

            DtorCollection dc = collectDestructorTypes(inst);

            if (dc.types().isEmpty()) {
                // No destructor exists for any possible site type.
                i++;
                continue;
            }

            // Monomorphic release is only safe when the field is provably
            // populated exclusively by sites that resolve to the single
            // destructor we have. If any system-class site was filtered
            // out, the field could hold a value whose vtable does not
            // match, so the polymorphic vtable-checked chain must be used.
            if (dc.types().size() == 1 && !dc.hasFilteredSystemTypes()) {
                // Monomorphic: inline select + direct call.
                List<Instruction> release =
                    emitMonomorphicRelease(inst, block, dc.types().getFirst());
                insts.addAll(i, release);
                i += release.size() + 1;  // release + PUT_FIELD
            } else {
                // Polymorphic: split the block, emit vtable-dispatch chain.
                // Even a single-type chain goes through this path when a
                // system-class site was filtered — the chain then consists
                // of exactly one vtable check, and values of any other type
                // fall through to the tail without a release.
                return emitPolymorphicRelease(inst, block, i, dc.types());
            }
        }
        return null;
    }

    /**
     * Result of {@link #collectDestructorTypes(Instruction)}.
     *
     * @param types                   distinct destructor targets, one entry
     *                                per unique {@code __destruct_*} symbol,
     *                                in deterministic insertion order
     * @param hasFilteredSystemTypes  true when at least one site in the
     *                                field's points-to set was a system-class
     *                                reference (which has no destructor and
     *                                was therefore dropped from {@code types});
     *                                forces the polymorphic path so that
     *                                runtime vtable checks prevent a
     *                                user-class destructor from being called
     *                                on a system-class instance
     */
    private record DtorCollection(List<Type> types,
                                  boolean hasFilteredSystemTypes) {}

    /**
     * Collects the set of distinct destructor target types for the sites
     * that may currently live in the field, and reports whether any
     * system-class site was filtered out along the way.
     *
     * <p>System-class sites (String, Integer, Object, …) are dropped from
     * the returned {@code types} list — no destructor exists for them,
     * and none should be emitted. The {@code hasFilteredSystemTypes} flag
     * records their presence so the caller can force polymorphic dispatch
     * and preserve soundness for the mixed case.</p>
     *
     * <p>If two sites share the same destructor (either because they are
     * of the same type, or because {@link #findDestructor} resolved them
     * to the same symbol), the result contains only one entry — this is
     * the monomorphic path.</p>
     */
    private DtorCollection collectDestructorTypes(Instruction putField) {
        if (putField.getOperands().size() < 2) {
            return new DtorCollection(List.of(), false);
        }

        Value base       = putField.getOperands().get(0);
        Value fieldConst = putField.getOperands().get(1);
        if (!(fieldConst instanceof Constant c) || !c.getType().isReference()) {
            return new DtorCollection(List.of(), false);
        }
        String fieldKey = c.getValue().toString();

        PointsToSet baseSites  = aliasResult.getPointsTo(base);
        PointsToSet fieldSites = aliasResult.getFieldPointsToForSites(
            baseSites, fieldKey);

        // name → representative Type (deterministic insertion order).
        Map<String, Type> byDtorName = new LinkedHashMap<>();
        boolean hasFilteredSystemTypes = false;

        for (AllocationSite site : fieldSites.getSites()) {
            if (site == AllocationSite.UNKNOWN) continue;

            Type siteType = site.getType();
            if (siteType == null
                || siteType.isUnknown()
                || siteType.isNull()
                || siteType.isBlock()
                || siteType.isPrimitive()) {
                continue;
            }

            // Record — but do not include — system-class reference sites.
            // Their presence forces the polymorphic dispatch path; the
            // runtime vtable check then prevents the user destructor from
            // firing on a system-class instance.
            if (siteType.isReference()) {
                String cls = siteType.getClassName();
                if (cls != null && !cls.isEmpty() && cls.charAt(0) != '['
                    && LlvmUtil.isSystemClassName(cls)) {
                    hasFilteredSystemTypes = true;
                    continue;
                }
            }

            Function dtor = findDestructor(siteType);
            if (dtor == null) {
                continue;  // no destructor for this type — skip it
            }
            byDtorName.putIfAbsent(dtor.getName(), siteType);
        }

        return new DtorCollection(
            new ArrayList<>(byDtorName.values()),
            hasFilteredSystemTypes);
    }

    /**
     * The name of the global that identifies the runtime type of a value in
     * the word at offset 0 of its header.
     *
     * <p>For an ordinary object that word is the class's {@code %JNativeVTable}
     * global; for an array it is the array's class mirror ({@code %ReflectionClass}).
     * The two live in different globals, so the name is derived differently:
     * a class internal name for objects, a JVM array descriptor for arrays.</p>
     *
     * @return the class internal name or the array descriptor, or {@code null}
     *         when the type has no addressable runtime identity
     */
    private String dispatchKeyName(Type type) {
        if (type == null) return null;
        if (type.isArray()) {
            return arrayDescriptorOf(type);
        }
        if (type.isReference()) {
            return type.getClassName();
        }
        return null;
    }

    /**
     * Reconstructs the JVM array descriptor of an array type
     * ({@code "[I"}, {@code "[Ljava/lang/String;"}, {@code "[[I"}, …).
     */
    private String arrayDescriptorOf(Type arrayType) {
        Type elem = arrayType.getElementType();
        if (elem == null) return null;
        if (elem.isArray()) {
            String inner = arrayDescriptorOf(elem);
            return inner == null ? null : "[" + inner;
        }
        if (elem.isPrimitive()) {
            if (elem == Type.BOOLEAN) return "[Z";
            if (elem == Type.BYTE)    return "[B";
            if (elem == Type.SHORT)   return "[S";
            if (elem == Type.CHAR)    return "[C";
            if (elem == Type.INT)     return "[I";
            if (elem == Type.LONG)    return "[J";
            if (elem == Type.FLOAT)   return "[F";
            if (elem == Type.DOUBLE)  return "[D";
            return null;
        }
        if (elem.isReference()) {
            return "[L" + elem.getClassName() + ";";
        }
        return null;
    }

    // =====================================================================
    //  Monomorphic release
    // =====================================================================

    /**
     * Emits the inline release sequence for a single-destructor field:
     *
     * <pre>
     *   %old     = GET_FIELD base.field
     *   %is_null = icmp eq %old, null
     *   %is_same = icmp eq %old, rhs
     *   %skip    = or  %is_null, %is_same
     *   %to_free = select %skip, null, %old
     *   call void __destruct_&lt;T&gt;(%to_free)
     * </pre>
     *
     * <p>The sequence is branchless: the {@code select} ensures that
     * {@code null} is passed to the destructor when either the field is
     * empty or the same reference is about to be stored back. Every
     * generated destructor begins with {@code __jnative_dtor_enter}, which
     * returns 0 for a {@code null} argument and causes the destructor to
     * return immediately — so a {@code null} argument is a strict no-op,
     * identical to the null check that would otherwise be required.</p>
     */
    private List<Instruction> emitMonomorphicRelease(
        Instruction putField,
        BasicBlock block,
        Type dtorType) {

        List<Instruction> out = new ArrayList<>();

        Value base       = putField.getOperands().get(0);
        Value fieldConst = putField.getOperands().get(1);
        Value rhs        = putField.getOperands().get(2);
        Type fieldType   = resolveFieldType(putField, rhs);

        Function dtor = findDestructor(dtorType);
        if (dtor == null) {
            return out;  // defensive — collectDestructorTypes already filtered
        }

        // %old = GET_FIELD base.field
        Instruction getField = new Instruction(Opcode.GET_FIELD);
        getField.addOperand(base);
        getField.addOperand(fieldConst);
        Temporary oldValue = new Temporary(fieldType);
        getField.setResult(oldValue);
        oldValue.setDefiningInstruction(getField);
        getField.setParent(block);
        out.add(getField);

        // %is_null = icmp eq %old, null
        Instruction isNull = new Instruction(Opcode.EQ);
        isNull.addOperand(oldValue);
        isNull.addOperand(new Constant(Type.NULL, null));
        Temporary isNullResult = new Temporary(Type.BOOLEAN);
        isNull.setResult(isNullResult);
        isNullResult.setDefiningInstruction(isNull);
        isNull.setParent(block);
        out.add(isNull);

        // %is_same = icmp eq %old, %rhs
        Instruction isSame = new Instruction(Opcode.EQ);
        isSame.addOperand(oldValue);
        isSame.addOperand(rhs);
        Temporary isSameResult = new Temporary(Type.BOOLEAN);
        isSame.setResult(isSameResult);
        isSameResult.setDefiningInstruction(isSame);
        isSame.setParent(block);
        out.add(isSame);

        // %skip = or %is_null, %is_same
        Instruction skip = new Instruction(Opcode.OR);
        skip.addOperand(isNullResult);
        skip.addOperand(isSameResult);
        Temporary skipResult = new Temporary(Type.BOOLEAN);
        skip.setResult(skipResult);
        skipResult.setDefiningInstruction(skip);
        skip.setParent(block);
        out.add(skip);

        // %to_free = select %skip, null, %old
        Instruction select = new Instruction(Opcode.SELECT);
        select.addOperand(skipResult);
        select.addOperand(new Constant(Type.NULL, null));
        select.addOperand(oldValue);
        Temporary toFree = new Temporary(fieldType);
        select.setResult(toFree);
        toFree.setDefiningInstruction(select);
        select.setParent(block);
        out.add(select);

        // call void @__destruct_<T>(%to_free)
        Instruction call = new Instruction(Opcode.STATIC_CALL);
        call.addOperand(new Constant(
            Type.reference(dtor.getName()), dtor.getName()));
        call.addOperand(toFree);
        call.setParent(block);
        out.add(call);

        return out;
    }

    // =====================================================================
    //  Polymorphic release
    // =====================================================================

    /**
     * Splits {@code block} at instruction index {@code instIdx} and emits a
     * static vtable-dispatch chain for a field whose sites resolve to more
     * than one concrete destructor, or whose site set included system-class
     * values that were filtered out.
     *
     * <p>Generated CFG (one type shown for brevity; the real chain has one
     * {@code check} / {@code free} pair per distinct destructor):</p>
     *
     * <pre>
     *   block:
     *     ...instructions before PUT_FIELD...
     *     %old     = GET_FIELD base.field
     *     %is_null = icmp eq %old, null
     *     %is_same = icmp eq %old, rhs
     *     %skip    = or %is_null, %is_same
     *     br i1 %skip, label %tail, label %dispatch
     *
     *   dispatch:
     *     %vtable = load i8*, i8** %old         ; safe — %old is non-null here
     *     %is_T1  = icmp eq i8* %vtable,
     *                 bitcast(%JNativeVTable* @vtable_T1 to i8*)
     *     br i1 %is_T1, label %free_T1, label %check_T2
     *
     *   free_T1:
     *     call void @__destruct_T1(i8* %old)
     *     br label %tail
     *
     *   check_T2:
     *     ...
     *
     *   tail:
     *     store %rhs, base.field
     *     ...instructions after PUT_FIELD...
     *     (original terminator)
     * </pre>
     *
     * <p>If {@code %old} does not match any expected vtable, control falls
     * straight through to {@code tail}. This happens on the system-class
     * sites that were filtered out of {@code destructorTypes}: those values
     * still exist at runtime, but the correct behaviour for them is not to
     * call a user-class destructor (which is what the fall-through
     * provides).</p>
     *
     * @return the new tail block. The caller must re-enqueue it for
     *         further PUT_FIELD processing.
     */
    private BasicBlock emitPolymorphicRelease(
        Instruction putField,
        BasicBlock block,
        int instIdx,
        List<Type> destructorTypes) {

        Function func = block.getFunction();
        List<Instruction> insts = block.getInstructions();
        Terminator oldTerm = block.getTerminator();

        // ---- 1. Split: move [instIdx, end) to a new tail block ----
        BasicBlock tail = new BasicBlock(
            block.getLabel() + "_rel_tail_" + (++splitId));
        func.addBlock(tail);

        List<Instruction> moved = new ArrayList<>(insts.subList(instIdx, insts.size()));
        for (Instruction m : moved) {
            tail.addInstruction(m);
        }
        while (insts.size() > instIdx) {
            insts.removeLast();
        }

        // ---- 2. Move terminator to tail, fix CFG links ----
        if (oldTerm != null) {
            // Remember where `block` sat in each successor's predecessor list.
            // The successor's PHI operands are indexed by that position, so
            // `tail` has to take `block`'s place rather than being appended.
            Map<BasicBlock, Integer> predIndex = new LinkedHashMap<>();
            for (BasicBlock succ : oldTerm.getTargets()) {
                if (succ == null) continue;
                int idx = succ.getPredecessors().indexOf(block);
                if (idx >= 0) predIndex.put(succ, idx);
                block.removeSuccessor(succ);
            }
            tail.setTerminator(oldTerm);
            for (Map.Entry<BasicBlock, Integer> e : predIndex.entrySet()) {
                List<BasicBlock> preds = e.getKey().getPredecessors();
                int cur = preds.indexOf(tail);
                if (cur < 0) continue;
                preds.remove(cur);
                preds.add(Math.min(e.getValue(), preds.size()), tail);
            }
        }

        // ---- 3. Copy exceptional successors and try-range membership ----
        for (BasicBlock exc : new ArrayList<>(block.getExceptionalSuccessors())) {
            tail.addExceptionalSuccessor(exc);

            // ------------------------------------------------------------------
            // Keep the handler's predecessor list consistent.
            //
            // The handler's predecessor list is the index space of the
            // handler's PHI operands: the i-th operand belongs to the i-th
            // predecessor. Adding `tail` as a new predecessor would grow
            // that list without a matching operand, and the PHI lowering
            // would store `null` for the extra edge.
            //
            // `tail` therefore takes over the edge that `block` owned. The
            // value live at the handler's entry is the same on both sides
            // of the split for every local that the SSA transformer kept
            // eligible for a PHI: a local that is stored after a throwing
            // instruction is excluded from PHI insertion by the "unsafe
            // local" rule, so no local is redefined by the instructions
            // that moved into `tail`.
            // ------------------------------------------------------------------
            List<BasicBlock> preds = exc.getPredecessors();
            int blockIdx = preds.indexOf(block);
            if (blockIdx < 0) continue;
            int tailIdx = preds.indexOf(tail);
            if (tailIdx >= 0) {
                preds.remove(tailIdx);
                if (tailIdx < blockIdx) blockIdx--;
            }
            preds.add(blockIdx, tail);
        }
        List<TryCatchRange> ranges = func.getTryCatchRanges();
        if (ranges != null) {
            for (TryCatchRange range : ranges) {
                if (range.body != null
                    && range.body.contains(block)
                    && !range.body.contains(tail)) {
                    range.body.add(tail);
                }
            }
        }

        // ---- 4. Build the effective dispatch chain.
        //
        // Every type passed in has a destructor (collectDestructorTypes
        // filtered the rest). The chain therefore contains exactly one
        // entry per distinct destructor, ordered by first occurrence in
        // the field's points-to set.
        List<Type> chainTypes = new ArrayList<>();
        for (Type t : destructorTypes) {
            if (findDestructor(t) != null && dispatchKeyName(t) != null) {
                chainTypes.add(t);
            }
        }
        if (chainTypes.isEmpty()) {
            // Nothing can be released — reconnect the block and fall through
            // to the tail as if no split had happened.
            block.setTerminator(new BranchTerminator(tail));
            return tail;
        }

        // ---- 5. Emit %old / %is_null / %is_same / %skip into `block` ----
        Value base       = putField.getOperands().get(0);
        Value fieldConst = putField.getOperands().get(1);
        Value rhs        = putField.getOperands().get(2);
        Type fieldType   = resolveFieldType(putField, rhs);

        Instruction getField = new Instruction(Opcode.GET_FIELD);
        getField.addOperand(base);
        getField.addOperand(fieldConst);
        Temporary oldValue = new Temporary(fieldType);
        getField.setResult(oldValue);
        oldValue.setDefiningInstruction(getField);
        getField.setParent(block);
        block.addInstruction(getField);

        Instruction isNull = new Instruction(Opcode.EQ);
        isNull.addOperand(oldValue);
        isNull.addOperand(new Constant(Type.NULL, null));
        Temporary isNullResult = new Temporary(Type.BOOLEAN);
        isNull.setResult(isNullResult);
        isNullResult.setDefiningInstruction(isNull);
        isNull.setParent(block);
        block.addInstruction(isNull);

        Instruction isSame = new Instruction(Opcode.EQ);
        isSame.addOperand(oldValue);
        isSame.addOperand(rhs);
        Temporary isSameResult = new Temporary(Type.BOOLEAN);
        isSame.setResult(isSameResult);
        isSameResult.setDefiningInstruction(isSame);
        isSame.setParent(block);
        block.addInstruction(isSame);

        Instruction skip = new Instruction(Opcode.OR);
        skip.addOperand(isNullResult);
        skip.addOperand(isSameResult);
        Temporary skipResult = new Temporary(Type.BOOLEAN);
        skip.setResult(skipResult);
        skipResult.setDefiningInstruction(skip);
        skip.setParent(block);
        block.addInstruction(skip);

        // ---- 6. Dispatch block ----
        BasicBlock dispatch = new BasicBlock(
            block.getLabel() + "_rel_dispatch_" + (++splitId));
        func.addBlock(dispatch);
        block.setTerminator(new CondBranchTerminator(skipResult, tail, dispatch));

        // Load vtable: GET_FIELD with integer offset 0 emits
        //   %gep  = getelementptr i8, i8* base, i32 0
        //   %cast = bitcast i8* %gep to i8**
        //   %res  = load i8*, i8** %cast
        // which is exactly the receiver's vtable word.
        Instruction loadVt = new Instruction(Opcode.GET_FIELD);
        loadVt.addOperand(oldValue);
        loadVt.addOperand(new Constant(Type.INT, 0));
        Temporary vtableValue = new Temporary(
            Type.reference("java/lang/Object"));
        loadVt.setResult(vtableValue);
        vtableValue.setDefiningInstruction(loadVt);
        loadVt.setParent(dispatch);
        dispatch.addInstruction(loadVt);

        // ---- 7. Chain: check_T1 -> free_T1 / check_T2 -> ... / tail ----
        BasicBlock current = dispatch;
        for (int i = 0; i < chainTypes.size(); i++) {
            Type t = chainTypes.get(i);
            Function dtor = findDestructor(t);
            if (dtor == null) continue;

            boolean last = (i == chainTypes.size() - 1);

            Instruction isT = new Instruction(Opcode.EQ);
            isT.addOperand(vtableValue);
            isT.addOperand(new Constant(
                Type.reference("__llvm_vtable_ptr__"),
                dispatchKeyName(t)));
            Temporary isTResult = new Temporary(Type.BOOLEAN);
            isT.setResult(isTResult);
            isTResult.setDefiningInstruction(isT);
            isT.setParent(current);
            current.addInstruction(isT);

            BasicBlock freeBlock = new BasicBlock(
                current.getLabel() + "_rel_free_" + (++splitId));
            func.addBlock(freeBlock);

            BasicBlock next;
            if (last) {
                next = tail;
            } else {
                next = new BasicBlock(
                    current.getLabel() + "_rel_next_" + (++splitId));
                func.addBlock(next);
            }

            current.setTerminator(
                new CondBranchTerminator(isTResult, freeBlock, next));

            Instruction call = new Instruction(Opcode.STATIC_CALL);
            call.addOperand(new Constant(
                Type.reference(dtor.getName()), dtor.getName()));
            call.addOperand(oldValue);
            call.setParent(freeBlock);
            freeBlock.addInstruction(call);
            freeBlock.setTerminator(new BranchTerminator(tail));

            current = next;
        }

        return tail;
    }

    /**
     * A PUT_FIELD needs the release-before-store sequence iff the value it
     * stores can carry a managed reference. Primitive writes (an int
     * stored into an int field, a long into a long field, …) never need
     * it: the value they carry is a machine scalar, with no vtable and no
     * destructor.
     */
    private boolean shouldInstrumentPutField(Instruction putField) {
        if (putField.getOperands().size() < 3) return false;
        Value rhs = putField.getOperands().get(2);
        if (rhs == null) return false;
        Type type = rhs.getType();
        if (type == null) return false;
        if (isOuterInstanceField(fieldNameOf(putField))) return false;
        return !type.isPrimitive() && !type.isVoid();
    }

    /**
     * The simple name of the field a PUT_FIELD/GET_FIELD refers to.
     *
     * <p>The field key stored in the IR is {@code owner.field} (see
     * {@link #createClassDestructor}); only the last segment is the name
     * the JVM knows the field by.</p>
     */
    private static String fieldNameOf(Instruction fieldAccess) {
        if (fieldAccess.getOperands().size() < 2) return null;
        Value fieldConst = fieldAccess.getOperands().get(1);
        if (!(fieldConst instanceof Constant c)) return null;
        String key = c.getValue().toString();
        int dot = key.lastIndexOf('.');
        return dot < 0 ? key : key.substring(dot + 1);
    }

    /**
     * True for the synthetic {@code this$N} field that {@code javac} adds to
     * an inner class to keep the reference to its enclosing instance.
     *
     * <p>That reference is <b>borrowed</b>, not owned: the enclosing
     * instance normally outlives the inner object, and it is released by
     * whoever created it. Treating it as owned makes the inner class's
     * destructor free an object that is still reachable through its real
     * owner — a double free, which the next use of the object turns into a
     * use-after-free.</p>
     */
    private static boolean isOuterInstanceField(String fieldName) {
        if (fieldName == null) return false;
        if (!fieldName.startsWith("this$")) return false;
        if (fieldName.length() == "this$".length()) return false;
        for (int i = "this$".length(); i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /**
     * Returns true iff the field's previous value is provably dead at the
     * PUT_FIELD — i.e. no allocation site that could currently be stored
     * in the field is live on exit from the block, and no such site is
     * read by any instruction following the PUT_FIELD within the same
     * block.
     *
     * <p>If this returns false, the previous value might be held by a
     * live local variable, a parameter, or an array element (the
     * HashMap.resize case), and the release must not be emitted: freeing
     * it would break the subsequent use.</p>
     *
     * <p><b>Direction of error.</b> The check errs on the side of
     * <em>not</em> releasing. Every path that could conceivably be a
     * legitimate case of "the field's previous value is still observable
     * somewhere" returns false, so the pass can only under-release. That
     * is a leak, which the containing object's own destructor chain (or
     * the shutdown pass) will eventually reclaim. Over-release — which
     * the pass must never do — would be a use-after-free, and the entire
     * reason this pass has a liveness gate at all is that the obvious
     * "any field overwrite means the old value is dead" rule produces
     * exactly that use-after-free in HashMap.resize.</p>
     */
    private boolean oldValueIsDead(
        Instruction putField,
        BasicBlock block,
        Map<BasicBlock, Set<AllocationSite>> liveOut) {

        if (putField.getOperands().size() < 2) return false;

        Value base       = putField.getOperands().get(0);
        Value fieldConst = putField.getOperands().get(1);

        // Only a field-name constant (the canonical "owner.Class.name"
        // key emitted by InstructionHandlers.getField / putField) has a
        // resolvable points-to identity. An integer-offset constant
        // (from an Unsafe-style manual field access) has no such
        // identity, and the analysis carries no information about what
        // the integer-offset store overwrote; conservatively skip it.
        if (!(fieldConst instanceof Constant c) || !c.getType().isReference()) {
            return false;
        }
        String fieldKey = c.getValue().toString();

        PointsToSet baseSites = aliasResult.getPointsTo(base);
        PointsToSet fieldSites =
            aliasResult.getFieldPointsToForSites(baseSites, fieldKey);

        // ------------------------------------------------------------------
        // The analysis has no record of anything having been stored into
        // this field on any base reachable from `base`. Two situations
        // produce that:
        //
        //   1. The field is genuinely fresh (never written since the
        //      object was allocated). Its runtime value is null, and
        //      dispatching on null is a no-op. Emitting the release is
        //      harmless — and unnecessary. Not emitting it is also
        //      harmless. We choose "not emitting" because that keeps
        //      the emitted IR smaller.
        //
        //   2. The analysis under-approximated the field's contents. In
        //      that case we cannot reason about liveness at all, and
        //      emitting a release risks freeing a still-live value.
        //
        // Both cases are handled by the same conservative answer: skip.
        // The cost of the skip is a leak that the container's own
        // destructor will reclaim; the cost of the alternative would be
        // a use-after-free.
        // ------------------------------------------------------------------
        if (fieldSites.isEmpty() || fieldSites.containsUnknown()) {
            return false;
        }

        // ------------------------------------------------------------------
        // Block-level liveness gate.
        //
        // If the map has no entry for this block, liveness information is
        // unavailable for it (the analyzer never ran, or the block was
        // created after the analysis). Conservatively do not emit.
        //
        // Otherwise: if ANY site that could currently be in the field is
        // live on exit from the block, the loaded old value might be that
        // site's object. Freeing it would break the live reference. The
        // check runs over the field's whole points-to set, not over a
        // single site, because the whole set is what the runtime can
        // observe in the field.
        // ------------------------------------------------------------------
        Set<AllocationSite> liveOutSet = liveOut.get(block);
        if (liveOutSet == null) {
            return false;
        }
        for (AllocationSite site : fieldSites.getSites()) {
            if (liveOutSet.contains(site)) {
                return false;
            }
        }

        // ------------------------------------------------------------------
        // Same-block, later-instruction gate.
        //
        // Block-level live-out only covers values used in successor
        // blocks. A value used by an instruction that appears later in
        // the *same* block is invisible to liveOut. Scan forward from the
        // PUT_FIELD and check whether any subsequent instruction's
        // operand — or the block's terminator's operand — can reach one
        // of the field's sites.
        //
        // The terminator is scanned explicitly because a value can be
        // consumed by the terminator alone (a RETURN of a value loaded
        // from the field, a COND_BRANCH on a boolean read from the field,
        // etc.).
        // ------------------------------------------------------------------
        List<Instruction> insts = block.getInstructions();
        int idx = insts.indexOf(putField);
        if (idx >= 0) {
            for (int i = idx + 1; i < insts.size(); i++) {
                for (Value op : insts.get(i).getOperands()) {
                    if (op == null) continue;
                    if (operandReachesAnySite(op, fieldSites)) {
                        return false;
                    }
                }
            }
        }
        Terminator term = block.getTerminator();
        if (term != null) {
            for (Value op : terminatorOperands(term)) {
                if (op == null) continue;
                if (operandReachesAnySite(op, fieldSites)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Returns true iff any allocation site reachable from {@code op}'s
     * points-to set is a member of {@code fieldSites}. This is the
     * "does this use touch the same object the field might hold?" test
     * — the same test the block-level liveness check performs, but
     * applied to a specific operand.
     */
    private boolean operandReachesAnySite(Value op, PointsToSet fieldSites) {
        PointsToSet opSites = aliasResult.getPointsTo(op);
        if (opSites.isEmpty()) return false;
        for (AllocationSite site : opSites.getSites()) {
            if (site == AllocationSite.UNKNOWN) continue;
            if (fieldSites.contains(site)) return true;
        }
        return false;
    }

    /**
     * Resolves the declared type of the field a PUT_FIELD writes to.
     *
     * <p>The instruction carries the field's canonical name as a
     * {@code Constant(reference, "owner/Class.name")} operand; the
     * declared type lives on the corresponding FieldNode in the
     * resolver. That is the authoritative answer — it is the same type
     * the emitter will use when it lowers the GET_FIELD instruction.</p>
     *
     * <p>The rhs's own IR type is used as a fallback when the resolver
     * cannot find the field. That only happens for synthetic fields the
     * resolver does not know about (e.g. a field written by an
     * instrumenter pass that bypassed the normal method-translation
     * path); using the value's type keeps the emitted GET_FIELD
     * structurally valid even in that case. The runtime dispatch is
     * unaffected by the declared type — it works on the receiver's
     * vtable, not on the field's declared IR type — so the fallback
     * cannot change runtime behaviour; it only avoids producing a
     * GET_FIELD with an inconsistent declared type.</p>
     */
    private Type resolveFieldType(Instruction putField, Value rhs) {
        Value fieldOperand = putField.getOperands().get(1);
        if (fieldOperand instanceof Constant c && c.getType().isReference()) {
            String fullName = c.getValue().toString();
            int lastDot = fullName.lastIndexOf('.');
            if (lastDot > 0) {
                String owner = fullName.substring(0, lastDot);
                String name  = fullName.substring(lastDot + 1);
                FieldNode node = resolver.getField(owner, name);
                if (node != null && node.getType() != null) {
                    return node.getType();
                }
            }
        }
        return rhs.getType();
    }

    /**
     * The operands a terminator consumes, in the same shape as
     * {@link Instruction#getOperands()}. Needed by the same-block
     * forward scan, which cannot see a terminator's operands through
     * {@link Instruction#getOperands()}.
     */
    private List<Value> terminatorOperands(Terminator term) {
        List<Value> result = new ArrayList<>();
        if (term instanceof ReturnTerminator rt) {
            if (rt.getValue() != null) result.add(rt.getValue());
        } else if (term instanceof ThrowTerminator tt) {
            if (tt.getException() != null) result.add(tt.getException());
        } else if (term instanceof CondBranchTerminator cbt) {
            if (cbt.getCondition() != null) result.add(cbt.getCondition());
        } else if (term instanceof LookupSwitchTerminator lst) {
            if (lst.getKey() != null) result.add(lst.getKey());
        } else if (term instanceof TableSwitchTerminator tst) {
            if (tst.getKey() != null) result.add(tst.getKey());
        } else if (term instanceof IndirectBranchTerminator ibt) {
            if (ibt.getTargetBlock() != null) result.add(ibt.getTargetBlock());
        }
        return result;
    }

    // =====================================================================
    //  Allocated-type discovery
    // =====================================================================

    private Set<Type> collectAllocatedTypes() {
        Set<Type> types = new LinkedHashSet<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    Opcode op = inst.getOpcode();
                    if (op != Opcode.NEW
                        && op != Opcode.NEW_ARRAY
                        && op != Opcode.MULTI_NEW_ARRAY) continue;
                    if (inst.getResult() == null) continue;
                    Type t = inst.getResult().getType();
                    if (t == null || t.isUnknown() || t.isNull() || t.isBlock()) continue;

                    // System-class reference allocations do not get their
                    // own destructor: their lifetime is owned by the JDK's
                    // own caches (String interning, Integer.valueOf,
                    // static singletons, etc.) which this runtime cannot
                    // reason about. Including them here would only cause
                    // findDestructor to reject them one by one.
                    if (t.isReference()) {
                        String cls = t.getClassName();
                        if (cls != null && !cls.isEmpty() && cls.charAt(0) != '['
                            && LlvmUtil.isSystemClassName(cls)) {
                            continue;
                        }
                    }

                    types.add(t);
                }
            }
        }
        // Close under the array-of-array relation. A MULTI_NEW_ARRAY of
        // "[[I" creates "[[I" from one instruction, but the inner "[I"
        // arrays are created inside __jnative_new_multi_array and are not
        // represented as separate NEW_ARRAY instructions. Their destructor
        // must still be emitted, because the outer destructor's dispatch
        // loop will call it on each element.
        Deque<Type> queue = new ArrayDeque<>(types);
        while (!queue.isEmpty()) {
            Type t = queue.poll();
            if (!t.isArray()) continue;
            Type inner = t.getElementType();
            if (inner != null && inner.isArray() && types.add(inner)) {
                queue.add(inner);
            }
        }
        return types;
    }

    // =====================================================================
    //  Destructor lookup / creation
    // =====================================================================

    private Function findDestructor(Type type) {
        if (type == null) return null;
        if (!isManagedType(type)) return null;
        if (type.isArray()) return createDestructor(type);
        if (!allocatedTypes.contains(type)) return null;
        return createDestructor(type);
    }

    /**
     * Returns true iff a destructor should be generated for {@code type}.
     *
     * <p>System-class reference types (String, Integer, Object, Number,
     * Exception, …) are deliberately excluded. Their lifetime is owned by
     * the JDK's own caches, singletons and static tables, which this
     * runtime's interprocedural analysis does not model. Generating a
     * per-use destructor for such a value risks freeing an object that is
     * still referenced from a static cache (String.intern, Integer's
     * small-value cache, ClassValue maps, …) and turning the next read of
     * that object into a use-after-free.</p>
     *
     * <p>Arrays are always considered managed: the array's storage is a
     * plain heap allocation that the runtime owns, regardless of what its
     * elements point to. The element destruction policy is decided inside
     * {@link #createArrayDestructor(Type)} and may itself be downgraded to
     * a trivial "free the array only" — for example when the element type
     * is a final system class such as {@code String}.</p>
     */
    private boolean isManagedType(Type type) {
        if (type == null) return false;
        if (type.isUnknown() || type.isNull() || type.isBlock()) return false;
        if (type.isPrimitive()) return false;
        if (type.isArray()) return true;
        if (type.isReference()) {
            String className = type.getClassName();
            if (className == null || className.isEmpty()) return false;

            // System-class reference — not managed by this pass.
            if (LlvmUtil.isSystemClassName(className)) return false;

            ClassNode cn = resolver.getClassNode(className);
            return !(cn != null && cn.isExternal());
        }
        return false;
    }

    private Function createDestructor(Type type) {
        if (!isManagedType(type)) return null;
        return type.isArray()
            ? createArrayDestructor(type)
            : createClassDestructor(type.getClassName());
    }

    private String destructorName(Type type) {
        if (type == null) return "__destruct_unknown";
        if (type.isArray()) {
            return "__destruct_array_"
                + type.toString().replaceAll("[^a-zA-Z0-9_.]", "_");
        }
        if (type.isReference()) {
            return "__destruct_"
                + type.getClassName().replace('/', '_').replace('.', '_');
        }
        return "__destruct_unknown";
    }

    /**
     * Wires the destructor entry block into the shared prologue that checks
     * the shutdown flag, then either runs {@code __jnative_dtor_enter} (slow
     * path) or jumps straight to {@code bodyFast} (fast path).
     *
     * <p>Fast path (every call outside shutdown):</p>
     * <pre>
     *   %shutdown = load atomic i32, i32* @gv_jnative_in_shutdown acquire
     *   %in_shutdown = icmp ne i32 %shutdown, 0
     *   br i1 %in_shutdown, label %slow, label %bodyFast
     * </pre>
     *
     * <p>The load is atomic acquire (matching the release store in
     * {@code __jnative_begin_shutdown}).  ThinLTO at -O2 inlines this into
     * every call site, so the fast path becomes a single load, a compare and
     * a branch — three instructions, no frame, no call.</p>
     *
     * <p>Slow path (only during {@code __jnative_shutdown}):</p>
     * <pre>
     * slow:
     *   %r = call i32 @__jnative_dtor_enter(i8* %this)
     *   %skip = icmp eq i32 %r, 0
     *   br i1 %skip, label %return, label %bodySlow
     * </pre>
     *
     * <p>{@code bodyFast} and {@code bodySlow} acquire a second predecessor
     * ({@code slow}) in addition to the original {@code entry}.  No PHI node
     * exists in a generated destructor, so nothing needs to be renumbered.</p>
     */
private void emitDestructionGuard(BasicBlock entry,
                                       Parameter thisParam,
                                       BasicBlock returnBlock,
                                       BasicBlock bodyFast,
                                       BasicBlock bodySlow) {
        Function func = entry.getFunction();
        if (func == null) {
            throw new IllegalStateException(
                "emitDestructionGuard: entry block '" + entry.getLabel()
                    + "' is not attached to any Function");
        }

        // ---------------------------------------------------------------
        // Null guard: деструктор вызывается с null как no-op.
        //
        // Контракт (см. emitMonomorphicRelease / emitPolymorphicRelease):
        // когда освобождать нечего, инструментация перезаписи поля
        // передаёт в деструктор null через select. Деструктор ОБЯЗАН
        // вернуться немедленно, не трогая поля.
        //
        // Исторически этот контракт выполнялся только в slow-path через
        // __jnative_dtor_enter(null) == 0. Но fast-path (body_fast) —
        // это путь при gv_jnative_in_shutdown == 0, и он начинается
        // сразу с GET_FIELD / GET_STATIC, первая же null-проверка базы
        // которого бросает NPE. Наблюдаемое поведение (TestHeap):
        //
        //   NullPointerException: Cannot invoke destruct_TestHeap$Payload
        //     because field 'TestHeap$Payload.padding' is null
        //
        // Фикс: явный null-guard в entry — до любой другой работы.
        // ---------------------------------------------------------------
        Instruction isNull = new Instruction(Opcode.EQ);
        isNull.addOperand(thisParam);
        isNull.addOperand(new Constant(Type.NULL, null));
        Temporary isNullResult = new Temporary(Type.BOOLEAN);
        isNull.setResult(isNullResult);
        isNullResult.setDefiningInstruction(isNull);
        entry.addInstruction(isNull);

        BasicBlock entryAfterNull =
            new BasicBlock(entry.getLabel() + "_after_null");
        func.addBlock(entryAfterNull);

        entry.setTerminator(
            new CondBranchTerminator(isNullResult, returnBlock, entryAfterNull));
        entry.addSuccessor(returnBlock);
        entry.addSuccessor(entryAfterNull);

        // ---------------------------------------------------------------
        // Shutdown-guard — исходная логика, перенесённая в entryAfterNull.
        // ---------------------------------------------------------------
        BasicBlock slowCheck = new BasicBlock(entry.getLabel() + "_dtor_slow");
        func.addBlock(slowCheck);

        Instruction shutdownLoad = new Instruction(Opcode.GET_STATIC);
        shutdownLoad.addOperand(new Constant(
            Type.reference("jnative_in_shutdown"), "jnative_in_shutdown"));
        Temporary shutdownVal = new Temporary(Type.INT);
        shutdownLoad.setResult(shutdownVal);
        shutdownVal.setDefiningInstruction(shutdownLoad);
        entryAfterNull.addInstruction(shutdownLoad);

        Instruction inShutdown = new Instruction(Opcode.NE);
        inShutdown.addOperand(shutdownVal);
        inShutdown.addOperand(new Constant(Type.INT, 0));
        Temporary inShutdownBool = new Temporary(Type.BOOLEAN);
        inShutdown.setResult(inShutdownBool);
        inShutdownBool.setDefiningInstruction(inShutdown);
        entryAfterNull.addInstruction(inShutdown);

        entryAfterNull.setTerminator(
            new CondBranchTerminator(inShutdownBool, slowCheck, bodyFast));
        entryAfterNull.addSuccessor(slowCheck);
        entryAfterNull.addSuccessor(bodyFast);

        // ---------------------------------------------------------------
        // Slow-path: __jnative_dtor_enter + claim-check (без изменений).
        // ---------------------------------------------------------------
        Instruction enterCall = new Instruction(Opcode.STATIC_CALL);
        enterCall.addOperand(new Constant(
            Type.reference("__jnative_dtor_enter"), "__jnative_dtor_enter"));
        enterCall.addOperand(thisParam);
        Temporary enterResult = new Temporary(Type.INT);
        enterCall.setResult(enterResult);
        enterResult.setDefiningInstruction(enterCall);
        slowCheck.addInstruction(enterCall);

        Instruction skipInst = new Instruction(Opcode.EQ);
        skipInst.addOperand(enterResult);
        skipInst.addOperand(new Constant(Type.INT, 0));
        Temporary skipBool = new Temporary(Type.BOOLEAN);
        skipInst.setResult(skipBool);
        skipBool.setDefiningInstruction(skipInst);
        slowCheck.addInstruction(skipInst);

        slowCheck.setTerminator(
            new CondBranchTerminator(skipBool, returnBlock, bodySlow));
        slowCheck.addSuccessor(returnBlock);
        slowCheck.addSuccessor(bodySlow);
    }

    private void emitUnclaim(BasicBlock block, Parameter thisParam) {
        Instruction unclaim = new Instruction(Opcode.STATIC_CALL);
        unclaim.addOperand(new Constant(
            Type.reference("__jnative_dtor_unclaim"), "__jnative_dtor_unclaim"));
        unclaim.addOperand(thisParam);
        block.addInstruction(unclaim);
    }

    /**
     * Emits a call to {@code __jnative_destruct_dispatch(fieldValue)}.
     *
     * <p>The dispatch is done at runtime against the field value's vtable
     * (or, for an array, against the array's class mirror), so the
     * concrete type of the field value is resolved to the correct
     * destructor even when the field's declared type is an abstract
     * superclass, an interface, or {@code Object}.</p>
     */
    private void emitDispatchCall(BasicBlock block, Value fieldValue) {
        Instruction call = new Instruction(Opcode.STATIC_CALL);
        call.addOperand(new Constant(
            Type.reference("__jnative_destruct_dispatch"),
            "__jnative_destruct_dispatch"));
        call.addOperand(fieldValue);
        block.addInstruction(call);
    }

    /**
     * Emits the destruction of a reference held by a field (or by an array
     * element) using the CONCRETE destructor of its static type whenever
     * that is provably safe.
     *
     * <p>The runtime dispatch ({@link #emitDispatchCall}) resolves the
     * receiver's runtime type through its vtable and therefore always
     * picks the right destructor — but it costs a vtable load, a symbol
     * lookup and an indirect call for every field of every destroyed
     * object. When the static type of the field leaves no room for a
     * different runtime type, the concrete destructor can be called
     * directly:</p>
     *
     * <ul>
     *   <li><b>Array types</b> always have exactly one runtime type (the
     *       array class itself), so the array destructor is always
     *       called directly.</li>
     *
     *   <li><b>Reference types</b> are only safe when the class is final,
     *       or when the resolver knows no subclass of it. An abstract
     *       class, an interface, an external class (no bytecode
     *       available, so its hierarchy is unknown) and a class with
     *       known subclasses all keep the runtime dispatch.</li>
     * </ul>
     *
     * <p>Every other case falls back to the runtime dispatch, which is
     * always correct. In particular, for a field whose declared type is a
     * system class, the runtime dispatch call is emitted; at run time
     * {@code __jnative_destruct_dispatch} finds no {@code __destruct_*}
     * symbol for that system class (this pass never generates one) and
     * becomes a strict no-op, preserving the system-owned lifetime.</p>
     */
    private void emitFieldDestruction(BasicBlock block,
                                      Value fieldValue,
                                      Type fieldType) {
        if (fieldType == null) {
            emitDispatchCall(block, fieldValue);
            return;
        }

        if (fieldType.isArray()) {
            Function dtor = findDestructor(fieldType);
            if (dtor != null) {
                emitDestructorCall(block, dtor, fieldValue);
                return;
            }
            emitDispatchCall(block, fieldValue);
            return;
        }

        if (fieldType.isReference() && !fieldType.isUnknown() && !fieldType.isNull()) {
            String className = fieldType.getClassName();
            if (className != null && !className.isEmpty()
                && className.charAt(0) != '[') {
                ClassNode cn = resolver.getClassNode(className);
                if (cn != null && !cn.isExternal() && !cn.isInterface()
                    && (cn.getAccess() & Opcodes.ACC_ABSTRACT) == 0) {
                    boolean isFinal = (cn.getAccess() & Opcodes.ACC_FINAL) != 0;
                    boolean hasNoSubclasses =
                        resolver.getSubclasses(className).isEmpty();
                    if (isFinal || hasNoSubclasses) {
                        Function dtor = findDestructor(fieldType);
                        if (dtor != null) {
                            emitDestructorCall(block, dtor, fieldValue);
                            return;
                        }
                    }
                }
            }
        }

        // Polymorphic fields / abstract types / system-class fields:
        // only the runtime dispatch can pick the destructor of the actual
        // runtime type. For system classes the dispatch is a no-op at run
        // time because no __destruct_* symbol exists for them.
        emitDispatchCall(block, fieldValue);
    }

    /**
     * Emits a direct call to a concrete destructor.
     */
    private void emitDestructorCall(BasicBlock block,
                                    Function dtor,
                                    Value value) {
        Instruction call = new Instruction(Opcode.STATIC_CALL);
        call.addOperand(new Constant(
            Type.reference(dtor.getName()), dtor.getName()));
        call.addOperand(value);
        call.setParent(block);
        block.addInstruction(call);
    }

    /**
     * Emits the destructor body for a reference class.
     *
     * <p>For each reference-typed instance field — from this class AND
     * from every ancestor, flattened superclass-first — one destruction
     * call is emitted. Then the cycle-guard is released and the receiver
     * is freed. There is no superclass delegation: every ancestor's
     * fields are destroyed here, which means an abstract superclass needs
     * no destructor of its own (and, by {@link #findDestructor(Type)},
     * does not get one).</p>
     *
     * <p>Fields whose declared type is a system class are NOT touched:
     * the system object's lifetime is owned elsewhere (static caches,
     * singletons, interned values) and freeing it here would be unsafe.
     * {@link #emitFieldDestruction} implements the same policy for the
     * remaining (non-system) field types, choosing between direct
     * destructor calls and runtime dispatch based on the static type.</p>
     *
     * <p>The body is a <b>single</b> BasicBlock from the IR builder's
     * point of view. The {@code GET_FIELD} instruction still expands into
     * several LLVM blocks internally (its null check), but no
     * additional BasicBlock is created for the field's null check or
     * call: every destructor starts with a guard that turns a
     * {@code null} argument into an immediate return, so no
     * {@code isNull} branch is needed.</p>
     */
    private Function createClassDestructor(String className) {
        ClassNode classNode = resolver.getClassNode(className);
        if (classNode == null || classNode.isExternal()) return null;

        String funcName = destructorName(Type.reference(className));
        Function existing = module.getFunction(funcName);
        if (existing != null) return existing;

        Function func = new Function(funcName, Type.VOID);
        func.setOwnerClass(className);
        module.addFunction(func);

        Parameter thisParam = new Parameter(Type.reference(className), 0);
        func.addParameter(thisParam);

        BasicBlock entry       = new BasicBlock(funcName + "_entry");
        BasicBlock returnBlock = new BasicBlock(funcName + "_return");
        BasicBlock bodyFast    = new BasicBlock(funcName + "_body_fast");
        BasicBlock bodySlow    = new BasicBlock(funcName + "_body_slow");
        func.addBlock(entry);
        func.addBlock(returnBlock);
        func.addBlock(bodyFast);
        func.addBlock(bodySlow);
        func.setEntryBlock(entry);
        returnBlock.setTerminator(new ReturnTerminator(null));

        emitDestructionGuard(entry, thisParam, returnBlock, bodyFast, bodySlow);

        // ---- Fast path: destroy children, then __jnative_free_fast ----
        for (FieldNode field : collectAllReferenceFields(classNode)) {
            if ((field.getAccess() & Opcodes.ACC_STATIC) != 0) continue;
            Type fieldType = field.getType();
            if (!isManagedType(fieldType)) continue;

            String fieldOwner = field.getOwner();
            if (fieldOwner == null || fieldOwner.isEmpty()) fieldOwner = className;
            String fullFieldName = fieldOwner + "." + field.getName();

            Instruction getField = new Instruction(Opcode.GET_FIELD);
            getField.addOperand(thisParam);
            getField.addOperand(new Constant(
                Type.reference(fullFieldName), fullFieldName));
            Temporary fieldValue = new Temporary(fieldType);
            getField.setResult(fieldValue);
            fieldValue.setDefiningInstruction(getField);
            bodyFast.addInstruction(getField);

            emitFieldDestruction(bodyFast, fieldValue, fieldType);
        }

        Instruction freeFast = new Instruction(Opcode.STATIC_CALL);
        freeFast.addOperand(new Constant(
            Type.reference("__jnative_free_fast"), "__jnative_free_fast"));
        freeFast.addOperand(thisParam);
        bodyFast.addInstruction(freeFast);
        bodyFast.setTerminator(new ReturnTerminator(null));

        // ---- Slow path: destroy children, unclaim, __jnative_free ----
        for (FieldNode field : collectAllReferenceFields(classNode)) {
            if ((field.getAccess() & Opcodes.ACC_STATIC) != 0) continue;
            Type fieldType = field.getType();
            if (!isManagedType(fieldType)) continue;

            String fieldOwner = field.getOwner();
            if (fieldOwner == null || fieldOwner.isEmpty()) fieldOwner = className;
            String fullFieldName = fieldOwner + "." + field.getName();

            Instruction getField = new Instruction(Opcode.GET_FIELD);
            getField.addOperand(thisParam);
            getField.addOperand(new Constant(
                Type.reference(fullFieldName), fullFieldName));
            Temporary fieldValue = new Temporary(fieldType);
            getField.setResult(fieldValue);
            fieldValue.setDefiningInstruction(getField);
            bodySlow.addInstruction(getField);

            emitFieldDestruction(bodySlow, fieldValue, fieldType);
        }

        emitUnclaim(bodySlow, thisParam);
        Instruction freeInst = new Instruction(Opcode.FREE);
        freeInst.addOperand(thisParam);
        bodySlow.addInstruction(freeInst);
        bodySlow.setTerminator(new ReturnTerminator(null));

        return func;
    }

    private List<FieldNode> collectAllReferenceFields(ClassNode classNode) {
        List<FieldNode> result = new ArrayList<>();
        String superName = classNode.getSuperName();
        if (superName != null
            && !superName.equals("java/lang/Object")
            && !superName.equals(classNode.getName())) {
            ClassNode superNode = resolver.getClassNode(superName);
            if (superNode != null && !superNode.isExternal()) {
                result.addAll(collectAllReferenceFields(superNode));
            }
        }
        for (FieldNode field : classNode.getFields()) {
            if ((field.getAccess() & Opcodes.ACC_STATIC) != 0) continue;
            if (isOuterInstanceField(field.getName())) continue;
            Type ft = field.getType();
            if (ft.isReference() || ft.isArray()) result.add(field);
        }
        return result;
    }

    /**
     * Emits the destructor for an array type.
     *
     * <p>Primitive element types: the elements hold no references; the
     * destructor is {@code unclaim(arr); free(arr); ret}.</p>
     *
     * <p>Reference or array element types: the destructor normally loops
     * over the array and destroys each element, then frees the array.
     * Two exceptions downgrade this to a trivial destructor that only
     * frees the array itself (without touching the elements):</p>
     *
     * <ul>
     *   <li><b>System-class element type that is final</b> — a final
     *       class has no subclasses by construction, so every element is
     *       provably of that exact final system class. Strings and
     *       boxed primitives fall into this category. Their instances
     *       are never owned by the array (String literals are baked
     *       into the module, small Integer/Long cache entries live for
     *       the process lifetime), and iterating them would attempt a
     *       release of shared values that this runtime does not own.</li>
     *
     *   <li><b>Nested array whose element is such an array</b> — covered
     *       by the same rule, because the closure applied in
     *       {@link #collectAllocatedTypes()} ensures the inner array's
     *       destructor exists, and the outer loop's direct call to that
     *       destructor will in turn only free the inner array's storage
     *       without touching its elements.</li>
     * </ul>
     *
     * <p>Non-final system-class element types (Object, Number, Throwable,
     * …) keep the normal loop: a user class may legitimately subclass
     * them, and the runtime dispatch call resolves the correct
     * concrete destructor for every element (including elements whose
     * concrete type has no destructor — that resolves to a no-op).</p>
     *
     * <p>The element-destructor symbol for an inner-array element type is
     * guaranteed to exist because {@link #collectAllocatedTypes()}
     * closed the type set under the array-of-array relation before
     * generating any destructor.</p>
     */
    private Function createArrayDestructor(Type arrayType) {
        String funcName = destructorName(arrayType);
        Function existing = module.getFunction(funcName);
        if (existing != null) return existing;

        Type elementType = arrayType.getElementType();
        boolean elementsAreRefs = elementType.isArray() || elementType.isReference();

        if (!elementsAreRefs) {
            return createTrivialArrayDestructor(arrayType);
        }

        // ------------------------------------------------------------------
        // System-class element handling.
        //
        // A FINAL system class cannot be subclassed, so every element of
        // the array is provably of that exact class. For system-owned
        // values (String, Integer's small-value cache, …) freeing them
        // from the array destructor would orphan shared state; the array
        // therefore only frees its own storage and leaves the elements
        // untouched.
        //
        // A NON-FINAL system class (Object, Number, Throwable, …) may
        // have user-defined subclasses, and those subclasses' instances
        // genuinely belong to the array. The normal loop is kept for them;
        // per-element runtime dispatch (via emitFieldDestruction) then
        // frees the user subclass values and no-ops on system-class
        // values (there is no __destruct_* symbol for them).
        // ------------------------------------------------------------------
        if (elementType.isReference()) {
            String elemClass = elementType.getClassName();
            if (elemClass != null && !elemClass.isEmpty()
                && elemClass.charAt(0) != '['
                && LlvmUtil.isSystemClassName(elemClass)) {
                ClassNode elemNode = resolver.getClassNode(elemClass);
                boolean elemIsFinal = elemNode != null
                    && (elemNode.getAccess() & Opcodes.ACC_FINAL) != 0;
                if (elemIsFinal) {
                    return createTrivialArrayDestructor(arrayType);
                }
            }
        }

        Function func = new Function(funcName, Type.VOID);
        module.addFunction(func);

        Parameter arrParam = new Parameter(arrayType, 0);
        func.addParameter(arrParam);

        // Slot 0 is owned by the array parameter (LlvmFunctionEmitter
        // materialises every parameter into %local_<index> in the entry
        // prologue). The loop counter lives in slot 1.
        final int COUNTER_SLOT = 1;

        BasicBlock entry       = new BasicBlock(funcName + "_entry");
        BasicBlock returnBlock = new BasicBlock(funcName + "_return");
        BasicBlock bodyFast    = new BasicBlock(funcName + "_body_fast");
        BasicBlock bodySlow    = new BasicBlock(funcName + "_body_slow");
        func.addBlock(entry);
        func.addBlock(returnBlock);
        func.addBlock(bodyFast);
        func.addBlock(bodySlow);
        func.setEntryBlock(entry);
        returnBlock.setTerminator(new ReturnTerminator(null));

        emitDestructionGuard(entry, arrParam, returnBlock, bodyFast, bodySlow);

        // ---- Fast path: loop over elements, destroy each, then __jnative_free_fast ----
        // body: len = arraylength(arr); store local[1] = 0; br loop_header
        Instruction lenInstFast = new Instruction(Opcode.ARRAYLENGTH);
        lenInstFast.addOperand(arrParam);
        Temporary lenValFast = new Temporary(Type.INT);
        lenInstFast.setResult(lenValFast);
        lenValFast.setDefiningInstruction(lenInstFast);
        bodyFast.addInstruction(lenInstFast);

        Instruction counterInitFast = new Instruction(Opcode.STORE);
        counterInitFast.addOperand(new Constant(Type.INT, 0));
        counterInitFast.setLocalIndex(COUNTER_SLOT);
        Temporary counterInitResultFast = new Temporary(Type.INT);
        counterInitFast.setResult(counterInitResultFast);
        counterInitResultFast.setDefiningInstruction(counterInitFast);
        bodyFast.addInstruction(counterInitFast);

        BasicBlock loopHeaderFast = new BasicBlock(funcName + "_loop_header_fast");
        BasicBlock loopBodyFast = new BasicBlock(funcName + "_loop_body_fast");
        BasicBlock loopAfterFast = new BasicBlock(funcName + "_loop_after_fast");
        func.addBlock(loopHeaderFast);
        func.addBlock(loopBodyFast);
        func.addBlock(loopAfterFast);

        bodyFast.setTerminator(new BranchTerminator(loopHeaderFast));
        bodyFast.addSuccessor(loopHeaderFast);

        // loop_header: counter = load local[1]; if counter < len → loop_body else loop_after
        Instruction counterLoadFast = new Instruction(Opcode.LOAD);
        counterLoadFast.setLocalIndex(COUNTER_SLOT);
        Temporary counterFast = new Temporary(Type.INT);
        counterLoadFast.setResult(counterFast);
        counterFast.setDefiningInstruction(counterLoadFast);
        loopHeaderFast.addInstruction(counterLoadFast);

        Instruction cmpLoopFast = new Instruction(Opcode.LT);
        cmpLoopFast.addOperand(counterFast);
        cmpLoopFast.addOperand(lenValFast);
        Temporary cmpLoopResultFast = new Temporary(Type.BOOLEAN);
        cmpLoopFast.setResult(cmpLoopResultFast);
        cmpLoopResultFast.setDefiningInstruction(cmpLoopFast);
        loopHeaderFast.addInstruction(cmpLoopFast);
        loopHeaderFast.setTerminator(new CondBranchTerminator(cmpLoopResultFast, loopBodyFast, loopAfterFast));
        loopHeaderFast.addSuccessor(loopBodyFast);
        loopHeaderFast.addSuccessor(loopAfterFast);

        // loop_body: elem = arr[counter]; destroy(elem); counter++; store; br header
        Instruction loadElemFast = new Instruction(Opcode.ALOAD);
        loadElemFast.addOperand(arrParam);
        loadElemFast.addOperand(counterFast);
        Temporary elemValFast = new Temporary(elementType);
        loadElemFast.setResult(elemValFast);
        elemValFast.setDefiningInstruction(loadElemFast);
        loopBodyFast.addInstruction(loadElemFast);

        emitFieldDestruction(loopBodyFast, elemValFast, elementType);

        Instruction incFast = new Instruction(Opcode.ADD);
        incFast.addOperand(counterFast);
        incFast.addOperand(new Constant(Type.INT, 1));
        Temporary newCounterFast = new Temporary(Type.INT);
        incFast.setResult(newCounterFast);
        newCounterFast.setDefiningInstruction(incFast);
        loopBodyFast.addInstruction(incFast);

        Instruction storeCounterFast = new Instruction(Opcode.STORE);
        storeCounterFast.addOperand(newCounterFast);
        storeCounterFast.setLocalIndex(COUNTER_SLOT);
        Temporary storedCounterFast = new Temporary(Type.INT);
        storeCounterFast.setResult(storedCounterFast);
        storedCounterFast.setDefiningInstruction(storeCounterFast);
        loopBodyFast.addInstruction(storeCounterFast);

        loopBodyFast.setTerminator(new BranchTerminator(loopHeaderFast));
        loopBodyFast.addSuccessor(loopHeaderFast);

        // loop_after: __jnative_free_fast(arr); ret
        Instruction freeFast = new Instruction(Opcode.STATIC_CALL);
        freeFast.addOperand(new Constant(
            Type.reference("__jnative_free_fast"), "__jnative_free_fast"));
        freeFast.addOperand(arrParam);
        loopAfterFast.addInstruction(freeFast);
        loopAfterFast.setTerminator(new ReturnTerminator(null));

        // ---- Slow path: full shutdown semantics with unclaim ----
        // body: len = arraylength(arr); store local[1] = 0; br loop_header
        Instruction lenInstSlow = new Instruction(Opcode.ARRAYLENGTH);
        lenInstSlow.addOperand(arrParam);
        Temporary lenValSlow = new Temporary(Type.INT);
        lenInstSlow.setResult(lenValSlow);
        lenValSlow.setDefiningInstruction(lenInstSlow);
        bodySlow.addInstruction(lenInstSlow);

        Instruction counterInitSlow = new Instruction(Opcode.STORE);
        counterInitSlow.addOperand(new Constant(Type.INT, 0));
        counterInitSlow.setLocalIndex(COUNTER_SLOT);
        Temporary counterInitResultSlow = new Temporary(Type.INT);
        counterInitSlow.setResult(counterInitResultSlow);
        counterInitResultSlow.setDefiningInstruction(counterInitSlow);
        bodySlow.addInstruction(counterInitSlow);

        BasicBlock loopHeaderSlow = new BasicBlock(funcName + "_loop_header_slow");
        BasicBlock loopBodySlow = new BasicBlock(funcName + "_loop_body_slow");
        BasicBlock loopAfterSlow = new BasicBlock(funcName + "_loop_after_slow");
        func.addBlock(loopHeaderSlow);
        func.addBlock(loopBodySlow);
        func.addBlock(loopAfterSlow);

        bodySlow.setTerminator(new BranchTerminator(loopHeaderSlow));
        bodySlow.addSuccessor(loopHeaderSlow);

        // loop_header: counter = load local[1]; if counter < len → loop_body else loop_after
        Instruction counterLoadSlow = new Instruction(Opcode.LOAD);
        counterLoadSlow.setLocalIndex(COUNTER_SLOT);
        Temporary counterSlow = new Temporary(Type.INT);
        counterLoadSlow.setResult(counterSlow);
        counterSlow.setDefiningInstruction(counterLoadSlow);
        loopHeaderSlow.addInstruction(counterLoadSlow);

        Instruction cmpLoopSlow = new Instruction(Opcode.LT);
        cmpLoopSlow.addOperand(counterSlow);
        cmpLoopSlow.addOperand(lenValSlow);
        Temporary cmpLoopResultSlow = new Temporary(Type.BOOLEAN);
        cmpLoopSlow.setResult(cmpLoopResultSlow);
        cmpLoopResultSlow.setDefiningInstruction(cmpLoopSlow);
        loopHeaderSlow.addInstruction(cmpLoopSlow);
        loopHeaderSlow.setTerminator(new CondBranchTerminator(cmpLoopResultSlow, loopBodySlow, loopAfterSlow));
        loopHeaderSlow.addSuccessor(loopBodySlow);
        loopHeaderSlow.addSuccessor(loopAfterSlow);

        // loop_body: elem = arr[counter]; destroy(elem); counter++; store; br header
        Instruction loadElemSlow = new Instruction(Opcode.ALOAD);
        loadElemSlow.addOperand(arrParam);
        loadElemSlow.addOperand(counterSlow);
        Temporary elemValSlow = new Temporary(elementType);
        loadElemSlow.setResult(elemValSlow);
        elemValSlow.setDefiningInstruction(loadElemSlow);
        loopBodySlow.addInstruction(loadElemSlow);

        emitFieldDestruction(loopBodySlow, elemValSlow, elementType);

        Instruction incSlow = new Instruction(Opcode.ADD);
        incSlow.addOperand(counterSlow);
        incSlow.addOperand(new Constant(Type.INT, 1));
        Temporary newCounterSlow = new Temporary(Type.INT);
        incSlow.setResult(newCounterSlow);
        newCounterSlow.setDefiningInstruction(incSlow);
        loopBodySlow.addInstruction(incSlow);

        Instruction storeCounterSlow = new Instruction(Opcode.STORE);
        storeCounterSlow.addOperand(newCounterSlow);
        storeCounterSlow.setLocalIndex(COUNTER_SLOT);
        Temporary storedCounterSlow = new Temporary(Type.INT);
        storeCounterSlow.setResult(storedCounterSlow);
        storedCounterSlow.setDefiningInstruction(storeCounterSlow);
        loopBodySlow.addInstruction(storeCounterSlow);

        loopBodySlow.setTerminator(new BranchTerminator(loopHeaderSlow));
        loopBodySlow.addSuccessor(loopHeaderSlow);

        // loop_after: unclaim(arr); free(arr); ret
        emitUnclaim(loopAfterSlow, arrParam);
        Instruction freeArr = new Instruction(Opcode.FREE);
        freeArr.addOperand(arrParam);
        loopAfterSlow.addInstruction(freeArr);
        loopAfterSlow.setTerminator(new ReturnTerminator(null));

        return func;
    }

    private Function createTrivialArrayDestructor(Type arrayType) {
        String funcName = destructorName(arrayType);
        Function existing = module.getFunction(funcName);
        if (existing != null) return existing;

        Function func = new Function(funcName, Type.VOID);
        module.addFunction(func);

        Parameter arrParam = new Parameter(arrayType, 0);
        func.addParameter(arrParam);

        BasicBlock entry       = new BasicBlock(funcName + "_entry");
        BasicBlock returnBlock = new BasicBlock(funcName + "_return");
        BasicBlock bodyFast    = new BasicBlock(funcName + "_body_fast");
        BasicBlock bodySlow    = new BasicBlock(funcName + "_body_slow");
        func.addBlock(entry);
        func.addBlock(returnBlock);
        func.addBlock(bodyFast);
        func.addBlock(bodySlow);
        func.setEntryBlock(entry);
        returnBlock.setTerminator(new ReturnTerminator(null));

        emitDestructionGuard(entry, arrParam, returnBlock, bodyFast, bodySlow);

        // Fast path — no shutdown checks, no claim bookkeeping.
        Instruction freeFast = new Instruction(Opcode.STATIC_CALL);
        freeFast.addOperand(new Constant(
            Type.reference("__jnative_free_fast"), "__jnative_free_fast"));
        freeFast.addOperand(arrParam);
        bodyFast.addInstruction(freeFast);
        bodyFast.setTerminator(new ReturnTerminator(null));

        // Slow path — full semantics during shutdown.
        emitUnclaim(bodySlow, arrParam);
        Instruction freeInst = new Instruction(Opcode.FREE);
        freeInst.addOperand(arrParam);
        bodySlow.addInstruction(freeInst);
        bodySlow.setTerminator(new ReturnTerminator(null));

        return func;
    }

    // =====================================================================
    //  Call insertion
    // =====================================================================

    /**
     * Inserts the call to the CONCRETE destructor ({@code __destruct_<Type>})
     * of {@code site} after the last use of the object, and — for HEAP
     * sites — nulls out the field the object was stored in.
     *
     * <h2>Why HEAP sites whose containing field cannot be identified are
     * skipped</h2>
     *
     * <p>A HEAP allocation is by construction reachable from a field of
     * another object. Freeing it without nulling out that field leaves a
     * dangling pointer that the container's own destructor, or a later
     * read of the field through a LOAD, will observe. The concrete
     * failure that motivated this guard, taken from the compiled
     * {@code TestHeap$Payload} constructor:</p>
     *
     * <pre>
     *   this.padding = new byte[Math.max(0, size)];
     *   this.padding[0] = (byte) value;
     *   this.padding[this.padding.length - 1] = (byte) (value &gt;&gt; 8);
     * </pre>
     *
     * <p>{@code LifetimeAnalyzer.computeSite} produces a
     * {@link DestructionPoint} for every value that may point at the
     * site, via {@code collectAliases}. For the {@code padding} array
     * those are the {@code NEW_ARRAY} result <em>and</em> each
     * {@code GET_FIELD this.padding} result that later code uses. For
     * each LOAD-result root, the search below looks for a
     * {@code PUT_FIELD … root} that stored that <em>specific</em> value
     * into a field. That store does not exist: the field was written
     * with {@code %tmp_10572} (the {@code NEW_ARRAY} result), not with
     * any of the LOAD results. {@code lastStore} stays {@code null},
     * the null-out branch is skipped, and the destructor is inserted
     * anyway — so the array is freed inside its own allocator, and the
     * subsequent {@code padding[length-1] = byte} writes into freed
     * storage. The observed failure is the SIGSEGV at the second array
     * store, with the exception object still on the propagation stack.
     *
     * <p>When {@code lastStore == null} the analysis has no proof that
     * the field is about to be cleared, so the only sound action is to
     * not act. The object stays reachable from the field, and whatever
     * eventually clears that field — the container's destructor chain,
     * {@link #instrumentFieldOverwrites} on the next overwrite, or the
     * shutdown pass — will free the object through the normal path.
     * Leaking until then is strictly safer than corrupting the field.</p>
     *
     * <p>This mirrors the conservative direction already taken by
     * {@link #oldValueIsDead}, which explicitly refuses to emit a
     * field-overwrite release when liveness information is unavailable.
     * The invariant being protected is the same in both places: a
     * reference that could still be observed at run time must never be
     * released.</p>
     *
     * <p>Why the null-out itself: once the HEAP object has been freed
     * here, the field-overwrite instrumentation of the next loop
     * iteration would load the already-freed pointer and try to free it
     * again (double free). Nulling the field guarantees that the
     * overwrite instrumentation sees NULL and skips the release. It also
     * means that a destructor of the container — if it is ever called —
     * sees NULL instead of a dangling pointer and does not free the
     * storage twice.</p>
     */
    private void insertDestructorCall(AllocationSite site,
                                      DestructionPoint dp,
                                      Function dtor) {
        BasicBlock block = dp.getBlock();
        Instruction before = dp.getBeforeInstruction();
        Value objRef = dp.getObjectRef();

        if (!isLiveReference(objRef)) return;

        List<Instruction> insts = block.getInstructions();
        int insertIndex;
        if (before != null) {
            int index = insts.indexOf(before);
            insertIndex = (index >= 0) ? index : insts.size();
        } else {
            insertIndex = insts.size();
        }

        if (!noLaterUse(objRef, block, insertIndex)) return;

        // ------------------------------------------------------------------
        // Dominance check at the *insertion position*.
        //
        // objectRefAvailableAt consults the dominator tree of the block.
        // The tree itself is computed lazily and cached per function;
        // insertDestructorCall runs before any block split done by
        // emitPolymorphicRelease of the SAME function — but not before
        // splits done in *other* functions of the same module. Since the
        // dominator tree is stored per Function, a fresh computation is
        // required when insts have been reshuffled (the previous call may
        // have called emitPolymorphicRelease on a different Function).
        //
        // The domTrees field is populated from DominatorTree(func), which
        // is O(V+E) per function. To avoid recomputing it per insertion,
        // invalidate the cached tree whenever the block or its predecessors
        // change. Today emitPolymorphicRelease adds a new predecessor to
        // `tail` and to the handler; the safest thing to do is to drop the
        // cache entry for the function containing `block` right here if the
        // tree was created before the current call.
        //
        // The simplest correct fix: compute the tree freshly at this point
        // (objectRefAvailableAt does not cache), and let domTrees be
        // memoised only by callers that know the CFG has been rebuilt.
        // ------------------------------------------------------------------
        Function func = block.getFunction();
        if (func == null) return;
        DominatorTree dom = new DominatorTree(func);
        if (!valueAvailableAt(objRef, block, dom)) {
            return;
        }

        // ------------------------------------------------------------------
        // Find the last PUT_FIELD that stored objRef into a field.
        //
        // This is the field that has to be nulled out after the object is
        // freed: it is the only place the (now dangling) reference to the
        // object can still be observed. The scan is limited to the
        // instructions preceding the insertion point inside the same
        // block — the store that put the object into the field always
        // precedes its last use.
        // ------------------------------------------------------------------
        Instruction lastStore = null;
        for (int i = insertIndex - 1; i >= 0; i--) {
            Instruction candidate = insts.get(i);
            if (candidate.getOpcode() == Opcode.PUT_FIELD
                && candidate.getOperands().size() >= 3
                && candidate.getOperands().get(2) == objRef) {
                lastStore = candidate;
                break;
            }
        }

        boolean isHeap = escapeResult.getSiteStatus(site) == EscapeStatus.HEAP;

        // ------------------------------------------------------------------
        // HEAP sites must have their containing field nulled out before
        // the destructor runs.
        //
        // If no store of objRef into a field was located, the analysis has
        // no way to prove that the field will not observe the freed
        // pointer. Skip the insertion entirely; see the method javadoc
        // for the concrete TestHeap$Payload crash that this guard
        // prevents.
        // ------------------------------------------------------------------
        if (isHeap && lastStore == null) {
            return;
        }

        // ------------------------------------------------------------------
        // The owner of the field (the base) is checked across all of its
        // possible sites. If at least one of them is already
        // GLOBAL/RETURN/THREAD, the field belongs to a long-lived object,
        // which means the site stored in that field should have been
        // promoted to the same status and must not be released per-use.
        // If the escape analysis missed that, the object still cannot be
        // freed, and the field cannot be nulled out either — otherwise the
        // live owner would get null instead of its own field. Skip the
        // whole insertion.
        // ------------------------------------------------------------------
        boolean ownerIsLongLived = false;
        if (lastStore != null) {
            Value base = lastStore.getOperands().getFirst();
            for (AllocationSite baseSite : aliasResult.getPointsTo(base).getSites()) {
                EscapeStatus ownerStatus = escapeResult.getSiteStatus(baseSite);
                if (ownerStatus == EscapeStatus.GLOBAL
                    || ownerStatus == EscapeStatus.RETURN
                    || ownerStatus == EscapeStatus.THREAD) {
                    ownerIsLongLived = true;
                    break;
                }
            }
        }
        if (ownerIsLongLived) {
            return;
        }

        // The call is a direct STATIC_CALL to the concrete destructor of
        // the site's type — never a runtime dispatch. The site's type is
        // always a concrete class (an abstract class cannot have a NEW)
        // or an array type, so the call is well-typed.
        Instruction call = new Instruction(Opcode.STATIC_CALL);
        call.addOperand(new Constant(
            Type.reference(dtor.getName()), dtor.getName()));
        call.addOperand(objRef);
        call.setParent(block);
        insts.add(insertIndex, call);

        // For HEAP sites the field the object was stored in is nulled out,
        // so that neither the container's destructor nor the field-
        // overwrite instrumentation can observe (and free) the object a
        // second time. The guard above guarantees that lastStore != null
        // whenever isHeap is true at this point.
        if (isHeap) {
            Instruction nullOut = new Instruction(Opcode.PUT_FIELD);
            nullOut.addOperand(lastStore.getOperands().get(0));  // base
            nullOut.addOperand(lastStore.getOperands().get(1));  // field name
            nullOut.addOperand(new Constant(Type.NULL, null));
            nullOut.setParent(block);
            insts.add(insertIndex + 1, nullOut);
        }
    }

    private static boolean isLiveReference(Value v) {
        if (v == null) return false;
        if (v instanceof Constant c && c.getValue() == null) return false;
        Type t = v.getType();
        if (t == null) return false;
        return !t.isNull() && !t.isUnknown();
    }

    private boolean objectRefAvailableAt(Value objRef, BasicBlock block) {
        switch (objRef) {
            case null -> {
                return false;
            }
            case Constant ignored1 -> {
                return true;
            }
            case Parameter ignored -> {
                return true;
            }
            default -> {
            }
        }
        if (!(objRef instanceof Temporary t)) return false;
        Instruction def = t.getDefiningInstruction();
        if (def == null) return false;
        BasicBlock defBlock = def.getParent();
        if (defBlock == null) return false;
        if (defBlock == block) return true;
        Function func = block.getFunction();
        if (func == null) return false;
        DominatorTree dom = domTrees.computeIfAbsent(func, DominatorTree::new);
        return dom.dominates(defBlock, block);
    }

    /**
     * Checks whether a value is available at the given block using the
     * provided dominator tree. Used when the cached dominator tree may be
     * stale due to CFG modifications by earlier passes.
     */
    private boolean valueAvailableAt(Value objRef, BasicBlock block, DominatorTree dom) {
        switch (objRef) {
            case null -> {
                return false;
            }
            case Constant ignored1 -> {
                return true;
            }
            case Parameter ignored -> {
                return true;
            }
            default -> {
            }
        }
        if (!(objRef instanceof Temporary t)) return false;
        Instruction def = t.getDefiningInstruction();
        if (def == null) return false;
        BasicBlock defBlock = def.getParent();
        if (defBlock == null) return false;
        if (defBlock == block) return true;
        Function func = block.getFunction();
        if (func == null) return false;
        return dom.dominates(defBlock, block);
    }

    private static boolean noLaterUse(Value objRef, BasicBlock block, int fromIndex) {
        if (objRef == null) return true;
        List<Instruction> insts = block.getInstructions();
        for (int i = fromIndex; i < insts.size(); i++) {
            for (Value op : insts.get(i).getOperands()) {
                if (op == objRef) return false;
            }
        }
        Terminator term = block.getTerminator();
        if (term != null) {
            switch (term) {
                case CondBranchTerminator cbt -> { if (cbt.getCondition() == objRef) return false; }
                case ReturnTerminator rt -> { if (rt.getValue() == objRef) return false; }
                case ThrowTerminator tt -> { if (tt.getException() == objRef) return false; }
                case LookupSwitchTerminator lst -> { if (lst.getKey() == objRef) return false; }
                case TableSwitchTerminator tst -> { if (tst.getKey() == objRef) return false; }
                case IndirectBranchTerminator ibt -> { if (ibt.getTargetBlock() == objRef) return false; }
                default -> { }
            }
        }
        return true;
    }
}
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
     * <p>The closure is needed because {@code MULTI_NEW_ARRAY} allocates
     * the inner arrays at C level ({@code __jnative_new_multi_array}),
     * not through separate {@code NEW_ARRAY} instructions. The inner
     * array types therefore may not appear as independent allocation
     * sites, but they still need destructors because the outer
     * destructor will dispatch on each element.</p>
     *
     * <p>Reference types (non-arrays) are <b>not</b> added by closure:
     * an abstract superclass that has no {@code NEW} of its own must not
     * receive a destructor. The virtual destructor dispatch added by
     * this inserter is what allows fields of such types to be freed
     * correctly: the field is dispatched on at runtime, and the concrete
     * type's destructor is found through the vtable.</p>
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
     *   <li><b>Monomorphic</b> (one concrete destructor for all sites):
     *       inline {@code GET_FIELD / icmp / or / select / call} inserted
     *       directly before the PUT_FIELD. No CFG restructuring, no runtime
     *       dispatch, one direct call to {@code __destruct_<Type>}.</li>
     *
     *   <li><b>Polymorphic</b> (two or more distinct destructors): the
     *       block is split at the PUT_FIELD, and a static vtable-dispatch
     *       chain is emitted — one {@code icmp} per distinct vtable,
     *       branching to a block that calls the corresponding concrete
     *       destructor. All function pointers are resolved at compile
     *       time; the {@code dlsym}-based {@code __jnative_destruct_dispatch}
     *       helper is no longer used.</li>
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

            List<Type> dtorTypes = collectDestructorTypes(inst);

            if (dtorTypes.isEmpty()) {
                // No destructor exists for any possible site type.
                i++;
                continue;
            }

            if (dtorTypes.size() == 1) {
                // Monomorphic: inline select + direct call.
                List<Instruction> release =
                    emitMonomorphicRelease(inst, block, dtorTypes.getFirst());
                insts.addAll(i, release);
                i += release.size() + 1;  // release + PUT_FIELD
            } else {
                // Polymorphic: split the block, emit vtable-dispatch chain.
                return emitPolymorphicRelease(inst, block, i, dtorTypes);
            }
        }
        return null;
    }

    /**
     * Collects the set of distinct destructor target types for the sites
     * that may currently live in the field.
     *
     * <p>If two sites share the same destructor (either because they are
     * of the same type, or because {@code findDestructor} resolved them to
     * the same symbol), the result contains only one entry — this is the
     * monomorphic path.</p>
     */
    private List<Type> collectDestructorTypes(Instruction putField) {
        if (putField.getOperands().size() < 2) return List.of();

        Value base       = putField.getOperands().get(0);
        Value fieldConst = putField.getOperands().get(1);
        if (!(fieldConst instanceof Constant c) || !c.getType().isReference()) {
            return List.of();
        }
        String fieldKey = c.getValue().toString();

        PointsToSet baseSites  = aliasResult.getPointsTo(base);
        PointsToSet fieldSites = aliasResult.getFieldPointsToForSites(
                baseSites, fieldKey);

        // name → representative Type (deterministic insertion order).
        Map<String, Type> byDtorName = new LinkedHashMap<>();

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

            Function dtor = findDestructor(siteType);
            if (dtor == null) {
                continue;  // no destructor for this type — skip it
            }
            byDtorName.putIfAbsent(dtor.getName(), siteType);
        }

        return new ArrayList<>(byDtorName.values());
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
     *
     * <p>The descriptor is what names the array's class mirror, and it is what
     * the runtime uses to derive the array destructor's symbol.</p>
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
     * than one concrete destructor.
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
     * straight through to {@code tail}. This cannot happen given a sound
     * points-to analysis ({@code fieldSites} enumerates every site that can
     * be in the field, and every site carries one of the expected types);
     * the fall-through is defensive.</p>
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

        // ---- 4. Only the types whose destructor could actually be resolved
        //          take part in the dispatch chain. The chain must not end on
        //          a block without a terminator, so the "last" flag is
        //          computed over that filtered set.
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

    private boolean isManagedType(Type type) {
        if (type == null) return false;
        if (type.isUnknown() || type.isNull() || type.isBlock()) return false;
        if (type.isPrimitive()) return false;
        if (type.isArray()) return true;
        if (type.isReference()) {
            ClassNode cn = resolver.getClassNode(type.getClassName());
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

    private Temporary emitDestructionGuard(BasicBlock entry, Parameter thisParam) {
        Instruction enterCall = new Instruction(Opcode.STATIC_CALL);
        enterCall.addOperand(new Constant(
                Type.reference("__jnative_dtor_enter"), "__jnative_dtor_enter"));
        enterCall.addOperand(thisParam);
        Temporary enterResult = new Temporary(Type.INT);
        enterCall.setResult(enterResult);
        enterResult.setDefiningInstruction(enterCall);
        entry.addInstruction(enterCall);

        Instruction skip = new Instruction(Opcode.EQ);
        skip.addOperand(enterResult);
        skip.addOperand(new Constant(Type.INT, 0));
        Temporary skipResult = new Temporary(Type.BOOLEAN);
        skip.setResult(skipResult);
        skipResult.setDefiningInstruction(skip);
        entry.addInstruction(skip);
        return skipResult;
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
     * always correct.</p>
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

        // Polymorphic fields / abstract types: only the runtime dispatch
        // can pick the destructor of the actual runtime type.
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
     * <p>The destruction call is a direct call to the concrete destructor
     * of the field's static type whenever that type leaves no room for a
     * different runtime type (a final class, a class without known
     * subclasses, or an array type); every other field keeps the runtime
     * dispatch. See {@link #emitFieldDestruction}.</p>
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

        BasicBlock entry = new BasicBlock(funcName + "_entry");
        func.addBlock(entry);
        func.setEntryBlock(entry);

        BasicBlock returnBlock = new BasicBlock(funcName + "_return");
        func.addBlock(returnBlock);
        returnBlock.setTerminator(new ReturnTerminator(null));

        BasicBlock bodyBlock = new BasicBlock(funcName + "_body");
        func.addBlock(bodyBlock);

        Temporary skipResult = emitDestructionGuard(entry, thisParam);
        entry.setTerminator(new CondBranchTerminator(skipResult, returnBlock, bodyBlock));
        entry.addSuccessor(returnBlock);
        entry.addSuccessor(bodyBlock);

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
            bodyBlock.addInstruction(getField);

            emitFieldDestruction(bodyBlock, fieldValue, fieldType);
        }

        emitUnclaim(bodyBlock, thisParam);
        Instruction freeInst = new Instruction(Opcode.FREE);
        freeInst.addOperand(thisParam);
        bodyBlock.addInstruction(freeInst);
        bodyBlock.setTerminator(new ReturnTerminator(null));

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
     * <p>Reference or array element types: the destructor loops over the
     * array and destroys each element, then frees the array. No
     * per-element null check is needed: every destructor starts with a
     * guard that turns a {@code null} argument into an immediate return.
     * The element's destructor is called directly when the declared
     * element type leaves no room for a different runtime type (a final
     * class, a class without known subclasses, or an array type);
     * otherwise the runtime type of each element is resolved via the
     * element's vtable/mirror, which is what makes {@code Object[]},
     * {@code Number[]} and {@code Throwable[]} free their elements
     * correctly regardless of the declared element type. See
     * {@link #emitFieldDestruction}.</p>
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

        Function func = new Function(funcName, Type.VOID);
        module.addFunction(func);

        Parameter arrParam = new Parameter(arrayType, 0);
        func.addParameter(arrParam);

        // Slot 0 is owned by the array parameter (LlvmFunctionEmitter
        // materialises every parameter into %local_<index> in the entry
        // prologue). The loop counter lives in slot 1.
        final int COUNTER_SLOT = 1;

        BasicBlock entry = new BasicBlock(funcName + "_entry");
        func.addBlock(entry);
        func.setEntryBlock(entry);

        BasicBlock returnBlock = new BasicBlock(funcName + "_return");
        func.addBlock(returnBlock);
        returnBlock.setTerminator(new ReturnTerminator(null));

        BasicBlock bodyBlock = new BasicBlock(funcName + "_body");
        func.addBlock(bodyBlock);

        Temporary skipResult = emitDestructionGuard(entry, arrParam);
        entry.setTerminator(new CondBranchTerminator(skipResult, returnBlock, bodyBlock));
        entry.addSuccessor(returnBlock);
        entry.addSuccessor(bodyBlock);

        // body: len = arraylength(arr); store local[1] = 0; br loop_header
        Instruction lenInst = new Instruction(Opcode.ARRAYLENGTH);
        lenInst.addOperand(arrParam);
        Temporary lenVal = new Temporary(Type.INT);
        lenInst.setResult(lenVal);
        lenVal.setDefiningInstruction(lenInst);
        bodyBlock.addInstruction(lenInst);

        Instruction counterInit = new Instruction(Opcode.STORE);
        counterInit.addOperand(new Constant(Type.INT, 0));
        counterInit.setLocalIndex(COUNTER_SLOT);
        Temporary counterInitResult = new Temporary(Type.INT);
        counterInit.setResult(counterInitResult);
        counterInitResult.setDefiningInstruction(counterInit);
        bodyBlock.addInstruction(counterInit);

        BasicBlock loopHeader = new BasicBlock(funcName + "_loop_header");
        BasicBlock loopBody = new BasicBlock(funcName + "_loop_body");
        BasicBlock loopAfter = new BasicBlock(funcName + "_loop_after");
        func.addBlock(loopHeader);
        func.addBlock(loopBody);
        func.addBlock(loopAfter);

        bodyBlock.setTerminator(new BranchTerminator(loopHeader));
        bodyBlock.addSuccessor(loopHeader);

        // loop_header: counter = load local[1]; if counter < len → loop_body else loop_after
        Instruction counterLoad = new Instruction(Opcode.LOAD);
        counterLoad.setLocalIndex(COUNTER_SLOT);
        Temporary counter = new Temporary(Type.INT);
        counterLoad.setResult(counter);
        counter.setDefiningInstruction(counterLoad);
        loopHeader.addInstruction(counterLoad);

        Instruction cmpLoop = new Instruction(Opcode.LT);
        cmpLoop.addOperand(counter);
        cmpLoop.addOperand(lenVal);
        Temporary cmpLoopResult = new Temporary(Type.BOOLEAN);
        cmpLoop.setResult(cmpLoopResult);
        cmpLoopResult.setDefiningInstruction(cmpLoop);
        loopHeader.addInstruction(cmpLoop);
        loopHeader.setTerminator(new CondBranchTerminator(cmpLoopResult, loopBody, loopAfter));
        loopHeader.addSuccessor(loopBody);
        loopHeader.addSuccessor(loopAfter);

        // loop_body: elem = arr[counter]; destroy(elem); counter++; store; br header
        Instruction loadElem = new Instruction(Opcode.ALOAD);
        loadElem.addOperand(arrParam);
        loadElem.addOperand(counter);
        Temporary elemVal = new Temporary(elementType);
        loadElem.setResult(elemVal);
        elemVal.setDefiningInstruction(loadElem);
        loopBody.addInstruction(loadElem);

        emitFieldDestruction(loopBody, elemVal, elementType);

        Instruction inc = new Instruction(Opcode.ADD);
        inc.addOperand(counter);
        inc.addOperand(new Constant(Type.INT, 1));
        Temporary newCounter = new Temporary(Type.INT);
        inc.setResult(newCounter);
        newCounter.setDefiningInstruction(inc);
        loopBody.addInstruction(inc);

        Instruction storeCounter = new Instruction(Opcode.STORE);
        storeCounter.addOperand(newCounter);
        storeCounter.setLocalIndex(COUNTER_SLOT);
        Temporary storedCounter = new Temporary(Type.INT);
        storeCounter.setResult(storedCounter);
        storedCounter.setDefiningInstruction(storeCounter);
        loopBody.addInstruction(storeCounter);

        loopBody.setTerminator(new BranchTerminator(loopHeader));
        loopBody.addSuccessor(loopHeader);

        // loop_after: unclaim(arr); free(arr); ret
        emitUnclaim(loopAfter, arrParam);
        Instruction freeArr = new Instruction(Opcode.FREE);
        freeArr.addOperand(arrParam);
        loopAfter.addInstruction(freeArr);
        loopAfter.setTerminator(new ReturnTerminator(null));

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

        BasicBlock entry = new BasicBlock(funcName + "_entry");
        func.addBlock(entry);
        func.setEntryBlock(entry);

        BasicBlock returnBlock = new BasicBlock(funcName + "_return");
        func.addBlock(returnBlock);
        returnBlock.setTerminator(new ReturnTerminator(null));

        BasicBlock bodyBlock = new BasicBlock(funcName + "_body");
        func.addBlock(bodyBlock);

        Temporary skipResult = emitDestructionGuard(entry, arrParam);
        entry.setTerminator(new CondBranchTerminator(skipResult, returnBlock, bodyBlock));
        entry.addSuccessor(returnBlock);
        entry.addSuccessor(bodyBlock);

        emitUnclaim(bodyBlock, arrParam);
        Instruction freeInst = new Instruction(Opcode.FREE);
        freeInst.addOperand(arrParam);
        bodyBlock.addInstruction(freeInst);
        bodyBlock.setTerminator(new ReturnTerminator(null));

        return func;
    }

    // =====================================================================
    //  Call insertion (unchanged)
    // =====================================================================

    /**
     * Inserts the call to the CONCRETE destructor ({@code __destruct_<Type>})
     * of {@code site} after the last use of the object, and — for HEAP
     * sites — nulls out the field the object was stored in.
     *
     * <p>Why the null-out: once the HEAP object has been freed here, the
     * field-overwrite instrumentation of the next loop iteration would
     * load the already freed pointer and try to free it again (double
     * free). Nulling the field guarantees that the overwrite
     * instrumentation sees NULL and skips the release. It also means that
     * a destructor of the container — if it is ever called — sees NULL
     * instead of a dangling pointer and does not free the storage twice.
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
        // second time.
        boolean isHeap = escapeResult.getSiteStatus(site) == EscapeStatus.HEAP;
        if (isHeap && lastStore != null) {
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
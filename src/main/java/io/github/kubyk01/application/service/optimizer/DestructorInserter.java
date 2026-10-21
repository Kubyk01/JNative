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
        // type have been emitted, so the runtime dispatch inside
        // emitFieldOverwriteRelease is guaranteed to find a matching
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
        int inserted = 0;
        for (Map.Entry<AllocationSite, Set<DestructionPoint>> entry
                : lifetimeResult.getDestructionPoints().entrySet()) {

            AllocationSite site = entry.getKey();
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
                insertDestructorCall(dp, dtor);
                inserted++;
            }
        }
        log.info("Destructor insertion: {} destructor(s) generated, {} call(s) inserted",
                generated, inserted);
    }

    // =====================================================================
    //  Field-overwrite instrumentation
    // =====================================================================

    /**
     * Walks every function of the module and instruments each PUT_FIELD
     * that stores a reference-typed value into a reference-typed field
     * with a pre-store release of the field's previous value, subject to
     * the provable-death gate in {@link #oldValueIsDead}.
     *
     * <p>Returns the number of blocks that received at least one new
     * instruction pair. The caller logs the count; the exact number of
     * emitted GET_FIELD/STATIC_CALL pairs is typically 1–3 times this
     * value because a block can contain more than one PUT_FIELD.</p>
     */
    private int instrumentFieldOverwrites(
            Map<BasicBlock, Set<AllocationSite>> liveOut) {
        if (liveOut == null || liveOut.isEmpty()) {
            // No liveness information was published. Emitting a release
            // without it is exactly the HashMap.resize use-after-free
            // this pass exists to prevent; bail out of the whole pass
            // rather than producing unsound IR.
            log.warn("DestructorInserter: no live-out map was published by "
                    + "the lifetime analyzer; field-overwrite releases will "
                    + "not be emitted. HEAP-escape allocations will leak "
                    + "until their container's destructor runs.");
            return 0;
        }

        int touchedBlocks = 0;
        for (Function func : new ArrayList<>(module.getFunctions())) {
            if (func.getEntryBlock() == null) continue;
            for (BasicBlock block : func.getBlocks()) {
                if (instrumentBlockFieldOverwrites(block, liveOut)) {
                    touchedBlocks++;
                }
            }
        }
        return touchedBlocks;
    }

    private boolean instrumentBlockFieldOverwrites(
            BasicBlock block,
            Map<BasicBlock, Set<AllocationSite>> liveOut) {
        List<Instruction> insts = block.getInstructions();
        if (insts.isEmpty()) return false;

        List<Instruction> rewritten = new ArrayList<>(insts.size() + 8);
        boolean changed = false;

        for (Instruction inst : insts) {
            if (inst.getOpcode() == Opcode.PUT_FIELD
                    && shouldInstrumentPutField(inst)
                    && oldValueIsDead(inst, block, liveOut)) {
                emitFieldOverwriteRelease(inst, block, rewritten);
                changed = true;
            }
            rewritten.add(inst);
        }

        if (!changed) return false;

        insts.clear();
        insts.addAll(rewritten);
        return true;
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
        return !type.isPrimitive() && !type.isVoid();
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
     * Emits the release sequence:
     *
     * <pre>
     *     GET_FIELD base.field     -> oldValue
     *     STATIC_CALL __jnative_destruct_dispatch_if_not_same(oldValue, rhs)
     * </pre>
     *
     * The original PUT_FIELD is emitted by the caller after these two
     * instructions, in the same list.
     *
     * <p>Both GET_FIELD and PUT_FIELD carry the same canonical
     * `owner.Class.name` key (or the same integer offset), so the load
     * reads exactly the bytes the store is about to overwrite. The
     * declared type of the freshly-created temporary is the field's
     * declared type, resolved from the resolver's FieldNode; when the
     * field cannot be resolved (a synthetic field the resolver does not
     * know about), the rhs's own IR type is used, which keeps the
     * emitted instruction structurally valid.</p>
     *
     * <p>The runtime helper {@code __jnative_destruct_dispatch_if_not_same}
     * performs the same two guards that the analysis-side pass cannot
     * perform exactly:</p>
     * <ul>
     *   <li>{@code old == NULL} — the field's initial (zero) value, or
     *       a value already released by an earlier overwrite on a
     *       different path. No-op.</li>
     *   <li>{@code old == rhs} — re-storing the same reference. Freeing
     *       it here would leave the field dangling. No-op.</li>
     * </ul>
     *
     * <p>Any other {@code old} is dispatched through the runtime-type
     * resolution used by every other reference release: the receiver's
     * vtable (or class mirror, for arrays) is looked up in the cache, the
     * matching {@code __destruct_*} symbol is found, and it is invoked.
     * Values whose runtime type has no emitted destructor — String
     * literals, boxed {@code Integer} cache entries, objects that only
     * a native helper ever creates — silently fall through, which is
     * the correct behaviour because they are not managed by this
     * runtime's allocator.</p>
     */
    private void emitFieldOverwriteRelease(
            Instruction putField,
            BasicBlock block,
            List<Instruction> out) {

        Value base       = putField.getOperands().get(0);
        Value fieldConst = putField.getOperands().get(1);
        Value rhs        = putField.getOperands().get(2);

        Type fieldType = resolveFieldType(putField, rhs);

        Instruction getField = new Instruction(Opcode.GET_FIELD);
        getField.addOperand(base);
        getField.addOperand(fieldConst);
        Temporary oldValue = new Temporary(fieldType);
        getField.setResult(oldValue);
        oldValue.setDefiningInstruction(getField);
        getField.setParent(block);

        Instruction release = new Instruction(Opcode.STATIC_CALL);
        release.addOperand(new Constant(
                Type.reference("__jnative_destruct_dispatch_if_not_same"),
                "__jnative_destruct_dispatch_if_not_same"));
        release.addOperand(oldValue);
        release.addOperand(rhs);
        release.setParent(block);

        out.add(getField);
        out.add(release);
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
     * Emits the destructor body for a reference class.
     *
     * <p>For each reference-typed instance field — from this class AND
     * from every ancestor, flattened superclass-first — one
     * {@code __jnative_destruct_dispatch} call is emitted. Then the
     * cycle-guard is released and the receiver is freed. There is no
     * superclass delegation: every ancestor's fields are destroyed here,
     * which means an abstract superclass needs no destructor of its own
     * (and, by {@link #findDestructor(Type)}, does not get one).</p>
     *
     * <p>The body is a <b>single</b> BasicBlock from the IR builder's
     * point of view. The {@code GET_FIELD} instruction still expands into
     * several LLVM blocks internally (its null check), but no
     * additional BasicBlock is created for the field's null check or
     * call: {@code __jnative_destruct_dispatch(null)} is a no-op, so no
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

            emitDispatchCall(bodyBlock, fieldValue);
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
     * array and emits {@code __jnative_destruct_dispatch(elem)} for each
     * element, then frees the array. No per-element null check is
     * needed: {@code __jnative_destruct_dispatch(null)} is a no-op. No
     * static dispatch on the element type is done either — the runtime
     * type of each element is resolved via the element's vtable/mirror,
     * which is what makes {@code Object[]}, {@code Number[]} and
     * {@code Throwable[]} free their elements correctly regardless of
     * the declared element type.</p>
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

        // loop_body: elem = arr[counter]; dispatch(elem); counter++; store; br header
        Instruction loadElem = new Instruction(Opcode.ALOAD);
        loadElem.addOperand(arrParam);
        loadElem.addOperand(counter);
        Temporary elemVal = new Temporary(elementType);
        loadElem.setResult(elemVal);
        elemVal.setDefiningInstruction(loadElem);
        loopBody.addInstruction(loadElem);

        emitDispatchCall(loopBody, elemVal);

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

    private void insertDestructorCall(DestructionPoint dp, Function dtor) {
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

        Instruction call = new Instruction(Opcode.STATIC_CALL);
        call.addOperand(new Constant(Type.reference(dtor.getName()), dtor.getName()));
        call.addOperand(objRef);
        insts.add(insertIndex, call);
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
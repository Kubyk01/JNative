package io.github.kubyk01.application.service.optimizer;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.ssa.DominatorTree;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.BranchTerminator;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.IndirectBranchTerminator;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.LookupSwitchTerminator;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.TableSwitchTerminator;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.Opcodes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

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

    public void insert() {
        // 1. Collect classes and array types encountered at destruction points
        Set<Type> classTypes = new HashSet<>();
        for (AllocationSite site : lifetimeResult.getDestructionPoints().keySet()) {
            Type type = site.getType();
            if (isManagedType(type)) {
                classTypes.add(type);
            }
        }

        // 2. Generate destructors, including reference field types and array types
        Map<String, Function> destructorMap = new HashMap<>();
        Queue<Type> worklist = new ArrayDeque<>(classTypes);
        Set<Type> processed = new HashSet<>();
        while (!worklist.isEmpty()) {
            Type type = worklist.poll();
            if (!processed.add(type)) continue;
            Function dtor = createDestructor(type);
            if (dtor == null) continue;
            destructorMap.put(type.toString(), dtor);

            if (!isArrayType(type)) {
                String className = type.getClassName();
                ClassNode classNode = resolver.getClassNode(className);
                for (FieldNode field : classNode.getFields()) {
                    Type fieldType = field.getType();
                    if (fieldType.isReference() || fieldType.isArray()) {
                        if (isManagedType(fieldType)) {
                            worklist.add(fieldType);
                        }
                    }
                }
                String superName = classNode.getSuperName();
                if (superName != null && !superName.equals("java/lang/Object")) {
                    Type superType = Type.reference(superName);
                    if (isManagedType(superType)) {
                        worklist.add(superType);
                    }
                }
            }
        }

        // 3. Insert destructor calls at destruction points – only for managed types
        int inserted = 0;
        for (Map.Entry<AllocationSite, Set<DestructionPoint>> entry
            : lifetimeResult.getDestructionPoints().entrySet()) {
            AllocationSite site = entry.getKey();

            // ------------------------------------------------------------------
            // Stack-allocated object.
            //
            // The object's storage lives in its function's stack frame and is
            // released automatically when the function returns. Emitting a
            // destructor call — which ends in `free(obj)` — would call free()
            // on a stack address and corrupt the heap. The same applies to a
            // scalar-replaced object: the destructor argument would be a
            // dangling reference. One check handles both cases.
            // ------------------------------------------------------------------
            if (escapeResult.getSiteStatus(site) == EscapeStatus.STACK) {
                continue;
            }

            Type type = site.getType();
            if (type.isUnknown() || type.isNull()) continue;
            if (!isManagedType(type)) continue;
            Function dtor = findDestructor(type);
            if (dtor == null) continue;
            for (DestructionPoint dp : entry.getValue()) {
                if (!isLiveReference(dp.getObjectRef())) {
                    // Destruction point without a live reference: skip it
                    // entirely so the inserted counter is not inflated and no
                    // fictitious insertion is logged.
                    continue;
                }

                // ------------------------------------------------------------------
                // Second line of defence against the LLVM verifier's dominance
                // rule. LifetimeAnalyzer already filters destruction points whose
                // object reference is not available at the insertion block (see
                // its valueAvailableAt helper); this check re-runs the same test
                // using the information the DestructorInserter has, so a
                // destruction point added by any other route — a future pass, a
                // different analyser, a manual construction from a unit test — is
                // still rejected before it can become a call to a value that does
                // not dominate its own argument.
                //
                // The check is cheap: it walks the CFG predecessors from the
                // insertion block towards the entry block, looking for the
                // defining block of the object reference. It stops at the first
                // block visited twice, at the entry block, or when the search
                // depth exceeds a small bound, whichever comes first; anything
                // not proven reachable is treated as unreachable, which is the
                // conservative direction.
                // ------------------------------------------------------------------
                if (!objectRefAvailableAt(dp.getObjectRef(), dp.getBlock())) {
                    continue;
                }

                insertDestructorCall(dp, dtor);
                inserted++;
            }
        }
        log.info("Destructor insertion: {} destructors generated, {} calls inserted",
            destructorMap.size(), inserted);

    }

    /**
     * A type is "managed" if the destructor pass may generate a destructor for
     * it: it is not unknown / null, it is not a JDK class, and it is not
     * {@code java.lang.Object}. Arrays are managed if their base element type
     * is itself managed.
     */
    private boolean isManagedType(Type type) {
        if (type == null) return false;
        if (type.isUnknown() || type.isNull()) return false;
        if (type.isArray()) {
            Type base = getBaseElementType(type);
            if (base.isPrimitive()) return false;
            if (base.isUnknown() || base.isNull()) return false;
            return isManagedType(base);
        }
        if (type.isReference()) {
            String className = type.getClassName();
            ClassNode cn = resolver.getClassNode(className);
            if (cn != null && cn.isExternal()) return false;
            return !className.equals("java/lang/Object");
        }
        return false;
    }

    private Type getBaseElementType(Type type) {
        if (!type.isArray()) return type;
        Type inner = type.getElementType();
        while (inner.isArray()) {
            inner = inner.getElementType();
        }
        return inner;
    }

    private boolean isArrayType(Type type) {
        return type != null && type.isArray();
    }

    private String destructorName(Type type) {
        if (type == null) return "__destruct_unknown";
        if (type.isArray()) {
            String base = type.toString();
            base = base.replaceAll("[^a-zA-Z0-9_.]", "_");
            return "__destruct_array_" + base;
        } else if (type.isReference()) {
            return "__destruct_" + type.getClassName().replace('/', '_').replace('.', '_');
        }
        return "__destruct_unknown";
    }

    private Function createDestructor(Type type) {
        if (type == null) return null;
        if (!isManagedType(type)) return null;
        if (type.isArray()) {
            return createArrayDestructor(type);
        } else if (type.isReference()) {
            return createClassDestructor(type.getClassName());
        }
        return null;
    }

    /**
     * Emits the entry guard for a generated destructor.
     *
     * <p>Calls {@code __jnative_dtor_enter(this)} and returns a boolean that
     * is true exactly when the destructor must skip its body and return
     * immediately. {@code __jnative_dtor_enter} rejects:
     *
     * <ul>
     *   <li>a null receiver;</li>
     *   <li>an object that has already been freed earlier in the same
     *       shutdown pass (the DAG case — a shared singleton reachable from
     *       two static fields or from two field paths);</li>
     *   <li>an object that is currently on the destruction call chain (the
     *       cycle case — e.g. {@code UCharacterProperty.intProp} pointing at
     *       an {@code IntProperty} whose {@code this$0} points back).</li>
     * </ul>
     *
     * <p>The guard is what breaks infinite mutual recursion between two
     * destructors on a cyclic graph. It is installed at the entry of every
     * generated destructor, so an object can never be entered twice on the
     * same destruction chain, whether the re-entry comes from a diamond or
     * from a cycle.</p>
     *
     * <p>Outside the shutdown phase {@code __jnative_dtor_enter} returns 1
     * immediately, so this guard costs a single predictable branch in the
     * normal-execution destruction path.</p>
     */
    private Temporary emitDestructionGuard(BasicBlock entry, Parameter thisParam) {
        Instruction enterCall = new Instruction(Opcode.STATIC_CALL);
        enterCall.addOperand(new Constant(
            Type.reference("__jnative_dtor_enter"),
            "__jnative_dtor_enter"));
        enterCall.addOperand(thisParam);
        Temporary enterResult = new Temporary(Type.INT);
        enterCall.setResult(enterResult);
        enterResult.setDefiningInstruction(enterCall);
        entry.addInstruction(enterCall);

        // Skip the destructor body when the entry check rejected us
        // (__jnative_dtor_enter returned 0).
        Instruction skip = new Instruction(Opcode.EQ);
        skip.addOperand(enterResult);
        skip.addOperand(new Constant(Type.INT, 0));
        Temporary skipResult = new Temporary(Type.BOOLEAN);
        skip.setResult(skipResult);
        skipResult.setDefiningInstruction(skip);
        entry.addInstruction(skip);

        return skipResult;
    }

    /**
     * Emits a call to {@code __jnative_dtor_unclaim(thisParam)} into
     * {@code block}.
     *
     * <p>Removes {@code thisParam} from the thread-local claim stack that
     * {@link #emitDestructionGuard} populated on entry, so that a
     * superclass destructor (which receives the same receiver during the
     * delegation chain) can claim it in turn. Without this release the
     * delegation chain would stop at the first level, since the superclass
     * destructor would see the receiver already claimed and return without
     * touching its own fields.</p>
     *
     * <p>Called immediately before the destructor delegates to its
     * superclass destructor and before it frees the object. Outside the
     * shutdown phase the call is a no-op.</p>
     */
    private void emitUnclaim(BasicBlock block, Parameter thisParam) {
        Instruction unclaim = new Instruction(Opcode.STATIC_CALL);
        unclaim.addOperand(new Constant(
            Type.reference("__jnative_dtor_unclaim"),
            "__jnative_dtor_unclaim"));
        unclaim.addOperand(thisParam);
        unclaim.setParent(block);
        block.addInstruction(unclaim);
    }

    private Function createClassDestructor(String className) {
        ClassNode classNode = resolver.getClassNode(className);
        if (classNode.isExternal()) {
            log.debug("Skipping destructor for external class: {}", className);
            return null;
        }

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

        Constant nullConst = new Constant(Type.NULL, null);

        BasicBlock returnBlock = new BasicBlock(funcName + "_return");
        func.addBlock(returnBlock);
        returnBlock.setTerminator(new ReturnTerminator(null));

        BasicBlock bodyBlock = new BasicBlock(funcName + "_body");
        func.addBlock(bodyBlock);

        Temporary skipResult = emitDestructionGuard(entry, thisParam);
        entry.setTerminator(new CondBranchTerminator(skipResult, returnBlock, bodyBlock));
        entry.addSuccessor(returnBlock);
        entry.addSuccessor(bodyBlock);

        BasicBlock current = bodyBlock;

        for (FieldNode field : collectReferenceFields(classNode)) {
            if ((field.getAccess() & Opcodes.ACC_STATIC) != 0) continue;
            Type fieldType = field.getType();
            if (!isManagedType(fieldType)) continue;

            Instruction getField = new Instruction(Opcode.GET_FIELD);
            getField.addOperand(thisParam);
            getField.addOperand(new Constant(
                Type.reference(className + "." + field.getName()),
                className + "." + field.getName()));
            Temporary fieldValue = new Temporary(fieldType);
            getField.setResult(fieldValue);
            fieldValue.setDefiningInstruction(getField);
            current.addInstruction(getField);

            Instruction isNull = new Instruction(Opcode.EQ);
            isNull.addOperand(fieldValue);
            isNull.addOperand(nullConst);
            Temporary isNullTmp = new Temporary(Type.BOOLEAN);
            isNull.setResult(isNullTmp);
            isNullTmp.setDefiningInstruction(isNull);
            current.addInstruction(isNull);

            BasicBlock callBlock = new BasicBlock(funcName + "_call_" + field.getName());
            BasicBlock skipBlock = new BasicBlock(funcName + "_skip_" + field.getName());
            func.addBlock(callBlock);
            func.addBlock(skipBlock);

            current.setTerminator(new CondBranchTerminator(isNullTmp, skipBlock, callBlock));
            current.addSuccessor(skipBlock);
            current.addSuccessor(callBlock);

            Function fieldDtor = findDestructor(fieldType);
            if (fieldDtor != null) {
                Instruction callDtor = new Instruction(Opcode.STATIC_CALL);
                callDtor.addOperand(new Constant(
                    Type.reference(fieldDtor.getName()), fieldDtor.getName()));
                callDtor.addOperand(fieldValue);
                callBlock.addInstruction(callDtor);
            }
            callBlock.setTerminator(new BranchTerminator(skipBlock));
            callBlock.addSuccessor(skipBlock);

            current = skipBlock;
        }

        // ------------------------------------------------------------------
        // Delegate field destruction to the superclass destructor when one
        // exists; the topmost managed class in the chain emits the single
        // free() for the whole hierarchy.
        //
        // The destructor chain must free the object exactly once. A class
        // whose superclass is also managed delegates to the superclass
        // destructor, which itself recurses upward; only the class at the
        // top of the managed hierarchy (whose super is Object or is not
        // managed) actually calls free(). If every level of the hierarchy
        // both delegated to its superclass AND freed the same pointer, the
        // object would be freed once per level:
        //
        //   __destruct_ConcurrentHashMap calls
        //       __destruct_AbstractMap(this)   -- frees this
        //       free(this)                     -- frees this again
        //
        // The second free is what glibc's tcache detects and aborts on. The
        // defect is systematic: every class whose superclass is also
        // managed produced a destructor of this shape (846 of them in the
        // generated IR), and the first one reached at run time -- on
        // ConcurrentHashMap inside NativeLibraries.<clinit> -- aborted the
        // process before user code could run.
        // ------------------------------------------------------------------
        // Release the claim before delegating to the superclass destructor or
        // freeing the object. The superclass destructor receives the same
        // receiver (thisParam) and must be able to claim it in turn; without
        // this release the whole delegation chain would stop at the first
        // level, and the fields declared by every ancestor would leak.
        emitUnclaim(current, thisParam);

        boolean delegatedToSuper = false;
        String superName = classNode.getSuperName();
        if (superName != null && !superName.equals("java/lang/Object")) {
            Type superType = Type.reference(superName);
            if (isManagedType(superType)) {
                Function superDtor = findDestructor(superType);
                if (superDtor != null) {
                    Instruction callSuper = new Instruction(Opcode.STATIC_CALL);
                    callSuper.addOperand(new Constant(
                        Type.reference(superDtor.getName()), superDtor.getName()));
                    callSuper.addOperand(thisParam);
                    current.addInstruction(callSuper);
                    delegatedToSuper = true;
                }
            }
        }

        if (!delegatedToSuper) {
            Instruction freeInst = new Instruction(Opcode.FREE);
            freeInst.addOperand(thisParam);
            current.addInstruction(freeInst);
        }

        current.setTerminator(new ReturnTerminator(null));

        return func;
    }

    private Function createArrayDestructor(Type arrayType) {
        Type elementType = arrayType.getElementType();
        Type baseType = getBaseElementType(arrayType);

        if (baseType.isPrimitive()) {
            return createTrivialArrayDestructor(arrayType);
        }

        if (!isManagedType(elementType)) {
            return createTrivialArrayDestructor(arrayType);
        }

        String funcName = destructorName(arrayType);
        Function existing = module.getFunction(funcName);
        if (existing != null) return existing;

        Function func = new Function(funcName, Type.VOID);
        module.addFunction(func);
        Parameter arrParam = new Parameter(arrayType, 0);
        func.addParameter(arrParam);

        // ------------------------------------------------------------------
        // The loop counter lives in its own JVM local slot.
        //
        // Slot 0 is owned by the array parameter: LlvmFunctionEmitter
        // materialises every parameter into %local_<index> in the entry
        // prologue, and the array parameter's index is 0. Reusing slot 0
        // for the counter let the prologue overwrite the counter's
        // initial zero with the array's pointer, so the loop test read the
        // low 32 bits of a heap address as its starting index. On an ASLR
        // layout where that low half has bit 31 set, the counter is a large
        // negative number, the loop body executes with a negative index,
        // and the bounds check raises ArrayIndexOutOfBoundsException from
        // inside __jnative_shutdown. That was the alternating failure (A):
        // roughly half of the runs of the same binary aborted with
        //
        //   Exception in thread "main"
        //   java.lang.ArrayIndexOutOfBoundsException:
        //       Array index out of bounds in
        //       destruct_array_array_ref_java_lang_String__
        //       at destruct_array_array_ref_java_lang_String__
        //       at destruct_sun_security_util_KnownOIDs
        //       at (jnative_shutdown)
        //
        // Slot 1 belongs to nothing else: the function has exactly one
        // parameter, and no other slot is referenced by any instruction
        // this method emits.
        // ------------------------------------------------------------------
        final int COUNTER_SLOT = 1;

        BasicBlock entry = new BasicBlock(funcName + "_entry");
        func.addBlock(entry);
        func.setEntryBlock(entry);

        Constant nullConst = new Constant(Type.NULL, null);

        BasicBlock returnBlock = new BasicBlock(funcName + "_return");
        func.addBlock(returnBlock);
        returnBlock.setTerminator(new ReturnTerminator(null));

        BasicBlock bodyBlock = new BasicBlock(funcName + "_body");
        func.addBlock(bodyBlock);

        Temporary skipResult = emitDestructionGuard(entry, arrParam);
        entry.setTerminator(new CondBranchTerminator(skipResult, returnBlock, bodyBlock));
        entry.addSuccessor(returnBlock);
        entry.addSuccessor(bodyBlock);

        Function elementDtor = findDestructor(elementType);
        if (elementDtor == null) {
            Instruction freeInst = new Instruction(Opcode.FREE);
            freeInst.addOperand(arrParam);
            bodyBlock.addInstruction(freeInst);
            bodyBlock.setTerminator(new ReturnTerminator(null));
            return func;
        }

        // body:
        //   len = arraylength(arr)
        //   store i32 0 into %local_<COUNTER_SLOT>
        //   br loop_header
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

        // ------------------------------------------------------------------
        // loop_header:
        //   counter = load %local_<COUNTER_SLOT>
        //   if (counter < len) -> loop_body else -> loop_after
        //
        // The counter must be reloaded at the head of every iteration.
        // The previous revision issued the LOAD once in the pre-header and
        // reused its SSA result in the comparison, so the loop condition
        // never observed the increment stored in skip_elem -- the loop
        // could not terminate. After the SSA pass this pattern lowers to
        // a standard PHI at the loop header, with one incoming value per
        // back-edge and per pre-header, exactly as the JVM's own loops do.
        // ------------------------------------------------------------------
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

        Instruction loadElem = new Instruction(Opcode.ALOAD);
        loadElem.addOperand(arrParam);
        loadElem.addOperand(counter);
        Temporary elemVal = new Temporary(elementType);
        loadElem.setResult(elemVal);
        elemVal.setDefiningInstruction(loadElem);
        loopBody.addInstruction(loadElem);

        Instruction isNullElem = new Instruction(Opcode.EQ);
        isNullElem.addOperand(elemVal);
        isNullElem.addOperand(nullConst);
        Temporary isNullElemResult = new Temporary(Type.BOOLEAN);
        isNullElem.setResult(isNullElemResult);
        isNullElemResult.setDefiningInstruction(isNullElem);
        loopBody.addInstruction(isNullElem);

        BasicBlock skipElem = new BasicBlock(funcName + "_skip_elem");
        BasicBlock callElemDtor = new BasicBlock(funcName + "_call_elem_dtor");
        func.addBlock(skipElem);
        func.addBlock(callElemDtor);

        loopBody.setTerminator(new CondBranchTerminator(isNullElemResult, skipElem, callElemDtor));
        loopBody.addSuccessor(skipElem);
        loopBody.addSuccessor(callElemDtor);

        Instruction callDtor = new Instruction(Opcode.STATIC_CALL);
        callDtor.addOperand(new Constant(
            Type.reference(elementDtor.getName()), elementDtor.getName()));
        callDtor.addOperand(elemVal);
        callElemDtor.addInstruction(callDtor);
        callElemDtor.setTerminator(new BranchTerminator(skipElem));
        callElemDtor.addSuccessor(skipElem);

        // skip_elem:
        //   store %local_<COUNTER_SLOT> = counter + 1
        //   br loop_header
        Instruction inc = new Instruction(Opcode.ADD);
        inc.addOperand(counter);
        inc.addOperand(new Constant(Type.INT, 1));
        Temporary newCounter = new Temporary(Type.INT);
        inc.setResult(newCounter);
        newCounter.setDefiningInstruction(inc);
        skipElem.addInstruction(inc);

        Instruction storeCounter = new Instruction(Opcode.STORE);
        storeCounter.addOperand(newCounter);
        storeCounter.setLocalIndex(COUNTER_SLOT);
        Temporary storedCounter = new Temporary(Type.INT);
        storeCounter.setResult(storedCounter);
        storedCounter.setDefiningInstruction(storeCounter);
        skipElem.addInstruction(storeCounter);

        skipElem.setTerminator(new BranchTerminator(loopHeader));
        skipElem.addSuccessor(loopHeader);

        // loop_after:
        //   free(arr)
        //   ret
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

        Constant nullConst = new Constant(Type.NULL, null);

        BasicBlock returnBlock = new BasicBlock(funcName + "_return");
        func.addBlock(returnBlock);
        returnBlock.setTerminator(new ReturnTerminator(null));

        BasicBlock bodyBlock = new BasicBlock(funcName + "_body");
        func.addBlock(bodyBlock);
        Instruction freeInst = new Instruction(Opcode.FREE);
        freeInst.addOperand(arrParam);
        bodyBlock.addInstruction(freeInst);
        bodyBlock.setTerminator(new ReturnTerminator(null));

        Temporary skipResult = emitDestructionGuard(entry, arrParam);
        entry.setTerminator(new CondBranchTerminator(skipResult, returnBlock, bodyBlock));
        entry.addSuccessor(returnBlock);
        entry.addSuccessor(bodyBlock);

        return func;
    }

    private Function findDestructor(Type type) {
        if (!isManagedType(type)) return null;
        String name = destructorName(type);
        if (name == null) return null;
        Function existing = module.getFunction(name);
        if (existing != null) return existing;
        return createDestructor(type);
    }

    private List<FieldNode> collectReferenceFields(ClassNode classNode) {
        List<FieldNode> refFields = new ArrayList<>();
        for (FieldNode field : classNode.getFields()) {
            Type ft = field.getType();
            if (ft.isReference() || ft.isArray()) {
                refFields.add(field);
            }
        }
        return refFields;
    }

    /**
     * Inserts a destructor call at the given destruction point.
     *
     * <p>The call is not inserted when {@code objectRef} is not a real
     * reference to an allocated object:</p>
     *
     * <ul>
     *   <li>Java {@code null} — the destruction point was created without an
     *       object reference (for example, for an alias of a variable that
     *       already owns the storage);</li>
     *   <li>a {@code Constant} whose value is {@code null} — the same thing,
     *       but represented as a null constant;</li>
     *   <li>a value with type {@code NULL} or {@code UNKNOWN} — the type shows
     *       it cannot be the result of {@code NEW}.</li>
     * </ul>
     *
     * <p>In all of these cases the destructor call would degenerate into
     * {@code free(i8* null)} at the LLVM level — a meaningless no-op that
     * pollutes the output. There is nothing to free, so the call is simply not
     * created.</p>
     */
    private void insertDestructorCall(DestructionPoint dp, Function dtor) {
        BasicBlock block = dp.getBlock();
        Instruction before = dp.getBeforeInstruction();
        Value objRef = dp.getObjectRef();

        if (!isLiveReference(objRef)) {
            // A destruction point without a live reference has nothing to
            // free. Such points arise for aliases (`var c = a`) and for values
            // whose type was not resolved during lifetime analysis. Skip them
            // silently: the only alternative would be to emit `free(i8* null)`,
            // which does nothing.
            return;
        }

        // ------------------------------------------------------------------
        // Locate the insertion index and re-verify the lifetime invariant.
        //
        // The index is `indexOf(before)` when `before` is present and
        // resolves, otherwise the end of the block (i.e. insts.size()).
        // Whichever index is chosen, no instruction at or after it may
        // consume objRef -- otherwise the destructor call would run while
        // a live reference to the object is still in flight, and free()
        // would invalidate that reference for the following use. See
        // noLaterUse() for the full rationale.
        // ------------------------------------------------------------------
        List<Instruction> insts = block.getInstructions();
        int insertIndex;

        if (before != null) {
            int index = insts.indexOf(before);
            insertIndex = (index >= 0) ? index : insts.size();
        } else {
            insertIndex = insts.size();
        }

        if (!noLaterUse(objRef, block, insertIndex)) {
            return;
        }

        Instruction call = new Instruction(Opcode.STATIC_CALL);
        call.addOperand(new Constant(Type.reference(dtor.getName()), dtor.getName()));
        call.addOperand(objRef);
        call.setParent(block);

        insts.add(insertIndex, call);
    }

    /**
     * Returns {@code true} iff {@code v} can denote a live Java object for
     * which generating a {@code free()} makes sense.
     *
     * <p>It filters out exactly the cases that used to produce
     * {@code free(i8* null)} in the generated IR:</p>
     *
     * <ul>
     *   <li>{@code v == null} — the operand is absent;</li>
     *   <li>{@code v instanceof Constant} with {@code getValue() == null} —
     *       a null constant;</li>
     *   <li>type {@code NULL} or {@code UNKNOWN} — the type shows the value is
     *       not a reference to an allocated object.</li>
     * </ul>
     */
    private static boolean isLiveReference(Value v) {
        if (v == null) return false;
        if (v instanceof Constant c && c.getValue() == null) return false;
        Type t = v.getType();
        if (t == null) return false;
        return !t.isNull() && !t.isUnknown();
    }

    /**
     * Returns true when the object reference can be legally named at
     * {@code block}.
     *
     * <p>Constants and parameters are always available. A temporary is
     * available when its defining instruction is in {@code block} itself,
     * or when the defining block <em>dominates</em> {@code block}. The
     * dominance question is answered by the function's dominator tree,
     * built lazily and cached for the duration of this inserter's life.</p>
     *
     * <p>The previous revision approximated dominance with a bounded
     * backward-reachability walk: starting from {@code block}, walk
     * predecessors up to a fixed bound and report "available" when the
     * defining block is reached. Reachability is not dominance. A block
     * that is merely on <em>some</em> path to the insertion block does
     * not dominate it — the other predecessors of the insertion block
     * may bypass the defining block entirely, and any value defined only
     * there is undefined on those paths. LLVM detects exactly this and
     * rejects the module. The concrete failure the walk produced is
     * documented at the top of this method's fix.</p>
     *
     * <p>Unlike the earlier reachability walk, the dominance check has no
     * arbitrary bound: the dominator tree is exact for every legal CFG,
     * and it is computed in linear time.</p>
     */
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
     * Returns true when no instruction at index {@code fromIndex} or later
     * in {@code block} consumes {@code objRef}, and the block's terminator
     * does not consume it either.
     *
     * <p>This is the last line of defence against a DestructionPoint whose
     * insertion index lies before the object reference's last use. With a
     * correctly computed DestructionPoint the check is redundant: the
     * lifetime analyser places the point strictly after the value's final
     * consumption. It exists so that a future change to the analyser, or
     * an unusual IR shape the analyser did not anticipate, cannot
     * reintroduce the silent use-after-free that produced the
     * java.security.AccessController crash. A destructor call that fails
     * this check is dropped entirely -- the object stays alive until
     * process exit, which is strictly safer than freeing it while a live
     * reference to it is still in flight.</p>
     */
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
                case CondBranchTerminator cbt -> {
                    if (cbt.getCondition() == objRef) return false;
                }
                case ReturnTerminator rt -> {
                    if (rt.getValue() == objRef) return false;
                }
                case ThrowTerminator tt -> {
                    if (tt.getException() == objRef) return false;
                }
                case LookupSwitchTerminator lst -> {
                    if (lst.getKey() == objRef) return false;
                }
                case TableSwitchTerminator tst -> {
                    if (tst.getKey() == objRef) return false;
                }
                case IndirectBranchTerminator ibt -> {
                    if (ibt.getTargetBlock() == objRef) return false;
                }
                default -> { }
            }
        }
        return true;
    }
}
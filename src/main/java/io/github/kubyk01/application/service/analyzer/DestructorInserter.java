package io.github.kubyk01.application.service.analyzer;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToGraph;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.BranchTerminator;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
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

    public void insert() {
        // 1. Collect classes and array types encountered at destruction points
        Set<Type> classTypes = new HashSet<>();
        for (AllocationSite site : lifetimeResult.getDestructionPoints().keySet()) {
            Type type = site.getType();
            if (isManagedType(type)) {
                classTypes.add(type);
            }
        }

        // 2. Generate destructors, including reference field types and array types (transitively)
        Map<String, Function> destructorMap = new HashMap<>();
        Queue<Type> worklist = new ArrayDeque<>(classTypes);
        Set<Type> processed = new HashSet<>();
        while (!worklist.isEmpty()) {
            Type type = worklist.poll();
            if (!processed.add(type)) continue;
            Function dtor = createDestructor(type);
            if (dtor == null) continue;
            destructorMap.put(type.toString(), dtor);

            // If this is a class, add reference fields and array fields
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
                // Also add superclass to worklist if it is managed
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
            Type type = site.getType();
            if (type.isUnknown() || type.isNull()) continue;
            if (!isManagedType(type)) continue;   // skip system types
            Function dtor = findDestructor(type);
            if (dtor == null) continue;
            for (DestructionPoint dp : entry.getValue()) {
                insertDestructorCall(dp, dtor);
                inserted++;
            }
        }
        log.info("Destructor insertion: {} destructors generated, {} calls inserted",
            destructorMap.size(), inserted);

        // After inserting regular destructors, create the shutdown function
        createShutdownFunction();
    }

    private void createShutdownFunction() {
        String funcName = LlvmRuntime.mangleFunction("__jnative_shutdown");
        Function existing = module.getFunction(funcName);
        if (existing != null) return;

        Function func = new Function(funcName, Type.VOID);
        module.addFunction(func);
        BasicBlock entry = new BasicBlock(funcName + "_entry");
        func.addBlock(entry);
        func.setEntryBlock(entry);

        BasicBlock current = entry;
        PointsToGraph graph = aliasResult.getGraph();
        Map<String, PointsToSet> staticFields = graph.getStaticFieldPointsToMap();

        Set<AllocationSite> processedSites = new HashSet<>();

        for (Map.Entry<String, PointsToSet> entryStatic : staticFields.entrySet()) {
            String fieldName = entryStatic.getKey();
            PointsToSet pts = entryStatic.getValue();
            for (AllocationSite site : pts.getSites()) {
                if (processedSites.contains(site)) continue;
                Type type = site.getType();
                if (type.isUnknown() || type.isNull()) continue;
                if (!isManagedType(type)) continue;   // skip system types
                Function dtor = findDestructor(type);
                if (dtor == null) continue;
                processedSites.add(site);

                Instruction getStatic = new Instruction(Opcode.GET_STATIC);
                getStatic.addOperand(new Constant(Type.reference(fieldName), fieldName));
                Temporary staticVal = new Temporary(Type.reference("java/lang/Object"));
                getStatic.setResult(staticVal);
                staticVal.setDefiningInstruction(getStatic);
                current.addInstruction(getStatic);

                Constant nullConst = new Constant(Type.NULL, null);
                Instruction isNull = new Instruction(Opcode.EQ);
                isNull.addOperand(staticVal);
                isNull.addOperand(nullConst);
                Temporary isNullTmp = new Temporary(Type.BOOLEAN);
                isNull.setResult(isNullTmp);
                isNullTmp.setDefiningInstruction(isNull);
                current.addInstruction(isNull);

                String sanitizedField = fieldName.replace('.', '_').replace('/', '_');
                BasicBlock skipBlock = new BasicBlock(funcName + "_skip_" + sanitizedField);
                BasicBlock callBlock = new BasicBlock(funcName + "_call_" + sanitizedField);
                func.addBlock(skipBlock);
                func.addBlock(callBlock);

                CondBranchTerminator cond = new CondBranchTerminator(isNullTmp, skipBlock, callBlock);
                current.setTerminator(cond);
                current.addSuccessor(skipBlock);
                current.addSuccessor(callBlock);

                Instruction callDtor = new Instruction(Opcode.STATIC_CALL);
                callDtor.addOperand(new Constant(Type.reference(dtor.getName()), dtor.getName()));
                callDtor.addOperand(staticVal);
                callBlock.addInstruction(callDtor);
                callBlock.setTerminator(new BranchTerminator(skipBlock));
                callBlock.addSuccessor(skipBlock);

                current = skipBlock;
            }
        }

        current.setTerminator(new ReturnTerminator(null));
    }

    private boolean isSystemClassName(String className) {
        String dot = className.replace('/', '.');
        return dot.startsWith("java.") ||
            dot.startsWith("javax.") ||
            dot.startsWith("sun.") ||
            dot.startsWith("jdk.") ||
            dot.startsWith("org.objectweb.asm.") ||
            dot.startsWith("picocli.") ||
            dot.startsWith("reactor.") ||
            dot.startsWith("org.slf4j.") ||
            dot.startsWith("org.reactivestreams.") ||
            dot.startsWith("io.micrometer.") ||
            dot.startsWith("org.junit.") ||
            dot.startsWith("com.fasterxml.");
    }

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
            if (isSystemClassName(className)) return false;
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
        if (!isManagedType(type)) return null;   // never create destructors for system types
        if (type.isArray()) {
            return createArrayDestructor(type);
        } else if (type.isReference()) {
            return createClassDestructor(type.getClassName());
        }
        return null;
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
        module.addFunction(func);
        Parameter thisParam = new Parameter(Type.reference(className), 0);
        func.addParameter(thisParam);

        BasicBlock entry = new BasicBlock(funcName + "_entry");
        func.addBlock(entry);
        func.setEntryBlock(entry);

        Constant nullConst = new Constant(Type.NULL, null);
        Instruction cmp = new Instruction(Opcode.EQ);
        cmp.addOperand(thisParam);
        cmp.addOperand(nullConst);
        Temporary cmpResult = new Temporary(Type.BOOLEAN);
        cmp.setResult(cmpResult);
        cmpResult.setDefiningInstruction(cmp);
        entry.addInstruction(cmp);

        BasicBlock returnBlock = new BasicBlock(funcName + "_return");
        func.addBlock(returnBlock);
        returnBlock.setTerminator(new ReturnTerminator(null));

        BasicBlock bodyBlock = new BasicBlock(funcName + "_body");
        func.addBlock(bodyBlock);

        entry.setTerminator(new CondBranchTerminator(cmpResult, returnBlock, bodyBlock));
        entry.addSuccessor(returnBlock);
        entry.addSuccessor(bodyBlock);

        BasicBlock current = bodyBlock;

        for (FieldNode field : collectReferenceFields(classNode)) {
            if ((field.getAccess() & Opcodes.ACC_STATIC) != 0) continue;
            Type fieldType = field.getType();
            if (!isManagedType(fieldType)) continue;

            Instruction getField = new Instruction(Opcode.GET_FIELD);
            getField.addOperand(thisParam);
            getField.addOperand(new Constant(Type.reference(className + "." + field.getName()), className + "." + field.getName()));
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
                callDtor.addOperand(new Constant(Type.reference(fieldDtor.getName()), fieldDtor.getName()));
                callDtor.addOperand(fieldValue);
                callBlock.addInstruction(callDtor);
            }
            callBlock.setTerminator(new BranchTerminator(skipBlock));
            callBlock.addSuccessor(skipBlock);

            current = skipBlock;
        }

        String superName = classNode.getSuperName();
        if (superName != null && !superName.equals("java/lang/Object")) {
            Type superType = Type.reference(superName);
            if (isManagedType(superType)) {
                Function superDtor = findDestructor(superType);
                if (superDtor != null) {
                    Instruction callSuper = new Instruction(Opcode.STATIC_CALL);
                    callSuper.addOperand(new Constant(Type.reference(superDtor.getName()), superDtor.getName()));
                    callSuper.addOperand(thisParam);
                    current.addInstruction(callSuper);
                }
            }
        }

        Instruction freeInst = new Instruction(Opcode.FREE);
        freeInst.addOperand(thisParam);
        current.addInstruction(freeInst);
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

        BasicBlock entry = new BasicBlock(funcName + "_entry");
        func.addBlock(entry);
        func.setEntryBlock(entry);

        BasicBlock returnBlock = new BasicBlock(funcName + "_return");
        func.addBlock(returnBlock);
        returnBlock.setTerminator(new ReturnTerminator(null));

        BasicBlock bodyBlock = new BasicBlock(funcName + "_body");
        func.addBlock(bodyBlock);

        Constant nullConst = new Constant(Type.NULL, null);
        Instruction cmpNull = new Instruction(Opcode.EQ);
        cmpNull.addOperand(arrParam);
        cmpNull.addOperand(nullConst);
        Temporary cmpNullResult = new Temporary(Type.BOOLEAN);
        cmpNull.setResult(cmpNullResult);
        cmpNullResult.setDefiningInstruction(cmpNull);
        entry.addInstruction(cmpNull);

        entry.setTerminator(new CondBranchTerminator(cmpNullResult, returnBlock, bodyBlock));
        entry.addSuccessor(returnBlock);
        entry.addSuccessor(bodyBlock);

        Function elementDtor = findDestructor(elementType);
        if (elementDtor == null) {
            // fallback: only free the array itself
            Instruction freeInst = new Instruction(Opcode.FREE);
            freeInst.addOperand(arrParam);
            bodyBlock.addInstruction(freeInst);
            bodyBlock.setTerminator(new ReturnTerminator(null));
            return func;
        }

        // Get array length
        Instruction lenInst = new Instruction(Opcode.ARRAYLENGTH);
        lenInst.addOperand(arrParam);
        Temporary lenVal = new Temporary(Type.INT);
        lenInst.setResult(lenVal);
        lenVal.setDefiningInstruction(lenInst);
        bodyBlock.addInstruction(lenInst);

        // Loop counter
        Temporary counter = new Temporary(Type.INT);
        Instruction counterInit = new Instruction(Opcode.LOAD);
        counterInit.setLocalIndex(0);
        counterInit.setResult(counter);
        counter.setDefiningInstruction(counterInit);
        bodyBlock.addInstruction(counterInit);

        // Loop header
        BasicBlock loopHeader = new BasicBlock(funcName + "_loop_header");
        BasicBlock loopBody = new BasicBlock(funcName + "_loop_body");
        BasicBlock loopAfter = new BasicBlock(funcName + "_loop_after");
        func.addBlock(loopHeader);
        func.addBlock(loopBody);
        func.addBlock(loopAfter);

        bodyBlock.setTerminator(new BranchTerminator(loopHeader));
        bodyBlock.addSuccessor(loopHeader);

        // Condition: counter < length
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

        // Load element
        Instruction loadElem = new Instruction(Opcode.ALOAD);
        loadElem.addOperand(arrParam);
        loadElem.addOperand(counter);
        Temporary elemVal = new Temporary(elementType);
        loadElem.setResult(elemVal);
        elemVal.setDefiningInstruction(loadElem);
        loopBody.addInstruction(loadElem);

        // Check if element is not null
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

        // Call element destructor
        Instruction callDtor = new Instruction(Opcode.STATIC_CALL);
        callDtor.addOperand(new Constant(Type.reference(elementDtor.getName()), elementDtor.getName()));
        callDtor.addOperand(elemVal);
        callElemDtor.addInstruction(callDtor);
        callElemDtor.setTerminator(new BranchTerminator(skipElem));
        callElemDtor.addSuccessor(skipElem);

        // Increment counter
        Instruction inc = new Instruction(Opcode.ADD);
        inc.addOperand(counter);
        inc.addOperand(new Constant(Type.INT, 1));
        Temporary newCounter = new Temporary(Type.INT);
        inc.setResult(newCounter);
        newCounter.setDefiningInstruction(inc);
        skipElem.addInstruction(inc);

        // Store new counter (simulate store to local)
        Instruction storeCounter = new Instruction(Opcode.STORE);
        storeCounter.addOperand(newCounter);
        storeCounter.setLocalIndex(0);
        Temporary storedCounter = new Temporary(Type.INT);
        storeCounter.setResult(storedCounter);
        storedCounter.setDefiningInstruction(storeCounter);
        skipElem.addInstruction(storeCounter);

        skipElem.setTerminator(new BranchTerminator(loopHeader));
        skipElem.addSuccessor(loopHeader);

        // After loop: free the array itself
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
        Instruction cmpNull = new Instruction(Opcode.EQ);
        cmpNull.addOperand(arrParam);
        cmpNull.addOperand(nullConst);
        Temporary cmpNullResult = new Temporary(Type.BOOLEAN);
        cmpNull.setResult(cmpNullResult);
        cmpNullResult.setDefiningInstruction(cmpNull);
        entry.addInstruction(cmpNull);

        BasicBlock returnBlock = new BasicBlock(funcName + "_return");
        func.addBlock(returnBlock);
        returnBlock.setTerminator(new ReturnTerminator(null));

        BasicBlock bodyBlock = new BasicBlock(funcName + "_body");
        func.addBlock(bodyBlock);
        Instruction freeInst = new Instruction(Opcode.FREE);
        freeInst.addOperand(arrParam);
        bodyBlock.addInstruction(freeInst);
        bodyBlock.setTerminator(new ReturnTerminator(null));

        entry.setTerminator(new CondBranchTerminator(cmpNullResult, returnBlock, bodyBlock));
        entry.addSuccessor(returnBlock);
        entry.addSuccessor(bodyBlock);

        return func;
    }

    private Function findDestructor(Type type) {
        if (!isManagedType(type)) return null;   // never return destructor for system types
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

    private void insertDestructorCall(DestructionPoint dp, Function dtor) {
        BasicBlock block = dp.getBlock();
        Instruction before = dp.getBeforeInstruction();
        Value objRef = dp.getObjectRef();

        Instruction call = new Instruction(Opcode.STATIC_CALL);
        call.addOperand(new Constant(Type.reference(dtor.getName()), dtor.getName()));
        call.addOperand(objRef);

        if (before != null) {
            int index = block.getInstructions().indexOf(before);
            if (index >= 0) {
                block.getInstructions().add(index, call);
                call.setParent(block);
                return;
            }
        }
        block.addInstruction(call);
    }
}
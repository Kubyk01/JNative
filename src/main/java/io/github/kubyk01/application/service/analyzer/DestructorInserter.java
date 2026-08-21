package io.github.kubyk01.application.service.analyzer;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToGraph;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.ir.BasicBlock;
import io.github.kubyk01.domain.analyzer.ir.BranchTerminator;
import io.github.kubyk01.domain.analyzer.ir.CondBranchTerminator;
import io.github.kubyk01.domain.analyzer.ir.Constant;
import io.github.kubyk01.domain.analyzer.ir.Function;
import io.github.kubyk01.domain.analyzer.ir.Instruction;
import io.github.kubyk01.domain.analyzer.ir.Module;
import io.github.kubyk01.domain.analyzer.ir.Opcode;
import io.github.kubyk01.domain.analyzer.ir.Parameter;
import io.github.kubyk01.domain.analyzer.ir.ReturnTerminator;
import io.github.kubyk01.domain.analyzer.ir.Temporary;
import io.github.kubyk01.domain.analyzer.ir.Type;
import io.github.kubyk01.domain.analyzer.ir.Value;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    private final Map<AllocationSite, Value> siteToValue;
    private final AliasAnalysisResult aliasResult;

    public void insert() {
        // 1. Collect classes and array types encountered at destruction points
        Set<String> classNames = new HashSet<>();
        for (AllocationSite site : lifetimeResult.getDestructionPoints().keySet()) {
            String type = site.getType();
            if (isManagedClass(type)) {
                classNames.add(type);
            }
        }

        // 2. Generate destructors, including reference field types and array types (transitively)
        Map<String, Function> destructorMap = new HashMap<>();
        Queue<String> worklist = new ArrayDeque<>(classNames);
        Set<String> processed = new HashSet<>();
        while (!worklist.isEmpty()) {
            String className = worklist.poll();
            if (!processed.add(className)) continue;
            Function dtor = createDestructor(className);
            if (dtor == null) continue;
            destructorMap.put(className, dtor);

            // If this is a class, add reference fields and array fields
            if (!isArrayType(className)) {
                ClassNode classNode = resolver.getClassNode(className);
                for (FieldNode field : classNode.getFields()) {
                    String fieldDesc = field.getDescriptor();
                    // If the field is a reference array or an object
                    if (fieldDesc.startsWith("L") || fieldDesc.startsWith("[")) {
                        String fieldType = fieldDesc.startsWith("[")
                                ? fieldDesc  // keep the array descriptor
                                : extractClassName(fieldDesc);
                        if (isManagedClass(fieldType)) {
                            worklist.add(fieldType);
                        }
                    }
                }
            }
        }

        // 3. Insert destructor calls at destruction points
        int inserted = 0;
        for (Map.Entry<AllocationSite, Set<DestructionPoint>> entry
                : lifetimeResult.getDestructionPoints().entrySet()) {
            AllocationSite site = entry.getKey();
            Function dtor = destructorMap.get(site.getType());
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

    /**
     * Creates the __jnative_shutdown function that destroys all objects stored
     * in static fields. Registration via atexit is performed at the code generation stage.
     */
    private Function createShutdownFunction() {
        String funcName = "__jnative_shutdown";
        Function existing = module.getFunction(funcName);
        if (existing != null) return existing;

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
            String fieldName = entryStatic.getKey(); // full field name
            PointsToSet pts = entryStatic.getValue();
            for (AllocationSite site : pts.getSites()) {
                if (processedSites.contains(site)) continue;
                // Check whether a destructor exists for the site's type
                Function dtor = findDestructor(site.getType());
                if (dtor == null) continue;
                processedSites.add(site);

                // Load the value from the static field
                Instruction getStatic = new Instruction(Opcode.GET_STATIC);
                getStatic.addOperand(new Constant(Type.REFERENCE, fieldName));
                Temporary staticVal = new Temporary(Type.REFERENCE);
                getStatic.setResult(staticVal);
                staticVal.setDefiningInstruction(getStatic);
                current.addInstruction(getStatic);

                // Null check
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

                // Destructor call
                Instruction callDtor = new Instruction(Opcode.STATIC_CALL);
                callDtor.addOperand(new Constant(Type.REFERENCE, dtor.getName()));
                callDtor.addOperand(staticVal);
                callBlock.addInstruction(callDtor);
                callBlock.setTerminator(new BranchTerminator(skipBlock));
                callBlock.addSuccessor(skipBlock);

                current = skipBlock;
            }
        }

        // Return from the function
        current.setTerminator(new ReturnTerminator(null));
        return func;
    }

    // ---- Helper methods for working with types ----

    private boolean isManagedClass(String type) {
        if (type == null) return false;
        if (isArrayType(type)) {
            // Arrays are managed only if their element type is a reference type
            String elementType = getArrayElementType(type);
            return !isPrimitiveType(elementType) && isManagedClass(elementType);
        }
        return !type.startsWith("array")
                && !type.startsWith("multiarray")
                && !type.equals("unknown")
                && !type.equals("<unknown>");
    }

    private boolean isArrayType(String type) {
        return type != null && type.startsWith("[");
    }

    private boolean isPrimitiveType(String type) {
        return type.length() == 1 && "ZBCSIFJD".indexOf(type.charAt(0)) >= 0;
    }

    /**
     * Extracts the element type from an array descriptor.
     * Reference elements are normalized to the internal class name,
     * so that destructor names match those used for regular classes.
     * Example: "[Ljava/lang/String;" -> "java/lang/String", "[[I" -> "[I"
     */
    private String getArrayElementType(String descriptor) {
        if (!descriptor.startsWith("[")) return descriptor;
        String inner = descriptor.substring(1);
        if (inner.startsWith("L") && inner.endsWith(";")) {
            return inner.substring(1, inner.length() - 1); // reference type
        }
        // primitive or another array level
        return inner;
    }

    /**
     * Extracts the internal class name from an object descriptor.
     * For example: "Ljava/lang/String;" -> "java/lang/String"
     */
    private String extractClassName(String descriptor) {
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length() - 1);
        }
        return null;
    }

    // ---- Destructor creation ----

    private Function createDestructor(String type) {
        if (isArrayType(type)) {
            return createArrayDestructor(type);
        } else {
            return createClassDestructor(type);
        }
    }

    /**
     * Creates a destructor for a class.
     */
    private Function createClassDestructor(String className) {
        ClassNode classNode = resolver.getClassNode(className);
        if (classNode.isExternal()) {
            log.debug("Skipping destructor for external class: {}", className);
            return null;
        }

        String funcName = destructorName(className);
        Function existing = module.getFunction(funcName);
        if (existing != null) {
            return existing;
        }

        Function func = new Function(funcName, Type.VOID);
        module.addFunction(func);
        Parameter thisParam = new Parameter(Type.REFERENCE, 0);
        func.addParameter(thisParam);

        BasicBlock entry = new BasicBlock(funcName + "_entry");
        func.addBlock(entry);
        func.setEntryBlock(entry);

        // if (this == null) return;
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

        // Process reference fields and array fields
        for (FieldNode field : collectReferenceFields(classNode)) {
            String fieldDesc = field.getDescriptor();
            String fieldType = fieldDesc.startsWith("[")
                    ? fieldDesc
                    : extractClassName(fieldDesc);
            if (!isManagedClass(fieldType)) continue;

            // Field load: fieldValue = this.field
            Instruction getField = new Instruction(Opcode.GET_FIELD);
            getField.addOperand(thisParam);
            getField.addOperand(new Constant(Type.REFERENCE, className + "." + field.getName()));
            Temporary fieldValue = new Temporary(Type.REFERENCE);
            getField.setResult(fieldValue);
            fieldValue.setDefiningInstruction(getField);
            current.addInstruction(getField);

            // if (fieldValue == null) skip
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

            // Call the destructor for the field
            Function fieldDtor = findDestructor(fieldType);
            if (fieldDtor != null) {
                Instruction callDtor = new Instruction(Opcode.STATIC_CALL);
                callDtor.addOperand(new Constant(Type.REFERENCE, fieldDtor.getName()));
                callDtor.addOperand(fieldValue);
                callBlock.addInstruction(callDtor);
            }
            callBlock.setTerminator(new BranchTerminator(skipBlock));
            callBlock.addSuccessor(skipBlock);

            current = skipBlock;
        }

        // free(this); return;
        Instruction freeInst = new Instruction(Opcode.FREE);
        freeInst.addOperand(thisParam);
        current.addInstruction(freeInst);
        current.setTerminator(new ReturnTerminator(null));

        return func;
    }

    /**
     * Creates a destructor for an array.
     * @param arrayDescriptor the array descriptor, e.g. "[Ljava/lang/String;" or "[[I"
     * @return the destructor function, or null if the array holds primitives
     */
    private Function createArrayDestructor(String arrayDescriptor) {
        String elementType = getArrayElementType(arrayDescriptor);
        if (isPrimitiveType(elementType)) {
            // Primitive arrays do not require a destructor
            return null;
        }

        String funcName = destructorName(arrayDescriptor);
        Function existing = module.getFunction(funcName);
        if (existing != null) {
            return existing;
        }

        Function func = new Function(funcName, Type.VOID);
        module.addFunction(func);
        Parameter arrParam = new Parameter(Type.REFERENCE, 0);
        func.addParameter(arrParam);

        // Blocks:
        // entry: null check -> return or body
        // body: get length, initialize i=0
        // loop_cond: check i < length
        // loop_body: load element, null check, call destructor, increment i
        // loop_end: return

        BasicBlock entry = new BasicBlock(funcName + "_entry");
        func.addBlock(entry);
        func.setEntryBlock(entry);

        BasicBlock returnBlock = new BasicBlock(funcName + "_return");
        func.addBlock(returnBlock);
        returnBlock.setTerminator(new ReturnTerminator(null));

        BasicBlock bodyBlock = new BasicBlock(funcName + "_body");
        func.addBlock(bodyBlock);

        // Check arr == null
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

        // Make sure the element has a destructor (recursively).
        // The function is already registered in the module, so cycles like
        // A[] -> Node -> A[] do not lead to duplicate creation.
        Function elementDtor = findDestructor(elementType);
        if (elementDtor == null) {
            // Elements are external/unmanaged - free(this) is enough for the array
            Instruction freeInst = new Instruction(Opcode.FREE);
            freeInst.addOperand(arrParam);
            bodyBlock.addInstruction(freeInst);
            bodyBlock.setTerminator(new ReturnTerminator(null));
            return func;
        }

        // In body: length = arr.length
        Instruction lenInst = new Instruction(Opcode.ARRAYLENGTH);
        lenInst.addOperand(arrParam);
        Temporary lenTmp = new Temporary(Type.INT);
        lenInst.setResult(lenTmp);
        lenTmp.setDefiningInstruction(lenInst);
        bodyBlock.addInstruction(lenInst);

        // i = 0
        Temporary iTmp = new Temporary(Type.INT);
        Constant zero = new Constant(Type.INT, 0);
        Instruction storeI = new Instruction(Opcode.STORE);
        storeI.addOperand(zero);
        storeI.setLocalIndex(0); // use local 0 for i
        storeI.setResult(iTmp);
        iTmp.setDefiningInstruction(storeI);
        bodyBlock.addInstruction(storeI);

        // Jump to the loop condition
        BasicBlock loopCondBlock = new BasicBlock(funcName + "_loop_cond");
        func.addBlock(loopCondBlock);
        bodyBlock.setTerminator(new BranchTerminator(loopCondBlock));
        bodyBlock.addSuccessor(loopCondBlock);

        // Loop condition: i < length
        Instruction loadI = new Instruction(Opcode.LOAD);
        loadI.setLocalIndex(0);
        Temporary iLoaded = new Temporary(Type.INT);
        loadI.setResult(iLoaded);
        iLoaded.setDefiningInstruction(loadI);
        loopCondBlock.addInstruction(loadI);

        Instruction cmpLT = new Instruction(Opcode.LT);
        cmpLT.addOperand(iLoaded);
        cmpLT.addOperand(lenTmp);
        Temporary cmpLTResult = new Temporary(Type.BOOLEAN);
        cmpLT.setResult(cmpLTResult);
        cmpLTResult.setDefiningInstruction(cmpLT);
        loopCondBlock.addInstruction(cmpLT);

        BasicBlock loopBodyBlock = new BasicBlock(funcName + "_loop_body");
        func.addBlock(loopBodyBlock);
        loopCondBlock.setTerminator(new CondBranchTerminator(cmpLTResult, loopBodyBlock, returnBlock));
        loopCondBlock.addSuccessor(loopBodyBlock);
        loopCondBlock.addSuccessor(returnBlock);

        // Loop body: element = arr[i]
        Instruction loadElement = new Instruction(Opcode.ALOAD);
        loadElement.addOperand(arrParam);
        loadElement.addOperand(iLoaded);
        Temporary elementTmp = new Temporary(Type.REFERENCE);
        loadElement.setResult(elementTmp);
        elementTmp.setDefiningInstruction(loadElement);
        loopBodyBlock.addInstruction(loadElement);

        // Check element != null
        Instruction cmpElemNull = new Instruction(Opcode.NE);
        cmpElemNull.addOperand(elementTmp);
        cmpElemNull.addOperand(nullConst);
        Temporary cmpElemNullResult = new Temporary(Type.BOOLEAN);
        cmpElemNull.setResult(cmpElemNullResult);
        cmpElemNullResult.setDefiningInstruction(cmpElemNull);
        loopBodyBlock.addInstruction(cmpElemNull);

        BasicBlock callElemBlock = new BasicBlock(funcName + "_call_elem");
        BasicBlock skipElemBlock = new BasicBlock(funcName + "_skip_elem");
        func.addBlock(callElemBlock);
        func.addBlock(skipElemBlock);

        loopBodyBlock.setTerminator(new CondBranchTerminator(cmpElemNullResult, callElemBlock, skipElemBlock));
        loopBodyBlock.addSuccessor(callElemBlock);
        loopBodyBlock.addSuccessor(skipElemBlock);

        // Element destructor call
        Instruction callElemDtor = new Instruction(Opcode.STATIC_CALL);
        callElemDtor.addOperand(new Constant(Type.REFERENCE, elementDtor.getName()));
        callElemDtor.addOperand(elementTmp);
        callElemBlock.addInstruction(callElemDtor);
        callElemBlock.setTerminator(new BranchTerminator(skipElemBlock));
        callElemBlock.addSuccessor(skipElemBlock);

        // i = i + 1
        Constant one = new Constant(Type.INT, 1);
        Instruction addI = new Instruction(Opcode.ADD);
        addI.addOperand(iLoaded);
        addI.addOperand(one);
        Temporary iNext = new Temporary(Type.INT);
        addI.setResult(iNext);
        iNext.setDefiningInstruction(addI);
        skipElemBlock.addInstruction(addI);

        // store iNext into local[0]
        Instruction storeINext = new Instruction(Opcode.STORE);
        storeINext.addOperand(iNext);
        storeINext.setLocalIndex(0);
        Temporary storeResult = new Temporary(Type.INT);
        storeINext.setResult(storeResult);
        storeResult.setDefiningInstruction(storeINext);
        skipElemBlock.addInstruction(storeINext);

        // Jump back to the loop condition
        skipElemBlock.setTerminator(new BranchTerminator(loopCondBlock));
        skipElemBlock.addSuccessor(loopCondBlock);

        return func;
    }

    private String destructorName(String type) {
        if (type.startsWith("[")) {
            // For arrays, generate a name based on the descriptor
            return "__destruct_array_" + type.replace('/', '_').replace('[', '_').replace(';', '_');
        } else {
            return "__destruct_" + type.replace('/', '_').replace('.', '_');
        }
    }

    /**
     * Finds or creates a destructor for the given type.
     */
    private Function findDestructor(String type) {
        Function existing = module.getFunction(destructorName(type));
        if (existing != null) return existing;
        // Recursive creation
        return createDestructor(type);
    }

    private List<FieldNode> collectReferenceFields(ClassNode classNode) {
        List<FieldNode> refFields = new ArrayList<>();
        for (FieldNode field : classNode.getFields()) {
            String desc = field.getDescriptor();
            // Object fields or arrays
            if (desc.startsWith("L") || desc.startsWith("[")) {
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
        call.addOperand(new Constant(Type.REFERENCE, dtor.getName()));
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

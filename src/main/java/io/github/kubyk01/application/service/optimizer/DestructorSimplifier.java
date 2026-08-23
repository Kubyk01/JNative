package io.github.kubyk01.application.service.optimizer;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.ir.*;
import io.github.kubyk01.domain.analyzer.ir.Module;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
@RequiredArgsConstructor
public class DestructorSimplifier {

    private final Module module;
    @Getter
    private final AliasAnalysisResult aliasResult;
    private final LifetimeAnalysisResult lifetimeResult;
    @Getter
    private final Map<AllocationSite, Value> siteToValue;
    private final boolean enableSimplification;
    private final boolean enableInlining;
    private final boolean enableDeadElimination;

    private final Set<Function> destructors = new HashSet<>();
    private final Set<Function> trivialDestructors = new HashSet<>();

    public void simplify() {
        // Collect all destructors
        for (Function func : module.getFunctions()) {
            if (func.getName().startsWith("__destruct_") || func.getName().startsWith("__destruct_array_")) {
                destructors.add(func);
            }
        }

        if (enableDeadElimination) {
            eliminateDeadDestructors();
        }

        if (enableSimplification) {
            simplifyDestructors();
        }

        if (enableInlining) {
            inlineDestructors();
        }
    }

    private void eliminateDeadDestructors() {
        // Build the destructor call graph from destruction points and the shutdown function
        Set<Function> called = new HashSet<>();
        for (Map.Entry<AllocationSite, Set<DestructionPoint>> entry : lifetimeResult.getDestructionPoints().entrySet()) {
            Type type = entry.getKey().getType();
            Function dtor = module.getFunction(destructorName(type));
            if (dtor != null) called.add(dtor);
        }
        // The shutdown function may also call destructors of static fields
        Function shutdown = module.getFunction("__jnative_shutdown");
        if (shutdown != null) {
            collectCalls(shutdown, called);
        }

        // Remove destructors that are never called
        Set<Function> dead = new HashSet<>(destructors);
        dead.removeAll(called);
        for (Function func : dead) {
            log.debug("Removing dead destructor: {}", func.getName());
            module.getFunctions().remove(func);
            destructors.remove(func);
        }
    }

    private void collectCalls(Function func, Set<Function> accumulator) {
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                // Check only static calls (destructors are called statically)
                if (inst.getOpcode() == Opcode.STATIC_CALL && !inst.getOperands().isEmpty()) {
                    Value callee = inst.getOperands().getFirst();
                    if (callee instanceof Constant c) {
                        Object val = c.getValue();
                        if (val instanceof String name) {
                            Function called = module.getFunction(name);
                            if (called != null && (called.getName().startsWith("__destruct_") || called.getName().startsWith("__destruct_array_"))) {
                                if (accumulator.add(called)) {
                                    collectCalls(called, accumulator);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void simplifyDestructors() {
        for (Function func : destructors) {
            // If a destructor consists only of a null check and free, replace it with a simple free
            if (isTrivialDestructor(func)) {
                simplifyToFree(func);
                trivialDestructors.add(func);
            }
        }
    }

    private boolean isTrivialDestructor(Function func) {
        // Check that the destructor contains only EQ, COND_BRANCH, FREE and RETURN instructions
        // and has no calls to other destructors
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                Opcode op = inst.getOpcode();
                if (op == Opcode.EQ || op == Opcode.COND_BRANCH || op == Opcode.FREE || op == Opcode.RETURN) {
                    // allowed
                } else if (op == Opcode.STATIC_CALL) {
                    return false; // has a call — not trivial
                } else {
                    return false; // other instructions
                }
            }
        }
        return true;
    }

    private void simplifyToFree(Function func) {
        // Replace the destructor body with a single FREE instruction and RETURN
        BasicBlock entry = func.getEntryBlock();
        if (entry == null) return;
        // Clear the instructions in entry
        entry.getInstructions().clear();
        // Add only free and return
        Parameter thisParam = func.getParameters().getFirst();
        Instruction freeInst = new Instruction(Opcode.FREE);
        freeInst.addOperand(thisParam);
        entry.addInstruction(freeInst);
        entry.setTerminator(new ReturnTerminator(null));
        // Remove the remaining blocks
        List<BasicBlock> toRemove = new ArrayList<>(func.getBlocks());
        toRemove.remove(entry);
        for (BasicBlock block : toRemove) {
            func.getBlocks().remove(block);
        }
        log.debug("Simplified destructor: {}", func.getName());
    }

    private void inlineDestructors() {
        // For each call to a trivial destructor, replace the call with FREE
        for (Function caller : module.getFunctions()) {
            if (caller.getEntryBlock() == null) continue;
            for (BasicBlock block : caller.getBlocks()) {
                List<Instruction> instructions = block.getInstructions();
                for (int i = 0; i < instructions.size(); i++) {
                    Instruction callInst = instructions.get(i);
                    // Look for a static call
                    if (callInst.getOpcode() == Opcode.STATIC_CALL && !callInst.getOperands().isEmpty()) {
                        Value callee = callInst.getOperands().get(0);
                        if (callee instanceof Constant c) {
                            Object val = c.getValue();
                            if (val instanceof String name) {
                                Function dtor = module.getFunction(name);
                                if (dtor != null && trivialDestructors.contains(dtor)) {
                                    // Replace the call with FREE
                                    // The argument (object) is at position 1
                                    Value obj = callInst.getOperands().size() > 1 ? callInst.getOperands().get(1) : null;
                                    if (obj != null) {
                                        Instruction freeInst = new Instruction(Opcode.FREE);
                                        freeInst.addOperand(obj);
                                        instructions.set(i, freeInst);
                                        log.debug("Inlined destructor call to FREE: {}", name);
                                    } else {
                                        // If there is no object (should not happen), remove the call
                                        instructions.remove(i);
                                        i--;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Remove trivial destructors that were inlined (and are no longer called)
        // But they may still be called from other places, so we only remove those without calls.
        // For simplicity we keep them; they will be removed later if unused.
        // But we could check whether any calls remain.
        // Not removing them for now.
    }

    private String destructorName(Type type) {
        if (type.isArray()) {
            return "__destruct_array_" + type.toString().replace('/', '_').replace('[', '_').replace(';', '_');
        } else if (type.isReference()) {
            return "__destruct_" + type.getClassName().replace('/', '_').replace('.', '_');
        }
        return "__destruct_unknown";
    }
}
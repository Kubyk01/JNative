package io.github.kubyk01.application.service.optimizer;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
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
        // Sound over-approximation of "everything that might be needed at
        // runtime": walk every function in the module and collect the set of
        // destructors that appear as a STATIC_CALL callee anywhere. This
        // includes __jnative_shutdown, which is registered with atexit and
        // is therefore always live, and any user function that directly
        // calls a destructor.
        //
        // The previous implementation seeded the walk only from
        // lifetimeResult.getDestructionPoints() and from __jnative_shutdown.
        // That missed destructors that are reachable only from
        // __jnative_shutdown's guarded branches (which is the normal case
        // when a static field's points-to set has no corresponding entry in
        // the lifetime analysis). The result was that a referenced
        // destructor was removed from the module while its call site
        // remained in the emitted IR, and LLVM rejected the module with
        // "use of undefined value '@__destruct_...'".
        Set<Function> called = new LinkedHashSet<>();

        for (Function func : new ArrayList<>(module.getFunctions())) {
            if (func.getEntryBlock() == null) continue;
            collectCalls(func, called);
        }

        // Also walk from destruction points. Those are the sites where
        // DestructorInserter will insert calls at analysis time, and they
        // may reference destructors whose bodies the module-wide walk above
        // has not yet seen (e.g. a destructor whose body was rebuilt by
        // retranslateClinit and whose callers are not yet in the module).
        for (Map.Entry<AllocationSite, Set<DestructionPoint>> entry
            : lifetimeResult.getDestructionPoints().entrySet()) {
            Type type = entry.getKey().getType();
            Function dtor = module.getFunction(destructorName(type));
            if (dtor != null && called.add(dtor)) {
                collectCalls(dtor, called);
            }
        }

        // Remove only functions that no surviving call site references, and
        // remove them through Module.removeFunction so that both the list
        // and the name->function map stay in sync. Removing only from the
        // list (the previous behaviour) left the deleted function resolvable
        // via Module.getFunction(name), which allowed a later pass to
        // resurrect it into the IR while its body was no longer emitted.
        Set<Function> dead = new HashSet<>(destructors);
        dead.removeAll(called);
        for (Function func : dead) {
            log.debug("Removing dead destructor: {}", func.getName());
            module.removeFunction(func);
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

    /**
     * Replaces calls to trivial destructors with direct {@code FREE}
     * instructions.
     *
     * <p>A destructor is trivial when its body was reduced by
     * {@link #simplifyToFree} to a single {@code FREE} instruction. Such a call
     * does not need to go through the linker: {@code FREE} is a built-in
     * runtime operation.</p>
     *
     * <p>The replacement is performed <em>only</em> when the destructor
     * argument is a live reference. Every other case — Java {@code null},
     * {@code Constant(null)}, type {@code NULL}/{@code UNKNOWN} — causes the
     * call to be removed outright. Reasons:</p>
     *
     * <ul>
     *   <li>{@code free(NULL)} per the C standard is a no-op, so removal is
     *       semantically equivalent to replacement;</li>
     *   <li>but {@code free(i8* null)} in LLVM IR is not a no-op, it is a real
     *       function call with a null pointer. It is redundant in the output,
     *       hurts readability and masks more serious lifetime-analysis
     *       problems.</li>
     * </ul>
     */
    private void inlineDestructors() {
        for (Function caller : module.getFunctions()) {
            if (caller.getEntryBlock() == null) continue;
            for (BasicBlock block : caller.getBlocks()) {
                List<Instruction> instructions = block.getInstructions();
                for (int i = 0; i < instructions.size(); i++) {
                    Instruction callInst = instructions.get(i);

                    if (callInst.getOpcode() != Opcode.STATIC_CALL) continue;
                    if (callInst.getOperands().isEmpty()) continue;

                    Value callee = callInst.getOperands().getFirst();
                    if (!(callee instanceof Constant c)) continue;

                    Object val = c.getValue();
                    if (!(val instanceof String name)) continue;

                    Function dtor = module.getFunction(name);
                    if (dtor == null || !trivialDestructors.contains(dtor)) continue;

                    // The destructor argument is operand #1. It is precisely
                    // the object the destructor must free.
                    Value obj = callInst.getOperands().size() > 1
                        ? callInst.getOperands().get(1)
                        : null;

                    // ---- dead-reference filtering -----------------------
                    //
                    // All three checks below guard against the same thing:
                    // emitting a free() call with a null pointer into the IR.
                    // The cases differ only in which form the "null pointer"
                    // arrived in:
                    //
                    //   1. Java null: the operand is simply absent
                    //      (DestructorInserter could not find a reference —
                    //      this is the normal case for aliases such as
                    //      `var c = a`, where a already owns the storage and
                    //      is freed separately).
                    //
                    //   2. Constant(null): the same thing, but represented by
                    //      an explicit null constant. The emitter turns it into
                    //      the literal `null` in the IR text.
                    //
                    //   3. NULL/UNKNOWN type: a value with such a type cannot
                    //      be the result of NEW; it is definitely an invalid
                    //      reference left behind by some earlier pass.
                    //
                    // In all three cases the correct action is to remove the
                    // call entirely rather than replace it with free(null).
                    if (obj == null
                        || (obj instanceof Constant oc && oc.getValue() == null)
                        || obj.getType() == null
                        || obj.getType().isNull()
                        || obj.getType().isUnknown()) {
                        instructions.remove(i);
                        i--;
                        continue;
                    }

                    Instruction freeInst = new Instruction(Opcode.FREE);
                    freeInst.addOperand(obj);
                    freeInst.setParent(block);
                    instructions.set(i, freeInst);
                    log.debug("Inlined destructor call to FREE: {}", name);
                }
            }
        }
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
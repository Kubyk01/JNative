package io.github.kubyk01.domain.ir;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
public class BasicBlock {
    private final String label;
    private final List<Instruction> instructions = new ArrayList<>();
    private Terminator terminator;
    @Setter
    private Function function;
    private final List<BasicBlock> predecessors = new ArrayList<>();
    private final List<BasicBlock> successors = new ArrayList<>();
    private final List<BasicBlock> exceptionalSuccessors = new ArrayList<>();
    @Getter(AccessLevel.NONE)
    private final List<BasicBlock> successorsView = Collections.unmodifiableList(successors);
    @Getter(AccessLevel.NONE)
    private List<BasicBlock> mergedSuccessors;

    public BasicBlock(String label) {
        this.label = label;
    }

    public void setTerminator(Terminator terminator) {
        if (this.terminator != null) {
            for (BasicBlock oldTarget : this.terminator.getTargets()) {
                if (oldTarget != null) {
                    removeSuccessor(oldTarget);
                }
            }
        }
        this.terminator = terminator;
        if (terminator == null) return;
        terminator.setBlock(this);
        for (BasicBlock target : terminator.getTargets()) {
            if (target != null) {
                addSuccessor(target);
            }
        }
    }

    public void addInstruction(Instruction inst) {
        inst.setParent(this);
        instructions.add(inst);
    }

    public void addSuccessor(BasicBlock block) {
        if (successors.contains(block)) return;
        successors.add(block);
        mergedSuccessors = null;
        if (!block.predecessors.contains(this)) {
            block.predecessors.add(this);
        }
    }

    public void addExceptionalSuccessor(BasicBlock block) {
        if (exceptionalSuccessors.contains(block)) return;
        exceptionalSuccessors.add(block);
        mergedSuccessors = null;
        if (!block.predecessors.contains(this)) {
            block.predecessors.add(this);
        }
    }

    public void removeSuccessor(BasicBlock block) {
        if (successors.remove(block)) {
            mergedSuccessors = null;
            // Predecessor link is dropped only when block is no longer a
            // successor via either path. The exceptional-successor list is a
            // separate relation; if it still contains `block`, the predecessor
            // link must stay.
            if (!exceptionalSuccessors.contains(block)) {
                block.predecessors.remove(this);
            }
        }
    }

    public List<BasicBlock> getSuccessors() {
        if (exceptionalSuccessors.isEmpty()) return successorsView;
        List<BasicBlock> merged = mergedSuccessors;
        if (merged != null) return merged;
        List<BasicBlock> all = new ArrayList<>(successors.size() + exceptionalSuccessors.size());
        all.addAll(successors);
        List<? extends BasicBlock> jnLoop1 = exceptionalSuccessors;
        for (BasicBlock ex : jnLoop1) {
            if (!all.contains(ex)) all.add(ex);
        }
        merged = Collections.unmodifiableList(all);
        mergedSuccessors = merged;
        return merged;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(":\n");
        int idx = 0;
        List<? extends Instruction> jnLoop2 = instructions;
        for (Instruction inst : jnLoop2) {
            sb.append("  ").append(idx++).append(": ").append(inst).append("\n");
        }
        if (terminator != null) {
            sb.append("  TERM: ").append(terminator).append("\n");
        }
        return sb.toString();
    }
}
package io.github.kubyk01.domain.ir;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
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

    public BasicBlock(String label) {
        this.label = label;
    }

    public void setTerminator(Terminator terminator) {
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
        if (!block.predecessors.contains(this)) {
            block.predecessors.add(this);
        }
    }

    public void addExceptionalSuccessor(BasicBlock block) {
        if (exceptionalSuccessors.contains(block)) return;
        exceptionalSuccessors.add(block);
        if (!block.predecessors.contains(this)) {
            block.predecessors.add(this);
        }
    }

    public List<BasicBlock> getSuccessors() {
        if (exceptionalSuccessors.isEmpty()) {
            return new ArrayList<>(successors);
        }
        List<BasicBlock> all = new ArrayList<>(successors);
        for (BasicBlock ex : exceptionalSuccessors) {
            if (!all.contains(ex)) {
                all.add(ex);
            }
        }
        return all;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(":\n");
        int idx = 0;
        for (Instruction inst : instructions) {
            sb.append("  ").append(idx++).append(": ").append(inst).append("\n");
        }
        if (terminator != null) {
            sb.append("  TERM: ").append(terminator).append("\n");
        }
        return sb.toString();
    }
}
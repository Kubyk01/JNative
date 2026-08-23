package io.github.kubyk01.domain.analyzer.ir;

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
    private List<BasicBlock> predecessors = new ArrayList<>();
    private final List<BasicBlock> successors = new ArrayList<>();
    private final List<BasicBlock> exceptionalSuccessors = new ArrayList<>();

    public BasicBlock(String label) {
        this.label = label;
    }

    public void setTerminator(Terminator terminator) {
        this.terminator = terminator;
        if (terminator != null) terminator.setBlock(this);
    }

    public void addInstruction(Instruction inst) {
        inst.setParent(this);
        instructions.add(inst);
    }

    public void addSuccessor(BasicBlock block) {
        successors.add(block);
        block.predecessors.add(this);
    }

    public void addExceptionalSuccessor(BasicBlock block) {
        // Duplicate guard is required for multi-catch (one handler for several
        // ranges and several throwing instructions in the same block)
        if (!exceptionalSuccessors.contains(block)) {
            exceptionalSuccessors.add(block);
            block.predecessors.add(this); // also add as a predecessor
        }
    }

    /**
     * Returns the union of normal and exceptional successors.
     * Overrides the Lombok-generated getter for the {@code successors} field.
     */
    public List<BasicBlock> getSuccessors() {
        List<BasicBlock> all = new ArrayList<>(successors);
        all.addAll(exceptionalSuccessors);
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

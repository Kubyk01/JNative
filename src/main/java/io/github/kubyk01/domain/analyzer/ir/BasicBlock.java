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
    private List<BasicBlock> successors = new ArrayList<>();

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

package io.github.kubyk01.domain.ir;

import lombok.Getter;

import java.util.List;

@Getter
public class BranchTerminator extends Terminator {
    private final BasicBlock target;

    public BranchTerminator(BasicBlock target) {
        this.target = target;
    }

    @Override
    public List<BasicBlock> getTargets() { return List.of(target); }

    @Override
    public String toString() {
        return "BRANCH " + target.getLabel();
    }
}

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
    public List<BasicBlock> getTargets() {
        List<BasicBlock> t = targets;
        if (t == null) targets = t = List.of(target);
        return t;
    }

    @Getter(lombok.AccessLevel.NONE)
    private List<BasicBlock> targets;

    @Override
    public String toString() {
        return "BRANCH " + target.getLabel();
    }
}

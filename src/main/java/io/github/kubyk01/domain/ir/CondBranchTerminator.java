package io.github.kubyk01.domain.ir;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
public class CondBranchTerminator extends Terminator {
    @Setter
    private Value condition;
    private final BasicBlock trueTarget;
    private final BasicBlock falseTarget;

    public CondBranchTerminator(Value condition, BasicBlock trueTarget, BasicBlock falseTarget) {
        this.condition = condition;
        this.trueTarget = trueTarget;
        this.falseTarget = falseTarget;
    }

    @Override
    public List<BasicBlock> getTargets() { return List.of(trueTarget, falseTarget); }

    @Override
    public String toString() {
        return "COND_BRANCH " + condition + " -> " +
                trueTarget.getLabel() + ", " + falseTarget.getLabel();
    }
}

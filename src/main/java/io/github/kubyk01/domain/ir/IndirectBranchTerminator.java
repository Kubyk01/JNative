package io.github.kubyk01.domain.ir;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class IndirectBranchTerminator extends Terminator {
    private Value targetBlock; // value of type BLOCK
    private List<BasicBlock> possibleTargets = new ArrayList<>();

    public IndirectBranchTerminator(Value targetBlock) {
        this.targetBlock = targetBlock;
    }

    @Override
    public List<BasicBlock> getTargets() {
        return possibleTargets;
    }

    @Override
    public String toString() {
        return "INDIRECT_BRANCH " + targetBlock;
    }
}

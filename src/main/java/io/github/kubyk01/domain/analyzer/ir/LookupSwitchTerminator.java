package io.github.kubyk01.domain.analyzer.ir;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LookupSwitchTerminator extends Terminator {
    @Setter
    @Getter
    private Value key;
    @Getter
    private final int[] keys;
    private final BasicBlock[] targets;
    @Getter
    private final BasicBlock defaultTarget;

    public LookupSwitchTerminator(Value key, int[] keys, BasicBlock[] targets, BasicBlock defaultTarget) {
        this.key = key;
        this.keys = keys;
        this.targets = targets;
        this.defaultTarget = defaultTarget;
    }

    public BasicBlock[] getTargetsArray() { return targets; }

    @Override
    public List<BasicBlock> getTargets() {
        List<BasicBlock> list = new ArrayList<>(Arrays.asList(targets));
        list.add(defaultTarget);
        return list;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("LOOKUP_SWITCH ").append(key).append(" { ");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(keys[i]).append(" -> ").append(targets[i].getLabel());
        }
        sb.append(" } default -> ").append(defaultTarget.getLabel());
        return sb.toString();
    }
}

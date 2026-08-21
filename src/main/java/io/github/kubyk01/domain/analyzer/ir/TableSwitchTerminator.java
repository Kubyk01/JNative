package io.github.kubyk01.domain.analyzer.ir;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TableSwitchTerminator extends Terminator {
    @Setter
    @Getter
    private Value key;
    @Getter
    private final int min;
    @Getter
    private final int max;
    private final BasicBlock[] targets;
    @Getter
    private final BasicBlock defaultTarget;

    public TableSwitchTerminator(Value key, int min, int max, BasicBlock[] targets, BasicBlock defaultTarget) {
        this.key = key;
        this.min = min;
        this.max = max;
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
        sb.append("TABLE_SWITCH ").append(key).append(" [").append(min).append("..").append(max).append("] { ");
        for (int i = 0; i < targets.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append((min + i)).append(" -> ").append(targets[i].getLabel());
        }
        sb.append(" } default -> ").append(defaultTarget.getLabel());
        return sb.toString();
    }
}

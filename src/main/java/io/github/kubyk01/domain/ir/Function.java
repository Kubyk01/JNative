package io.github.kubyk01.domain.ir;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class Function {
    private final String name;
    private final Type returnType;
    private final List<Parameter> parameters = new ArrayList<>();
    private final List<BasicBlock> blocks = new ArrayList<>();
    @Setter
    private BasicBlock entryBlock;
    @Setter
    private Module module;
    @Setter
    private List<TryCatchRange> tryCatchRanges = new ArrayList<>();

    public Function(String name, Type returnType) {
        this.name = name;
        this.returnType = returnType;
    }

    public void addParameter(Parameter p) {
        parameters.add(p);
    }

    public void addBlock(BasicBlock block) {
        blocks.add(block);
        block.setFunction(this);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("function ").append(name).append("(");
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(parameters.get(i));
        }
        sb.append(") -> ").append(returnType).append(" {\n");
        for (BasicBlock bb : blocks) {
            sb.append(bb);
        }
        sb.append("}\n");
        return sb.toString();
    }
}

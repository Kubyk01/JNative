package io.github.kubyk01.domain.analyzer.ir;

import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Module {
    @Getter
    private final List<Function> functions = new ArrayList<>();
    private final Map<String, Function> functionMap = new HashMap<>();

    public void addFunction(Function func) {
        if (!functionMap.containsKey(func.getName())) {
            functions.add(func);
            functionMap.put(func.getName(), func);
            func.setModule(this);
        } else {
            // Ignore duplicate function entries by name.
        }
    }

    public Function getFunction(String name) {
        return functionMap.get(name);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("module {\n");
        for (Function f : functions) {
            sb.append(f);
        }
        sb.append("}\n");
        return sb.toString();
    }
}

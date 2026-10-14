package io.github.kubyk01.domain.ir;

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
        }
    }

    public void removeFunction(Function func) {
        if (func == null) return;
        functions.remove(func);
        functionMap.remove(func.getName());
    }

    public Function getFunction(String name) {
        return functionMap.get(name);
    }

    /**
     * Registers {@code aliasName} as an additional lookup key for
     * {@code target}. The target itself must already have been added via
     * {@link #addFunction}. Used by the override machinery to make the
     * mangled name of a Java method resolve to a C override function whose
     * own mangled name is different.
     */
    public void registerAlias(String aliasName, Function target) {
        if (aliasName == null || target == null) return;
        functionMap.put(aliasName, target);
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
package io.github.kubyk01.domain.analyzer.reflection;

import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldReference;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Getter
public class ReflectInfo {
    private final Map<String, ReflectClassInfo> classInfoMap = new HashMap<>();

    public ReflectClassInfo getOrCreateClassInfo(String className) {
        return classInfoMap.computeIfAbsent(className, ReflectClassInfo::new);
    }

    public void addMethod(String className, MethodReference method) {
        getOrCreateClassInfo(className).addMethod(method);
    }

    public void addField(String className, FieldReference field) {
        getOrCreateClassInfo(className).addField(field);
    }

    public void addConstructor(String className, MethodReference constructor) {
        getOrCreateClassInfo(className).addConstructor(constructor);
    }

    public Set<String> getAllClasses() {
        return classInfoMap.keySet();
    }
}

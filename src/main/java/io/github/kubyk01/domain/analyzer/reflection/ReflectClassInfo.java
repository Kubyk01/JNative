package io.github.kubyk01.domain.analyzer.reflection;

import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldReference;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class ReflectClassInfo {
    private final String className;
    @Setter
    private volatile String superName;
    private final Set<String> interfaces = ConcurrentHashMap.newKeySet();
    private final Set<MethodReference> methods = ConcurrentHashMap.newKeySet();
    private final Set<FieldReference> fields = ConcurrentHashMap.newKeySet();
    private final Set<MethodReference> constructors = ConcurrentHashMap.newKeySet();

    public ReflectClassInfo(String className) {
        this.className = className;
    }

    public void addMethod(MethodReference method) {
        methods.add(method);
    }

    public void addField(FieldReference field) {
        fields.add(field);
    }

    public void addConstructor(MethodReference constructor) {
        constructors.add(constructor);
    }
}

package io.github.kubyk01.domain.analyzer.reflection;

import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldReference;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Getter
public class ReflectClassInfo {
    private final String className;
    @Setter
    private String superName;
    private final Set<String> interfaces = new HashSet<>();
    private final Set<MethodReference> methods = new HashSet<>();
    private final Set<FieldReference> fields = new HashSet<>();
    private final Set<MethodReference> constructors = new HashSet<>();

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

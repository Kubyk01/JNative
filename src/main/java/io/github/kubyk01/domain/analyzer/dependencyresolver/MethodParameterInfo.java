package io.github.kubyk01.domain.analyzer.dependencyresolver;

/** Parameter metadata read from a class file's MethodParameters attribute. */
//Todo move to domain
public record MethodParameterInfo(String name, int access) {
}

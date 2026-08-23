package io.github.kubyk01.domain.analyzer.dependencyresolver;

import lombok.Value;

@Value
public class FieldReference {
    String owner;   // internal class name
    String name;
    String descriptor;

    @Override
    public String toString() {
        return owner + "." + name + ":" + descriptor;
    }
}

package io.github.kubyk01.domain.analyzer.dependencyresolver;

import io.github.kubyk01.domain.analyzer.ir.Type;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FieldNode {
    private String name;
    private String descriptor;
    private Type type;          // resolved type
    private int access;
}

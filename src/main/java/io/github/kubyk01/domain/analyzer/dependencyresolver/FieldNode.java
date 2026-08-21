package io.github.kubyk01.domain.analyzer.dependencyresolver;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FieldNode {
    private String name;
    private String descriptor;
    private int access;
}

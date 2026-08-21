package io.github.kubyk01.domain.analyzer.dependencyresolver;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.List;

@Data
@Builder
public class MethodNode {
    private String name;
    private String descriptor;
    private int access;
    @Singular
    private List<String> exceptions;     // internal names
    private boolean isAbstract;
    private boolean isNative;
    private boolean isStatic;
}

package io.github.kubyk01.domain.analyzer.dependencyresolver;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.List;

@Data
@Builder
public class ClassNode {
    private String name;                 // internal name (e.g. "java/lang/String")
    private String superName;            // internal name
    @Singular
    private List<String> interfaces;
    private int access;
    @Singular
    private List<FieldNode> fields;
    @Singular
    private List<MethodNode> methods;
    private boolean isInterface;
    private boolean isExternal;          // true if we don't have bytecode (JDK classes)
}
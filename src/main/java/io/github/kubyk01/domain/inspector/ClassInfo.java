package io.github.kubyk01.domain.inspector;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ClassInfo {
    private String className;
    private String superName;
    private List<FieldInfo> fields;
    private List<MethodInfo> methods;
}

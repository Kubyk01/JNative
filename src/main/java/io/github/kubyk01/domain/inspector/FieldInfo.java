package io.github.kubyk01.domain.inspector;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FieldInfo {
    private String name;
    private String descriptor;
}
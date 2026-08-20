package io.github.kubyk01.domain.inspector;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.nio.file.Path;
import java.util.List;

@Data
@Builder
public class InspectionResult {

    private Path filePath;

    private String fileType;

    @Builder.Default
    private boolean success = true;

    private String errorMessage;

    @Singular
    private List<ClassInfo> classes;

    public int getClassCount() {
        return classes != null ? classes.size() : 0;
    }
}
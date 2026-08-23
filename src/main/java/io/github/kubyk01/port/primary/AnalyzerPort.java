package io.github.kubyk01.port.primary;

import java.nio.file.Path;

public interface AnalyzerPort {
    void analyze(Path path, String entryClass, String entryMethod, String entryDescriptor,
                 boolean showClasses, boolean showAlias, boolean showEscape,
                 boolean showLifetime, boolean showDestructor,
                 String outputFile, boolean noCompile,
                 boolean includeSystem, String debugName);
}

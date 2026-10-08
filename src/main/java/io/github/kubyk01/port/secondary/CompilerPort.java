package io.github.kubyk01.port.secondary;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.ir.Module;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

public interface CompilerPort {

    void compileAndLink(Path llPath,
                        Path exePath,
                        Set<String> usedSystemClasses,
                        Module module,
                        DependencyResolver resolver,
                        int cores,
                        int optimizationLevel)
        throws IOException, InterruptedException;

    boolean hasNativeSupport(String className);
}
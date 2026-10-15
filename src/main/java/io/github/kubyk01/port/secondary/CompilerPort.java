package io.github.kubyk01.port.secondary;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.TargetPlatform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public interface CompilerPort {

    void compileAndLink(List<Path> llPaths,
                        Path exePath,
                        Set<String> usedSystemClasses,
                        Module module,
                        DependencyResolver resolver,
                        int cores,
                        int optimizationLevel,
                        TargetPlatform targetPlatform)
        throws IOException, InterruptedException;

    boolean hasNativeSupport(String className);

    /**
     * Discovers the platform native shared objects that provide the JNI
     * symbols the module needs, by intersecting each library's exported
     * symbol set with the set of JNI natives in {@code module}. Returns
     * the libraries to append to the link command, in a deterministic
     * order.
     */
    List<Path> collectNativeLibraries(Module module,
                                      DependencyResolver resolver,
                                      TargetPlatform targetPlatform)
        throws IOException;
}

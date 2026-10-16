package io.github.kubyk01.port.secondary;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.TargetPlatform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
     *
     * <p>This method feeds the {@code DT_NEEDED} entries of the final
     * executable: every returned path is passed to the linker as an
     * input file. It is therefore only useful for libraries whose
     * exported symbols match the traditional
     * {@code Java_<class>_<method>} naming scheme. Libraries that
     * install their natives through {@code JNI_OnLoad ->
     * RegisterNatives} — Netty's transports among them — export no
     * {@code Java_*} symbols at all and are handled by
     * {@link #embedNativeLibraries} instead.</p>
     */
    List<Path> collectNativeLibraries(Module module,
                                      DependencyResolver resolver,
                                      TargetPlatform targetPlatform)
            throws IOException;

    /**
     * Discovers every native library the input archive contributes,
     * reads its bytes, and returns them together with the resource keys
     * under which they must be registered.
     *
     * <p>The returned {@link EmbeddedNativeLibraries} record carries two
     * parallel views of the same set of libraries:</p>
     *
     * <ul>
     *   <li>{@code keys} — the resource keys in the order they must be
     *       loaded by {@code __jnative_jni_load_all} at program
     *       start-up. The code generator emits one string literal per
     *       key and passes the whole array to the runtime, which loads
     *       each library in turn and calls its {@code JNI_OnLoad}.</li>
     *
     *   <li>{@code resources} — the same keys paired with the bytes of
     *       the library they name. This is the input of
     *       {@code LlvmGenerator.setEmbeddedResources}, which emits
     *       them into the {@code @jnative_builtin_resources} table so
     *       the runtime can materialise each library to a temporary
     *       file at load time.</li>
     * </ul>
     *
     * <p>The two lists are keyed identically; the code generator relies
     * on this correspondence to look up each key at load time. Keys are
     * stable across runs of the same build and are written in a form
     * that cannot collide with a resource key produced by
     * {@code Class.getResourceAsStream}.</p>
     *
     * <p>Returning a record rather than populating a side channel on the
     * compiler keeps the method free of hidden state: two calls with the
     * same arguments return the same data, and the caller owns the
     * returned collections outright.</p>
     */
    EmbeddedNativeLibraries embedNativeLibraries(Module module,
                                                 DependencyResolver resolver,
                                                 TargetPlatform targetPlatform)
            throws IOException;

    /**
     * The result of {@link #embedNativeLibraries}: a set of native
     * libraries that must be loaded at program start-up, presented both
     * as an ordered list of resource keys and as the (key, bytes) pairs
     * that the code generator needs to embed.
     *
     * <p>Both lists have the same length and the same order; the i-th
     * element of {@code keys} is the key of the i-th entry of
     * {@code resources}.</p>
     */
    record EmbeddedNativeLibraries(
            List<String> keys,
            List<Map.Entry<String, byte[]>> resources
    ) {
        /** An empty result. Used when the input archive contributes no
         *  native library. */
        public static EmbeddedNativeLibraries empty() {
            return new EmbeddedNativeLibraries(List.of(), List.of());
        }
    }
}
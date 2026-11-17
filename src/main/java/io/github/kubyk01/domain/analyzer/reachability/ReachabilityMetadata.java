package io.github.kubyk01.domain.analyzer.reachability;

import lombok.Data;
import lombok.Builder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@Builder
public class ReachabilityMetadata {
    @Builder.Default
    private List<ReflectClass> reflectClasses = new ArrayList<>();
    @Builder.Default
    private List<ProxyInterface> proxyInterfaces = new ArrayList<>();
    @Builder.Default
    private List<Resource> resources = new ArrayList<>();
    @Builder.Default
    private List<JniClass> jniClasses = new ArrayList<>();

    public void merge(ReachabilityMetadata other) {
        if (other == null) return;
        this.reflectClasses.addAll(other.reflectClasses);
        this.proxyInterfaces.addAll(other.proxyInterfaces);
        this.resources.addAll(other.resources);
        this.jniClasses.addAll(other.jniClasses);
    }

    @Data
    @Builder
    public static class ReflectClass {
        private String name;
        @Builder.Default
        private Set<String> methods = new HashSet<>();
        @Builder.Default
        private Set<String> fields = new HashSet<>();
        @Builder.Default
        private Set<String> constructors = new HashSet<>();
        private boolean allDeclaredMethods;
        private boolean allDeclaredFields;
        private boolean allDeclaredConstructors;
        private boolean allPublicMethods;
        private boolean allPublicFields;
        private boolean allPublicConstructors;

        /**
         * The value of the {@code condition.typeReachable} expression from
         * the source {@code reflect-config.json}, or {@code null} when the
         * entry has no condition.
         *
         * <p>Semantics (as in GraalVM native-image): the entry is applied
         * only if the named trigger type is reachable in the image. When
         * {@code null} or empty the entry is unconditional and always
         * applied.</p>
         *
         * <p>The name form is a binary name with dots
         * ({@code java.lang.String}), not an internal name with slashes.
         * Conversion to internal form is performed in
         * {@link io.github.kubyk01.application.service.analyzer.reachabilityanalysis.ReachabilityAnalysis#isTriggerReachable(String)}.</p>
         */
        private String typeReachable;
    }

    @Data
    @Builder
    public static class ProxyInterface {
        @Builder.Default
        private Set<String> interfaces = new HashSet<>();
    }

    @Data
    @Builder
    public static class Resource {
        private String pattern;
        private String includes;
        private String excludes;
    }

    @Data
    @Builder
    public static class JniClass {
        private String name;
        @Builder.Default
        private Set<String> methods = new HashSet<>();
        @Builder.Default
        private Set<String> fields = new HashSet<>();
    }
}

package io.github.kubyk01.port.primary;

import io.github.kubyk01.domain.inspector.InspectionResult;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

public interface InspectorPort {

    Mono<InspectionResult> inspectJar(Path path);

    Mono<InspectionResult> inspectClass(Path path, boolean showBytecode);
}

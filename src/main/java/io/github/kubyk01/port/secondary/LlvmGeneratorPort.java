package io.github.kubyk01.port.secondary;

import io.github.kubyk01.domain.analyzer.ClinitScheduleEntry;
import io.github.kubyk01.domain.ir.TargetPlatform;
import reactor.core.scheduler.Scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface LlvmGeneratorPort {

    void setScheduler(Scheduler scheduler);

    void setClinitSchedule(List<ClinitScheduleEntry> schedule);

    void setEmbeddedResources(List<Map.Entry<String, byte[]>> resources);

    void setClinitWrappers(Map<String, String> wrappers);

    void setTargetPlatform(TargetPlatform platform);

    void generate(Path outputDir) throws IOException;
}
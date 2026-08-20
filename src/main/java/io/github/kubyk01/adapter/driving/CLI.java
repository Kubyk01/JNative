package io.github.kubyk01.adapter.driving;

import io.github.kubyk01.domain.inspector.ClassInfo;
import io.github.kubyk01.domain.inspector.FieldInfo;
import io.github.kubyk01.domain.inspector.InspectionResult;
import io.github.kubyk01.domain.inspector.MethodInfo;
import io.github.kubyk01.port.primary.InspectorPort;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import reactor.core.publisher.Mono;

import java.io.File;
import java.nio.file.Path;

@RequiredArgsConstructor
@Command(name = "jnative", mixinStandardHelpOptions = true, version = "0.1")
public class CLI implements Runnable {

    private final InspectorPort inspector;

    @Override
    public void run() {
        System.err.println("Please specify a subcommand: inspect");
        new CommandLine(this).usage(System.err);
    }

    @Command(name = "inspect", description = "Inspect a JAR or CLASS file")
    public void inspect(
        @CommandLine.Parameters(index = "0", description = "Path to JAR or CLASS file") File file,
        @CommandLine.Option(names = "--bytecode", description = "Show bytecode instructions") boolean showBytecode
    ) {
        Path path = file.toPath();
        String fileName = path.getFileName().toString().toLowerCase();
        Mono<InspectionResult> resultMono;

        if (fileName.endsWith(".jar")) {
            resultMono = inspector.inspectJar(path);
        } else if (fileName.endsWith(".class")) {
            resultMono = inspector.inspectClass(path, showBytecode);
        } else {
            System.err.println("Unsupported file extension. Use .jar or .class");
            return;
        }

        InspectionResult result = resultMono.block();
        if (result == null) {
            System.err.println("No result received.");
            return;
        }

        printResult(result, showBytecode);
    }

    private void printResult(InspectionResult result, boolean showBytecode) {
        if (!result.isSuccess()) {
            System.err.println("Inspection failed: " + result.getErrorMessage());
            return;
        }

        System.out.println("File: " + result.getFilePath());
        System.out.println("Type: " + result.getFileType());
        System.out.println("Classes found: " + result.getClassCount());
        System.out.println();

        for (ClassInfo classInfo : result.getClasses()) {
            System.out.println("Class: " + classInfo.getClassName());
            System.out.println("  Super: " + classInfo.getSuperName());
            System.out.println("  Fields:");
            for (FieldInfo f : classInfo.getFields()) {
                System.out.println("    " + f.getDescriptor() + " " + f.getName());
            }
            System.out.println("  Methods:");
            for (MethodInfo m : classInfo.getMethods()) {
                System.out.println("    " + m.getDescriptor() + " " + m.getName());
                if (showBytecode && m.getBytecode() != null && !m.getBytecode().isEmpty()) {
                    System.out.println("      Bytecode:");
                    String[] lines = m.getBytecode().split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        System.out.println("        " + i + ": " + lines[i]);
                    }
                }
            }
            System.out.println();
        }
    }
}
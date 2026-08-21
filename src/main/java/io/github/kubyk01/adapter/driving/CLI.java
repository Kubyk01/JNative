package io.github.kubyk01.adapter.driving;

import io.github.kubyk01.domain.inspector.ClassInfo;
import io.github.kubyk01.domain.inspector.FieldInfo;
import io.github.kubyk01.domain.inspector.InspectionResult;
import io.github.kubyk01.domain.inspector.MethodInfo;
import io.github.kubyk01.port.primary.AnalyzerPort;
import io.github.kubyk01.port.primary.InspectorPort;
import lombok.AllArgsConstructor;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import reactor.core.publisher.Mono;

import java.io.File;
import java.nio.file.Path;

@AllArgsConstructor
@Command(name = "jnative", mixinStandardHelpOptions = true, version = "0.1")
public class CLI implements Runnable {

    private final InspectorPort inspector;
    private final AnalyzerPort analyzer;

    @Override
    public void run() {
        System.err.println("Please specify a subcommand: inspect or analyze");
        new CommandLine(this).usage(System.err);
    }

    @Command(name = "inspect", description = "Inspect a JAR or CLASS file")
    public void inspect(
        @CommandLine.Parameters(index = "0", description = "Path to JAR or CLASS file") File file,
        @CommandLine.Option(names = "--bytecode", description = "Show bytecode instructions") boolean showBytecode) {
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

    @Command(name = "analyze", description = "Perform dependency resolution and reachability analysis")
    public void analyze(
        @CommandLine.Parameters(index = "0", description = "Path to JAR or directory containing .class files") File file,
        @CommandLine.Option(names = "--entry", description = "Entry point class (fully qualified)", defaultValue = "com.example.Main") String entryClass,
        @CommandLine.Option(names = "--method", description = "Entry method name", defaultValue = "main") String entryMethod,
        @CommandLine.Option(names = "--descriptor", description = "Method descriptor", defaultValue = "([Ljava/lang/String;)V") String descriptor,
        @CommandLine.Option(names = "--classes", description = "Show user classes and methods") boolean showClasses,
        @CommandLine.Option(names = "--alias", description = "Show alias analysis") boolean showAlias,
        @CommandLine.Option(names = "--escape", description = "Show escape analysis") boolean showEscape,
        @CommandLine.Option(names = "--lifetime", description = "Show lifetime analysis") boolean showLifetime,
        @CommandLine.Option(names = "--destructor", description = "Show destructor insertion") boolean showDestructor,
        @CommandLine.Option(names = "--all", description = "Show all analysis stages (default if none specified)") boolean showAll) {

        // If no flag is given, show everything (including classes)
        boolean anyFlag = showClasses || showAlias || showEscape || showLifetime || showDestructor || showAll;
        if (!anyFlag) {
            showClasses = showAlias = showEscape = showLifetime = showDestructor = true;
        } else if (showAll) {
            showClasses = showAlias = showEscape = showLifetime = showDestructor = true;
        }

        Path path = file.toPath();
        analyzer.analyze(path, entryClass, entryMethod, descriptor,
                         showClasses, showAlias, showEscape, showLifetime, showDestructor);
    }
}
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
import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

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
        @CommandLine.Option(names = "--entry", description = "Entry point class (fully qualified)") String entryClass,
        @CommandLine.Option(names = "--method", description = "Entry method name", defaultValue = "main") String entryMethod,
        @CommandLine.Option(names = "--descriptor", description = "Method descriptor", defaultValue = "([Ljava/lang/String;)V") String descriptor,
        @CommandLine.Option(names = "--classes", description = "Show user classes and methods") boolean showClasses,
        @CommandLine.Option(names = "--alias", description = "Show alias analysis") boolean showAlias,
        @CommandLine.Option(names = "--escape", description = "Show escape analysis") boolean showEscape,
        @CommandLine.Option(names = "--lifetime", description = "Show lifetime analysis") boolean showLifetime,
        @CommandLine.Option(names = "--destructor", description = "Show destructor insertion") boolean showDestructor,
        @CommandLine.Option(names = "--all", description = "Show all analysis stages (default if none specified)") boolean showAll,
        @CommandLine.Option(names = "--output", description = "Output executable file name (default: a.out)") String outputFile,
        @CommandLine.Option(names = "--no-compile", description = "Do not compile to native executable") boolean noCompile,
        @CommandLine.Option(names = "--include-system", description = "Include system/library classes in output") boolean includeSystem,
        @CommandLine.Option(names = "--debug-name", description = "Debug only this class or method (shows only matching items)") String debugName,
        @CommandLine.Option(names = "--classes-graph", description = "Show call graph from entry point") boolean showClassesGraph) {

        // If entryClass is not provided, try to read from JAR manifest
        if (entryClass == null && file.isFile() && file.getName().toLowerCase().endsWith(".jar")) {
            try (JarFile jar = new JarFile(file)) {
                Manifest manifest = jar.getManifest();
                if (manifest != null) {
                    Attributes mainAttrs = manifest.getMainAttributes();
                    String mainClass = mainAttrs.getValue("Main-Class");
                    if (mainClass != null && !mainClass.isEmpty()) {
                        entryClass = mainClass.replace('.', '/'); // convert to internal format
                    }
                }
            } catch (IOException e) {
                System.err.println("Failed to read manifest from JAR: " + e.getMessage());
            }
        }

        if (entryClass == null) {
            System.err.println("Error: entry class not specified and could not be determined from manifest.");
            return;
        }

        // If no flag is given, show everything (including classes)
        boolean anyFlag = showClasses || showAlias || showEscape || showLifetime || showDestructor || showClassesGraph || showAll;
        if (!anyFlag) {
            showClasses = showAlias = showEscape = showLifetime = showDestructor = true;
        } else if (showAll) {
            showClasses = showAlias = showEscape = showLifetime = showDestructor = true;
        }

        Path path = file.toPath();
        analyzer.analyze(path, entryClass, entryMethod, descriptor,
            showClasses, showAlias, showEscape, showLifetime, showDestructor,
            outputFile, noCompile, includeSystem, debugName, showClassesGraph);
    }
}
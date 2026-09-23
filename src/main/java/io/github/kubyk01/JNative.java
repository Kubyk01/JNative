package io.github.kubyk01;

import io.github.kubyk01.adapter.driving.CLI;
import io.github.kubyk01.application.service.Orchestrator;
import io.github.kubyk01.application.service.compiler.Compiler;
import io.github.kubyk01.application.service.inspector.Inspector;
import io.github.kubyk01.port.primary.InspectorPort;
import io.github.kubyk01.port.primary.OrchestratorPort;
import io.github.kubyk01.port.secondary.CompilerPort;
import picocli.CommandLine;

public class JNative {

    public static void main(String[] args) {
        InspectorPort inspector = new Inspector();
        CompilerPort compiler = new Compiler();
        OrchestratorPort orchestrator = new Orchestrator(compiler);
        CLI cli = new CLI(inspector, orchestrator);

        int exitCode = new CommandLine(cli).execute(args);
        System.exit(exitCode);
    }
}
package io.github.kubyk01;

import io.github.kubyk01.adapter.driving.CLI;
import io.github.kubyk01.application.service.analyzer.Analyzer;
import io.github.kubyk01.application.service.inspector.Inspector;
import io.github.kubyk01.port.primary.AnalyzerPort;
import io.github.kubyk01.port.primary.InspectorPort;
import picocli.CommandLine;

public class JNative {

    public static void main(String[] args) {
        InspectorPort inspector = new Inspector();
        AnalyzerPort analyzer = new Analyzer();
        CLI cli = new CLI(inspector, analyzer);

        int exitCode = new CommandLine(cli).execute(args);
        System.exit(exitCode);
    }
}

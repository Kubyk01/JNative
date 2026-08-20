package io.github.kubyk01;

import io.github.kubyk01.adapter.driving.CLI;
import io.github.kubyk01.application.service.Inspector.Inspector;
import io.github.kubyk01.port.primary.InspectorPort;
import picocli.CommandLine;

public class JNative
{

    public static void main(String[] args) {
        InspectorPort inspector = new Inspector();
        CLI cli = new CLI(inspector);

        int exitCode = new CommandLine(cli).execute(args);
        System.exit(exitCode);
    }
}

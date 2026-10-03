package com.aiproxy.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "doctor", description = "Validate configuration, credentials, URLs, and model discovery.", mixinStandardHelpOptions = true)
final class DoctorCommand implements Callable<Integer> {
    @CommandLine.Spec CommandLine.Model.CommandSpec spec;
    @Option(names = "--config") Path config;
    @Option(names = "--inference", description = "Also require usable credentials for provider inference checks.") boolean inference;
    public Integer call() throws Exception {
        ProxyCommand root = (ProxyCommand) spec.root().userObject();
        ServeOptions options = new ServeOptions();
        options.config = config == null ? null : config.toString();
        options.startupCheck = inference ? "inference" : "credentials";
        try {
            return root.runProxy(options, true);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            spec.commandLine().getErr().println("Diagnostics failed: " + StartupRenderer.safe(error.getMessage()));
            return 1;
        }
    }
}

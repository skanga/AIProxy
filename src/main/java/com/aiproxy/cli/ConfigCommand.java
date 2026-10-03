package com.aiproxy.cli;

import com.aiproxy.config.ConfigException;
import com.aiproxy.config.ConfigOverrides;
import com.aiproxy.config.EffectiveConfig;
import com.aiproxy.config.EffectiveConfigLoader;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine;

@Command(name = "config", description = "Inspect configuration.", subcommands = ConfigCommand.Show.class)
final class ConfigCommand implements Runnable {
    public void run() {}

    @Command(name = "show", description = "Print resolved, redacted configuration and value sources.", mixinStandardHelpOptions = true)
    static final class Show implements Callable<Integer> {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Option(names = "--config") Path config;
        public Integer call() {
            ProxyCommand root = (ProxyCommand) spec.root().userObject();
            try {
                EffectiveConfig effective = EffectiveConfigLoader.load(config, root.environment(), new ConfigOverrides());
                ConfigRenderer.printResolvedConfig(spec.commandLine().getOut(), effective);
                return 0;
            } catch (ConfigException error) {
                spec.commandLine().getErr().println("Configuration error: " + error.getMessage());
                return 2;
            }
        }
    }
}

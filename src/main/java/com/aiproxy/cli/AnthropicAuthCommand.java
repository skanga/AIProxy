package com.aiproxy.cli;

import com.aiproxy.config.ConfigException;
import com.aiproxy.config.ConfigOverrides;
import com.aiproxy.config.EffectiveConfigLoader;
import com.aiproxy.provider.anthropic.auth.AnthropicAuthCommands;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine;

@Command(name = "anthropic", description = "Manage Anthropic OAuth credentials.",
        subcommands = {AnthropicAuthCommand.Login.class, AnthropicAuthCommand.Logout.class})
final class AnthropicAuthCommand implements Runnable {
    public void run() {}

    static final class Options {
        @Option(names = "--config", paramLabel = "<yaml>") Path config;
        @Option(names = "--anthropic-oauth-file", paramLabel = "<path>") String oauthFile;

        Path credentialPath(CommandLine.Model.CommandSpec spec) {
            ProxyCommand root = (ProxyCommand) spec.root().userObject();
            ConfigOverrides overrides = new ConfigOverrides();
            overrides.anthropicOauthFile = oauthFile;
            try {
                return EffectiveConfigLoader.load(config, root.environment(), overrides).anthropic().oauthFile();
            } catch (ConfigException error) {
                throw new CommandLine.ParameterException(spec.commandLine(), "Configuration error: " + error.getMessage());
            }
        }
    }

    @Command(name = "login", description = "Run interactive Anthropic OAuth login.", mixinStandardHelpOptions = true)
    static final class Login implements Callable<Integer> {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Mixin Options options = new Options();
        @Option(names = "--allow-stdin-oauth-code") boolean allowStdin;
        @Option(names = "--no-browser", description = "Open the printed URL on another device, then paste code#state here.") boolean noBrowser;
        public Integer call() {
            Path path = options.credentialPath(spec);
            var auth = AnthropicAuthCommands.system(path, spec.commandLine().getOut(), spec.commandLine().getErr());
            return noBrowser ? auth.login(allowStdin, true) : auth.login(allowStdin);
        }
    }

    @Command(name = "logout", description = "Delete the resolved Anthropic OAuth credential.", mixinStandardHelpOptions = true)
    static final class Logout implements Callable<Integer> {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Mixin Options options = new Options();
        @Option(names = "--yes") boolean yes;
        public Integer call() {
            Path path = options.credentialPath(spec);
            return AnthropicAuthCommands.system(path, spec.commandLine().getOut(), spec.commandLine().getErr()).logout(yes);
        }
    }
}

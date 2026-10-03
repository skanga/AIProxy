package com.aiproxy.cli;

import com.aiproxy.config.EffectiveConfigLoader;
import com.aiproxy.provider.copilot.auth.CopilotCredentials;
import com.aiproxy.provider.copilot.auth.CopilotOAuth;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine;

@Command(name = "copilot", description = "Manage Copilot credentials.",
        subcommands = {CopilotAuthCommand.Login.class, CopilotAuthCommand.Logout.class})
final class CopilotAuthCommand implements Runnable {
    public void run() {}

    @Command(name = "login", description = "Authorize Copilot using GitHub device login.", mixinStandardHelpOptions = true)
    static final class Login implements Callable<Integer> {
        @Mixin ServeOptions options = new ServeOptions();
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        public Integer call() {
            try {
                ProxyCommand root = (ProxyCommand) spec.root().userObject();
                var config = EffectiveConfigLoader.load(options.configPath(), root.environment(), options.toOverrides());
                CopilotOAuth.login(config.copilot(), spec.commandLine().getOut());
                return 0;
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                spec.commandLine().getErr().println("Copilot login failed: " + StartupRenderer.safe(error.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "logout", description = "Delete only the proxy-managed Copilot login.", mixinStandardHelpOptions = true)
    static final class Logout implements Callable<Integer> {
        @Mixin ServeOptions options = new ServeOptions();
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        public Integer call() {
            try {
                ProxyCommand root = (ProxyCommand) spec.root().userObject();
                var config = EffectiveConfigLoader.load(options.configPath(), root.environment(), options.toOverrides());
                new CopilotCredentials(config.copilot()).logout();
                spec.commandLine().getOut().println("Proxy-managed Copilot login removed; external credentials are unchanged.");
                return 0;
            } catch (Exception error) {
                spec.commandLine().getErr().println("Copilot logout failed: " + StartupRenderer.safe(error.getMessage()));
                return 1;
            }
        }
    }
}

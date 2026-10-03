package com.aiproxy.cli;

import com.aiproxy.config.ConfigOverrides;
import com.aiproxy.config.EffectiveConfig;
import com.aiproxy.config.EffectiveConfigLoader;
import com.aiproxy.provider.codex.auth.nativeoauth.CodexAuthSelection;
import com.aiproxy.provider.codex.auth.nativeoauth.NativeCredentialStore;
import com.aiproxy.provider.copilot.auth.CopilotCredentials;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine;

@Command(name = "auth", description = "Manage and inspect provider credentials.",
        subcommands = {CodexAuthCommand.class, AnthropicAuthCommand.class, CopilotAuthCommand.class, AuthCommand.Status.class})
final class AuthCommand implements Runnable {
    public void run() {}

    @Command(name = "status", description = "Show provider credential availability.", mixinStandardHelpOptions = true)
    static final class Status implements Callable<Integer> {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Option(names = "--config") Path config;
        public Integer call() {
            ProxyCommand root = (ProxyCommand) spec.root().userObject();
            EffectiveConfig effective = EffectiveConfigLoader.load(config, root.environment(), new ConfigOverrides());
            CodexAuthSelection selected = CodexAuthSelection.select(effective.codex());
            String codex = selected.available() ? selected.path().toString() : null;
            boolean anthropic = root.environment().containsKey("CLAUDE_CODE_OAUTH_TOKEN")
                    || Files.isRegularFile(effective.anthropic().oauthFile());
            spec.commandLine().getOut().println("Codex (" + (selected.nativeProfile() ? "native" : "cli") + "): "
                    + (codex == null ? "not found" : "available from " + codex) + "; use doctor to validate");
            if (selected.nativeProfile() && selected.available()) {
                try { spec.commandLine().getOut().println("  " + StartupRenderer.safe(new NativeCredentialStore(selected.path()).status())); }
                catch (Exception invalid) { spec.commandLine().getOut().println("  Invalid native credentials; use auth codex logout --yes then login to recover."); codex = null; }
            }
            spec.commandLine().getOut().println("Anthropic: " + (anthropic ? "available" : "not found"));
            boolean copilot = new CopilotCredentials(effective.copilot()).available();
            spec.commandLine().getOut().println("Copilot: " + (copilot ? "available" : "not found or invalid"));
            return codex != null || anthropic || copilot ? 0 : 1;
        }
    }
}

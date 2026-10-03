package com.aiproxy.cli;

import com.aiproxy.config.ConfigOverrides;
import com.aiproxy.config.EffectiveConfig;
import com.aiproxy.config.EffectiveConfigLoader;
import com.aiproxy.provider.codex.auth.CodexAuthFileResolver;
import com.aiproxy.provider.codex.auth.CodexDeviceLogin;
import com.aiproxy.provider.codex.auth.nativeoauth.NativeAuthCommands;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine;
import static com.aiproxy.bootstrap.ProxyRuntime.findExistingAuthFile;

@Command(name = "codex", description = "Manage native Sign in with ChatGPT credentials.",
        subcommands = {CodexAuthCommand.Login.class, CodexAuthCommand.Logout.class}, mixinStandardHelpOptions = true)
final class CodexAuthCommand implements Runnable {
    public void run() {}

    static final class Options {
        @Option(names = "--config") Path config;
        @Option(names = "--codex-native-auth-file") String nativeAuthFile;
        EffectiveConfig effective(CommandLine.Model.CommandSpec spec) {
            ProxyCommand root = (ProxyCommand) spec.root().userObject();
            ConfigOverrides overrides = new ConfigOverrides();
            overrides.codexNativeAuthFile = nativeAuthFile;
            return EffectiveConfigLoader.load(config, root.environment(), overrides);
        }
    }

    @Command(name = "login", description = "Sign in with ChatGPT using a browser or official CLI device login.", mixinStandardHelpOptions = true)
    static final class Login implements Callable<Integer> {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Mixin Options options = new Options();
        @Option(names = "--no-browser", description = "Print the login URL without opening a browser.") boolean noBrowser;
        @Option(names = "--new-account", description = "Register a different ChatGPT account; replace the active login only after success.") boolean newAccount;
        @Option(names = "--device-auth", description = "Use official Codex CLI device login; saves CLI-profile credentials.") boolean deviceAuth;
        @Option(names = "--codex-oauth-file", description = "CLI credential destination (must be named auth.json); device login only.") String cliFile;
        public Integer call() {
            if (deviceAuth && (noBrowser || newAccount || options.nativeAuthFile != null))
                throw new CommandLine.ParameterException(spec.commandLine(), "--device-auth cannot be combined with native login options.");
            if (!deviceAuth && cliFile != null)
                throw new CommandLine.ParameterException(spec.commandLine(), "--codex-oauth-file requires --device-auth.");
            if (deviceAuth) {
                ProxyCommand root = (ProxyCommand) spec.root().userObject();
                ConfigOverrides overrides = new ConfigOverrides();
                overrides.codexOauthFile = cliFile;
                // Device login explicitly targets the CLI profile, independently of serving mode.
                overrides.codexAuthMode = "cli";
                var selected = EffectiveConfigLoader.load(options.config, root.environment(), overrides);
                Path file = Path.of(CodexAuthFileResolver.resolveWritePath(selected.codex().oauthFile() == null
                        ? null : selected.codex().oauthFile().toString()));
                return CodexDeviceLogin.system().login(file, spec.commandLine().getOut(), spec.commandLine().getErr());
            }
            EffectiveConfig effective = options.effective(spec);
            try (NativeAuthCommands auth = NativeAuthCommands.system(effective.codex().nativeAuthFile(), spec.commandLine().getOut(), spec.commandLine().getErr())) {
                int result = auth.login(noBrowser, newAccount);
                if (result == 0 && (effective.codex().authMode() == EffectiveConfig.CodexAuthMode.CLI || effective.codex().oauthFile() != null))
                    spec.commandLine().getOut().println("Current settings still select CLI credentials; use native mode without codex.oauth_file to select this login.");
                return result;
            }
        }
    }

    @Command(name = "logout", description = "Revoke and clear native tokens; preserve external CLI credentials.", mixinStandardHelpOptions = true)
    static final class Logout implements Callable<Integer> {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Mixin Options options = new Options();
        @Option(names = "--yes") boolean yes;
        public Integer call() {
            EffectiveConfig effective = options.effective(spec);
            try (NativeAuthCommands auth = NativeAuthCommands.system(effective.codex().nativeAuthFile(), spec.commandLine().getOut(), spec.commandLine().getErr())) {
                int result = auth.logout(yes);
                if (result == 0) {
                    String cli = findExistingAuthFile(effective.codex().oauthFile() == null ? null : effective.codex().oauthFile().toString());
                    spec.commandLine().getOut().println(cli == null ? "No CLI credential file found."
                            : "CLI credentials remain available from " + cli + "; auto/cli mode may select them on restart.");
                }
                return result;
            }
        }
    }
}

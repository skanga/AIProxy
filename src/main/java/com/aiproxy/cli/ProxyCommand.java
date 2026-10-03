package com.aiproxy.cli;

import com.aiproxy.bootstrap.ProxyRuntime;
import com.aiproxy.config.ConfigException;
import com.aiproxy.config.EffectiveConfig;
import com.aiproxy.config.EffectiveConfigLoader;
import com.aiproxy.config.ServerConfig;
import com.aiproxy.util.ApiKeyUtils;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(
        name = "aiproxy",
        description = "AI proxy exposing multiple model providers via local APIs.",
        mixinStandardHelpOptions = true,
        version = "AIProxy 5.1",
        subcommands = {
                ServeCommand.class,
                AuthCommand.class,
                KeyCommand.class,
                ConfigCommand.class,
                DoctorCommand.class
        }
)
public class ProxyCommand implements Callable<Integer> {

    @Mixin
    private ServeOptions rootOptions = new ServeOptions();

    private final Supplier<Map<String, String>> environment;
    private final ProxyRuntime runtime;
    private ServeOptions activeOptions;

    public ProxyCommand() {
        this(System::getenv);
    }

    ProxyCommand(Supplier<Map<String, String>> environment) {
        this.environment = environment;
        this.runtime = new ProxyRuntime(environment);
    }

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        return runServe(rootOptions);
    }

    Integer runServe(ServeOptions options) throws Exception {
        return runProxy(options, false);
    }

    Integer runProxy(ServeOptions options, boolean doctorMode) throws Exception {
        activeOptions = options;
        EffectiveConfig effective;
        try {
            effective = EffectiveConfigLoader.load(options.configPath(), environment.get(), options.toOverrides());
        } catch (ConfigException error) {
            spec.commandLine().getErr().println("Configuration error: " + error.getMessage());
            return 2;
        }
        return runtime().run(effective, doctorMode, spec.commandLine().getOut(), spec.commandLine().getErr());
    }

    ProxyRuntime runtime() { return runtime; }

    Map<String, String> environment() { return environment.get(); }

    Integer handleGenerateKey() {
        String key = ApiKeyUtils.generateNewKey();
        spec.commandLine().getOut().println(key);
        return 0;
    }

    ServerConfig buildServerConfig() throws Exception {
        ServeOptions options = activeOptions == null ? rootOptions : activeOptions;
        EffectiveConfig effective = EffectiveConfigLoader.load(options.configPath(), environment.get(), options.toOverrides());
        Map<String, String> keys = new HashMap<>(effective.clientAuth().environmentKeys());
        if (effective.clientAuth().keysFile() != null) {
            Files.readAllLines(effective.clientAuth().keysFile()).stream().map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(line -> ApiKeyUtils.parseKeyEntry(line, keys));
        }
        String admin = effective.clientAuth().environmentAdminKey();
        if (effective.clientAuth().adminKeyFile() != null) admin = Files.readString(effective.clientAuth().adminKeyFile()).strip();
        return effective.legacyServerConfig(keys, admin);
    }

    List<String> parseModelList() {
        try {
            List<String> modelList = EffectiveConfigLoader.load(null, environment.get(), rootOptions.toOverrides()).codex().models();
            return modelList.isEmpty() ? null : modelList;
        } catch (ConfigException error) {
            throw error;
        }
    }

    /** Returns only client keys supplied by the environment, not the key file. */
    Map<String, String> parseInlineKeys() {
        return new HashMap<>(EffectiveConfigLoader.load(null, environment.get(), rootOptions.toOverrides())
                .clientAuth().environmentKeys());
    }

    Map<String, String> parseApiKeyMap() throws Exception {
        EffectiveConfig effective = EffectiveConfigLoader.load(rootOptions.configPath(), environment.get(), rootOptions.toOverrides());
        Map<String, String> apiKeyMap = new HashMap<>(effective.clientAuth().environmentKeys());
        if (effective.clientAuth().keysFile() != null) Files.readAllLines(effective.clientAuth().keysFile()).stream()
                .map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .forEach(entry -> ApiKeyUtils.parseKeyEntry(entry, apiKeyMap));
        return apiKeyMap;
    }

    public static CommandLine commandLine() { return commandLine(new ProxyCommand()); }

    static CommandLine commandLine(ProxyCommand root) {
        CommandLine command = new CommandLine(root);
        command.setExecutionStrategy(parseResult -> {
            for (var parent = parseResult; parent.hasSubcommand(); parent = parent.subcommand()) {
                for (var option : parent.matchedOptions()) {
                    if (!option.usageHelp() && !option.versionHelp()) {
                        throw new CommandLine.ParameterException(parent.commandSpec().commandLine(),
                                "Place " + option.longestName() + " after the final subcommand; options before subcommands are not supported.");
                    }
                }
            }
            return new CommandLine.RunLast().execute(parseResult);
        });
        command.setParameterExceptionHandler((error, args) -> {
            String message = error.getMessage();
            Map<String, String> replacements = Map.ofEntries(
                    Map.entry("--models", "--codex-models"), Map.entry("--base-url", "--codex-base-url"),
                    Map.entry("--oauth-file", "--codex-oauth-file"), Map.entry("--oauth-client-id", "--codex-oauth-client-id"),
                    Map.entry("--oauth-token-url", "--codex-oauth-token-url"), Map.entry("--api-keys-file", "--client-keys-file"),
                    Map.entry("--providers", "--provider"), Map.entry("--store", "--codex-store"),
                    Map.entry("--forward-prompt-cache-headers", "--codex-forward-prompt-cache-headers"),
                    Map.entry("--codex-instructions", "--codex-instructions-mode"),
                    Map.entry("--generate-key", "key generate"), Map.entry("--anthropic-login", "auth anthropic login"),
                    Map.entry("--anthropic-logout", "auth anthropic logout"), Map.entry("--api-key", "AIPROXY_CLIENT_KEYS"),
                    Map.entry("--admin-key", "AIPROXY_ADMIN_CLIENT_KEY"));
            for (Map.Entry<String, String> replacement : replacements.entrySet()) {
                if (message != null && message.contains(replacement.getKey())) {
                    message += System.lineSeparator() + "Use " + replacement.getValue() + " instead of " + replacement.getKey() + ".";
                    break;
                }
            }
            error.getCommandLine().getErr().println(message);
            return 2;
        });
        return command;
    }

}

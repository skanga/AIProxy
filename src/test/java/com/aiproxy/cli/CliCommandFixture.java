package com.aiproxy.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import picocli.CommandLine;

/** Runs the real command tree with captured writers and isolated credential paths. */
final class CliCommandFixture {
    final StringWriter out = new StringWriter();
    final StringWriter err = new StringWriter();
    final CommandLine command;

    CliCommandFixture(Path directory) { this(directory, Map.of()); }

    CliCommandFixture(Path directory, Map<String, String> overrides) {
        var environment = new HashMap<String, String>();
        environment.put("AIPROXY_CODEX_AUTH_MODE", "cli");
        environment.put("AIPROXY_CODEX_OAUTH_FILE", directory.resolve("auth.json").toString());
        environment.put("AIPROXY_CODEX_NATIVE_AUTH_FILE", directory.resolve("native.json").toString());
        environment.put("AIPROXY_ANTHROPIC_OAUTH_FILE", directory.resolve("anthropic.json").toString());
        environment.put("AIPROXY_COPILOT_OAUTH_FILE", directory.resolve("copilot.json").toString());
        if ("native".equals(overrides.get("AIPROXY_CODEX_AUTH_MODE"))) {
            environment.remove("AIPROXY_CODEX_OAUTH_FILE");
        }
        environment.putAll(overrides);
        command = ProxyCommand.commandLine(new ProxyCommand(() -> environment));
        command.setOut(new PrintWriter(out));
        command.setErr(new PrintWriter(err));
    }

    int execute(String... args) { return command.execute(args); }
}

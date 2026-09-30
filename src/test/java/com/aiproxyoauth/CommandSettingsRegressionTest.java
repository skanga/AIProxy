package com.aiproxyoauth;

import com.aiproxyoauth.config.ConfigOverrides;
import com.aiproxyoauth.config.EffectiveConfigLoader;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicAuthCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandSettingsRegressionTest {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(strings = {"serve", "config", "doctor", "auth"})
    void rejectsRootSettingsBeforeSubcommand(String subcommand) {
        CommandLine command = AIProxyOauth.commandLine(new AIProxyOauth(Map::of));
        StringWriter errors = new StringWriter();
        command.setErr(new PrintWriter(errors));
        // Help prevents any accidental serving/authentication if placement validation regresses.
        assertEquals(2, command.execute("--config", "missing.yaml", subcommand,
                subcommand.equals("config") ? "show" : subcommand.equals("auth") ? "status" : "--help"));
        assertTrue(errors.toString().contains("after"), errors.toString());
        assertFalse(errors.toString().contains("not readable"), errors.toString());
    }

    @ParameterizedTest @ValueSource(strings = {"login", "logout"})
    void anthropicCommandsResolveYamlEnvironmentAndCli(String action) throws Exception {
        Path yaml = temporary.resolve("config.yaml");
        Files.writeString(yaml, "anthropic:\n  oauth_file: yaml-auth.json\n");
        Path env = temporary.resolve("env-auth.json");
        Path cli = temporary.resolve("cli-auth.json");
        for (int layer = 0; layer < 3; layer++) {
            Map<String, String> environment = layer == 0 ? Map.of() : Map.of("AIPROXY_ANTHROPIC_OAUTH_FILE", env.toString());
            CommandLine command = AIProxyOauth.commandLine(new AIProxyOauth(() -> environment));
            command.setOut(new PrintWriter(new StringWriter()));
            command.setErr(new PrintWriter(new StringWriter()));
            Path expected = layer == 0 ? temporary.resolve("yaml-auth.json") : layer == 1 ? env : cli;
            // Intercept the interactive boundary; no browser, login, or real credentials are touched.
            try (var system = mockStatic(AnthropicAuthCommands.class)) {
                AnthropicAuthCommands auth = mock(AnthropicAuthCommands.class);
                system.when(() -> AnthropicAuthCommands.system(any(Path.class), any(PrintWriter.class), any(PrintWriter.class))).thenReturn(auth);
                String[] args = layer == 2
                        ? new String[]{"auth", "anthropic", action, "--config", yaml.toString(), "--anthropic-oauth-file", cli.toString()}
                        : new String[]{"auth", "anthropic", action, "--config", yaml.toString()};
                assertEquals(0, command.execute(args));
                system.verify(() -> AnthropicAuthCommands.system(eq(expected), any(PrintWriter.class), any(PrintWriter.class)));
                if (action.equals("login")) verify(auth).login(false); else verify(auth).logout(false);
            }
        }
    }

    @Test void configShowIncludesEveryResolvedSettingAndCustomMembership() {
        Map<String, String> env = Map.of("AIPROXY_PROVIDER", "codex,anthropic", "AIPROXY_CODEX_VERSION", "test-version",
                "AIPROXY_CODEX_STORE", "true", "AIPROXY_CODEX_FORWARD_PROMPT_CACHE_HEADERS", "true",
                "AIPROXY_ANTHROPIC_TOKEN_URL", "https://example.test/token", "AIPROXY_ADMIN_CLIENT_KEY", "private-admin");
        CommandLine command = AIProxyOauth.commandLine(new AIProxyOauth(() -> env));
        StringWriter output = new StringWriter();
        command.setOut(new PrintWriter(output));
        assertEquals(0, command.execute("config", "show"));
        String text = output.toString();
        EffectiveConfigLoader.load(null, env, new ConfigOverrides()).sources().forEach((key, source) ->
                assertTrue(text.lines().anyMatch(line -> line.startsWith(key + ": ") && line.endsWith("# source: " + source)), key));
        assertTrue(text.contains("routing.selected_providers: [codex, anthropic]"), text);
        assertTrue(text.contains("codex.store: true"), text);
        assertFalse(text.contains("private-admin"));
    }
}

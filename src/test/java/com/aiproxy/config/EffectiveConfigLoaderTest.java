package com.aiproxy.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class EffectiveConfigLoaderTest {
    @TempDir Path temporary;

    @Test
    void managedLoginsDefaultToSharedHomeDirectory() {
        EffectiveConfig config = EffectiveConfigLoader.load(null, Map.of(), new ConfigOverrides());
        Path directory = Path.of(System.getProperty("user.home"), ".aiproxy").toAbsolutePath().normalize();

        assertEquals(directory.resolve("copilot-auth.json"), config.copilot().oauthFile());
        assertEquals(directory.resolve("codex-auth.json"), config.codex().nativeAuthFile());
        assertEquals(directory.resolve("anthropic-auth.json"), config.anthropic().oauthFile());
        assertNull(config.codex().oauthFile(), "CLI credentials must retain their separate discovery rules");
    }

    @Test
    void managedLoginPathOverridesKeepCliEnvironmentYamlPrecedence() throws Exception {
        Path yaml = temporary.resolve("paths.yaml");
        Files.writeString(yaml, """
                copilot:
                  oauth_file: yaml/copilot.json
                codex:
                  native_auth_file: yaml/codex.json
                anthropic:
                  oauth_file: yaml/anthropic.json
                """);
        ConfigOverrides overrides = new ConfigOverrides();
        EffectiveConfig config = EffectiveConfigLoader.load(yaml, Map.of(), overrides);
        assertManagedPaths(config, temporary.resolve("yaml"));

        Map<String, String> environment = Map.of(
                "AIPROXY_COPILOT_OAUTH_FILE", temporary.resolve("env/copilot.json").toString(),
                "AIPROXY_CODEX_NATIVE_AUTH_FILE", temporary.resolve("env/codex.json").toString(),
                "AIPROXY_ANTHROPIC_OAUTH_FILE", temporary.resolve("env/anthropic.json").toString());
        assertManagedPaths(EffectiveConfigLoader.load(yaml, environment, overrides), temporary.resolve("env"));

        overrides.copilotOauthFile = temporary.resolve("cli/copilot.json").toString();
        overrides.codexNativeAuthFile = temporary.resolve("cli/codex.json").toString();
        overrides.anthropicOauthFile = temporary.resolve("cli/anthropic.json").toString();
        assertManagedPaths(EffectiveConfigLoader.load(yaml, environment, overrides), temporary.resolve("cli"));
    }

    private static void assertManagedPaths(EffectiveConfig config, Path directory) {
        assertEquals(directory.resolve("copilot.json"), config.copilot().oauthFile());
        assertEquals(directory.resolve("codex.json"), config.codex().nativeAuthFile());
        assertEquals(directory.resolve("anthropic.json"), config.anthropic().oauthFile());
    }

    @Test
    void loadsYamlAndResolvesPathsRelativeToConfigFile() throws Exception {
        Path configFile = temporary.resolve("conf/aiproxy.yaml");
        Files.createDirectories(configFile.getParent());
        Files.createDirectories(temporary.resolve("secrets"));
        Files.writeString(temporary.resolve("secrets/keys.txt"), "app:sk-proxy-test\n");
        Files.writeString(configFile, """
                server:
                  host: 127.0.0.1
                  port: 9090
                routing:
                  provider: both
                  default_provider: anthropic
                client_auth:
                  keys_file: ../secrets/keys.txt
                codex:
                  models: [gpt-test]
                  base_url: https://example.test/codex/
                  instructions:
                    mode: none
                anthropic:
                  models: [claude-test]
                  base_url: https://example.test/v1/
                startup:
                  check: off
                """);

        EffectiveConfig config = EffectiveConfigLoader.load(configFile, Map.of(), new ConfigOverrides());

        assertEquals(9090, config.server().port());
        assertEquals(EffectiveConfig.ProviderSelection.BOTH, config.routing().provider());
        assertEquals("https://example.test/codex", config.codex().baseUrl());
        assertEquals("https://example.test", config.anthropic().baseUrl());
        assertEquals(configFile.getParent().resolve("../secrets/keys.txt").normalize(), config.clientAuth().keysFile());
        assertEquals(EffectiveConfig.StartupCheck.OFF, config.startup().check());
    }

    @Test
    void cliOverridesEnvironmentWhichOverridesYaml() throws Exception {
        Path configFile = temporary.resolve("aiproxy.yaml");
        Files.writeString(configFile, "server:\n  port: 7000\n");
        ConfigOverrides cli = new ConfigOverrides();
        cli.port = 9000;

        EffectiveConfig config = EffectiveConfigLoader.load(
                configFile, Map.of("AIPROXY_PORT", "8000", "AIPROXY_HOST", "localhost"), cli);

        assertEquals(9000, config.server().port());
        assertEquals("localhost", config.server().host());
        assertEquals("cli", config.sources().get("server.port"));
        assertEquals("environment", config.sources().get("server.host"));
    }

    @Test
    void rejectsUnknownKeysInlineSecretsAndInsecureRemoteUrls() throws Exception {
        Path unknown = temporary.resolve("unknown.yaml");
        Files.writeString(unknown, "server:\n  mystery: true\n");
        assertThrows(ConfigException.class,
                () -> EffectiveConfigLoader.load(unknown, Map.of(), new ConfigOverrides()));

        Path secret = temporary.resolve("secret.yaml");
        Files.writeString(secret, "client_auth:\n  keys: [secret]\n");
        ConfigException inline = assertThrows(ConfigException.class,
                () -> EffectiveConfigLoader.load(secret, Map.of(), new ConfigOverrides()));
        assertTrue(inline.getMessage().contains("inline"));

        ConfigOverrides overrides = new ConfigOverrides();
        overrides.codexBaseUrl = "http://example.com/codex";
        assertThrows(ConfigException.class,
                () -> EffectiveConfigLoader.load(null, Map.of(), overrides));
    }

    @Test
    void allowsLoopbackHttpAndValidatesCorsAndInstructionFile() throws Exception {
        ConfigOverrides loopback = new ConfigOverrides();
        loopback.codexBaseUrl = "http://127.0.0.1:8181/codex/";
        loopback.anthropicBaseUrl = "http://localhost:8182/v1";
        EffectiveConfig config = EffectiveConfigLoader.load(null, Map.of(), loopback);
        assertEquals("http://127.0.0.1:8181/codex", config.codex().baseUrl());
        assertEquals("http://localhost:8182", config.anthropic().baseUrl());

        ConfigOverrides cors = new ConfigOverrides();
        cors.corsOrigins = List.of("https://example.com/path");
        assertThrows(ConfigException.class,
                () -> EffectiveConfigLoader.load(null, Map.of(), cors));

        cors.corsOrigins = List.of("http://example.com");
        assertEquals(List.of("http://example.com"),
                EffectiveConfigLoader.load(null, Map.of(), cors).cors().origins());

        ConfigOverrides file = new ConfigOverrides();
        file.codexInstructionsMode = "file";
        file.codexInstructionsFile = temporary.resolve("missing.txt").toString();
        assertThrows(ConfigException.class,
                () -> EffectiveConfigLoader.load(null, Map.of(), file));
    }

    @Test
    void wildcardCorsRequiresClientAuthentication() {
        ConfigOverrides overrides = new ConfigOverrides();
        overrides.allowAnyCors = true;
        ConfigException error = assertThrows(ConfigException.class,
                () -> EffectiveConfigLoader.load(null, Map.of(), overrides));
        assertTrue(error.getMessage().contains("client authentication"));
    }
}

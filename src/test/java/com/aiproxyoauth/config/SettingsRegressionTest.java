package com.aiproxyoauth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SettingsRegressionTest {
    @TempDir Path temporary;

    @Test void exampleConfigurationPassesStrictSchemaValidation() {
        assertNotNull(EffectiveConfigLoader.load(Path.of("aiproxy.example.yaml"), Map.of(), new ConfigOverrides()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "client_auth: mistaken-value", "server: [9090]", "server: null",
            "codex:\n  instructions: none", "server:\n  port: [9090]",
            "server:\n  port: true", "logging:\n  requests: [true]",
            "codex:\n  models: [true]", "cors:\n  origins: [null]",
            "server:\n  host: 123", "codex:\n  models: 123",
            "routing:\n  provider_order: [codex, 123]"
    })
    void rejectsWrongYamlTypes(String yaml) throws Exception {
        Path file = temporary.resolve("config.yaml");
        Files.writeString(file, yaml);
        assertThrows(ConfigException.class, () -> EffectiveConfigLoader.load(file, Map.of(), new ConfigOverrides()));
    }

    @ParameterizedTest @ValueSource(strings = {"none", "latest"})
    void higherPrecedenceModeDiscardsInheritedFile(String mode) throws Exception {
        Path file = temporary.resolve("config.yaml");
        Files.writeString(file, "codex:\n  instructions:\n    mode: file\n    file: missing.txt\n");
        ConfigOverrides cli = new ConfigOverrides();
        cli.codexInstructionsMode = mode;
        EffectiveConfig config = EffectiveConfigLoader.load(file, Map.of(), cli);
        assertEquals(mode.toUpperCase(), config.codex().instructionsMode().name());
        assertNull(config.codex().instructionsFile());
        assertNull(EffectiveConfigLoader.load(file, Map.of("AIPROXY_CODEX_INSTRUCTIONS_MODE", mode),
                new ConfigOverrides()).codex().instructionsFile());
        assertNull(EffectiveConfigLoader.load(file, Map.of("AIPROXY_CODEX_INSTRUCTIONS_FILE", "other.txt"), cli)
                .codex().instructionsFile());
    }

    @Test void explicitConflictingFilesStillFail() {
        ConfigOverrides cli = new ConfigOverrides();
        cli.codexInstructionsMode = "none";
        cli.codexInstructionsFile = "missing.txt";
        assertThrows(ConfigException.class, () -> EffectiveConfigLoader.load(null, Map.of(), cli));
        assertThrows(ConfigException.class, () -> EffectiveConfigLoader.load(null,
                Map.of("AIPROXY_CODEX_INSTRUCTIONS_MODE", "latest", "AIPROXY_CODEX_INSTRUCTIONS_FILE", "missing.txt"), new ConfigOverrides()));
    }

    @ParameterizedTest @ValueSource(strings = {"[::1]", "[0:0:0:0:0:0:0:1]"})
    void acceptsIpv6LoopbackHttp(String host) {
        ConfigOverrides cli = new ConfigOverrides();
        cli.codexBaseUrl = "http://" + host + ":8181/codex";
        cli.anthropicBaseUrl = "http://" + host + ":8182/v1";
        cli.codexOauthTokenUrl = "http://" + host + ":8183/token";
        assertEquals(cli.codexBaseUrl, EffectiveConfigLoader.load(null, Map.of(), cli).codex().baseUrl());
        cli.codexBaseUrl = "http://[2001:db8::1]:8181/codex";
        assertThrows(ConfigException.class, () -> EffectiveConfigLoader.load(null, Map.of(), cli));
    }
}

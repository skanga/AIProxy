package com.aiproxy.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class AuthCommandTest {
    @TempDir Path directory;

    @Test void missingCredentialsReportEveryProviderAndFail() {
        var cli = new CliCommandFixture(directory);
        assertEquals(1, cli.execute("auth", "status"));
        assertTrue(cli.out.toString().contains("Codex (cli): not found"));
        assertTrue(cli.out.toString().contains("Anthropic: not found"));
        assertTrue(cli.out.toString().contains("Copilot: not found or invalid"));
        assertEquals("", cli.err.toString());
    }

    @ParameterizedTest @ValueSource(strings = {"auth.json", "anthropic.json"})
    void credentialFileAvailabilityIsNotValidation(String filename) throws Exception {
        Files.writeString(directory.resolve(filename), "not-valid-json-secret");
        var cli = new CliCommandFixture(directory);
        assertEquals(0, cli.execute("auth", "status"));
        assertTrue(cli.out.toString().contains(filename.equals("auth.json")
                ? "available from " + directory.resolve(filename) : "Anthropic: available"));
        assertTrue(cli.out.toString().contains("use doctor to validate"));
        assertFalse(cli.out.toString().contains("not-valid-json-secret"));
    }

    @ParameterizedTest @ValueSource(strings = {"CLAUDE_CODE_OAUTH_TOKEN", "AIPROXY_COPILOT_TOKEN"})
    void environmentCredentialAloneMakesStatusSucceedWithoutPrintingToken(String variable) {
        var cli = new CliCommandFixture(directory, Map.of(variable, "secret-test-token"));
        assertEquals(0, cli.execute("auth", "status"));
        assertTrue(cli.out.toString().contains(variable.startsWith("CLAUDE")
                ? "Anthropic: available" : "Copilot: available"));
        assertFalse((cli.out.toString() + cli.err).contains("secret-test-token"));
    }

    @Test void invalidCopilotTokenDoesNotCountAsAvailable() {
        var cli = new CliCommandFixture(directory, Map.of("AIPROXY_COPILOT_TOKEN", "invalid token"));
        assertEquals(1, cli.execute("auth", "status"));
        assertTrue(cli.out.toString().contains("Copilot: not found or invalid"));
    }

    @Test void nativeStatusReportsIdentityWithoutTokens() throws Exception {
        Files.writeString(directory.resolve("native.json"), """
                {"version":1,"provider":"openai-native","issuer":"https://auth.openai.com",
                 "token_type":"Bearer","client_id":"test-client","subject":"test-account",
                 "host_id":"test-host","session_id":"test-session","id_token":"secret-id",
                 "access_token":"secret-access","refresh_token":"secret-refresh",
                 "scope":"chatgpt.tokens.use.direct","expires_at":4102444800,"earliest_refresh_at":0}
                """);
        var cli = new CliCommandFixture(directory, Map.of("AIPROXY_CODEX_AUTH_MODE", "native"));
        assertEquals(0, cli.execute("auth", "status"));
        assertTrue(cli.out.toString().contains("Codex (native): available"));
        assertTrue(cli.out.toString().contains("account test-account, client test-client"));
        assertFalse((cli.out.toString() + cli.err).contains("secret-"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void invalidNativeCredentialsDoNotHideOtherProviderAvailability(boolean otherProvider) throws Exception {
        Files.writeString(directory.resolve("native.json"), "broken-secret");
        var env = new java.util.HashMap<String, String>();
        env.put("AIPROXY_CODEX_AUTH_MODE", "native");
        if (otherProvider) env.put("AIPROXY_COPILOT_TOKEN", "test-token");
        var cli = new CliCommandFixture(directory, env);
        assertEquals(otherProvider ? 0 : 1, cli.execute("auth", "status"));
        assertTrue(cli.out.toString().contains("Invalid native credentials"));
        assertFalse((cli.out.toString() + cli.err).contains("broken-secret"));
    }

    @Test void missingNativeFileReportsNativeProfile() {
        var cli = new CliCommandFixture(directory, Map.of("AIPROXY_CODEX_AUTH_MODE", "native"));
        assertEquals(1, cli.execute("auth", "status"));
        assertTrue(cli.out.toString().contains("Codex (native): not found"));
    }
}

package com.aiproxy.cli;

import com.aiproxy.provider.copilot.auth.CopilotOAuth;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CopilotAuthCommandTest {
    @TempDir Path directory;

    @Test void loginUsesEffectiveHostPathAndWriter() throws Exception {
        var cli = new CliCommandFixture(directory);
        Path chosen = directory.resolve("enterprise.json");
        try (var oauth = mockStatic(CopilotOAuth.class)) {
            oauth.when(() -> CopilotOAuth.login(any(), any())).thenAnswer(call -> {
                call.<java.io.PrintWriter>getArgument(1).println("device-login-instructions");
                return null;
            });
            assertEquals(0, cli.execute("auth", "copilot", "login", "--copilot-github-host", "tenant.ghe.com",
                    "--copilot-oauth-file", chosen.toString()));
            oauth.verify(() -> CopilotOAuth.login(argThat(config -> config.oauthFile().equals(chosen)
                    && config.githubHost().equals("tenant.ghe.com")), same(cli.command.getOut())));
            assertTrue(cli.out.toString().contains("device-login-instructions"));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void loginFailureReportsErrorAndPreservesInterrupt(boolean interrupted) throws Exception {
        var cli = new CliCommandFixture(directory);
        Exception failure = interrupted ? new InterruptedException("cancelled") : new IOException("unreachable");
        try (var oauth = mockStatic(CopilotOAuth.class)) {
            oauth.when(() -> CopilotOAuth.login(any(), any())).thenThrow(failure);
            assertEquals(1, cli.execute("auth", "copilot", "login"));
            assertTrue(cli.err.toString().contains("Copilot login failed: " + failure.getMessage()));
            assertEquals(interrupted, Thread.currentThread().isInterrupted());
            assertEquals("", cli.out.toString());
        } finally {
            Thread.interrupted(); // Do not leak interruption into the rest of the test suite.
        }
    }

    @Test void logoutRemovesOnlyManagedCredentials() throws Exception {
        Path managed = directory.resolve("copilot.json");
        Files.writeString(managed, """
                {"version":1,"provider":"copilot","github_host":"github.com","access_token":"managed-test-token"}
                """);
        Path external = directory.resolve("external-token.txt");
        Files.writeString(external, "external-test-token");
        var cli = new CliCommandFixture(directory);
        assertEquals(0, cli.execute("auth", "copilot", "logout", "--copilot-token-file", external.toString()));
        assertFalse(Files.exists(managed));
        assertEquals("external-test-token", Files.readString(external));
        assertTrue(cli.out.toString().contains("external credentials are unchanged"));
    }

    @Test void logoutRefusesToDeleteUnmanagedFile() throws Exception {
        Path file = directory.resolve("copilot.json");
        Files.writeString(file, "{\"access_token\":\"external-secret\"}");
        var cli = new CliCommandFixture(directory);
        assertEquals(1, cli.execute("auth", "copilot", "logout"));
        assertTrue(Files.exists(file));
        assertTrue(cli.err.toString().contains("Copilot logout failed:"));
        assertFalse(cli.err.toString().contains("external-secret"));
    }

    @Test void logoutWithNoManagedFileIsSuccessful() {
        var cli = new CliCommandFixture(directory);
        assertEquals(0, cli.execute("auth", "copilot", "logout"));
        assertTrue(cli.out.toString().contains("Proxy-managed Copilot login removed"));
    }
}

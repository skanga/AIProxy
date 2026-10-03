package com.aiproxy.cli;

import com.aiproxy.provider.anthropic.auth.AnthropicAuthCommands;
import java.io.PrintWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnthropicAuthCommandTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void noBrowserLoginForwardsStdinPermissionAndExitCode(boolean allowStdin) {
        var cli = new CliCommandFixture(directory);
        var auth = mock(AnthropicAuthCommands.class);
        try (var factory = mockStatic(AnthropicAuthCommands.class)) {
            factory.when(() -> AnthropicAuthCommands.system(eq(directory.resolve("anthropic.json")),
                    any(PrintWriter.class), any(PrintWriter.class))).thenReturn(auth);
            when(auth.login(allowStdin, true)).thenReturn(7);
            String[] args = allowStdin
                    ? new String[]{"auth", "anthropic", "login", "--no-browser", "--allow-stdin-oauth-code"}
                    : new String[]{"auth", "anthropic", "login", "--no-browser"};
            assertEquals(7, cli.execute(args));
            verify(auth).login(allowStdin, true);
            verifyNoMoreInteractions(auth);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void logoutForwardsConfirmationAndFailure(boolean yes) {
        var cli = new CliCommandFixture(directory);
        var auth = mock(AnthropicAuthCommands.class);
        try (var factory = mockStatic(AnthropicAuthCommands.class)) {
            factory.when(() -> AnthropicAuthCommands.system(any(Path.class), any(), any())).thenReturn(auth);
            when(auth.logout(yes)).thenReturn(3);
            assertEquals(3, yes ? cli.execute("auth", "anthropic", "logout", "--yes")
                    : cli.execute("auth", "anthropic", "logout"));
            verify(auth).logout(yes);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"login", "logout"})
    void invalidConfigurationFailsBeforeStartingAuth(String action) {
        var cli = new CliCommandFixture(directory);
        try (var factory = mockStatic(AnthropicAuthCommands.class)) {
            assertEquals(2, cli.execute("auth", "anthropic", action,
                    "--config", directory.resolve("missing.yaml").toString()));
            assertTrue(cli.err.toString().contains("Configuration error"));
            factory.verifyNoInteractions();
        }
    }
}

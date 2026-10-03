package com.aiproxy.cli;

import com.aiproxy.provider.codex.auth.nativeoauth.NativeAuthCommands;
import com.aiproxy.provider.codex.auth.CodexAuthFileResolver;
import com.aiproxy.provider.codex.auth.CodexDeviceLogin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodexAuthCommandTest {
    @TempDir Path directory;

    @Test void deviceLoginUsesDefaultDestinationAndPropagatesFailure() {
        var cli = new CliCommandFixture(directory, Map.of("AIPROXY_CODEX_OAUTH_FILE", ""));
        Path destination = directory.resolve("default/auth.json");
        var login = mock(CodexDeviceLogin.class);
        try (var resolver = mockStatic(CodexAuthFileResolver.class);
             var factory = mockStatic(CodexDeviceLogin.class)) {
            resolver.when(() -> CodexAuthFileResolver.resolveWritePath(null)).thenReturn(destination.toString());
            factory.when(CodexDeviceLogin::system).thenReturn(login);
            when(login.login(eq(destination), any(), any())).thenReturn(6);
            assertEquals(6, cli.execute("auth", "codex", "login", "--device-auth"));
            verify(login).login(eq(destination), same(cli.command.getOut()), same(cli.command.getErr()));
            resolver.verify(() -> CodexAuthFileResolver.resolveWritePath(null));
        }
    }

    @Test void logoutReportsDiscoveredCliFileWithoutChangingIt() throws Exception {
        Path external = directory.resolve("discovered-auth.json");
        Files.writeString(external, "external-credential-sentinel");
        var cli = new CliCommandFixture(directory, Map.of("AIPROXY_CODEX_AUTH_MODE", "native"));
        var auth = mock(NativeAuthCommands.class);
        try (var resolver = mockStatic(CodexAuthFileResolver.class);
             var factory = mockStatic(NativeAuthCommands.class)) {
            resolver.when(() -> CodexAuthFileResolver.resolveCandidates(null))
                    .thenReturn(java.util.List.of(external.toString()));
            factory.when(() -> NativeAuthCommands.system(any(), any(), any())).thenReturn(auth);
            assertEquals(0, cli.execute("auth", "codex", "logout", "--yes"));
            verify(auth).logout(true);
            verify(auth).close();
            assertTrue(cli.out.toString().contains("CLI credentials remain available from " + external));
            assertEquals("external-credential-sentinel", Files.readString(external));
        }
    }

    @ParameterizedTest @CsvSource({"auto,false", "cli,true", "native,false"})
    void nativeLoginWarnsOnlyWhenSettingsSelectCli(String mode, boolean warning) {
        // An empty override clears the fixture's explicit CLI file, keeping selection deterministic.
        var cli = new CliCommandFixture(directory, Map.of("AIPROXY_CODEX_AUTH_MODE", mode,
                "AIPROXY_CODEX_OAUTH_FILE", ""));
        var auth = mock(NativeAuthCommands.class);
        Path chosen = directory.resolve("chosen-native.json");
        try (var factory = mockStatic(NativeAuthCommands.class)) {
            factory.when(() -> NativeAuthCommands.system(eq(chosen), any(), any())).thenReturn(auth);
            when(auth.login(true, true)).thenReturn(0);
            assertEquals(0, cli.execute("auth", "codex", "login", "--no-browser", "--new-account",
                    "--codex-native-auth-file", chosen.toString()));
            verify(auth).login(true, true);
            verify(auth).close();
            assertEquals(warning, cli.out.toString().contains("Current settings still select CLI credentials"));
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 4})
    void explicitCliFileWarnsOnlyAfterSuccessfulNativeLogin(int result) {
        var cli = new CliCommandFixture(directory, Map.of("AIPROXY_CODEX_AUTH_MODE", "auto"));
        var auth = mock(NativeAuthCommands.class);
        try (var factory = mockStatic(NativeAuthCommands.class)) {
            factory.when(() -> NativeAuthCommands.system(eq(directory.resolve("native.json")), any(), any())).thenReturn(auth);
            when(auth.login(false, false)).thenReturn(result);
            assertEquals(result, cli.execute("auth", "codex", "login"));
            verify(auth).login(false, false);
            verify(auth).close();
            assertEquals(result == 0, cli.out.toString().contains("Current settings still select CLI credentials"));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void logoutReportsWhetherExternalCliCredentialsRemain(boolean cliExists) throws Exception {
        Path file = directory.resolve("auth.json");
        if (cliExists) Files.writeString(file, "external-credential-sentinel");
        var cli = new CliCommandFixture(directory);
        var auth = mock(NativeAuthCommands.class);
        try (var factory = mockStatic(NativeAuthCommands.class)) {
            factory.when(() -> NativeAuthCommands.system(eq(directory.resolve("native.json")), any(), any())).thenReturn(auth);
            assertEquals(0, cli.execute("auth", "codex", "logout", "--yes"));
            verify(auth).logout(true);
            verify(auth).close();
            assertTrue(cli.out.toString().contains(cliExists ? "CLI credentials remain available from " + file
                    : "No CLI credential file found."));
            if (cliExists) assertEquals("external-credential-sentinel", Files.readString(file));
        }
    }

    @Test void unsuccessfulLogoutReturnsFailureWithoutSuccessAdvice() {
        var cli = new CliCommandFixture(directory);
        var auth = mock(NativeAuthCommands.class);
        try (var factory = mockStatic(NativeAuthCommands.class)) {
            factory.when(() -> NativeAuthCommands.system(any(), any(), any())).thenReturn(auth);
            when(auth.logout(false)).thenReturn(4);
            assertEquals(4, cli.execute("auth", "codex", "logout"));
            verify(auth).logout(false);
            verify(auth).close();
            assertEquals("", cli.out.toString());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"--no-browser", "--new-account", "--codex-native-auth-file"})
    void deviceLoginRejectsEveryNativeOnlyOption(String option) {
        var cli = new CliCommandFixture(directory);
        try (var device = mockStatic(com.aiproxy.provider.codex.auth.CodexDeviceLogin.class);
             var nativeAuth = mockStatic(NativeAuthCommands.class)) {
            String[] args = option.endsWith("file")
                    ? new String[]{"auth", "codex", "login", "--device-auth", option, directory.resolve("native.json").toString()}
                    : new String[]{"auth", "codex", "login", "--device-auth", option};
            assertEquals(2, cli.execute(args));
            assertTrue(cli.err.toString().contains("cannot be combined"));
            device.verifyNoInteractions();
            nativeAuth.verifyNoInteractions();
        }
    }

    @Test void cliDestinationRequiresDeviceLogin() {
        var cli = new CliCommandFixture(directory);
        try (var nativeAuth = mockStatic(NativeAuthCommands.class)) {
            assertEquals(2, cli.execute("auth", "codex", "login", "--codex-oauth-file",
                    directory.resolve("auth.json").toString()));
            assertTrue(cli.err.toString().contains("requires --device-auth"));
            nativeAuth.verifyNoInteractions();
        }
    }
}

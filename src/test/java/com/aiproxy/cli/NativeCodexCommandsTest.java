package com.aiproxy.cli;

import com.aiproxy.provider.codex.auth.CodexDeviceLogin;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeCodexCommandsTest {
    @TempDir Path temporary;

    @Test void nativeLoginHasNoninteractiveHelp() {
        var command = ProxyCommand.commandLine(new ProxyCommand(Map::of));
        var output = new StringWriter();
        command.setOut(new PrintWriter(output));
        command.setErr(new PrintWriter(output));
        assertEquals(0, command.execute("auth", "codex", "login", "--help"));
        assertTrue(output.toString().contains("--no-browser"));
        assertTrue(output.toString().contains("--device-auth"));
    }

    @Test void deviceLoginRejectsNativeOnlyOptionsBeforeStartingLogin() {
        var command = ProxyCommand.commandLine(new ProxyCommand(Map::of));
        var errors = new StringWriter(); command.setErr(new PrintWriter(errors));
        assertEquals(2, command.execute("auth", "codex", "login", "--device-auth", "--new-account"));
        assertTrue(errors.toString().contains("cannot be combined"));
    }

    @Test void deviceLoginUsesEffectiveCliPathEvenWhenServingUsesNative() throws Exception {
        Path yaml = temporary.resolve("device.yaml");
        Files.writeString(yaml, "codex:\n  auth_mode: native\n");
        Path destination = temporary.resolve("chosen/auth.json");
        var command = ProxyCommand.commandLine(new ProxyCommand(() -> Map.of(
                "AIPROXY_CODEX_OAUTH_FILE", temporary.resolve("ignored/auth.json").toString())));
        var login = mock(CodexDeviceLogin.class);
        try (var factory = mockStatic(CodexDeviceLogin.class)) {
            factory.when(CodexDeviceLogin::system).thenReturn(login);
            when(login.login(eq(destination), any(), any())).thenReturn(0);
            assertEquals(0, command.execute("auth", "codex", "login", "--device-auth",
                    "--config", yaml.toString(), "--codex-oauth-file", destination.toString()));
            verify(login).login(eq(destination), any(), any());
        }
    }

    @Test void deviceLoginResolvesYamlRelativePath() throws Exception {
        Path yaml = temporary.resolve("device.yaml");
        Files.writeString(yaml, "codex:\n  oauth_file: selected/auth.json\n");
        var command = ProxyCommand.commandLine(new ProxyCommand(Map::of));
        var login = mock(CodexDeviceLogin.class);
        try (var factory = mockStatic(CodexDeviceLogin.class)) {
            factory.when(CodexDeviceLogin::system).thenReturn(login);
            assertEquals(0, command.execute("auth", "codex", "login", "--device-auth", "--config", yaml.toString()));
            verify(login).login(eq(temporary.resolve("selected/auth.json")), any(), any());
        }
    }

    @Test void nativeSettingsUseEffectiveLoaderAndAreVisible() throws Exception {
        Path yaml = temporary.resolve("settings.yaml");
        Files.writeString(yaml, "codex:\n  auth_mode: native\n  native_auth_file: native.json\n");
        var command = ProxyCommand.commandLine(new ProxyCommand(Map::of));
        var output = new StringWriter();
        command.setOut(new PrintWriter(output));
        command.setErr(new PrintWriter(output));
        assertEquals(0, command.execute("config", "show", "--config", yaml.toString()));
        assertTrue(output.toString().contains("codex.auth_mode: native"));
        assertTrue(output.toString().contains(temporary.resolve("native.json").toString()));
    }
    @Test void nativeConflictsAreConfigurationErrorsBeforeServing() throws Exception {
        Path yaml = temporary.resolve("conflict.yaml");
        Files.writeString(yaml,"codex:\n  auth_mode: native\n  store: true\n");
        var command = ProxyCommand.commandLine(new ProxyCommand(Map::of));
        var errors = new StringWriter(); command.setErr(new PrintWriter(errors));
        assertEquals(2,command.execute("serve","--config",yaml.toString()));
        assertTrue(errors.toString().contains("Configuration error"));
    }
}

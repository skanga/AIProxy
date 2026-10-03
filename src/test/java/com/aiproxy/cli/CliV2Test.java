package com.aiproxy.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

class CliV2Test {
    @Test
    void rootHelpDescribesProxyAndSubcommands() {
        StringWriter output = new StringWriter();
        CommandLine command = ProxyCommand.commandLine();
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("--help"));
        String help = output.toString();
        assertTrue(help.contains("AI proxy exposing multiple model providers via local APIs."));
        assertTrue(help.contains("serve"));
        assertTrue(help.contains("auth"));
        assertTrue(help.contains("key"));
        assertTrue(help.contains("config"));
        assertTrue(help.contains("doctor"));
        assertFalse(help.contains("--api-key"));
    }

    @Test
    void serveHelpUsesExplicitProviderAndClientAuthNames() {
        StringWriter output = new StringWriter();
        CommandLine command = ProxyCommand.commandLine();
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("serve", "--help"));
        String help = output.toString();
        assertTrue(help.contains("--provider"));
        assertTrue(help.contains("--client-keys-file"));
        assertTrue(help.contains("--codex-models"));
        assertTrue(help.contains("--codex-instructions-file"));
        assertTrue(help.contains("--startup-check"));
        assertFalse(help.contains("--models"));
        assertFalse(help.contains("--providers"));
    }

    @Test
    void removedFlagFailsWithReplacement() {
        StringWriter errors = new StringWriter();
        CommandLine command = ProxyCommand.commandLine();
        command.setErr(new PrintWriter(errors));

        assertEquals(2, command.execute("serve", "--models", "gpt-test"));
        assertTrue(errors.toString().contains("--codex-models"));
    }

    @Test
    void keyGenerateIsACommand() {
        StringWriter output = new StringWriter();
        CommandLine command = ProxyCommand.commandLine();
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("key", "generate", "myapp"));
        assertTrue(output.toString().matches("(?s)myapp:sk-proxy-[0-9a-f]{32}\\R"));
    }

    @Test
    void configShowAlwaysRedactsEnvironmentSecrets() {
        StringWriter output = new StringWriter();
        ProxyCommand root = new ProxyCommand(() -> java.util.Map.of(
                "AIPROXY_CLIENT_KEYS", "top-secret",
                "AIPROXY_ADMIN_CLIENT_KEY", "admin-secret"));
        CommandLine command = ProxyCommand.commandLine(root);
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("config", "show"));
        assertTrue(output.toString().contains("<redacted>"));
        assertFalse(output.toString().contains("top-secret"));
        assertFalse(output.toString().contains("admin-secret"));
    }
}

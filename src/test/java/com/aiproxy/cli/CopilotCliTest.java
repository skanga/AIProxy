package com.aiproxy.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CopilotCliTest {
    @Test void loginIsAvailableAndServeListsCopilotOptions() {
        var command = ProxyCommand.commandLine(new ProxyCommand(Map::of));
        var output = new StringWriter();
        command.setOut(new PrintWriter(output));
        command.setErr(new PrintWriter(output));
        assertEquals(0, command.execute("auth", "copilot", "login", "--help"));
        assertEquals(0, command.execute("serve", "--help"));
        assertTrue(output.toString().contains("--copilot-token-file"));
        assertTrue(output.toString().contains("--provider-order"));
    }
    @Test void authStatusAndConfigRedactCopilotToken() {
        var command = ProxyCommand.commandLine(new ProxyCommand(() -> Map.of("AIPROXY_COPILOT_TOKEN", "hidden-token")));
        var output = new StringWriter();
        command.setOut(new PrintWriter(output));
        command.setErr(new PrintWriter(output));
        assertEquals(0, command.execute("auth", "status"));
        assertTrue(output.toString().contains("Copilot: available"));
        assertEquals(0, command.execute("config", "show"));
        assertFalse(output.toString().contains("hidden-token"));
    }
}

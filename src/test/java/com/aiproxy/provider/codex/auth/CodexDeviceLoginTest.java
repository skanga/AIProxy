package com.aiproxy.provider.codex.auth;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class CodexDeviceLoginTest {
    @TempDir Path temporary;
    private final StringWriter output = new StringWriter();
    private int login(CodexDeviceLogin.Runner runner, Path file) {
        return new CodexDeviceLogin(runner).login(file, new PrintWriter(output), new PrintWriter(output));
    }

    @Test void passesSelectedHomeAndExplainsProfileSelection() throws Exception {
        Path file = temporary.resolve("custom/auth.json");
        assertEquals(0, login(home -> {
            assertEquals(file.getParent(), home);
            Files.writeString(home.resolve("auth.json"), "test credentials");
            return 0;
        }, file));
        assertTrue(output.toString().contains("--codex-auth-mode cli"));
        assertFalse(output.toString().contains("test credentials"));
    }

    @Test void rejectsOtherFilenamesBeforeLaunching() {
        assertEquals(2, login(home -> { fail("must not launch"); return 0; }, temporary.resolve("custom.json")));
    }

    @Test void failurePreservesExistingFileAndDoesNotReportSuccess() throws Exception {
        Path file = temporary.resolve("auth.json");
        Files.writeString(file, "existing");
        assertEquals(7, login(home -> 7, file));
        assertEquals("existing", Files.readString(file));
        assertFalse(output.toString().contains("CLI login complete"));
    }

    @Test void missingCliProducesActionableErrorWithoutExceptionContents() {
        assertEquals(1, login(home -> { throw new IOException("secret"); }, temporary.resolve("auth.json")));
        assertTrue(output.toString().contains("Install the official Codex CLI"));
        assertFalse(output.toString().contains("secret"));
    }

    @Test void successWithoutFileIsRejected() {
        assertEquals(1, login(home -> 0, temporary.resolve("auth.json")));
    }

    @Test void interruptionRestoresInterruptFlag() {
        try {
            assertEquals(1, login(home -> { throw new InterruptedException(); }, temporary.resolve("auth.json")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}

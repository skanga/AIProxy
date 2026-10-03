package com.aiproxy.cli;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class KeyCommandTest {
    @TempDir Path directory;

    @Test void keyGroupDoesNotGenerateAKeyWithoutSubcommand() {
        var cli = new CliCommandFixture(directory);
        assertEquals(0, cli.execute("key"));
        assertEquals("", cli.out.toString());
        assertEquals("", cli.err.toString());
    }

    @Test void helpDoesNotGenerateAKey() {
        var cli = new CliCommandFixture(directory);
        assertEquals(0, cli.execute("key", "generate", "--help"));
        assertTrue(cli.out.toString().contains("Usage:"));
        assertFalse(cli.out.toString().contains("sk-proxy-"));
        assertEquals("", cli.err.toString());
    }

    @Test void extraNamesAreRejectedWithoutGeneratingKey() {
        var cli = new CliCommandFixture(directory);
        assertEquals(2, cli.execute("key", "generate", "first", "second"));
        assertEquals("", cli.out.toString());
        assertFalse(cli.err.toString().contains("sk-proxy-"));
    }

    @Test void namedKeyUsesConfiguredWriterAndPrintsExactlyOneEntry() {
        var cli = new CliCommandFixture(directory);
        assertEquals(0, cli.execute("key", "generate", "myapp"));
        assertTrue(cli.out.toString().strip().matches("myapp:sk-proxy-[0-9a-f]{32}"));
        assertEquals(1, cli.out.toString().lines().count());
        assertEquals("", cli.err.toString());
    }
}

package com.aiproxy.logging;

import com.aiproxy.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import tools.jackson.databind.node.ObjectNode;

/** Serialize complete lines from request threads; no open writer to close on shutdown. */
final class JsonLineLog {
    private final Path file;

    JsonLineLog(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    synchronized void append(ObjectNode entry) throws IOException {
        String line = Json.MAPPER.writeValueAsString(entry) + "\n";
        Files.createDirectories(file.getParent());
        Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}

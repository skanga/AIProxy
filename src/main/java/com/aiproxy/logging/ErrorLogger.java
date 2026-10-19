package com.aiproxy.logging;

import com.aiproxy.util.Json;
import io.javalin.http.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** One append-only JSON object per failed HTTP request. Null disables logging. */
public final class ErrorLogger {
    private final Path file;

    public ErrorLogger(Path file) {
        this.file = file == null ? null : file.toAbsolutePath().normalize();
    }

    public void logErrorResponse(String requestId, Context ctx) {
        if (file == null || ctx.statusCode() < 400) {
            return;
        }
        String line = Json.MAPPER.writeValueAsString(RequestLogger.errorEntry(requestId, ctx)) + "\n";
        try {
            append(line);
        } catch (IOException error) {
            System.err.println("Warning: failed to write error log: " + error.getMessage());
        }
    }

    // Serialize complete lines from concurrent request threads. No open writer to close on shutdown.
    private synchronized void append(String line) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}

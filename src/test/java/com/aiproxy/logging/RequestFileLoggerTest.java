package com.aiproxy.logging;

import com.aiproxy.util.Json;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestFileLoggerTest {
    @TempDir Path temp;

    @Test
    void disabledDoesNotReadContext() {
        Context ctx = mock(Context.class);
        new RequestFileLogger(null).logResponse("disabled", ctx);
        verifyNoInteractions(ctx);
    }

    @Test
    void concurrentRequestsAppendWholeLinesAndBoundBodies() throws Exception {
        Path file = temp.resolve("requests.jsonl");
        var logger = new RequestFileLogger(file);
        Context ctx = mock(Context.class);
        when(ctx.statusCode()).thenReturn(200);
        when(ctx.method()).thenReturn(HandlerType.POST);
        when(ctx.path()).thenReturn("/v1/responses");
        when(ctx.body()).thenReturn("{\"input\":\"" + "x".repeat(300 * 1024) + "\"}");
        when(ctx.result()).thenReturn("{\"output\":\"line one\\nline two\"}");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = java.util.stream.IntStream.range(0, 20).mapToObj(i -> executor.submit(
                    () -> logger.logResponse("request_" + i, ctx))).toList();
            for (var job : jobs) job.get();
        }
        var lines = Files.readAllLines(file);
        assertEquals(20, lines.size());
        var ids = new java.util.HashSet<String>();
        for (String line : lines) {
            var entry = Json.MAPPER.readTree(line);
            ids.add(entry.path("request_id").asString());
            assertEquals("request_response", entry.path("stage").asString());
            assertTrue(entry.path("response_body_available").asBoolean());
            assertTrue(entry.path("request").path("truncated").asBoolean());
            assertEquals(256 * 1024, entry.path("request").path("body").asString().length());
        }
        assertEquals(20, ids.size());
    }

    @Test
    void writeFailureDoesNotFailHttpRequest() throws Exception {
        Path file = temp.resolve("not-a-directory");
        Files.writeString(file, "occupied");
        Context ctx = mock(Context.class);
        when(ctx.method()).thenReturn(HandlerType.GET);
        assertDoesNotThrow(() -> new RequestFileLogger(file.resolve("requests.jsonl")).logResponse("request", ctx));
    }
}

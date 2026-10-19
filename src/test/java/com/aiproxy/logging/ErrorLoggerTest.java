package com.aiproxy.logging;

import com.aiproxy.util.Json;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ErrorLoggerTest {
    @TempDir Path temporary;

    @Test
    void concurrentErrorsAppendCompleteLinesAndPreserveExistingEntries() throws Exception {
        Path file = temporary.resolve("nested/errors.jsonl");
        Context ctx = mock(Context.class);
        when(ctx.statusCode()).thenReturn(500);
        when(ctx.method()).thenReturn(HandlerType.POST);
        when(ctx.path()).thenReturn("/v1/responses");
        when(ctx.body()).thenReturn("{\"input\":\"first line\\nsecond line\"}");
        when(ctx.result()).thenReturn("upstream error\nwith newline");
        new ErrorLogger(file).logErrorResponse("existing", ctx);
        ErrorLogger logger = new ErrorLogger(file);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = IntStream.range(0, 40).mapToObj(i -> executor.submit(
                    () -> logger.logErrorResponse("request_" + i, ctx))).toList();
            for (var job : jobs) job.get();
        }
        var lines = Files.readAllLines(file);
        assertEquals(41, lines.size());
        assertEquals("existing", Json.MAPPER.readTree(lines.getFirst()).path("request_id").asString());
        var ids = new HashSet<String>();
        for (String line : lines) {
            var entry = Json.MAPPER.readTree(line);
            assertEquals("upstream error\nwith newline", entry.path("body").asString());
            ids.add(entry.path("request_id").asString());
        }
        assertEquals(41, ids.size());
        assertTrue(Files.readString(file).endsWith("\n"));
    }

    @Test
    void disabledLoggingDoesNotReadContext() {
        Context ctx = mock(Context.class);
        new ErrorLogger(null).logErrorResponse("disabled", ctx);
        verifyNoInteractions(ctx);
    }

    @Test
    void successfulRequestsDoNotCreateFileOrReadBody() {
        Path file = temporary.resolve("errors.jsonl");
        Context ctx = mock(Context.class);
        when(ctx.statusCode()).thenReturn(200);
        new ErrorLogger(file).logErrorResponse("success", ctx);
        assertFalse(Files.exists(file));
        verify(ctx, never()).body();
    }
}

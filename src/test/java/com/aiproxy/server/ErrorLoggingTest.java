package com.aiproxy.server;

import com.aiproxy.bootstrap.ProviderAssembly;
import com.aiproxy.config.ConfigOverrides;
import com.aiproxy.config.EffectiveConfigLoader;
import com.aiproxy.provider.codex.CodexHttpClient;
import com.aiproxy.usage.UsageTracker;
import com.aiproxy.util.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ErrorLoggingTest {
    @TempDir Path temp;

    @Test
    void enabledLogsOnlyErrorsWithResponseDetailsAndRequestId() throws Exception {
        Path directory = temp.resolve("nested/errors.jsonl");
        ProxyServer server = server(directory, true);
        server.getApp().start(0);
        try {
            assertEquals(200, get(server, "/health").statusCode());
            assertFalse(Files.exists(directory));
            assertEquals(404, get(server, "/missing").statusCode());
            assertEquals(404, get(server, "/another-missing").statusCode());
        } finally {
            server.stop();
        }
        {
            var lines = Files.readAllLines(directory);
            assertEquals(2, lines.size());
            var entry = Json.MAPPER.readTree(lines.getFirst());
            assertEquals(404, entry.path("status").asInt());
            assertEquals("/missing", entry.path("path").asString());
            assertEquals("GET", entry.path("method").asString());
            assertTrue(entry.path("request_id").asString().startsWith("aiproxy_"));
            assertTrue(entry.path("body").asString().contains("Route not found"));
            assertFalse(Files.readString(directory).contains("secret-key"));
        }
    }

    @Test
    void failedPostIncludesOriginalRequestAndRedactedHeaders() throws Exception {
        Path directory = temp.resolve("post-errors.jsonl");
        ProxyServer server = server(directory, true);
        String body = "{\"model\":\"invalid/model\",\"input\":\"diagnostic prompt\",\"access_token\":\"body-secret\"}";
        server.getApp().start(0);
        try {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.getApp().port() + "/v1/responses"))
                    .header("Authorization", "Bearer header-secret")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
        } finally {
            server.stop();
        }
        {
            var logs = Files.readAllLines(directory);
            assertEquals(1, logs.size());
            String log = logs.getFirst();
            var request = Json.MAPPER.readTree(log).path("request");
            assertEquals("diagnostic prompt", Json.MAPPER.readTree(request.path("body").asString()).path("input").asString());
            assertFalse(request.path("truncated").asBoolean());
            assertFalse(log.contains("body-secret"));
            assertFalse(log.contains("header-secret"));
        }
    }

    @Test
    void disabledDoesNotCreateLogs() throws Exception {
        Path directory = temp.resolve("disabled");
        ProxyServer server = server(directory, false);
        server.getApp().start(0);
        try { assertEquals(404, get(server, "/missing").statusCode()); }
        finally { server.stop(); }
        assertFalse(Files.exists(directory));
    }

    private ProxyServer server(Path directory, boolean enabled) {
        var effective = EffectiveConfigLoader.load(null, enabled ? Map.of(
                "AIPROXY_ERROR_LOG_FILE", directory.toString()) : Map.of(), new ConfigOverrides());
        return ProviderAssembly.create(effective.legacyServerConfig(Map.of(), null),
                mock(CodexHttpClient.class), () -> List.of(), new UsageTracker(),
                new ApiKeyStore(Map.of(), null, null));
    }

    private HttpResponse<String> get(ProxyServer server, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getApp().port() + path))
                .header("Authorization", "Bearer secret-key").build(), HttpResponse.BodyHandlers.ofString());
    }
}

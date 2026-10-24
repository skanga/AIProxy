package com.aiproxy.server;

import com.aiproxy.config.ConfigOverrides;
import com.aiproxy.config.EffectiveConfigLoader;
import com.aiproxy.logging.RequestLogger;
import com.aiproxy.usage.UsageTracker;
import com.aiproxy.util.Json;
import io.javalin.http.Handler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class RequestFileLoggingTest {
    @TempDir Path temp;

    @Test
    void singleFileContainsSuccessAndHttpErrorsWithOriginalRequestsAndRedactedBodies() throws Exception {
        Path file = temp.resolve("nested/requests.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"existing\":true}\n");
        var server = server(file, false);
        server.getApp().start(0);
        try {
            assertEquals(200, send(server, "/health", null).statusCode());
            assertEquals(200, send(server, "/v1/responses", "{\"input\":\"successful prompt\",\"refresh_token\":\"private-token\"}").statusCode());
            assertEquals(404, send(server, "/missing", "{\"input\":\"failed prompt\"}").statusCode());
        } finally { server.stop(); }
        var lines = Files.readAllLines(file);
        assertEquals(4, lines.size(), "one record per HTTP request, with existing entries preserved");
        assertTrue(Json.MAPPER.readTree(lines.getFirst()).path("existing").asBoolean());
        var success = Json.MAPPER.readTree(lines.get(2));
        assertEquals("request_response", success.path("stage").asString());
        assertEquals(200, success.path("status").asInt());
        assertTrue(success.path("request_id").asString().startsWith("aiproxy_"));
        assertEquals("successful prompt", Json.MAPPER.readTree(success.path("request").path("body").asString()).path("input").asString());
        assertEquals("ok", Json.MAPPER.readTree(success.path("body").asString()).path("result").asString());
        assertEquals(404, Json.MAPPER.readTree(lines.getLast()).path("status").asInt());
        assertTrue(lines.getLast().contains("failed prompt"));
        assertFalse(Files.readString(file).contains("private-token"));
        assertFalse(Files.readString(file).contains("private-header"));
        assertFalse(Files.readString(file).contains("private-response-token"));
    }

    @Test
    void includesRequestsRejectedByAuthenticationWithoutEnablingPerStageFiles() throws Exception {
        Path file = temp.resolve("auth.jsonl");
        var server = server(file, true);
        server.getApp().start(0);
        try { assertEquals(401, send(server, "/v1/responses", "{\"input\":\"rejected prompt\"}").statusCode()); }
        finally { server.stop(); }
        assertTrue(Files.exists(file), "all-request file logging must work without --log-requests");
        var lines = Files.readAllLines(file);
        assertEquals(1, lines.size());
        assertEquals(401, Json.MAPPER.readTree(lines.getFirst()).path("status").asInt());
        assertTrue(lines.getFirst().contains("rejected prompt"));
        assertFalse(Files.exists(temp.resolve("per-stage")));
    }

    @Test
    void streamingRequestIsLoggedWithoutBufferingOrChangingSseResponse() throws Exception {
        Path file = temp.resolve("stream.jsonl");
        String sse = "data: hello\n\ndata: [DONE]\n\n";
        Handler streaming = ctx -> {
            JsonHelper.setSseHeaders(ctx);
            ctx.res().getOutputStream().write(sse.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ctx.res().getOutputStream().flush();
        };
        var server = server(file, false, streaming);
        server.getApp().start(0);
        try {
            var response = send(server, "/v1/responses", "{\"input\":\"streaming prompt\",\"stream\":true}");
            assertEquals(200, response.statusCode());
            assertEquals(sse, response.body());
        } finally { server.stop(); }
        var entry = Json.MAPPER.readTree(Files.readString(file));
        assertTrue(entry.path("request").path("body").asString().contains("streaming prompt"));
        assertFalse(entry.path("response_body_available").asBoolean());
        assertEquals("", entry.path("body").asString());
    }

    private ProxyServer server(Path file, boolean enforceAuth) {
        Handler success = ctx -> JsonHelper.toJsonResponse(ctx,
                Json.MAPPER.createObjectNode().put("result", "ok").put("access_token", "private-response-token"));
        return server(file, enforceAuth, success);
    }

    private ProxyServer server(Path file, boolean enforceAuth, Handler handler) {
        var effective = EffectiveConfigLoader.load(null, Map.of("AIPROXY_REQUEST_LOG_FILE", file.toString()), new ConfigOverrides());
        Map<String, String> keys = enforceAuth ? Map.of("valid-key", "client") : Map.of();
        return new ProxyServer(effective.legacyServerConfig(keys, null), new ProxyEndpoints(handler, handler, handler, handler),
                new UsageTracker(), new ApiKeyStore(keys, null, null), new RequestLogger(false, temp.resolve("per-stage")));
    }

    private HttpResponse<String> send(ProxyServer server, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getApp().port() + path))
                .header("Authorization", "Bearer private-header");
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

package com.aiproxy.provider.copilot;

import com.aiproxy.bootstrap.ProviderAssembly;
import com.aiproxy.config.EffectiveConfig;
import com.aiproxy.config.ServerConfig;
import com.aiproxy.model.ProviderModel;
import com.aiproxy.provider.ProviderId;
import com.aiproxy.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxy.provider.anthropic.AnthropicHttpClient;
import com.aiproxy.provider.anthropic.AnthropicRequestOptions;
import com.aiproxy.provider.codex.CodexHttpClient;
import com.aiproxy.provider.copilot.model.CopilotModelCatalog;
import com.aiproxy.server.ApiKeyStore;
import com.aiproxy.server.ProxyServer;
import com.aiproxy.usage.UsageTracker;
import com.aiproxy.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CopilotMessagesTest {
    private static final String MESSAGE = """
            {"type":"message","id":"msg_test","role":"assistant","model":"claude-test",
             "content":[{"type":"tool_use","id":"tool_1","name":"lookup","input":{"query":"hello"}}],
             "stop_reason":"tool_use","usage":{"input_tokens":3,"cache_read_input_tokens":4,"output_tokens":2}}
            """;
    private static ObjectNode body(boolean stream) {
        return (ObjectNode) Json.MAPPER.readTree("""
                {"model":"copilot/claude-test","max_tokens":64,"stream":%s,
                 "system":[{"type":"text","text":"client system","cache_control":{"type":"ephemeral"}}],
                 "thinking":{"type":"adaptive"},"metadata":{"user_id":"test-user"},
                 "tools":[{"name":"lookup","input_schema":{"type":"object"},"defer_loading":true}],
                 "messages":[{"role":"user","content":"hello"}],"future_field":{"preserve":true}}
                """.formatted(stream));
    }

    @Test void copilotOnlyPreservesNativeBodyHeadersToolsAndUsage() throws Exception {
        try (Fixture f = new Fixture(false, true)) {
            ObjectNode request = body(false);
            var response = f.post(request, "2023-06-01", "tools-beta,thinking-beta", "proxy-secret");
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(MESSAGE, response.body());
            assertEquals("upstream-id", response.headers().firstValue("request-id").orElseThrow());
            ObjectNode expected = request.deepCopy().put("model", "claude-test");
            assertEquals(expected, f.sent.getFirst());
            assertEquals("Bearer github-token", f.headers.getFirst().getFirst("Authorization"));
            assertNull(f.headers.getFirst().getFirst("x-api-key"));
            assertEquals("2023-06-01", f.headers.getFirst().getFirst("anthropic-version"));
            assertEquals("tools-beta,thinking-beta", f.headers.getFirst().getFirst("anthropic-beta"));
            assertEquals("application/json", f.headers.getFirst().getFirst("Accept"));
            assertEquals(7, f.usage.snapshot().get("test").promptTokens());
            assertEquals(2, f.usage.snapshot().get("test").completionTokens());

            request.withArray("messages").addObject().put("role", "assistant")
                    .set("content", Json.MAPPER.readTree(MESSAGE).get("content"));
            request.withArray("messages").addObject().put("role", "user").putArray("content")
                    .addObject().put("type", "tool_result").put("tool_use_id", "tool_1").put("content", "found");
            assertEquals(200, f.post(request).statusCode());
            assertEquals(request.deepCopy().put("model", "claude-test"), f.sent.getLast());
        }
    }

    @Test void streamsNativeEventsWithoutTranslationOrAddedTerminators() throws Exception {
        try (Fixture f = new Fixture(false, true)) {
            f.responseType = "text/event-stream";
            f.responseBody = """
                    event: message_start
                    data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":3,"cache_read_input_tokens":4}}}

                    event: ping
                    data: {"type":"ping"}

                    event: content_block_start
                    data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool_1","name":"lookup","input":{}}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":0}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":2}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """;
            var response = f.post(body(true));
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(f.responseBody, response.body());
            assertFalse(response.body().contains("[DONE]"));
            assertEquals("text/event-stream", f.headers.getFirst().getFirst("Accept"));
            assertEquals(7, f.usage.snapshot().get("test").promptTokens());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"[]", "[\"/chat/completions\"]", "[\"/responses\"]", "null", "\"/v1/messages\"", "missing"})
    void requiresExplicitMessagesEndpointAndNeverFallsBack(String endpoints) throws Exception {
        try (Fixture f = new Fixture(true, true)) {
            f.modelJson = "{\"data\":[{\"id\":\"claude-test\"" + (endpoints.equals("missing") ? "" : ",\"supported_endpoints\":" + endpoints) + "}]}";
            var response = f.post(body(false));
            assertEquals(400, response.statusCode(), response.body());
            assertEquals("invalid_request_error", Json.MAPPER.readTree(response.body()).at("/error/type").asString());
            assertTrue(f.sent.isEmpty());
            assertEquals(0, f.otherRequests.get());
            verifyNoInteractions(f.anthropic);
        }
    }

    @Test void preservesAnthropicRoutingAndRejectsOtherProviders() throws Exception {
        try (Fixture f = new Fixture(true, true)) {
            assertEquals(200, f.post(body(false)).statusCode());
            for (String model : List.of("claude-test", "anthropic/claude-test")) {
                assertEquals(200, f.post(body(false).put("model", model)).statusCode());
            }
            verify(f.anthropic, times(2)).request(any(URI.class), eq("POST"), anyString(), any(AnthropicRequestOptions.class));
            assertEquals(1, f.sent.size());
            assertEquals(400, f.post(body(false).put("model", "codex/gpt-test")).statusCode());
            assertEquals(400, f.post(body(false).put("model", "copilot/unknown")).statusCode());
        }
        try (Fixture f = new Fixture(false, true)) {
            assertEquals(503, f.post(body(false).put("model", "claude-test")).statusCode());
        }
        try (Fixture f = new Fixture(true, false)) {
            assertEquals(503, f.post(body(false)).statusCode());
            assertTrue(f.sent.isEmpty());
        }
    }

    @Test void rejectsInvalidRequestsAndClientKeysBeforeUpstream() throws Exception {
        try (Fixture f = new Fixture(false, true)) {
            assertEquals(401, f.post(body(false), "2023-06-01", null, "wrong-key").statusCode());
            assertEquals(400, f.post(body(false), null, null, "proxy-secret").statusCode());
            assertEquals(400, f.post(body(false), "invalid", null, "proxy-secret").statusCode());
            assertEquals(400, f.post(body(false), "2023-06-01", "invalid beta", "proxy-secret").statusCode());
            assertEquals(400, f.post(body(false).put("stream", "yes")).statusCode());
            assertEquals(400, f.post(body(false).put("model", "copilot/")).statusCode());
            assertTrue(f.sent.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(ints = {401, 403, 429, 500})
    void preservesNativeErrorsAndDoesNotRetryAnotherProvider(int status) throws Exception {
        try (Fixture f = new Fixture(true, true)) {
            f.responseStatus = status;
            f.responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"retry later\"}}";
            var response = f.post(body(false));
            assertEquals(status, response.statusCode());
            assertEquals(f.responseBody, response.body());
            assertEquals("2", response.headers().firstValue("retry-after").orElseThrow());
            assertEquals(1, f.sent.size());
            verifyNoInteractions(f.anthropic);
        }
    }

    @Test void preservesNativeStreamErrorWithoutInventingCompletion() throws Exception {
        try (Fixture f = new Fixture(true, true)) {
            f.responseType = "text/event-stream";
            f.responseBody = "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}\n\n";
            var response = f.post(body(true));
            assertEquals(f.responseBody, response.body());
            assertEquals(1, f.sent.size());
            verifyNoInteractions(f.anthropic);
        }
    }

    @Test void preservesCopilotsOwnTrailingDoneAndReplacesBearerCredential() throws Exception {
        try (Fixture f = new Fixture(false, true)) {
            f.responseType = "text/event-stream";
            f.responseBody = "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\ndata: [DONE]\n\n";
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + f.proxy.getApp().port() + "/v1/messages"))
                    .header("Authorization", "Bearer proxy-secret").header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(true).toString())).build();
            var response = f.http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(f.responseBody, response.body());
            assertEquals("Bearer github-token", f.headers.getFirst().getFirst("Authorization"));
        }
    }

    @Test void boundsUpstreamErrorsAndReportsTransportFailure() throws Exception {
        try (Fixture f = new Fixture(false, true)) {
            f.responseStatus = 500;
            f.responseBody = "x".repeat(1024 * 1024 + 1);
            var response = f.post(body(false));
            assertEquals(502, response.statusCode());
            assertEquals("api_error", Json.MAPPER.readTree(response.body()).at("/error/type").asString());
            f.upstream.stop(0);
            response = f.post(body(false));
            assertEquals(502, response.statusCode());
            assertFalse(response.body().contains("github-token"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer upstream;
        final HttpClient http = HttpClient.newHttpClient();
        final ProxyServer proxy;
        final UsageTracker usage = new UsageTracker();
        final AnthropicHttpClient anthropic = mock();
        final List<JsonNode> sent = new CopyOnWriteArrayList<>();
        final List<com.sun.net.httpserver.Headers> headers = new CopyOnWriteArrayList<>();
        final AtomicInteger otherRequests = new AtomicInteger();
        volatile String modelJson = "{\"data\":[{\"id\":\"claude-test\",\"supported_endpoints\":[\"/chat/completions\",\"/v1/messages\"]}]}";
        volatile String responseBody = MESSAGE;
        volatile String responseType = "application/json";
        volatile int responseStatus = 200;
        Fixture(boolean withAnthropic, boolean withCopilot) throws Exception {
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
            upstream.createContext("/user", ex -> respond(ex, 200, "{\"endpoints\":{\"api\":\"" + base + "\"}}", "application/json"));
            upstream.createContext("/models", ex -> respond(ex, 200, modelJson, "application/json"));
            upstream.createContext("/v1/messages", ex -> {
                sent.add(Json.MAPPER.readTree(ex.getRequestBody().readAllBytes()));
                headers.add(ex.getRequestHeaders());
                ex.getResponseHeaders().add("request-id", "upstream-id");
                ex.getResponseHeaders().add("retry-after", "2");
                respond(ex, responseStatus, responseBody, responseType);
            });
            upstream.createContext("/", ex -> { otherRequests.incrementAndGet(); respond(ex, 500, "{}", "application/json"); });
            upstream.start();
            var config = new EffectiveConfig.Copilot("github.com", Path.of("unused"), "client", null, "github-token", List.of());
            var client = new CopilotHttpClient(config, http, Clock.systemUTC(), URI.create(base + "/user"));
            var catalog = new CopilotModelCatalog(client, List.of(), Clock.systemUTC());
            when(anthropic.request(any(URI.class), eq("POST"), anyString(), any(AnthropicRequestOptions.class)))
                    .thenAnswer(call -> {
                        HttpResponse<InputStream> response = mock();
                        when(response.statusCode()).thenReturn(200);
                        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("content-type", List.of("application/json")), (a,b) -> true));
                        when(response.body()).thenReturn(new ByteArrayInputStream(MESSAGE.getBytes(StandardCharsets.UTF_8)));
                        return response;
                    });
            var serverConfig = new ServerConfig("127.0.0.1", 10531, null, null, null, null, null, null, "", false, Map.of(), null);
            proxy = ProviderAssembly.create(serverConfig, mock(CodexHttpClient.class),
                    () -> List.of(new ProviderModel("claude-test", "Claude", ProviderId.ANTHROPIC, List.of(), Optional.empty(), 0)),
                    usage, new ApiKeyStore(Map.of("proxy-secret", "test"), null, null),
                    withAnthropic ? anthropic : null, withAnthropic ? AnthropicCompatibilityProfile.claudeCodeOAuth() : null,
                    ProviderId.COPILOT, client, catalog,
                    withCopilot ? (withAnthropic ? Set.of(ProviderId.COPILOT, ProviderId.ANTHROPIC) : Set.of(ProviderId.COPILOT)) : Set.of(ProviderId.ANTHROPIC),
                    ProviderId.defaultOrder(), true);
            proxy.getApp().start("127.0.0.1", 0);
        }
        HttpResponse<String> post(ObjectNode body) throws Exception {
            return post(body, "2023-06-01", null, "proxy-secret");
        }
        HttpResponse<String> post(ObjectNode body, String version, String beta, String key) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxy.getApp().port() + "/v1/messages?beta=true"))
                    .header("Content-Type", "application/json").header("x-api-key", key);
            if (version != null) request.header("anthropic-version", version);
            if (beta != null) request.header("anthropic-beta", beta);
            return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        }
        public void close() { proxy.stop(); http.close(); upstream.stop(0); }
    }
    private static void respond(HttpExchange ex, int status, String body, String type) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}

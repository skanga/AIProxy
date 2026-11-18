package com.aiproxy.provider.copilot;

import com.aiproxy.config.ConfigOverrides;
import com.aiproxy.config.EffectiveConfigLoader;
import com.aiproxy.logging.RequestLogger;
import com.aiproxy.provider.ProviderId;
import com.aiproxy.provider.copilot.model.CopilotModelCatalog;
import com.aiproxy.routing.ModelRoute;
import com.aiproxy.server.*;
import com.aiproxy.usage.UsageTracker;
import com.aiproxy.util.Json;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CopilotReadTimeoutTest {
    @TempDir Path temp;

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {200, 422})
    void stalledHttpBodyIsClosedAndReturns504(int status) throws Exception {
        var upstreamServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var release = new java.util.concurrent.CountDownLatch(1);
        upstreamServer.createContext("/stall", exchange -> {
            try {
                exchange.sendResponseHeaders(status, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        upstreamServer.start();
        ProxyServer server = null;
        try (var http = HttpClient.newHttpClient()) {
            var upstream = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + upstreamServer.getAddress().getPort() + "/stall")).build(), HttpResponse.BodyHandlers.ofInputStream());
            var client = mock(CopilotHttpClient.class);
            @SuppressWarnings("unchecked")
            HttpResponse<InputStream> timedUpstream = mock(HttpResponse.class);
            when(timedUpstream.statusCode()).thenReturn(upstream.statusCode());
            when(timedUpstream.headers()).thenReturn(upstream.headers());
            when(timedUpstream.body()).thenReturn(new com.aiproxy.transport.ReadIdleTimeoutInputStream(
                    upstream.body(), java.time.Duration.ofMillis(150)));
            when(client.request(anyString(), any())).thenReturn(timedUpstream);
            var catalog = mock(CopilotModelCatalog.class);
            when(catalog.endpoint(anyString(), anyBoolean())).thenReturn("/chat/completions");
            var usage = new UsageTracker();
            var logger = new RequestLogger(false, temp);
            var backend = new CopilotBackend(client, catalog, usage, logger);
            io.javalin.http.Handler handler = ctx -> backend.handle(ctx,
                    new ModelRoute(ProviderId.COPILOT, "copilot/test", "test", null), InferenceApi.CHAT_COMPLETIONS);
            var config = EffectiveConfigLoader.load(null, Map.of(), new ConfigOverrides()).legacyServerConfig(Map.of(), null);
            server = new ProxyServer(config, new ProxyEndpoints(handler, handler, handler, handler), usage,
                    new ApiKeyStore(Map.of(), null, null), logger);
            server.getApp().start(0);
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getApp().port()
                            + "/v1/chat/completions")).timeout(java.time.Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(504, response.statusCode(), response.body());
            assertEquals("upstream_read_timeout", Json.MAPPER.readTree(response.body()).at("/error/code").asString());
            assertEquals("upstream_timeout", response.headers().firstValue("X-AIProxy-Error-Category").orElseThrow());
            verify(client, times(1)).request(anyString(), any());
        } finally {
            release.countDown();
            if (server != null) server.stop();
            upstreamServer.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({"sync,false", "error,false", "stream-before,false", "stream-after,false",
            "sync,true", "error,true", "stream-before,true", "stream-after,true"})
    void readTimeoutIs504UnlessStreamIsAlreadyCommitted(String mode, boolean responses) throws Exception {
        var client = mock(CopilotHttpClient.class);
        when(client.identity()).thenReturn("test-client");
        var catalog = mock(CopilotModelCatalog.class);
        when(catalog.endpoint(anyString(), anyBoolean())).thenReturn("/chat/completions");
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> upstream = mock(HttpResponse.class);
        when(upstream.statusCode()).thenReturn(mode.equals("error") ? 422 : 200);
        when(upstream.headers()).thenReturn(HttpHeaders.of(Map.of("request-id", List.of("upstream-123")), (a, b) -> true));
        String prefix = mode.equals("stream-after")
                ? "data: {\"id\":\"one\",\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n" : "";
        InputStream stalled = new InputStream() {
            @Override public int read() throws IOException { throw new HttpTimeoutException("private transport details"); }
        };
        when(upstream.body()).thenReturn(new SequenceInputStream(
                new ByteArrayInputStream(prefix.getBytes(StandardCharsets.UTF_8)), stalled));
        when(client.request(anyString(), any())).thenReturn(upstream);
        var usage = new UsageTracker();
        var logger = new RequestLogger(false, temp);
        var backend = new CopilotBackend(client, catalog, usage, logger);
        var api = responses ? InferenceApi.RESPONSES : InferenceApi.CHAT_COMPLETIONS;
        io.javalin.http.Handler handler = ctx -> backend.handle(ctx,
                new ModelRoute(ProviderId.COPILOT, "copilot/test", "test", null), api);
        var config = EffectiveConfigLoader.load(null, Map.of(), new ConfigOverrides()).legacyServerConfig(Map.of(), null);
        var server = new ProxyServer(config, new ProxyEndpoints(handler, handler, handler, handler), usage,
                new ApiKeyStore(Map.of(), null, null), logger);
        try (var http = HttpClient.newHttpClient()) {
            server.getApp().start(0);
            var body = Json.MAPPER.createObjectNode().put("stream", mode.startsWith("stream"));
            if (responses) body.put("input", "hi");
            else body.putArray("messages").addObject().put("role", "user").put("content", "hi");
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getApp().port()
                            + (responses ? "/v1/responses" : "/v1/chat/completions")))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(mode.equals("stream-after") ? 200 : 504, response.statusCode(), response.body());
            assertTrue(response.body().contains("timed out"), response.body());
            assertFalse(response.body().contains("private transport details"));
            assertTrue(response.headers().firstValue("X-Request-Id").orElseThrow().startsWith("aiproxy_"));
            if (mode.equals("stream-after")) {
                assertTrue(response.body().contains("partial"));
                assertTrue(response.body().contains(responses ? "event: response.failed" : "event: error"));
            } else {
                assertEquals("upstream_read_timeout", Json.MAPPER.readTree(response.body()).at("/error/code").asString());
                assertEquals("application/json;charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow().replace(" ", ""));
            }
            verify(client, times(1)).request(anyString(), any());
        } finally { server.stop(); }
    }
}

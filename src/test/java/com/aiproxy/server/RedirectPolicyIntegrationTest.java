package com.aiproxy.server;

import com.aiproxy.config.ConfigOverrides;
import com.aiproxy.config.EffectiveConfigLoader;
import com.aiproxy.logging.RequestLogger;
import com.aiproxy.model.ProviderModel;
import com.aiproxy.provider.ProviderId;
import com.aiproxy.transport.InferenceTransport;
import com.aiproxy.usage.UsageTracker;
import com.aiproxy.util.Json;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RedirectPolicyIntegrationTest {
    @TempDir Path temp;

    @Test void rejectedRedirectsReturnSafe502WithoutFailoverOrFollowingLocation() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var hits = new AtomicInteger();
        var redirectedHits = new AtomicInteger();
        var fallback = new AtomicInteger();
        upstream.createContext("/start", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/target?private-secret=abc");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        upstream.createContext("/target", exchange -> {
            redirectedHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        upstream.start();
        try (var http = HttpClient.newHttpClient()) {
            InferenceBackend first = (ctx, route, api) -> {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/start"))
                        .header("Authorization", "Bearer private-token").POST(HttpRequest.BodyPublishers.ofString("private prompt")).build();
                var response = InferenceTransport.send(http, request);
                response.body().close();
            };
            var models = List.of(new ProviderModel("same", "same", ProviderId.COPILOT, List.of(), Optional.of(true), 0),
                    new ProviderModel("same", "same", ProviderId.CODEX, List.of(), Optional.of(true), 0));
            var dispatch = new InferenceDispatchHandler(() -> models, ProviderId.COPILOT, "same",
                    Map.of(ProviderId.COPILOT, first, ProviderId.CODEX, (ctx, route, api) -> { fallback.incrementAndGet(); ctx.result("wrong"); }),
                    ProviderId.defaultOrder(), true, InferenceApi.CHAT_COMPLETIONS);
            var config = EffectiveConfigLoader.load(null, Map.of(), new ConfigOverrides()).legacyServerConfig(Map.of(), null);
            io.javalin.http.Handler handler = dispatch;
            var proxy = new ProxyServer(config, new ProxyEndpoints(handler, handler, handler, handler), new UsageTracker(),
                    new ApiKeyStore(Map.of(), null, null), new RequestLogger(false, temp));
            proxy.getApp().start(0);
            try {
                for (String path : List.of("/v1/chat/completions", "/v1/responses", "/v1/messages")) {
                    var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxy.getApp().port() + path))
                            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"same\"}")).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(502, response.statusCode(), response.body());
                    assertEquals("upstream_redirect_rejected", response.headers().firstValue("X-AIProxy-Error-Category").orElseThrow());
                    assertTrue(response.headers().firstValue("X-Request-Id").orElseThrow().startsWith("aiproxy_"));
                    var error = Json.MAPPER.readTree(response.body()).path("error");
                    assertEquals(path.equals("/v1/messages") ? "api_error" : "upstream_error", error.path("type").asString());
                    assertFalse(response.body().contains("private"));
                    assertTrue(response.headers().firstValue("Location").isEmpty());
                }
                assertEquals(3, hits.get());
                assertEquals(0, redirectedHits.get());
                assertEquals(0, fallback.get());
            } finally { proxy.stop(); }
        } finally { upstream.stop(0); }
    }

    @Test void automaticRedirectsAreRejectedBeforeAnyNetworkRequest() throws Exception {
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var request = HttpRequest.newBuilder(URI.create("https://not-contacted.invalid/")).build();
            assertThrows(InferenceTransport.RedirectRejectedException.class, () -> InferenceTransport.send(client, request));
        }
    }
}

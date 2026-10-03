package com.aiproxy.server;

import com.aiproxy.model.ProviderModel;
import com.aiproxy.provider.ProviderId;
import com.aiproxy.routing.ModelRoute;
import io.javalin.Javalin;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InferenceDispatchHandlerTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "/v1/chat/completions, CHAT_COMPLETIONS",
            "/v1/responses, RESPONSES",
            "/alternate-endpoint, RESPONSES"
    })
    void checksCapabilitiesForTheRequestedApiBeforeExecuting(String path, InferenceApi requestedApi) throws Exception {
        var observed = new java.util.concurrent.atomic.AtomicReference<InferenceApi>();
        AtomicInteger executions = new AtomicInteger();
        InferenceBackend backend = new InferenceBackend() {
            public boolean supports(tools.jackson.databind.JsonNode body, ModelRoute route, InferenceApi api) {
                observed.set(api);
                return true;
            }
            public void handle(io.javalin.http.Context ctx, ModelRoute route, InferenceApi api) {
                executions.incrementAndGet();
                ctx.result("selected");
            }
        };
        var dispatch = new InferenceDispatchHandler(() -> List.of(model(ProviderId.COPILOT)),
                ProviderId.COPILOT, "same", Map.of(ProviderId.COPILOT, backend), ProviderId.defaultOrder(), true, requestedApi);
        Javalin app = Javalin.create(c -> c.routes.post(path, dispatch)).start("127.0.0.1", 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"same\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("selected", response.body());
            assertEquals(requestedApi, observed.get());
            assertEquals(1, executions.get());
        } finally { app.stop(); }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"connection", "timeout"})
    void retriesConnectionFailuresAndTimeoutsBeforeCommitment(String kind) throws Exception {
        AtomicInteger fallback = new AtomicInteger();
        var backends = Map.<ProviderId, InferenceBackend>of(ProviderId.COPILOT, (ctx, route, api) -> {
            if (kind.equals("connection")) throw new java.net.ConnectException();
            throw new java.net.http.HttpTimeoutException("timeout");
        }, ProviderId.CODEX, (ctx, route, api) -> { fallback.incrementAndGet(); ctx.result("ok"); });
        var handler = new InferenceDispatchHandler(() -> List.of(model(ProviderId.COPILOT), model(ProviderId.CODEX)),
                ProviderId.COPILOT, "same", backends, ProviderId.defaultOrder(), true, InferenceApi.CHAT_COMPLETIONS);
        Javalin app = Javalin.create(c -> c.routes.post("/v1/chat/completions", handler)).start("127.0.0.1", 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertEquals("ok", post(http, app.port(), "{\"model\":\"same\"}").body());
            assertEquals(1, fallback.get());
        } finally { app.stop(); }
    }
    @Test void doesNotAssumeImageCapabilityInFallbackCatalog() throws Exception {
        AtomicInteger fallback = new AtomicInteger();
        InferenceBackend capable = new InferenceBackend() {
            public boolean supports(tools.jackson.databind.JsonNode body, ModelRoute route, InferenceApi api) { return true; }
            public void handle(io.javalin.http.Context ctx, ModelRoute route, InferenceApi api) throws Exception { throw new UpstreamFailure(503); }
        };
        var handler = new InferenceDispatchHandler(() -> List.of(model(ProviderId.COPILOT), model(ProviderId.CODEX)),
                ProviderId.COPILOT, "same", Map.of(ProviderId.COPILOT, capable, ProviderId.CODEX,
                (InferenceBackend) (ctx, route, api) -> { fallback.incrementAndGet(); ctx.result("fallback"); }), ProviderId.defaultOrder(), true, InferenceApi.CHAT_COMPLETIONS);
        Javalin app = Javalin.create(c -> c.routes.post("/v1/chat/completions", handler)).start("127.0.0.1", 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertEquals(503, post(http, app.port(), "{\"model\":\"same\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AQID\"}}]}]}").statusCode());
            assertEquals(0, fallback.get());
        } finally { app.stop(); }
    }
    @Test void neverRetriesAfterWritingAResponseAndSkipsUnsupportedCandidates() throws Exception {
        AtomicInteger fallback = new AtomicInteger();
        InferenceBackend first = (ctx, route, api) -> {
            ctx.res().getOutputStream().write("started".getBytes()); ctx.res().getOutputStream().flush();
            throw new UpstreamFailure(503);
        };
        var backends = Map.<ProviderId, InferenceBackend>of(ProviderId.COPILOT, first,
                ProviderId.CODEX, (ctx, route, api) -> { fallback.incrementAndGet(); ctx.result("fallback"); });
        var handler = new InferenceDispatchHandler(() -> List.of(model(ProviderId.COPILOT), model(ProviderId.CODEX)),
                ProviderId.COPILOT, "same", backends, ProviderId.defaultOrder(), true, InferenceApi.CHAT_COMPLETIONS);
        Javalin app = Javalin.create(c -> c.routes.post("/v1/chat/completions", handler)).start("127.0.0.1", 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertEquals("started", post(http, app.port(), "{\"model\":\"same\"}").body());
            assertEquals(0, fallback.get());
        } finally { app.stop(); }
    }
    @Test void pinsCompletedResponsesAcrossCatalogChanges() throws Exception {
        var models = new java.util.concurrent.atomic.AtomicReference<>(List.of(model(ProviderId.COPILOT), model(ProviderId.CODEX)));
        AtomicInteger copilot = new AtomicInteger(), codex = new AtomicInteger();
        var backends = Map.<ProviderId, InferenceBackend>of(
                ProviderId.COPILOT, (ctx, route, api) -> { copilot.incrementAndGet(); ctx.attribute("completedResponse",
                        com.aiproxy.util.Json.MAPPER.readTree("{\"id\":\"resp_one\",\"output\":[]}")); ctx.result("copilot"); },
                ProviderId.CODEX, (ctx, route, api) -> { codex.incrementAndGet(); ctx.result("codex"); });
        var handler = new InferenceDispatchHandler(models::get, ProviderId.COPILOT, "same", backends, ProviderId.defaultOrder(), true, InferenceApi.CHAT_COMPLETIONS);
        Javalin app = Javalin.create(c -> c.routes.post("/v1/chat/completions", handler)).start("127.0.0.1", 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertEquals("copilot", post(http, app.port(), "{\"model\":\"same\"}").body());
            models.set(List.of(model(ProviderId.CODEX)));
            assertEquals("copilot", post(http, app.port(), "{\"model\":\"same\",\"previous_response_id\":\"resp_one\"}").body());
            assertEquals(2, copilot.get()); assertEquals(0, codex.get());
            assertEquals(400, post(http, app.port(), "{\"model\":\"codex/same\",\"previous_response_id\":\"resp_one\"}").statusCode());
            assertEquals(400, post(http, app.port(), "{\"model\":\"same\",\"previous_response_id\":\"resp_copilot_evicted\"}").statusCode());
        } finally { app.stop(); }
    }
    @Test void retriesExactModelInOrderButNeverAuthQualifiedCommittedOrReplayRequests() throws Exception {
        var models = List.of(model(ProviderId.COPILOT), model(ProviderId.CODEX));
        AtomicInteger status = new AtomicInteger(429), attempts = new AtomicInteger();
        var backend = Map.<ProviderId, InferenceBackend>of(
                ProviderId.COPILOT, (ctx, route, api) -> { attempts.incrementAndGet(); throw new UpstreamFailure(status.get()); },
                ProviderId.CODEX, (ctx, route, api) -> { attempts.incrementAndGet(); ctx.result("codex"); });
        var dispatch = new InferenceDispatchHandler(() -> models, ProviderId.COPILOT, "same", backend, ProviderId.defaultOrder(), true, InferenceApi.CHAT_COMPLETIONS);
        Javalin app = Javalin.create(c -> c.routes.post("/v1/chat/completions", dispatch)).start("127.0.0.1", 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            var result = post(http, app.port(), "{\"model\":\"same\"}");
            assertEquals(200, result.statusCode()); assertEquals("codex", result.body()); assertEquals(2, attempts.get());
            attempts.set(0); status.set(403);
            assertEquals(403, post(http, app.port(), "{\"model\":\"same\"}").statusCode()); assertEquals(1, attempts.get());
            attempts.set(0); status.set(503);
            assertEquals(503, post(http, app.port(), "{\"model\":\"copilot/same\"}").statusCode()); assertEquals(1, attempts.get());
            attempts.set(0);
            assertEquals(400, post(http, app.port(), "{\"model\":\"same\",\"previous_response_id\":\"missing\"}").statusCode()); assertEquals(0, attempts.get());
        } finally { app.stop(); }
    }
    private static ProviderModel model(ProviderId id) { return new ProviderModel("same", "same", id, List.of(), Optional.of(true), 1000); }
    private static HttpResponse<String> post(HttpClient http, int port, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}

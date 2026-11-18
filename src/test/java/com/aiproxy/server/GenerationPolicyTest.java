package com.aiproxy.server;

import com.aiproxy.model.*;
import com.aiproxy.provider.ProviderId;
import com.aiproxy.util.Json;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationPolicyTest {
    @Test void explicitCapabilitiesAreCheckedForQualifiedRequests() throws Exception {
        var anthropic = model(ProviderId.ANTHROPIC, 100, List.of("low"), Map.of("adaptive_thinking", false));
        for (String fields : List.of("\"temperature\":1.5", "\"max_tokens\":101", "\"reasoning_effort\":\"low\"")) {
            var calls = new AtomicInteger();
            var response = post(List.of(anthropic), Map.of(ProviderId.ANTHROPIC,
                    (ctx, route, api) -> { calls.incrementAndGet(); ctx.result("ok"); }),
                    "{\"model\":\"anthropic/same\"," + fields + "}", false);
            assertEquals(400, response.statusCode(), response.body());
            assertEquals(0, calls.get());
            assertTrue(Json.MAPPER.readTree(response.body()).at("/error/param").isString());
        }
    }

    @Test void failoverDoesNotSelectCodexForExplicitTokenBudget() throws Exception {
        var calls = new AtomicInteger();
        var response = post(List.of(model(ProviderId.COPILOT, 200, List.of(), Map.of()),
                        model(ProviderId.CODEX, 200, List.of(), Map.of())),
                Map.of(ProviderId.COPILOT, (ctx, route, api) -> { throw new UpstreamFailure(503); },
                        ProviderId.CODEX, (ctx, route, api) -> { calls.incrementAndGet(); ctx.result("lost budget"); }),
                "{\"model\":\"same\",\"max_tokens\":100}", true);
        assertEquals(503, response.statusCode());
        assertEquals(0, calls.get());
    }

    @Test void failoverRejectsUnknownSamplingSupportButPrimaryCanDelegateToUpstream() throws Exception {
        var calls = new AtomicInteger();
        var models = List.of(model(ProviderId.COPILOT, 0, List.of(), Map.of("temperature", true)),
                model(ProviderId.CODEX, 0, List.of(), Map.of()));
        var response = post(models,
                Map.of(ProviderId.COPILOT, (ctx, route, api) -> { throw new UpstreamFailure(503); },
                        ProviderId.CODEX, (ctx, route, api) -> { calls.incrementAndGet(); ctx.result("unknown capability"); }),
                "{\"model\":\"same\",\"temperature\":0.5}", true);
        assertEquals(503, response.statusCode());
        assertEquals(0, calls.get());
    }

    @Test void compatibleFallbackReceivesUnchangedExplicitSettings() throws Exception {
        var first = model(ProviderId.COPILOT, 200, List.of("high"), Map.of("temperature", true));
        var second = model(ProviderId.ANTHROPIC, 200, List.of(), Map.of("temperature", true));
        String body = "{\"model\":\"same\",\"temperature\":0.5,\"max_tokens\":100}";
        var response = post(List.of(first, second), Map.of(
                ProviderId.COPILOT, (ctx, route, api) -> { throw new UpstreamFailure(503); },
                ProviderId.ANTHROPIC, (ctx, route, api) -> ctx.result(ctx.body())), body, true);
        assertEquals(200, response.statusCode());
        assertEquals(body, response.body());
    }

    @Test void supportedEffortIsAcceptedAndKnownUnsupportedEffortIsRejected() throws Exception {
        var codex = model(ProviderId.CODEX, 0, List.of("xhigh"), Map.of());
        var valid = Json.MAPPER.readTree("{\"reasoning_effort\":\"xhigh\"}");
        assertDoesNotThrow(() -> ModelGenerationPolicy.validate(valid, codex, InferenceApi.CHAT_COMPLETIONS, false));
        var invalid = Json.MAPPER.readTree("{\"reasoning_effort\":\"none\"}");
        assertThrows(com.aiproxy.provider.spi.GenerationParameters.ParameterException.class,
                () -> ModelGenerationPolicy.validate(invalid, codex, InferenceApi.CHAT_COMPLETIONS, false));
    }

    @Test void defaultTokenBudgetIsBoundedByModelAndDiscoveryDoesNotAdvertiseCodexLimits() throws Exception {
        var anthropic = model(ProviderId.ANTHROPIC, 128, List.of("high"), Map.of("adaptive_thinking", true));
        assertEquals(128, ModelGenerationPolicy.defaultMessagesTokens(anthropic));
        assertEquals(4096, ModelGenerationPolicy.defaultMessagesTokens(model(ProviderId.ANTHROPIC, 0, List.of(), Map.of())));
        var codex = model(ProviderId.CODEX, 128, List.of("high"), Map.of());
        var context = org.mockito.Mockito.mock(io.javalin.http.Context.class);
        new ModelsHandler(() -> List.of(codex, anthropic)).handle(context);
        var captured = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(context).result(captured.capture());
        var data = Json.MAPPER.readTree(captured.getValue()).path("data");
        assertFalse(data.get(0).path("supported_parameters").toString().contains("max_tokens"));
        assertTrue(data.get(1).path("supported_parameters").toString().contains("max_tokens"));
        assertTrue(data.get(1).path("supported_parameters").toString().contains("reasoning_effort"));
    }

    private static ProviderModel model(ProviderId provider, int max, List<String> efforts, Map<String, Boolean> capabilities) {
        return new ProviderModel("same", "same", provider, List.of(), Optional.of(true), 1000,
                new ModelMetadata(1000, max, List.of(), capabilities, efforts,
                        provider == ProviderId.COPILOT ? List.of("/chat/completions") : List.of(), "test", null, null));
    }

    private static HttpResponse<String> post(List<ProviderModel> models, Map<ProviderId, InferenceBackend> backends,
                                              String body, boolean failover) throws Exception {
        var handler = new InferenceDispatchHandler(() -> models, ProviderId.COPILOT, "same", backends,
                ProviderId.defaultOrder(), failover, InferenceApi.CHAT_COMPLETIONS);
        var app = Javalin.create(c -> c.routes.post("/v1/chat/completions", handler)).start("127.0.0.1", 0);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/v1/chat/completions"))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        } finally { app.stop(); }
    }
}

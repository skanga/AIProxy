package com.aiproxy.model;

import com.aiproxy.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxy.provider.anthropic.AnthropicHttpClient;
import com.aiproxy.provider.anthropic.model.AnthropicModelCatalog;
import com.aiproxy.provider.codex.CodexHttpClient;
import com.aiproxy.provider.codex.model.CodexModelCatalog;
import com.aiproxy.provider.codex.model.CodexModelResolver;
import com.aiproxy.provider.copilot.CopilotHttpClient;
import com.aiproxy.provider.copilot.model.CopilotModelCatalog;
import com.aiproxy.server.ModelsHandler;
import com.aiproxy.util.Json;
import io.javalin.http.Context;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelMetadataTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void copilotExposesAdvertisedMetadataWithoutLeakingRawCatalog() throws Exception {
        CopilotHttpClient client = mock(CopilotHttpClient.class);
        when(client.identity()).thenReturn("test-account");
        when(client.models()).thenReturn(Json.MAPPER.readTree("""
                {"data":[{"id":"test","name":"Test Model","secret":"must-not-leak",
                  "supported_endpoints":["/responses","/v1/messages"],
                  "capabilities":{"type":"chat","limits":{"max_context_window_tokens":128000,
                    "max_prompt_tokens":120000,"max_output_tokens":8192},
                    "supports":{"tool_calls":true,"vision":true,"streaming":true,
                      "reasoning_effort":["low","high"]}}}]}
                """));
        var catalog = new CopilotModelCatalog(client, List.of(), CLOCK);
        JsonNode model = render(catalog).path("data").get(0);
        assertEquals("copilot/test", model.path("id").asString());
        assertEquals("Test Model", model.path("name").asString());
        assertEquals(128000, model.path("context_length").asInt());
        assertEquals(8192, model.path("top_provider").path("max_completion_tokens").asInt());
        assertEquals(Json.MAPPER.readTree("[\"text\",\"image\"]"), model.path("architecture").path("input_modalities"));
        assertTrue(model.path("supported_parameters").toString().contains("tools"));
        assertEquals("copilot", model.path("aiproxy").path("provider").asString());
        assertEquals("copilot/test", model.path("aiproxy").path("qualified_id").asString());
        assertEquals(120000, model.path("aiproxy").path("max_input_tokens").asInt());
        assertEquals("upstream", model.path("aiproxy").path("metadata_source").asString());
        assertEquals(CLOCK.instant().toString(), model.path("aiproxy").path("fetched_at").asString());
        assertEquals(Json.MAPPER.readTree("[\"low\",\"high\"]"), model.path("aiproxy").path("reasoning_efforts"));
        assertEquals(Json.MAPPER.readTree("[\"/responses\",\"/v1/messages\"]"), model.path("aiproxy").path("upstream_endpoints"));
        assertFalse(model.toString().contains("must-not-leak"));
        assertEquals(model, render(catalog).path("data").get(0));
        verify(client, times(1)).models();
    }

    @Test
    @SuppressWarnings("unchecked")
    void codexRetainsMetadataAlongsideCachedIdsAndNativeAllowlist() throws Exception {
        CodexHttpClient client = mock(CodexHttpClient.class);
        when(client.isNative()).thenReturn(true);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"models":[{"slug":"test","display_name":"Codex Test","visibility":"list",
                 "context_window":64000,"max_context_window":128000,"input_modalities":["text","image"],
                 "supported_reasoning_levels":[{"effort":"low"},{"effort":"high"}],
                 "base_instructions":"private instructions"},
                 {"slug":"hidden","visibility":"hide","context_window":999},
                 {"slug":"excluded","visibility":"list","context_window":999}]}
                """);
        when(client.requestString(eq("/models"), eq("GET"), isNull(), isNull())).thenReturn(response);
        var resolver = new CodexModelResolver(client, List.of("test", "hidden"), "test-version");
        var catalog = new CodexModelCatalog(resolver);
        JsonNode models = render(catalog).path("data");
        assertEquals(1, models.size());
        assertEquals(64000, models.get(0).path("context_length").asInt());
        assertEquals("Codex Test", models.get(0).path("name").asString());
        assertEquals("native", models.get(0).path("aiproxy").path("auth_profile").asString());
        assertEquals(Json.MAPPER.readTree("[\"low\",\"high\"]"), models.get(0).path("aiproxy").path("reasoning_efforts"));
        assertFalse(models.toString().contains("private instructions"));
        assertFalse(models.get(0).path("top_provider").has("max_completion_tokens"));
        assertEquals(List.of("test"), resolver.resolveModels());
        assertEquals(models, render(catalog).path("data"));
        verify(client, times(1)).requestString(anyString(), anyString(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anthropicUsesReportedLimitsAndPreservesUnknowns() throws Exception {
        AnthropicHttpClient client = mock(AnthropicHttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream("""
                {"data":[{"id":"claude-test","display_name":"Claude Test",
                  "max_input_tokens":1000000,"max_tokens":64000,
                  "capabilities":{"image_input":{"supported":true},"thinking":{"supported":true},
                    "effort":{"supported":true,"low":{"supported":true},"high":{"supported":false}}}},
                  {"id":"claude-unknown"}]}
                """.getBytes(StandardCharsets.UTF_8)));
        when(client.request(any(URI.class), eq("GET"), isNull(), anyMap(), any())).thenReturn(response);
        var catalog = new AnthropicModelCatalog(client, AnthropicCompatibilityProfile.claudeCodeOAuth(), List.of(), CLOCK);
        JsonNode models = render(catalog).path("data");
        assertEquals(1000000, models.get(0).path("context_length").asInt());
        assertEquals(64000, models.get(0).path("top_provider").path("max_completion_tokens").asInt());
        assertEquals(Json.MAPPER.readTree("[\"low\"]"), models.get(0).path("aiproxy").path("reasoning_efforts"));
        assertFalse(models.get(0).path("supported_parameters").toString().contains("reasoning_effort"));
        assertFalse(models.get(1).has("context_length"));
        assertFalse(models.get(1).has("supported_parameters"));
        assertEquals(0, catalog.resolveModels().get(1).contextWindow());
    }

    @Test
    void malformedAndMissingOptionalMetadataDoesNotInventLimitsOrBreakDiscovery() throws Exception {
        CopilotHttpClient client = mock(CopilotHttpClient.class);
        when(client.identity()).thenReturn("test-account");
        when(client.models()).thenReturn(Json.MAPPER.readTree("""
                {"data":[{"id":"unknown","capabilities":{"limits":{
                  "max_context_window_tokens":"128000","max_output_tokens":-1},
                  "supports":{"vision":"true","reasoning_effort":[null,12,""]}}},
                  {"id":"overflow","capabilities":{"limits":{"max_context_window_tokens":9999999999999}}}]}
                """));
        JsonNode models = render(new CopilotModelCatalog(client, List.of(), CLOCK)).path("data");
        for (JsonNode model : models) {
            assertFalse(model.has("context_length"));
            assertFalse(model.has("top_provider"));
            assertFalse(model.has("architecture"));
            assertFalse(model.has("supported_parameters"));
        }
    }

    @Test
    void configuredModelsDoNotPretendTheirLimitsWereDiscovered() throws Exception {
        var codex = new CodexModelCatalog(new CodexModelResolver(mock(CodexHttpClient.class), List.of("custom"), "version"));
        JsonNode model = render(codex).path("data").get(0);
        assertFalse(model.has("context_length"));
        assertEquals("configured", model.path("aiproxy").path("metadata_source").asString());
        assertFalse(model.path("aiproxy").has("fetched_at"));
    }

    @Test
    void copilotLastGoodMetadataKeepsTimestampAndIsDiscardedOnAccountChange() throws Exception {
        CopilotHttpClient client = mock(CopilotHttpClient.class);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(CLOCK.instant());
        when(client.identity()).thenReturn("first-account");
        when(client.models()).thenReturn(Json.MAPPER.readTree("""
                {"data":[{"id":"shared","capabilities":{"limits":{"max_context_window_tokens":32000}}}]}
                """));
        var catalog = new CopilotModelCatalog(client, List.of(), clock);
        var first = catalog.resolveModels();
        when(clock.instant()).thenReturn(CLOCK.instant().plusSeconds(301));
        when(client.models()).thenThrow(new java.io.IOException("unavailable"));
        assertEquals(first, catalog.resolveModels());
        assertEquals(CopilotModelCatalog.Source.LAST_GOOD, catalog.source());
        when(client.identity()).thenReturn("second-account");
        assertThrows(java.io.IOException.class, catalog::resolveModels);
    }

    @Test
    void collidingModelsKeepProviderSpecificLimits() throws Exception {
        var first = new com.aiproxy.model.ProviderModel("shared", "First",
                com.aiproxy.provider.ProviderId.CODEX, List.of(), java.util.Optional.empty(), 32000);
        var second = new com.aiproxy.model.ProviderModel("shared", "Second",
                com.aiproxy.provider.ProviderId.COPILOT, List.of(), java.util.Optional.empty(), 64000);
        JsonNode data = render(() -> List.of(first, second)).path("data");
        assertEquals("codex/shared", data.get(0).path("id").asString());
        assertEquals(32000, data.get(0).path("context_length").asInt());
        assertEquals("copilot/shared", data.get(1).path("id").asString());
        assertEquals(64000, data.get(1).path("context_length").asInt());
    }

    private static JsonNode render(ModelCatalog catalog) throws Exception {
        Context context = mock(Context.class);
        new ModelsHandler(catalog).handle(context);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(context).status(200);
        verify(context).result(body.capture());
        return Json.MAPPER.readTree(body.getValue());
    }
}

package com.aiproxyoauth.server;

import com.aiproxyoauth.model.ModelResolver;
import com.aiproxyoauth.usage.UsageTracker;
import tools.jackson.databind.JsonNode;
import com.aiproxyoauth.util.Json;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HandlersTest {

    @Mock Context ctx;
    @Mock ModelResolver modelResolver;
    @Mock UsageTracker usageTracker;

    @Test
    void healthHandler_returnsSafeStatusFields() throws Exception {
        HealthHandler handler = new HealthHandler(() -> 5_500_000_000L, 1_000_000_000L);
        handler.handle(ctx);

        verify(ctx).status(200);
        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        verify(ctx).result(resultCaptor.capture());

        JsonNode node = Json.MAPPER.readTree(resultCaptor.getValue());
        assertTrue(node.path("ok").asBoolean());
        assertEquals("AIProxyOauth", node.path("service").asString());
        assertEquals(4, node.path("uptime_seconds").asLong());
        assertEquals("4.2", node.path("version").asString());
        assertFalse(node.has("auth_file"));
        assertFalse(node.has("api_keys"));
        assertFalse(node.has("models"));
        assertFalse(node.has("upstream"));
    }

    @Test
    void modelsHandler_returnsModelList() throws Exception {
        when(modelResolver.resolveProviderModels()).thenReturn(List.of(new com.aiproxyoauth.provider.ProviderModel(
                "gpt-5", "gpt-5", com.aiproxyoauth.provider.ProviderId.CODEX, List.of(), java.util.Optional.empty(), 0)));
        ModelsHandler handler = new ModelsHandler(modelResolver);
        
        handler.handle(ctx);

        verify(ctx).status(200);
        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        verify(ctx).result(resultCaptor.capture());
        
        JsonNode node = Json.MAPPER.readTree(resultCaptor.getValue());
        assertEquals("list", node.get("object").asString());
        assertEquals("gpt-5", node.get("data").get(0).get("id").asString());
    }

    @Test
    void usageHandler_returnsUsageStats() throws Exception {
        UsageTracker tracker = new UsageTracker();
        tracker.record("test-key", 10, 5);
        UsageHandler handler = new UsageHandler(tracker);
        
        handler.handle(ctx);

        verify(ctx).status(200);
        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        verify(ctx).result(resultCaptor.capture());
        
        JsonNode node = Json.MAPPER.readTree(resultCaptor.getValue());
        assertEquals(10, node.get("total").get("prompt_tokens").asInt());
        assertEquals(5, node.get("total").get("completion_tokens").asInt());
        assertEquals(15, node.get("total").get("total_tokens").asInt());
    }
}

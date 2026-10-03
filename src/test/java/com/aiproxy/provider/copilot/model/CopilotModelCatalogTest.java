package com.aiproxy.provider.copilot.model;

import com.aiproxy.protocol.chat.ChatRequestDecoder;
import com.aiproxy.provider.copilot.CopilotHttpClient;
import com.aiproxy.util.Json;
import java.io.IOException;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CopilotModelCatalogTest {
    @Test void messagesGateUsesBoundedAccountCacheAndHonorsAllowlist() throws Exception {
        CopilotHttpClient client = mock();
        Clock clock = mock();
        Instant start = Instant.parse("2026-09-30T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        when(client.identity()).thenReturn("account1");
        when(client.models()).thenReturn(Json.MAPPER.readTree("""
                {"data":[{"id":"allowed","supported_endpoints":["/v1/messages"]},
                {"id":"excluded","supported_endpoints":["/v1/messages"]}]}
                """));
        var catalog = new CopilotModelCatalog(client, List.of("allowed"), clock);
        catalog.requireMessagesEndpoint("allowed");
        catalog.requireMessagesEndpoint("allowed");
        verify(client, times(1)).models();
        assertThrows(IllegalArgumentException.class, () -> catalog.requireMessagesEndpoint("excluded"));
        when(client.models()).thenThrow(new IOException("offline"));
        when(clock.instant()).thenReturn(start.plusSeconds(301));
        catalog.requireMessagesEndpoint("allowed");
        when(clock.instant()).thenReturn(start.plusSeconds(3601));
        assertThrows(IOException.class, () -> catalog.requireMessagesEndpoint("allowed"));
        when(clock.instant()).thenReturn(start.plusSeconds(302));
        when(client.identity()).thenReturn("account2");
        assertThrows(IOException.class, () -> catalog.requireMessagesEndpoint("allowed"));
    }
    @Test void expiresStaleCatalogAndNeverCarriesItAcrossAccounts() throws Exception {
        CopilotHttpClient client = mock(CopilotHttpClient.class); Clock clock = mock(Clock.class);
        Instant start = Instant.parse("2026-09-23T00:00:00Z");
        when(clock.instant()).thenReturn(start); when(client.identity()).thenReturn("account1");
        when(client.models()).thenReturn(Json.MAPPER.readTree("{\"data\":[{\"id\":\"test\"}]}"));
        var catalog = new CopilotModelCatalog(client, List.of(), clock);
        assertEquals(1, catalog.resolveModels().size());
        when(client.models()).thenThrow(new IOException("offline"));
        when(clock.instant()).thenReturn(start.plusSeconds(301));
        assertEquals(1, catalog.resolveModels().size());
        when(clock.instant()).thenReturn(start.plusSeconds(3601));
        assertThrows(IOException.class, catalog::resolveModels);
        when(clock.instant()).thenReturn(start.plusSeconds(302)); when(client.identity()).thenReturn("account2");
        assertThrows(IOException.class, catalog::resolveModels);
    }
    @Test void rejectsImagesExceedingAdvertisedLimitsAndNonStreamingModels() throws Exception {
        CopilotHttpClient client = mock(CopilotHttpClient.class); when(client.identity()).thenReturn("account");
        when(client.models()).thenReturn(Json.MAPPER.readTree("""
                {"data":[{"id":"test","capabilities":{"supports":{"vision":true,"streaming":true},
                "limits":{"vision":{"max_prompt_images":1,"max_prompt_image_size":1,"supported_media_types":["image/png"]}}}},
                {"id":"sync","capabilities":{"supports":{"streaming":false}}}]}
                """));
        var catalog = new CopilotModelCatalog(client, List.of(), Clock.systemUTC());
        var adapter = new ChatRequestDecoder();
        var image = adapter.decode(Json.MAPPER.readTree("""
                {"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AQID"}}]}]}
                """), "test");
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(image));
        var request = adapter.decode(Json.MAPPER.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"), "sync");
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(request));
    }
}

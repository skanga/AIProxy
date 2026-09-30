package com.aiproxyoauth;

import com.aiproxyoauth.config.ServerConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StartupProbeSelectionTest {
    @ParameterizedTest
    @CsvSource({
            "opus|copilot/LUNA-one|claude-haiku|last, copilot/LUNA-one",
            "sonnet|claude-HAIKU-one|luna-two|last, claude-HAIKU-one",
            "codex/first|codex/middle|codex/last, codex/last",
            "only, only"
    })
    void bothProbeEndpointsUseFirstLunaOrHaikuOtherwiseLast(String catalog, String expected) throws Exception {
        var app = new AIProxyOauth();
        var config = new ServerConfig("127.0.0.1", 10531, null, null,
                "http://base", null, null, null, "", false, Map.of(), null);
        List<String> models = List.of(catalog.split("\\|"));
        HttpClient http = mock();
        HttpResponse<String> response = mock();
        when(http.<String>send(any(), any())).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");
        var chat = app.verifyChatCompletionThroughProxy(config, models, null, http);
        assertTrue(chat.success());
        assertEquals(expected, chat.model());

        when(response.body()).thenReturn("{\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":\"OK\"}]}");
        var anthropic = app.verifyAnthropicThroughProxy(config, models, null, http);
        assertTrue(anthropic.success());
        assertEquals(expected, anthropic.model());
        verify(http, times(2)).send(any(), any());
    }
}

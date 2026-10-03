package com.aiproxy.server;

import com.aiproxy.bootstrap.ProviderAssembly;
import com.aiproxy.config.ServerConfig;
import com.aiproxy.model.ProviderModel;
import com.aiproxy.provider.ProviderId;
import com.aiproxy.provider.codex.CodexHttpClient;
import com.aiproxy.usage.UsageTracker;
import com.aiproxy.util.Json;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoutedCodexAliasesTest {
    @ParameterizedTest
    @CsvSource({
            "chat/completions,gpt-5.3-codex-spark-xhigh,xhigh",
            "chat/completions,codex/gpt-5.3-codex-spark-xhigh,xhigh",
            "responses,gpt-5.3-codex-spark-xhigh,xhigh",
            "responses,codex/gpt-5.3-codex-spark-xhigh,xhigh"
    })
    void aliasesResolveAfterProviderSelectionAndExplicitEffortWins(String endpoint, String alias, String effort) throws Exception {
        CodexHttpClient upstream = mock(CodexHttpClient.class);
        AtomicReference<String> sent = new AtomicReference<>();
        when(upstream.request(anyString(), anyString(), anyString(), anyMap())).thenAnswer(call -> {
            sent.set(call.getArgument(2));
            @SuppressWarnings("unchecked") HttpResponse<InputStream> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(new ByteArrayInputStream(("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_test\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n").getBytes(StandardCharsets.UTF_8)));
            return response;
        });
        ServerConfig config = new ServerConfig("127.0.0.1", 10531, null, null, null, null, null, null, "", false, Map.of(), null);
        ProxyServer server = ProviderAssembly.create(config, upstream,
                () -> List.of(new ProviderModel("gpt-5.3-codex-spark", "spark", ProviderId.CODEX, List.of(), Optional.empty(), 0)),
                new UsageTracker(), new ApiKeyStore(Map.of(), null, null));
        server.getApp().start("127.0.0.1", 0);
        try (HttpClient http = HttpClient.newHttpClient()) {
            for (boolean explicit : List.of(false, true)) {
                var body = Json.MAPPER.createObjectNode().put("model", alias);
                if (endpoint.equals("responses")) {
                    body.put("input", "hi");
                    if (explicit) body.putObject("reasoning").put("effort", "low");
                } else {
                    body.putArray("messages").addObject().put("role", "user").put("content", "hi");
                    if (explicit) body.put("reasoning_effort", "low");
                }
                var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getApp().port() + "/v1/" + endpoint))
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                var wire = Json.MAPPER.readTree(sent.get());
                assertEquals("gpt-5.3-codex-spark", wire.path("model").asString());
                assertEquals(explicit ? "low" : effort, wire.at("/reasoning/effort").asString());
            }
        } finally { server.stop(); }
    }
}

package com.aiproxy.server;

import com.aiproxy.logging.RequestLogger;
import com.aiproxy.model.ProviderModel;
import com.aiproxy.provider.ProviderId;
import com.aiproxy.provider.anthropic.*;
import com.aiproxy.provider.copilot.*;
import com.aiproxy.provider.copilot.model.CopilotModelCatalog;
import com.aiproxy.usage.UsageTracker;
import com.aiproxy.util.Json;
import io.javalin.Javalin;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeMessagesHeaderTimeoutTest {
    @TempDir Path temp;
    @ParameterizedTest @ValueSource(strings = {"anthropic:headers", "anthropic:connect", "anthropic:io", "copilot:headers", "copilot:connect", "copilot:io"})
    void distinguishesTimeoutsFromOtherIoBeforeHeaders(String scenario) throws Exception {
        boolean anthropic = scenario.startsWith("anthropic");
        boolean timeout = !scenario.endsWith(":io");
        IOException error = scenario.endsWith(":connect") ? new HttpConnectTimeoutException("private details")
                : timeout ? new HttpTimeoutException("private details") : new IOException("private details");
        var usage = new UsageTracker();
        var anthropicClient = mock(AnthropicHttpClient.class);
        when(anthropicClient.request(any(URI.class), eq("POST"), anyString(), any(AnthropicRequestOptions.class), anyString())).thenThrow(error);
        var anthropicBackend = new AnthropicMessagesBackend(anthropicClient, AnthropicCompatibilityProfile.claudeCodeOAuth(),
                () -> List.of(new ProviderModel("claude-test", "Claude", ProviderId.ANTHROPIC, List.of(), Optional.empty(), 0)),
                usage, new RequestLogger(false, temp));
        var copilot = mock(CopilotHttpClient.class);
        when(copilot.messages(any(), anyString(), nullable(String.class))).thenThrow(error);
        var copilotBackend = new CopilotMessagesBackend(copilot, mock(CopilotModelCatalog.class), usage);
        var app = Javalin.create(config -> config.routes.post("/v1/messages", ctx -> {
            if (anthropic) anthropicBackend.handle(ctx);
            else copilotBackend.handle(ctx, (tools.jackson.databind.node.ObjectNode) Json.MAPPER.readTree(ctx.body()));
        })).start("127.0.0.1", 0);
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/v1/messages"))
                    .header("anthropic-version", "2023-06-01").POST(HttpRequest.BodyPublishers.ofString(
                            "{\"model\":\"" + (anthropic ? "anthropic/claude-test" : "copilot/claude-test")
                                    + "\",\"max_tokens\":32,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}" )).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(timeout ? 504 : 502, response.statusCode(), response.body());
            assertEquals("api_error", Json.MAPPER.readTree(response.body()).path("error").path("type").asString());
            assertFalse(response.body().contains("private details"));
            if (timeout) assertEquals("upstream_timeout", response.headers().firstValue("X-AIProxy-Error-Category").orElseThrow());
        } finally { app.stop(); }
    }
}

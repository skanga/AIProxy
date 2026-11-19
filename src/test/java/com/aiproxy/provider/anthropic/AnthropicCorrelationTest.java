package com.aiproxy.provider.anthropic;

import com.aiproxy.logging.RequestLogger;
import com.aiproxy.model.ProviderModel;
import com.aiproxy.provider.ProviderId;
import com.aiproxy.provider.anthropic.auth.AnthropicAuthManager;
import com.aiproxy.routing.ModelRoute;
import com.aiproxy.server.AccessLogFields;
import com.aiproxy.usage.UsageTracker;
import io.javalin.Javalin;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnthropicCorrelationTest {
    @ParameterizedTest @ValueSource(strings = {"/v1/chat/completions", "/v1/responses", "/v1/messages"})
    void backendAndTransportUseOneIdIncludingAuthRetry(String path) throws Exception {
        var logger = mock(RequestLogger.class);
        when(logger.nextRequestId()).thenReturn("unrelated-id");
        var auth = mock(AnthropicAuthManager.class);
        when(auth.accessToken()).thenReturn("old", "new");
        var http = mock(HttpClient.class);
        when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        var unauthorized = response(401);
        var rejected = response(422);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(unauthorized, rejected);
        var profile = AnthropicCompatibilityProfile.claudeCodeOAuth();
        var client = new AnthropicHttpClient(profile, http, auth, logger);
        var usage = new UsageTracker();
        var route = new ModelRoute(ProviderId.ANTHROPIC, "anthropic/sonnet", "claude-sonnet-4-5", null);
        var chat = new AnthropicChatBackend(client, profile, usage, logger);
        var responses = new AnthropicResponsesBackend(client, profile, usage, logger);
        var messages = new AnthropicMessagesBackend(client, profile,
                () -> List.of(new ProviderModel("claude-sonnet-4-5", "Claude", ProviderId.ANTHROPIC,
                        List.of("sonnet"), Optional.of(true), 200000)), usage, logger);
        var app = Javalin.create(config -> {
            config.routes.before(ctx -> ctx.attribute(AccessLogFields.REQUEST_ID, "req_shared"));
            config.routes.post("/v1/chat/completions", ctx -> chat.handle(ctx, route));
            config.routes.post("/v1/responses", ctx -> responses.handle(ctx, route));
            config.routes.post("/v1/messages", messages);
        }).start("127.0.0.1", 0);
        try (var downstream = HttpClient.newHttpClient()) {
            String body = path.equals("/v1/responses") ? "{\"model\":\"anthropic/sonnet\",\"input\":\"hello\"}"
                    : "{\"model\":\"anthropic/sonnet\",\"max_tokens\":32,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
            var response = downstream.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                    .header("anthropic-version", "2023-06-01").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(422, response.statusCode(), response.body());
            verify(logger).logInbound(eq("req_shared"), any(), anyString());
            verify(logger, times(2)).logUpstreamRequest(eq("req_shared"), eq("POST"), anyString(), anyMap(), anyString());
            verify(logger).logUpstreamResponse(eq("req_shared"), eq(422), anyMap(), anyString());
            verify(logger, never()).nextRequestId();
        } finally { app.stop(); }
    }

    private static HttpResponse<InputStream> response(int status) {
        HttpResponse<InputStream> response = mock();
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream("{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"rejected\"}}".getBytes()));
        return response;
    }
}

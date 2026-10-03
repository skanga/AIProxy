package com.aiproxy.provider.anthropic;

import com.aiproxy.logging.RequestLogger;
import com.aiproxy.model.ModelCatalog;
import com.aiproxy.model.ProviderModel;
import com.aiproxy.provider.ProviderId;
import com.aiproxy.provider.anthropic.auth.AnthropicAuthException;
import com.aiproxy.routing.ProviderRouter;
import com.aiproxy.server.AccessLogFields;
import com.aiproxy.server.MessagesBackend;
import com.aiproxy.server.NativeMessagesRelay;
import com.aiproxy.usage.UsageTracker;
import com.aiproxy.util.Json;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;

import static com.aiproxy.server.MessagesHttp.*;

/** Native Anthropic Messages proxy; responses deliberately bypass OpenAI translation. */
public final class AnthropicMessagesBackend implements Handler, MessagesBackend {
    private static final List<String> SAFE_REQUEST_HEADERS = List.of(
            "X-Claude-Code-Session-Id", "X-Claude-Code-Agent-Id",
            "X-Claude-Code-Parent-Agent-Id", "Anthropic-User-Profile-Id"
    );

    private final AnthropicHttpClient client;
    private final AnthropicCompatibilityProfile profile;
    private final ModelCatalog modelCatalog;
    private final UsageTracker usageTracker;
    private final RequestLogger requestLogger;

    public AnthropicMessagesBackend(
            AnthropicHttpClient client,
            AnthropicCompatibilityProfile profile,
            ModelCatalog modelCatalog,
            UsageTracker usageTracker,
            RequestLogger requestLogger
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.modelCatalog = Objects.requireNonNull(modelCatalog, "modelCatalog");
        this.usageTracker = Objects.requireNonNull(usageTracker, "usageTracker");
        this.requestLogger = Objects.requireNonNull(requestLogger, "requestLogger");
    }

    @Override
    public void handle(Context context) throws Exception {
        AccessLogFields.provider(context, ProviderId.ANTHROPIC.wireName());
        ObjectNode body = readRequest(context, profile.anthropicVersion(), requestLogger);
        if (body != null) handle(context, body);
    }

    public void handle(Context context, ObjectNode body) throws Exception {
        AccessLogFields.provider(context, ProviderId.ANTHROPIC.wireName());
        List<ProviderModel> models;
        try {
            models = modelCatalog.resolveModels();
        } catch (Exception error) {
            writeError(context, 502, "api_error", "Anthropic model catalog is unavailable");
            return;
        }

        AnthropicNativeRequest.Prepared prepared;
        AnthropicRequestOptions options;
        try {
            ProviderRouter router = new ProviderRouter(models, ProviderId.ANTHROPIC);
            prepared = AnthropicNativeRequest.prepare(body, router, profile);
            options = AnthropicRequestOptions.nativeRequest(
                    context.header("anthropic-beta"), requestedHeaders(context));
        } catch (IllegalArgumentException error) {
            writeError(context, 400, "invalid_request_error", error.getMessage());
            return;
        }
        AccessLogFields.mode(context, prepared.stream() ? "stream" : "sync");
        HttpResponse<InputStream> upstream;
        try {
            upstream = client.request(
                    profile.messagesUri(), "POST",
                    Json.MAPPER.writeValueAsString(prepared.body()), options);
        } catch (AnthropicAuthException error) {
            // Proxy-side credential problem, not an upstream outage: report it as such.
            writeError(context, 401, "authentication_error", error.userMessage());
            return;
        } catch (IOException error) {
            writeError(context, 502, "api_error", "Anthropic is temporarily unavailable");
            return;
        }
        new NativeMessagesRelay(usageTracker, "Anthropic").handle(context, upstream, prepared.stream());
    }

    private static Map<String, String> requestedHeaders(Context context) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : SAFE_REQUEST_HEADERS) {
            String value = context.header(name);
            if (value != null) headers.put(name, value);
        }
        return headers;
    }

}

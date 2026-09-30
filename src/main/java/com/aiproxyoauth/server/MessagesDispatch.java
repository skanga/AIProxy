package com.aiproxyoauth.server;

import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.model.CopilotModelCatalog;
import com.aiproxyoauth.provider.anthropic.AnthropicRequestOptions;
import com.aiproxyoauth.provider.copilot.CopilotClient;
import com.aiproxyoauth.usage.UsageTracker;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Map;
import static com.aiproxyoauth.server.AnthropicMessagesHandler.writeError;

/** Explicit Copilot Messages routing; unqualified names retain Anthropic semantics. */
final class MessagesDispatch implements Handler {
    private final AnthropicMessagesHandler anthropic;
    private final CopilotClient copilot;
    private final CopilotModelCatalog catalog;
    private final RequestLogger logger;
    private final NativeMessagesRelay relay;

    MessagesDispatch(AnthropicMessagesHandler anthropic, CopilotClient copilot, CopilotModelCatalog catalog,
                     UsageTracker usage, RequestLogger logger) {
        this.anthropic = anthropic;
        this.copilot = copilot;
        this.catalog = catalog;
        this.logger = logger;
        this.relay = new NativeMessagesRelay(usage, "Copilot");
    }

    @Override public void handle(Context context) throws Exception {
        if (anthropic == null && copilot == null) {
            writeError(context, 503, "api_error", "No Messages provider is enabled");
            return;
        }
        ObjectNode body = AnthropicMessagesHandler.readRequest(context, "2023-06-01", logger);
        if (body == null) return;
        JsonNode model = body.get("model");
        if (model == null || !model.isString() || model.asString().isBlank()) {
            writeError(context, 400, "invalid_request_error", "`model` must be a non-empty string");
            return;
        }
        String requested = model.asString().strip();
        if (!requested.startsWith("copilot/")) {
            if (requested.contains("/") && !requested.startsWith("anthropic/")) {
                writeError(context, 400, "invalid_request_error", "Messages supports only Anthropic and explicit copilot/<model> routes");
            } else if (anthropic == null) {
                writeError(context, 503, "api_error", "Anthropic provider is not enabled; use copilot/<model> for Copilot Messages");
            } else {
                anthropic.handle(context, body);
            }
            return;
        }
        AccessLogFields.provider(context, "copilot");
        if (copilot == null || catalog == null) {
            writeError(context, 503, "api_error", "Copilot provider is not enabled");
            return;
        }
        String upstreamModel = requested.substring("copilot/".length());
        JsonNode stream = body.get("stream");
        try {
            if (upstreamModel.isBlank()) throw new IllegalArgumentException("Qualified model cannot be blank");
            if (stream != null && !stream.isNull() && !stream.isBoolean())
                throw new IllegalArgumentException("`stream` must be a boolean");
            AnthropicRequestOptions.nativeRequest(context.header("anthropic-beta"), Map.of());
            catalog.requireMessagesEndpoint(upstreamModel);
        } catch (IllegalArgumentException error) {
            writeError(context, 400, "invalid_request_error", error.getMessage());
            return;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            writeError(context, 502, "api_error", "Copilot model discovery was interrupted");
            return;
        } catch (Exception error) {
            writeError(context, 502, "api_error", "Copilot model catalog is unavailable");
            return;
        }
        boolean streaming = stream != null && stream.asBoolean(false);
        AccessLogFields.mode(context, streaming ? "stream" : "sync");
        ObjectNode upstreamBody = body.deepCopy().put("model", upstreamModel);
        try {
            var response = copilot.messages(upstreamBody, "2023-06-01", context.header("anthropic-beta"));
            relay.handle(context, response, streaming);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (!context.res().isCommitted()) writeError(context, 502, "api_error", "Copilot request was interrupted");
        } catch (IOException error) {
            if (!context.res().isCommitted()) writeError(context, 502, "api_error", "Copilot is temporarily unavailable");
        }
    }
}

package com.aiproxy.server;

import com.aiproxy.logging.RequestLogger;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static com.aiproxy.server.MessagesHttp.writeError;

/** Explicit Copilot Messages routing; unqualified names retain Anthropic semantics. */
public final class MessagesDispatchHandler implements Handler {
    private final MessagesBackend anthropic;
    private final MessagesBackend copilot;
    private final RequestLogger logger;

    public MessagesDispatchHandler(MessagesBackend anthropic, MessagesBackend copilot, RequestLogger logger) {
        this.anthropic = anthropic;
        this.copilot = copilot;
        this.logger = logger;
    }

    @Override public void handle(Context context) throws Exception {
        if (anthropic == null && copilot == null) {
            writeError(context, 503, "api_error", "No Messages provider is enabled");
            return;
        }
        ObjectNode body = MessagesHttp.readRequest(context, "2023-06-01", logger);
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
        if (copilot == null) {
            writeError(context, 503, "api_error", "Copilot provider is not enabled");
            return;
        }
        copilot.handle(context, body);
    }
}

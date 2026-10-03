package com.aiproxy.provider.copilot;

import com.aiproxy.protocol.messages.MessagesBetaHeaders;
import com.aiproxy.provider.copilot.model.CopilotModelCatalog;
import com.aiproxy.server.AccessLogFields;
import com.aiproxy.server.MessagesBackend;
import com.aiproxy.server.NativeMessagesRelay;
import com.aiproxy.usage.UsageTracker;
import io.javalin.http.Context;
import java.io.IOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static com.aiproxy.server.MessagesHttp.writeError;

/** Copilot execution of the native Messages protocol. */
public final class CopilotMessagesBackend implements MessagesBackend {
    private final CopilotHttpClient copilot;
    private final CopilotModelCatalog catalog;
    private final NativeMessagesRelay relay;

    public CopilotMessagesBackend(CopilotHttpClient copilot, CopilotModelCatalog catalog, UsageTracker usage) {
        this.copilot = copilot;
        this.catalog = catalog;
        this.relay = new NativeMessagesRelay(usage, "Copilot");
    }

    @Override
    public void handle(Context context, ObjectNode body) throws Exception {
        String requested = body.path("model").asString().strip();
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
            MessagesBetaHeaders.parse(context.header("anthropic-beta"));
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

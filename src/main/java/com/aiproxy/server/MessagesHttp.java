package com.aiproxy.server;

import com.aiproxy.logging.RequestLogger;
import com.aiproxy.util.Json;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Shared HTTP boundary for the Anthropic Messages wire protocol. */
public final class MessagesHttp {
    private static final int MAX_REQUEST_BYTES = 32 * 1024 * 1024;
    private MessagesHttp() {}
    public static ObjectNode readRequest(Context context, String expectedVersion, RequestLogger requestLogger) {
        String version = context.header("anthropic-version");
        if (version == null || !expectedVersion.equals(version.strip())) {
            writeError(context, 400, "invalid_request_error",
                    "`anthropic-version` must be " + expectedVersion);
            return null;
        }
        String contentLength = context.header("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > MAX_REQUEST_BYTES) {
                    writeError(context, 413, "request_too_large", "Request body is too large");
                    return null;
                }
            } catch (NumberFormatException ignored) {
                // Jetty validates the framing; the decoded body is checked below.
            }
        }
        String bodyText = context.body();
        if (bodyText.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
            writeError(context, 413, "request_too_large", "Request body is too large");
            return null;
        }
        requestLogger.logInbound(AccessLogFields.requestId(context, requestLogger), context, bodyText);
        ObjectNode body;
        try {
            JsonNode parsed = Json.MAPPER.readTree(bodyText);
            if (parsed == null || !parsed.isObject()) {
                writeError(context, 400, "invalid_request_error",
                        "Request body must be a JSON object");
                return null;
            }
            body = (ObjectNode) parsed;
        } catch (JacksonException error) {
            writeError(context, 400, "invalid_request_error",
                    "Request body must contain valid JSON");
            return null;
        }

        return body;
    }

    public static void writeError(Context context, int status, String type, String message) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("type", "error");
        ObjectNode error = root.putObject("error");
        error.put("type", type);
        error.put("message", message == null || message.isBlank() ? "Request failed" : message);
        String requestId = context.attribute(AccessLogFields.REQUEST_ID);
        if (requestId != null) root.put("request_id", requestId);
        JsonHelper.toJsonResponse(context, root, status);
    }

    public static boolean isNativeRequest(Context context) {
        return context.header("anthropic-version") != null
                || context.header("x-api-key") != null;
    }

}

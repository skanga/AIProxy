package com.aiproxy.logging;

import com.aiproxy.server.AccessLogFields;
import io.javalin.http.Context;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** All completed HTTP requests with bounded/redacted bodies; null disables logging. */
public final class RequestFileLogger {
    private static final Logger LOG = LoggerFactory.getLogger(RequestFileLogger.class);
    private final JsonLineLog log;

    public RequestFileLogger(Path file) {
        log = file == null ? null : new JsonLineLog(file);
    }

    public void logResponse(String requestId, Context ctx) {
        if (log == null) return;
        var entry = RequestLogger.responseEntry(requestId, "request_response", ctx, true);
        String streamFailure = ctx.attribute(AccessLogFields.STREAM_FAILURE);
        if (streamFailure != null) entry.put("stream_error", streamFailure);
        // Only buffered responses are available here. Never buffer a live SSE stream for logging.
        entry.put("response_body_available", ctx.result() != null);
        try {
            log.append(entry);
        } catch (IOException error) {
            LOG.atWarn().addKeyValue("request_id", requestId)
                    .addKeyValue("exception", error.getClass().getName()).log("Failed to write request file log");
        }
    }
}

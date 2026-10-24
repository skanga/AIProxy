package com.aiproxy.logging;

import com.aiproxy.server.AccessLogFields;
import io.javalin.http.Context;
import java.io.IOException;
import java.nio.file.Path;

/** One append-only JSON object per failed HTTP request/stream. Null disables logging; bodies are opt-in. */
public final class ErrorLogger {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ErrorLogger.class);
    private final JsonLineLog log;
    private final boolean includeBodies;

    public ErrorLogger(Path file) { this(file, false); }

    public ErrorLogger(Path file, boolean includeBodies) {
        this.log = file == null ? null : new JsonLineLog(file);
        this.includeBodies = includeBodies;
    }

    public void logErrorResponse(String requestId, Context ctx) {
        if (log == null) return;
        String streamFailure = ctx.attribute(AccessLogFields.STREAM_FAILURE);
        if (ctx.statusCode() < 400 && streamFailure == null) return;
        var entry = RequestLogger.responseEntry(requestId, "error_response", ctx, includeBodies);
        if (streamFailure != null) {
            entry.put("stage", "stream_error");
            entry.put("stream_error", streamFailure);
        }
        try {
            log.append(entry);
        } catch (IOException error) {
            LOG.atWarn().addKeyValue("request_id", requestId)
                    .addKeyValue("exception", error.getClass().getName()).log("Failed to write error log");
        }
    }

}

package com.aiproxy.server;

import io.javalin.http.Context;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

/** Common HTTP timeout contract; translated streams may use their own protocol encoders. */
public final class UpstreamTimeouts {
    public static final String MESSAGE = "Upstream response read timed out";
    private UpstreamTimeouts() {}

    public static boolean isTimeout(Throwable error) {
        return error instanceof HttpTimeoutException
                || error instanceof java.io.UncheckedIOException io && io.getCause() instanceof HttpTimeoutException;
    }

    public static void handle(Context ctx) throws IOException {
        ctx.attribute(AccessLogFields.ERROR_CATEGORY, "upstream_timeout");
        if (!ctx.res().isCommitted()) {
            if (Boolean.TRUE.equals(ctx.attribute("providerFailoverAttempt"))) throw new HttpTimeoutException(MESSAGE);
            ctx.header("X-AIProxy-Error-Category", "upstream_timeout");
            if ("/v1/messages".equals(ctx.path())) MessagesHttp.writeError(ctx, 504, "api_error", MESSAGE);
            else JsonHelper.toErrorResponse(ctx, MESSAGE, 504, "upstream_error", null, "upstream_read_timeout");
            return;
        }
        AccessLogFields.streamFailure(ctx);
        var root = JsonHelper.MAPPER.createObjectNode();
        boolean nativeMessages = "/v1/messages".equals(ctx.path());
        root.put("type", "error");
        root.putObject("error").put("type", nativeMessages ? "api_error" : "upstream_error")
                .put("code", "upstream_read_timeout").put("message", MESSAGE);
        String done = "/v1/chat/completions".equals(ctx.path()) ? "data: [DONE]\n\n" : "";
        byte[] bytes = ("event: error\ndata: " + root + "\n\n" + done).getBytes(StandardCharsets.UTF_8);
        ctx.res().getOutputStream().write(bytes);
        AccessLogFields.addResponseBytes(ctx, bytes.length);
        ctx.res().getOutputStream().flush();
    }
}

package com.aiproxy.server;

import io.javalin.http.Context;

public final class AccessLogFields {
    public static final String REQUEST_ID = "requestId";
    public static final String START_NANOS = "accessLogStartNanos";
    public static final String MODE = "accessLogMode";
    public static final String PROVIDER = "accessLogProvider";
    public static final String UPSTREAM_STATUS = "accessLogUpstreamStatus";
    public static final String RESPONSE_BYTES = "accessLogResponseBytes";

    private AccessLogFields() {}

    public static String requestId(Context context, com.aiproxy.logging.RequestLogger logger) {
        String id = context.attribute(REQUEST_ID);
        if (id == null || id.isBlank()) {
            id = logger.nextRequestId();
            context.attribute(REQUEST_ID, id);
        }
        return id;
    }

    public static void mode(Context ctx, String mode) {
        ctx.attribute(MODE, mode);
    }

    public static void provider(Context ctx, String provider) {
        ctx.attribute(PROVIDER, provider);
    }

    public static void upstreamStatus(Context ctx, int status) {
        ctx.attribute(UPSTREAM_STATUS, status);
    }

    public static void responseBytes(Context ctx, long bytes) {
        ctx.attribute(RESPONSE_BYTES, Math.max(0L, bytes));
    }

    public static void addResponseBytes(Context ctx, long bytes) {
        Long current = ctx.attribute(RESPONSE_BYTES);
        responseBytes(ctx, (current == null ? 0L : current) + Math.max(0L, bytes));
    }
}

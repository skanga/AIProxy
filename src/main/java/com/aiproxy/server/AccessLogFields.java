package com.aiproxy.server;

import io.javalin.http.Context;

public final class AccessLogFields {
    public static final String REQUEST_ID = "requestId";
    public static final String START_NANOS = "accessLogStartNanos";
    public static final String MODE = "accessLogMode";
    public static final String PROVIDER = "accessLogProvider";
    public static final String UPSTREAM_STATUS = "accessLogUpstreamStatus";
    public static final String UPSTREAM_REQUEST_ID = "upstreamRequestId";
    public static final String UPSTREAM_CLIENT_REQUEST_ID = "upstreamClientRequestId";
    public static final String ERROR_CATEGORY = "upstreamErrorCategory";
    public static final String RESPONSE_BYTES = "accessLogResponseBytes";
    public static final String STREAM_FAILURE = "accessLogStreamFailure";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AccessLogFields.class);

    private AccessLogFields() {}

    public static String requestId(Context context, com.aiproxy.logging.RequestLogger logger) {
        String id = context.attribute(REQUEST_ID);
        if (id == null || id.isBlank()) {
            id = logger.nextRequestId();
            context.attribute(REQUEST_ID, id);
        }
        context.header("X-Request-Id", id);
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

    /** Only allow bounded, single-token correlation IDs into headers and metadata logs. */
    public static void upstreamError(Context ctx, java.net.http.HttpResponse<?> response, String category) {
        upstreamResponse(ctx, response);
        errorCategory(ctx, category);
    }

    public static void errorCategory(Context ctx, String category) {
        ctx.attribute(ERROR_CATEGORY, category);
        if (ctx.res() == null || !ctx.res().isCommitted()) ctx.header("X-AIProxy-Error-Category", category);
    }

    public static void upstreamResponse(Context ctx, java.net.http.HttpResponse<?> response) {
        upstreamStatus(ctx, response.statusCode());
        ctx.attribute(UPSTREAM_REQUEST_ID, null);
        ctx.attribute(UPSTREAM_CLIENT_REQUEST_ID, null);
        ctx.attribute(ERROR_CATEGORY, null);
        if (ctx.res() != null) {
            ctx.res().setHeader("X-Upstream-Request-Id", null);
            ctx.res().setHeader("X-AIProxy-Error-Category", null);
        }
        String id = response.headers() == null ? null : safeRequestId(response.headers().firstValue("x-request-id")
                .or(() -> response.headers().firstValue("request-id")).orElse(null));
        if (id != null) {
            ctx.attribute(UPSTREAM_REQUEST_ID, id);
            ctx.header("X-Upstream-Request-Id", id);
        }
        if (response.request() != null) {
            String sentId = safeRequestId(response.request().headers().firstValue("x-request-id").orElse(null));
            if (sentId != null) ctx.attribute(UPSTREAM_CLIENT_REQUEST_ID, sentId);
        }
    }

    private static String safeRequestId(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,200}") ? value : null;
    }

    /** Metadata only: never record upstream error messages or stream contents here. */
    public static void streamFailure(Context ctx) {
        if (ctx.attribute(STREAM_FAILURE) != null) return;
        ctx.attribute(STREAM_FAILURE, "upstream_error");
        String requestId = ctx.attribute(REQUEST_ID);
        String provider = ctx.attribute(PROVIDER);
        LOG.atWarn().addKeyValue("request_id", requestId)
                .addKeyValue("provider", provider)
                .log("Stream failed or was interrupted");
    }

    public static void responseBytes(Context ctx, long bytes) {
        ctx.attribute(RESPONSE_BYTES, Math.max(0L, bytes));
    }

    public static void addResponseBytes(Context ctx, long bytes) {
        Long current = ctx.attribute(RESPONSE_BYTES);
        responseBytes(ctx, (current == null ? 0L : current) + Math.max(0L, bytes));
    }
}

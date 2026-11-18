package com.aiproxy.server;

import java.util.Locale;

public final class UpstreamErrorMapper {

    public record MappedUpstreamError(int statusCode, String body) {}

    public MappedUpstreamError map(int upstreamStatus, String upstreamBody) {
        int status = shouldMapUsageLimit(upstreamStatus, upstreamBody) ? 429 : upstreamStatus;
        String body = upstreamBody;
        if (status == 422 && (body == null || body.isBlank() || body.strip().equalsIgnoreCase("Unprocessable Entity"))) {
            body = "Upstream rejected the request (HTTP 422) but did not provide a structured reason. "
                    + "This does not establish a policy or validation failure. Contact the proxy operator with X-Request-Id.";
        }
        return new MappedUpstreamError(status, JsonHelper.toUpstreamErrorBody(body, status));
    }

    /** Classify only explicit machine-readable reasons, never status codes or message text. */
    public String category(String upstreamBody) {
        try {
            var error = JsonHelper.MAPPER.readTree(upstreamBody).path("error");
            String code = error.path("code").asString("");
            String type = error.path("type").asString("");
            if (isPolicyReason(code) || isPolicyReason(type)) return "content_filter";
            if (code.equals("validation_error") || type.equals("validation_error")
                    || type.equals("invalid_request_error")) return "validation_error";
        } catch (Exception ignored) { }
        return "upstream_error";
    }

    private boolean isPolicyReason(String reason) {
        return switch (reason) {
            case "content_filter", "content_filter_error", "content_policy_violation",
                    "responsible_ai_policy_violation" -> true;
            default -> false;
        };
    }

    private boolean shouldMapUsageLimit(int upstreamStatus, String upstreamBody) {
        if (upstreamStatus != 404 || upstreamBody == null) {
            return false;
        }
        String body = upstreamBody.toLowerCase(Locale.ROOT);
        return body.contains("usage_limit_reached")
                || body.contains("usage_not_included")
                || body.contains("rate_limit_exceeded")
                || body.contains("usage limit");
    }
}

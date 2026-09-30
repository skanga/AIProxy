package com.aiproxyoauth.server;

import com.aiproxyoauth.provider.anthropic.AnthropicUsageObserver;
import com.aiproxyoauth.usage.UsageTracker;
import com.aiproxyoauth.util.Json;
import io.javalin.http.Context;
import tools.jackson.databind.JsonNode;
import java.io.*;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static com.aiproxyoauth.server.AnthropicMessagesHandler.writeError;

/** Bounded native JSON/SSE delivery, without protocol translation. */
final class NativeMessagesRelay {
    private static final int MAX_ERROR_BYTES = 1024 * 1024;
    private static final long MAX_RESPONSE_BYTES = 64L * 1024 * 1024;
    private static final List<String> SAFE_RESPONSE_HEADERS = List.of(
            "request-id", "retry-after", "x-should-retry",
            "anthropic-ratelimit-requests-limit", "anthropic-ratelimit-requests-remaining",
            "anthropic-ratelimit-requests-reset", "anthropic-ratelimit-tokens-limit",
            "anthropic-ratelimit-tokens-remaining", "anthropic-ratelimit-tokens-reset"
    );

    private final UsageTracker usageTracker;
    private final String provider;
    NativeMessagesRelay(UsageTracker usageTracker, String provider) {
        this.usageTracker = usageTracker;
        this.provider = provider;
    }
    void handle(Context context, HttpResponse<InputStream> upstream, boolean streaming) throws IOException {
        AccessLogFields.upstreamStatus(context, upstream.statusCode());
        copyResponseHeaders(context, upstream);
        if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
            try (InputStream input = upstream.body()) {
                byte[] error = readBounded(input, MAX_ERROR_BYTES);
                context.status(upstream.statusCode());
                context.contentType(contentType(upstream, JsonHelper.JSON_CONTENT_TYPE));
                AccessLogFields.responseBytes(context, error.length);
                context.result(new String(error, StandardCharsets.UTF_8));
            } catch (BodyLimitException error) {
                writeError(context, 502, "api_error", provider + " error response was too large");
            }
            return;
        }
        if (streaming) stream(context, upstream);
        else collect(context, upstream);
    }

    private void collect(Context context, HttpResponse<InputStream> upstream) throws IOException {
        byte[] bytes;
        try (InputStream input = upstream.body()) {
            bytes = readBounded(input, Math.toIntExact(MAX_RESPONSE_BYTES));
        } catch (BodyLimitException error) {
            writeError(context, 502, "api_error", provider + " response was too large");
            return;
        }
        context.status(upstream.statusCode());
        context.contentType(contentType(upstream, JsonHelper.JSON_CONTENT_TYPE));
        AccessLogFields.responseBytes(context, bytes.length);
        String body = new String(bytes, StandardCharsets.UTF_8);
        context.result(body);
        recordSyncUsage(context, body);
    }

    private void stream(Context context, HttpResponse<InputStream> upstream) throws IOException {
        JsonHelper.setSseHeaders(context);
        context.status(upstream.statusCode());
        OutputStream output = context.res().getOutputStream();
        AnthropicUsageObserver usage = new AnthropicUsageObserver();
        long total = 0;
        try (InputStream input = upstream.body()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    return;
                }
                byte[] bytes = java.util.Arrays.copyOf(buffer, read);
                output.write(bytes);
                output.flush();
                usage.accept(bytes);
                AccessLogFields.addResponseBytes(context, read);
            }
        } catch (IOException clientOrUpstreamDisconnect) {
            if (!context.res().isCommitted()) {
                writeError(context, 502, "api_error", provider + " stream was interrupted");
            }
            return;
        }
        usageTracker.record(context.attribute("keyName"),
                usage.inputTokens(), usage.outputTokens());
    }

    private void recordSyncUsage(Context context, String body) {
        try {
            JsonNode usage = Json.MAPPER.readTree(body).path("usage");
            usageTracker.record(context.attribute("keyName"),
                    usage.path("input_tokens").asLong() + usage.path("cache_creation_input_tokens").asLong()
                            + usage.path("cache_read_input_tokens").asLong(),
                    usage.path("output_tokens").asLong());
        } catch (Exception ignored) {
            // Native response delivery is not contingent on accounting.
        }
    }

    private static void copyResponseHeaders(
            Context context, HttpResponse<InputStream> response) {
        for (String name : SAFE_RESPONSE_HEADERS) {
            response.headers().firstValue(name).ifPresent(value -> context.header(name, value));
        }
    }

    private static String contentType(
            HttpResponse<InputStream> response, String fallback) {
        return response.headers().firstValue("content-type").orElse(fallback);
    }

    private static byte[] readBounded(InputStream input, int maximumBytes)
            throws IOException, BodyLimitException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int remaining = maximumBytes + 1;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read == -1) break;
            output.write(buffer, 0, read);
            remaining -= read;
        }
        if (output.size() > maximumBytes) throw new BodyLimitException();
        return output.toByteArray();
    }


    private static final class BodyLimitException extends Exception {}
}

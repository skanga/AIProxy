package com.aiproxy.provider.anthropic;

import com.aiproxy.logging.RequestLogger;
import com.aiproxy.protocol.messages.MessagesErrorParser;
import com.aiproxy.protocol.messages.MessagesStreamDecoder;
import com.aiproxy.protocol.messages.MessagesEncodingException;
import com.aiproxy.protocol.chat.ChatCompletionEncoder;
import com.aiproxy.protocol.chat.ChatRequestDecoder;
import com.aiproxy.provider.anthropic.auth.AnthropicAuthException;
import com.aiproxy.provider.spi.ChatRequest;
import com.aiproxy.provider.spi.CompletionEvent;
import com.aiproxy.provider.spi.ProviderError;
import com.aiproxy.routing.ModelRoute;
import com.aiproxy.server.AccessLogFields;
import com.aiproxy.server.InferenceApi;
import com.aiproxy.server.InferenceBackend;
import com.aiproxy.server.JsonHelper;
import com.aiproxy.server.UpstreamFailure;
import com.aiproxy.transport.BoundedBodyReader;
import com.aiproxy.usage.UsageTracker;
import com.aiproxy.util.Json;
import io.javalin.http.Context;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class AnthropicChatBackend implements InferenceBackend {
    private static final int READ_BUFFER_BYTES = 16 * 1024;
    private static final int MAX_ERROR_BODY_BYTES = 1024 * 1024;
    private static final long MAX_RESPONSE_BYTES = 64L * 1024 * 1024;

    private final AnthropicHttpClient client;
    private final AnthropicCompatibilityProfile profile;
    private final UsageTracker usageTracker;
    private final RequestLogger requestLogger;
    private final ChatRequestDecoder requestAdapter;
    private final Clock clock;

    public AnthropicChatBackend(
            AnthropicHttpClient client,
            AnthropicCompatibilityProfile profile,
            UsageTracker usageTracker,
            RequestLogger requestLogger
    ) {
        this(client, profile, usageTracker, requestLogger,
                new ChatRequestDecoder(), Clock.systemUTC());
    }

    AnthropicChatBackend(
            AnthropicHttpClient client,
            AnthropicCompatibilityProfile profile,
            UsageTracker usageTracker,
            RequestLogger requestLogger,
            ChatRequestDecoder requestAdapter,
            Clock clock
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.usageTracker = Objects.requireNonNull(usageTracker, "usageTracker");
        this.requestLogger = Objects.requireNonNull(requestLogger, "requestLogger");
        this.requestAdapter = Objects.requireNonNull(requestAdapter, "requestAdapter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void handle(Context context, ModelRoute route, InferenceApi api) throws Exception {
        if (api != InferenceApi.CHAT_COMPLETIONS) throw new IllegalArgumentException("Unsupported inference API: " + api);
        handle(context, route);
    }

    public void handle(Context context, ModelRoute route) throws Exception {
        String bodyText = context.body();
        String requestId = AccessLogFields.requestId(context, requestLogger);
        requestLogger.logInbound(requestId, context, bodyText);

        ChatRequest chatRequest;
        AnthropicWire.Request wire;
        try {
            JsonNode body = Json.MAPPER.readTree(bodyText);
            chatRequest = requestAdapter.decode(body, route.upstreamModel());
            wire = AnthropicWire.build(chatRequest, profile);
        } catch (MessagesEncodingException | IllegalArgumentException error) {
            JsonHelper.toErrorResponse(context, error.getMessage(), 400,
                    "invalid_request_error", null, "invalid_value");
            return;
        }

        AccessLogFields.mode(context, chatRequest.stream() ? "stream" : "sync");
        HttpResponse<InputStream> upstream;
        try {
            upstream = client.request(
                    wire.uri(), "POST", wire.body(), Map.of("Content-Type", "application/json"));
        } catch (AnthropicAuthException error) {
            // Proxy-side credential problem, not an upstream outage: report it as such.
            JsonHelper.toErrorResponse(context, error.userMessage(), 401,
                    "authentication_error", null, "authentication_error");
            return;
        }
        AccessLogFields.upstreamStatus(context, upstream.statusCode());
        try (InputStream input = upstream.body()) {
            if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
                if (Boolean.TRUE.equals(context.attribute("providerFailoverAttempt"))) throw new UpstreamFailure(upstream.statusCode());
                ProviderError error = MessagesErrorParser.parse(
                        upstream.statusCode(), BoundedBodyReader.readError(input, MAX_ERROR_BODY_BYTES, "{}"));
                writeError(context, error);
                return;
            }
            if (chatRequest.stream()) {
                stream(context, input, route.requestedModel());
            } else {
                collect(context, input, route.requestedModel());
            }
        }
    }

    private void collect(Context context, InputStream input, String requestedModel) throws IOException {
        MessagesStreamDecoder decoder = new MessagesStreamDecoder(clock);
        ChatCompletionEncoder encoder = new ChatCompletionEncoder(requestedModel);
        ProviderError error = decode(input, decoder, encoder, null, context);
        if (error != null) {
            writeError(context, error);
            return;
        }
        if (!encoder.isFinished()) {
            writeError(context, ProviderError.of(
                    ProviderError.Kind.PROTOCOL, "Anthropic stream ended without a completion"));
            return;
        }
        recordUsage(context, encoder);
        JsonHelper.toJsonResponse(context, encoder.completion());
    }

    private void stream(Context context, InputStream input, String requestedModel) throws IOException {
        JsonHelper.setSseHeaders(context);
        OutputStream output = context.res().getOutputStream();
        MessagesStreamDecoder decoder = new MessagesStreamDecoder(clock);
        ChatCompletionEncoder encoder = new ChatCompletionEncoder(requestedModel);
        boolean done = false;
        try {
            ProviderError error = decode(input, decoder, encoder, output, context);
            if (error != null) writeSseError(context, output, error);
            if (encoder.isFinished()) recordUsage(context, encoder);
            writeDone(context, output);
            done = true;
        } finally {
            if (!done) {
                try {
                    writeDone(context, output);
                } catch (IOException ignored) {
                    // The downstream client may already have disconnected.
                }
            }
            output.flush();
        }
    }

    private ProviderError decode(
            InputStream input,
            MessagesStreamDecoder decoder,
            ChatCompletionEncoder encoder,
            OutputStream output,
            Context context
    ) throws IOException {
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        long total = 0;
        while (true) {
            int read;
            try {
                read = input.read(buffer);
            } catch (IOException error) {
                return ProviderError.of(ProviderError.Kind.PROTOCOL, "Anthropic response was interrupted");
            }
            if (read == -1) break;
            total += read;
            if (total > MAX_RESPONSE_BYTES) {
                return ProviderError.of(ProviderError.Kind.PROTOCOL,
                        "Anthropic response exceeded the size limit");
            }
            byte[] bytes = java.util.Arrays.copyOf(buffer, read);
            ProviderError error = consume(decoder.feed(bytes), encoder, output, context);
            if (error != null || encoder.isFinished()) return error;
        }
        return consume(decoder.end(), encoder, output, context);
    }

    private ProviderError consume(
            List<CompletionEvent> events,
            ChatCompletionEncoder encoder,
            OutputStream output,
            Context context
    ) throws IOException {
        for (CompletionEvent event : events) {
            if (event instanceof CompletionEvent.Error failure) return failure.error();
            List<ObjectNode> chunks = encoder.accept(event);
            if (output != null) {
                for (ObjectNode chunk : chunks) writeChunk(context, output, chunk);
            }
        }
        return null;
    }

    private void recordUsage(Context context, ChatCompletionEncoder encoder) {
        CompletionEvent.UsageSnapshot usage = encoder.usage();
        usageTracker.record(context.attribute("keyName"), usage.inputTokens(), usage.outputTokens());
    }

    private void writeError(Context context, ProviderError error) {
        JsonHelper.toErrorResponse(context, error.message(), error.httpStatus(),
                errorType(error.kind()), null, error.kind().name().toLowerCase(java.util.Locale.ROOT));
    }

    private void writeChunk(Context context, OutputStream output, ObjectNode chunk) throws IOException {
        writeBytes(context, output,
                "data: " + Json.MAPPER.writeValueAsString(chunk) + "\n\n");
    }

    private void writeSseError(Context context, OutputStream output, ProviderError error)
            throws IOException {
        ObjectNode root = Json.MAPPER.createObjectNode();
        ObjectNode body = root.putObject("error");
        body.put("message", error.message());
        body.put("type", errorType(error.kind()));
        body.put("code", error.kind().name().toLowerCase(java.util.Locale.ROOT));
        writeBytes(context, output,
                "event: error\ndata: " + Json.MAPPER.writeValueAsString(root) + "\n\n");
    }

    private void writeDone(Context context, OutputStream output) throws IOException {
        writeBytes(context, output, "data: [DONE]\n\n");
    }

    private void writeBytes(Context context, OutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(bytes);
        AccessLogFields.addResponseBytes(context, bytes.length);
        output.flush();
    }

    private String errorType(ProviderError.Kind kind) {
        return switch (kind) {
            case INVALID_REQUEST -> "invalid_request_error";
            case AUTHENTICATION -> "authentication_error";
            case PERMISSION -> "permission_error";
            case RATE_LIMIT -> "rate_limit_error";
            default -> "upstream_error";
        };
    }
}

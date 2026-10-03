package com.aiproxy.provider.anthropic;

import com.aiproxy.logging.RequestLogger;
import com.aiproxy.protocol.messages.MessagesErrorParser;
import com.aiproxy.protocol.messages.MessagesStreamDecoder;
import com.aiproxy.protocol.messages.MessagesEncodingException;
import com.aiproxy.protocol.responses.ResponsesEventEncoder;
import com.aiproxy.protocol.responses.ResponsesRequestDecoder;
import com.aiproxy.provider.anthropic.auth.AnthropicAuthException;
import com.aiproxy.provider.spi.ChatRequest;
import com.aiproxy.provider.spi.CompletionEvent;
import com.aiproxy.provider.spi.ProviderError;
import com.aiproxy.routing.ModelRoute;
import com.aiproxy.server.AccessLogFields;
import com.aiproxy.server.InferenceApi;
import com.aiproxy.server.InferenceBackend;
import com.aiproxy.server.JsonHelper;
import com.aiproxy.server.ReplayNamespace;
import com.aiproxy.server.UpstreamFailure;
import com.aiproxy.state.ReplayStateStore;
import com.aiproxy.state.ResponsesState;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class AnthropicResponsesBackend implements InferenceBackend {
    private static final int MAX_REPLAY_NAMESPACES = 512;
    private static final int READ_BUFFER_BYTES = 16 * 1024;
    private static final int MAX_ERROR_BODY_BYTES = 1024 * 1024;
    private static final long MAX_RESPONSE_BYTES = 64L * 1024 * 1024;

    private final AnthropicHttpClient client;
    private final AnthropicCompatibilityProfile profile;
    private final UsageTracker usageTracker;
    private final RequestLogger requestLogger;
    private final ResponsesRequestDecoder requestAdapter;
    private final Clock clock;
    private final ReplayStateStore replayStates = new ReplayStateStore(MAX_REPLAY_NAMESPACES);

    public AnthropicResponsesBackend(
            AnthropicHttpClient client,
            AnthropicCompatibilityProfile profile,
            UsageTracker usageTracker,
            RequestLogger requestLogger
    ) {
        this(client, profile, usageTracker, requestLogger,
                new ResponsesRequestDecoder(), Clock.systemUTC());
    }

    AnthropicResponsesBackend(
            AnthropicHttpClient client,
            AnthropicCompatibilityProfile profile,
            UsageTracker usageTracker,
            RequestLogger requestLogger,
            ResponsesRequestDecoder requestAdapter,
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
        if (api != InferenceApi.RESPONSES) throw new IllegalArgumentException("Unsupported inference API: " + api);
        handle(context, route);
    }

    public void handle(Context context, ModelRoute route) throws Exception {
        String bodyText = context.body();
        requestLogger.logInbound(AccessLogFields.requestId(context, requestLogger), context, bodyText);
        ObjectNode body;
        try {
            JsonNode parsed = Json.MAPPER.readTree(bodyText);
            if (parsed == null || !parsed.isObject()) {
                writeInvalid(context, "Request body must be a JSON object", null, "invalid_type");
                return;
            }
            body = normalizeStringInput((ObjectNode) parsed);
            validateStateFields(body);
        } catch (IllegalArgumentException error) {
            writeInvalid(context, error.getMessage(), null, "invalid_value");
            return;
        } catch (JacksonException error) {
            writeInvalid(context, "Request body must contain valid JSON", null, "invalid_json");
            return;
        }

        ResponsesState state = replayStateFor(context);
        ObjectNode expanded = state.expandRequestBody(body);
        if (state.requiresCachedState(expanded)) {
            String parameter = expanded.has("previous_response_id")
                    ? "previous_response_id" : "input";
            JsonHelper.toErrorResponse(context,
                    "Claude requires this state reference to be available in the local replay cache.",
                    400, "invalid_request_error", parameter, "unsupported_provider_feature");
            return;
        }
        expanded.remove("previous_response_id");

        ChatRequest request;
        AnthropicWire.Request wire;
        try {
            request = requestAdapter.decode(expanded, route.upstreamModel());
            wire = AnthropicWire.build(request, profile);
        } catch (MessagesEncodingException | IllegalArgumentException error) {
            writeInvalid(context, error.getMessage(), null, "invalid_value");
            return;
        }

        AccessLogFields.mode(context, request.stream() ? "stream" : "sync");
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
                writeError(context, MessagesErrorParser.parse(
                        upstream.statusCode(), BoundedBodyReader.readError(input, MAX_ERROR_BODY_BYTES, "{}")));
                return;
            }
            if (request.stream()) {
                stream(context, input, route.requestedModel(), state, expanded);
            } else {
                collect(context, input, route.requestedModel(), state, expanded);
            }
        }
    }

    private void collect(
            Context context,
            InputStream input,
            String requestedModel,
            ResponsesState state,
            ObjectNode expanded
    ) throws IOException {
        MessagesStreamDecoder decoder = new MessagesStreamDecoder(clock);
        ResponsesEventEncoder encoder = new ResponsesEventEncoder(requestedModel);
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
        ObjectNode response = encoder.response();
        recordUsage(context, encoder);
        state.rememberResponse(response, expanded);
        context.attribute("completedResponse", response);
        JsonHelper.toJsonResponse(context, response);
    }

    private void stream(
            Context context,
            InputStream input,
            String requestedModel,
            ResponsesState state,
            ObjectNode expanded
    ) throws IOException {
        JsonHelper.setSseHeaders(context);
        OutputStream output = context.res().getOutputStream();
        MessagesStreamDecoder decoder = new MessagesStreamDecoder(clock);
        ResponsesEventEncoder encoder = new ResponsesEventEncoder(requestedModel);
        ProviderError error = decode(input, decoder, encoder, output, context);
        if (error != null) {
            if (!context.res().isCommitted()) {
                writeError(context, error);
                return;
            }
            for (var event : encoder.accept(new CompletionEvent.Error(error))) {
                writeStreamEvent(context, output, event.name(), event.data());
            }
        } else if (encoder.isFinished()) {
            ObjectNode response = encoder.response();
            recordUsage(context, encoder);
            state.rememberResponse(response, expanded);
            context.attribute("completedResponse", response);
        }
        output.flush();
    }

    private ProviderError decode(
            InputStream input,
            MessagesStreamDecoder decoder,
            ResponsesEventEncoder encoder,
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
            ProviderError error = consume(
                    decoder.feed(Arrays.copyOf(buffer, read)), encoder, output, context);
            if (error != null || encoder.isFinished()) return error;
        }
        return consume(decoder.end(), encoder, output, context);
    }

    private ProviderError consume(
            List<CompletionEvent> events,
            ResponsesEventEncoder encoder,
            OutputStream output,
            Context context
    ) throws IOException {
        for (CompletionEvent event : events) {
            if (event instanceof CompletionEvent.Error failure) return failure.error();
            List<ResponsesEventEncoder.StreamEvent> encoded = encoder.accept(event);
            if (output != null) {
                for (ResponsesEventEncoder.StreamEvent streamEvent : encoded) {
                    writeStreamEvent(context, output, streamEvent.name(), streamEvent.data());
                }
            }
        }
        return null;
    }

    private void validateStateFields(ObjectNode body) {
        JsonNode previous = body.get("previous_response_id");
        if (previous != null && !previous.isNull() && !previous.isString()) {
            throw new IllegalArgumentException("`previous_response_id` must be a string");
        }
        JsonNode store = body.get("store");
        if (store != null && !store.isNull() && !store.isBoolean()) {
            throw new IllegalArgumentException("`store` must be a boolean");
        }
    }

    private ObjectNode normalizeStringInput(ObjectNode body) {
        ObjectNode normalized = body.deepCopy();
        JsonNode input = normalized.get("input");
        if (input == null || !input.isString()) return normalized;
        ObjectNode message = Json.MAPPER.createObjectNode();
        message.put("type", "message");
        message.put("role", "user");
        ObjectNode content = Json.MAPPER.createObjectNode();
        content.put("type", "input_text");
        content.put("text", input.asString());
        message.set("content", Json.MAPPER.createArrayNode().add(content));
        normalized.set("input", Json.MAPPER.createArrayNode().add(message));
        return normalized;
    }

    private void recordUsage(Context context, ResponsesEventEncoder encoder) {
        CompletionEvent.UsageSnapshot usage = encoder.usage();
        usageTracker.record(context.attribute("keyName"), usage.inputTokens(), usage.outputTokens());
    }

    private void writeStreamEvent(
            Context context, OutputStream output, String eventName, ObjectNode data) throws IOException {
        String value = "event: " + eventName + "\ndata: "
                + Json.MAPPER.writeValueAsString(data) + "\n\n";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(bytes);
        AccessLogFields.addResponseBytes(context, bytes.length);
        output.flush();
    }

    private void writeInvalid(Context context, String message, String parameter, String code) {
        JsonHelper.toErrorResponse(context, message, 400,
                "invalid_request_error", parameter, code);
    }

    private void writeError(Context context, ProviderError error) {
        JsonHelper.toErrorResponse(context, error.message(), error.httpStatus(),
                errorType(error.kind()), null,
                error.kind().name().toLowerCase(java.util.Locale.ROOT));
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

    private ResponsesState replayStateFor(Context context) {
        String namespace = ReplayNamespace.of(context);
        return replayStates.forNamespace(namespace);
    }
}

package com.aiproxy.provider.codex;

import com.aiproxy.config.ServerConfig;
import com.aiproxy.logging.RequestLogger;
import com.aiproxy.protocol.responses.ResponsesStreamCollector;
import com.aiproxy.provider.codex.model.CodexModelAliasResolver;
import com.aiproxy.routing.ModelRoute;
import com.aiproxy.server.AccessLogFields;
import com.aiproxy.server.InferenceApi;
import com.aiproxy.server.InferenceBackend;
import com.aiproxy.server.JsonHelper;
import com.aiproxy.server.ReplayNamespace;
import com.aiproxy.server.UpstreamErrorMapper;
import com.aiproxy.server.UpstreamFailure;
import com.aiproxy.state.ReplayStateStore;
import com.aiproxy.state.ResponsesState;
import com.aiproxy.usage.UsageTracker;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static com.aiproxy.server.JsonHelper.MAPPER;

public class CodexResponsesBackend implements Handler, InferenceBackend {

    private static final int MAX_REPLAY_NAMESPACES = 512;
    private static final int MAX_SSE_BOOKKEEPING_LINE_BYTES = 64 * 1024;

    private final CodexHttpClient client;
    private final ServerConfig config;
    private final UsageTracker usageTracker;
    private final RequestLogger requestLogger;
    private final CodexInstructionsProvider instructionsProvider;
    private final CodexResponsesRequestSanitizer requestSanitizer = new CodexResponsesRequestSanitizer();
    private final CodexModelAliasResolver modelAliasResolver = new CodexModelAliasResolver();
    private final UpstreamErrorMapper upstreamErrorMapper = new UpstreamErrorMapper();
    private final ReplayStateStore replayStates = new ReplayStateStore(MAX_REPLAY_NAMESPACES);

    public CodexResponsesBackend(CodexHttpClient client, ServerConfig config, UsageTracker usageTracker) {
        this(client, config, usageTracker,
                new RequestLogger(false, java.nio.file.Path.of(config.requestLogDir())),
                new CodexInstructionsProvider(config.instructions()));
    }

    public CodexResponsesBackend(CodexHttpClient client, ServerConfig config, UsageTracker usageTracker,
                            RequestLogger requestLogger, CodexInstructionsProvider instructionsProvider) {
        this.client = client;
        this.config = config;
        this.usageTracker = usageTracker;
        this.requestLogger = requestLogger;
        this.instructionsProvider = instructionsProvider;
    }

    @Override
    public void handle(Context context, ModelRoute route, InferenceApi api) throws Exception {
        if (api != InferenceApi.RESPONSES) throw new IllegalArgumentException("Unsupported inference API: " + api);
        handle(context, route);
    }

    @Override
    public void handle(Context ctx) throws Exception {
        create(ctx, null);
    }

    public void create(Context ctx) throws Exception {
        create(ctx, null);
    }

    public void handle(Context ctx, ModelRoute route) throws Exception {
        create(ctx, route);
    }

    private void create(Context ctx, ModelRoute route) throws Exception {
        String requestId = shouldUseRequestContext() ? AccessLogFields.requestId(ctx, requestLogger) : requestLogger.nextRequestId();
        String bodyStr = ctx.body();
        requestLogger.logInbound(requestId, ctx, bodyStr);
        JsonNode body = MAPPER.readTree(bodyStr);

        if (body == null || !body.isObject()) {
            JsonHelper.toErrorResponse(ctx, "Request body must be a JSON object.");
            return;
        }

        JsonNode inputNode = body.get("input");
        if (inputNode != null && !inputNode.isString() && !inputNode.isArray()) {
            JsonHelper.toErrorResponse(ctx, "`input` must be a string or an array.", 400,
                    "invalid_request_error", "input", "invalid_type");
            return;
        }

        if (client.isNative()) {
            String error = CodexNativeRequestProfile.validate(body, false);
            if (error != null) { JsonHelper.toErrorResponse(ctx, error, 400, "invalid_request_error"); return; }
        }
        boolean wantsStream = body.path("stream").asBoolean(false);
        AccessLogFields.mode(ctx, wantsStream ? "stream" : "sync");

        ObjectNode canonical = normalizeInput((ObjectNode) body);

        // Expand previous_response_id and item_reference references after canonicalizing input,
        // so string input participates in replay just like typed input.
        ResponsesState state = replayStateFor(ctx);
        ObjectNode expanded = state.expandRequestBody(canonical);
        if (client.isNative() && state.requiresCachedState(expanded)) {
            JsonHelper.toErrorResponse(ctx, "Native Codex replay history is unavailable; send the full conversation input.",
                    400, "invalid_request_error");
            return;
        }

        // Normalize body
        ObjectNode normalized = requestSanitizer.sanitize(
                normalizeBody(expanded, route), config.store());
        if (client.isNative()) CodexNativeRequestProfile.prepare(normalized,
                Math.max(0, normalized.path("input").size() - canonical.path("input").size()));
        ObjectNode replayBody = client.isNative() ? normalized : expanded;
        String promptCacheKey = config.forwardPromptCacheHeaders()
                ? normalized.path("prompt_cache_key").asString(null)
                : null;

        // Forward to upstream
        HttpResponse<InputStream> upstream = sendUpstream(normalized, requestId, promptCacheKey);
        AccessLogFields.upstreamStatus(ctx, upstream.statusCode());

        if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
            try (InputStream is = upstream.body()) {
                if (Boolean.TRUE.equals(ctx.attribute("providerFailoverAttempt"))) throw new UpstreamFailure(upstream.statusCode());
                String rawBody = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                UpstreamErrorMapper.MappedUpstreamError mapped = upstreamErrorMapper.map(upstream.statusCode(), rawBody);
                requestLogger.logUpstreamResponse(requestId, mapped.statusCode(), responseHeaders(upstream), mapped.body());
                ctx.status(mapped.statusCode());
                ctx.contentType(JsonHelper.JSON_CONTENT_TYPE);
                AccessLogFields.responseBytes(ctx, mapped.body().getBytes(StandardCharsets.UTF_8).length);
                ctx.result(mapped.body());
            }
            return;
        }

        if (wantsStream) {
            // Stream SSE directly to client
            JsonHelper.setSseHeaders(ctx);
            StreamingCompletionRecorder recorder = new StreamingCompletionRecorder(ctx, state, replayBody);
            try (InputStream is = upstream.body();
                 OutputStream os = ctx.res().getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    AccessLogFields.addResponseBytes(ctx, bytesRead);
                    recorder.accept(buffer, bytesRead);
                    os.flush();
                }
            }
            recorder.finish();
        } else {
            // Collect completed response from SSE
            try (InputStream is = upstream.body()) {
                JsonNode completed = ResponsesStreamCollector.collectCompletedResponse(is);
                recordUsage(ctx, completed.get("usage"));
                // Best-effort same-process replay cache only; nothing is persisted locally.
                state.rememberResponse(completed, replayBody);
                ctx.attribute("completedResponse", completed);
                JsonHelper.toJsonResponse(ctx, completed);
            } catch (java.io.IOException error) {
                JsonHelper.toErrorResponse(ctx, "Upstream response was interrupted or invalid.", 502, "upstream_error");
            }
        }
    }

    private ObjectNode normalizeBody(ObjectNode body, ModelRoute route) {
        ObjectNode normalized = body.deepCopy();
        normalized.put("stream", true);
        String requestedModel = normalized.path("model").asString(ServerConfig.DEFAULT_MODEL);
        CodexModelAliasResolver.ResolvedModel resolvedModel = modelAliasResolver.resolve(route == null ? requestedModel : route.upstreamModel());
        if (resolvedModel.model() != null && !resolvedModel.model().isBlank()) {
            normalized.put("model", resolvedModel.model());
        }

        if (!normalized.has("instructions") || !normalized.get("instructions").isString()) {
            normalized.put("instructions", instructionsProvider.instructionsForModel(normalized.path("model").asString()));
        }

        if (!normalized.has("store")) {
            normalized.put("store", config.store());
        }

        String aliasEffort = resolvedModel.reasoningEffort();
        JsonNode reasoningNode = normalized.get("reasoning");
        ObjectNode reasoning = reasoningNode != null && reasoningNode.isObject()
                ? ((ObjectNode) reasoningNode).deepCopy()
                : MAPPER.createObjectNode();
        String requestedEffort = reasoning.path("effort").asString(aliasEffort);
        String clampedEffort = modelAliasResolver.clampReasoningEffort(normalized.path("model").asString(), requestedEffort);
        if (clampedEffort != null) {
            reasoning.put("effort", clampedEffort);
            normalized.set("reasoning", reasoning);
        }

        return normalized;
    }

    private ObjectNode normalizeInput(ObjectNode body) {
        ObjectNode normalized = body.deepCopy();
        JsonNode input = normalized.get("input");
        if (input == null || !input.isString()) {
            return normalized;
        }

        ObjectNode message = MAPPER.createObjectNode();
        message.put("type", "message");
        message.put("role", "user");
        ObjectNode text = MAPPER.createObjectNode();
        text.put("type", "input_text");
        text.put("text", input.asString());
        ArrayNode content = MAPPER.createArrayNode().add(text);
        message.set("content", content);
        normalized.set("input", MAPPER.createArrayNode().add(message));
        return normalized;
    }

    private HttpResponse<InputStream> sendUpstream(ObjectNode normalized, String requestId, String promptCacheKey)
            throws Exception {
        String payload = MAPPER.writeValueAsString(normalized);
        if (shouldUseRequestContext()) {
            return client.request(
                    "/responses", "POST",
                    payload,
                    Map.of("Content-Type", "application/json"),
                    requestId,
                    promptCacheKey);
        }
        return client.request(
                "/responses", "POST",
                payload,
                Map.of("Content-Type", "application/json"));
    }

    private boolean shouldUseRequestContext() {
        return config.fullRequestLogging() || config.forwardPromptCacheHeaders();
    }

    private static <T> Map<String, List<String>> responseHeaders(HttpResponse<T> response) {
        return response.headers() == null ? Map.of() : response.headers().map();
    }

    private boolean recordStreamingCompletion(
            Context ctx, String eventType, String data, ResponsesState state, JsonNode expandedRequest) {
        try {
            if (data == null || data.isEmpty() || "[DONE]".equals(data)) {
                return false;
            }
            JsonNode parsed = MAPPER.readTree(data);
            if (parsed == null || !parsed.isObject()) {
                return false;
            }

            String parsedEventType = parsed.path("type").asString(eventType != null ? eventType : "");
            if (!"response.completed".equals(parsedEventType) && !"response.incomplete".equals(parsedEventType)) {
                return false;
            }

            JsonNode response = parsed.get("response");
            if (response != null && response.isObject()) {
                recordUsage(ctx, response.get("usage"));
                state.rememberResponse(response, expandedRequest);
                ctx.attribute("completedResponse", response);
                return true;
            }
        } catch (Exception ignored) {
            // Streaming payloads have already been forwarded; replay/usage bookkeeping is best-effort.
        }
        return false;
    }

    private void recordUsage(Context ctx, JsonNode usageNode) {
        usageTracker.record(ctx.attribute("keyName"),
                usageNode != null ? usageNode.path("input_tokens").asLong(0) : 0,
                usageNode != null ? usageNode.path("output_tokens").asLong(0) : 0);
    }

    private ResponsesState replayStateFor(Context ctx) throws Exception {
        String namespace = ReplayNamespace.of(ctx);
        if (client.isNative()) namespace += ":native:" + client.credentialIdentity();
        return replayStates.forNamespace(namespace);
    }

    private final class StreamingCompletionRecorder {
        private final Context ctx;
        private final ResponsesState state;
        private final JsonNode expandedRequest;
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        private final List<String> dataLines = new ArrayList<>();
        private String eventType;
        private boolean recorded;
        private boolean bookkeepingDisabled;

        private StreamingCompletionRecorder(Context ctx, ResponsesState state, JsonNode expandedRequest) {
            this.ctx = ctx;
            this.state = state;
            this.expandedRequest = expandedRequest;
        }

        private void accept(byte[] buffer, int length) {
            if (bookkeepingDisabled) {
                return;
            }
            for (int i = 0; i < length; i++) {
                byte b = buffer[i];
                if (b == '\n') {
                    acceptLine(lineBuffer.toString(StandardCharsets.UTF_8));
                    lineBuffer.reset();
                } else {
                    lineBuffer.write(b);
                    if (lineBuffer.size() > MAX_SSE_BOOKKEEPING_LINE_BYTES) {
                        disableBookkeeping();
                        return;
                    }
                }
            }
        }

        private void finish() {
            if (bookkeepingDisabled) {
                return;
            }
            if (lineBuffer.size() > 0) {
                acceptLine(lineBuffer.toString(StandardCharsets.UTF_8));
                lineBuffer.reset();
            }
            if (eventType != null || !dataLines.isEmpty()) {
                dispatchEvent();
            }
        }

        private void acceptLine(String line) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty()) {
                if (eventType != null || !dataLines.isEmpty()) {
                    dispatchEvent();
                }
                return;
            }
            if (line.startsWith("event:")) {
                eventType = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (!value.isEmpty() && value.charAt(0) == ' ') {
                    value = value.substring(1);
                }
                dataLines.add(value);
            }
        }

        private void dispatchEvent() {
            if (!recorded) {
                String data = dataLines.isEmpty() ? null : String.join("\n", dataLines);
                recorded = recordStreamingCompletion(ctx, eventType, data, state, expandedRequest);
            }
            eventType = null;
            dataLines.clear();
        }

        private void disableBookkeeping() {
            bookkeepingDisabled = true;
            recorded = true;
            lineBuffer.reset();
            dataLines.clear();
            eventType = null;
        }
    }
}

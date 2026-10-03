package com.aiproxy.server;

import com.aiproxy.model.ModelCatalog;
import com.aiproxy.model.ProviderModel;
import com.aiproxy.provider.ProviderId;
import com.aiproxy.routing.ModelRoute;
import com.aiproxy.routing.ProviderRouter;
import com.aiproxy.util.Json;
import io.javalin.http.*;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class InferenceDispatchHandler implements Handler {
    private final InferenceApi api;
    private final ModelCatalog catalog;
    private final ProviderId defaultProvider;
    private final String fallbackModel;
    private final Map<ProviderId, InferenceBackend> backends;
    private final List<ProviderId> order;
    private final boolean failover;
    private final Map<String, ModelRoute> replayRoutes = Collections.synchronizedMap(new LinkedHashMap<>(16, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, ModelRoute> entry) { return size() > 10000; }
    });
    public InferenceDispatchHandler(ModelCatalog catalog, ProviderId defaultProvider, String fallbackModel,
            Map<ProviderId, InferenceBackend> backends, List<ProviderId> order, boolean failover, InferenceApi api) {
        this.api = Objects.requireNonNull(api, "api");
        this.catalog = catalog; this.defaultProvider = defaultProvider; this.fallbackModel = fallbackModel;
        this.backends = Map.copyOf(backends); this.order = List.copyOf(order); this.failover = failover;
    }
    public void handle(Context context) throws Exception {
        JsonNode body;
        try { body = Json.MAPPER.readTree(context.body()); }
        catch (Exception error) { JsonHelper.toErrorResponse(context, "Request body must contain valid JSON."); return; }
        if (body == null || !body.isObject()) { JsonHelper.toErrorResponse(context, "Request body must be a JSON object."); return; }
        String requestedInput = body.path("model").asString(fallbackModel);
        List<ModelRoute> routes;
        try {
            List<ProviderModel> models = catalog.resolveModels();
            String requested = requestedInput != null ? requestedInput : models.stream()
                    .filter(model -> model.provider() == defaultProvider).map(ProviderModel::id).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No account models are available for the default provider"));
            List<String> refs = references(body);
            ModelRoute pinned = null;
            for (String ref : refs) {
                ModelRoute found = replayRoutes.get(namespace(context) + ref);
                if (found == null && ref.matches("(?:resp|msg|fc|rs)_copilot_.*")) {
                    throw new IllegalArgumentException("Copilot state reference is unavailable for this client");
                }
                if (found != null) {
                    if (pinned != null && (pinned.provider() != found.provider() || !pinned.upstreamModel().equals(found.upstreamModel()))) {
                        throw new IllegalArgumentException("State references belong to different models or providers");
                    }
                    pinned = found;
                }
            }
            if (pinned != null) {
                if (body.hasNonNull("model") && !requested.equals(pinned.requestedModel()) && !requested.equals(pinned.upstreamModel())
                        && !requested.equals(pinned.provider().wireName() + "/" + pinned.upstreamModel())) {
                    throw new IllegalArgumentException("A stateful continuation must use the original model and provider");
                }
                routes = List.of(new ModelRoute(pinned.provider(), body.hasNonNull("model") ? requested : pinned.requestedModel(), pinned.upstreamModel(), null));
            } else if (failover && refs.isEmpty() && !requested.contains("/")) {
                List<ProviderId> ranked = new ArrayList<>(order);
                ranked.remove(defaultProvider); ranked.addFirst(defaultProvider);
                List<ModelRoute> candidates = new ArrayList<>();
                for (ProviderId provider : ranked) {
                    boolean exact = models.stream().anyMatch(m -> m.provider() == provider && m.id().equals(requested)
                            && (!body.path("tools").isArray() || body.path("tools").isEmpty() || m.supportsTools().orElse(false)));
                    if (exact && backends.containsKey(provider)) {
                        ModelRoute route = new ModelRoute(provider, requested, requested, null);
                        if (backends.get(provider).supports(body, route, api)) candidates.add(route);
                    }
                }
                routes = candidates.isEmpty() ? List.of(new ProviderRouter(models, defaultProvider).route(requested)) : List.copyOf(candidates);
            } else routes = List.of(new ProviderRouter(models, defaultProvider).route(requested));
        } catch (IllegalArgumentException error) {
            JsonHelper.toErrorResponse(context, error.getMessage(), 400, "invalid_request_error", "model", "invalid_model"); return;
        } catch (Exception error) {
            JsonHelper.toErrorResponse(context, "Model catalog is temporarily unavailable.", 503, "server_error", "model", "model_catalog_unavailable"); return;
        }
        for (int index = 0; index < routes.size(); index++) {
            ModelRoute route = routes.get(index);
            InferenceBackend backend = backends.get(route.provider());
            if (backend == null) {
                JsonHelper.toErrorResponse(context, "The requested model provider is not enabled: " + route.provider().wireName(),
                        400, "invalid_request_error", "model", "provider_not_enabled"); return;
            }
            AccessLogFields.provider(context, route.provider().wireName());
            context.attribute("providerFailoverAttempt", index + 1 < routes.size());
            try {
                backend.handle(context, route, api);
                JsonNode completed = context.attribute("completedResponse");
                if (completed != null) {
                    replayRoutes.put(namespace(context) + completed.path("id").asString(), route);
                    for (JsonNode item : completed.path("output")) if (item.hasNonNull("id")) replayRoutes.put(namespace(context) + item.path("id").asString(), route);
                }
                return;
            } catch (UpstreamFailure | ConnectException | HttpTimeoutException error) {
                boolean retryable = !(error instanceof UpstreamFailure failure) || failure.retryable();
                if (context.res().isCommitted()) return;
                if (retryable && index + 1 < routes.size() && !Thread.currentThread().isInterrupted()) continue;
                int status = error instanceof UpstreamFailure failure ? failure.status() : 504;
                JsonHelper.toErrorResponse(context, "Upstream request failed", status, "upstream_error"); return;
            } finally { context.attribute("providerFailoverAttempt", null); }
        }
    }
    private static List<String> references(JsonNode body) {
        List<String> refs = new ArrayList<>();
        if (body.hasNonNull("previous_response_id")) refs.add(body.path("previous_response_id").asString());
        for (JsonNode item : body.path("input")) if (item.path("type").asString().equals("item_reference")) refs.add(item.path("id").asString());
        return refs;
    }
    private static String namespace(Context context) {
        return ReplayNamespace.of(context) + ":";
    }
}

package com.aiproxyoauth.server;

import com.aiproxyoauth.model.CodexModelCatalog;
import com.aiproxyoauth.model.ModelCatalog;
import com.aiproxyoauth.model.ModelResolver;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderModel;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashMap;

public class ModelsHandler implements Handler {

    private final ModelCatalog modelCatalog;
    private final AnthropicModelsHandler anthropicHandler;

    public ModelsHandler(ModelResolver modelResolver) {
        this(new CodexModelCatalog(modelResolver));
    }

    public ModelsHandler(ModelCatalog modelCatalog) {
        this(modelCatalog, null);
    }

    public ModelsHandler(ModelCatalog modelCatalog, AnthropicModelsHandler anthropicHandler) {
        this.modelCatalog = Objects.requireNonNull(modelCatalog, "modelCatalog");
        this.anthropicHandler = anthropicHandler;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        if (AnthropicModelsHandler.isNativeRequest(ctx)) {
            if (anthropicHandler == null) {
                AnthropicMessagesHandler.writeError(ctx, 503, "api_error",
                        "Anthropic provider is not enabled");
            } else {
                anthropicHandler.handle(ctx);
            }
            return;
        }
        try {
            List<ProviderModel> models = modelCatalog.resolveModels();
            List<Map<String, Object>> data = models.stream()
                    .map(model -> describe(model, listedId(model, models)))
                    .toList();
            JsonHelper.toJsonResponse(ctx, Map.of("object", "list", "data", data));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Failed to load models.";
            JsonHelper.toErrorResponse(ctx, msg, 502, "upstream_error");
        }
    }

    private static String listedId(ProviderModel model, List<ProviderModel> models) {
        boolean ambiguous = models.stream().filter(candidate -> candidate.id().equals(model.id())
                || candidate.aliases().contains(model.id())).count() > 1;
        return model.provider() == ProviderId.COPILOT || ambiguous
                ? model.provider().wireName() + "/" + model.id() : model.id();
    }

    private static Map<String, Object> describe(ProviderModel model, String listedId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", listedId);
        value.put("object", "model");
        value.put("created", 0);
        value.put("owned_by", owner(model.provider()));
        value.put("name", model.displayName());
        var metadata = model.metadata();
        Map<String, Object> limits = new LinkedHashMap<>();
        if (model.contextWindow() > 0) {
            value.put("context_length", model.contextWindow());
            limits.put("context_length", model.contextWindow());
        }
        if (metadata.maxOutputTokens() > 0) limits.put("max_completion_tokens", metadata.maxOutputTokens());
        if (!limits.isEmpty()) value.put("top_provider", limits);
        if (!metadata.inputModalities().isEmpty()) {
            value.put("architecture", Map.of("input_modalities", metadata.inputModalities()));
        }
        // A known subset, not an exhaustive claim about all upstream parameters.
        List<String> parameters = new ArrayList<>();
        if (model.supportsTools().orElse(false)) parameters.add("tools");
        if (metadata.maxOutputTokens() > 0) parameters.add("max_tokens");
        boolean messagesTranslation = model.provider() == ProviderId.ANTHROPIC
                || metadata.upstreamEndpoints().equals(List.of("/v1/messages"));
        boolean compatibleEffort = metadata.reasoningEfforts().stream()
                .anyMatch(effort -> !messagesTranslation || List.of("low", "medium", "high", "max").contains(effort));
        if (compatibleEffort && (model.provider() != ProviderId.ANTHROPIC
                || Boolean.TRUE.equals(metadata.capabilities().get("adaptive_thinking")))) {
            parameters.add("reasoning_effort");
        }
        if (!parameters.isEmpty()) value.put("supported_parameters", parameters);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("provider", model.provider().wireName());
        details.put("qualified_id", model.provider().wireName() + "/" + model.id());
        details.put("metadata_source", metadata.source());
        if (metadata.authProfile() != null) details.put("auth_profile", metadata.authProfile());
        if (metadata.fetchedAt() != null) details.put("fetched_at", metadata.fetchedAt().toString());
        if (metadata.maxInputTokens() > 0) details.put("max_input_tokens", metadata.maxInputTokens());
        if (!metadata.capabilities().isEmpty()) details.put("capabilities", metadata.capabilities());
        if (!metadata.reasoningEfforts().isEmpty()) details.put("reasoning_efforts", metadata.reasoningEfforts());
        if (!metadata.upstreamEndpoints().isEmpty()) details.put("upstream_endpoints", metadata.upstreamEndpoints());
        value.put("aiproxy", details);
        return value;
    }

    private static String owner(ProviderId provider) {
        return switch (provider) {
            case CODEX -> "codex-oauth";
            case ANTHROPIC -> "anthropic-oauth";
            case COPILOT -> "copilot-oauth";
        };
    }
}

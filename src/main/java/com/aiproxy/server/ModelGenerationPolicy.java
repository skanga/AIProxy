package com.aiproxy.server;

import com.aiproxy.model.ProviderModel;
import com.aiproxy.model.ModelMetadata.Support;
import com.aiproxy.provider.ProviderId;
import com.aiproxy.provider.spi.GenerationParameters;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Catalog/profile checks shared by explicit routing and failover eligibility. */
final class ModelGenerationPolicy {
    static final String DEFAULT_MESSAGES_TOKENS = "defaultMessagesTokens";
    private ModelGenerationPolicy() {}

    static void validate(JsonNode body, ProviderModel model, InferenceApi api, boolean fallback) {
        boolean responses = api == InferenceApi.RESPONSES;
        GenerationParameters.validate(body, responses);
        Integer limit = GenerationParameters.tokenLimit(body, responses);
        String tokenField = responses ? "max_output_tokens" : body.hasNonNull("max_completion_tokens")
                ? "max_completion_tokens" : "max_tokens";
        var metadata = model.metadata();
        if (responses && model.provider() != ProviderId.CODEX) {
            for (var field : body.path("reasoning").properties()) {
                if (!field.getKey().equals("effort") && !field.getValue().isNull()) {
                    throw unsupported("reasoning." + field.getKey(), "This translation supports reasoning.effort only.");
                }
            }
        }
        if (limit != null) {
            if (model.provider() == ProviderId.CODEX) throw unsupported(tokenField, "Codex does not support an explicit output token limit.");
            if (metadata.maxOutputTokens() > 0 && limit > metadata.maxOutputTokens()) {
                throw unsupported(tokenField, "Output token limit exceeds the selected model's maximum.");
            }
            if (fallback && metadata.maxOutputTokens() == 0) throw unsupported(tokenField, "Fallback output limit is unknown.");
        }
        boolean messages = usesMessages(model);
        for (String field : List.of("temperature", "top_p")) {
            if (!body.hasNonNull(field)) continue;
            if (model.provider() == ProviderId.CODEX && "native".equals(metadata.authProfile())) {
                throw unsupported(field, "Native Codex does not support `" + field + "`.");
            }
            Support supported = metadata.support(field);
            if (supported == Support.UNSUPPORTED || fallback && supported != Support.SUPPORTED) {
                throw unsupported(field, "Sampling parameter is not advertised by the selected model.");
            }
        }
        if (messages) GenerationParameters.number(body, "temperature", 0, 1);
        String effort = GenerationParameters.effort(body, responses);
        if (effort != null) {
            String field = responses ? "reasoning.effort" : "reasoning_effort";
            Support support = metadata.reasoningEffortSupport(effort);
            if (support == Support.UNSUPPORTED || fallback && support != Support.SUPPORTED) {
                throw unsupported(field, "Reasoning effort is unsupported or is not established for fallback.");
            }
            if (messages && (body.hasNonNull("temperature") || body.hasNonNull("top_p"))) {
                throw unsupported(body.hasNonNull("temperature") ? "temperature" : "top_p",
                        "Sampling controls with translated adaptive thinking are not supported.");
            }
            Support adaptive = metadata.support("adaptive_thinking");
            if (messages && (!List.of("low", "medium", "high", "max").contains(effort)
                    || adaptive == Support.UNSUPPORTED || fallback && adaptive != Support.SUPPORTED)) {
                throw unsupported(field, "The selected model does not advertise compatible adaptive thinking.");
            }
        }
    }

    static boolean usesMessages(ProviderModel model) {
        if (model.provider() == ProviderId.ANTHROPIC) return true;
        var endpoints = model.metadata().upstreamEndpoints();
        // Copilot prefers the client's protocol, then Chat, Responses, Messages in that order.
        return model.provider() == ProviderId.COPILOT && endpoints.contains("/v1/messages")
                && !endpoints.contains("/chat/completions") && !endpoints.contains("/responses");
    }

    static int defaultMessagesTokens(ProviderModel model) {
        int maximum = model.metadata().maxOutputTokens();
        return maximum > 0 ? Math.min(4096, maximum) : 4096;
    }

    private static GenerationParameters.ParameterException unsupported(String field, String message) {
        return GenerationParameters.unsupported(field, message);
    }
}

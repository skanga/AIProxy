package com.aiproxy.provider.copilot.model;

import com.aiproxy.model.ModelMetadata;
import com.aiproxy.model.ProviderModel;
import com.aiproxy.provider.ProviderId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

import static com.aiproxy.model.ModelFields.*;

final class CopilotModelParser {
    private CopilotModelParser() {}

    static ProviderModel parse(JsonNode model, Instant fetchedAt) {
        JsonNode limits = model.path("capabilities").path("limits");
        JsonNode supports = model.path("capabilities").path("supports");
        Map<String, Boolean> capabilities = new LinkedHashMap<>();
        for (String key : List.of("tool_calls", "vision", "streaming")) {
            booleanField(capabilities, key, supports.path(key));
        }
        List<String> efforts = strings(supports.path("reasoning_effort"));
        var metadata = new ModelMetadata(positiveInt(limits.path("max_prompt_tokens")),
                positiveInt(limits.path("max_output_tokens")), modalities(capabilities.get("vision")),
                capabilities, efforts, strings(model.path("supported_endpoints")).stream()
                .filter(List.of("/chat/completions", "/responses", "/v1/messages")::contains).toList(),
                "upstream", fetchedAt, null);
        String id = model.path("id").asString();
        return new ProviderModel(id, name(model.path("name"), id), ProviderId.COPILOT, List.of(),
                Optional.ofNullable(capabilities.get("tool_calls")),
                positiveInt(limits.path("max_context_window_tokens")), metadata);
    }
}

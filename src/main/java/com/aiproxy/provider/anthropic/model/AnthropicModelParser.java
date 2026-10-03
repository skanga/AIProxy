package com.aiproxy.provider.anthropic.model;

import com.aiproxy.model.ModelMetadata;
import com.aiproxy.model.ProviderModel;
import com.aiproxy.provider.ProviderId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

import static com.aiproxy.model.ModelFields.*;

final class AnthropicModelParser {
    private AnthropicModelParser() {}

    static ProviderModel parse(JsonNode model, Instant fetchedAt) {
        JsonNode upstream = model.path("capabilities");
        Map<String, Boolean> capabilities = new LinkedHashMap<>();
        booleanField(capabilities, "vision", upstream.path("image_input").path("supported"));
        booleanField(capabilities, "thinking", upstream.path("thinking").path("supported"));
        booleanField(capabilities, "adaptive_thinking", upstream.path("thinking").path("types")
                .path("adaptive").path("supported"));
        List<String> efforts = new ArrayList<>();
        JsonNode effort = upstream.path("effort");
        if (effort.path("supported").isBoolean() && effort.path("supported").asBoolean()) {
            for (String level : List.of("low", "medium", "high", "xhigh", "max")) {
                JsonNode supported = effort.path(level).path("supported");
                if (supported.isBoolean() && supported.asBoolean()) efforts.add(level);
            }
        }
        int input = positiveInt(model.path("max_input_tokens"));
        var metadata = new ModelMetadata(input, positiveInt(model.path("max_tokens")),
                modalities(capabilities.get("vision")), capabilities, efforts, List.of(), "upstream", fetchedAt, null);
        String id = model.path("id").asString();
        return new ProviderModel(id, name(model.path("display_name"), id), ProviderId.ANTHROPIC, List.of(),
                Optional.empty(), input, metadata);
    }
}

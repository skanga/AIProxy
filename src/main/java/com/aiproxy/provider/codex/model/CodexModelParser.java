package com.aiproxy.provider.codex.model;

import com.aiproxy.model.ModelMetadata;
import com.aiproxy.model.ProviderModel;
import com.aiproxy.provider.ProviderId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

import static com.aiproxy.model.ModelFields.*;

final class CodexModelParser {
    private CodexModelParser() {}

    static ProviderModel parse(JsonNode model, Instant fetchedAt, boolean nativeProfile) {
        List<String> efforts = new ArrayList<>();
        JsonNode levels = model.path("supported_reasoning_levels");
        if (levels.isArray()) for (JsonNode level : levels) {
            JsonNode effort = level.path("effort");
            if (effort.isString() && !effort.asString().isBlank()) efforts.add(effort.asString());
        }
        var metadata = new ModelMetadata(0, 0, strings(model.path("input_modalities")), Map.of(),
                efforts.stream().distinct().toList(), List.of(), "upstream", fetchedAt,
                nativeProfile ? "native" : "cli");
        String id = model.path("slug").asString();
        return new ProviderModel(id, name(model.path("display_name"), id), ProviderId.CODEX, List.of(),
                Optional.empty(), positiveInt(model.path("context_window")), metadata);
    }
}

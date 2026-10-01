package com.aiproxyoauth.model;

import com.aiproxyoauth.provider.ModelMetadata;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderModel;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Normalize only documented catalog fields; never copy arbitrary upstream objects. */
final class ModelMetadataParser {
    private ModelMetadataParser() {}

    static ProviderModel copilot(JsonNode model, Instant fetchedAt) {
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

    static ProviderModel codex(JsonNode model, Instant fetchedAt, boolean nativeProfile) {
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

    static ProviderModel anthropic(JsonNode model, Instant fetchedAt) {
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

    private static int positiveInt(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) return 0;
        int number = value.asInt();
        return number > 0 ? number : 0;
    }

    private static void booleanField(Map<String, Boolean> target, String key, JsonNode value) {
        if (value.isBoolean()) target.put(key, value.asBoolean());
    }

    private static List<String> modalities(Boolean vision) {
        return vision == null ? List.of() : vision ? List.of("text", "image") : List.of("text");
    }

    private static List<String> strings(JsonNode value) {
        if (!value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (item.isString() && !item.asString().isBlank()) result.add(item.asString());
        }
        return result.stream().distinct().toList();
    }

    private static String name(JsonNode value, String fallback) {
        return value.isString() && !value.asString().isBlank() ? value.asString() : fallback;
    }
}

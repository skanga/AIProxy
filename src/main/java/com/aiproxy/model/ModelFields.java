package com.aiproxy.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Scalar validation shared by provider catalog parsers. */
public final class ModelFields {
    private ModelFields() {}

    public static int positiveInt(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) return 0;
        int number = value.asInt();
        return number > 0 ? number : 0;
    }

    public static void booleanField(Map<String, Boolean> target, String key, JsonNode value) {
        if (value.isBoolean()) target.put(key, value.asBoolean());
    }

    public static List<String> modalities(Boolean vision) {
        return vision == null ? List.of() : vision ? List.of("text", "image") : List.of("text");
    }

    public static List<String> strings(JsonNode value) {
        if (!value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (item.isString() && !item.asString().isBlank()) result.add(item.asString());
        }
        return result.stream().distinct().toList();
    }

    public static String name(JsonNode value, String fallback) {
        return value.isString() && !value.asString().isBlank() ? value.asString() : fallback;
    }
}

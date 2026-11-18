package com.aiproxy.provider.spi;

import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Public Chat/Responses generation scalars; no provider or HTTP policy. Null means omitted. */
public final class GenerationParameters {
    private static final Set<String> EFFORTS = Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max");
    private GenerationParameters() {}

    public static void validate(JsonNode body, boolean responses) {
        for (String field : responses ? Set.of("max_tokens", "max_completion_tokens", "reasoning_effort")
                : Set.of("max_output_tokens", "reasoning")) {
            if (body.hasNonNull(field)) throw invalid(field, "is not a parameter of this API");
        }
        number(body, "temperature", 0, 2);
        number(body, "top_p", 0, 1);
        tokenLimit(body, responses);
        effort(body, responses);
    }

    public static Integer tokenLimit(JsonNode body, boolean responses) {
        if (responses) return positiveInteger(body, "max_output_tokens");
        Integer legacy = positiveInteger(body, "max_tokens");
        Integer current = positiveInteger(body, "max_completion_tokens");
        if (legacy != null && current != null && !legacy.equals(current)) {
            throw invalid("max_completion_tokens", "must equal max_tokens when both are supplied");
        }
        return current != null ? current : legacy;
    }

    public static Double number(JsonNode body, String field, double minimum, double maximum) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw invalid(field, "must be a number");
        double result = value.asDouble();
        if (!Double.isFinite(result) || result < minimum || result > maximum) {
            throw invalid(field, "must be between " + minimum + " and " + maximum);
        }
        return result;
    }

    public static String effort(JsonNode body, boolean responses) {
        String field = responses ? "reasoning.effort" : "reasoning_effort";
        JsonNode node = body;
        if (responses) {
            node = body.get("reasoning");
            if (node == null || node.isNull()) return null;
            if (!node.isObject()) throw invalid("reasoning", "must be an object");
        }
        JsonNode value = node.get(responses ? "effort" : "reasoning_effort");
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw invalid(field, "must be a string");
        String effort = value.asString().toLowerCase(Locale.ROOT);
        if (!EFFORTS.contains(effort)) throw invalid(field, "is not a recognized reasoning effort");
        return effort;
    }

    private static Integer positiveInteger(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() <= 0) {
            throw invalid(field, "must be a positive integer");
        }
        return value.asInt();
    }

    public static ParameterException unsupported(String field, String message) {
        return new ParameterException(field, "unsupported_provider_feature", message);
    }

    private static ParameterException invalid(String field, String reason) {
        return new ParameterException(field, "invalid_value", "`" + field + "` " + reason);
    }

    public static final class ParameterException extends IllegalArgumentException {
        private final String param;
        private final String code;
        public ParameterException(String param, String code, String message) {
            super(message);
            this.param = param;
            this.code = code;
        }
        public String param() { return param; }
        public String code() { return code; }
    }
}

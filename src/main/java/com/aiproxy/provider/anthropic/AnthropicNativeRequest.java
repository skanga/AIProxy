package com.aiproxy.provider.anthropic;

import com.aiproxy.provider.ProviderId;
import com.aiproxy.routing.ModelRoute;
import com.aiproxy.routing.ModelRoutingException;
import com.aiproxy.routing.ProviderRouter;
import com.aiproxy.util.Json;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Minimal mutation required to send a native Messages request with Claude Code OAuth. */
public final class AnthropicNativeRequest {
    private AnthropicNativeRequest() {
    }

    public static Prepared prepare(
            ObjectNode input,
            ProviderRouter router,
            AnthropicCompatibilityProfile profile
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(profile, "profile");
        ObjectNode body = input.deepCopy();
        JsonNode modelNode = body.get("model");
        if (modelNode == null || !modelNode.isString() || modelNode.asString().isBlank()) {
            throw invalid("`model` must be a non-empty string");
        }
        String requestedModel = modelNode.asString().strip();
        String normalizedModel = requestedModel.toLowerCase(java.util.Locale.ROOT);
        if (requestedModel.indexOf('/') < 0
                && (normalizedModel.startsWith("gpt-")
                || normalizedModel.startsWith("codex-"))) {
            throw invalid("Native Messages requests require an Anthropic model");
        }
        ModelRoute route;
        try {
            route = router.route(requestedModel.indexOf('/') >= 0
                    ? requestedModel : "anthropic/" + requestedModel);
        } catch (ModelRoutingException error) {
            throw invalid(error.getMessage());
        }
        if (route.provider() != ProviderId.ANTHROPIC) {
            throw invalid("Native Messages requests require an Anthropic model");
        }
        body.put("model", route.upstreamModel());

        JsonNode stream = body.get("stream");
        if (stream != null && !stream.isNull() && !stream.isBoolean()) {
            throw invalid("`stream` must be a boolean");
        }
        boolean streaming = stream != null && stream.asBoolean(false);

        ArrayNode system = Json.MAPPER.createArrayNode();
        system.addObject().put("type", "text").put("text", profile.oauthSystemPreamble());
        JsonNode suppliedSystem = body.get("system");
        if (suppliedSystem == null || suppliedSystem.isNull()) {
            // The OAuth preamble is the complete system prompt.
        } else if (suppliedSystem.isString()) {
            system.addObject().put("type", "text").put("text", suppliedSystem.asString());
        } else if (suppliedSystem.isArray()) {
            suppliedSystem.forEach(value -> system.add(value.deepCopy()));
        } else {
            throw invalid("`system` must be a string or array");
        }
        body.set("system", system);
        return new Prepared(body, streaming);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    public record Prepared(ObjectNode body, boolean stream) {
        public Prepared {
            body = Objects.requireNonNull(body, "body");
        }
    }
}

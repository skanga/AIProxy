package com.aiproxy.provider.anthropic;

import com.aiproxy.protocol.messages.MessagesRequestEncoder;
import com.aiproxy.protocol.messages.MessagesEncodingException;
import com.aiproxy.provider.spi.ChatRequest;
import com.aiproxy.util.Json;
import tools.jackson.databind.node.ArrayNode;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class AnthropicWire {
    private AnthropicWire() {
    }

    public static Request build(
            ChatRequest request,
            AnthropicCompatibilityProfile profile
    ) throws MessagesEncodingException {
        Objects.requireNonNull(profile, "profile");
        var encoded = new MessagesRequestEncoder().encode(request);
        encoded.put("stream", true);
        var system = Json.MAPPER.createArrayNode();
        system.addObject().put("type", "text").put("text", profile.oauthSystemPreamble());
        system.addAll((ArrayNode) encoded.path("system"));
        encoded.set("system", system);
        String body = encoded.toString();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "AIProxy/1.3.0");
        headers.put("x-app", "AIProxy");
        headers.put("anthropic-version", profile.anthropicVersion());
        headers.put(
                "anthropic-beta",
                profile.claudeCodeBeta() + "," + profile.oauthBeta()
        );
        headers.put("anthropic-dangerous-direct-browser-access", "true");
        return new Request(profile.messagesUri(), body, headers);
    }

    public record Request(URI uri, String body, Map<String, String> headers) {
        public Request {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(body, "body");
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        }
    }
}

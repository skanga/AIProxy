package com.aiproxy.provider.codex.auth.nativeoauth;

import com.aiproxy.util.Json;
import java.io.IOException;
import java.util.Arrays;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** A verified identity and one atomically replaced token generation. Never print tokens. */
public record NativeCredential(String clientId, String subject, String hostId, String sessionId,
                               String idToken, String accessToken, String refreshToken,
                               String scope, long expiresAt, long earliestRefreshAt) {
    static String text(JsonNode node, String key) throws IOException {
        JsonNode value = node.path(key);
        if (!value.isString() || value.asString().isBlank()) throw new IOException("Invalid native credential field: " + key);
        return value.asString();
    }
    static long number(JsonNode node, String key) throws IOException {
        if (!node.path(key).isIntegralNumber() || !node.path(key).canConvertToLong())
            throw new IOException("Invalid native credential field: " + key);
        return node.path(key).asLong();
    }
    public static NativeCredential parse(JsonNode node) throws IOException {
        if (node == null || !node.isObject() || number(node, "version") != 1
                || !"openai-native".equals(text(node, "provider"))
                || !NativeOAuth.ISSUER.equals(text(node, "issuer"))
                || !"Bearer".equalsIgnoreCase(text(node, "token_type")))
            throw new IOException("Unsupported native credential file");
        NativeCredential result = new NativeCredential(text(node,"client_id"), text(node,"subject"),
                text(node,"host_id"), text(node,"session_id"), text(node,"id_token"), text(node,"access_token"),
                text(node,"refresh_token"), text(node,"scope"), number(node,"expires_at"), number(node,"earliest_refresh_at"));
        result.requireScope();
        if (result.expiresAt <= 0 || result.earliestRefreshAt < 0 || result.earliestRefreshAt > result.expiresAt)
            throw new IOException("Invalid native token expiry");
        return result;
    }
    void requireScope() throws IOException {
        if (!Arrays.asList(scope.split("\\s+")).contains("chatgpt.tokens.use.direct"))
            throw new IOException("ChatGPT plan permission was not granted; sign in again");
    }
    ObjectNode registration() {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("version", 1).put("provider", "openai-native").put("issuer", NativeOAuth.ISSUER)
                .put("client_id", clientId).put("subject", subject).put("host_id", hostId);
        return node;
    }
    ObjectNode json() {
        return registration().put("session_id",sessionId).put("id_token",idToken).put("access_token",accessToken)
                .put("refresh_token",refreshToken).put("token_type","Bearer").put("scope",scope)
                .put("expires_at",expiresAt).put("earliest_refresh_at",earliestRefreshAt)
                .put("saved_at",java.time.Instant.now().toString());
    }
    @Override public String toString() { return "NativeCredential[clientId=" + clientId + ", tokens=<redacted>]"; }
}

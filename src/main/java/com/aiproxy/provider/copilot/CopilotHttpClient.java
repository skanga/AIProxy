package com.aiproxy.provider.copilot;

import com.aiproxy.config.EffectiveConfig;
import com.aiproxy.protocol.messages.MessagesBetaHeaders;
import com.aiproxy.provider.copilot.auth.CopilotCredentials;
import com.aiproxy.transport.BoundedBodyReader;
import com.aiproxy.util.Json;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class CopilotHttpClient implements AutoCloseable {
    private final EffectiveConfig.Copilot config;
    private final CopilotCredentials credentials;
    private final HttpClient client;
    private final Clock clock;
    private final URI discovery;
    private final boolean ownedClient;
    private URI base;
    private String identity;
    private Instant expires = Instant.MIN;
    public CopilotHttpClient(EffectiveConfig.Copilot config) {
        this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC(), null, true);
    }
    CopilotHttpClient(EffectiveConfig.Copilot config, HttpClient client, Clock clock, URI discovery) {
        this(config, client, clock, discovery, false);
        if (discovery != null && !("http".equals(discovery.getScheme()) && "127.0.0.1".equals(discovery.getHost()))) {
            throw new IllegalArgumentException("Test discovery override must use IPv4 loopback");
        }
    }
    private CopilotHttpClient(EffectiveConfig.Copilot config, HttpClient client, Clock clock, URI discovery, boolean owned) {
        this.config = config; this.credentials = new CopilotCredentials(config);
        this.client = client; this.clock = clock; this.ownedClient = owned;
        this.discovery = discovery == null ? URI.create("https://api." + config.githubHost() + "/copilot_internal/user") : discovery;
    }
    public String identity() throws IOException { return fingerprint(credentials.token()); }
    private static String fingerprint(String token) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private synchronized URI endpoint(String token) throws IOException, InterruptedException {
        String current = fingerprint(token);
        if (base != null && current.equals(identity) && clock.instant().isBefore(expires)) return base;
        var response = send(discovery, token, null, true);
        byte[] bytes = BoundedBodyReader.read(response, 1024 * 1024);
        if (response.statusCode() != 200) throw new IOException("Copilot endpoint discovery returned HTTP " + response.statusCode());
        JsonNode root = parse(bytes);
        base = validateEndpoint(root.path("endpoints").path("api").asString());
        identity = current; expires = clock.instant().plusSeconds(1800);
        return base;
    }
    private URI validateEndpoint(String value) throws IOException {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            boolean test = "http".equals(discovery.getScheme()) && "http".equals(uri.getScheme())
                    && discovery.getRawAuthority().equals(uri.getRawAuthority());
            boolean trusted = host != null && (host.endsWith(".githubcopilot.com")
                    || (!config.githubHost().equals("github.com") && host.endsWith("." + config.githubHost())));
            if ((!test && !("https".equals(uri.getScheme()) && trusted && (uri.getPort() == -1 || uri.getPort() == 443)))
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException();
            return URI.create(value.replaceAll("/+$", ""));
        } catch (IllegalArgumentException error) { throw new IOException("Copilot returned an untrusted inference endpoint"); }
    }
    public void validateCredentials() throws IOException, InterruptedException { endpoint(credentials.token()); }
    public JsonNode models() throws IOException, InterruptedException {
        var response = request("/models", null);
        byte[] bytes = BoundedBodyReader.read(response, 8 * 1024 * 1024);
        if (response.statusCode() != 200) throw new IOException("Copilot models returned HTTP " + response.statusCode());
        return parse(bytes);
    }
    public HttpResponse<InputStream> request(String path, JsonNode body) throws IOException, InterruptedException {
        return request(path, body, Map.of(), false);
    }
    public HttpResponse<InputStream> messages(JsonNode body, String version, String beta) throws IOException, InterruptedException {
        if (!"2023-06-01".equals(version)) throw new IllegalArgumentException("Unsupported anthropic-version");
        var betas = MessagesBetaHeaders.parse(beta);
        Map<String, String> headers = betas.isEmpty() ? Map.of()
                : Map.of("anthropic-beta", String.join(",", betas));
        return request("/v1/messages", body, headers, !body.path("stream").asBoolean(false));
    }
    private HttpResponse<InputStream> request(String path, JsonNode body, Map<String, String> headers, boolean jsonResponse)
            throws IOException, InterruptedException {
        if (!java.util.Set.of("/models", "/chat/completions", "/responses", "/v1/messages").contains(path)) {
            throw new IllegalArgumentException("Unsupported Copilot endpoint");
        }
        String token = credentials.token();
        HttpResponse<InputStream> response = send(URI.create(endpoint(token) + path), token, body, false, headers, jsonResponse);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            synchronized (this) { expires = Instant.MIN; }
            String reloaded = credentials.token();
            if (response.statusCode() == 401 && !token.equals(reloaded)) {
                response.body().close();
                return send(URI.create(endpoint(reloaded) + path), reloaded, body, false, headers, jsonResponse);
            }
        }
        return response;
    }
    private HttpResponse<InputStream> send(URI uri, String token, JsonNode body, boolean discoveryRequest)
            throws IOException, InterruptedException {
        return send(uri, token, body, discoveryRequest, Map.of(), false);
    }
    private HttpResponse<InputStream> send(URI uri, String token, JsonNode body, boolean discoveryRequest,
                                           Map<String, String> headers, boolean jsonResponse)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + token).header("User-Agent", "AIProxy")
                .header("Accept", body == null || jsonResponse ? "application/json" : "text/event-stream")
                .header("X-Request-Id", UUID.randomUUID().toString());
        if (discoveryRequest) builder.header("X-GitHub-Api-Version", "2025-04-01");
        if (uri.getPath().endsWith("/v1/messages")) builder.header("anthropic-version", "2023-06-01");
        headers.forEach(builder::header);
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return com.aiproxy.transport.InferenceTransport.send(client, builder.build());
    }
    private static JsonNode parse(byte[] bytes) throws IOException {
        try {
            JsonNode root = Json.MAPPER.readTree(bytes);
            if (root == null || !root.isObject()) throw new IOException();
            return root;
        } catch (IOException | RuntimeException error) { throw new IOException("Copilot returned invalid JSON"); }
    }
    public void close() { if (ownedClient) client.close(); }
}

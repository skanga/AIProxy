package com.aiproxy.provider.anthropic;

import com.aiproxy.logging.RequestLogger;
import com.aiproxy.provider.anthropic.auth.AnthropicAuthManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AnthropicHttpClient {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Set<String> PROTECTED_HEADERS = Set.of(
            "authorization",
            "x-api-key",
            "anthropic-version",
            "anthropic-beta",
            "anthropic-dangerous-direct-browser-access",
            "user-agent",
            "x-app"
    );

    private final AnthropicCompatibilityProfile profile;
    private final HttpClient httpClient;
    private final AnthropicAuthManager authManager;
    private final RequestLogger requestLogger;

    public AnthropicHttpClient(AnthropicCompatibilityProfile profile,
                               HttpClient httpClient,
                               AnthropicAuthManager authManager,
                               RequestLogger requestLogger) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Inference HTTP clients must disable automatic redirects");
        }
        this.authManager = Objects.requireNonNull(authManager, "authManager");
        this.requestLogger = Objects.requireNonNull(requestLogger, "requestLogger");
    }

    public HttpResponse<InputStream> request(
            URI uri, String method, String body, Map<String, String> extraHeaders)
            throws IOException {
        return request(uri, method, body, AnthropicRequestOptions.existing(extraHeaders));
    }

    public HttpResponse<InputStream> request(
            URI uri, String method, String body, AnthropicRequestOptions options)
            throws IOException {
        return request(uri, method, body, options, DEFAULT_TIMEOUT);
    }

    public HttpResponse<InputStream> request(
            URI uri, String method, String body, Map<String, String> extraHeaders, String requestId)
            throws IOException {
        return request(uri, method, body, AnthropicRequestOptions.existing(extraHeaders), DEFAULT_TIMEOUT, requestId);
    }

    public HttpResponse<InputStream> request(
            URI uri, String method, String body, AnthropicRequestOptions options, String requestId)
            throws IOException {
        return request(uri, method, body, options, DEFAULT_TIMEOUT, requestId);
    }

    public HttpResponse<InputStream> request(
            URI uri,
            String method,
            String body,
            Map<String, String> extraHeaders,
            Duration timeout
    ) throws IOException {
        return request(uri, method, body, AnthropicRequestOptions.existing(extraHeaders), timeout);
    }

    public HttpResponse<InputStream> request(
            URI uri,
            String method,
            String body,
            AnthropicRequestOptions options,
            Duration timeout
    ) throws IOException {
        return request(uri, method, body, options, timeout, null);
    }

    private HttpResponse<InputStream> request(
            URI uri, String method, String body, AnthropicRequestOptions options,
            Duration timeout, String requestId) throws IOException {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(timeout, "timeout");
        if (requestId == null || requestId.isBlank()) requestId = requestLogger.nextRequestId();
        String token = authManager.accessToken();
        HttpResponse<InputStream> response = send(
                buildRequest(uri, method, body, options, timeout, requestId, token));
        if (response.statusCode() == 401) {
            String refreshedToken;
            try {
                authManager.invalidate();
                refreshedToken = authManager.accessToken();
            } catch (IOException | RuntimeException error) {
                try (InputStream ignored = response.body()) { throw error; }
            }
            // Fixed overrides and failed refreshes may return the same token. Keep the original body readable.
            if (!token.equals(refreshedToken)) {
                response.body().close();
                response = send(buildRequest(uri, method, body, options, timeout, requestId, refreshedToken));
            }
        }
        logResponse(requestId, response);
        return response;
    }

    private HttpRequest buildRequest(
            URI uri,
            String method,
            String body,
            AnthropicRequestOptions options,
            Duration timeout,
            String requestId,
            String token
    ) throws IOException {
        String effectiveMethod = method == null || method.isBlank() ? "GET" : method;
        Map<String, String> headers = new LinkedHashMap<>();
        if (options.headers() != null) {
            options.headers().forEach((name, value) -> {
                if (PROTECTED_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException(
                            "Cannot override protected Anthropic header: " + name);
                }
                headers.put(name, value);
            });
        }
        headers.put("Authorization", "Bearer " + token);
        headers.put("Accept", "application/json");
        if (body != null) headers.put("Content-Type", "application/json");
        headers.put("anthropic-version", profile.anthropicVersion());
        LinkedHashSet<String> betas = new LinkedHashSet<>();
        if (isMessagesEndpoint(uri)) betas.add(profile.claudeCodeBeta());
        betas.add(profile.oauthBeta());
        betas.addAll(options.clientBetas());
        headers.put("anthropic-beta", String.join(",", betas));
        headers.put("anthropic-dangerous-direct-browser-access", "true");
        headers.put("User-Agent", "AIProxy/1.3.0");
        headers.put("x-app", "AIProxy");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(builder::header);
        if (body == null) {
            builder.method(effectiveMethod, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(
                    effectiveMethod,
                    HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
            );
        }
        requestLogger.logUpstreamRequest(
                requestId, effectiveMethod, uri.getPath(), headers, body);
        return builder.build();
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws IOException {
        try {
            return com.aiproxy.transport.InferenceTransport.send(httpClient, request);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Anthropic request was interrupted", error);
        }
    }

    private boolean isMessagesEndpoint(URI uri) {
        return uri.getPath().equals(profile.messagesUri().getPath());
    }

    private void logResponse(String requestId, HttpResponse<InputStream> response) {
        Map<String, List<String>> headers = response.headers() == null
                ? Map.of()
                : response.headers().map();
        requestLogger.logUpstreamResponse(
                requestId, response.statusCode(), headers, "[streaming body omitted]");
    }
}

package com.aiproxy.provider.codex;

import com.aiproxy.config.ServerConfig;
import com.aiproxy.logging.RequestLogger;
import com.aiproxy.provider.codex.auth.CodexAuthManager;
import com.aiproxy.transport.InferenceTransport;
import com.aiproxy.transport.UrlResolver;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class CodexHttpClient {

    private final HttpClient httpClient;
    private final CodexAuthManager authManager;
    private final String baseUrl;
    private final RequestLogger requestLogger;

    public CodexHttpClient(ServerConfig config, CodexAuthManager authManager) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .followRedirects(authManager.isNative() ? HttpClient.Redirect.NEVER : HttpClient.Redirect.NORMAL)
                .build(), authManager);
    }

    public CodexHttpClient(ServerConfig config, HttpClient httpClient, CodexAuthManager authManager) {
        this.authManager = authManager;
        if (authManager.isNative() && httpClient.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("Native inference must not follow redirects");
        this.baseUrl = config.baseUrl();
        this.httpClient = httpClient;
        this.requestLogger = new RequestLogger(config.fullRequestLogging(), Path.of(config.requestLogDir()));
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }
    public boolean isNative() { return authManager.isNative(); }
    public String credentialIdentity() throws Exception { return authManager.nativeIdentity(); }

    public HttpResponse<InputStream> request(String path, String method, String body,
                                              Map<String, String> extraHeaders) throws Exception {
        return request(path, method, body, extraHeaders, null, null);
    }

    public HttpResponse<InputStream> request(String path, String method, String body,
                                             Map<String, String> extraHeaders,
                                             String requestId,
                                             String promptCacheKey) throws Exception {
        String logRequestId = requestId != null ? requestId : requestLogger.nextRequestId();
        HttpRequest request = buildRequest(path, method, body, extraHeaders, promptCacheKey, logRequestId);
        HttpResponse<InputStream> response = InferenceTransport.send(httpClient, request);
        requestLogger.logUpstreamResponse(logRequestId, response.statusCode(), responseHeaders(response),
                "[streaming body omitted]");
        return response;
    }

    public HttpResponse<String> requestString(String path, String method, String body,
                                               Map<String, String> extraHeaders) throws Exception {
        String requestId = requestLogger.nextRequestId();
        HttpRequest request = buildRequest(path, method, body, extraHeaders, null, requestId);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        requestLogger.logUpstreamResponse(requestId, response.statusCode(), responseHeaders(response), response.body());
        return response;
    }

    private HttpRequest buildRequest(String path, String method, String body,
                                     Map<String, String> extraHeaders,
                                     String promptCacheKey,
                                     String requestId) throws Exception {
        String targetUrl = UrlResolver.resolveTargetUrl(path, baseUrl);
        if (isNative()) {
            if (!"/responses".equals(path) && !"/models".equals(path))
                throw new IllegalArgumentException("Unsupported native OpenAI endpoint");
            targetUrl = com.aiproxy.provider.codex.auth.nativeoauth.NativeOAuth.RESOURCE + path;
        }
        Map<String, String> authHeaders = authManager.getAuthHeaders();
        Map<String, String> loggedHeaders = new LinkedHashMap<>();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(120))
                .uri(URI.create(targetUrl));

        for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
            loggedHeaders.put(entry.getKey(), entry.getValue());
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                if (isNative() && !entry.getKey().equalsIgnoreCase("content-type")
                        && !entry.getKey().equalsIgnoreCase("accept")) continue;
                builder.header(entry.getKey(), entry.getValue());
                loggedHeaders.put(entry.getKey(), entry.getValue());
            }
        }
        if (!isNative() && promptCacheKey != null && !promptCacheKey.isBlank()) {
            builder.header("conversation_id", promptCacheKey);
            builder.header("session_id", promptCacheKey);
            loggedHeaders.put("conversation_id", promptCacheKey);
            loggedHeaders.put("session_id", promptCacheKey);
        }

        if (body != null && !body.isEmpty()) {
            builder.method(method != null ? method : "POST",
                    HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method != null ? method : "GET",
                    HttpRequest.BodyPublishers.noBody());
        }

        requestLogger.logUpstreamRequest(requestId, method != null ? method : "GET", path, loggedHeaders, body);
        return builder.build();
    }

    private static <T> Map<String, java.util.List<String>> responseHeaders(HttpResponse<T> response) {
        return response.headers() == null ? Map.of() : response.headers().map();
    }
}

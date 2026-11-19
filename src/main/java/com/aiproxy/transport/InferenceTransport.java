package com.aiproxy.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/** Authenticated transport with bounded, validated redirects. Request bodies must be repeatable. */
public final class InferenceTransport {
    public enum RedirectPolicy { SAME_ORIGIN, NEVER }
    private static final int MAX_REDIRECTS = 3;
    private InferenceTransport() {}

    /** Policy violations are not connection failures and must not trigger provider failover. */
    public static final class RedirectRejectedException extends IOException {
        private RedirectRejectedException(String reason) { super("Upstream redirect rejected: " + reason); }
    }

    public static HttpResponse<InputStream> send(HttpClient client, HttpRequest request)
            throws IOException, InterruptedException {
        return send(client, request, RedirectPolicy.SAME_ORIGIN);
    }

    public static HttpResponse<InputStream> send(HttpClient client, HttpRequest request, RedirectPolicy policy)
            throws IOException, InterruptedException {
        return send(client, request, Duration.ofSeconds(60), policy);
    }

    static HttpResponse<InputStream> send(HttpClient client, HttpRequest request, Duration readTimeout)
            throws IOException, InterruptedException {
        return send(client, request, readTimeout, RedirectPolicy.SAME_ORIGIN);
    }

    private static HttpResponse<InputStream> send(HttpClient client, HttpRequest original, Duration readTimeout,
                                                  RedirectPolicy policy) throws IOException, InterruptedException {
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new RedirectRejectedException("automatic redirects must be disabled");
        }
        HttpRequest request = original;
        Set<URI> visited = new HashSet<>();
        visited.add(original.uri().normalize());
        long started = System.nanoTime();
        for (int hops = 0; ; hops++) {
            HttpResponse<InputStream> response = sendOnce(client, request, readTimeout);
            int status = response.statusCode();
            if (status < 300 || status >= 400 || status == 304) return response;
            // Closing cancels a redirect body without draining potentially unbounded private data.
            try (InputStream ignored = response.body()) {
                if (policy == RedirectPolicy.NEVER) throw new RedirectRejectedException("disabled for this profile");
                if (hops >= MAX_REDIRECTS) throw new RedirectRejectedException("hop limit exceeded");
                boolean preserveBody = status == 307 || status == 308;
                boolean readOnly = request.method().equals("GET") || request.method().equals("HEAD");
                if (!preserveBody && !(readOnly && (status == 301 || status == 302 || status == 303))) {
                    throw new RedirectRejectedException("status would change request semantics");
                }
                var locations = response.headers().allValues("location");
                if (locations.size() != 1 || locations.getFirst().isBlank()) {
                    throw new RedirectRejectedException("missing or ambiguous location");
                }
                URI target;
                try { target = request.uri().resolve(URI.create(locations.getFirst())).normalize(); }
                catch (IllegalArgumentException error) { throw new RedirectRejectedException("invalid location"); }
                validateDestination(original.uri(), target);
                if (!visited.add(target)) throw new RedirectRejectedException("loop detected");
                // Same origin is mandatory, so authentication and all other request headers stay intact.
                var builder = HttpRequest.newBuilder(request, (name, value) -> true).uri(target);
                if (original.timeout().isPresent()) {
                    Duration remaining = original.timeout().orElseThrow().minusNanos(System.nanoTime() - started);
                    if (remaining.isNegative() || remaining.isZero()) throw new HttpTimeoutException("Upstream request timed out");
                    builder.timeout(remaining);
                }
                request = builder.build();
            }
        }
    }

    public static HttpResponse<String> sendString(HttpClient client, HttpRequest request, RedirectPolicy policy, int maximumBytes)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = send(client, request, policy);
        String body = new String(BoundedBodyReader.read(response, maximumBytes), java.nio.charset.StandardCharsets.UTF_8);
        return new BufferedResponse(response, body);
    }

    private record BufferedResponse(HttpResponse<InputStream> source, String body) implements HttpResponse<String> {
        public int statusCode() { return source.statusCode(); }
        public HttpRequest request() { return source.request(); }
        public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
        public java.net.http.HttpHeaders headers() { return source.headers(); }
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return source.sslSession(); }
        public URI uri() { return source.uri(); }
        public HttpClient.Version version() { return source.version(); }
    }

    private static void validateDestination(URI origin, URI target) throws RedirectRejectedException {
        if (!"https".equalsIgnoreCase(origin.getScheme()) || !"https".equalsIgnoreCase(target.getScheme())
                || origin.getHost() == null || target.getHost() == null
                || !origin.getHost().equalsIgnoreCase(target.getHost()) || port(origin) != port(target)
                || origin.getUserInfo() != null || target.getUserInfo() != null || target.getFragment() != null) {
            throw new RedirectRejectedException("destination is not a same-origin HTTPS URL");
        }
    }

    private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }

    private static HttpResponse<InputStream> sendOnce(HttpClient client, HttpRequest request, Duration readTimeout)
            throws IOException, InterruptedException {
        try {
            return client.send(request, info -> HttpResponse.BodySubscribers.mapping(
                    HttpResponse.BodySubscribers.ofInputStream(), input -> new ReadIdleTimeoutInputStream(input, readTimeout)));
        } catch (IOException error) {
            if (error instanceof HttpTimeoutException || error instanceof InterruptedIOException
                    || Thread.currentThread().isInterrupted()) throw error;
            ConnectException failure = new ConnectException("Upstream connection failed");
            failure.initCause(error);
            throw failure;
        }
    }
}

package com.aiproxy.provider.anthropic;

import com.aiproxy.logging.RequestLogger;
import com.aiproxy.provider.anthropic.auth.AnthropicAuthManager;
import com.aiproxy.provider.anthropic.auth.AnthropicCredential;
import com.aiproxy.provider.anthropic.auth.AnthropicCredentialStore;
import com.aiproxy.provider.anthropic.auth.AnthropicOAuthClient;
import com.aiproxy.transport.BoundedBodyReader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnthropicHttpClientTest {
    private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");

    @TempDir
    Path temporary;

    @Test
    void addsExactOauthCompatibilityHeadersWithoutApiKey() throws Exception {
        AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/messages", exchange -> {
            headers.set(exchange.getRequestHeaders());
            respond(exchange, 200, "{}");
        });
        server.start();
        try (Fixture fixture = fixture(server, "override-token")) {
            var response = fixture.transport.request(
                    fixture.profile.messagesUri(), "POST", "{}", Map.of("content-type", "application/json"));
            BoundedBodyReader.read(response, 1024);

            assertEquals("Bearer override-token", first(headers.get(), "Authorization"));
            assertEquals("application/json", first(headers.get(), "Content-Type"));
            assertEquals("2023-06-01", first(headers.get(), "anthropic-version"));
            assertEquals(
                    "claude-code-20250219,oauth-2025-04-20",
                    first(headers.get(), "anthropic-beta")
            );
            assertEquals("true", first(headers.get(), "anthropic-dangerous-direct-browser-access"));
            assertEquals("AIProxy", first(headers.get(), "x-app"));
            assertFalse(headers.get().containsKey("x-api-key"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refreshesAndRetriesExactlyOnceOnPreBody401() throws Exception {
        AtomicInteger messageCalls = new AtomicInteger();
        List<String> authorizations = new ArrayList<>();
        HttpServer server = server();
        server.createContext("/messages", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int call = messageCalls.incrementAndGet();
            respond(exchange, call == 1 ? 401 : 200, call == 1 ? """
                    {"type":"error","error":{"type":"authentication_error","message":"expired"}}
                    """ : "{}");
        });
        server.createContext("/token", exchange -> respond(exchange, 200, """
                {"access_token":"new-access","refresh_token":"rotated","expires_in":3600}
                """));
        server.start();

        AnthropicCompatibilityProfile profile = profile(server);
        try (AnthropicCredentialStore store =
                     AnthropicCredentialStore.open(temporary.resolve("credential.json"))) {
            store.save(new AnthropicCredential(
                    "old-access", "refresh", NOW.plusSeconds(3600), NOW));
            HttpClient http = HttpClient.newHttpClient();
            AnthropicAuthManager auth = new AnthropicAuthManager(
                    store,
                    new AnthropicOAuthClient(profile, http),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    (String) null
            );
            AnthropicHttpClient transport = new AnthropicHttpClient(
                    profile, http, auth, new RequestLogger(false, temporary.resolve("logs")));

            var response = transport.request(profile.messagesUri(), "POST", "{}", Map.of());
            BoundedBodyReader.read(response, 1024);

            assertEquals(200, response.statusCode());
            assertEquals(List.of("Bearer old-access", "Bearer new-access"), authorizations);
            assertEquals(2, messageCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsCallerOverridesOfAuthenticationHeaders() throws Exception {
        HttpServer server = server();
        server.createContext("/messages", exchange -> respond(exchange, 200, "{}"));
        server.start();
        try (Fixture fixture = fixture(server, "override-token")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.transport.request(
                            fixture.profile.messagesUri(),
                            "GET",
                            null,
                            Map.of("Authorization", "Bearer attacker-controlled")
                    )
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fixedToken401IsReturnedWithoutDuplicateRequest() throws Exception {
        var calls = new AtomicInteger();
        var server = server();
        server.createContext("/messages", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 401, "original authentication explanation");
        });
        server.start();
        try (var fixture = fixture(server, "fixed-token")) {
            var response = fixture.transport.request(fixture.profile.messagesUri(), "POST", "{}", Map.of());
            assertEquals(401, response.statusCode());
            assertEquals("original authentication explanation", new String(BoundedBodyReader.read(response, 1024), StandardCharsets.UTF_8));
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void unchangedRefreshedTokenPreservesOriginal401Body() throws Exception {
        var auth = org.mockito.Mockito.mock(AnthropicAuthManager.class);
        org.mockito.Mockito.when(auth.accessToken()).thenReturn("unchanged");
        var http = org.mockito.Mockito.mock(HttpClient.class);
        org.mockito.Mockito.when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        java.net.http.HttpResponse<java.io.InputStream> response = org.mockito.Mockito.mock();
        var body = org.mockito.Mockito.spy(new java.io.ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8)));
        org.mockito.Mockito.when(response.statusCode()).thenReturn(401);
        org.mockito.Mockito.when(response.body()).thenReturn(body);
        org.mockito.Mockito.when(http.send(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                org.mockito.ArgumentMatchers.any(java.net.http.HttpResponse.BodyHandler.class))).thenReturn(response);
        var profile = AnthropicCompatibilityProfile.claudeCodeOAuth();
        var client = new AnthropicHttpClient(profile, http, auth, new RequestLogger(false, temporary));
        org.junit.jupiter.api.Assertions.assertSame(response, client.request(profile.messagesUri(), "POST", "{}", Map.of()));
        org.mockito.Mockito.verify(http, org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(body, org.mockito.Mockito.never()).close();
        assertEquals("original", new String(body.readAllBytes(), StandardCharsets.UTF_8));
        body.close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void refreshFailureClosesBodyAndChangedTokenRetriesAtMostOnce(boolean refreshFails) throws Exception {
        var auth = org.mockito.Mockito.mock(AnthropicAuthManager.class);
        var tokens = org.mockito.Mockito.when(auth.accessToken()).thenReturn("old");
        if (refreshFails) tokens.thenThrow(new java.io.IOException("refresh failed"));
        else tokens.thenReturn("new");
        var http = org.mockito.Mockito.mock(HttpClient.class);
        org.mockito.Mockito.when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        java.net.http.HttpResponse<java.io.InputStream> first = org.mockito.Mockito.mock();
        java.net.http.HttpResponse<java.io.InputStream> second = org.mockito.Mockito.mock();
        var firstBody = org.mockito.Mockito.mock(java.io.InputStream.class);
        var secondBody = org.mockito.Mockito.mock(java.io.InputStream.class);
        org.mockito.Mockito.when(first.statusCode()).thenReturn(401);
        org.mockito.Mockito.when(first.body()).thenReturn(firstBody);
        org.mockito.Mockito.when(second.statusCode()).thenReturn(401);
        org.mockito.Mockito.when(second.body()).thenReturn(secondBody);
        org.mockito.Mockito.when(http.send(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                org.mockito.ArgumentMatchers.any(java.net.http.HttpResponse.BodyHandler.class))).thenReturn(first, second);
        var profile = AnthropicCompatibilityProfile.claudeCodeOAuth();
        var client = new AnthropicHttpClient(profile, http, auth, new RequestLogger(false, temporary));
        if (refreshFails) {
            assertThrows(java.io.IOException.class, () -> client.request(profile.messagesUri(), "POST", "{}", Map.of()));
        } else {
            org.junit.jupiter.api.Assertions.assertSame(second, client.request(profile.messagesUri(), "POST", "{}", Map.of()));
            org.mockito.Mockito.verify(secondBody, org.mockito.Mockito.never()).close();
        }
        var requests = org.mockito.ArgumentCaptor.forClass(java.net.http.HttpRequest.class);
        org.mockito.Mockito.verify(http, org.mockito.Mockito.times(refreshFails ? 1 : 2)).send(requests.capture(), org.mockito.ArgumentMatchers.any());
        assertEquals("Bearer old", requests.getAllValues().getFirst().headers().firstValue("Authorization").orElseThrow());
        if (!refreshFails) assertEquals("Bearer new", requests.getAllValues().getLast().headers().firstValue("Authorization").orElseThrow());
        org.mockito.Mockito.verify(firstBody).close();
        org.mockito.Mockito.verify(auth).invalidate();
    }

    private Fixture fixture(HttpServer server, String override) throws Exception {
        AnthropicCompatibilityProfile profile = profile(server);
        AnthropicCredentialStore store =
                AnthropicCredentialStore.open(temporary.resolve("override.json"));
        HttpClient http = HttpClient.newHttpClient();
        AnthropicAuthManager auth = new AnthropicAuthManager(
                store, new AnthropicOAuthClient(profile, http),
                Clock.fixed(NOW, ZoneOffset.UTC), override);
        return new Fixture(
                profile,
                new AnthropicHttpClient(
                        profile, http, auth, new RequestLogger(false, temporary.resolve("logs"))),
                store
        );
    }

    private static AnthropicCompatibilityProfile profile(HttpServer server) {
        AnthropicCompatibilityProfile source = AnthropicCompatibilityProfile.claudeCodeOAuth();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new AnthropicCompatibilityProfile(
                source.name(), source.clientId(), source.authorizationUri(),
                URI.create(base + "/token"), source.redirectUri(),
                URI.create(base + "/messages"), URI.create(base + "/models?limit=100"),
                source.scopes(), source.anthropicVersion(), source.oauthBeta(),
                source.claudeCodeBeta(), source.oauthSystemPreamble()
        );
    }

    private static HttpServer server() throws Exception {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static String first(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .findFirst().orElseThrow().getValue().getFirst();
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Fixture(
            AnthropicCompatibilityProfile profile,
            AnthropicHttpClient transport,
            AnthropicCredentialStore store
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            store.close();
        }
    }
}

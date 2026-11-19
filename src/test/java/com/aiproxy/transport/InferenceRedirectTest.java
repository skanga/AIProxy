package com.aiproxy.transport;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InferenceRedirectTest {
    private final HttpClient client = mock(HttpClient.class);

    private HttpRequest request() {
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        return HttpRequest.newBuilder(URI.create("https://provider.example/v1/start"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer test-secret")
                .header("Content-Type", "application/json").header("X-Request-Id", "original-id")
                .POST(HttpRequest.BodyPublishers.ofString("private prompt")).build();
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<InputStream> response(int status, String location) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(location == null ? Map.of() : Map.of("Location", List.of(location)), (a, b) -> true));
        when(response.body()).thenReturn(mock(InputStream.class));
        return response;
    }

    @ParameterizedTest @ValueSource(ints = {307, 308})
    void sameOriginPostPreservesMethodBodyAndCredentials(int status) throws Exception {
        var request = request();
        var redirect = response(status, "../next?route=1");
        var success = response(200, null);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(redirect, success);
        assertSame(success, InferenceTransport.send(client, request));
        var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(requests.capture(), any());
        var redirected = requests.getAllValues().get(1);
        assertEquals(URI.create("https://provider.example/next?route=1"), redirected.uri());
        assertEquals("POST", redirected.method());
        assertEquals(request.headers(), redirected.headers());
        assertEquals("private prompt", body(redirected));
        assertTrue(redirected.timeout().orElseThrow().compareTo(request.timeout().orElseThrow()) <= 0);
        verify(redirect.body()).close();
        verify(success.body(), never()).close();
    }

    @ParameterizedTest @ValueSource(strings = {
            "https://evil.example/steal", "https://provider.example.evil.example/steal",
            "https://127.0.0.1/private", "http://provider.example/downgrade",
            "https://provider.example:8443/port", "https://user:pass@provider.example/path",
            "https://provider.example/path#fragment", "file:///private", "//evil.example/path", "not a URI"
    })
    void unsafeDestinationIsRejectedBeforeSecondSend(String location) throws Exception {
        var request = request();
        var redirect = response(307, location);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(redirect);
        IOException error = assertThrows(IOException.class, () -> InferenceTransport.send(client, request));
        assertFalse(error instanceof java.net.ConnectException, "Policy rejection must not trigger transport failover");
        assertFalse(error.getMessage().contains(location));
        assertFalse(error.getMessage().contains("test-secret"));
        verify(client, times(1)).send(any(HttpRequest.class), any());
        verify(redirect.body()).close();
    }

    @ParameterizedTest @ValueSource(ints = {301, 302, 303})
    void postNeverTurnsIntoGet(int status) throws Exception {
        var request = request();
        var redirect = response(status, "/next");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(redirect);
        assertThrows(IOException.class, () -> InferenceTransport.send(client, request));
        verify(client, times(1)).send(any(HttpRequest.class), any());
        verify(redirect.body()).close();
    }

    @ParameterizedTest @ValueSource(ints = {301, 302, 303, 307, 308})
    void getRedirectsRemainGet(int status) throws Exception {
        var request = HttpRequest.newBuilder(request(), (a, b) -> true).GET().build();
        var redirect = response(status, "https://PROVIDER.example:443/next");
        var success = response(200, null);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(redirect, success);
        assertSame(success, InferenceTransport.send(client, request));
        var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(requests.capture(), any());
        assertEquals("GET", requests.getAllValues().get(1).method());
    }

    @Test void missingLocationAndHttpOriginsAreRejected() throws Exception {
        var request = request();
        var redirect = response(307, null);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(redirect);
        assertThrows(IOException.class, () -> InferenceTransport.send(client, request));
        when(redirect.headers()).thenReturn(HttpHeaders.of(Map.of("Location", List.of("/next")), (a, b) -> true));
        var http = HttpRequest.newBuilder(request, (a, b) -> true).uri(URI.create("http://provider.example/start")).build();
        assertThrows(IOException.class, () -> InferenceTransport.send(client, http));
        verify(client, times(2)).send(any(HttpRequest.class), any());
    }

    @Test void redirectBudgetIsThreeAndEveryIntermediateBodyCloses() throws Exception {
        var request = request();
        var redirects = List.of(response(307, "/one"), response(307, "/two"), response(307, "/three"), response(307, "/four"));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(redirects.get(0), redirects.get(1), redirects.get(2), redirects.get(3));
        assertThrows(IOException.class, () -> InferenceTransport.send(client, request));
        verify(client, times(4)).send(any(HttpRequest.class), any());
        for (var redirect : redirects) verify(redirect.body()).close();
    }

    @Test void automaticRedirectClientCannotBypassValidation() throws Exception {
        var request = request();
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.ALWAYS);
        assertThrows(IOException.class, () -> InferenceTransport.send(client, request));
        verify(client, never()).send(any(HttpRequest.class), any());
    }

    @ParameterizedTest @ValueSource(ints = {200, 304, 422})
    void nonRedirectResponsesAreReturnedUnchanged(int status) throws Exception {
        var request = request();
        var response = response(status, null);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertSame(response, InferenceTransport.send(client, request));
        verify(client, times(1)).send(any(HttpRequest.class), any());
        verify(response.body(), never()).close();
    }

    @Test void laterHopCannotEscapeOrigin() throws Exception {
        var request = request();
        var first = response(307, "/safe");
        var second = response(308, "https://evil.example/private");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(first, second);
        assertThrows(InferenceTransport.RedirectRejectedException.class, () -> InferenceTransport.send(client, request));
        verify(client, times(2)).send(any(HttpRequest.class), any());
        verify(first.body()).close();
        verify(second.body()).close();
    }

    @Test void loopAndAmbiguousLocationsAreRejected() throws Exception {
        var request = request();
        var redirect = response(307, request.uri().toString());
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(redirect);
        assertThrows(InferenceTransport.RedirectRejectedException.class, () -> InferenceTransport.send(client, request));
        when(redirect.headers()).thenReturn(HttpHeaders.of(Map.of("Location", List.of("/one", "/two")), (a, b) -> true));
        assertThrows(InferenceTransport.RedirectRejectedException.class, () -> InferenceTransport.send(client, request));
        verify(client, times(2)).send(any(HttpRequest.class), any());
        verify(redirect.body(), times(2)).close();
    }

    @Test void deadlineIsNotResetByRedirects() throws Exception {
        var request = HttpRequest.newBuilder(request(), (a, b) -> true).timeout(Duration.ofMillis(1)).build();
        var redirect = response(307, "/next");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            Thread.sleep(20);
            return redirect;
        });
        assertThrows(HttpTimeoutException.class, () -> InferenceTransport.send(client, request));
        verify(client, times(1)).send(any(HttpRequest.class), any());
        verify(redirect.body()).close();
    }

    @Test void stricterProfilesRejectEvenSameOriginRedirects() throws Exception {
        var request = request();
        var redirect = response(307, "/next");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(redirect);
        assertThrows(InferenceTransport.RedirectRejectedException.class,
                () -> InferenceTransport.send(client, request, InferenceTransport.RedirectPolicy.NEVER));
        verify(client, times(1)).send(any(HttpRequest.class), any());
        verify(redirect.body()).close();
    }

    private static String body(HttpRequest request) throws Exception {
        var result = new CompletableFuture<String>();
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part); }
            public void onError(Throwable error) { result.completeExceptionally(error); }
            public void onComplete() { result.complete(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)); }
        });
        return result.get(2, TimeUnit.SECONDS);
    }
}

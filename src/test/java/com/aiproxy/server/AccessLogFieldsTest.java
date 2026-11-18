package com.aiproxy.server;

import io.javalin.http.Context;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class AccessLogFieldsTest {
    @Test
    void upstreamErrorExposesOnlySafeCorrelationHeaders() {
        Context context = mock(Context.class);
        HttpResponse<?> response = mock(HttpResponse.class);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(
                "request-id", List.of("req_123"), "set-cookie", List.of("private-cookie")), (a, b) -> true));
        when(response.request()).thenReturn(HttpRequest.newBuilder(URI.create("https://example.com"))
                .header("X-Request-Id", "sent_123").build());

        AccessLogFields.upstreamError(context, response, "upstream_error");

        verify(context).header("X-Upstream-Request-Id", "req_123");
        verify(context).attribute(AccessLogFields.UPSTREAM_REQUEST_ID, "req_123");
        verify(context).attribute(AccessLogFields.UPSTREAM_CLIENT_REQUEST_ID, "sent_123");
        verify(context, never()).header(eq("Set-Cookie"), anyString());
    }

    @Test
    void unsafeOrOversizedUpstreamIdsAreNotReflected() {
        for (String id : List.of("private value with spaces", "x".repeat(201))) {
            Context context = mock(Context.class);
            HttpResponse<?> response = mock(HttpResponse.class);
            when(response.headers()).thenReturn(HttpHeaders.of(Map.of("x-request-id", List.of(id)), (a, b) -> true));

            AccessLogFields.upstreamError(context, response, "upstream_error");

            verify(context, never()).header(eq("X-Upstream-Request-Id"), anyString());
            verify(context, never()).attribute(eq(AccessLogFields.UPSTREAM_REQUEST_ID), anyString());
        }
    }
}

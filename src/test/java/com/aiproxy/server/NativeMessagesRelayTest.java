package com.aiproxy.server;

import com.aiproxy.usage.UsageTracker;
import io.javalin.http.Context;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.http.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeMessagesRelayTest {
    @Test void rejectsOversizedRequestBeforeReadingBody() {
        Context context = mock();
        when(context.header("anthropic-version")).thenReturn("2023-06-01");
        when(context.header("Content-Length")).thenReturn("33554433");
        assertNull(MessagesHttp.readRequest(context, "2023-06-01", mock()));
        verify(context).status(413);
        verify(context, never()).body();
    }

    @Test void reportsFailureBeforeFirstStreamByteAndClosesUpstream() throws Exception {
        Context context = mock();
        HttpServletResponse downstream = mock();
        when(context.res()).thenReturn(downstream);
        when(downstream.getOutputStream()).thenReturn(mock(ServletOutputStream.class));
        InputStream input = mock();
        when(input.read(any(byte[].class))).thenThrow(new IOException("upstream failed"));
        new NativeMessagesRelay(new UsageTracker(), "Copilot").handle(context, response(input), true);
        verify(context).status(502);
        verify(context).result(contains("api_error"));
        verify(input).close();
    }

    @Test void clientDisconnectClosesUpstreamWithoutReplacingCommittedResponse() throws Exception {
        Context context = mock();
        HttpServletResponse downstream = mock();
        when(context.res()).thenReturn(downstream);
        when(downstream.isCommitted()).thenReturn(true);
        ServletOutputStream output = mock();
        when(downstream.getOutputStream()).thenReturn(output);
        doThrow(new IOException("client disconnected")).when(output).write(any(byte[].class));
        InputStream input = spy(new ByteArrayInputStream("event: ping\n\n".getBytes()));
        new NativeMessagesRelay(new UsageTracker(), "Copilot").handle(context, response(input), true);
        verify(input).close();
        verify(context, never()).status(502);
        verify(context, never()).result(anyString());
    }

    @Test void boundsSuccessfulJsonAndClosesOversizedUpstream() throws Exception {
        Context context = mock();
        // Generate the body lazily so the fixture does not allocate a second 64 MiB buffer.
        InputStream input = spy(new InputStream() {
            long remaining = 64L * 1024 * 1024 + 1;
            public int read() { return remaining-- > 0 ? 'x' : -1; }
            public int read(byte[] bytes, int offset, int length) {
                if (remaining <= 0) return -1;
                int count = (int) Math.min(remaining, length);
                remaining -= count;
                return count;
            }
        });
        new NativeMessagesRelay(new UsageTracker(), "Copilot").handle(context, response(input), false);
        verify(context).status(502);
        verify(context).result(contains("too large"));
        verify(input).close();
    }

    private HttpResponse<InputStream> response(InputStream input) {
        HttpResponse<InputStream> response = mock();
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a,b) -> true));
        when(response.body()).thenReturn(input);
        return response;
    }
}
